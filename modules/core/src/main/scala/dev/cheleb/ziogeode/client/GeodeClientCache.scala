package dev.cheleb.ziogeode.client

import zio._
import zio.stream.ZStream
import org.apache.geode.cache.client.{ClientCache, ClientCacheFactory}
import org.apache.geode.cache.{Region, GemFireCache}
import dev.cheleb.ziogeode.config.ValidConfig
import java.util.Properties
import org.apache.geode.distributed.ConfigurationProperties
import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.{SSLContext, TrustManagerFactory, KeyManagerFactory}
import scala.jdk.CollectionConverters.*
import dev.cheleb.ziogeode.region.{GeodeRegion}
import org.apache.geode.cache.client.ClientRegionShortcut
import org.apache.geode.cache.query.{
  Query,
  QueryService,
  SelectResults,
  CqQuery,
  CqEvent as GeodeCqEvent,
  CqListener,
  CqAttributesFactory
}
import org.apache.geode.cache.{EntryEvent, Operation}
import org.apache.geode.cache.util.CqListenerAdapter
import scala.util.Try
import scala.util.Failure
import scala.util.Success

// Error types for Geode operations
sealed trait GeodeError
object GeodeError {
  case class ConnectionError(message: String) extends GeodeError
  case class AuthenticationFailed(message: String) extends GeodeError
  case class SslError(message: String) extends GeodeError
  case class RegionError(message: String) extends GeodeError
  case class RegionAlreadyExists(regionName: String) extends GeodeError
  case class RegionNotFound(regionName: String) extends GeodeError
  case class QueryError(message: String) extends GeodeError
  case class TransactionError(message: String) extends GeodeError
  case class SerializationError(message: String) extends GeodeError
  case class GenericError(message: String, cause: Throwable) extends GeodeError
}

// CQ Event types
sealed trait CqEvent[T]
object CqEvent {
  case class Old[T](value: T) extends CqEvent[T]
  case class Created[T](key: Any, value: T) extends CqEvent[T]
  case class Updated[T](key: Any, oldValue: T, newValue: T) extends CqEvent[T]
  case class Destroyed[T](key: Any) extends CqEvent[T]
  case class Error[T](throable: Throwable) extends CqEvent[T]
}

// GeodeClient service trait
trait GeodeClientCache {

  /** Check if the client is connected to Geode.
    *
    * @return
    *   true if connected, false otherwise
    */
  def isConnected(): Boolean

  /** Create a new region with the specified type.
    *
    * @param name
    *   the name of the region to create
    * @param regionType
    *   the type of region (PARTITIONED, REPLICATED, etc.)
    * @return
    *   a ZIO effect producing a GeodeRegion
    */
  def createRegion[K, V](
      name: String,
      regionType: ClientRegionShortcut
  ): ZIO[Scope, GeodeError, GeodeRegion[K, V]]

  /** Get an existing region by name.
    *
    * @param name
    *   the name of the region to get
    * @return
    *   a ZIO effect producing Some(region) if found, None otherwise
    */
  def getRegion[K, V](
      name: String
  ): ZIO[Any, GeodeError, Option[GeodeRegion[K, V]]]

  /** Destroy a region by name.
    *
    * @param name
    *   the name of the region to destroy
    * @return
    *   a ZIO effect that completes when the region is destroyed
    */
  def destroyRegion(name: String): ZIO[Any, GeodeError, Unit]

  /** List all region names.
    *
    * @return
    *   a ZIO effect producing a set of region names
    */
  def listRegions(): ZIO[Any, GeodeError, Set[String]]

  /** Execute an OQL query and return results as a stream.
    *
    * @param query
    *   the OQL query string
    * @param params
    *   parameters for the query
    * @return
    *   ZIO effect producing a stream of results
    */
  def executeQuery[T](
      query: String,
      params: Any*
  ): ZIO[Any, GeodeError, ZStream[Any, Nothing, T]]

  /** Execute an OQL query and collect all results into a Chunk.
    *
    * @param query
    *   the OQL query string
    * @param params
    *   parameters for the query
    * @return
    *   ZIO effect producing a Chunk of results
    */
  def executeQueryCollect[T](
      query: String,
      params: Any*
  ): ZIO[Any, GeodeError, Chunk[T]]

