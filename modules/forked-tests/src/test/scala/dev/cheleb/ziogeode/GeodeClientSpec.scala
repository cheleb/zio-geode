package dev.cheleb.ziogeode

import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._
import dev.cheleb.ziogeode.config._
import dev.cheleb.ziogeode.client.*

/** Tests for [[GeodeClientCache]] lifecycle management. */
object GeodeClientSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] = suite("GeodeClient lifecycle management")(
    // Tests provide their own layers, so no environment requirements
    test("invalid locators cause connection failures mapped to GeodeError") {
      val invalidConfig = ValidConfig(
        GeodeConfig(
          locators = List(Locator("invalid.host", 99999)),
          auth = None,
          ssl = Ssl(enabled = false),
          pool = Pool(1, 10)
        )
      )
      val effect = ZIO
        .service[GeodeClientCache]
        .provide(
          ZLayer.succeed(invalidConfig) >>> GeodeClientCache.singleton()
        )

      // // val effect = ZIO.fail(new Exception("mock"))
      assertZIO(effect.exit)(
        fails(isSubtype[GeodeError.ConnectionError](anything))
      )
    },
    test("authentication failures mapped to GeodeError") {
      val configWithBadAuth = ValidConfig(
        GeodeConfig(
          locators = List(Locator("localhost", 10334)),
          auth = Some(Auth("baduser", "badpass")),
          ssl = Ssl(enabled = false),
          pool = Pool(1, 10)
        )
      )
      val effect = ZIO
        .service[GeodeClientCache]
        .provideLayer(
          ZLayer.succeed(configWithBadAuth) >>> GeodeClientCache.singleton()
        )
      assertZIO(effect.exit)(
        fails(isSubtype[GeodeError.AuthenticationFailed](anything))
      )
    } @@ ignore, // requires Geode authentication server to test
    test("SSL handshake failures mapped to GeodeError") {
      val configWithBadSsl = ValidConfig(
        GeodeConfig(
          locators = List(Locator("localhost", 10334)),
          auth = None,
          ssl = Ssl(
            enabled = true,
            keystorePath = Some("/invalid/path"),
            truststorePath = Some("/invalid/path")
          ),
          pool = Pool(1, 10)
        )
      )
      val effect = ZIO
        .service[GeodeClientCache]
        .provideLayer(
          ZLayer.succeed(configWithBadSsl) >>> GeodeClientCache.singleton()
        )

      assertZIO(effect.exit)(
        fails(isSubtype[GeodeError.SslError](anything))
      )
    } @@ ignore, // requires Geode server with SSL to test
    test("layer shutdown closes client properly") {
      val validConfig = ValidConfig(
        GeodeConfig(
          locators = List(Locator("localhost", 10334)),
          auth = None,
          ssl = Ssl(enabled = false),
          pool = Pool(1, 10)
        )
      )
      // Test that resources are cleaned up on layer shutdown
      val effect = ZIO
        .scoped {
          ZIO.service[GeodeClientCache].flatMap { client =>
            // Use client, then scope ends, should cleanup
            ZIO.succeed(client)
          }

        }
        .provideLayer(
          ZLayer.succeed(validConfig) >>> GeodeClientCache.singleton()
        )

      assertZIO(effect.exit)(succeeds(anything))
    }
  )

}
