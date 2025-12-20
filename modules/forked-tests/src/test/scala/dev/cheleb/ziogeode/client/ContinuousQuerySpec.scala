package dev.cheleb.ziogeode.client

import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._
import dev.cheleb.ziogeode.config._
import dev.cheleb.ziogeode.region.GeodeRegion
import org.apache.geode.cache.client.ClientRegionShortcut

/** Regions must exists !
  *
  * see scripts/test-setup.sh
  */
object ContinuousQuerySpec extends ZIOSpecDefault {

  private val validConfig = ValidConfig(
    GeodeConfig(
      locators = List(Locator("localhost", 10334)),
      auth = None,
      ssl = Ssl(enabled = false),
      pool = Pool(1, 10)
    )
  )

  def spec: Spec[Any, Any] = suite("Continuous Query Streaming")(
    suite("Happy Path")(
      test("CQ registered, data changes trigger events") {
        ZIO.scoped {
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              "test-region",
              ClientRegionShortcut.CACHING_PROXY
            )
            stream = client.continuousQuery[String](
              "SELECT * FROM /test-region"
            )

            _ <- ZIO.debug(stream.toString())

            _ <- (ZIO.sleep(2.second) *> ZIO.debug(
              "Put"
            ) *> region.put(
              "key3",
              "value3"
            ) *> region.put(
              "key4",
              "value4"
            )).fork
            events <- ZIO.debug("Listen") *> stream
              .take(4)
              .tap(e => ZIO.debug(e.toString()))
              .runCollect
          } yield assert(events)(hasSize(equalTo(4)))
        }
      }
    ) @@ withLiveClock,
    suite("Edge Cases")(
      test("Invalid CQ query fails") {
        ZIO.scoped {
          for {
            client <- ZIO.service[GeodeClientCache]
            result = client.continuousQuery[String]("INVALID QUERY")
            e <- result.take(1).runCollect.either
          } yield assert(e)(isLeft)
        }
      },
      test("Concurrent CQs work") {
        ZIO.scoped {
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              "test-region2",
              ClientRegionShortcut.CACHING_PROXY
            )
            stream1 = client.continuousQuery[String](
              "SELECT * FROM /test-region2"
            )
            stream2 = client.continuousQuery[String](
              "SELECT * FROM /test-region2"
            )
            fiber1 <- stream1.take(1).runCollect.fork
            fiber2 <- stream2.take(1).runCollect.fork
            _ <- region.put("key1", "value1")
            events1 <- fiber1.join
            events2 <- fiber2.join
          } yield assert(events1)(hasSize(equalTo(1))) &&
            assert(events2)(hasSize(equalTo(1)))
        }
      }
    ),
    suite("Lifecycle")(
      test("CQ unregistered on stream close") {
        ZIO.scoped {
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              "test-region3",
              ClientRegionShortcut.CACHING_PROXY_OVERFLOW
            )
            stream = client.continuousQuery[String](
              "SELECT * FROM /test-region3"
            )
            _ <- region.put("key1", "value1")
            events <- stream.take(1).runCollect
            // Stream should be closed after scope
          } yield assert(events)(hasSize(equalTo(1)))
        }
      }
    ) @@ ignore
  )
    .provide(
      ZLayer.succeed(validConfig),
      GeodeClientCache.singleton(true)
    ) @@ withLiveClock @@ sequential @@ timeout(5.seconds)
}
