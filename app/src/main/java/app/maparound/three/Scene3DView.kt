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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Where the 3D camera looks and from how far; kept when the view is rebuilt (e.g. satellite on/off). */
data class Camera3D(val lat: Double, val lon: Double, val distance: Float, val yaw: Float = 0f, val pitch: Float = 45f)

/**
 * Streamed 3D map (OpenGL ES 3): terrain tiles with the map draped on them, loaded around the
 * camera as you move, coarse in the distance and detailed nearby, plus buildings close up.
 * One finger pans, pinch zooms, two fingers twist to turn and slide up/down to tilt; double-tap
 * zooms in.
 */
@SuppressLint("ViewConstructor")
class Scene3DView(context: Context, start: Camera3D, private val engine: TerrainEngine) : GLSurfaceView(context) {
    private val renderer = Renderer3D(engine, start)

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        renderer.requestMore = { requestRender() }
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    val camera: Camera3D get() = renderer.camera()
    val status: String get() = renderer.status

    fun setExaggeration(e: Float) {
        if (renderer.exaggeration == e) return
        renderer.exaggeration = e
        requestRender()
    }

    fun setBuildingsVisible(on: Boolean) {
        if (renderer.buildingsOn == on) return
        renderer.buildingsOn = on
        requestRender()
    }

    fun faceNorth() {
        renderer.yaw = 0f
        renderer.pitch = 45f
        requestRender()
    }

