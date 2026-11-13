package app.maparound.three

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewGroup
import android.widget.FrameLayout
import app.maparound.ui.Scene3DControl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What one frame showed, for the names drawn over it. */
class FrameState(
    /** The eye, ECEF metres. */
    val eye: Vec3,
    /** View × projection with the eye at the origin. */
    val viewProj: FloatArray,
    val exaggeration: Float,
    val width: Int,
    val height: Int,
    /** Height of the eye above the ellipsoid, and its distance to the target. */
    val altitude: Double,
    val targetDistance: Double,
    /** Tiles whose names to show. */
    val labelTiles: List<TileId>,
)

/**
 * The 3D map on the whole globe (OpenGL ES 3): terrain tiles with the map draped on them, loaded
 * around the camera as you move, detailed nearby and coarse in the distance, buildings close up,
 * a sky with haze, and names standing up over it all ([LabelOverlay]). Zoom out far enough and it's
 * the planet in space. One finger pans (and flings), pinch zooms, two fingers twist to turn and
 * slide up/down to tilt; double-tap zooms in.
 */
@SuppressLint("ViewConstructor")
class Scene3DView(context: Context, start: Camera3D, private val engine: TerrainEngine) : FrameLayout(context), Scene3DControl {
    private val gl = GLSurfaceView(context)
    private val labels = LabelOverlay(context, engine)
    private val renderer = Renderer3D(engine, start)

