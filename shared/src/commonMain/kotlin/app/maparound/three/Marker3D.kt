package app.maparound.three

/**
 * A marker standing on the 3D view's ground: my position, a party member, a meeting point. [image]
 * is the 2D map's marker image name ([app.maparound.map.maplibre.MarkerIcons]): it looks the same.
 */
data class Marker3D(val lat: Double, val lon: Double, val image: String)
