package blogblitz

import zio._
import zio.test._
import zio.test.Assertion._
import zio.config._
import zio.config.yaml._
import zio.logging.backend.SLF4J

object BlogBlitzConfigSpec extends ZIOSpecDefault {

  // a valid configuration YAML as a string
  val validYaml: String =
    """
      |crawler:
      |  host: http://example.com
      |  apiPath: /api/posts
      |  perPage: 50
      |scheduler:
      |  intervalInSec: 1
      |  startDateGmt: "2024-12-23T00:00:00Z"
      |  maxCoolDownScale: 3
      |websocket:
      |  port: 8888
      |  subscribePath: "ws://localhost:${port}/subscribe/v2"
      |""".stripMargin

  // an invalid configuration YAML (invalid perPage)
  val invalidPerPageYaml: String =
    """
      |crawler:
      |  host: http://example.com
      |  apiPath: /api/posts
      |  perPage: 0
      |scheduler:
      |  intervalInSec: 1
      |  startDateGmt: "2024-12-23T00:00:00Z"
      |  maxCoolDownScale: 3
      |websocket:
      |  port: 8888
      |  subscribePath: "ws://localhost:${port}/subscribe/v2"
      |""".stripMargin

  // an invalid configuration YAML (invalid host)
  val invalidHostYaml: String =
    """
      |crawler:
      |  host: not_a_valid_url
      |  apiPath: /api/posts
      |  perPage: 50
      |scheduler:
      |  intervalInSec: 1
      |  startDateGmt: "2024-12-23T00:00:00Z"
      |  maxCoolDownScale: 3
      |websocket:
      |  port: 8888
      |  subscribePath: "ws://localhost:${port}/subscribe/v2"
      |""".stripMargin

  // an invalid configuration YAML (invalid host and perPage)
  val invalidHostAndPerPageYaml: String =
    """
      |crawler:
      |  host: not_a_valid_url
      |  apiPath: /api/posts
      |  perPage: 0
      |scheduler:
      |  intervalInSec: 1
      |  startDateGmt: "2024-12-23T00:00:00Z"
      |  maxCoolDownScale: 3
      |websocket:
      |  port: 8888
      |  subscribePath: "ws://localhost:${port}/subscribe/v2"
      |""".stripMargin

  // an invalid configuration YAML (invalid scheduler interval)
  val invalidSchedulerIntervalAndCoolDownScalaYaml: String =
    """
      |crawler:
      |  host: http://example.com
      |  apiPath: /api/posts
      |  perPage: 50
      |scheduler:
      |  intervalInSec: 0
      |  startDateGmt: "2999-12-23T00:00:00Z"
      |  maxCoolDownScale: 0
      |websocket:
      |  port: 8888
      |  subscribePath: "ws://localhost:${port}/subscribe/v2"
      |""".stripMargin

  // an invalid configuration YAML (invalid scheduler interval)
  val invalidStartDateYaml: String =
    """
      |crawler:
      |  host: http://example.com
      |  apiPath: /api/posts
      |  perPage: 50
      |scheduler:
      |  intervalInSec: 1
      |  startDateGmt: "2024-12-23T00:00:00Z"
      |  maxCoolDownScale: 3
      |websocket:
      |  port: 8888
      |  subscribePath: "ws://localhost:${port}/subscribe/v2"
      |""".stripMargin