    init {
        gl.setEGLContextClientVersion(3)
        gl.setEGLConfigChooser(MultisampleConfigChooser())
        gl.preserveEGLContextOnPause = true
        renderer.requestMore = { gl.requestRender() }
        renderer.onFrame = labels::publish
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        addView(gl, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(labels, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override val camera: Camera3D get() = renderer.camera()
    override val status: String get() = renderer.status

    fun requestRender() = gl.requestRender()
    fun onPause() = gl.onPause()
    fun onResume() = gl.onResume()

    fun setExaggeration(e: Float) {
        if (renderer.exaggeration == e) return
        renderer.exaggeration = e
        gl.requestRender()
    }

    fun setBuildingsVisible(on: Boolean) {
        if (renderer.buildingsOn == on) return
        renderer.buildingsOn = on
        gl.requestRender()
    }

    override fun faceNorth() {
        renderer.yaw = 0f
        renderer.pitch = 45f
        gl.requestRender()
    }

    fun release() {
        labels.release()
        engine.close()
    }

    // ---- gestures --------------------------------------------------------------------------------------

    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastAngle = 0f
    private var lastMidY = 0f
    private var lastY1 = 0f
    private var lastY2 = 0f
    private var velocity: VelocityTracker? = null
    private var twoFingers = false

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            renderer.zoomTo(renderer.distance / 2.5f)
            gl.requestRender()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                renderer.stopMotion()
                lastX = e.x
                lastY = e.y
                twoFingers = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                twoFingers = true
                // Restart the reference so nothing jumps when a finger is added or lifted.
                val up = e.actionMasked == MotionEvent.ACTION_POINTER_UP
                val idx = (0 until e.pointerCount).filter { !up || it != e.actionIndex }
                if (idx.size >= 2) twoFingerStart(e, idx[0], idx[1])
                if (idx.size == 1) {
                    lastX = e.getX(idx[0])
                    lastY = e.getY(idx[0])
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (e.pointerCount == 1) {
                    renderer.pan(e.x - lastX, e.y - lastY, height.toFloat())
                    lastX = e.x
                    lastY = e.y
                } else {
                    val x1 = e.getX(0); val y1 = e.getY(0); val x2 = e.getX(1); val y2 = e.getY(1)
                    val span = hypot(x2 - x1, y2 - y1)
                    val angle = Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()
                    val midY = (y1 + y2) / 2
                    if (lastSpan > 0 && span > 0) renderer.distance = (renderer.distance * lastSpan / span).coerceIn(Renderer3D.MIN_DISTANCE, Renderer3D.MAX_DISTANCE)
                    var dAngle = angle - lastAngle
                    if (dAngle > 180) dAngle -= 360
                    if (dAngle < -180) dAngle += 360
                    renderer.yaw = (renderer.yaw + dAngle + 360f) % 360f
                    // Both fingers sliding up or down together: tilt.
                    val dy1 = y1 - lastY1
                    val dy2 = y2 - lastY2
                    if (dy1 * dy2 > 0 && abs(span - lastSpan) < 6f) {
                        renderer.pitch = (renderer.pitch + (midY - lastMidY) * 0.2f).coerceIn(12f, 89f)
                    }
                    lastSpan = span
                    lastAngle = angle
                    lastMidY = midY
                    lastY1 = y1
                    lastY2 = y2
                }
                gl.requestRender()
            }
            MotionEvent.ACTION_UP -> {
                velocity?.let { v ->
                    v.addMovement(e)
                    v.computeCurrentVelocity(1000)
                    // A one-finger flick keeps the map gliding.
                    if (!twoFingers && hypot(v.xVelocity, v.yVelocity) > FLING_MIN_PX_S * resources.displayMetrics.density) {
                        renderer.fling(v.xVelocity, v.yVelocity, height.toFloat())
                        gl.requestRender()
                    }
                    v.recycle()
                }
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    private fun twoFingerStart(e: MotionEvent, a: Int, b: Int) {
        val x1 = e.getX(a); val y1 = e.getY(a); val x2 = e.getX(b); val y2 = e.getY(b)
        lastSpan = hypot(x2 - x1, y2 - y1)
        lastAngle = Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()
        lastMidY = (y1 + y2) / 2
        lastY1 = y1
        lastY2 = y2
    }

    /** 4× multisampling when the GPU offers it (smoother edges), else a plain config. */
    private class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            fun pick(samples: Int): EGLConfig? {
                val attrs = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                    EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0, EGL10.EGL_SAMPLES, samples,
                    EGL10.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val n = IntArray(1)
                return if (egl.eglChooseConfig(display, attrs, configs, 1, n) && n[0] > 0) configs[0] else null
            }
            return pick(4) ?: pick(0) ?: throw IllegalStateException("No OpenGL ES 3 configuration")
        }
    }

    private companion object {
        /** Slower lifts of the finger don't fling (dp per second). */
        const val FLING_MIN_PX_S = 400f
    }
}

private class Renderer3D(private val engine: TerrainEngine, start: Camera3D) : GLSurfaceView.Renderer {
    // Camera: a point on the ground, how far away, which way, how steep.
    @Volatile var lat = start.lat
    @Volatile var lon = start.lon
    @Volatile var distance = start.distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    @Volatile var yaw = start.yaw
    @Volatile var pitch = start.pitch
    @Volatile var exaggeration = 1.5f
    @Volatile var buildingsOn = true
    @Volatile var status = ""

    /** Called with what each frame showed (on the GL thread). */
    var onFrame: ((FrameState) -> Unit)? = null

    /** Asks the view for another frame (set by the view). */
    var requestMore: (() -> Unit)? = null

    // Motion after the finger left: a fling (screen pixels per second) and an eased zoom.
    @Volatile private var flingVx = 0f
    @Volatile private var flingVy = 0f
    @Volatile private var flingViewPx = 1f
    @Volatile private var zoomFrom = 0f
    @Volatile private var zoomTarget = 0f
    @Volatile private var zoomStart = 0L
    private var lastFrameNanos = 0L

    private class GpuTile(val vbo: Int, val tex: Int, val n: Int, val center: Vec3, var used: Long, val fadeFrom: Long)
    private class GpuBuildings(val vbo: Int, val vertexCount: Int, val center: Vec3, val basis: FloatArray, var used: Long)

    private val tiles = HashMap<TileId, GpuTile>()
    private val buildings = HashMap<TileId, GpuBuildings>()
    private val indexBuffers = HashMap<Int, Pair<Int, Int>>() // grid size -> (buffer, index count)
    private var terrainProgram = 0
    private var buildingProgram = 0
    private var skyProgram = 0
    private var skyVao = 0
    private var blankTexture = 0
    private var anisotropy = 0f
    private var aspect = 1f
    private var viewWidth = 1
    private var viewHeight = 1
    private var frameNo = 0L
    private val invViewProj = FloatArray(16)

    fun camera() = Camera3D(lat, lon, distance, yaw, pitch)

    private fun globeCamera(e: Float): GlobeCamera =
        GlobeCamera(lat, lon, engine.groundAt(lat, lon).toDouble() * e, distance.toDouble(), yaw.toDouble(), pitch.toDouble())

    /** Moves the target so the ground follows the finger. */
    fun pan(dx: Float, dy: Float, viewPx: Float) {
        val (la, lo) = globeCamera(exaggeration).panned(dx.toDouble(), dy.toDouble(), FOV.toDouble(), viewPx.toDouble())
        lat = la
        lon = lo
    }

    fun fling(vx: Float, vy: Float, viewPx: Float) {
        flingViewPx = viewPx
        flingVx = vx
        flingVy = vy
    }

    fun zoomTo(d: Float) {
        zoomFrom = distance
        zoomTarget = d.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        zoomStart = System.nanoTime()
    }

    fun stopMotion() {
        flingVx = 0f
        flingVy = 0f
        zoomStart = 0L
    }

    /** Moves the fling and the eased zoom on by [dt] seconds; true while either goes on. */
    private fun animate(now: Long, dt: Float): Boolean {
        var moving = false
        if (flingVx != 0f || flingVy != 0f) {
            pan(flingVx * dt, flingVy * dt, flingViewPx)
            val decay = exp(-FLING_DECAY * dt)
            flingVx *= decay
            flingVy *= decay
            if (hypot(flingVx, flingVy) < 20f) stopMotion() else moving = true
        }
        if (zoomStart != 0L) {
            val t = ((now - zoomStart) / 1e9f / ZOOM_SECONDS).coerceIn(0f, 1f)
            val ease = 1 - (1 - t) * (1 - t) * (1 - t)
            // Distance changes by ratio: interpolate its logarithm.
            distance = exp(ln(zoomFrom) + (ln(zoomTarget) - ln(zoomFrom)) * ease)
            if (t >= 1f) zoomStart = 0L else moving = true
        }
        return moving
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new GL context: everything uploaded before is gone.
        tiles.keys.forEach(engine::forgetTile)
        buildings.keys.forEach(engine::forgetBuildings)
        tiles.clear()
        buildings.clear()
        indexBuffers.clear()
        terrainProgram = program(TERRAIN_VS, TERRAIN_FS)
        buildingProgram = program(BUILDING_VS, BUILDING_FS)
        skyProgram = program(SKY_VS, SKY_FS)
        val vao = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        skyVao = vao[0]
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        blankTexture = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blankTexture)
        val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).put(byteArrayOf(0xE6.toByte(), 0xE1.toByte(), 0xD6.toByte(), -1)).position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
        if (GLES30.glGetString(GLES30.GL_EXTENSIONS)?.contains("GL_EXT_texture_filter_anisotropic") == true) {
            val max = FloatArray(1)
            GLES30.glGetFloatv(0x84FF, max, 0) // GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT
            anisotropy = min(8f, max[0])
        }
        GLES30.glClearColor(HORIZON[0], HORIZON[1], HORIZON[2], 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
        viewWidth = width
        viewHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        frameNo++
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1e9f).coerceAtMost(0.1f)
        lastFrameNanos = now
        var again = animate(now, dt)
        upload(now)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val e = exaggeration
        val cam = globeCamera(e)
        val eye = cam.eye
        val eyeGeo = Wgs84.geodetic(eye)
        val alt = eyeGeo[2]
        val d = distance.toDouble()
        // Clip planes: near close to the ground ahead, far beyond the horizon.
        val aboveGround = max(alt - engine.groundAt(eyeGeo[0], eyeGeo[1]) * e, 1.0)
        val near = min(aboveGround * 0.3, d * 0.1).coerceIn(0.5, 500_000.0)
        val horizon = sqrt(max(alt, 0.0) * (2 * Wgs84.R + max(alt, 0.0))) + sqrt(2 * Wgs84.R * MAX_TERRAIN_M * e)
        val far = max((horizon + d) * 1.2, near * 100)
        val proj = Mat4.perspective(FOV.toDouble(), aspect.toDouble(), near, far)
        val viewProj = Mat4.multiply(proj, cam.viewMatrix())
        Matrix.invertM(invViewProj, 0, viewProj, 0)

        // Which tiles, and what to load next (coarse first so there are no holes, then nearest).
        val sel = GlobeTileSelector.select(eye, viewProj, engine.textures.maxZoom, maxHeight = (MAX_TERRAIN_M * e).toDouble())
        val wanted = HashMap<TileId, Double>()
        for (t in sel.nodes) if (t !in tiles) wanted[t] = t.z * 1e9 + distTo(t, eye)
        val wantBuildings = HashMap<TileId, Double>()
        if (buildingsOn && engine.hasBuildings && d < 4_000) {
            for (l in sel.leaves) {
                if (l.z < 16) continue
                val b = l.ancestorAt(16)
                val dd = distTo(b, cam.target)
                if (dd < 2_500.0) wantBuildings[b] = dd
            }
        }
        val labelTiles = LinkedHashMap<TileId, Double>()
        for (l in sel.leaves) {
            val t = if (l.z >= Label3D.MAP_LABELS_FROM_ZOOM) l.ancestorAt(min(l.z, LABEL_ZOOM)) else l
            labelTiles[t] = min(labelTiles[t] ?: Double.MAX_VALUE, distTo(t, eye))
        }
        engine.want(wanted, wantBuildings.filterKeys { it !in buildings }, labelTiles)

        // The sky (and the Earth beyond the loaded tiles), then the ground over it.
        val space = GlobeCamera.smoothstep(20_000.0, 400_000.0, alt).toFloat()
        drawSky(eye, cam.horizonSine().toFloat(), space)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(terrainProgram)
        val light = lightDirection(cam)
        uniforms(terrainProgram, viewProj, e, light, alt)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(terrainProgram, "uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val drawable = HashMap<TileId, Boolean>()
        fun canDraw(t: TileId): Boolean = drawable.getOrPut(t) {
            if (t in sel.leaves) t in tiles
            else t.children.filter { it in sel.nodes }.let { kids -> kids.isNotEmpty() && kids.all(::canDraw) } || t in tiles
        }
        var fading = false
        fun draw(t: TileId) {
            if (t in sel.leaves) {
                tiles[t]?.let { fading = drawTile(it, eye, now) || fading }
                return
            }
            val kids = t.children.filter { it in sel.nodes }
            when {
                kids.isNotEmpty() && kids.all(::canDraw) -> kids.forEach(::draw)
                t in tiles -> fading = drawTile(tiles[t]!!, eye, now) || fading
                else -> kids.forEach(::draw)
            }
        }
        sel.roots.filter { it in sel.nodes }.forEach(::draw)
        (0..4).forEach { GLES30.glDisableVertexAttribArray(it) }

        if (buildingsOn) {
            GLES30.glUseProgram(buildingProgram)
            uniforms(buildingProgram, viewProj, e, light, alt)
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(-1f, -2f)
            for (key in wantBuildings.keys) {
                val b = buildings[key] ?: continue
                b.used = frameNo
                if (b.vertexCount == 0) continue
                val off = b.center - eye
                GLES30.glUniform3f(GLES30.glGetUniformLocation(buildingProgram, "uOffset"), off.x.toFloat(), off.y.toFloat(), off.z.toFloat())
                GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(buildingProgram, "uBasis"), 1, false, b.basis, 0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.vbo)
                val stride = Buildings.STRIDE * 4
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, stride, 0)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 16)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, b.vertexCount)
            }
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            (0..1).forEach { GLES30.glDisableVertexAttribArray(it) }
        }

        evict(sel)
        val loading = engine.pending
        status = if (loading > 0) "Loading $loading tiles…" else ""
        onFrame?.invoke(FrameState(eye, viewProj, e, viewWidth, viewHeight, alt, d, labelTiles.keys.toList()))
        // More to show: motion, tiles fading in, or more finished tiles than one frame uploads.
        again = again || fading || !engine.readyTiles.isEmpty() || !engine.readyBuildings.isEmpty()
        if (again) requestMore?.invoke()
    }

    /** Light from the north-west, 45° up, as on shaded relief maps (in ECEF, at the target). */
    private fun lightDirection(cam: GlobeCamera): FloatArray {
        val up = Wgs84.up(cam.lat, cam.lon)
        val east = Wgs84.east(cam.lon)
        val north = Wgs84.north(cam.lat, cam.lon)
        val l = (east * -0.5 + north * 0.5 + up * 0.707).normalized()
        return floatArrayOf(l.x.toFloat(), l.y.toFloat(), l.z.toFloat())
    }

    private fun drawSky(eye: Vec3, horizon: Float, space: Float) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(skyProgram)
        val up = eye.normalized()
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(skyProgram, "uInvViewProj"), 1, false, invViewProj, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(skyProgram, "uUp"), up.x.toFloat(), up.y.toFloat(), up.z.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(skyProgram, "uHorizon"), horizon)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(skyProgram, "uSpace"), space)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(skyProgram, "uHorizonColor"), HORIZON[0], HORIZON[1], HORIZON[2])
        GLES30.glUniform3f(GLES30.glGetUniformLocation(skyProgram, "uZenithColor"), ZENITH[0], ZENITH[1], ZENITH[2])
        GLES30.glUniform3f(GLES30.glGetUniformLocation(skyProgram, "uSpaceColor"), SPACE[0], SPACE[1], SPACE[2])
        GLES30.glUniform3f(GLES30.glGetUniformLocation(skyProgram, "uEarthColor"), EARTH[0], EARTH[1], EARTH[2])
        GLES30.glBindVertexArray(skyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
    }

    /** Draws a tile; true while it's still fading in. */
    private fun drawTile(t: GpuTile, eye: Vec3, now: Long): Boolean {
        t.used = frameNo
        val (ibo, count) = indexBuffer(t.n)
        val off = t.center - eye
        val fade = if (t.fadeFrom == 0L) 1f else ((now - t.fadeFrom) / 1e9f / FADE_SECONDS).coerceIn(0f, 1f)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(terrainProgram, "uOffset"), off.x.toFloat(), off.y.toFloat(), off.z.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(terrainProgram, "uFade"), fade)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (t.tex != 0) t.tex else blankTexture)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, t.vbo)
        val stride = GlobeMesh.STRIDE * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, stride, 28)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 2, GLES30.GL_FLOAT, false, stride, 36)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_SHORT, 0)
        return fade < 1f
    }

    private fun indexBuffer(n: Int): Pair<Int, Int> = indexBuffers.getOrPut(n) {
        val idx = GlobeMesh.indices(n)
        val buf = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(idx).position(0)
        val id = IntArray(1)
        GLES30.glGenBuffers(1, id, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, id[0])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, buf, GLES30.GL_STATIC_DRAW)
        id[0] to idx.size
    }

    /** Moves a few finished tiles to the GPU each frame (textures are the slow part). */
    private fun upload(now: Long) {
        repeat(6) {
            val t = engine.readyTiles.poll() ?: return@repeat
            tiles.remove(t.id)?.let(::free)
            val vbo = buffer(t.mesh.vertices)
            var tex = 0
            t.bitmap?.let { bmp ->
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                tex = ids[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                if (anisotropy > 1f) GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, 0x84FE, anisotropy) // GL_TEXTURE_MAX_ANISOTROPY_EXT
                bmp.recycle()
            }
            // Fade in only where nothing was shown before (a finer tile simply replaces its parent).
            val coveredBefore = generateSequence(t.id.parent) { it.parent }.any { it in tiles }
            tiles[t.id] = GpuTile(vbo, tex, t.mesh.n, t.mesh.center, frameNo, if (coveredBefore) 0L else now)
        }
        repeat(4) {
            val b = engine.readyBuildings.poll() ?: return@repeat
            buildings.remove(b.id)?.let { GLES30.glDeleteBuffers(1, intArrayOf(it.vbo), 0) }
            val vbo = if (b.vertices.isEmpty()) 0 else buffer(b.vertices)
            val e = Wgs84.east(b.lon)
            val n = Wgs84.north(b.lat, b.lon)
            val u = Wgs84.up(b.lat, b.lon)
            // Columns: east, north, up.
            val basis = floatArrayOf(
                e.x.toFloat(), e.y.toFloat(), e.z.toFloat(),
                n.x.toFloat(), n.y.toFloat(), n.z.toFloat(),
                u.x.toFloat(), u.y.toFloat(), u.z.toFloat(),
            )
            buildings[b.id] = GpuBuildings(vbo, b.vertices.size / Buildings.STRIDE, b.center, basis, frameNo)
        }
    }

    private fun free(t: GpuTile) {
        GLES30.glDeleteBuffers(1, intArrayOf(t.vbo), 0)
        if (t.tex != 0) GLES30.glDeleteTextures(1, intArrayOf(t.tex), 0)
    }

    /** Keeps GPU memory bounded: drops the least recently drawn tiles that aren't needed now. */
    private fun evict(sel: TileSelection) {
        if (tiles.size > MAX_TILES) {
            val drop = tiles.entries.filter { it.key !in sel.nodes }.sortedBy { it.value.used }.take(tiles.size - MAX_TILES)
            for ((id, t) in drop) {
                free(t)
                tiles.remove(id)
                engine.forgetTile(id)
            }
        }
        if (buildings.size > MAX_BUILDING_TILES) {
            val drop = buildings.entries.sortedBy { it.value.used }.take(buildings.size - MAX_BUILDING_TILES)
            for ((id, b) in drop) {
                if (b.vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(b.vbo), 0)
                buildings.remove(id)
                engine.forgetBuildings(id)
            }
        }
    }

    private fun distTo(t: TileId, p: Vec3): Double {
        val c = Wgs84.ecef((t.north + t.south) / 2, (t.west + t.east) / 2, 0.0)
        return (c - p).length
    }

    private fun uniforms(prog: Int, viewProj: FloatArray, e: Float, light: FloatArray, alt: Double) {
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uViewProj"), 1, false, viewProj, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uExag"), e)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, "uLight"), light[0], light[1], light[2])
        // Haze: thick near the ground, thin from high up; the atmosphere's rim seen from space.
        val a = max(alt, 0.0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uHazeDist"), (60_000.0 + a * 3).toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uHazeMax"), (0.8 - 0.65 * GlobeCamera.smoothstep(50_000.0, 3_000_000.0, a)).toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uRim"), (0.75 * GlobeCamera.smoothstep(150_000.0, 2_500_000.0, a)).toFloat())
        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, "uHaze"), HORIZON[0], HORIZON[1], HORIZON[2])
        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, "uRimColor"), RIM[0], RIM[1], RIM[2])
    }

    private fun buffer(data: FloatArray): Int {
        val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).position(0)
        val id = IntArray(1)
        GLES30.glGenBuffers(1, id, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, fb, GLES30.GL_STATIC_DRAW)
        return id[0]
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "Shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Program: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        private const val TAG = "Scene3D"
        const val FOV = 45f
        const val MIN_DISTANCE = 150f

        /** Far enough to see the whole planet. */
        const val MAX_DISTANCE = 22_000_000f
        private const val MAX_TILES = 220
        private const val MAX_BUILDING_TILES = 40
        private const val MAX_TERRAIN_M = 9_000f
        private const val LABEL_ZOOM = 14
        private const val FADE_SECONDS = 0.35f
        private const val ZOOM_SECONDS = 0.35f
        private const val FLING_DECAY = 3.2f

        private val HORIZON = floatArrayOf(0.80f, 0.87f, 0.94f)
        private val ZENITH = floatArrayOf(0.30f, 0.52f, 0.85f)
        private val SPACE = floatArrayOf(0.015f, 0.02f, 0.05f)
        private val EARTH = floatArrayOf(0.20f, 0.36f, 0.56f)
        private val RIM = floatArrayOf(0.55f, 0.75f, 1.0f)

        private const val TERRAIN_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aUp;
