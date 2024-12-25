package blogblitz

import zio.*
import zio.test.*
import zio.http.*
import zio.logging.backend.SLF4J
import java.time.Instant
// Part 1
object BlogBlitzMachineSpec1 extends ZIOSpecDefault:
  private val QUEUE_CAPACITY = 256

  private val TestTimeout       = 10.seconds
  private val TimestampInterval = BlogBlitzMachine.PERIOD

  private val testTime = "2024-01-01T00:00:00Z"

  private val sinceTimestampGmt = Instant.parse(testTime)

  private val timestampHubLayer = ZLayer.fromZIO(
    Queue.bounded[BlogBlitzMachine.TimestampEvent](QUEUE_CAPACITY)
  )

  def mockWordPressFetch(
    lastModifiedGmt: Instant
  ): Task[List[WordPressApi.BlogPost]] = {
    ZIO.succeed {
      List(
        WordPressApi.BlogPost(
          title = WordPressApi.Title("Sample Post"),
          content = WordPressApi.Content(s"Content last modified: $lastModifiedGmt"),
          id = 1,
          guid = WordPressApi.Guid("https://wptavern.com/?p=140881"),
          link = "https://example.com/post/1",
          publishedDateGmt = WordPressApi.GmtInstant(lastModifiedGmt.minus(1.days)),
          modifiedDateGmt = WordPressApi.GmtInstant(lastModifiedGmt.minus(1.days)),
          importDateTime = lastModifiedGmt.plus(1.days),
          requestUrl = "",
        )
      )
    }
  }

  def compareRoutePatterns(actual: RoutePattern[?], expected: RoutePattern[?]): Boolean = {
    (actual.method == expected.method) &&
    (actual.pathCodec.toString == expected.pathCodec.toString)
  }

  def spec = suite("BlogBlitzMachineSpec")(
    suite("getPathSegments")(
      test("should extract correct segments from valid URLs") {
        assertTrue(
          BlogBlitzMachine.getPathSegments("http://localhost:8888/subscribe/v1") == Some(
            ("subscribe", "v1")
          ),
          BlogBlitzMachine.getPathSegments("http://localhost:9999/subscribe/v1") == Some(
            ("subscribe", "v1")
          ),
        )
      },
      test("should handle path with port placeholder") {
        assertTrue(
          BlogBlitzMachine.getPathSegments("http://localhost:${port}/subscribe/v1") == Some(
            ("subscribe", "v1")
          )
        )
      },
      test("should handle path with invalid segments") {
        assertTrue(
          BlogBlitzMachine.getPathSegments("what?/http://localhost:${port}/subscribe/v1") == None
        )
      },
      test("should handle empty path") {
        assertTrue(
          BlogBlitzMachine.getPathSegments("${port}") == None
        )
      },
    ),
    suite("extractPathSegments")(
      test("should make correct route for valid segments") {
        assertTrue(
          compareRoutePatterns(
            BlogBlitzMachine.makeSocketRoute(("subscribe", "v1")),
            Method.GET / "subscribe" / "v1",
          )
        )
      },
      test("should make invalid route for invalid segments") {
        assertTrue(
          compareRoutePatterns(
            BlogBlitzMachine.makeSocketRoute(("a", "")),
            BlogBlitzMachine.INVALID_ROUTE,
          )
        )
        assertTrue(
          compareRoutePatterns(
            BlogBlitzMachine.makeSocketRoute(("", "b")),
            BlogBlitzMachine.INVALID_ROUTE,
          )
        )
        assertTrue(
          compareRoutePatterns(
            BlogBlitzMachine.makeSocketRoute(),
            BlogBlitzMachine.INVALID_ROUTE,
          )
        )
      },
    ),
    suite("timeStampEmitter")(
      test("publishes periodic timestamp events to the hub") {
        for {
          queue           <- ZIO.service[Queue[BlogBlitzMachine.TimestampEvent]]
          crawlMetaData   <- ZIO.service[BlogPostMeta.CrawlMetadata]
          schedulerConfig <- ZIO.service[BlogBlitzConfig.SchedulerConfig]

          log <- ZIO.logInfo(
            f"Starting test with scheduler config: $schedulerConfig"
          )
          laterTime = sinceTimestampGmt.plusSeconds(3600)

          _ <- TestClock.setTime(sinceTimestampGmt)
          _ <- crawlMetaData.setLastModifiedGmt(sinceTimestampGmt)

          fiber <- BlogBlitzMachine
            .timeStampEmitter(queue, crawlMetaData, schedulerConfig)
            .fork

          // Event 1 happens immediately (without advancing the clock)
          event1 <- queue.take
          _      <- ZIO.logInfo(s"Got event1: ${event1}")

          // Update last fetch time for the next event
          _ <- crawlMetaData.setLastModifiedGmt(laterTime)

          // Event 2 happens after advancing the clock by the interval
          _ <- TestClock.adjust(TimestampInterval)

          event2 <- queue.take
          _      <- ZIO.logInfo(s"Got event2: ${event2}")

          _ <- fiber.interrupt
        } yield assertTrue(
          event1.sinceTimestampGmt.isBefore(event2.sinceTimestampGmt)
        )
      } @@ TestAspect.timeout(TestTimeout),
      test("skips emitting events when crawling is in progress") {
        for {
          queue           <- ZIO.service[Queue[BlogBlitzMachine.TimestampEvent]]
          crawlMetaData   <- ZIO.service[BlogPostMeta.CrawlMetadata]
          schedulerConfig <- ZIO.service[BlogBlitzConfig.SchedulerConfig]

          _ <- TestClock.setTime(sinceTimestampGmt)
          _ <- crawlMetaData.setLastModifiedGmt(sinceTimestampGmt)

          // Set crawling status to true
          _ <- crawlMetaData.setCrawlingStatus(true)

          fiber <- BlogBlitzMachine
            .timeStampEmitter(queue, crawlMetaData, schedulerConfig)
            .fork

          // Even after advancing the time, no events should be emitted
          _ <- TestClock.adjust(TimestampInterval)

          isEmpty <- queue.isEmpty
          _       <- ZIO.logInfo(s"Queue is empty: $isEmpty")

          _ <- fiber.interrupt
        } yield assertTrue(isEmpty)
      } @@ TestAspect.timeout(TestTimeout),
    ),
    suite("performWordPressFetch")(
      test(
        "returns blog post with lastModifiedGmt in content"
      ) {
        for {

          // there's exactly one post in the mock
          posts <- mockWordPressFetch(sinceTimestampGmt)
          post = posts.head
        } yield assertTrue(
          posts.length == 1,
          post.title.rendered == "Sample Post",
          post.content.rendered == s"Content last modified: $sinceTimestampGmt",
        )
      } @@ TestAspect.timeout(TestTimeout)
    ),
  ).provide(
    timestampHubLayer,
    BlogPostMeta.layer,
    BlogBlitzConfig.schedulerLayer,
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j,
  )
