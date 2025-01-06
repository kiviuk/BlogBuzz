package blogblitz

import zio.*
import zio.http.*
import zio.json.*
import java.time.Instant
import blogblitz.WordPressApi.*

import zio.http.{ Response, Status }

// Crawler that fetches blog posts from a WordPress site and
// publishes them to a hub connected to a WebSocket server endpoint.
// Uses pagination to fetch posts.
//
// Pages are fetched concurrently.
// https://developer.wordpress.org/rest-api/reference/posts/
// https://developer.wordpress.org/rest-api/using-the-rest-api/pagination/
object Crawler {

  // WordPress request parameters
  private val PER_PAGE       = "per_page"
  private val AFTER          = "after"
  private val MODIFIED_AFTER = "modified_after"
  private val ORDERBY        = "orderby"
  private val ORDER          = "order"
  private val PAGE           = "page"
  private val ORDER_BY_DATE  = "modified" // WP supports date or modified
  private val ASC            = "asc"

  private val timeoutDuration = 10.seconds

  // total number of pages in the response
  private val X_WP_TOTAL_PAGES_HEADER = "X-WP-TotalPages"

  private val FIRST_PAGE = 1

  private val PATH_SPLITTER = "/"

  object WordPressCrawler {
    import BlogBlitzConfig.*

    def createUrl(base: URL, path: String): URL = {
      val pathSegments = path.split(PATH_SPLITTER).filter(_.nonEmpty)

      pathSegments.foldLeft(base) { (url, segment) =>
        url / segment
      }
    }