layout(location = 2) in float aH;
layout(location = 3) in vec2 aSlope;
layout(location = 4) in vec2 aUv;
uniform mat4 uViewProj;
uniform vec3 uOffset;
uniform float uExag;
out vec2 vUv;
out vec3 vNormal;
out vec3 vUp;
out vec3 vPos;
void main() {
    vec3 p = uOffset + aPos + aUp * (aH * uExag);
    vec3 east = normalize(vec3(-aUp.y, aUp.x, 0.0) + vec3(1e-7, 0.0, 0.0));
    vec3 north = cross(aUp, east);
    vNormal = aUp + uExag * (aSlope.x * east + aSlope.y * north);
    vUp = aUp;
    vUv = aUv;
    vPos = p;
    gl_Position = uViewProj * vec4(p, 1.0);
}"""

        private const val TERRAIN_FS = """#version 300 es
precision highp float;
in vec2 vUv;
in vec3 vNormal;
in vec3 vUp;
in vec3 vPos;
uniform sampler2D uTex;
uniform vec3 uLight;
uniform float uHazeDist;
uniform float uHazeMax;
uniform float uRim;
uniform vec3 uHaze;
uniform vec3 uRimColor;
uniform float uFade;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    float light = 0.72 + 0.38 * dot(n, uLight);
    vec3 c = texture(uTex, vUv).rgb * clamp(light, 0.45, 1.1);
    float d = length(vPos);
    c = mix(c, uHaze, uHazeMax * (1.0 - exp(-d / uHazeDist)));
    // The atmosphere, seen edge-on at the globe's rim.
    float facing = clamp(dot(normalize(-vPos), normalize(vUp)), 0.0, 1.0);
    c = mix(c, uRimColor, uRim * pow(1.0 - facing, 3.0));
    // Fading in: from the haze, where nothing was shown before.
    fragColor = vec4(mix(uHaze, c, uFade), 1.0);
}"""

        private const val BUILDING_VS = """#version 300 es
