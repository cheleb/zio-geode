package dev.cheleb.ziogeode.client

import zio._
import org.apache.geode.cache.client.{ClientCache, ClientCacheFactory}
import org.apache.geode.cache.{Region, GemFireCache}
import dev.cheleb.ziogeode.config.ValidConfig
import java.util.Properties
import org.apache.geode.distributed.ConfigurationProperties
import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.{SSLContext, TrustManagerFactory, KeyManagerFactory}
import scala.jdk.CollectionConverters.*
import dev.cheleb.ziogeode.region.GeodeRegion
import org.apache.geode.cache.client.ClientRegionShortcut

// Error types for Geode operations
sealed trait GeodeError
object GeodeError {
  case class ConnectionError(message: String) extends GeodeError
  case class AuthenticationFailed(message: String) extends GeodeError
  case class SslError(message: String) extends GeodeError
  case class RegionError(message: String) extends GeodeError
  case class QueryError(message: String) extends GeodeError
  case class TransactionError(message: String) extends GeodeError
  case class SerializationError(message: String) extends GeodeError
  case class GenericError(message: String, cause: Throwable) extends GeodeError
}

// GeodeClient service trait
trait GeodeClientCache {
  def isConnected(): Boolean
  def openRegion[K, V](
      regionName: String
  ): ZIO[Scope, GeodeError, GeodeRegion[K, V]]
}

private class GeodeClientCacheLive(clientCache: ClientCache)
    extends GeodeClientCache {
  override def isConnected(): Boolean =
    !clientCache.isClosed
  def openRegion[K, V](
      regionName: String
  ): ZIO[Scope, GeodeError, GeodeRegion[K, V]] =
    ZIO.fromAutoCloseable:
      for {
        region <- ZIO
          .attemptBlocking {
            clientCache
              .createClientRegionFactory[K, V](
                ClientRegionShortcut.CACHING_PROXY
              )
              .create(regionName)
          }
          .mapError { case th: Throwable =>
            GeodeError.RegionError(
              s"Failed to open region '$regionName': ${th.getMessage}"
            )
          }
        _ <- ZIO.when(region == null) {
          ZIO.fail(
            GeodeError.RegionError(s"Region '$regionName' does not exist.")
          )
        }
      } yield new GeodeRegion(region)
}

// Companion object with layer creation
object GeodeClientCache {

  /** ZLayer that provides GeodeClientLive given ValidConfig
    *
    * Cac
    *
    * @return
    */
  def layer: ZLayer[Scope & ValidConfig, GeodeError, GeodeClientCacheLive] =
    ZLayer:
      for {
        _ <- ZIO.logDebug("Creating GeodeClient layer")
        validConfig <- ZIO.service[ValidConfig]
        clientCache <- createClientCache(validConfig)
        _ <- ZIO.addFinalizer(
          ZIO.attemptBlocking {
            if (!clientCache.isClosed) {
              clientCache.close()
            }
          }.orDie
        )
      } yield new GeodeClientCacheLive(clientCache)

  val singletonClientCache: Ref[Option[GeodeClientCacheLive]] =
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
    * @return
    */
  def singleton: ZLayer[ValidConfig, GeodeError, GeodeClientCacheLive] =
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
              clientCache <- createClientCache(validConfig)
              clientLive = new GeodeClientCacheLive(clientCache)
              _ <- singletonClientCache.set(Some(clientLive))

              _ = sys.addShutdownHook: // Ensure cleanup on JVM shutdown
                println("Shutting down GeodeClientCache singleton...")
                clientCache.close()

            } yield clientLive)
        }
      } yield geodeClientCache

  private def createClientCache(
      validConfig: ValidConfig
  ): ZIO[Any, GeodeError, ClientCache] =
    ZIO
      .attemptBlocking {
        val factory = new ClientCacheFactory()

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
