package app.maparound.map

/**
 * The world's country borders, from Natural Earth's 1:10m land boundary lines (public domain,
 * naturalearthdata.com), packed into `borders.bin` by tools/borders/make_borders.py.
 *
 * The offline maps have borders only where a country map is installed, and the world overview map
 * has none: these lines fill the overview zooms and the rest of the world. Accurate to a few
 * hundred metres, so the country maps' own borders are used where those are installed.
 *
 * Lines are cut into pieces of at most [PIECE] points, so a query only returns what's near, and
 * each piece is thinned for the overview zooms the first time it's needed.
 */
class BorderLines private constructor(private val pieces: List<Piece>) {

    /** Part of a border line; coordinates in microdegrees as (lat, lon) pairs. */
    class Piece internal constructor(
        /** The line it's part of; pieces of a line are consecutive and share their end points. */
        val line: Int,
        /** A disputed border, line of control or an undefined frontier. */
        val contested: Boolean,
        coords: IntArray,
    ) : ThinnedLine(coords)

    val pieceCount: Int get() = pieces.size
    val pointCount: Int get() = pieces.sumOf { it.coords.size / 2 }

    /** Pieces whose box meets [box], in line order. */
    fun query(box: GeoBox, visit: (Piece) -> Unit) {
        for (p in pieces) if (p.box.intersects(box)) visit(p)
    }

    companion object {
        /** Most points in one piece. */
        const val PIECE = 64

        /** Reads `borders.bin`; null if it isn't a borders file. */
        fun decode(bytes: ByteArray): BorderLines? {
            if (bytes.size < 5 || bytes.decodeToString(0, 4) != "MABL" || bytes[4].toInt() != 1) return null
            val r = PackedReader(bytes, 5)
            val pieces = ArrayList<Piece>()
            for (line in 0 until r.int()) {
                val contested = r.byte() == 1
                // 1e-5 degrees in the file.
                for (c in cutLine(r.points(10), PIECE)) pieces += Piece(line, contested, c)
            }
            return BorderLines(pieces)
        }
    }
}
