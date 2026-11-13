package app.maparound.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.WrongLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maparound.i18n.T
import app.maparound.routing.Guidance
import app.maparound.routing.NavigationSession
import app.maparound.routing.Turn
import app.maparound.routing.TurnGlyph
import app.maparound.shared.util.nowMillis

/** A maneuver arrow (the same shapes the car screen draws). */
@Composable
fun TurnIcon(turn: Turn, exit: Int, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    val glyph = remember(turn, exit) { TurnGlyph.of(turn, exit) }
    Canvas(modifier) {
        val s = size.minDimension
        val ox = (size.width - s) / 2
        val oy = (size.height - s) / 2
        fun at(p: TurnGlyph.Pt) = Offset(ox + p.x * s, oy + p.y * s)
        val stroke = Stroke(width = TurnGlyph.STROKE * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (line in glyph.lines) {
            val path = Path()
            line.forEachIndexed { i, p -> at(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            drawPath(path, color, style = stroke)
        }
        for (head in glyph.heads) {
            val path = Path()
            head.forEachIndexed { i, p -> at(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            path.close()
            drawPath(path, color, style = Fill)
        }
        for (r in glyph.rings) drawCircle(color, r.radius * s, at(r.center), style = if (r.filled) Fill else stroke)
    }
}

/** The next maneuver at the top of the map while navigating. */
@Composable
fun NavigationBanner(nav: NavigationSession.State, modifier: Modifier = Modifier) {
    val p = nav.progress
    val m = p?.next ?: nav.maneuvers.firstOrNull()
    Surface(
        modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                nav.rerouting -> {
                    CircularProgressIndicator(Modifier.size(40.dp), color = LocalContentColor.current)
                    Spacer(Modifier.width(16.dp))
                    Text(T.nav.recalculating, style = MaterialTheme.typography.titleLarge)
                }
                p?.arrived == true -> {
                    TurnIcon(Turn.ARRIVE, 0, Modifier.size(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(T.nav.youHaveArrived, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                p?.offRoute == true -> {
                    Icon(Icons.Filled.WrongLocation, contentDescription = null, Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(T.nav.offRoute, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            nav.error ?: T.nav.offRouteBy(Fmt.distance(Guidance.rounded(p.offsetM).toFloat())),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                m != null -> {
                    TurnIcon(m.turn, m.exit, Modifier.size(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (p == null) T.nav.waitingForGps else Fmt.distance(Guidance.rounded(p.distanceToNextM).toFloat()),
                            style = if (p == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            Guidance.instruction(m),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    p?.then?.let { t ->
                        Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(T.nav.thenLabel, style = MaterialTheme.typography.labelSmall)
                            TurnIcon(t.turn, t.exit, Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Time and distance left, the arrival time, the voice switch and Stop, at the bottom while navigating. */
@Composable
fun NavigationPanel(nav: NavigationSession.State, voice: Boolean, onVoice: (Boolean) -> Unit, onStop: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val p = nav.progress
            val leftM = p?.remainingM ?: nav.route?.stats?.distanceM
            val leftS = p?.remainingS ?: nav.route?.durationS
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(leftS?.let { Fmt.duration(it) }, leftM?.let { Fmt.distance(it.toFloat()) }).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (leftS != null && p?.arrived != true) {
                    Text(
                        T.nav.arrival(Fmt.timeOfDay(nowMillis() + leftS * 1000)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { onVoice(!voice) }) {
                Icon(
                    if (voice) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = if (voice) T.nav.muteVoice else T.nav.unmuteVoice,
                )
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) { Text(T.nav.stopNavigation) }
        }
    }
}
