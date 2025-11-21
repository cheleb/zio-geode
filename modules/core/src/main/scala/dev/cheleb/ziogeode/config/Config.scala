package dev.cheleb.ziogeode.config

import java.nio.file.{Files, Paths}

case class Locator(host: String, port: Int)

case class Auth(username: String, password: String)

case class Ssl(
    enabled: Boolean,
    keystorePath: Option[String] = None,
    keystorePassword: Option[String] = None,
    truststorePath: Option[String] = None,
    truststorePassword: Option[String] = None
)

case class Pool(minConnections: Int, maxConnections: Int)

case class GeodeConfig(
    locators: List[Locator],
    auth: Option[Auth],
    ssl: Ssl,
    pool: Pool
)

sealed trait ConfigError

object ConfigError {
  case class InvalidLocatorFormat(locator: Locator) extends ConfigError
  case object MissingRequiredFields extends ConfigError
  case class SslEnabledWithoutKeystore(ssl: Ssl) extends ConfigError
  case class PoolConstraintsViolated(pool: Pool) extends ConfigError
}

case class ValidConfig(config: GeodeConfig)

object ValidConfig {
  def validate(config: GeodeConfig): Either[ConfigError, ValidConfig] = {
    val locatorErrors = config.locators.filterNot(isValidLocator)
    if (locatorErrors.nonEmpty)
      Left(ConfigError.InvalidLocatorFormat(locatorErrors.head))
    else if (config.locators.isEmpty) Left(ConfigError.MissingRequiredFields)
    else if (
      config.ssl.enabled && (config.ssl.keystorePath.isEmpty || config.ssl.truststorePath.isEmpty)
    ) {
      Left(ConfigError.SslEnabledWithoutKeystore(config.ssl))
    } else if (config.pool.minConnections > config.pool.maxConnections) {
      Left(ConfigError.PoolConstraintsViolated(config.pool))
    } else if (
      config.ssl.enabled && config.ssl.keystorePath.exists(!fileExists(_))
    ) {
      Left(ConfigError.SslEnabledWithoutKeystore(config.ssl)) // simplified
    } else Right(ValidConfig(config))
  }

  private def isValidLocator(locator: Locator): Boolean =
    locator.host.nonEmpty && locator.port > 0 && locator.port < 65536

  private def fileExists(path: String): Boolean =
    Files.exists(Paths.get(path))
}
