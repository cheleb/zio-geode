package dev.cheleb.ziogeode.region

import org.apache.geode.cache.Region

import scala.jdk.CollectionConverters.*

/** Wrapper around Geode Region to provide type-safe operations.
  *
  * @param region
  *   the underlying Geode region
  * @param regionName
  *   the name of the region
  */
class GeodeRegion[K, V](region: Region[K, V], val regionName: String)
    extends AutoCloseable {

  /** Get the name of this region.
    *
    * @return
    *   the region name
    */
  def name: String = regionName

  /** Get a value by key.
    *
    * @param key
    *   the key to retrieve
    * @return
    *   Some(value) if found, None otherwise
    */
  def get(key: K): Option[V] =
    Option(region.get(key))

  /** Put a key-value pair into the region.
    *
    * @param key
    *   the key
    * @param value
    *   the value
    */
  def put(key: K, value: V): Unit =
    region.put(key, value)

  /** Remove an entry by key.
    *
    * @param key
    *   the key to remove
    * @return
    *   true if the entry existed and was removed, false otherwise
    */
  def remove(key: K): Boolean =
    region.remove(key) != null

  /** Check if a key exists in the region.
    *
    * @param key
    *   the key to check
    * @return
    *   true if the key exists, false otherwise
    */
  def containsKey(key: K): Boolean =
    region.containsKey(key)

  /** Get all entries for the specified keys.
    *
    * @param keys
    *   the set of keys to retrieve
    * @return
    *   a map of key-value pairs found
    */
  def getAll(keys: java.util.Set[K]): Map[K, V] =
    region.getAll(keys).asScala.toMap

  /** Put all key-value pairs into the region.
    *
    * @param map
    *   the map of entries to put
    */
  def putAll(map: Map[K, V]): Unit =
    region.putAll(map.asJava)

  /** Get the number of entries in the region.
    *
    * @return
    *   the size of the region
    */
  def size: Int = region.size()

  /** Check if the region is empty.
    *
    * @return
    *   true if empty, false otherwise
    */
  def isEmpty: Boolean = region.isEmpty

  /** Clear all entries from the region.
    */
  def clear(): Unit = region.clear()

  /** Get all keys in the region.
    *
    * @return
    *   set of all keys
    */
  def keySet: Set[K] = region.keySet().asScala.toSet

  /** Get the underlying Geode region (for advanced operations).
    *
    * @return
    *   the underlying Region instance
    */
  private[ziogeode] def underlying: Region[K, V] = region

  /** Destroy this region, removing it from the cache.
    */
  def destroy(): Unit = region.destroyRegion()

  /** Check if the region has been destroyed.
    *
    * @return
    *   true if destroyed, false otherwise
    */
  def isDestroyed: Boolean = region.isDestroyed

  /** Close this region. Handles the case where the region was already
    * destroyed.
    */
  override def close(): Unit =
    if (!region.isDestroyed) {
      try {
        region.close()
      } catch {
        case _: org.apache.geode.cache.RegionDestroyedException =>
        // Region was already destroyed, nothing to do
      }
    }
}

object GeodeRegion {

  /** Create a new GeodeRegion wrapper.
    *
    * @param region
    *   the underlying Geode region
    * @return
    *   a new GeodeRegion instance
    */
  def apply[K, V](region: Region[K, V]): GeodeRegion[K, V] =
    new GeodeRegion(region, region.getName)
}
