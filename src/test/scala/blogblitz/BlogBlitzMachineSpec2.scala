package blogblitz

import zio.*
import zio.http.*
import zio.test.*
import java.time.Instant
import blogblitz.BlogBlitzConfig.CrawlerConfig
import zio.logging.backend.SLF4J
// Part 2
object BlogBlitzMachineSpec2 extends ZIOSpecDefault:
  java
    .lang
    .System
    .setProperty("logback.configurationFile", "src/test/resources/logback.xml")

  private val QUEUE_CAPACITY = 256

  private val TestTimeout       = 10.seconds
  private val testTime          = "2024-01-01T00:00:00Z"
  private val sinceTimestampGmt = Instant.parse(testTime)

  private val timestampHubLayer = ZLayer.fromZIO(
    Queue.bounded[BlogBlitzMachine.TimestampEvent](QUEUE_CAPACITY)
  )

  private val blogPostHubLayer = ZLayer.fromZIO(
    Hub.bounded[WordPressApi.BlogPost](QUEUE_CAPACITY)
  )

  private def mockfetchAndPublishPostsSinceGmt = new CrawlerService {
    def fetchAndPublishPostsSinceGmt(
      sinceTimestampGmt: Instant,
      publishingBlogPostHub: Hub[WordPressApi.BlogPost],
    ): ZIO[Client & BlogBlitzConfig.CrawlerConfig, Throwable, List[WordPressApi.BlogPost]] = {
      val post = WordPressApi.BlogPost(
        title = WordPressApi.Title("Test Blog Post"),
        content = WordPressApi.Content(s"Test content at $sinceTimestampGmt"),
        id = 1,
        guid = WordPressApi.Guid("https://wptavern.com/?p=140881"),
        link = "https://example.com/post/1",
        publishedDateGmt = WordPressApi.GmtInstant(sinceTimestampGmt.plus(1.days)),
        modifiedDateGmt = WordPressApi.GmtInstant(sinceTimestampGmt.plus(1.days)),
        importDateTime = sinceTimestampGmt.plus(1.days),
        requestUrl = "",
      )

      publishingBlogPostHub.publish(post) *> ZIO.succeed(List(post))

    }

  }

  private def mockEmptyfetchAndPublishPostsSinceGmt = new CrawlerService {
    def fetchAndPublishPostsSinceGmt(
      sinceTimestamp: Instant,
      publishingBlogPostHub: Hub[WordPressApi.BlogPost],
    ): ZIO[Client & BlogBlitzConfig.CrawlerConfig, Throwable, List[WordPressApi.BlogPost]] =
      // Return empty list to simulate no new posts
      ZIO.succeed(List.empty)

  }
  def spec = suite("BlogBlitzMachineSpec2")(
    suite("timestampListener")(
      test("fetches blog posts and updates lastest fetch time") {
        for {

          timestampQueue <- ZIO.service[Queue[BlogBlitzMachine.TimestampEvent]]
          blogPostHub    <- ZIO.service[Hub[WordPressApi.BlogPost]]
          crawlMetaData  <- ZIO.service[CrawlerMeta.CrawlMetadata]

          // Initial crawling status should be false
          initialCrawling <- crawlMetaData.isCrawling
          _               <- ZIO.logInfo(s"Initial crawling status: $initialCrawling")

          subscriber <- blogPostHub.subscribe

          // Start the listener
          fiber <- BlogBlitzMachine
            .timestampListener(
              timestampQueue,
              blogPostHub,
              crawlMetaData,
              mockfetchAndPublishPostsSinceGmt,
            )
            .fork

          // Offer a timestamp event to the queue
          _ <- timestampQueue.offer(
            BlogBlitzMachine.TimestampEvent(sinceTimestampGmt)
          )

          _ <- TestClock.adjust(1.second)

          post <- subscriber.take

          // Verify crawling status was reset
          finalCrawling <- crawlMetaData.isCrawling
          _             <- ZIO.logInfo(s"Final crawling status: $finalCrawling")

          // Get the most recent modified GMT time for the retrieved posts
          lastModifiedGmt <- crawlMetaData.getLastModifiedGmt

          _ <- fiber.interrupt
        } yield assertTrue(
          !initialCrawling,
          !finalCrawling,
          post.title.rendered == "Test Blog Post",
          post.modifiedDateGmt.value.isAfter(sinceTimestampGmt),
          post.importDateTime.isAfter(sinceTimestampGmt),
          lastModifiedGmt.equals(sinceTimestampGmt.plus(1.days)),
        )
      } @@ TestAspect.timeout(TestTimeout),
      test("handles empty post list by keeping original timestamp") {
        for {

          timestampQueue <- ZIO.service[Queue[BlogBlitzMachine.TimestampEvent]]
          blogPostHub    <- ZIO.service[Hub[WordPressApi.BlogPost]]
          crawlMetaData  <- ZIO.service[CrawlerMeta.CrawlMetadata]

          // Initial crawling status should be false
          initialCrawling <- crawlMetaData.isCrawling
          _               <- ZIO.logInfo(s"Initial crawling status: $initialCrawling")

          // Offer a timestamp event to the queue
          timestampEvent = BlogBlitzMachine.TimestampEvent(sinceTimestampGmt)
          _ <- timestampQueue.offer(timestampEvent)

          // Start the listener with the empty mock service
          fiber <- BlogBlitzMachine
            .timestampListener(
              timestampQueue,
              blogPostHub,
              crawlMetaData,
              mockEmptyfetchAndPublishPostsSinceGmt,
            )
            .fork

          _ <- TestClock.adjust(1.second)

          // Get the most recent modified GMT time for the retrieved posts
          lastModifiedGmt <- crawlMetaData.getLastModifiedGmt

          // Verify crawling status was reset
          finalCrawling <- crawlMetaData.isCrawling
          _             <- ZIO.logInfo(s"Final crawling status: $finalCrawling")

          _ <- fiber.interrupt
        } yield assertTrue(
          !initialCrawling,
          !finalCrawling,
          // Key assertion: when no posts are found,
          // lastModifiedGmt should equal the original timestamp
          lastModifiedGmt == sinceTimestampGmt,
        )
      } @@ TestAspect.timeout(TestTimeout),
    )
  ).provide(
    timestampHubLayer,
    CrawlerMeta.layer,
    blogPostHubLayer,
    BlogBlitzConfig.crawlerLayer,
    BlogBlitzConfig.schedulerLayer,
    Client.default,
    Scope.default,
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j,
  )
