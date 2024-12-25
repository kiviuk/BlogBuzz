package blogblitz

import blogblitz.BlogBlitzConfig.CrawlerConfig
import blogblitz.BlogBlitzConfig.WebSocketConfig
import zio.*
import zio.http.ChannelEvent.Read
import zio.http.*
import zio.logging.backend.SLF4J
import zio.stream.ZStream

import java.time.Instant

/*
 * Core application
 */
object BlogBlitzMachine extends ZIOAppDefault {
  val QUEUE_CAPACITY = 100

  val PATH_SEPARATOR = "/"

  val RESOURCES_PATH = Path.empty / "static"

  val RESOURCES_WEBAPP_PATH = "webapp"

  val INVALID_ROUTE = Method.GET / "invalid" / "path" / "v1"

  case class CrawlState(isCrawling: Boolean)
  case class TimestampEvent(sinceTimestampGmt: Instant)

  // Create a stream from the blog post hub, websocket clients will receive messages from this stream
  def newBlogPostStream(blogPostHub: Hub[WordPressApi.BlogPost])
    : ZStream[Any, Throwable, WebSocketFrame] = {

    ZStream
      .fromHub(blogPostHub)
      .map { blogPost =>
        WebSocketFrame.text(blogPost.withSortedWordCountMap.toJson)
      }
  }

  // Create a WebSocket server and bind the blog post stream
  def createSocketHandlerAndBindStream(
    blogPostSocketStream: ZStream[Any, Throwable, WebSocketFrame]
  ): WebSocketApp[Any] = {
    Handler.webSocket { channel =>
      // Forward blog posts to WebSocket clients
      val blogPostSubscriber = blogPostSocketStream.foreach { message =>
        channel.send(Read(message)).catchAll(_ => ZIO.unit)
      }

      // Handle incoming WebSocket messages
      val messageHandler = channel.receiveAll {
        case Read(WebSocketFrame.Text("bye!")) =>
          channel.shutdown
        case Read(WebSocketFrame.Text("subscribe")) =>
          channel.send(Read(WebSocketFrame.text("Subscribed to blog posts")))
        case Read(WebSocketFrame.Text(msg)) =>
          channel.send(Read(WebSocketFrame.text(s"Received: $msg")))
        case _ =>
          ZIO.unit
      }

      // run both streams parallelly
      messageHandler.race(blogPostSubscriber)
    }
  }

  // Creates single ZIO route for the WebSocket server
  def makeSocketRoute(segments: (String, String) = ("", "")) = {
    val cleanSegments = (
      Option(segments._1).getOrElse("").trim,
      Option(segments._2).getOrElse("").trim,
    )
    (cleanSegments._1, cleanSegments._2) match
      case ("", "") | (_, "") | ("", _) =>
        INVALID_ROUTE
      case (segment, apiVersion) =>
        Method.GET / segment / apiVersion
  }

  // Extracts path segments from the configuration
  // path: "ws://localhost:${port}/subscribe/v1"
  def getPathSegments(path: String): Option[(String, String)] = {
    val segments = path.trim.split(PATH_SEPARATOR).toList.filterNot(_.isEmpty)

    segments match {
      case _ :: _ :: segment :: apiVersion :: Nil =>
        Some((segment, apiVersion))
      case _ =>
        None
    }
  }

  // Sets up WebSocket and static web routes
  def routes(
    config: WebSocketConfig,
    blogPostHub: Hub[WordPressApi.BlogPost],
  ): Routes[Any, Response] = {

    val routes = getPathSegments(config.subscribePath.value) match
      case Some((segment, apiVersion)) =>
        Routes(
          makeSocketRoute((segment, apiVersion)) -> handler(
            createSocketHandlerAndBindStream(newBlogPostStream(blogPostHub)).toResponse
          )
        )
      case _ => // invald path
        throw new IllegalArgumentException(
          s"Invalid subscribe path: ${config.subscribePath.value}"
        )

    // e.g.: serve http://host:port/static/blogbuzz.html from the resources/webapp
    (routes @@ Middleware.serveResources(RESOURCES_PATH, RESOURCES_WEBAPP_PATH)).sandbox
  }

