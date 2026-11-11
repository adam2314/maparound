package app.maparound.three

/** Where the 3D camera looks: the point below it, distance (m), heading (yaw) and tilt (pitch), degrees. */
data class Camera3D(val lat: Double, val lon: Double, val distance: Float, val yaw: Float = 0f, val pitch: Float = 45f)
