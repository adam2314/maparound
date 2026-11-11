package app.maparound.sensors

import app.maparound.shared.util.toDegrees
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sqrt

// Plain Kotlin: turning phone sensor vectors into vehicle (or body) pitch, roll and turn rate.
// Unit-tested.

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val norm: Float get() = sqrt(x * x + y * y + z * z)
    fun unit(): Vec3 = norm.let { if (it < 1e-6f) this else Vec3(x / it, y / it, z / it) }

    override fun toString() = "$x,$y,$z"

    companion object {
        fun of(v: FloatArray) = Vec3(v[0], v[1], v[2])
        fun parse(s: String?): Vec3? = s?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) }
    }
}

/** Pitch (nose up +) and roll (right side down +) in degrees, and the total tilt from level. */
data class Tilt(val pitchDeg: Float, val rollDeg: Float, val totalDeg: Float)

/**
 * The axes of whatever the phone is mounted in, from the "up" direction measured while it stands
 * level (the gravity sensor reads +9.81 m/s² pointing up). Forward is the phone's top when the
 * phone lies flat, or the phone's back when it stands upright in a holder facing you.
 */
class LevelFrame(up: Vec3) {
    val up: Vec3 = up.unit()
    val forward: Vec3
    val right: Vec3

    init {
        val top = Vec3(0f, 1f, 0f)
        val back = Vec3(0f, 0f, -1f)
        val candidate = if (abs(top dot this.up) <= abs(back dot this.up)) top else back
        forward = (candidate - this.up * (candidate dot this.up)).unit()
        right = forward cross this.up
    }

    /** Tilt of the body, given the current "up" (gravity) reading in phone coordinates. */
    fun tilt(gravity: Vec3): Tilt {
        val u = gravity.unit()
        val pitch = toDegrees(asin((u dot forward).coerceIn(-1f, 1f).toDouble())).toFloat()
        val roll = toDegrees(asin((-(u dot right)).coerceIn(-1f, 1f).toDouble())).toFloat()
        val total = toDegrees(acos((u dot up).coerceIn(-1f, 1f).toDouble())).toFloat()
        return Tilt(pitch, roll, total)
    }

    /** Acceleration (gravity removed) along the body: forward (+ speeding up) and right (+ turning right). */
    fun horizontal(linear: Vec3): Pair<Float, Float> = (linear dot forward) to (linear dot right)

    /** Rotation rate about the vertical (rad/s in, same units out): + turning left, − turning right. */
    fun yawRate(gyro: Vec3): Float = gyro dot up

    companion object {
        /** Without a saved level: assume the phone itself is level, lying flat or standing upright. */
        fun default(gravity: Vec3): LevelFrame {
            val ax = abs(gravity.x)
            val ay = abs(gravity.y)
            val az = abs(gravity.z)
            return LevelFrame(
                when {
                    az >= ay && az >= ax -> Vec3(0f, 0f, if (gravity.z >= 0) 1f else -1f)
                    ay >= ax -> Vec3(0f, if (gravity.y >= 0) 1f else -1f, 0f)
                    else -> Vec3(if (gravity.x >= 0) 1f else -1f, 0f, 0f)
                },
            )
        }
    }
}