    fun release() {
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

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            renderer.distance = (renderer.distance / 2f).coerceAtLeast(Renderer3D.MIN_DISTANCE)
            requestRender()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
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
                requestRender()
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
    private class MultisampleConfigChooser : EGLConfigChooser {
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
}

private class Renderer3D(private val engine: TerrainEngine, start: Camera3D) : GLSurfaceView.Renderer {
    private val frame = engine.frame
    // Camera: a point on the ground (local metres), how far away, which way, how steep.
    @Volatile var targetX = frame.x(start.lon).toDouble()
    @Volatile var targetY = frame.y(start.lat).toDouble()
    @Volatile var distance = start.distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    @Volatile var yaw = start.yaw
    @Volatile var pitch = start.pitch
    @Volatile var exaggeration = 1.5f
    @Volatile var buildingsOn = true
    @Volatile var status = ""

    private class GpuTile(val vbo: Int, val tex: Int, val n: Int, var used: Long)
    private class GpuBuildings(val vbo: Int, val vertexCount: Int, var used: Long)

    private val tiles = HashMap<TileId, GpuTile>()
    private val buildings = HashMap<TileId, GpuBuildings>()
    private val indexBuffers = HashMap<Int, Pair<Int, Int>>() // grid size -> (buffer, index count)
    private var terrainProgram = 0
    private var buildingProgram = 0
    private var blankTexture = 0
    private var anisotropy = 0f
    private var aspect = 1f
    private var viewHeight = 1
    private var frameNo = 0L
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    fun camera() = Camera3D(frame.lat(targetY), frame.lon(targetX), distance, yaw, pitch)

    /** Moves the target so the ground follows the finger. */
    fun pan(dx: Float, dy: Float, viewPx: Float) {
        val mpp = 2 * distance * tan(Math.toRadians(FOV / 2.0)) / viewPx.coerceAtLeast(1f)
        val y = Math.toRadians(yaw.toDouble())
        val rightX = cos(y)
        val rightY = sin(y)
        val fwdX = -sin(y)
        val fwdY = cos(y)
        val tilt = sin(Math.toRadians(pitch.toDouble())).coerceAtLeast(0.35)
        targetX += -rightX * dx * mpp + fwdX * dy * mpp / tilt
        targetY += -rightY * dx * mpp + fwdY * dy * mpp / tilt
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
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(SKY[0], SKY[1], SKY[2], 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
        viewHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        frameNo++
        upload()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val e = exaggeration
        val tx = targetX
        val ty = targetY
        val groundZ = (engine.groundAt(frame.lat(ty), frame.lon(tx)) * e).toDouble()
        val p = Math.toRadians(pitch.toDouble())
        val yw = Math.toRadians(yaw.toDouble())
        val d = distance.toDouble()
        val eyeX = tx + d * cos(p) * sin(yw)
        val eyeY = ty - d * cos(p) * cos(yw)
        val eyeZ = groundZ + d * sin(p)
        val range = (d * 6).coerceIn(3_000.0, 70_000.0)
        Matrix.perspectiveM(proj, 0, FOV, aspect, max(1f, distance * 0.02f), (range * 1.6).toFloat())
        Matrix.setLookAtM(view, 0, eyeX.toFloat(), eyeY.toFloat(), eyeZ.toFloat(), tx.toFloat(), ty.toFloat(), groundZ.toFloat(), 0f, 0f, 1f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        // Which tiles, and what to load next (coarse first so there are no holes, then nearest).
        val sel = TileSelector.select(frame, eyeX, eyeY, eyeZ, tx, ty, groundZ, range, engine.textures.maxZoom)
        val wanted = HashMap<TileId, Double>()
        for (t in sel.nodes) if (t !in tiles) wanted[t] = t.z * 1e6 + dist2(t, eyeX, eyeY)
        val wantBuildings = HashMap<TileId, Double>()
        if (buildingsOn && engine.hasBuildings && d < 4_000) {
            for (l in sel.leaves) {
                if (l.z < 16) continue
                val b = l.ancestorAt(16)
                val dd = dist2(b, eyeX, eyeY)
                if (dd < 2_500.0 * 2_500.0) wantBuildings[b] = dd
            }
        }
        engine.want(wanted, wantBuildings.filterKeys { it !in buildings })

        // Terrain: each tile, or its parent while the tile's children are still loading.
        GLES30.glUseProgram(terrainProgram)
        uniforms(terrainProgram, e, eyeX, eyeY, eyeZ, range)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(terrainProgram, "uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val drawable = HashMap<TileId, Boolean>()
        fun canDraw(t: TileId): Boolean = drawable.getOrPut(t) {
            if (t in sel.leaves) t in tiles
            else t.children.filter { it in sel.nodes }.let { kids -> kids.isNotEmpty() && kids.all(::canDraw) } || t in tiles
        }
        fun draw(t: TileId) {
            if (t in sel.leaves) {
                tiles[t]?.let { drawTile(it) }
                return
            }
            val kids = t.children.filter { it in sel.nodes }
            when {
                kids.isNotEmpty() && kids.all(::canDraw) -> kids.forEach(::draw)
                t in tiles -> drawTile(tiles[t]!!)
                else -> kids.forEach(::draw)
            }
        }
        sel.roots.filter { it in sel.nodes }.forEach(::draw)
        (0..2).forEach { GLES30.glDisableVertexAttribArray(it) }

        if (buildingsOn) {
            GLES30.glUseProgram(buildingProgram)
            uniforms(buildingProgram, e, eyeX, eyeY, eyeZ, range)
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(-1f, -2f)
            for (key in wantBuildings.keys) {
                val b = buildings[key] ?: continue
                b.used = frameNo
                if (b.vertexCount == 0) continue
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
        // More finished tiles than one frame uploads: draw again soon.
        if (!engine.readyTiles.isEmpty() || !engine.readyBuildings.isEmpty()) requestMore?.invoke()
    }

    /** Asks the view for another frame (set by the view). */
    var requestMore: (() -> Unit)? = null

    private fun drawTile(t: GpuTile) {
        t.used = frameNo
        val (ibo, count) = indexBuffer(t.n)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (t.tex != 0) t.tex else blankTexture)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, t.vbo)
        val stride = TileMesh.STRIDE * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_SHORT, 0)
    }

    private fun indexBuffer(n: Int): Pair<Int, Int> = indexBuffers.getOrPut(n) {
        val idx = TileMesh.indices(n)
        val buf = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(idx).position(0)
        val id = IntArray(1)
        GLES30.glGenBuffers(1, id, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, id[0])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, buf, GLES30.GL_STATIC_DRAW)
        id[0] to idx.size
    }

    /** Moves a few finished tiles to the GPU each frame (textures are the slow part). */
    private fun upload(): Boolean {
        var any = false
        repeat(6) {
            val t = engine.readyTiles.poll() ?: return@repeat
            tiles.remove(t.id)?.let(::free)
            val vbo = buffer(t.vertices)
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
            tiles[t.id] = GpuTile(vbo, tex, t.n, frameNo)
            any = true
        }
        repeat(4) {
            val b = engine.readyBuildings.poll() ?: return@repeat
            buildings.remove(b.id)?.let { GLES30.glDeleteBuffers(1, intArrayOf(it.vbo), 0) }
            val vbo = if (b.vertices.isEmpty()) 0 else buffer(b.vertices)
            buildings[b.id] = GpuBuildings(vbo, b.vertices.size / Buildings.STRIDE, frameNo)
            any = true
        }
        return any
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

    private fun dist2(t: TileId, x: Double, y: Double): Double {
        val cx = (frame.x(t.west) + frame.x(t.east)) / 2.0
        val cy = (frame.y(t.north) + frame.y(t.south)) / 2.0
        return (cx - x) * (cx - x) + (cy - y) * (cy - y)
    }

    private fun uniforms(prog: Int, e: Float, ex: Double, ey: Double, ez: Double, range: Double) {
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uExag"), e)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, "uLight"), -0.5f, 0.5f, 0.707f)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, "uEye"), ex.toFloat(), ey.toFloat(), ez.toFloat())
        GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uFog"), (range * 0.45).toFloat(), range.toFloat())
        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, "uSky"), SKY[0], SKY[1], SKY[2])
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
        const val MAX_DISTANCE = 60_000f
        private const val MAX_TILES = 180
        private const val MAX_BUILDING_TILES = 40
        private val SKY = floatArrayOf(0.80f, 0.87f, 0.94f)

        private const val TERRAIN_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
uniform mat4 uMvp;
uniform float uExag;
uniform vec3 uEye;
out vec2 vUv;
out vec3 vNormal;
out float vDist;
void main() {
    vec3 p = vec3(aPos.xy, aPos.z * uExag);
    vUv = aUv;
    vNormal = vec3(aNormal.xy * uExag, aNormal.z);
    vDist = distance(p, uEye);
    gl_Position = uMvp * vec4(p, 1.0);
}"""

        private const val TERRAIN_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
in vec3 vNormal;
in float vDist;
uniform sampler2D uTex;
uniform vec3 uLight;
uniform vec2 uFog;
uniform vec3 uSky;
out vec4 fragColor;
void main() {
    float light = 0.72 + 0.38 * dot(normalize(vNormal), normalize(uLight));
    vec3 c = texture(uTex, vUv).rgb * clamp(light, 0.45, 1.1);
    float fog = clamp((vDist - uFog.x) / (uFog.y - uFog.x), 0.0, 0.85);
    fragColor = vec4(mix(c, uSky, fog), 1.0);
}"""

        private const val BUILDING_VS = """#version 300 es
layout(location = 0) in vec4 aPos;
layout(location = 1) in vec3 aNormal;
uniform mat4 uMvp;
uniform float uExag;
uniform vec3 uEye;
out vec3 vNormal;
out float vDist;
void main() {
    vec3 p = vec3(aPos.xy, aPos.z * uExag + aPos.w);
    vNormal = aNormal;
    vDist = distance(p, uEye);
    gl_Position = uMvp * vec4(p, 1.0);
}"""

        private const val BUILDING_FS = """#version 300 es
precision mediump float;
in vec3 vNormal;
in float vDist;
uniform vec3 uLight;
uniform vec2 uFog;
uniform vec3 uSky;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    vec3 base = n.z > 0.5 ? vec3(0.80, 0.70, 0.64) : vec3(0.93, 0.90, 0.85);
    float light = 0.55 + 0.45 * max(dot(n, normalize(uLight)), 0.0) + 0.1 * n.z;
    float fog = clamp((vDist - uFog.x) / (uFog.y - uFog.x), 0.0, 0.85);
    fragColor = vec4(mix(base * light, uSky, fog), 1.0);
}"""

    }
}