    // TODO: collect errors using crawmeta data
    //  Throw only unrecoverable errors
    //  Refactor blog post processing into a pluggable processor
    def fetchAndPublishPostsSinceGmt(
      sinceTimestampGmt: Instant,
      publishingBlogPostHub: Hub[WordPressApi.BlogPost],
      page: Int = FIRST_PAGE,
    ): ZIO[Client & CrawlerConfig, Throwable, List[BlogPost]] = {
      for {

        baseClient <- ZIO.service[Client]
        config     <- ZIO.service[CrawlerConfig]

        client = baseClient.batched

        // the type of exceptions thrown could be refined
        // exceptions and recoverable errors should be tracked
        // unrecoverable errors should be thrown.
        baseUrl <- ZIO
          .fromEither(URL.decode(config.host.value))
          .mapError(new RuntimeException(_))

        // Construct the full URL with the path and query parameters
        // Note 1: Strict ordering loses significance for parallel page requests but guarantees
        // that the latest post will always be in the last page.
        // Note 2: Technically, at any timestamp boundary, a blog item could be fetched twice
        // Once as the youngest item from the previous batch and again as the oldest item in the current batch.
        // But better to eventually deduplicate data than to miss it.
        // [depending on the implementation of the API (>= or >). But WordPress API is >]
        url = createUrl(baseUrl, config.apiPath.value)
          .addQueryParam(PER_PAGE, config.perPage.toString)
          .addQueryParam(AFTER, sinceTimestampGmt.toString)
          .addQueryParam(MODIFIED_AFTER, sinceTimestampGmt.toString)
          .addQueryParam(ORDERBY, ORDER_BY_DATE)
          .addQueryParam(ORDER, ASC)
          .addQueryParam(PAGE, page.toString)

        _ <- ZIO.logInfo(s"Crawler Url: $url")

        req = Request(
          method = Method.GET,
          url = url,
        )

        // Log request details
        _ <- ZIO.logInfo(s"""
                            |Request details:
                            |URL: $url
                            |Parameters:
                            | - $PER_PAGE: ${config.perPage}
                            | - $AFTER: $sinceTimestampGmt
                            | - $MODIFIED_AFTER: $sinceTimestampGmt
                            | - $ORDERBY: $ORDER_BY_DATE
                            | - $ORDER: $ASC
                            | - $PAGE: $page
                            |""".stripMargin)

        // Make the request and handle timeout
        response <- ZIO
          .scoped(client.request(req))
          .timeout(timeoutDuration)

        response <- response match {
          case Some(value) => ZIO.succeed(value)
          case None =>
            ZIO
              .logError(
                s"Crawler timed out after timeoutDuration = ${timeoutDuration.toSeconds} sec for URL: $url"
              )
              .as(Response.status(Status.Ok)) // Log the error and continue with an empty response
        }

        // The total number of pages from headers
        totalPages = response
          .headers
          .get(X_WP_TOTAL_PAGES_HEADER)
          .getOrElse("0")
          .toInt

        // Fetch posts from other pages concurrently if applicable.
        // Note: The number of pages could change on the WordPress server between
        // the initial query and subsequent fetches.
        // Potential edge cases:
        // 1. The crawler might miss a newly added page.
        // 2. The crawler might try to fetch a page that no longer exists.
        // These cases should not significantly affect the overall consistency of the crawled data:
        // - A missing page will result in an empty crawl for that page.
        // - A new page will be fetched in the next crawl iteration.
        // And the 'order_by_date' requirement ensures the latest blog post
        // is always on the last page.
        // However, a perfect mirror of the WordPress state can only be achieved
        // by maintaining an index of previously collected blog posts.
        // This approach allows for detecting deletions and updates to existing posts.
        otherPagePostsFiber <-
          if page == FIRST_PAGE && totalPages > FIRST_PAGE then
          // Start fetching remaining pages recursively on separate fibers
          ZIO
            .foreachPar(FIRST_PAGE + 1 to totalPages)(p =>
              fetchAndPublishPostsSinceGmt(sinceTimestampGmt, publishingBlogPostHub, p)
            )
            .fork // good luck
          else
            ZIO.succeed(
              Fiber.succeed(List.empty[List[BlogPost]].toIndexedSeq)
            ) // Return a fiber that does nothing if there are no more pages to fetch

        // Extract html
        html <- response.body.asString

        _ <- ZIO.logInfo(s"Page: $page")
        _ <- ZIO.logInfo(s"Total Pages: $totalPages")
        _ <- ZIO.logDebug(html)

        // Parse posts from JSON response, skipping invalid posts
        // TODO: collect errors in crawl meta data
        posts <- ZIO
          .fromEither(html.fromJson[List[BlogPost]])
          .tapError(err =>
            ZIO.logError(
              s"Failed to parse JSON response: $err; html body: $html url: $url"
            )
          )
          .orElse(ZIO.succeed(List.empty[BlogPost]))

        // Collect all posts in the current page and add request URL for debugging
        // TODO: use meta data of the crawler to collect and associate urls with posts
        updatedPosts = posts
          .map { post => post.copy(requestUrl = url.toString()) }

        // Send each post to the publishing hub
        _ <- ZIO.foreachDiscard(updatedPosts) { post =>
          publishingBlogPostHub.publish(post) *>
            ZIO.logDebug(
              s"Publishing blog post: ${post.id} - ${post.title.rendered}".take(80)
            )
        }

        _ <- ZIO.logInfo(s"Crawler: processed ${updatedPosts.size} posts")

        otherPagePosts <- otherPagePostsFiber.join
      } yield updatedPosts ++ otherPagePosts.flatten
    }

  }

}

import BlogBlitzConfig.*

// Service that fetches blog posts
// from a WordPress site and publishes them to a given hub
trait CrawlerService {
  def fetchAndPublishPostsSinceGmt(
    sinceTimestamp: Instant,
    publishingBlogPostHub: Hub[WordPressApi.BlogPost],
  ): ZIO[Client & CrawlerConfig, Throwable, List[WordPressApi.BlogPost]]

}

object CrawlerService {
  val layer: ZLayer[Client & CrawlerConfig, Nothing, CrawlerService] =
    ZLayer.fromFunction { (client: Client, config: CrawlerConfig) =>
      new CrawlerService {
        def fetchAndPublishPostsSinceGmt(
          sinceTimestamp: Instant,
          publishingBlogPostHub: Hub[WordPressApi.BlogPost],
        ): ZIO[Client & CrawlerConfig, Throwable, List[BlogPost]] =
          Crawler
            .WordPressCrawler
            .fetchAndPublishPostsSinceGmt(sinceTimestamp, publishingBlogPostHub)
            .provide(ZLayer.succeed(client), ZLayer.succeed(config))
      }
    }

}

