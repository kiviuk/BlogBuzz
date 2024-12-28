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
          meta          <- ZIO.service[CrawlerMeta.CrawlMetadata]
          initialTime   <- meta.getLastModifiedGmt
          initialStatus <- meta.isCrawling
        } yield assertTrue(
          // set a time barrier 2 days in the past for fetching, to avoid too many requests
          initialTime.isBefore(Instant.now()),
          initialStatus == false,
        )
      },
      test("should update last fetched time") {
        for {
          meta <- ZIO.service[CrawlerMeta.CrawlMetadata]
          now = Instant.now()
          _       <- meta.setLastModifiedGmt(now)
          updated <- meta.getLastModifiedGmt
        } yield assertTrue(updated == now)
      },
      test("should update crawling status") {
        for {
          meta   <- ZIO.service[CrawlerMeta.CrawlMetadata]
          _      <- meta.activateCrawling
          status <- meta.isCrawling
        } yield assertTrue(status == true)
      },
      test("should handle multiple updates atomically") {
        for {
          meta <- ZIO.service[CrawlerMeta.CrawlMetadata]
          now = Instant.now()
          fiber1  <- meta.setLastModifiedGmt(now).fork
          fiber2  <- meta.activateCrawling.fork
          fiber3  <- meta.deactivateCrawling.fork
          _       <- fiber1.join
          _       <- fiber2.join
          _       <- fiber3.join
          time    <- meta.getLastModifiedGmt
          status1 <- meta.isCrawling
          status2 <- meta.isCrawling
        } yield assertTrue(
          time == now,
          status1 == false,
          status2 == false,
        )
      },
      test("should maintain independent state for time and status") {
        for {
          meta <- ZIO.service[CrawlerMeta.CrawlMetadata]
          time1 = Instant.now().minusSeconds(3600)
          _ <- meta.setLastModifiedGmt(time1)
          _ <- meta.activateCrawling
          time2 = time1.plusSeconds(60)
          _           <- meta.setLastModifiedGmt(time2)
          finalTime   <- meta.getLastModifiedGmt
          finalStatus <- meta.isCrawling
        } yield assertTrue(
          finalTime == time2,
          finalStatus == true,
        )
      },
      test("should throw an error when setting a future timestamp") {
        for {
          meta <- ZIO.service[CrawlerMeta.CrawlMetadata]
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
    )
  ).provide(
    CrawlerMeta.layer,
    BlogBlitzConfig.schedulerLayer,
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j,
  )

}
