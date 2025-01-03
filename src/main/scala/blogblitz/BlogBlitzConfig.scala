package blogblitz

import blogblitz.BlogBlitzConfig.ApiPath.value
import zio.*
import zio.config.magnolia.*
import zio.config.magnolia.deriveConfig
import zio.config.yaml.*

import java.time.Instant
import scala.io.Source

// TODO: use
// https://zio.github.io/zio-prelude/docs/functionaldatatypes/validation
// https://github.com/lightbend/config
object BlogBlitzConfig {
  private val ENV_VAR         = "APP_ENV"
  private val DEV_ENV         = "dev"
  private val TEST_ENV        = "test"
  private val PROD_ENV        = "prod"
  private val DEFAULT_ENV     = TEST_ENV
  private val NEW_LINE_SEP    = java.lang.System.lineSeparator
  private val MIN_PORT        = 1024
  private val MAX_PORT        = 65535
  private val ONE_HOUR_IN_SEC = 60 * 60

  opaque type Host = String
  object Host {
    def apply(value: String): Host        = value
    extension (h: Host) def value: String = h

  }
  opaque type ApiPath = String
  object ApiPath {
    def apply(value: String): ApiPath        = value
    extension (a: ApiPath) def value: String = a

  }
  opaque type PerPage = Int
  object PerPage {
    def apply(value: Int): PerPage        = value
    extension (p: PerPage) def value: Int = p

  }
  opaque type Port = Int
  object Port {
    def apply(value: Int): Port        = value
    extension (p: Port) def value: Int = p

  }
  opaque type Path = String
  object Path {
    def apply(value: String): Path        = value
    extension (p: Path) def value: String = p

  }
  case class CrawlerConfig(
    host: Host,
    apiPath: ApiPath,
    perPage: PerPage) {
    object CrawlerConfigErrors {
      val InvalidPerPage: String => String = perPage =>
        s"WordPress API: perPage: '$perPage' must be between 1 (inclusive) and ${WordPressApi.MAX_PER_PAGE_BY_WP} (inclusive)"

      val InvalidHost: String => String = host =>
        s"WordPress API: host '$host' must start with http"

    }
    def validate: Either[String, Unit] = {

      val invalidPerPage = perPage < 1 || perPage > WordPressApi.MAX_PER_PAGE_BY_WP
      val invalidHost    = !host.startsWith("http")

      val perPageError =
        if (invalidPerPage)
          Some(CrawlerConfigErrors.InvalidPerPage(perPage.toString))
        else None

      val hostError =
        if (invalidHost) Some(CrawlerConfigErrors.InvalidHost(host))
        else None

      // Collect all errors
      val errors = List(perPageError, hostError).flatten

      if errors.isEmpty then Right(())
      else Left(errors.mkString(NEW_LINE_SEP))
    }

  }

  // implicit conversion for Instant
  implicit private val instantConfig: DeriveConfig[Instant] = {
    DeriveConfig[String].map(string => Instant.parse(string))
  }

  // Todo: allow cron-style scheduling
  case class SchedulerConfig(
    intervalInSec: Int,
    startDateGmt: Instant,
    maxCoolDownScale: Int) {
    object SchedulerConfigErrors {
      val invalidInterval: Int => String = interval =>
        s"Scheduler: interval (seconds): '$interval' must be between 1 (inclusive) and $ONE_HOUR_IN_SEC (inclusive)."

      val invalidStartDate: Instant => String = startDate =>
        s"Scheduler: startDateGmt: '$startDate' must be in the past."

      val invalidCoolDownScale: Int => String = scale =>
        s"Scheduler: maxCoolDownScale: integer '$scale' must be greater than 0."

    }

    def validate: Either[String, Unit] = {
      val now = Instant.now()

      val isInvalidInterval  = intervalInSec <= 0 || intervalInSec > ONE_HOUR_IN_SEC
      val isInvalidStartDate = startDateGmt.isAfter(now)
      val isInvalidCoolDown  = maxCoolDownScale < 1

      val intervalError =
        if (isInvalidInterval)
          Some(SchedulerConfigErrors.invalidInterval(intervalInSec))
        else None

      val startDateError =
        if (isInvalidStartDate) Some(SchedulerConfigErrors.invalidStartDate(startDateGmt))
        else None

      val coolDownError =
        if (isInvalidCoolDown) Some(SchedulerConfigErrors.invalidCoolDownScale(maxCoolDownScale))
        else None

      val errors = List(intervalError, startDateError, coolDownError).flatten

      if errors.isEmpty then Right(())
      else Left(errors.mkString(NEW_LINE_SEP))
    }
    def toDuration: Duration = Duration.fromSeconds(intervalInSec.toLong)

  }

