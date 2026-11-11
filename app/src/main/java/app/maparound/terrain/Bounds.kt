package app.maparound.terrain

import org.mapsforge.core.model.BoundingBox

/** The shared bounds as mapsforge's type, for map layers. */
fun GeoBounds.toBoundingBox() = BoundingBox(minLat, minLon, maxLat, maxLon)
