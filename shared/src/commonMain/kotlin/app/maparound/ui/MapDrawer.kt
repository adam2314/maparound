package app.maparound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.maparound.i18n.T

/**
 * A panel over the bottom of the map (the map tools, the route planner, a replayed history) that
 * folds away to show the map: its handle, tapped or dragged down, leaves only [summary] (one line,
 * still with its own buttons); tapped or dragged up, [content] again.
 */
@Composable
fun MapDrawer(
    folded: Boolean,
    onFold: (Boolean) -> Unit,
    summary: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fold by rememberUpdatedState(onFold)
    val isFolded by rememberUpdatedState(folded)
    // How far the handle was dragged: down folds, up unfolds, past a short way.
    var dragged by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { dragged += it }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .semantics { contentDescription = if (folded) T.common.unfold else T.common.fold }
                    .clickable { fold(!isFolded) }
                    .draggable(
                        drag,
                        Orientation.Vertical,
                        onDragStarted = { dragged = 0f },
                        onDragStopped = {
                            if (dragged > FOLD_DRAG_PX && !isFolded) fold(true)
                            else if (dragged < -FOLD_DRAG_PX && isFolded) fold(false)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(width = 36.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(2.dp)),
                )
                Icon(
                    if (folded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).size(20.dp),
                )
            }
            if (folded) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = summary,
                )
            } else {
                Column(content = content)
            }
        }
    }
}

/** How far (px) the handle is dragged to fold or unfold. */
private const val FOLD_DRAG_PX = 24f
