package blogblitz

import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit.*

/*
 * Tracks crawl metadata
 */
object CrawlerMeta {
  object CrawlStateEvaluator {
    def successful(state: PreviousCrawlState): Boolean =
      state == PreviousCrawlState.Successful || state == PreviousCrawlState.NotYetCrawled
    def unsuccessful(state: PreviousCrawlState): Boolean = !successful(state)

  }
  enum PreviousCrawlState {
    case NotYetCrawled extends PreviousCrawlState
    case Successful    extends PreviousCrawlState
    case Empty         extends PreviousCrawlState
    case Failure       extends PreviousCrawlState

  }

  case class CrawlState(
    val isCrawling: Boolean,
    val previousCrawlState: PreviousCrawlState = PreviousCrawlState.NotYetCrawled)

  trait CrawlMetadata {
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

  class CrawlMetadataRepository(
    crawlStateRef: Ref[CrawlState],
    lastModifiedGmtRef: Ref[Instant])
      extends CrawlMetadata {
    def getLastModifiedGmt: UIO[Instant] = lastModifiedGmtRef.get
    def setLastModifiedGmt(time: Instant): UIO[Unit] =
      ZIO.when(time.isAfter(Instant.now()))(
        ZIO.dieMessage(s"Cannot set a future timestamp $time")
      ) *>
        lastModifiedGmtRef.set(time)

    def getCrawlState: UIO[CrawlState] = crawlStateRef.get
    def setCrawlingStatus(isCrawling: Boolean): UIO[Unit] =
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

  val layer: ZLayer[BlogBlitzConfig.SchedulerConfig, Nothing, CrawlMetadata] =
    ZLayer.fromZIO(
      for
        schedulerConfig    <- ZIO.service[BlogBlitzConfig.SchedulerConfig]
        crawlStateRef      <- Ref.make[CrawlState](CrawlState(false))
        lastModifiedGmtRef <- Ref.make[Instant](schedulerConfig.startDateGmt)
      yield new CrawlMetadataRepository(
        crawlStateRef,
        lastModifiedGmtRef,
      )
    )

}
