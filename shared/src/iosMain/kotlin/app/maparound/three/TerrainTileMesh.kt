package app.maparound.three

import app.maparound.terrain.DemIndex
import app.maparound.tools.Elevation
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes

/**
 * Phase 0 spike (c): the terrain of one map tile, meshed by the shared code (the same mesh the
 * Android 3D view draws), as raw buffers for Metal. Vertices: [TileMesh.STRIDE] floats each, in
 * metres around the tile's centre (x east, y north, z height); indices: 16-bit triangles.
 */
@OptIn(ExperimentalForeignApi::class)
class TerrainTileMesh(demDir: String, lat: Double, lon: Double, zoom: Int, gridSize: Int) {
    val tile = TileId(zoom, TileId.xOf(lon, zoom), TileId.yOf(lat, zoom))
    val north: Double get() = tile.north
    val south: Double get() = tile.south
    val west: Double get() = tile.west
    val east: Double get() = tile.east

    /** Tile width on the ground, metres. */
    val sizeM: Float = TileId.sizeM(zoom, (tile.north + tile.south) / 2).toFloat()
    val hasTerrain: Boolean
    val minHeight: Float
    val maxHeight: Float
    val vertexData: NSData
    val indexData: NSData
    val indexCount: Int

    init {
        val dem = DemIndex(demDir)
        val frame = LocalFrame((tile.north + tile.south) / 2, (tile.west + tile.east) / 2)
        val (vertices, any) = TileMesh.build(Elevation { la, lo -> dem.elevation(la, lo) }, tile, frame, gridSize)
        hasTerrain = any
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until gridSize * gridSize) {
            val z = vertices[i * TileMesh.STRIDE + 2]
            lo = minOf(lo, z)
            hi = maxOf(hi, z)
        }
        minHeight = lo
        maxHeight = hi
        val indices = TileMesh.indices(gridSize)
        indexCount = indices.size
        vertexData = vertices.usePinned { NSData.dataWithBytes(it.addressOf(0), (vertices.size * 4).toULong()) }
        indexData = indices.usePinned { NSData.dataWithBytes(it.addressOf(0), (indices.size * 2).toULong()) }
    }
}
