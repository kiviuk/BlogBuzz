package blogblitz

import zio.*
import zio.http.*
import zio.json.*
import java.time.Instant
import blogblitz.WordPressApi.*

import zio.http.{ Response, Status }

// Crawler that fetches blog posts from a WordPress site and publishes them to a WebSocket server.
// Uses pagination to fetch posts.
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
  private val DATE           = "date"
  private val ASC            = "asc"

  val timeoutDuration = 10.seconds

  // total number of pages in the response
  val X_WP_TOTAL_PAGES_HEADER = "X-WP-TotalPages"

  private val FIRST_PAGE = 1

  private val PATH_SPLITTER = "/"

  object WordPressCrawler {
    import BlogBlitzConfig.*

    def createUrl(base: URL, path: String): URL =
      val pathSegments = path.split(PATH_SPLITTER).filter(_.nonEmpty)

      pathSegments.foldLeft(base) { (url, segment) =>
        url / segment
      }

    def fetchAndPublishPostsSinceGmt(
      sinceTimestamp: Instant,
      publishingBlogPostHub: Hub[WordPressApi.BlogPost],
      page: Int = FIRST_PAGE,
    ): ZIO[Client & CrawlerConfig, Throwable, List[BlogPost]] = {
      for {

        baseClient <- ZIO.service[Client]
        config     <- ZIO.service[CrawlerConfig]

        baseUrl <- ZIO
          .fromEither(URL.decode(config.host.value))
          .mapError(new RuntimeException(_))

        client = baseClient.batched

        // Construct the full URL with the path and query parameters
        // note: Strict ordering loses significance for parallel page requests
        url = createUrl(baseUrl, config.apiPath.value)
          .addQueryParam(PER_PAGE, config.perPage.toString())
          .addQueryParam(AFTER, sinceTimestamp.toString())
          .addQueryParam(MODIFIED_AFTER, sinceTimestamp.toString())
          .addQueryParam(ORDERBY, DATE)
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
                            |URL: ${url}
                            |Parameters:
                            | - ${PER_PAGE}: ${config.perPage}
                            | - ${AFTER}: $sinceTimestamp
                            | - ${MODIFIED_AFTER}: $sinceTimestamp
                            | - ${ORDERBY}: ${DATE}
                            | - ${ORDER}: ${ASC}
                            | - ${PAGE}: $page
                            |""".stripMargin)

        // Make the request and handle timeout
        response <- ZIO
          .scoped(client.request(req))
          .timeout(timeoutDuration)

        response <- response match {
          case Some(value) => ZIO.succeed(value) // Use the response if it completes within the timeout
          case None =>
            ZIO.logError(
              s"Crawler timed out after timeoutDuration = ${timeoutDuration.toSeconds} sec for URL: $url"
            ) *> ZIO.succeed(Response.status(Status.Ok)) // Log the error and continue with an empty response
        }

        // Extract response body
        responseBody <- response.body.asString

        // The total number of pages from headers
        totalPages = response
          .headers
          .get(X_WP_TOTAL_PAGES_HEADER)
          .getOrElse("0")
          .toInt

        // Fetch posts from other pages concurrently if applicable
        otherPagePostsFiber <-
          if page == FIRST_PAGE && totalPages > FIRST_PAGE then
            // Start fetching remaining pages recursively on separate fibers
            ZIO
              .collectAllPar((2 to totalPages).map { p =>
                fetchAndPublishPostsSinceGmt(sinceTimestamp, publishingBlogPostHub, p)
              })
              .fork // good luck
          else
            ZIO.succeed(
              Fiber.succeed(List.empty[List[BlogPost]].toIndexedSeq)
            ) // Return a fiber that does nothing if there are no other pages to fetch

        _ <- ZIO.logInfo(s"Page: $page")
        _ <- ZIO.logInfo(s"Total Pages: $totalPages")
        _ <- ZIO.logInfo(s"Response body: $responseBody")

        // Parse posts from JSON response, skipping invalid posts
        posts <- ZIO
          .fromEither(responseBody.fromJson[List[BlogPost]])
          .tapError(err =>
            ZIO.logError(
              s"Failed to parse JSON response: $err; response body: $responseBody url: $url"
            )
          )
          .orElse(ZIO.succeed(List.empty[BlogPost]))

        // Collect all posts in the current page and add request URL for debugging
        updatedPosts = posts
          .map { post =>
            post.copy(
              requestUrl = url.toString()
            )
          }

        // Send each post to the publishing hub
        _ <- ZIO.foreach(updatedPosts) { post =>
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
  ): ZIO[Client & CrawlerConfig, Throwable, List[BlogPost]]

}

object CrawlerService {
  val layer: ZLayer[Client & CrawlerConfig, Nothing, CrawlerService] =
    ZLayer.fromFunction { (client: Client, config: CrawlerConfig) =>
      new CrawlerService:
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
