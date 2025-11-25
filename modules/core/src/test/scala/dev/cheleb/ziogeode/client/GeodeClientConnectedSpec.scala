package dev.cheleb.ziogeode.client

import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._
import dev.cheleb.ziogeode.config._
import dev.cheleb.ziogeode.region.GeodeRegion

object GeodeClientConnectedSpec extends ZIOSpecDefault {

  private val validConfig = ValidConfig(
    GeodeConfig(
      locators = List(Locator("localhost", 10334)),
      auth = None,
      ssl = Ssl(enabled = false),
      pool = Pool(1, 10)
    )
  )
  def spec: Spec[Any, Any] = suite("GeodeClient lifecycle management")(
    // Tests provide their own layers,
    // so no environment requirements
    test("layer creates client successfully with valid config") {

      // This test would need a mock or test container setup
      // For now, assuming the layer is implemented
      val effect = ZIO
        .service[GeodeClientCache]

      assertZIO(effect.map(_.isConnected()))(isTrue)
    },
    test("multiple fibers access client simultaneously without issues") {
      val validConfig = ValidConfig(
        GeodeConfig(
          locators = List(Locator("localhost", 10334)),
          auth = None,
          ssl = Ssl(enabled = false),
          pool = Pool(1, 10)
        )
      )
      val concurrentAccess =
        ZIO.foreachPar(1 to 10)(_ =>
          ZIO.service[GeodeClientCache].flatMap { client =>
            // Simulate concurrent access
            ZIO.succeed(client.isConnected())
          }
        )

      assertZIO(concurrentAccess)(forall(isTrue))
    }
  )
    .provide(
      ZLayer.succeed(validConfig),
      GeodeClientCache.singleton
    )

}
