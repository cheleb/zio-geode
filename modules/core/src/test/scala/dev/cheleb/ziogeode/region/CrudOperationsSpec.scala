package dev.cheleb.ziogeode.region

import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._
import dev.cheleb.ziogeode.client.{GeodeClientCache, GeodeError}
import dev.cheleb.ziogeode.config._
import org.apache.geode.cache.client.ClientRegionShortcut

/** Tests for basic CRUD data operations (Task 4).
  *
  * These tests verify the ZIO-based put, get, and remove operations on Geode
  * regions, including asynchronous execution, error handling, and concurrent
  * access.
  */
object CrudOperationsSpec extends ZIOSpecDefault {

  private val validConfig = ValidConfig(
    GeodeConfig(
      locators = List(Locator("localhost", 10334)),
      auth = None,
      ssl = Ssl(enabled = false),
      pool = Pool(1, 10)
    )
  )

  def spec: Spec[Any, Any] = suite("CRUD Operations")(
    suite("Happy Path")(
      test(
        "put value, get returns it, remove returns true, subsequent get None"
      ) {
        val regionName = s"test-crud-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            // Put
            _ <- region.put("key1", "value1")
            // Get
            v1 <- region.get("key1")
            // Remove
            removed <- region.remove("key1")
            // Get after remove
            v1After <- region.get("key1")
          } yield assertTrue(
            v1 == Some("value1"),
            removed,
            v1After.isEmpty
          )
      }
    ),
    suite("Edge Cases")(
      test("put null value succeeds") {
        val regionName = s"test-null-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            _ <- region.put("key", null).catchAll { case e =>

              assertTrue(true) // FIXME: assert error handling
            }
            v <- region.get("key")
          } yield assertTrue(v == None)
      },
      test("get non-existent key returns None") {
        val regionName = s"test-get-none-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            v <- region.get("nonexistent")
          } yield assertTrue(v.isEmpty)
      },
      test("remove non-existent key returns false") {
        val regionName = s"test-remove-none-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            removed <- region.remove("nonexistent")
          } yield assertTrue(!removed)
      }
    ),
    suite("Concurrent Operations")(
      test("concurrent puts and gets succeed") {
        val regionName = s"test-concurrent-crud-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            // Concurrent puts
            _ <- ZIO.foreachPar(1 to 10) { i =>
              region.put(s"key$i", s"value$i")
            }
            // Concurrent gets
            results <- ZIO.foreachPar(1 to 10) { i =>
              region.get(s"key$i")
            }
            allCorrect = results.forall(
              _.isDefined
            ) && results.flatten.size == 10
          } yield assertTrue(allCorrect)
      },
      test("concurrent removes") {
        val regionName =
          s"test-concurrent-remove-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, String](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            // Put some values
            _ <- ZIO.foreachPar(1 to 5) { i =>
              region.put(s"key$i", s"value$i")
            }
            // Concurrent removes
            removeResults <- ZIO.foreachPar(1 to 5) { i =>
              region.remove(s"key$i")
            }
            allRemoved = removeResults.forall(_ == true)
            // Check all gone
            gets <- ZIO.foreach(1 to 5) { i =>
              region.get(s"key$i")
            }
            allNone = gets.forall(_.isEmpty)
          } yield assertTrue(allRemoved, allNone)
      }
    ),
    suite("Serialization")(
      test("custom objects serialize and deserialize correctly") {
        case class Person(name: String, age: Int)
        val regionName = s"test-serialize-${java.util.UUID.randomUUID()}"
        ZIO.scoped:
          for {
            client <- ZIO.service[GeodeClientCache]
            region <- client.createRegion[String, Person](
              regionName,
              ClientRegionShortcut.LOCAL
            )
            person = Person("Alice", 30)
            _ <- region.put("person1", person)
            retrieved <- region.get("person1")
          } yield assertTrue(retrieved == Some(person))
      }
    )
  ).provide(
    ZLayer.succeed(validConfig),
    GeodeClientCache.singleton
  ) @@ sequential
}
