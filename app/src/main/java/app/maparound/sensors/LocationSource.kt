package app.maparound.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun Context.isLocationEnabled(): Boolean {
    val lm = getSystemService(LocationManager::class.java) ?: return false
    return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
}

/** Altitude above mean sea level when the platform can provide it, else the raw GPS (ellipsoid) altitude. */
fun Location.bestAltitude(): Double? = when {
    Build.VERSION.SDK_INT >= 34 && hasMslAltitude() -> mslAltitudeMeters
    hasAltitude() -> altitude
    else -> null
}

/**
 * Plain framework LocationManager (no Google Play services needed, works fully offline).
 * GPS fixes are preferred; network fixes are only passed through while GPS is silent.
 */
object LocationSource {
    @SuppressLint("MissingPermission")
    fun updates(context: Context, intervalMs: Long = 1000L): Flow<Location> = callbackFlow {
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null || !context.hasLocationPermission()) {
            close()
            return@callbackFlow
        }
        var lastGps = 0L
        // Implement every method: on API < 30 the framework interface has no default methods.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val now = SystemClock.elapsedRealtime()
                if (location.provider == LocationManager.GPS_PROVIDER) {
                    lastGps = now
                } else if (now - lastGps < 10_000) {
                    return
                }
                trySend(location)
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in lm.allProviders }
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { trySend(it) }
        for (p in providers) {
            try {
                lm.requestLocationUpdates(p, intervalMs, 0f, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                // provider vanished or permission revoked meanwhile
            }
        }
        awaitClose { lm.removeUpdates(listener) }
    }
}