  def spec = suite("BlogBlitzConfigSpec")(
    suite("CrawlerConfig")(
      test("should load and validate a valid configuration") {
        val configProvider = ConfigProvider.fromYamlString(validYaml)
        val result =
          configProvider.load(BlogBlitzConfig.config).flatMap { config =>
            ZIO.fromEither(config.crawler.validate)
          }

        assertZIO(result)(isUnit)
      },
      test("should fail validation for invalid perPage") {
        val configProvider = ConfigProvider.fromYamlString(invalidPerPageYaml)
        val result =
          configProvider.load(BlogBlitzConfig.config).flatMap { config =>
            ZIO.fromEither(config.crawler.validate)
          }

        assertZIO(result.either)(
          isLeft(
            equalTo(
              "WordPress API: perPage: '0' must be between 1 (inclusive) and 100 (inclusive)"
            )
          )
        )
      },
      test("should fail validation for invalid host") {
        val configProvider = ConfigProvider.fromYamlString(invalidHostYaml)
        val result =
          configProvider.load(BlogBlitzConfig.config).flatMap { config =>
            ZIO.fromEither(config.crawler.validate)
          }

        assertZIO(result.either)(
          isLeft(
            equalTo(
              "WordPress API: host 'not_a_valid_url' must start with http"
            )
          )
        )
      },
      test("should fail validation for invalid host and perPage") {
        val configProvider =
          ConfigProvider.fromYamlString(invalidHostAndPerPageYaml)
        val result =
          configProvider
            .load(BlogBlitzConfig.config)
            .flatMap { config =>
              ZIO.fromEither(config.crawler.validate)
            }

        assertZIO(result.either.map(_.left.map(_.toString)))(
          isLeft(
            containsString(
              "WordPress API: host 'not_a_valid_url' must start with http"
            ) &&
            containsString(
              "WordPress API: perPage: '0' must be between 1 (inclusive) and 100 (inclusive)"
            )
          )
        )
      },
    ),
    suite("SchedulerConfig")(
      test("should load and validate a valid configuration") {
        val configProvider = ConfigProvider.fromYamlString(validYaml)
        val result =
          configProvider.load(BlogBlitzConfig.config).flatMap { config =>
            ZIO.fromEither(config.scheduler.validate)
          }

        assertZIO(result)(isUnit)
      },
      test("should fail validation for invalid negative interval") {
        val configProvider =
          ConfigProvider.fromYamlString(invalidSchedulerIntervalAndCoolDownScalaYaml)
        val result =
          configProvider.load(BlogBlitzConfig.config).flatMap { config =>
            ZIO.fromEither(config.scheduler.validate)
          }
        assertZIO(result.either.map(_.left.map(_.toString)))(
          isLeft(
            containsString("must be between") && containsString(
              "Scheduler: startDateGmt: '2999-12-23T00:00:00Z' must be in the past"
            ) && containsString(
              "Scheduler: maxCoolDownScale: integer '0' must be greater than 0"
            )
          )
        )
      },
      test("should fail validation for invalid string interval") {
        val invalidYamlStringInterval = """
                                          |crawler:
                                          |  host: http://example.com
                                          |  apiPath: /api/posts
                                          |  perPage: 50
                                          |scheduler:
                                          |  intervalInSec: 'X'
                                          |  startDateGmt: "2024-12-23T00:00:00Z"
                                          |  maxCoolDownScale: 3
                                          |websocket:
                                          |  port: 8888
                                          |  subscribePath: "ws://localhost:${port}/subscribe/v2"
                                          |""".stripMargin

        val configProvider =
          ConfigProvider.fromYamlString(invalidYamlStringInterval)
        val result =
          configProvider.load(BlogBlitzConfig.config).flatMap { config =>
            ZIO.fromEither(config.scheduler.validate)
          }
        assertZIO(result.either.map(_.left.map(_.toString)))(
          isLeft(
            containsString(
              "Expected an integer value, but found X"
            )
          )
        )
      },
      test("should also return duration when using valid interval") {
        val configProvider = ConfigProvider.fromYamlString(validYaml)
        for {
          config <- configProvider.load(BlogBlitzConfig.config)
          duration = config.scheduler.toDuration
        } yield assertTrue(duration == 1.seconds)
      },
    ),
    suite("WebSocketConfig")(
      test("should have WebSocket paths with hardcoded port values") {

        val configProvider = ConfigProvider.fromYamlString(validYaml)
        val result =
          configProvider.load(BlogBlitzConfig.config).map { config =>
            config.websocket.subscribePath.value.replace("${port}", config.websocket.port.toString)
          }

        assertZIO(result)(
          equalTo(
            "ws://localhost:8888/subscribe/v2"
          )
        )
      },
      test("should validate WebSocket configuration with valid port range") {
        val configProvider = ConfigProvider.fromYamlString(validYaml)
        val result =
          configProvider.load(BlogBlitzConfig.config).flatMap { config =>
            ZIO.fromEither(config.websocket.validate)
          }

        assertZIO(result)(isUnit)
      },
      test("should fail validation for empty WebSocket paths") {
        val invalidYaml = """
                            |crawler:
                            |  host: http://example.com
                            |  apiPath: /api/posts
                            |  perPage: 50
                            |scheduler:
                            |  intervalInSec: 1
                            |  startDateGmt: "2024-12-23T00:00:00Z"
                            |  maxCoolDownScale: 3
                            |websocket:
                            |  port: 8888
                            |  subscribePath: ""
                            |""".stripMargin

        val configProvider = ConfigProvider.fromYamlString(invalidYaml)
        val result =
          configProvider.load(BlogBlitzConfig.config).flatMap { config =>
            ZIO.fromEither(config.websocket.validate)
          }

        assertZIO(result.either)(isLeft(anything))
      },
      test("should fail validation for missing hardcoded port values") {
        val invalidYaml = """
                            |crawler:
                            |  host: http://example.com
                            |  apiPath: /api/posts
                            |  perPage: 50
                            |scheduler:
                            |  intervalInSec: 1
                            |  startDateGmt: "2024-12-23T00:00:00Z"
                            |  maxCoolDownScale: 3
                            |websocket:
                            |  port: 8888
                            |  subscribePath: "ws://localhost:8888/subscribe/v2"
                            |""".stripMargin

        val configProvider = ConfigProvider.fromYamlString(invalidYaml)
        val result =
          configProvider.load(BlogBlitzConfig.config).flatMap { config =>
            ZIO.fromEither(config.websocket.validate)
          }

        assertZIO(result.either)(isLeft(anything))
      },
      test("should fail validation for port below minimum allowed range") {
        val invalidYaml = """
                            |crawler:
                            |  host: http://example.com
                            |  apiPath: /api/posts
                            |  perPage: 50
                            |scheduler:
                            |  intervalInSec: 1
                            |  startDateGmt: "2024-12-23T00:00:00Z"
                            |  maxCoolDownScale: 3
                            |websocket:
                            |  port: 1023
                            |  subscribePath: "ws://localhost:${port}/subscribe/v2"
                            |""".stripMargin

        val configProvider = ConfigProvider.fromYamlString(invalidYaml)
        val result =
          configProvider.load(BlogBlitzConfig.config).flatMap { config =>
            ZIO.fromEither(config.websocket.validate)
          }

        assertZIO(result.either)(
          isLeft(
            equalTo("WebSocket: port: '1023' must be between 1024 and 65535")
          )
        )
      },
      test("should fail validation for port above maximum allowed range") {
        val invalidYaml = """
                            |crawler:
                            |  host: http://example.com
                            |  apiPath: /api/posts
                            |  perPage: 50
                            |scheduler:
                            |  intervalInSec: 1
                            |  startDateGmt: "2024-12-23T00:00:00Z"
                            |  maxCoolDownScale: 3
                            |websocket:
                            |  port: 65536
                            |  subscribePath: "ws://localhost:${port}/subscribe/v2"
                            |""".stripMargin

        val configProvider = ConfigProvider.fromYamlString(invalidYaml)
        val result =
          configProvider.load(BlogBlitzConfig.config).flatMap { config =>
            ZIO.fromEither(config.websocket.validate)
          }

        assertZIO(result.either)(
          isLeft(
            equalTo("WebSocket: port: '65536' must be between 1024 and 65535")
          )
        )
      },
    ),
  ).provide(
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j
  )

}
