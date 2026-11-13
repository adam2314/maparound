package app.maparound.tools

import app.maparound.data.TerrainData
import app.maparound.routing.GeoPoint
import app.maparound.terrain.DemIndex
import app.maparound.terrain.GeoBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos

/**
 * The map tools (azimuth & range, line of sight, viewshed, coordinate converter). Lives outside
 * the UI so it survives tab switches; terrain work runs off the main thread.
 */
object ToolsSession {
    enum class Mode(val label: String) { MEASURE("Azimuth"), SIGHT("Sight"), VIEW("Viewshed"), COORDS("Coords") }
    enum class Slot { A, B }

    data class State(
        val active: Boolean = false,
        val mode: Mode = Mode.MEASURE,
        val a: GeoPoint? = null,
        val b: GeoPoint? = null,
        /** Which point the next tap on the map sets. */
        val next: Slot = Slot.A,
        val observerHeightM: Double = 1.7,
        val targetHeightM: Double = 1.7,
        val radiusM: Double = 3_000.0,
        /** Line of sight as a radio link between antennas: Fresnel zone and losses at [frequencyMHz]. */
        val radio: Boolean = false,
        val frequencyMHz: Double = 868.0,
        /** Ground heights at A and B from terrain data, when there is some. */
        val aGround: Double? = null,
        val bGround: Double? = null,
        val los: LineOfSight? = null,
        val viewshed: Viewshed? = null,
        val busy: Boolean = false,
        /** Line of sight / viewshed need terrain data at A (and B). */
        val needsTerrain: Boolean = false,
        /** The area's terrain is downloading for them (done, total 1° cells). */
        val terrainProgress: Pair<Int, Int>? = null,
        /** Why the area's terrain couldn't be fetched (offline, Wi‑Fi only…), with a retry. */
        val terrainProblem: String? = null,
        /** Ground near A that is higher than A (and may block the view), with its height. */
        val higherNearA: Pair<GeoPoint, Double>? = null,
    ) {
        /** The points of the current mode (COORDS and VIEW use A only). */
        val usesB: Boolean get() = mode == Mode.MEASURE || mode == Mode.SIGHT
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private var dem: DemIndex? = null
    private var demVersion = -1

    /**
     * Where the elevation tiles are, and a number that changes whenever they change. Set by the
     * platform at start-up (Android: the terrain repository).
     */
    var terrainSource: () -> Pair<String, Int>? = { null }

    /**
     * The platform's elevation data, to fetch what line of sight and viewshed need without asking.
     * Set at start-up, with [terrainSource].
     */
    var terrainData: TerrainData? = null
        set(value) {
            field = value
            watchJob?.cancel()
            watchJob = value?.let { t ->
                scope.launch {
                    var version = t.state.value.version
                    t.state.collect { st ->
                        val s = _state.value
                        if (s.active && s.terrainProgress != null) {
                            _state.value = s.copy(terrainProgress = st.progress?.let { it.done to it.total } ?: s.terrainProgress)
                        }
                        // New elevation data (or a download ended): try again with it.
                        if (st.version != version) {
                            version = st.version
                            if (_state.value.active) recompute()
                        }
                    }
                }
            }
        }
    private var watchJob: Job? = null

    /** 1° cells already fetched for the tools this session that didn't come: not again until [retryTerrain]. */
    private val triedCells = HashSet<Pair<Int, Int>>()

    /** Tries again to fetch the terrain that didn't come. */
    fun retryTerrain() {
        triedCells.clear()
        recompute()
    }

    fun open(mode: Mode? = null, a: GeoPoint? = null) {
        val s = _state.value
        _state.value = s.copy(
            active = true,
            mode = mode ?: s.mode,
            a = a ?: s.a,
            next = if ((a ?: s.a) != null) Slot.B else Slot.A,
        )
        recompute()
    }

    fun close() {
        job?.cancel()
        val s = _state.value
        // Keep the settings, drop the points and results.
        _state.value = State(
            mode = s.mode, observerHeightM = s.observerHeightM, targetHeightM = s.targetHeightM, radiusM = s.radiusM,
            radio = s.radio, frequencyMHz = s.frequencyMHz,
        )
    }

    fun setMode(mode: Mode) {
        val s = _state.value
        if (s.mode == mode) return
        // Sight checks are usually done for someone else standing at B; a viewshed for the ground.
        val target = when (mode) {
            Mode.SIGHT -> if (s.mode == Mode.VIEW && s.targetHeightM == 0.0) 1.7 else s.targetHeightM
            Mode.VIEW -> if (s.mode == Mode.SIGHT && s.targetHeightM == 1.7) 0.0 else s.targetHeightM
            else -> s.targetHeightM
        }
        val usesB = mode == Mode.MEASURE || mode == Mode.SIGHT
        _state.value = s.copy(
            mode = mode,
            targetHeightM = target,
            // With A already placed, the next tap places (or moves) B.
            next = if (usesB && s.a != null) Slot.B else Slot.A,
            los = null,
            viewshed = null,
        )
        recompute()
    }

    /** A tap on the map while the tools are open. */
    fun tap(p: GeoPoint) {
        val s = _state.value
        if (!s.usesB || s.next == Slot.A) setA(p) else setB(p)
    }

    fun select(slot: Slot) {
        _state.value = _state.value.copy(next = slot)
    }

    // Moving a point drops the old results at once, so nothing stale stays drawn while recomputing.

    fun setA(p: GeoPoint) {
        val s = _state.value
        _state.value = s.copy(
            a = p,
            next = if (s.usesB && (s.b == null || s.next == Slot.A)) Slot.B else s.next,
            los = null, viewshed = null, higherNearA = null, aGround = null,
        )
        recompute()
    }

    fun setB(p: GeoPoint) {
        _state.value = _state.value.copy(b = p, los = null, bGround = null)
        recompute()
    }

    fun swap() {
        val s = _state.value
        _state.value = s.copy(
            a = s.b, b = s.a, aGround = s.bGround, bGround = s.aGround,
            observerHeightM = s.targetHeightM, targetHeightM = s.observerHeightM,
            los = null, viewshed = null, higherNearA = null,
        )
        recompute()
    }

    fun setHeights(observerM: Double, targetM: Double) {
        val s = _state.value
        val o = observerM.coerceIn(0.0, 5_000.0)
        val t = targetM.coerceIn(0.0, 5_000.0)
        if (o == s.observerHeightM && t == s.targetHeightM) return
        _state.value = s.copy(observerHeightM = o, targetHeightM = t)
        recompute()
    }

    /** Line of sight as a radio link (antenna heights, Fresnel zone, losses) or by eye. */
    fun setRadio(on: Boolean) {
        val s = _state.value
        if (s.radio == on) return
        _state.value = s.copy(radio = on, los = null)
        recompute()
    }

    fun setFrequency(mhz: Double) {
        val f = mhz.coerceIn(MIN_MHZ, MAX_MHZ)
        if (f == _state.value.frequencyMHz) return
        _state.value = _state.value.copy(frequencyMHz = f)
        recompute()
    }

    fun setRadius(m: Double) {
        val r = m.coerceIn(MIN_RADIUS, MAX_RADIUS)
        if (r == _state.value.radiusM) return
        _state.value = _state.value.copy(radiusM = r)
        recompute()
    }

    private fun terrain(): DemIndex? {
        val (dir, version) = terrainSource() ?: return null
        if (version != demVersion || dem == null) {
            dem = DemIndex(dir).takeUnless { it.isEmpty }
            demVersion = version
        }
        return dem
    }

    /** The ground line of sight or the viewshed need, or null. */
    private fun neededArea(s: State): GeoBounds? = when {
        s.mode == Mode.SIGHT && s.a != null && s.b != null ->
            GeoBounds(minOf(s.a.lat, s.b.lat), minOf(s.a.lon, s.b.lon), maxOf(s.a.lat, s.b.lat), maxOf(s.a.lon, s.b.lon))
        s.mode == Mode.VIEW && s.a != null -> {
            val dLat = s.radiusM / 111_320.0
            val dLon = dLat / cos(s.a.lat * PI / 180).coerceAtLeast(0.05)
            GeoBounds(s.a.lat - dLat, s.a.lon - dLon, s.a.lat + dLat, s.a.lon + dLon)
        }
        else -> null
    }

    /**
     * Starts fetching the terrain [s] needs, if some is missing: true while it downloads (the
     * results wait for it). Cells that didn't come are left to [retryTerrain].
     */
    private fun fetchTerrain(s: State): Boolean {
        val t = terrainData ?: return false
        val area = neededArea(s) ?: return false
        val missing = t.missingCells(area)
        if (missing.isEmpty()) {
            _state.value = _state.value.copy(terrainProgress = null, terrainProblem = null)
            return false
        }
        val fresh = missing.filterNot { it in triedCells }
        if (fresh.isEmpty()) {
            // Tried and they didn't come (no connection?): compute with what there is.
            if (_state.value.terrainProblem == null) {
                _state.value = _state.value.copy(terrainProgress = null, terrainProblem = "Couldn't get the terrain data for this area (no connection?).")
            }
            return false
        }
        val problem = t.ensure("this area", area)
        if (problem != null) {
            _state.value = _state.value.copy(terrainProgress = null, terrainProblem = problem)
            return false
        }
        triedCells += fresh
        val p = t.state.value.progress
        _state.value = _state.value.copy(
            busy = true, needsTerrain = true, los = null, viewshed = null, terrainProblem = null,
            terrainProgress = (p?.done ?: 0) to (p?.total ?: missing.size),
        )
        return true
    }

    private fun recompute() {
        job?.cancel()
        val s = _state.value
        if (!s.active) return
        if (fetchTerrain(s)) return
        _state.value = _state.value.copy(busy = s.mode == Mode.SIGHT || s.mode == Mode.VIEW, terrainProgress = null)
        job = scope.launch {
            delay(150) // let slider drags and quick taps settle
            try {
                val result = withContext(Dispatchers.Default) { compute(s) }
                _state.value = _state.value.copy(
                    aGround = result.aGround, bGround = result.bGround,
                    los = result.los, viewshed = result.viewshed,
                    needsTerrain = result.needsTerrain, higherNearA = result.higherNearA, busy = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, los = null, viewshed = null)
            }
        }
    }

    private class Result(
        val aGround: Double?,
        val bGround: Double?,
        val los: LineOfSight?,
        val viewshed: Viewshed?,
        val needsTerrain: Boolean,
        val higherNearA: Pair<GeoPoint, Double>?,
    )

    private suspend fun compute(s: State): Result {
        val dem = terrain()
        val elevation = Elevation { lat, lon -> dem?.elevation(lat, lon) ?: Float.NaN }
        fun ground(p: GeoPoint?) = p?.let { elevation.at(it.lat, it.lon).toDouble().takeUnless(Double::isNaN) }
        val aGround = ground(s.a)
        val bGround = if (s.usesB) ground(s.b) else null
        val needs = (s.mode == Mode.SIGHT && s.a != null && s.b != null && (aGround == null || bGround == null)) ||
            (s.mode == Mode.VIEW && s.a != null && aGround == null)
        // Sample about as finely as the terrain data (30 m for 1″ tiles, 90 m for 3″).
        val stepM = ((dem?.finestStep ?: (1.0 / 1200)) * 111_000 * 0.8).coerceAtLeast(20.0)
        val los = if (s.mode == Mode.SIGHT && s.a != null && s.b != null && !needs) {
            LineOfSight.compute(elevation, s.a, s.b, s.observerHeightM, s.targetHeightM, stepM, s.frequencyMHz.takeIf { s.radio })
        } else {
            null
        }
        val ctx = coroutineContext
        val viewshed = if (s.mode == Mode.VIEW && s.a != null && !needs) {
            val size = (2 * s.radiusM / stepM).toInt().coerceIn(41, 601)
            Viewshed.compute(elevation, s.a, s.radiusM, s.observerHeightM, s.targetHeightM, size, stepM) { !ctx.isActive }
        } else {
            null
        }
        // Standing just below a rounded-off top hides a lot: offer to move up (3 m+ higher within 100 m).
        val higher = if ((s.mode == Mode.SIGHT || s.mode == Mode.VIEW) && s.a != null && aGround != null) {
            highestNear(elevation, s.a)?.takeIf { it.second > aGround + 3 }
        } else {
            null
        }
        return Result(aGround, bGround, los, viewshed, needs, higher)
    }

    const val MIN_RADIUS = 250.0
    const val MAX_RADIUS = 25_000.0
    const val MIN_MHZ = 1.0
    const val MAX_MHZ = 100_000.0

    /** Common bands for the frequency picker: label and MHz. */
    val BANDS = listOf(
        "VHF 2 m (amateur)" to 145.0,
        "Marine VHF" to 156.8,
        "LoRa 433 MHz (EU433)" to 433.0,
        "PMR446" to 446.0,
        "FRS / GMRS" to 462.6,
        "LoRa 868 MHz (EU868)" to 868.0,
        "LoRa 915 MHz (US / ANZ)" to 915.0,
        "2.4 GHz (LoRa 2.4, Wi-Fi)" to 2_440.0,
        "5.8 GHz (Wi-Fi, video)" to 5_800.0,
    )
}
