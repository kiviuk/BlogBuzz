package blogblitz

import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit.*

/*
 * Tracks crawl metadata
 */
object BlogPostMeta:
  trait CrawlMetadata:
    def getLastModifiedGmt: UIO[Instant]
    // tracks the most recent blog post modification time of the current crawl
    def setLastModifiedGmt(time: Instant): UIO[Unit]
    // notifies that crawling is underway
    def getCrawlStatus: UIO[CrawlState]
    def setCrawlingStatus(isCrawling: Boolean): UIO[Unit]
    // shortcuts for activating and deactivating crawling
    def activateCrawling: UIO[Unit]
    def deactivateCrawling: UIO[Unit]
    // how many blog posts have been fetched
    def setCrawlSize(crawlSize: Int): UIO[Unit]
    def getCrawlSize: UIO[CrawlSize]

  class CrawlMetadataRepository(
    crawlStateRef: Ref[CrawlState],
    crawlSizeRef: Ref[CrawlSize],
    lastModifiedGmtRef: Ref[Instant])
      extends CrawlMetadata:
    def getLastModifiedGmt: UIO[Instant] = lastModifiedGmtRef.get
    def setLastModifiedGmt(time: Instant): UIO[Unit] =
      ZIO.when(time.isAfter(Instant.now()))(
        ZIO.dieMessage(s"Cannot set a future timestamp $time")
      ) *>
        lastModifiedGmtRef.set(time)
    def getCrawlStatus: UIO[CrawlState] = crawlStateRef.get
    def setCrawlingStatus(isCrawling: Boolean): UIO[Unit] =
      crawlStateRef.update(_.copy(isCrawling = isCrawling))
    def activateCrawling: UIO[Unit]   = setCrawlingStatus(true)
    def deactivateCrawling: UIO[Unit] = setCrawlingStatus(false)
    def setCrawlSize(crawlSize: Int): UIO[Unit] =
      ZIO.when(crawlSize < 0)(ZIO.dieMessage(s"Crawl size $crawlSize cannot be negative")) *>
        crawlSizeRef.update(_.copy(crawlSize = crawlSize))
    def getCrawlSize: UIO[CrawlSize] = crawlSizeRef.get

  case class CrawlState(isCrawling: Boolean)
  case class CrawlSize(crawlSize: Int)

  val layer: ZLayer[BlogBlitzConfig.SchedulerConfig, Nothing, CrawlMetadata] =
    ZLayer.fromZIO(
      for
        schedulerConfig    <- ZIO.service[BlogBlitzConfig.SchedulerConfig]
        crawlStateRef      <- Ref.make[CrawlState](CrawlState(false))
        crawlSizeRef       <- Ref.make[CrawlSize](CrawlSize(0))
        lastModifiedGmtRef <- Ref.make[Instant](schedulerConfig.startDateGmt)
      yield new CrawlMetadataRepository(
        crawlStateRef,
        crawlSizeRef,
        lastModifiedGmtRef,
      )
    )
