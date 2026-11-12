package app.maparound.routing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.maparound.USER_AGENT
import app.maparound.terrain.DemIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Plans routes: on the device with BRouter when its data covers the points, otherwise with the
 * public brouter.de server; "Line" mode measures straight lines with heights from terrain data.
 */
class RoutePlanner(
    private val context: Context,
    private val data: RoutingDataRepository,
    private val demDir: File,
) {
    suspend fun plan(points: List<GeoPoint>, profile: RouteProfile): RouteResult = withContext(Dispatchers.Default) {
        if (points.size < 2) throw RoutingException("Add at least two points")
        if (profile == RouteProfile.LINE) return@withContext straight(points)

        val missing = data.offlineRouter.missingSegments(points)
        if (missing.isEmpty()) {
            try {
                return@withContext data.offlineRouter.route(points, profile)
            } catch (e: RoutingException) {
                // The route may run off the downloaded area; the server knows the whole world.
                if (!isOnline()) throw e
            }
        } else if (!isOnline()) {
            throw RoutingException(
                "No internet, and no offline routing data here (${missing.joinToString()}). " +
                    "Download it in Offline maps, or use Line mode.",
            )
        }
        withContext(Dispatchers.IO) { online(points, profile) }
    }

    private fun straight(points: List<GeoPoint>): RouteResult {
        val dem = DemIndex(demDir.path).takeUnless { it.isEmpty }
        val line = if (dem == null) {
            points
        } else {
            Geo.densify(points, 25.0).map { p ->
                p.copy(ele = dem.elevation(p.lat, p.lon).takeUnless { it.isNaN() }?.toDouble())
            }
        }
        val stats = TrackStats.of(line)
        return RouteResult(line, stats, Geo.hikingTimeSeconds(stats.distanceM, stats.ascentM, stats.descentM), RouteResult.Source.STRAIGHT)
    }

    private fun online(points: List<GeoPoint>, profile: RouteProfile): RouteResult {
        val lonlats = points.joinToString("|") { String.format(Locale.ROOT, "%.6f,%.6f", it.lon, it.lat) }
        val url = "https://brouter.de/brouter?lonlats=${URLEncoder.encode(lonlats, "UTF-8")}" +
            "&profile=${profile.brouterProfile}&alternativeidx=0&format=geojson&timode=${OfflineRouter.TURN_HINTS_LOCUS}"
        val conn = URL(url).openConnection() as HttpURLConnection
        val body = try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw RoutingException(
                    when {
                        "not mapped" in err || "position not" in err -> "A point is not near a road or path."
                        "datafile" in err -> "There is no routing data for one of the points (open sea?)."
                        err.isNotBlank() -> err.lineSequence().first().take(160)
                        else -> "Routing server error (HTTP ${conn.responseCode})"
                    },
                )
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: RoutingException) {
            throw e
        } catch (e: Exception) {
            throw RoutingException("Couldn't reach the routing server: ${e.message}")
        } finally {
            conn.disconnect()
        }
        val feature = JSONObject(body).getJSONArray("features").getJSONObject(0)
        val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until coords.length()).map { i ->
            val c = coords.getJSONArray(i)
            GeoPoint(c.getDouble(1), c.getDouble(0), if (c.length() > 2) c.getDouble(2) else null)
        }
        val props = feature.getJSONObject("properties")
        val stats = TrackStats.of(pts).let { s ->
            s.copy(distanceM = props.optString("track-length").toDoubleOrNull() ?: s.distanceM)
        }
        val duration = if (profile == RouteProfile.HIKE) {
            Geo.hikingTimeSeconds(stats.distanceM, stats.ascentM, stats.descentM)
        } else {
            props.optString("total-time").toLongOrNull()
        }
        return RouteResult(pts, stats, duration, RouteResult.Source.ONLINE, Guidance.brouterHints(body))
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
