package blogblitz

import zio.*
import zio.test.*

import java.time.Instant
import java.time.temporal.ChronoUnit._
import zio.logging.backend.SLF4J

object BlogPostMetaSpec extends ZIOSpecDefault {
  def spec = suite("BlogPostMetaSpec")(
    suite("CrawlMetadata")(
      test("should start with epoch time and not crawling") {
        for {
          meta          <- ZIO.service[BlogPostMeta.CrawlMetadata]
          initialTime   <- meta.getLastModifiedGmt
          initialStatus <- meta.getCrawlStatus
        } yield assertTrue(
          // set a time barrier 2 days in the past for fetching, to avoid too many requests
          initialTime.isBefore(Instant.now()),
          initialStatus == BlogPostMeta.CrawlState(false),
        )
      },
      test("should update last fetched time") {
        for {
          meta <- ZIO.service[BlogPostMeta.CrawlMetadata]
          now = Instant.now()
          _       <- meta.setLastModifiedGmt(now)
          updated <- meta.getLastModifiedGmt
        } yield assertTrue(updated == now)
      },
      test("should update crawling status") {
        for {
          meta   <- ZIO.service[BlogPostMeta.CrawlMetadata]
          _      <- meta.setCrawlingStatus(true)
          status <- meta.getCrawlStatus
        } yield assertTrue(status == BlogPostMeta.CrawlState(true))
      },
      test("should update crawling size") {
        for {
          meta <- ZIO.service[BlogPostMeta.CrawlMetadata]
          _    <- meta.setCrawlSize(10)
          size <- meta.getCrawlSize
        } yield assertTrue(size.crawlSize == 10)
      },
      test("should handle multiple updates atomically") {
        for {
          meta <- ZIO.service[BlogPostMeta.CrawlMetadata]
          now = Instant.now()
          fiber1  <- meta.setLastModifiedGmt(now).fork
          fiber2  <- meta.setCrawlingStatus(true).fork
          fiber3  <- meta.setCrawlingStatus(false).fork
          _       <- fiber1.join
          _       <- fiber2.join
          _       <- fiber3.join
          time    <- meta.getLastModifiedGmt
          status1 <- meta.getCrawlStatus
          status2 <- meta.getCrawlStatus
        } yield assertTrue(
          time == now,
          status1 == BlogPostMeta.CrawlState(false),
          status2 == BlogPostMeta.CrawlState(false),
        )
      },
      test("should maintain independent state for time and status") {
        for {
          meta <- ZIO.service[BlogPostMeta.CrawlMetadata]
          time1 = Instant.now().minusSeconds(3600)
          _ <- meta.setLastModifiedGmt(time1)
          _ <- meta.setCrawlingStatus(true)
          time2 = time1.plusSeconds(60)
          _           <- meta.setLastModifiedGmt(time2)
          finalTime   <- meta.getLastModifiedGmt
          finalStatus <- meta.getCrawlStatus
        } yield assertTrue(
          finalTime == time2,
          finalStatus == BlogPostMeta.CrawlState(true),
        )
      },
      test("should throw an error when setting a future timestamp") {
        for {
          meta <- ZIO.service[BlogPostMeta.CrawlMetadata]
          pastTime = Instant.now().minusSeconds(3600)
          _ <- meta.setLastModifiedGmt(pastTime)
          futureTime = Instant.now().plusSeconds(3600)
          result <- meta
            .setLastModifiedGmt(futureTime)
            .exit
          finalTime <- meta.getLastModifiedGmt
        } yield assertTrue( // all must be true
          result.isFailure,
          result
            .causeOption
            .exists(cause =>
              cause.dieOption.exists { throwable =>
                throwable.getMessage == s"Cannot set a future timestamp $futureTime"
              }
            ),
          finalTime == pastTime,
        )
      },
      test("should throw an error when setting negative size") {
        for {
          meta <- ZIO.service[BlogPostMeta.CrawlMetadata]
          initialSize = 10
          _ <- meta.setCrawlSize(initialSize)
          negativeSize = -1
          result    <- meta.setCrawlSize(negativeSize).exit
          finalSize <- meta.getCrawlSize
        } yield assertTrue( // all must be true
          result.isFailure,
          result
            .causeOption
            .exists(cause =>
              cause.dieOption.exists { throwable =>
                throwable.getMessage == s"Crawl size $negativeSize cannot be negative"
              }
            ),
          finalSize.crawlSize == initialSize,
        )
      },
    )
  ).provide(
    BlogPostMeta.layer,
    BlogBlitzConfig.schedulerLayer,
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j,
  )

}
