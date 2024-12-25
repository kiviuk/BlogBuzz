package blogblitz

import zio.*
import zio.http.*
import zio.test.*
import BlogBlitzConfig.{ Host, ApiPath, PerPage, CrawlerConfig }
import zio.http.netty.NettyConfig
import zio.http.netty.server.NettyDriver
import java.time.Instant
import zio.logging.backend.SLF4J
import Crawler.WordPressCrawler.*
object CrawlerSpec extends ZIOSpecDefault:
  val QUEUE_CAPACITY = 256
  // Sample JSON response that mimics WordPress API response
  val sampleBlogPosts =
    """
    [
      {
        "title": {"rendered": "Test Post 1"},
        "content": {"rendered": "Content 1"},
        "id": 1,
        "guid": {"rendered": "https://example.com/post/1"},
        "link": "https://example.com/post/1",
        "date_gmt": "2023-01-02T12:00:00Z",
        "modified_gmt": "2023-01-02T12:00:00Z",
        "requestUrl": "http://example.com?per_page=10&after=..."

      },
      {
        "title": {"rendered": "Test Post 2"},
        "content": {"rendered": "Content 2"},
        "id": 2,
        "guid": {"rendered": "https://example.com/post/2"},
        "link": "https://example.com/post/2",
        "date_gmt": "2023-01-03T12:00:00Z",
        "modified_gmt": "2023-01-03T12:00:00Z",
        "requestUrl": "http://example.com?per_page=10&after=..."
      }
    ]
    """

  val invalidBlogPosts =
    """
    [
      {
        "xtitle": {"rendered": "Test Post 1"},
        "xcontent": {"rendered": "Content 1"},
        "xid": 1,
        "xguid": {"rendered": "https://example.com/post/1"},
        "xlink": "https://example.com/post/1",
        "xdate_gmt": "2023-01-02T12:00:00Z",
        "xmodified_gmt": "2023-01-02T12:00:00Z",
        "xrequestUrl": "http://example.com?per_page=10&after=..."

      },
    ]
    """

  def spec = suite("WordPress Crawler")(
    suite("Helper functions")(
      test("createUrl should correctly combine base URL with path segments") {
        val baseUrl     = URL.decode("http://example.com").getOrElse(URL.empty)
        val path        = "/users/profile/123"
        val expectedUrl = URL.decode("http://example.com/users/profile/123").getOrElse(URL.empty)
        assertTrue(createUrl(baseUrl, path) == expectedUrl)
      },
      test("createUrl should handle empty path correctly") {
        val baseUrl     = URL.decode("http://example.com").getOrElse(URL.empty)
        val path        = ""
        val expectedUrl = baseUrl
        assertTrue(createUrl(baseUrl, path) == expectedUrl)
      },
    ),
    suite("fetchPostsSinceGmt")(
      test("skips invalid JSON response") {
        for
          client <- ZIO.service[Client]
          port   <- ZIO.serviceWithZIO[Server](_.port)

          // add test routes that mimic WordPress API
          _ <- TestServer.addRoutes {
            Routes(
              Method.GET / "wp-json" / "wp" / "v2" / "posts" -> handler {
                Response
                  .json(invalidBlogPosts)
                  .addHeader(Header.ContentType(MediaType.application.json))
                  .addHeader(Header.Custom("X-WP-TotalPages", "2"))
              }
            )
          }

          crawlerTestConfig = CrawlerConfig(
            host = Host(s"http://localhost:$port"),
            apiPath = ApiPath("wp-json/wp/v2/posts"),
            perPage = PerPage(10),
          )

          hub  <- Hub.bounded[WordPressApi.BlogPost](QUEUE_CAPACITY)
          post <- hub.subscribe

          posts <- Crawler
            .WordPressCrawler
            .fetchAndPublishPostsSinceGmt(Instant.parse("2023-01-01T00:00:00Z"), hub)
            .provide(ZLayer.succeed(crawlerTestConfig) ++ Client.default)

          hubSize <- post.size
        yield assertTrue(
          posts.length == 0,
          hubSize == posts.length,
        )
      },
      test(
        "successfully fetches and parses blog posts in multiple pages recursively"
      ) {
        for {
          client <- ZIO.service[Client]
          port   <- ZIO.serviceWithZIO[Server](_.port)

          // add test routes that mimic WordPress API
          _ <- TestServer.addRoutes {
            Routes(
              Method.GET / "wp-json" / "wp" / "v2" / "posts" -> handler {
                Response
                  .json(sampleBlogPosts)
                  .addHeader(Header.ContentType(MediaType.application.json))
                  .addHeader(Header.Custom("X-WP-TotalPages", "2"))
              }
            )
          }

          crawlerTestConfig = CrawlerConfig(
            host = Host(s"http://localhost:$port"),
            apiPath = ApiPath("wp-json/wp/v2/posts"),
            perPage = PerPage(10),
          )

          hub <- Hub.bounded[WordPressApi.BlogPost](QUEUE_CAPACITY)

          // run the crawler with our test configuration
          posts <- Crawler
            .WordPressCrawler
            .fetchAndPublishPostsSinceGmt(Instant.parse("2023-01-01T00:00:00Z"), hub)
            .provide(ZLayer.succeed(crawlerTestConfig) ++ Client.default)
          post    <- hub.subscribe
          hubSize <- post.takeAll
        } yield assertTrue(
          posts.length == 4,
          posts.head.title.rendered == "Test Post 1",
          posts.head.content.rendered == "Content 1",
          posts(1).title.rendered == "Test Post 2",
          posts(1).content.rendered == "Content 2",
        )
      },
      test("successfully fetches and parses blog posts") {
        for
          client <- ZIO.service[Client]
          port   <- ZIO.serviceWithZIO[Server](_.port)

          // add test routes that mimic WordPress API
          _ <- TestServer.addRoutes {
            Routes(
              Method.GET / "wp-json" / "wp" / "v2" / "posts" -> handler {
                Response
                  .json(sampleBlogPosts)
                  .addHeader(Header.ContentType(MediaType.application.json))
                  .addHeader(Header.Custom("X-WP-TotalPages", "1"))
              }
            )
          }

          crawlerTestConfig = CrawlerConfig(
            host = Host(s"http://localhost:$port"),
            apiPath = ApiPath("wp-json/wp/v2/posts"),
            perPage = PerPage(10),
          )

          hub  <- Hub.bounded[WordPressApi.BlogPost](QUEUE_CAPACITY)
          post <- hub.subscribe

          posts <- Crawler
            .WordPressCrawler
            .fetchAndPublishPostsSinceGmt(Instant.parse("2023-01-01T00:00:00Z"), hub)
            .provide(ZLayer.succeed(crawlerTestConfig) ++ Client.default)

          hubSize <- post.size
        yield assertTrue(
          posts.length == 2,
          hubSize == 2,
          posts.head.title.rendered == "Test Post 1",
          posts.head.content.rendered == "Content 1",
          posts(1).title.rendered == "Test Post 2",
          posts(1).content.rendered == "Content 2",
        )
      },
    ).provide(
      ZLayer.succeed(Server.Config.default.onAnyOpenPort),
      Client.default,
      NettyDriver.customized,
      TestServer.layer,
      Scope.default,
      ZLayer.succeed(NettyConfig.defaultWithFastShutdown),
      Runtime.removeDefaultLoggers >>> SLF4J.slf4j,
    ),
  )

  //   suite("fetchPostsSinceGmt") (
  //     test("successfully fetches and parses blog posts") {
  //       for
  //         client <- ZIO.service[Client]
  //         port   <- ZIO.serviceWithZIO[Server](_.port)

  //         // add test routes that mimic WordPress API
  //         _ <- TestServer.addRoutes {
  //           Routes(
  //             Method.GET / "wp-json" / "wp" / "v2" / "posts" -> handler {
  //               Response
  //                 .json(sampleBlogPosts)
  //                 .addHeader(Header.ContentType(MediaType.application.json))
  //                 .addHeader(Header.Custom("X-WP-TotalPages", "2"))
  //             }
  //           )
  //         }

  //         crawlerTestConfig = CrawlerConfig(
  //           host = Host(s"http://localhost:$port"),
  //           apiPath = ApiPath("wp-json/wp/v2/posts"),
  //           perPage = PerPage(10),
  //         )

  //         hub <- Hub.bounded[WordPress.BlogPost](QUEUE_CAPACITY)

  //         posts <- Crawler
  //           .WordPressCrawler
  //           .fetchPostsSinceGmt(Instant.parse("2023-01-01T00:00:00Z"), hub)
  //           .provide(ZLayer.succeed(crawlerTestConfig) ++ Client.default)

  //         post    <- hub.subscribe
  //         hubSize <- post.size
  //       yield assertTrue(
  //         posts.length == 2,
  //         hubSize == posts.length,
  //         posts.head.title.rendered == "Test Post 1",
  //         posts.head.content.rendered == "Content 1",
  //         posts(1).title.rendered == "Test Post 2",
  //         posts(1).content.rendered == "Content 2",
  //       )
  //     }

  //   //   test("fails to parse invalid JSON response") {
  //   //     for
  //   //       client <- ZIO.service[Client]
  //   //       port   <- ZIO.serviceWithZIO[Server](_.port)

  //   //       // add test routes that mimic WordPress API
  //   //       _ <- TestServer.addRoutes {
  //   //         Routes(
  //   //           Method.GET / "wp-json" / "wp" / "v2" / "posts" -> handler {
  //   //             Response
  //   //               .json(invalidBlogPosts)
  //   //               .addHeader(Header.ContentType(MediaType.application.json))
  //   //               .addHeader(Header.Custom("X-WP-TotalPages", "2"))
  //   //           }
  //   //         )
  //   //       }

  //   //       crawlerTestConfig = CrawlerConfig(
  //   //         host = Host(s"http://localhost:$port"),
  //   //         apiPath = ApiPath("wp-json/wp/v2/posts"),
  //   //         perPage = PerPage(10),
  //   //       )

  //   //       hub <- Hub.bounded[WordPress.BlogPost](QUEUE_CAPACITY)

  //   //       posts <- Crawler
  //   //         .WordPressCrawler
  //   //         .fetchPostsSinceGmt(Instant.parse("2023-01-01T00:00:00Z"), hub)
  //   //         .provide(ZLayer.succeed(crawlerTestConfig) ++ Client.default)

  //   //       post    <- hub.subscribe
  //   //       hubSize <- post.size
  //   //     yield assertTrue(
  //   //       posts.length == 2,
  //   //       hubSize == posts.length,
  //   //       posts.head.title.rendered == "Test Post 1",
  //   //       posts.head.content.rendered == "Content 1",
  //   //       posts(1).title.rendered == "Test Post 2",
  //   //       posts(1).content.rendered == "Content 2",
  //   //     )
  //   // }

  //   test(
  //     "successfully fetches and parses blog posts in multiple pages recursively"
  //   ) {
  //     for
  //       client <- ZIO.service[Client]
  //       port   <- ZIO.serviceWithZIO[Server](_.port)

  //       // add test routes that mimic WordPress API
  //       _ <- TestServer.addRoutes {
  //         Routes(
  //           Method.GET / "wp-json" / "wp" / "v2" / "posts" -> handler {
  //             Response
  //               .json(sampleBlogPosts)
  //               .addHeader(Header.ContentType(MediaType.application.json))
  //               .addHeader(Header.Custom("X-WP-TotalPages", "2"))
  //           }
  //         )
  //       }

  //       crawlerTestConfig = CrawlerConfig(
  //         host = Host(s"http://localhost:$port"),
  //         apiPath = ApiPath("wp-json/wp/v2/posts"),
  //         perPage = PerPage(10),
  //       )

  //       hub <- Hub.bounded[WordPress.BlogPost](QUEUE_CAPACITY)

  //       // run the crawler with our test configuration
  //       posts <- Crawler
  //         .WordPressCrawler
  //         .fetchPostsSinceGmt(Instant.parse("2023-01-01T00:00:00Z"), hub)
  //         .provide(ZLayer.succeed(crawlerTestConfig) ++ Client.default)
  //       post    <- hub.subscribe
  //       hubSize <- post.takeAll
  //     yield assertTrue(
  //       posts.length == 4,
  //       posts.head.title.rendered == "Test Post 1",
  //       posts.head.content.rendered == "Content 1",
  //       posts(1).title.rendered == "Test Post 2",
  //       posts(1).content.rendered == "Content 2",
  //     )
  //   }
  // }
  // ).provide(
  //   ZLayer.succeed(Server.Config.default.onAnyOpenPort),
  //   Client.default,
  //   NettyDriver.customized,
  //   TestServer.layer,
  //   Scope.default,
  //   ZLayer.succeed(NettyConfig.defaultWithFastShutdown),
  //   Runtime.removeDefaultLoggers >>> SLF4J.slf4j,
  // )