  case class WebSocketConfig(
    port: Port,
    subscribePath: Path) {
    object WebSocketConfigErrors {
      val invalidPort: Port => String = port =>
        s"WebSocket: port: '$port' must be between $MIN_PORT and $MAX_PORT"

      val emptySubscribePath: String =
        "WebSocket: subscribePath cannot be empty"

      val missingPortPlaceholder: String =
        "WebSocket: subscribePath must contain '${port}' placeholder"

    }
    def validate: Either[String, Unit] = {
      val invalidPortRange       = port < MIN_PORT || port > MAX_PORT
      val emptySubscribePath     = subscribePath.isEmpty
      val missingPortPlaceholder = !subscribePath.value.contains("${port}")

      val errors = List(
        if (invalidPortRange) Some(WebSocketConfigErrors.invalidPort(port)) else None,
        if (emptySubscribePath) Some(WebSocketConfigErrors.emptySubscribePath) else None,
        if (missingPortPlaceholder) Some(WebSocketConfigErrors.missingPortPlaceholder) else None,
      ).flatten

      if errors.isEmpty then Right(())
      else Left(errors.mkString(NEW_LINE_SEP))
    }

  }
  // main config that contains all sections
  // note: validation is not eager
  case class Config(
    crawler: CrawlerConfig,
    scheduler: SchedulerConfig,
    websocket: WebSocketConfig)

  val config: zio.Config[Config] = deriveConfig[Config]

  def makeLayer(env: String): ZLayer[Any, String, Config] = {

    object ConfigLoaderErrors {

      val fileNotFound: String => String = { fileName =>
        s"Configuration file '$fileName' not found for environment: $env. " +
          s"Did you forget to set environment variable '$ENV_VAR' to 'dev', 'test' or 'prod'?"
      }
      val fileReadError: String => String = { fileName =>
        s"Failed to read yaml from configuration file '$fileName'."
      }

      val parseError: String => String = { fileName =>
        s"Failed to parse configuration in file '$fileName'"
      }

      val validationError: String => String = { fileName =>
        s"Configuration validation failed for file '$fileName'"
      }

    }

    val CONFIG_FILE = s"application-$env.yaml"

    ZLayer.fromZIO {

      def loadResource(fileName: String): ZIO[Any, String, String] = {
        def openFile(name: => String): ZIO[Any, String, Source] = {
          ZIO
            .attemptBlockingIO(Source.fromResource(name))
            .mapError(err => ConfigLoaderErrors.fileNotFound(name) + s" Error message: $err")
        }
        def closeFile(source: Source): ZIO[Any, Nothing, Unit] = {
          ZIO.succeedBlocking(source.close())
        }

        def readFile(source: Source): ZIO[Any, String, String] = {
          ZIO
            .attemptBlocking(source.mkString)
            .mapError(err => ConfigLoaderErrors.fileReadError(fileName) + s" Error message: $err")
        }

        ZIO.acquireReleaseWith(openFile(fileName))(closeFile(_))(readFile(_))
      }

      def validator(appConfig: Config): Either[String, Config] = {
        val errors = List(
          appConfig.crawler.validate,
          appConfig.scheduler.validate,
          appConfig.websocket.validate,
        ).collect { case Left(err) => err }

        errors match {
          case Nil => Right(appConfig)
          case _ =>
            Left(errors.mkString(NEW_LINE_SEP))
        }
      }

      def parseAndValidate(yamlString: String): ZIO[Any, String, Config] = {
        ZIO
          .attempt(ConfigProvider.fromYamlString(yamlString).load(config))
          .flatten
          .mapError(err => ConfigLoaderErrors.parseError(CONFIG_FILE) + s": $err")
          .flatMap(parsedConfig =>
            ZIO
              .fromEither(validator(parsedConfig))
              .mapError(err => ConfigLoaderErrors.validationError(CONFIG_FILE) + s" $err")
          )
      }

      for {
        yamlString  <- loadResource(CONFIG_FILE)
        finalConfig <- parseAndValidate(yamlString)
      } yield finalConfig
    }
  }

  private def getEnv: ZIO[Any, Nothing, String] = {
    val env = sys.env.getOrElse(ENV_VAR, DEFAULT_ENV)
    ZIO
      .logInfo(
        s"Reading environment variable '$ENV_VAR': $env (allowed values: $DEV_ENV, $TEST_ENV, $PROD_ENV)"
      )
      .as(env)
  }

  val layer: ZLayer[Any, Path, Config] = ZLayer.fromZIO(getEnv).flatMap(env => makeLayer(env.get))

  val crawlerLayer: ZLayer[Any, Path, CrawlerConfig]     = layer.project(_.crawler)
  val schedulerLayer: ZLayer[Any, Path, SchedulerConfig] = layer.project(_.scheduler)
  val websocketLayer: ZLayer[Any, Path, WebSocketConfig] = layer.project(_.websocket)

}
