package app.maparound.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What an icon-only button does, in a few words: shown when the mouse rests on it for a moment
 * (the web app, a tablet with a mouse) and gone when it leaves or clicks; on a long press on touch
 * screens. Nothing when [text] is empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tip(text: String?, content: @Composable () -> Unit) {
    if (text.isNullOrBlank()) {
        content()
        return
    }
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = state,
        // The tip mustn't take the focus (or the next click) from the page.
        focusable = false,
    ) {
        // Compose's tooltip opens on a long press only: the mouse's hover is watched here.
        Box(
            Modifier.pointerInput(text) {
                awaitPointerEventScope {
                    var showing: Job? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val mouse = event.changes.firstOrNull()?.type == PointerType.Mouse
                        when (event.type) {
                            PointerEventType.Enter -> if (mouse) {
                                showing?.cancel()
                                showing = scope.launch {
                                    delay(HOVER_MS)
                                    state.show()
                                }
                            }
                            PointerEventType.Exit, PointerEventType.Press -> if (mouse) {
                                showing?.cancel()
                                state.dismiss()
                            }
                        }
                    }
                }
            },
        ) { content() }
    }
}

/** How long the mouse rests on a button before its tip shows. */
private const val HOVER_MS = 500L