  // Starts the WebSocket server
  def startServer(config: WebSocketConfig, blogPostHub: Hub[WordPressApi.BlogPost])
    : ZIO[Any, Throwable, Nothing] = {
    val serverConfig = Server.Config.default.port(config.port.value)

    for {
      _ <- ZIO.logInfo(
        s"Socket server ready at ws://localhost:${config.port.value}/subscribe/v1"
      )
      _ <- ZIO.logInfo(
        s"HTTP server ready at http://localhost:${config.port.value}/static/blogbuzz.html"
      )

      fiber <- Server
        .serve(routes(config, blogPostHub))
        .provide(
          ZLayer.succeed(serverConfig),
          Server.live,
        )
        .forever
    } yield fiber
  }

  // Event queue ensures work items are processed sequentially in the order they are added (emits heartbeat)
  def timeStampEmitter(
    eventQueue: Queue[TimestampEvent],
    crawlMetaData: BlogPostMeta.CrawlMetadata,
    schedulerConfig: BlogBlitzConfig.SchedulerConfig,
  ): ZIO[Any, Nothing, Unit] = {
    val emitTimestamp = (for {
      // Keep the queue free of noisy events.
      // Only submit events, if crawler is free.
      // todo: repl ace with ZIO CyclicBarrier
      state <- crawlMetaData.getCrawlStatus
      _ <- ZIO.unless(state.isCrawling) {
        for {
          // Technically, at any timestamp boundary, a blog item could be fetched twice.
          // Once as the youngest item from the previous batch and again as the oldest item in the current batch.
          // But better to eventually deduplicate data than to miss it.
          mostRecentBlogPostDate <- crawlMetaData.getLastModifiedGmt
          _                      <- eventQueue.offer(TimestampEvent(mostRecentBlogPostDate))
          _ <- ZIO.logInfo(
            s"Emitting next timestamp event: $mostRecentBlogPostDate"
          )
        } yield ()
      }

      // back off strategy based on runtime condition, better use ZIO scheduler?
      size <- crawlMetaData.getCrawlSize
      lowEffortKindOfWorkingBackOffStrategy = size.crawlSize == 0
      coolOff                               = if (lowEffortKindOfWorkingBackOffStrategy) 3 else 1

      _ <- ZIO.when(lowEffortKindOfWorkingBackOffStrategy)(
        ZIO.logInfo("Entering cooldown mode since no new data has been crawled.")
      )

      _ <- ZIO.sleep(schedulerConfig.toDuration.multipliedBy(coolOff)).unit
    } yield ()).forever /* coolOff */

    // emitTimestamp.repeat(Schedule.fixed(schedulerConfig.toDuration)).unit

    emitTimestamp.repeatN(1).unit

    // Repeat emitTimestamp with backoff logic
    // emitTimestamp
    //   .flatMap { coolOff =>
    //     // Choose backoff delay based on crawl size
    //     ZIO.sleep(schedulerConfig.toDuration.multipliedBy(coolOff))
    //   }
    //   .repeat(Schedule.fixed(schedulerConfig.toDuration))
    //   .unit

  }

