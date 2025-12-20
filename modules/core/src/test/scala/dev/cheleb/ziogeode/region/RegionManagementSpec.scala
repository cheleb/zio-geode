package dev.cheleb.ziogeode.region

import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._
import dev.cheleb.ziogeode.client.{GeodeClientCache, GeodeError}
import dev.cheleb.ziogeode.config._
import org.apache.geode.cache.client.ClientRegionShortcut

/** Integration tests for region management operations.
  *
  * These tests require a running Geode server and test:
  *   - Region creation with different types
  *   - Region retrieval
  *   - Region destruction
  *   - Concurrent operations
  *   - Error handling
  */
object RegionManagementSpec extends ZIOSpecDefault {

  private val validConfig = ValidConfig(
    GeodeConfig(
      locators = List(Locator("localhost", 10334)),
      auth = None,
      ssl = Ssl(enabled = false),
      pool = Pool(1, 10)
    )
  )

  def spec: Spec[Any, Any] = suite("Region Management")(
    suite("Happy Path")(
      test("create region successfully with CachingProxy type") {
        val regionName = s"test-region-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.CACHING_PROXY
            )
            // Evaluate properties before scope closes
            name = region.name
            empty = region.isEmpty
          } yield assertTrue(
            name == regionName,
            empty
          )
      },
      test("create region successfully with Local type") {
        val regionName = s"test-local-region-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, Int](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            _ <- ZIO.debug(s"Created region: ${region.name}")
            _ <- region.put("key1", 42)
            value <- region.get("key1")
            name = region.name
          } yield assertTrue(
            name == regionName,
            value == Some(42)
          )
      },
      test("get existing region returns Some") {
        val regionName = s"test-get-region-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            _ <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            regionOpt <- client.getRegion[String, String](regionName)
            isDefined = regionOpt.isDefined
          } yield assertTrue(isDefined)
      },
      test("destroy region successfully") {
        val regionName = s"test-destroy-region-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            // Create region
            region <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            _ <- region.put("key", "value")
            // Verify it exists
            existsBefore <- client.getRegion[String, String](regionName)
            hasValueBefore = existsBefore.isDefined
            // Destroy it (this will remove from cache and Geode)
            _ <- client.destroyRegion(regionName)
            // Verify it's gone from the client cache
            existsAfter <- client.getRegion[String, String](regionName)
            isGone = existsAfter.isEmpty
            // Region is destroyed, so check isDestroyed
            isDestroyed = region.isDestroyed
          } yield assertTrue(
            hasValueBefore,
            isGone,
            isDestroyed
          )
      },
      test("perform CRUD operations on region") {
        val regionName = s"test-crud-region-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            // Put
            _ <- region.put("key1", "value1")
            _ <- region.put("key2", "value2")
            // Get
            v1 <- region.get("key1")
            v2 <- region.get("key2")
            // Check exists
            exists1 = region.containsKey("key1")
            exists3 = region.containsKey("key3")
            // Size
            size = region.size
            // Remove
            removed <- region.remove("key1")
            notRemoved <- region.remove("nonexistent")
            sizeAfter = region.size
            v1After <- region.get("key1")
          } yield assertTrue(
            v1 == Some("value1"),
            v2 == Some("value2"),
            exists1,
            !exists3,
            size == 2,
            removed,
            !notRemoved,
            sizeAfter == 1,
            v1After.isEmpty
          )
      },
      test("list regions returns created regions") {
        val regionName1 = s"test-list-region-1-${java.util.UUID.randomUUID()}"
        val regionName2 = s"test-list-region-2-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            _ <- client.createRegion[String, String](
              regionName1,
              ClientRegionShortcut.LOCAL
            )
            _ <- client.createRegion[String, Int](
              regionName2,
              ClientRegionShortcut.LOCAL
            )
            regions <- client.listRegions()
            contains1 = regions.contains(regionName1)
            contains2 = regions.contains(regionName2)
          } yield assertTrue(
            contains1,
            contains2
          )
      }
    ),
    suite("Edge Cases - Creation")(
      test("create region that already exists fails with RegionAlreadyExists") {
        val regionName = s"test-dup-region-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            _ <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            result <- client
              .createRegion[String, String](
                regionName,
                ClientRegionShortcut.LOCAL
              )
              .either
            isLeft = result.isLeft
            isCorrectError = result.left.exists {
              case GeodeError.RegionAlreadyExists(name) => name == regionName
              case _                                    => false
            }
          } yield assertTrue(isLeft, isCorrectError)
      }
    ),
    suite("Edge Cases - Retrieval")(
      test("get non-existent region returns None") {
        for {
          client <- ZIO.service[GeodeClientCache]
          regionOpt <- client
            .getRegion[String, String]("nonexistent-region-12345")
        } yield assertTrue(regionOpt.isEmpty)
      }
    ),
    suite("Edge Cases - Destruction")(
      test("destroy non-existent region fails with RegionNotFound") {
        for {
          client <- ZIO.service[GeodeClientCache]
          result <- client.destroyRegion("nonexistent-region-67890").either
          isLeft = result.isLeft
          isCorrectError = result.left.exists {
            case GeodeError.RegionNotFound(_) => true
            case _                            => false
          }
        } yield assertTrue(isLeft, isCorrectError)
      },
      test("destroy region removes from cache and Geode") {
        // Test that destroy properly removes the region
        val regionName = s"test-destroy-explicit-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            // Create region
            region <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            _ <- region.put("key", "value")
            getValue <- region.get("key")
            // Destroy through client
            _ <- client.destroyRegion(regionName)
            // Verify region is destroyed
            destroyed = region.isDestroyed
            // Try to get it again - should be None since it was destroyed
            afterDestroy <- client.getRegion[String, String](regionName)
            isNone = afterDestroy.isEmpty
          } yield assertTrue(
            getValue == Some("value"),
            destroyed,
            isNone
          )
      }
    ),
    suite("Concurrent Operations")(
      test("concurrent region creation with different names succeeds") {
        val baseRegionName =
          s"test-concurrent-region-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            results <- ZIO.foreachPar(1 to 5) { i =>
              client
                .createRegion[String, String](
                  s"$baseRegionName-$i",
                  ClientRegionShortcut.LOCAL
                )
                .map(_.name)
            }
            resultSize = results.size
            uniqueSize = results.toSet.size
          } yield assertTrue(
            resultSize == 5,
            uniqueSize == 5
          )
      },
      test("concurrent get operations on same region") {
        val regionName = s"test-concurrent-get-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            _ <- region.put("key", "value")
            results <- ZIO.foreachPar(1 to 10) { _ =>
              client.getRegion[String, String](regionName)
            }
            allDefined = results.forall(_.isDefined)
          } yield assertTrue(allDefined)
      }
    ),
    suite("GeodeRegion Operations")(
      test("putAll and getAll work correctly") {
        val regionName = s"test-batch-region-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, Int](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            _ = region.putAll(Map("a" -> 1, "b" -> 2, "c" -> 3))
            keys = new java.util.HashSet[String]()
            _ = keys.add("a")
            _ = keys.add("b")
            result = region.getAll(keys)
            resultSize = result.size
            aValue = result("a")
            bValue = result("b")
          } yield assertTrue(
            resultSize == 2,
            aValue == 1,
            bValue == 2
          )
      },
      test("clear removes all entries") {
        val regionName = s"test-clear-region-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            _ <- region.put("key1", "value1")
            _ <- region.put("key2", "value2")

            sizeBefore = region.size
            _ = region.clear()
            sizeAfter = region.size
            empty = region.isEmpty
          } yield assertTrue(
            sizeBefore == 2,
            sizeAfter == 0,
            empty
          )
      },
      test("keySet returns all keys") {
        val regionName = s"test-keyset-region-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, Int](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            _ <- region.put("x", 1)
            _ <- region.put("y", 2)
            _ <- region.put("z", 3)
            keys = region.keySet
            keysMatch = keys == Set("x", "y", "z")
          } yield assertTrue(keysMatch)
      }
    )
  ).provide(
    ZLayer.succeed(validConfig),
    GeodeClientCache.singleton()
  ) @@ sequential
}
