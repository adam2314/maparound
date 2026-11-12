package app.maparound.routing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A maneuver arrow as plain shapes in a 1×1 box (y down), so the phone (Compose) and the car
 * screen (an Android bitmap) draw the same picture. Draw [lines] as strokes [STROKE] wide with
 * round ends, fill [heads], and draw each ring as a stroke or filled disc.
 */
class TurnGlyph(
    val lines: List<List<Pt>>,
    val heads: List<List<Pt>>,
    val rings: List<Ring> = emptyList(),
) {
    data class Pt(val x: Float, val y: Float)
    data class Ring(val center: Pt, val radius: Float, val filled: Boolean)

    companion object {
        /** Stroke width as a share of the box size. */
        const val STROKE = 0.11f

        fun of(turn: Turn, exit: Int = 0): TurnGlyph = when (turn) {
            Turn.STRAIGHT -> arrow(0.0)
            Turn.SLIGHT_LEFT, Turn.EXIT_LEFT -> arrow(-45.0)
            Turn.LEFT -> arrow(-90.0)
            Turn.SHARP_LEFT -> arrow(-135.0)
            Turn.SLIGHT_RIGHT, Turn.EXIT_RIGHT -> arrow(45.0)
            Turn.RIGHT -> arrow(90.0)
            Turn.SHARP_RIGHT -> arrow(135.0)
            Turn.KEEP_LEFT -> arrow(-25.0)
            Turn.KEEP_RIGHT -> arrow(25.0)
            Turn.U_TURN_LEFT -> uTurn(left = true)
            Turn.U_TURN_RIGHT -> uTurn(left = false)
            Turn.ROUNDABOUT -> roundabout(exit, clockwise = false)
            Turn.ROUNDABOUT_CW -> roundabout(exit, clockwise = true)
            Turn.STOP -> TurnGlyph(
                emptyList(), emptyList(),
                listOf(Ring(Pt(0.5f, 0.5f), 0.26f, false), Ring(Pt(0.5f, 0.5f), 0.1f, true)),
            )
            Turn.ARRIVE -> TurnGlyph(
                listOf(listOf(Pt(0.5f, 0.55f), Pt(0.5f, 0.9f))), emptyList(),
                listOf(Ring(Pt(0.5f, 0.34f), 0.22f, true)),
            )
        }

        /** Up from the bottom to a junction, then off at [angle] degrees (positive = right). */
        private fun arrow(angle: Double): TurnGlyph {
            val a = angle * PI / 180
            val x0 = (0.5 - 0.15 * sin(a)).toFloat() // lean the stem away from the turn to keep it centred
            val j = Pt(x0, 0.52f)
            val len = if (angle == 0.0) 0.2 else 0.3
            val dx = sin(a)
            val dy = -cos(a)
            val end = Pt((j.x + len * dx).toFloat(), (j.y + len * dy).toFloat())
            return TurnGlyph(listOf(listOf(Pt(x0, 0.92f), j, end)), listOf(head(end, dx, dy)))
        }

        private fun uTurn(left: Boolean): TurnGlyph {
            val s = if (left) 1f else -1f
            val cx = 0.5f
            val cy = 0.4f
            val r = 0.17f
            val line = ArrayList<Pt>()
            line += Pt(cx + s * r, 0.92f)
            for (k in 0..12) {
                val t = PI * k / 12 // over the top, from the stem side to the other
                line += Pt((cx + s * r * cos(t)).toFloat(), (cy - r * sin(t)).toFloat())
            }
            val end = Pt(cx - s * r, 0.6f)
            line += end
            return TurnGlyph(listOf(line), listOf(head(end, 0.0, 1.0)))
        }

        private fun roundabout(exit: Int, clockwise: Boolean): TurnGlyph {
            val c = Pt(0.5f, 0.5f)
            val r = 0.15f
            // Exits counted in the driving direction: counter-clockwise starts on the right.
            val angles = doubleArrayOf(90.0, 0.0, -90.0, -150.0)
            val base = angles[(exit.coerceAtLeast(1) - 1).coerceAtMost(angles.lastIndex)]
            val a = (if (clockwise) -base else base) * PI / 180
            val dx = sin(a)
            val dy = -cos(a)
            val from = Pt((c.x + r * dx).toFloat(), (c.y + r * dy).toFloat())
            val end = Pt((c.x + (r + 0.15) * dx).toFloat(), (c.y + (r + 0.15) * dy).toFloat())
            return TurnGlyph(
                listOf(listOf(Pt(0.5f, 0.92f), Pt(0.5f, c.y + r)), listOf(from, end)),
                listOf(head(end, dx, dy)),
                listOf(Ring(c, r, false)),
            )
        }

        /** A triangle pointing along (dx, dy) whose base sits on [at]. */
        private fun head(at: Pt, dx: Double, dy: Double): List<Pt> {
            val px = -dy
            val py = dx
            return listOf(
                Pt((at.x + 0.14 * dx).toFloat(), (at.y + 0.14 * dy).toFloat()),
                Pt((at.x + 0.13 * px).toFloat(), (at.y + 0.13 * py).toFloat()),
                Pt((at.x - 0.13 * px).toFloat(), (at.y - 0.13 * py).toFloat()),
            )
        }
    }
}