  // Listens for timestamp events (heartbeat) and
  // fetches blog posts starting from that timestamp.
  // To guarantee instant delivery, posts are forwarded to
  // the outbound websocket hub by the crawler service.
  def timestampListener(
    queue: Queue[TimestampEvent],
    publishingBlogPostHub: Hub[WordPressApi.BlogPost],
    crawlMetaData: BlogPostMeta.CrawlMetadata,
    crawlerService: CrawlerService,
  ): ZIO[Client & CrawlerConfig, Nothing, Unit] = {
    (for
      event <- queue.take
      _ <- ZIO.logInfo(
        s"Processing next timestamp event: ${event.sinceTimestampGmt}"
      )
      // Turn on crawling mode (so others will know when crawling is in progress)
      _ <- crawlMetaData.activateCrawling

      // Call the CrawlerService
      posts <- crawlerService
        .fetchAndPublishPostsSinceGmt(event.sinceTimestampGmt, publishingBlogPostHub)
        .foldZIO(
          error =>
            ZIO.logError(
              s"WordPress fetch failed: ${error} for event: ${event.sinceTimestampGmt}"
            ) *>
              ZIO.succeed(List.empty[WordPressApi.BlogPost]),
          posts => ZIO.succeed(posts),
        )

      _ <- ZIO.logInfo(
        s"Received ${posts.size} blog posts for event: ${event.sinceTimestampGmt}"
      )

      // Send a ping post if there are no posts
      _ <- ZIO.when(posts.isEmpty)(
        publishingBlogPostHub.publish(
          WordPressApi.pingPost
        )
      )

      // At this point, the crawler has collected all
      // blog posts for the given timestamp,
      // the most recent determines the next start timestamp
      lastModifiedGmt = posts
        .map(_.modifiedDateGmt.value)
        .maxOption
        .getOrElse(event.sinceTimestampGmt)

      // Update crawl statuses
      _ <- crawlMetaData.setLastModifiedGmt(lastModifiedGmt)
      _ <- crawlMetaData.deactivateCrawling
      _ <- crawlMetaData.setCrawlSize(posts.size)
      _ <- ZIO.logInfo(
        s"Tracking 'most recent blog post': $lastModifiedGmt"
      )
    yield ()).forever
  }

  override val bootstrap = Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  override def run = {

    // TODO: automatically wait for some websocket client to connect before running the crawler?
    val program = for {
      timestampQueue  <- Queue.bounded[TimestampEvent](QUEUE_CAPACITY)
      blogPostHub     <- Hub.bounded[WordPressApi.BlogPost](QUEUE_CAPACITY)
      crawlMetaData   <- ZIO.service[BlogPostMeta.CrawlMetadata]
      schedulerConfig <- ZIO.service[BlogBlitzConfig.SchedulerConfig]
      crawlerConfig   <- ZIO.service[BlogBlitzConfig.CrawlerConfig]
      crawlerService  <- ZIO.service[CrawlerService]
      wsConfig        <- ZIO.service[WebSocketConfig]

      _ <- ZIO.logInfo("Starting blog blitz...")
      _ <- ZIO.logInfo(s"Scheduler config: $schedulerConfig")
      _ <- ZIO.logInfo(s"Crawler config: $crawlerConfig")

      // Start WebSocket server
      serverFiber <- startServer(wsConfig, blogPostHub).fork
      _           <- ZIO.logInfo("WebSocket server is running")
      _ <- ZIO.logInfo(
        s"Test: wscat -c ws://localhost:${wsConfig.port.value}/subscribe/v1"
      )

      _ <- ZIO.logInfo("Press ENTER to continue...and ENTER again to quit") *> Console.readLine

      emitterFiber <- timeStampEmitter(
        timestampQueue,
        crawlMetaData,
        schedulerConfig,
      ).fork

      listenerFiber <- timestampListener(
        timestampQueue,
        blogPostHub,
        crawlMetaData,
        crawlerService,
      ).fork

      // Wait for user input to terminate
      _ <- ZIO.logInfo("Press ENTER to quit...") *> Console.readLine
      _ <- ZIO.logInfo("Shutting down...")

      // Clean up all fibers
      _ <- emitterFiber.interrupt
      _ <- listenerFiber.interrupt
      _ <- serverFiber.interrupt
      _ <- ZIO.logInfo("All services shut down successfully.")
    } yield ExitCode.success

    program
      .provide(
        BlogPostMeta.layer,
        BlogBlitzConfig.schedulerLayer,
        BlogBlitzConfig.crawlerLayer,
        BlogBlitzConfig.websocketLayer,
        CrawlerService.layer,
        Client.default,
      )
      .tapError {
        case err: Throwable =>
          Console.printLineError(s"System failed with error: $err")
        case err: String =>
          Console.printLineError(s"System failed with error: $err")
      }
      .exitCode
  }

}
