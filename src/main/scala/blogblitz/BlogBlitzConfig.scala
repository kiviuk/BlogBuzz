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
object BlogBlitzConfig:
  private val ENV_VAR      = "APP_ENV"
  private val DEV_ENV      = "dev"
  private val TEST_ENV     = "test"
  private val PROD_ENV     = "prod"
  private val DEFAULT_ENV  = TEST_ENV
  private val NEW_LINE_SEP = "\n"
  private val MIN_PORT     = 1024
  private val MAX_PORT     = 65535

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
    def validate: Either[String, Unit] = {
      val errors = List(
        if perPage < 1 || perPage > 100 then
          Some(
            s"WordPress API: perPage: '$perPage' must be between 1 (inclusive) and 100 (inclusive)"
          )
        else None,
        if !host.startsWith("http") then Some(f"WordPress API: host '$host' must start with http")
        else None,
      ).flatten

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
    def validate: Either[String, Unit] = {
      val oneHourInSec      = 60 * 60
      val isInvalidInterval = intervalInSec <= 0 || intervalInSec > oneHourInSec

      val errors = List(
        if isInvalidInterval then
          Some(
            s"Scheduler: interval (seconds): '$intervalInSec' must be between 1 (inclusive) and" +
              s" $oneHourInSec (inclusive)"
          )
        else None,
        if startDateGmt.isAfter(Instant.now()) then
          Some(
            s"Scheduler: startDateGmt: '$startDateGmt' must be in the past"
          )
        else None,
        if maxCoolDownScale < 1 then
          Some(
            s"Scheduler: maxCoolDownScale: integer '$maxCoolDownScale' must be greater than 0."
          )
        else None,
      ).flatten

      if errors.isEmpty then Right(())
      else Left(errors.mkString(NEW_LINE_SEP))
    }
    def toDuration: Duration = Duration.fromSeconds(intervalInSec.toLong)

  }

  case class WebSocketConfig(
    port: Port,
    subscribePath: Path) {
    def validate: Either[String, Unit] = {
      val invalidPortRange = port < MIN_PORT || port > MAX_PORT
      val errors = List(
        if invalidPortRange then
          Some(s"WebSocket: port: '$port' must be between $MIN_PORT and $MAX_PORT")
        else None,
        if subscribePath.isEmpty then Some("WebSocket: subscribePath cannot be empty")
        else None,
        if !subscribePath.value.contains("${port}") then
          Some("WebSocket: subscribePath must contain '${port}' placeholder")
        else None,
      ).flatten

      if errors.isEmpty then Right(())
      else Left(errors.mkString(NEW_LINE_SEP))
    }

    def subscribePathWithPort: Path =
      Path(subscribePath.value.replace("${port}", port.toString))

  }
  // main config that contains all sections
  // note: validation is not eager
  case class Config(
    crawler: CrawlerConfig,
    scheduler: SchedulerConfig,
    websocket: WebSocketConfig)

  val config: zio.Config[Config] = deriveConfig[Config]

  def makeLayer(env: String): ZLayer[Any, String, Config] = {

    ZLayer.fromZIO {

      val CONFIG_FILE = s"application-$env.yaml"

      def loadResource(fileName: String): ZIO[Any, String, String] = {
        def openFile(name: => String): ZIO[Any, String, Source] = {
          ZIO
            .attemptBlockingIO(Source.fromResource(name))
            .mapError(err =>
              s"Configuration file '$name' not found for environment: $env. " +
                s"Did you forget to set environment variable '$ENV_VAR' to 'dev', 'test' or 'prod'? Error message: $err"
            )
        }
        def closeFile(source: => Source): ZIO[Any, Nothing, Unit] = {
          ZIO.succeedBlocking(source.close())
        }

        def readFile(source: => Source): ZIO[Any, String, String] = {
          ZIO
            .attemptBlocking(source.mkString)
            .mapError(err => s"Failed to read yaml from configuration file '$fileName': $err")
        }

        ZIO.acquireReleaseWith(openFile(fileName))(closeFile(_))(readFile(_))
      }

      for {
        yamlString <- loadResource(CONFIG_FILE)

        appConfig <- ZIO
          .attempt(ConfigProvider.fromYamlString(yamlString).load(config))
          .flatten
          .mapError(err =>
            s"Failed to parse configuration in file '$CONFIG_FILE': ${err.getMessage}"
          )

        _ <- ZIO
          .fromEither(appConfig.crawler.validate)
          .mapError(err => s"Failed to validate crawler configuration in file '$CONFIG_FILE': $err")

        _ <- ZIO
          .fromEither(appConfig.scheduler.validate)
          .mapError(err =>
            s"Failed to validate scheduler configuration in file '$CONFIG_FILE': $err"
          )

        _ <- ZIO
          .fromEither(appConfig.websocket.validate)
          .mapError(err =>
            s"Failed to validate websocket configuration in file '$CONFIG_FILE': $err"
          )
      } yield appConfig
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
