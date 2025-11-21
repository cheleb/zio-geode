package dev.cheleb.ziogeode.config

import zio.test._
import zio.test.Assertion.*
import zio.test.TestAspect.*

object GeodeConfigSpec extends ZIOSpecDefault {

  def spec = suite("GeodeConfig validation")(
    test("valid config with all fields set correctly validates successfully") {
      val config = GeodeConfig(
        locators = List(Locator("localhost", 10334)),
        auth = Some(Auth("user", "pass")),
        ssl = Ssl(
          enabled = true,
          keystorePath = Some("src/test/resources/keystore.jks"),
          keystorePassword = Some("password"),
          truststorePath = Some("/path/to/truststore"),
          truststorePassword = Some("password")
        ),
        pool = Pool(minConnections = 1, maxConnections = 10)
      )
      val result = ValidConfig.validate(config)
      assert(result)(isRight)
    }, // Ignored due to file existence check
    test("invalid locator formats fail validation") {
      val config = GeodeConfig(
        locators = List(Locator("", 10334)), // empty host
        auth = None,
        ssl = Ssl(enabled = false),
        pool = Pool(1, 10)
      )
      val result = ValidConfig.validate(config)
      assert(result)(
        isLeft(equalTo(ConfigError.InvalidLocatorFormat(Locator("", 10334))))
      )
    },
    test("non-numeric ports fail validation") {
      val config = GeodeConfig(
        locators = List(Locator("localhost", -1)), // invalid port
        auth = None,
        ssl = Ssl(enabled = false),
        pool = Pool(1, 10)
      )
      val result = ValidConfig.validate(config)
      assert(result)(
        isLeft(
          equalTo(ConfigError.InvalidLocatorFormat(Locator("localhost", -1)))
        )
      )
    },
    test("missing required fields fail validation") {
      val config = GeodeConfig(
        locators = Nil, // empty locators
        auth = None,
        ssl = Ssl(enabled = false),
        pool = Pool(1, 10)
      )
      val result = ValidConfig.validate(config)
      assert(result)(isLeft(equalTo(ConfigError.MissingRequiredFields)))
    },
    test("SSL enabled without keystore paths fail validation") {
      val config = GeodeConfig(
        locators = List(Locator("localhost", 10334)),
        auth = None,
        ssl = Ssl(enabled = true, keystorePath = None, truststorePath = None),
        pool = Pool(1, 10)
      )
      val result = ValidConfig.validate(config)
      assert(result)(
        isLeft(
          equalTo(
            ConfigError.SslEnabledWithoutKeystore(
              Ssl(enabled = true, keystorePath = None, truststorePath = None)
            )
          )
        )
      )
    },
    test("pool min > max fails validation") {
      val config = GeodeConfig(
        locators = List(Locator("localhost", 10334)),
        auth = None,
        ssl = Ssl(enabled = false),
        pool = Pool(minConnections = 10, maxConnections = 5)
      )
      val result = ValidConfig.validate(config)
      assert(result)(
        isLeft(equalTo(ConfigError.PoolConstraintsViolated(Pool(10, 5))))
      )
    }
  )
}
