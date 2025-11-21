package dev.cheleb.ziogeode.region

import org.apache.geode.cache.Region

import scala.jdk.CollectionConverters.*

/** Wrapper around Geode Region to provide type-safe operations.
  *
  * @param region
  *   the underlying Geode region
  */
class GeodeRegion[K, V](region: Region[K, V]) extends AutoCloseable {
  def get(key: K): Option[V] =
    Option(region.get(key))

  def put(key: K, value: V): Unit =
    region.put(key, value)

  def remove(key: K): Unit =
    region.remove(key)

  def getAll(keys: java.util.Set[K]): Map[K, V] =
    region.getAll(keys).asScala.toMap

  def putAll(map: Map[K, V]): Unit =
    region.putAll(map.asJava)

  override def close(): Unit = region.close()
}
