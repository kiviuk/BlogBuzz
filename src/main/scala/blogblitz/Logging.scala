package blogblitz

import zio.*
import zio.logging.*

import zio.config.typesafe.TypesafeConfigProvider

object Logging {
  val configString: String =
    s"""
       |logger {
       |
       |  format = "%timestamp{yyyy-MM-dd'T'HH:mm:ssZ} %highlight{%fixed{7}{%level}} %color{BLUE}{%name:%line} %color{GREEN}{%message} %cause"
       |
       |  filter {
       |    mappings {
       |      "blogblitz"         = "INFO",
       |      "blogblitz.Crawler" = "INFO",
       |      "io.netty"          = "INFO",
       |    }
       |  }
       |}
       |""".stripMargin

  val configProvider: ConfigProvider = TypesafeConfigProvider.fromHoconString(configString)

  val consoleJsonLoggerʹ = Runtime.removeDefaultLoggers >>> Runtime.setConfigProvider(
    configProvider
  ) >>> consoleLogger()

}
