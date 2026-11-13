package app.maparound.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.Color
import app.maparound.i18n.T
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs

data class Satellite(
    val constellation: Int,
    val svid: Int,
    val cn0: Float,          // carrier-to-noise density, dB-Hz
    val elevation: Float,    // degrees above horizon
    val azimuth: Float,      // degrees from north
    val usedInFix: Boolean,
    val hasEphemeris: Boolean,
    val hasAlmanac: Boolean,
    val carrierHz: Float?,
) {
    val key: String get() = "$constellation-$svid-${carrierHz ?: 0f}"
}

data class GnssState(
    val satellites: List<Satellite> = emptyList(),
    val engineRunning: Boolean = false,
    val ttffMs: Int? = null,
    val location: Location? = null,
    val gpsEnabled: Boolean = true,
)

/** Live GNSS engine status: every satellite in view with signal strength and sky position. */
object GnssSource {
    @SuppressLint("MissingPermission")
    fun updates(context: Context): Flow<GnssState> = callbackFlow {
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null || !context.hasLocationPermission() || LocationManager.GPS_PROVIDER !in lm.allProviders) {
            trySend(GnssState(gpsEnabled = false))
            awaitClose { }
            return@callbackFlow
        }
        var state = GnssState(gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
        fun push(s: GnssState) {
            state = s
            trySend(s)
        }

        val statusCallback = object : GnssStatus.Callback() {
            override fun onStarted() = push(state.copy(engineRunning = true))
            override fun onStopped() = push(state.copy(engineRunning = false))
            override fun onFirstFix(ttffMillis: Int) = push(state.copy(ttffMs = ttffMillis))
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val sats = (0 until status.satelliteCount).map { i ->
                    Satellite(
                        constellation = status.getConstellationType(i),
                        svid = status.getSvid(i),
                        cn0 = status.getCn0DbHz(i),
                        elevation = status.getElevationDegrees(i),
                        azimuth = status.getAzimuthDegrees(i),
                        usedInFix = status.usedInFix(i),
                        hasEphemeris = status.hasEphemerisData(i),
                        hasAlmanac = status.hasAlmanacData(i),
                        carrierHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else null,
                    )
                }
                push(state.copy(satellites = sats, engineRunning = true))
            }
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = push(state.copy(location = location))
            override fun onProviderEnabled(provider: String) = push(state.copy(gpsEnabled = true))
            override fun onProviderDisabled(provider: String) =
                push(state.copy(gpsEnabled = false, satellites = emptyList(), engineRunning = false))

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        push(state)
        // The GNSS engine only runs (and reports satellites) while someone requests GPS fixes.
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= 30) {
            lm.registerGnssStatusCallback(context.mainExecutor, statusCallback)
        } else {
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(statusCallback, Handler(Looper.getMainLooper()))
        }
        awaitClose {
            lm.unregisterGnssStatusCallback(statusCallback)
            lm.removeUpdates(listener)
        }
    }
}

object Constellations {
    fun name(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
        else -> T.sensors.otherConstellation
    }

    fun flag(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "🇺🇸"
        GnssStatus.CONSTELLATION_GLONASS -> "🇷🇺"
        GnssStatus.CONSTELLATION_GALILEO -> "🇪🇺"
        GnssStatus.CONSTELLATION_BEIDOU -> "🇨🇳"
        GnssStatus.CONSTELLATION_QZSS -> "🇯🇵"
        GnssStatus.CONSTELLATION_IRNSS -> "🇮🇳"
        else -> "🛰"
    }

    fun color(type: Int): Color = when (type) {
        GnssStatus.CONSTELLATION_GPS -> Color(0xFF1E88E5)
        GnssStatus.CONSTELLATION_GLONASS -> Color(0xFFE53935)
        GnssStatus.CONSTELLATION_GALILEO -> Color(0xFF8E24AA)
        GnssStatus.CONSTELLATION_BEIDOU -> Color(0xFFFB8C00)
        GnssStatus.CONSTELLATION_QZSS -> Color(0xFFD81B60)
        GnssStatus.CONSTELLATION_SBAS -> Color(0xFF757575)
        GnssStatus.CONSTELLATION_IRNSS -> Color(0xFF43A047)
        else -> Color(0xFF546E7A)
    }

    private val bands = listOf(
        1575.42 to "L1", 1176.45 to "L5", 1227.60 to "L2", 1561.098 to "B1",
        1207.14 to "E5b", 1268.52 to "B3", 1278.75 to "E6", 1602.0 to "G1", 1246.0 to "G2",
    )

    /** Signal band label (L1, L5, E5b …) for dual-frequency receivers. */
    fun band(carrierHz: Float?): String? {
        val mhz = (carrierHz ?: return null) / 1e6
        return bands.minByOrNull { abs(it.first - mhz) }?.takeIf { abs(it.first - mhz) < 8 }?.second
    }
}
