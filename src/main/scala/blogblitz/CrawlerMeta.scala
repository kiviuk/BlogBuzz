package blogblitz

import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit.*

/*
 * Tracks crawl metadata
 */
object CrawlerMeta {
  enum PreviousCrawlState {
    case NotYetCrawled extends PreviousCrawlState
    case Successful    extends PreviousCrawlState
    case Empty         extends PreviousCrawlState
    case Failure       extends PreviousCrawlState

  }

  private case class CrawlState(
    isCrawling: Boolean = false,
    previousCrawlState: PreviousCrawlState = PreviousCrawlState.NotYetCrawled)

  trait CrawlMetaDataService {
    // tracks the most recent blog post modification time of the current crawl
    def getLastModifiedGmt: UIO[Instant]
    def setLastModifiedGmt(time: Instant): UIO[Unit]
    // notifies that crawling is underway
    def activateCrawling: UIO[Unit]
    def deactivateCrawling: UIO[Unit]
    // query whether crawling is in progress
    def isCrawling: UIO[Boolean]
    // tracks the latest crawl state
    def setPreviousCrawlState(previousCrawlState: PreviousCrawlState): UIO[Unit]
    def getPreviousCrawlState: UIO[PreviousCrawlState]

  }

  private class CrawlMetaDataRepository(
    crawlStateRef: Ref[CrawlState],
    lastModifiedGmtRef: Ref[Instant])
      extends CrawlMetaDataService {
    def getLastModifiedGmt: UIO[Instant] = lastModifiedGmtRef.get
    def setLastModifiedGmt(time: Instant): UIO[Unit] =
      ZIO.when(time.isAfter(Instant.now()))(
        ZIO.dieMessage(s"Cannot set a future timestamp $time")
      ) *>
        lastModifiedGmtRef.set(time)

    private def setCrawlingStatus(isCrawling: Boolean): UIO[Unit] =
      crawlStateRef.update(_.copy(isCrawling = isCrawling))

    // shortcuts for activating and deactivating crawling
    def activateCrawling: UIO[Unit]   = setCrawlingStatus(true)
    def deactivateCrawling: UIO[Unit] = setCrawlingStatus(false)

    def isCrawling: UIO[Boolean] = crawlStateRef.get.map(_.isCrawling)

    def setPreviousCrawlState(previousCrawlState: PreviousCrawlState): UIO[Unit] =
      crawlStateRef.update(_.copy(previousCrawlState = previousCrawlState))

    def getPreviousCrawlState: UIO[PreviousCrawlState] =
      crawlStateRef.get.map(_.previousCrawlState)

  }

  val layer: ZLayer[BlogBlitzConfig.SchedulerConfig, Nothing, CrawlMetaDataService] = {
    ZLayer.fromZIO(
      for schedulerConfig <- ZIO.service[BlogBlitzConfig.SchedulerConfig]
      crawlStateRef       <- Ref.make[CrawlState](CrawlState())
      lastModifiedGmtRef  <- Ref.make[Instant](schedulerConfig.startDateGmt)
      yield new CrawlMetaDataRepository(
        crawlStateRef,
        lastModifiedGmtRef,
      )
    )
  }

}