  /** Execute a continuous query and return a stream of events.
    *
    * @param query
    *   the CQ query string
    * @param params
    *   parameters for the query
    * @return
    *   ZStream of CQ events
    */

  def continuousQuery[T](
      query: String,
      params: Any*
  ): ZStream[Any, GeodeError, CqEvent[T]]
}

private class GeodeClientCacheLive(
    clientCache: ClientCache
) extends GeodeClientCache {

  override def isConnected(): Boolean =
    !clientCache.isClosed

  override def createRegion[K, V](
      name: String,
      regionType: ClientRegionShortcut
  ): ZIO[Scope, GeodeError, GeodeRegion[K, V]] =
    ZIO.fromAutoCloseable:
      for {
        // Check if region already exists in cache

        // Check if region already exists in Geode
        existingRegion <- ZIO
          .attemptBlocking {
            clientCache.getRegion[K, V](name)
          }
          .mapError { case th: Throwable =>
            GeodeError.RegionError(
              s"Failed to check region '$name': ${th.getMessage}"
            )
          }
        _ <- ZIO.when(existingRegion != null) {
          ZIO.fail(GeodeError.RegionAlreadyExists(name))
        }
        // Create the region
        region <- ZIO
          .attemptBlocking {
            clientCache
              .createClientRegionFactory[K, V](
                regionType
              )
              .create(name)
          }
          .mapError {
            case e: org.apache.geode.cache.RegionExistsException =>
              GeodeError.RegionAlreadyExists(name)
            case th: Throwable =>
              GeodeError.RegionError(
                s"Failed to create region '$name': ${th.getMessage}"
              )
          }
        geodeRegion = GeodeRegion(region)

      } yield geodeRegion

  override def getRegion[K, V](
      name: String
  ): ZIO[Any, GeodeError, Option[GeodeRegion[K, V]]] =
    for {
      // First check the cache

      result <-
        // Try to get from Geode
        ZIO
          .attemptBlocking {
            val region = clientCache.getRegion[K, V](name)
            if (region != null && !region.isDestroyed) {
              Some(GeodeRegion(region))
            } else {
              None
            }
          }
          .mapError { case th: Throwable =>
            GeodeError.RegionError(
              s"Failed to get region '$name': ${th.getMessage}"
            )
          }

    } yield result

  override def destroyRegion(name: String): ZIO[Any, GeodeError, Unit] =
    for {
      // Get the region from cache or Geode
      regionOpt <-
        ZIO
          .attemptBlocking {
            val region = clientCache.getRegion[Any, Any](name)
            if (region != null && !region.isDestroyed) {
              Some(GeodeRegion(region))
            } else {
              None
            }
          }
          .mapError { case th: Throwable =>
            GeodeError.RegionError(
              s"Failed to get region '$name': ${th.getMessage}"
            )
          }

      // Destroy the region if it exists
      _ <- regionOpt match {
        case Some(region) =>
          ZIO
            .attemptBlocking {
              region.destroy()
            }
            .mapError { case th: Throwable =>
              GeodeError.RegionError(
                s"Failed to destroy region '$name': ${th.getMessage}"
              )
            }
        case None =>
          ZIO.fail(GeodeError.RegionNotFound(name))
      }
    } yield ()

  override def listRegions(): ZIO[Any, GeodeError, Set[String]] =
    ZIO
      .attemptBlocking {
        clientCache.rootRegions().asScala.map(_.getName).toSet
      }
      .mapError { case th: Throwable =>
        GeodeError.RegionError(
          s"Failed to list regions: ${th.getMessage}"
        )
      }

  override def executeQuery[T](
      query: String,
      params: Any*
  ): ZIO[Any, GeodeError, ZStream[Any, Nothing, T]] =
    ZIO
      .attemptBlocking {
        val queryService = clientCache.getQueryService()
        val q = queryService.newQuery(query)
        val results =
          q.execute(params*).asInstanceOf[SelectResults[T]]
        results
      }
      .map { results =>
        ZStream.fromIterable(results.asScala, chunkSize = 1000)
      }
      .mapError {
        case e: org.apache.geode.cache.query.QueryInvalidException =>
          GeodeError.QueryError(s"Invalid query: ${e.getMessage}")
        case e: org.apache.geode.cache.query.QueryException =>
          GeodeError.QueryError(s"Query execution failed: ${e.getMessage}")
        case th: Throwable =>
          GeodeError.QueryError(s"Query failed: ${th.getMessage}")
      }

  override def executeQueryCollect[T](
      query: String,
      params: Any*
  ): ZIO[Any, GeodeError, Chunk[T]] =
    ZIO
      .attemptBlocking {
        val queryService = clientCache.getQueryService()
        val q = queryService.newQuery(query)
        val results =
          q.execute(params*).asInstanceOf[SelectResults[T]]
        Chunk.fromIterable(results.asScala)
      }
      .mapError {
        case e: org.apache.geode.cache.query.QueryInvalidException =>
          GeodeError.QueryError(s"Invalid query: ${e.getMessage}")
        case e: org.apache.geode.cache.query.QueryException =>
          GeodeError.QueryError(s"Query execution failed: ${e.getMessage}")
        case th: Throwable =>
          GeodeError.QueryError(s"Query failed: ${th.getMessage}")
      }

  def convert[T](event: GeodeCqEvent): CqEvent[T] =
    event.getNewValue() match
      case null => CqEvent.Destroyed(event.getKey())
      case o    => CqEvent.Created(event.getKey(), o.asInstanceOf[T])

  def continuousQuery[T](
      query: String,
      params: Any*
  ): ZStream[Any, GeodeError, CqEvent[T]] = {
    val queryService = clientCache.getQueryService()

    ZStream.asyncScoped { cb =>
      val listener = new CqListenerAdapter {
        override def onEvent(event: GeodeCqEvent): Unit = {
          val e = convert[T](event)
          cb(ZIO.succeed(Chunk(e)))
        }
        override def onError(event: GeodeCqEvent): Unit = {
          // Log error - in real implementation, might want to offer error event
          println(s"CQ error: ${event}")
          cb(ZIO.succeed(Chunk(CqEvent.Error(event.getThrowable()))))
        }
        override def close(): Unit = ()
      }

      val attrs = new CqAttributesFactory()
      attrs.addCqListener(listener)

      Try(
        queryService.newCq(
          s"cq-${java.lang.System.nanoTime()}",
          query,
          attrs.create()
        )
      ) match
        case Failure(exception) =>
          ZIO.fail(GeodeError.QueryError(exception.getMessage()))

        case Success(cqQuery) =>

          val result = cqQuery.executeWithInitialResults[T]()
          cb(
            ZIO.succeed(
              Chunk.fromIterable(
                result.asScala
                  .map(e => CqEvent.Old(e))
              )
            )
          )

          ZIO.unit.withFinalizer(a =>
            ZIO
              .logDebug("Closing cq") *> ZIO
              .attemptBlocking(cqQuery.close())
              .ignore
          )

    }
  }

}

