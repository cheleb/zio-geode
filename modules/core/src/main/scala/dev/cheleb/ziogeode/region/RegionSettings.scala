package dev.cheleb.ziogeode.region

/** Settings for a Geode region.
  *
  * @param name
  *   the name of the region
  * @param keyExtractor
  *   function to extract the key from a value
  */
final case class RegionSettings[K, V](
    name: String,
    keyExtractor: V => K
) {
  override def toString: String =
    "RegionSettings(" +
      s"name=$name" +
      ")"
}

/** Companion object for RegionSettings
  */
object RegionSettings {

  /** Create RegionSettings instance
    *
    * @param name
    *   the name of the region
    * @param keyExtractor
    *   function to extract the key from a value
    * @return
    */
  def apply[K, V](name: String, keyExtractor: V => K): RegionSettings[K, V] =
    new RegionSettings(name, keyExtractor)

}