layout(location = 0) in vec4 aPos;
layout(location = 1) in vec3 aNormal;
uniform mat4 uViewProj;
uniform vec3 uOffset;
uniform mat3 uBasis;
uniform float uExag;
out vec3 vNormal;
out vec3 vPos;
out float vRoof;
void main() {
    vec3 p = uOffset + uBasis * vec3(aPos.xy, aPos.z * uExag + aPos.w);
    vNormal = uBasis * aNormal;
    vRoof = aNormal.z;
    vPos = p;
    gl_Position = uViewProj * vec4(p, 1.0);
}"""

        private const val BUILDING_FS = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vPos;
in float vRoof;
uniform vec3 uLight;
uniform float uHazeDist;
uniform float uHazeMax;
uniform vec3 uHaze;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    vec3 base = vRoof > 0.5 ? vec3(0.80, 0.70, 0.64) : vec3(0.93, 0.90, 0.85);
    float light = 0.55 + 0.45 * max(dot(n, uLight), 0.0) + 0.1 * vRoof;
    vec3 c = base * light;
    float d = length(vPos);
    c = mix(c, uHaze, uHazeMax * (1.0 - exp(-d / uHazeDist)));
    fragColor = vec4(c, 1.0);
}"""

        private const val SKY_VS = """#version 300 es
out vec2 vNdc;
void main() {
    vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
    vNdc = p;
    gl_Position = vec4(p, 0.0, 1.0);
}"""

        private const val SKY_FS = """#version 300 es
precision highp float;
in vec2 vNdc;
uniform mat4 uInvViewProj;
uniform vec3 uUp;
uniform float uHorizon;
uniform float uSpace;
uniform vec3 uHorizonColor;
uniform vec3 uZenithColor;
uniform vec3 uSpaceColor;
uniform vec3 uEarthColor;
out vec4 fragColor;
void main() {
    vec4 a = uInvViewProj * vec4(vNdc, -1.0, 1.0);
    vec4 b = uInvViewProj * vec4(vNdc, 1.0, 1.0);
    vec3 dir = normalize(b.xyz / b.w - a.xyz / a.w);
    float above = dot(dir, uUp) - uHorizon;
    vec3 col;
    if (above < 0.0) {
        // The Earth where no tile is drawn (yet, or near the poles), through the haze.
        col = mix(uEarthColor, uHorizonColor, exp(above * 14.0) * (1.0 - 0.5 * uSpace));
    } else {
        float t = clamp(above / mix(0.55, 0.12, uSpace), 0.0, 1.0);
        vec3 sky = mix(uHorizonColor, uZenithColor, pow(t, 0.55));
        // From space: dark, with a thin glowing band above the Earth's rim.
        float band = exp(-above / mix(0.5, 0.025, uSpace));
        col = mix(sky, mix(uSpaceColor, uHorizonColor * 0.95, band), uSpace);
    }
    fragColor = vec4(col, 1.0);
}"""
    }
}
