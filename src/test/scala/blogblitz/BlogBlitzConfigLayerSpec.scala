package blogblitz

import zio._
import zio.test._
import zio.test.Assertion._
import BlogBlitzConfig.{Host, ApiPath, PerPage, CrawlerConfig}
import zio.logging.backend.SLF4J

object BlogBlitzConfigLayerSpec extends ZIOSpecDefault {

  def spec = suite("BlogBlitzConfigLayerSpec")(
    test("makeLayer successfully loads test configuration file") {
      val layer = BlogBlitzConfig.makeLayer("test")
      ZIO
        .serviceWith[CrawlerConfig] { config =>
          assertTrue(
            config.host.value.nonEmpty,
            config.apiPath.value.nonEmpty,
            config.perPage.value > 0
          )
        }
        .provide(layer.project(_.crawler))
    },
    test("makeLayer fails with non-existent environment") {
      assertZIO(
        BlogBlitzConfig.makeLayer("nonexistent").build.exit
      )(
        fails(
          containsString(
            "Configuration file 'application-nonexistent.yaml' not found for environment: nonexistent"
          )
        )
      )
    },
    test("makeLayer fails with broken configuration file") {
      assertZIO(
        BlogBlitzConfig.makeLayer("broken").build.exit
      )(
        fails(
          containsString(
            "Failed to parse configuration in file 'application-broken.yaml': while parsing a flow mapping"
          )
        )
      )
    },
    test("makeLayer fails with invalid configuration file") {
      assertZIO(
        BlogBlitzConfig.makeLayer("invalid").build.exit
      )(
        fails(
          containsString("http") && containsString("100")
        )
      )
    },
    test("should fail to create layer with invalid websocket configuration") {
      assertZIO(
        BlogBlitzConfig.makeLayer("invalid-websocket").build.exit
      )(
        fails(
          containsString("88080") && 
          containsString("must contain '${port}' placeholder")
        )
      )
    }
  ).provide(
    Scope.default,
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j
  )
}
