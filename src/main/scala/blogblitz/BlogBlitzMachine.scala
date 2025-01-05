package blogblitz

import zio.*
import zio.http.ChannelEvent.Read
import zio.http.*
import zio.logging.backend.SLF4J
import zio.stream.ZStream

import java.time.Instant

import blogblitz.BlogBlitzConfig.CrawlerConfig
import blogblitz.BlogBlitzConfig.WebSocketConfig

/*
 * Core application
 */
object BlogBlitzMachine extends ZIOAppDefault {
  val QUEUE_CAPACITY = 100

  val PATH_SEPARATOR = "/"

  private val STATIC_URL_PATH = Path.empty / "static"

  private val RESOURCES_WEBAPP_PATH = "webapp"

  val INVALID_ROUTE: RoutePattern[Unit] = Method.GET / "invalid" / "path" / "v2"

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
        channel.send(Read(message)).ignore
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

      // run both streams parallel
      messageHandler.race(blogPostSubscriber)
    }
  }

  // Creates single ZIO route for the WebSocket server
  def makeSocketRoute(segments: (String, String) = ("", "")): RoutePattern[Unit] = {
    val cleanSegments = (
      Option(segments._1).getOrElse("").trim,
      Option(segments._2).getOrElse("").trim,
    )
    (cleanSegments._1, cleanSegments._2) match {
      case ("", "") | (_, "") | ("", _) =>
        INVALID_ROUTE
      case (segment, apiVersion) =>
        Method.GET / segment / apiVersion
    }
  }

  // Extracts path segments from the configuration
  // path: "ws://localhost:${port}/subscribe/v2"
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

    val routes = getPathSegments(config.subscribePath.value) match {
      case Some((segment, apiVersion)) =>
        Routes(
          makeSocketRoute((segment, apiVersion)) -> handler(
            createSocketHandlerAndBindStream(newBlogPostStream(blogPostHub)).toResponse
          )
        )
      case _ => // invalid path
        throw new IllegalArgumentException(
          s"Invalid subscribe path: ${config.subscribePath.value}"
        )
    }

    // e.g.: serve http://host:port/static/blogbuzz.html from resources/webapp
    (routes @@ Middleware.serveResources(STATIC_URL_PATH, RESOURCES_WEBAPP_PATH)).sandbox
  }

  // Starts the WebSocket server
  def startServer(config: WebSocketConfig, blogPostHub: Hub[WordPressApi.BlogPost])
    : ZIO[Any, Throwable, Nothing] = {
    val serverConfig = Server.Config.default.port(config.port.value)

    for {
      _ <- ZIO.logInfo(
        s"WebSocket server ready at ${config.subscribePath}"
          .replace("${port}", config.port.value.toString)
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
    crawlMetaData: CrawlerMeta.CrawlMetaDataService,
    schedulerConfig: BlogBlitzConfig.SchedulerConfig,
  ): ZIO[Any, Nothing, Unit] = {

    (for {
      // Keep the queue free from noise.
      // Only submit events, if crawler is free.
      // todo: replace with ZIO CyclicBarrier
      isCrawling <- crawlMetaData.isCrawling

      _ <- ZIO.unless(isCrawling) {
        for {
          // Emit next timestamp event
          mostRecentBlogPostDate <- crawlMetaData.getLastModifiedGmt
          _                      <- eventQueue.offer(TimestampEvent(mostRecentBlogPostDate))
          _ <- ZIO.logInfo(
            s"Emitting next timestamp event: $mostRecentBlogPostDate"
          )
        } yield ()
      }

      previousCrawlState <- crawlMetaData.getPreviousCrawlState

      // back off strategy, better use ZIO scheduler?
      lowEffortKindOfWorkingBackOffStrategy = CrawlStateEvaluator.unsuccessful(previousCrawlState)

      maxCoolDownScale = schedulerConfig.maxCoolDownScale
      cooldownScale    = if (lowEffortKindOfWorkingBackOffStrategy) maxCoolDownScale else 1
      pauseDuration    = schedulerConfig.toDuration.multipliedBy(cooldownScale)
      _ <- ZIO.when(lowEffortKindOfWorkingBackOffStrategy)(
        ZIO.logInfo("Entering cooldown mode since no new data has been crawled.")
      )

      _ <- ZIO.sleep(pauseDuration).unit
    } yield ()).forever

  }

  // Listens for timestamp events (heartbeat) and
  // fetches blog posts starting from that timestamp.
  // To guarantee instant delivery, posts are forwarded to
  // the outbound websocket hub by the crawler service.
  def timestampListener(
    queue: Queue[TimestampEvent],
    publishingBlogPostHub: Hub[WordPressApi.BlogPost],
    crawlMetaData: CrawlerMeta.CrawlMetaDataService,
    crawlerService: CrawlerService,
  ): ZIO[Client & CrawlerConfig, Nothing, Unit] = {
    import WordPressApi.BlogPost
    import CrawlerMeta._

    (for event <- queue.take

    _ <- ZIO.logInfo(
      s"Processing next timestamp event: ${event.sinceTimestampGmt}"
    )

    // Turn on crawling mode (so others will know when crawling is in progress)
    // but: Instead of being able to reason about your program locally,
    // we have to consider the program holistically.Timing, etc.
    _ <- crawlMetaData.activateCrawling

    // Delegate work to the CrawlerService
    posts <- crawlerService
      .fetchAndPublishPostsSinceGmt(
        event.sinceTimestampGmt,
        publishingBlogPostHub,
      )
      .foldZIO(
        error =>
          ZIO
            .logError(
              s"WordPress fetch failed: $error for event: ${event.sinceTimestampGmt}"
            ) *>
            crawlMetaData
              .setPreviousCrawlState(PreviousCrawlState.Failure)
              .as(List.empty[BlogPost]),
        posts =>
          (if (posts.isEmpty)
             crawlMetaData.setPreviousCrawlState(PreviousCrawlState.Empty)
           else
             crawlMetaData.setPreviousCrawlState(
               PreviousCrawlState.Successful
             )).as(posts),
      )

    _ <- ZIO.logInfo(
      s"Received ${posts.size} blog posts for event: ${event.sinceTimestampGmt}"
    )

    previousCrawlState <- crawlMetaData.getPreviousCrawlState

    _ <- ZIO.when(CrawlStateEvaluator.unsuccessful(previousCrawlState))(
      publishingBlogPostHub.publish(WordPressApi.pingPost()) *>
        ZIO.logInfo(
          s"Sending ping post instead of unsuccessful crawl for event: ${event.sinceTimestampGmt}"
        )
    )

    // At this point, the crawler has collected all
    // blog posts for the given timestamp,
    // the most recent determines the next event timestamp
    lastModifiedGmt = posts
      .map(_.modifiedDateGmt.value)
      .maxOption
      .getOrElse(event.sinceTimestampGmt)

    // Update crawl statuses
    _ <- crawlMetaData.setLastModifiedGmt(lastModifiedGmt)
    _ <- crawlMetaData.deactivateCrawling
    _ <- ZIO.logInfo(
      s"Tracking 'most recent blog post': $lastModifiedGmt"
    )
    yield ()).forever
  }

  // evaluates the state of the previous crawl to determine if it was successful or not.
  private object CrawlStateEvaluator {
    import CrawlerMeta._

    private def successful(state: PreviousCrawlState): Boolean =
      state == PreviousCrawlState.Successful || state == PreviousCrawlState.NotYetCrawled

    def unsuccessful(state: PreviousCrawlState): Boolean = !successful(state)

  }

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Unit] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  override def run: URIO[Any, ExitCode] = {

    // TODO: automatically wait for some websocket client to connect before running the crawler?
    // and stop crawler if no client is connected
    // https://zio.dev/zio-http/examples/websocket/
    // https://github.com/scala-steward/zio-http/blob/d0c229d0beb857075e98597fbadbd33d4f07a917/zio-http-testkit/src/test/scala/zio/http/SocketContractSpec.scala#L142
    val program = for {
      timestampQueue  <- Queue.bounded[TimestampEvent](QUEUE_CAPACITY)
      blogPostHub     <- Hub.bounded[WordPressApi.BlogPost](QUEUE_CAPACITY)
      crawlMetaData   <- ZIO.service[CrawlerMeta.CrawlMetaDataService]
      schedulerConfig <- ZIO.service[BlogBlitzConfig.SchedulerConfig]
      crawlerConfig   <- ZIO.service[BlogBlitzConfig.CrawlerConfig]
      wsConfig        <- ZIO.service[BlogBlitzConfig.WebSocketConfig]
      crawlerService  <- ZIO.service[CrawlerService]

      _ <- ZIO.logInfo("Starting blog buzz...")
      _ <- ZIO.logInfo(s"Scheduler config: $schedulerConfig")
      _ <- ZIO.logInfo(s"Crawler config: $crawlerConfig")

      // Start WebSocket server
      serverFiber <- startServer(wsConfig, blogPostHub).fork
      _           <- ZIO.logInfo("WebSocket server is running")
      _ <- ZIO.logInfo(
        s"Test: wscat -c ws://localhost:${wsConfig.port.value}/subscribe/v2"
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
        CrawlerMeta.layer,
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
        case _ =>
          Console.printLineError(
            "System failed with unknown error. Please check logs for more details."
          )
      }
      .exitCode
  }

}
