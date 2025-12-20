package dev.cheleb.ziogeode.client

import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._
import dev.cheleb.ziogeode.config._
import dev.cheleb.ziogeode.region.GeodeRegion
import org.apache.geode.cache.client.ClientRegionShortcut

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
            stream <- client.continuousQuery[String](
              "SELECT * FROM /test-region"
            )

            _ <- (ZIO.debug("Put") *> ZIO.sleep(2.second) *> ZIO.debug(
              "Put"
            ) *> region.put(
              "key1",
              "value1"
            )).fork
            events <- ZIO.debug("Listen") *> stream.take(1).runCollect
          } yield assert(events)(hasSize(equalTo(1)))
        }
      }
    ),
    suite("Edge Cases")(
      test("Invalid CQ query fails") {
        ZIO.scoped {
          for {
            client <- ZIO.service[GeodeClientCache]
            result <- client.continuousQuery[String]("INVALID QUERY").exit
          } yield assert(result)(
            fails(isSubtype[GeodeError.QueryError](anything))
          )
        }
      } @@ ignore,
      test("Concurrent CQs work") {
        ZIO.scoped {
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              "test-region2",
              ClientRegionShortcut.CACHING_PROXY
            )
            stream1 <- client.continuousQuery[String](
              "SELECT * FROM /test-region2"
            )
            stream2 <- client.continuousQuery[String](
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
    ) @@ ignore,
    suite("Lifecycle")(
      test("CQ unregistered on stream close") {
        ZIO.scoped {
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              "test-region3",
              ClientRegionShortcut.CACHING_PROXY
            )
            stream <- client.continuousQuery[String](
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
