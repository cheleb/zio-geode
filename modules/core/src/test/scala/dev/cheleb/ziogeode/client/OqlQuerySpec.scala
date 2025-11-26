package dev.cheleb.ziogeode.client

import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.stream.ZSink
import dev.cheleb.ziogeode.config._
import org.apache.geode.cache.client.ClientRegionShortcut

/** Integration tests for OQL query execution.
  *
  * These tests require a running Geode server with no authentication and
  * existing regions
  *
  * Test:
  *   - Query execution with streaming results
  *   - Query execution with collected results
  *   - Parameterized queries
  *   - Error handling for invalid queries
  */
object OqlQuerySpec extends ZIOSpecDefault {

  private val validConfig = ValidConfig(
    GeodeConfig(
      locators = List(Locator("localhost", 10334)),
      auth = None,
      ssl = Ssl(enabled = false),
      pool = Pool(1, 10)
    )
  )

  def spec: Spec[Any, Any] = suite("OQL Query Execution")(
    suite("Happy Path")(
      test("executeQuery returns stream with correct results") {
        val regionName = s"test-query-region-int"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, Int](
              regionName,
              ClientRegionShortcut.CACHING_PROXY
            )
            _ <- region.put("key1", 1)
            _ <- region.put("key2", 2)
            _ <- region.put("key3", 3)
            stream <- client
              .executeQuery[Int](s"SELECT * FROM /$regionName")
            results <- stream.run(ZSink.collectAll[Int])
            sortedResults = results.sorted
          } yield assertTrue(sortedResults == Chunk(1, 2, 3))
      },
      test("executeQueryCollect returns chunk with correct results") {
        val regionName =
          s"test-query-collect-region-fruit"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, Int](
              regionName,
              ClientRegionShortcut.CACHING_PROXY
            )
            _ <- region.put("aa", 1)
            _ <- region.put("bb", 2)
            results <- client.executeQueryCollect[Int](
              s"SELECT * FROM /$regionName",
              Map.empty
            )
            sortedResults = results.sorted
          } yield assertTrue(sortedResults == Chunk(1, 2))
      },
      test("parameterized query works correctly") {
        val regionName =
          s"test-param-query-region-int2"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, Int](
              regionName,
              ClientRegionShortcut.CACHING_PROXY
            )
            _ <- region.put("key1", 10)
            _ <- region.put("key2", 20)
            _ <- region.put("key3", 30)
            stream <- client.executeQuery[Int](
              s"SELECT * FROM /$regionName v WHERE v > $$1",
              15
            )
            results <- stream.run(ZSink.collectAll[Int])
            sortedResults = results.sorted
          } yield assertTrue(sortedResults == Chunk(20, 30))
      }
    ),
    suite("Edge Cases")(
      test("invalid OQL syntax fails with QueryError") {
        for {
          client <- ZIO.service[GeodeClientCache]
          result <- client
            .executeQueryCollect[String]("INVALID QUERY SYNTAX", Map.empty)
            .either
          isLeft = result.isLeft
          isQueryError = result.left.exists {
            case GeodeError.QueryError(_) => true
            case _                        => false
          }
        } yield assertTrue(isLeft, isQueryError)
      },
      test("query on non-existent region fails with QueryError") {
        for {
          client <- ZIO.service[GeodeClientCache]
          result <- client
            .executeQueryCollect[String](
              "SELECT * FROM /nonexistent-region-123",
              Map.empty
            )
            .either
          isLeft = result.isLeft
          isQueryError = result.left.exists {
            case GeodeError.QueryError(_) => true
            case _                        => false
          }
        } yield assertTrue(isLeft, isQueryError)
      },
      test("empty results return empty chunk") {
        val regionName =
          s"test-empty-query-region-int"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, Int](
              regionName,
              ClientRegionShortcut.CACHING_PROXY
            )
            results <- client.executeQueryCollect[Int](
              s"SELECT * FROM /$regionName WHERE value > 100",
              Map.empty
            )
            isEmpty = results.isEmpty
          } yield assertTrue(isEmpty)
      },
      test("large dataset streams correctly") {
        val regionName =
          s"test-large-query-region-int"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, Int](
              regionName,
              ClientRegionShortcut.CACHING_PROXY
            )
            // Put 1000 entries
            _ <- ZIO.foreachDiscard(1 to 1000) { i =>
              region.put(s"key$i", i)
            }
            stream <- client
              .executeQuery[Int](s"SELECT * FROM /$regionName")
            results <- stream.run(ZSink.collectAll[Int])
            resultSize = results.size
            sum = results.sum
          } yield assertTrue(
            resultSize == 1000,
            sum == (1 to 1000).sum
          )
      }
    )
  ).provide(
    ZLayer.succeed(validConfig),
    GeodeClientCache.singleton
  ) @@ sequential
}