// Companion object with layer creation
object GeodeClientCache {

  /** ZLayer that provides GeodeClientLive given ValidConfig
    *
    * Creates a new client cache with region caching support.
    *
    * @return
    *   ZLayer providing GeodeClientCacheLive
    */
  def layer(
      subscription: Boolean = false
  ): ZLayer[Scope & ValidConfig, GeodeError, GeodeClientCacheLive] =
    ZLayer:
      for {
        _ <- ZIO.logDebug("Creating GeodeClient layer")
        validConfig <- ZIO.service[ValidConfig]
        clientCache <- createClientCache(validConfig, subscription)
        regionCache <- Ref.make(Map.empty[String, GeodeRegion[?, ?]])
        _ <- ZIO.addFinalizer(
          ZIO.attemptBlocking {
            if (!clientCache.isClosed) {
              clientCache.close()
            }
          }.orDie
        )
      } yield new GeodeClientCacheLive(clientCache)

  private val singletonClientCache: Ref[Option[GeodeClientCacheLive]] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          zio.Scope.global
            .extend(Ref.make(Option.empty[GeodeClientCacheLive]))
        )
        .getOrThrow()
    }

  /** ZLayer that provides a singleton GeodeClientLive given ValidConfig
    *
    * This ensures only one ClientCache instance per JVM (Geode limitation).
    *
    * @return
    *   ZLayer providing GeodeClientCacheLive singleton
    */
  def singleton(
      subsription: Boolean = false
  ): ZLayer[ValidConfig, GeodeError, GeodeClientCacheLive] =
    ZLayer:
      for {
        _ <- ZIO.logDebug("Creating GeodeClient layer")
        geodeClientCache <- singletonClientCache.get.flatMap {
          case Some(value) =>
            ZIO.logDebug("Reusing existing singleton GeodeClientLive") *>
              ZIO.succeed(value)
          case None =>
            ZIO.logDebug("Creating new singleton GeodeClientLive") *> (for {
              validConfig <- ZIO.service[ValidConfig]
              clientCache <- createClientCache(validConfig, subsription)
              regionCache <- Ref.make(Map.empty[String, GeodeRegion[?, ?]])
              clientLive = new GeodeClientCacheLive(clientCache)
              _ <- singletonClientCache.set(Some(clientLive))

              _ = sys.addShutdownHook: // Ensure cleanup on JVM shutdown
                println("#### Shutting down GeodeClientCache singleton...")
                clientCache.close()

            } yield clientLive)
        }
      } yield geodeClientCache

  private def createClientCache(
      validConfig: ValidConfig,
      subscription: Boolean
  ): ZIO[Any, GeodeError, ClientCache] =
    ZIO.debug(
      s"######### Creating Geode ClientCache with subscription=$subscription"
    ) *>
      ZIO
        .attemptBlocking {
          val factory = new ClientCacheFactory()
          if (subscription) {
            factory.setPoolSubscriptionEnabled(true)
          }

          // Configure locators
          validConfig.config.locators.foreach { locator =>
            factory.addPoolLocator(locator.host, locator.port)
          }

          // Configure pool settings
          factory.setPoolMinConnections(validConfig.config.pool.minConnections)
          factory.setPoolMaxConnections(validConfig.config.pool.maxConnections)

          // Configure authentication
          validConfig.config.auth.foreach { auth =>
            factory.set("security-username", auth.username)
            factory.set("security-password", auth.password)
          }

          // Configure SSL if enabled
          if (validConfig.config.ssl.enabled) {
            configureSSL(factory, validConfig.config.ssl)
          }

          // Create the client cache
          factory.create()
        }
        .mapError {
          case e: org.apache.geode.security.AuthenticationFailedException =>
            GeodeError.AuthenticationFailed(
              s"Authentication failed: ${e.getMessage}"
            )
          case e: org.apache.geode.cache.client.ServerOperationException
              if e.getCause != null && e.getCause.getMessage.contains("SSL") =>
            GeodeError.SslError(s"SSL handshake failed: ${e.getMessage}")
          case e: java.net.ConnectException =>
            GeodeError.ConnectionError(
              s"Failed to connect to locators: ${e.getMessage}"
            )
          case e: IllegalArgumentException =>
            GeodeError.ConnectionError(
              s"Invalid locator configuration: ${e.getMessage}"
            )
          case e: Exception =>
            GeodeError.GenericError(
              s"Failed to create client cache: ${e.getMessage}",
              e
            )
        }

  private def configureSSL(
      factory: ClientCacheFactory,
      ssl: dev.cheleb.ziogeode.config.Ssl
  ): Unit = {
    // Set SSL properties
    factory.set("ssl-enabled-components", "all")
    factory.set("ssl-protocols", "TLSv1.2,TLSv1.3")
    factory.set("ssl-ciphers", "default")

    // Configure keystore
    ssl.keystorePath.foreach { path =>
      factory.set("ssl-keystore", path)
      ssl.keystorePassword.foreach { pwd =>
        factory.set("ssl-keystore-password", pwd)
      }
    }

    // Configure truststore
    ssl.truststorePath.foreach { path =>
      factory.set("ssl-truststore", path)
      ssl.truststorePassword.foreach { pwd =>
        factory.set("ssl-truststore-password", pwd)
      }
    }
  }
}
