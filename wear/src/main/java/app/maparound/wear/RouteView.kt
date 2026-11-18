package app.maparound.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.North
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import app.maparound.i18n.ltr
import app.maparound.routing.GeoPoint
import app.maparound.ui.TurnIcon
import app.maparound.ui.scaleLength
import app.maparound.watch.WatchRoute
import app.maparound.watch.WatchState
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/**
 * The route around you, without a map: the route's line, its turns and stops, and you (the watch's
 * own location, or the phone's through it) as an arrow. North up, or the way you're going up; the
 * crown zooms. The next turn at the top, a scale at the bottom. Almost nothing to draw: easy on the
 * battery, and it answers "which way at this junction?".
 */
@Composable
fun RouteView(route: WatchRoute?, nav: WatchState.Nav?) {
    val context = LocalContext.current
    val me by rememberLocation(context)
    // Metres per dp: about 600 m across the screen at first.
    var scale by remember { mutableFloatStateOf(3f) }
    var headingUp by remember { mutableStateOf(false) }
    var heading by remember { mutableFloatStateOf(0f) }
    me?.takeIf { it.hasBearing() && it.speed > 0.7f }?.let { heading = it.bearing }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e ->
                scale = (scale * exp(e.verticalScrollPixels / 300f)).coerceIn(0.4f, 200f)
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        val centre = me?.let { GeoPoint(it.latitude, it.longitude) } ?: route?.points?.let(::middle)
        Canvas(Modifier.fillMaxSize()) {
            if (centre == null) return@Canvas
            val pxPerM = 1f / (scale * density)
            val cosLat = cos(centre.lat * PI / 180).toFloat()
            fun at(p: GeoPoint) = Offset(
                center.x + ((p.lon - centre.lon) * 111_320.0 * cosLat).toFloat() * pxPerM,
                center.y - ((p.lat - centre.lat) * 110_540.0).toFloat() * pxPerM,
            )
            rotate(if (headingUp) -heading else 0f) {
                route?.let { r ->
                    val line = Path()
                    r.points.forEachIndexed { i, p -> at(p).let { if (i == 0) line.moveTo(it.x, it.y) else line.lineTo(it.x, it.y) } }
                    drawPath(line, Color(0xFF4A148C), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(line, Color(0xFFBA68C8), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    for (t in r.turns) drawCircle(Color.White, 3.dp.toPx(), at(t))
                    for (s in r.stops) {
                        drawCircle(Color.White, 7.dp.toPx(), at(s))
                        drawCircle(Color(0xFFFF7043), 5.dp.toPx(), at(s))
                    }
                }
            }
            // You: an arrow the way you go (always up when the view turns with you).
            me?.let {
                val r = 9.dp.toPx()
                val angle = if (headingUp) 0f else heading
                rotate(angle, center) {
                    val arrow = Path().apply {
                        moveTo(center.x, center.y - r * 1.3f)
                        lineTo(center.x + r, center.y + r)
                        lineTo(center.x, center.y + r * 0.4f)
                        lineTo(center.x - r, center.y + r)
                        close()
                    }
                    drawPath(arrow, Color.White, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
                    drawPath(arrow, Color(0xFF42A5F5))
                }
            }
        }
        // The next turn, at the top.
        nav?.let { n ->
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 18.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                n.turn?.let { TurnIcon(it, n.exit, Modifier.size(26.dp), color = Color.White) }
                n.distance?.let { Text(ltr(it), style = MaterialTheme.typography.title3, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp)) }
            }
        }
        // North up or the way you go up.
        Button(
            onClick = { headingUp = !headingUp },
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(ButtonDefaults.ExtraSmallButtonSize),
            colors = ButtonDefaults.secondaryButtonColors(),
        ) {
            Icon(if (headingUp) Icons.Filled.Navigation else Icons.Filled.North, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        // A scale: a round distance in the phone's units, over the line it takes.
        val (metres, label) = scaleLength(scale * 80.0)
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp), contentAlignment = Alignment.BottomCenter) {
            Text(ltr(label), style = MaterialTheme.typography.caption3, color = Color(0xFFBDBDBD), modifier = Modifier.padding(bottom = 6.dp))
            Canvas(Modifier.size(width = (metres / scale).dp, height = 4.dp)) {
                drawLine(Color(0xFFBDBDBD), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        if (me == null) {
            Text(
                "GPS…",
                style = MaterialTheme.typography.caption3,
                color = Color(0xFFBDBDBD),
                modifier = Modifier.align(Alignment.Center).padding(top = 60.dp),
            )
        }
    }
}

/** The middle of a line's extent. */
private fun middle(points: List<GeoPoint>): GeoPoint =
    GeoPoint((points.minOf { it.lat } + points.maxOf { it.lat }) / 2, (points.minOf { it.lon } + points.maxOf { it.lon }) / 2)

/**
 * Where the watch is, every couple of seconds while shown (Google's fused location: the watch's GPS,
 * or the phone's through it); asks for location access the first time.
 */
@SuppressLint("MissingPermission")
@Composable
private fun rememberLocation(context: Context): androidx.compose.runtime.State<Location?> {
    val location = remember { mutableStateOf<Location?>(null) }
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> allowed = r.values.any { it } }
    LaunchedEffect(Unit) {
        if (!allowed) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    DisposableEffect(allowed) {
        if (!allowed) return@DisposableEffect onDispose { }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location.value = it }
            }
        }
        client.lastLocation.addOnSuccessListener { it?.let { l -> if (location.value == null) location.value = l } }
        client.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L).build(),
            callback,
            Looper.getMainLooper(),
        )
        onDispose { client.removeLocationUpdates(callback) }
    }
    return location
}
