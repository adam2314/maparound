package app.maparound.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Phase 3 shows UIDocumentPickerViewController here; until then nothing is picked. */
@Composable
actual fun rememberFilePicker(mimeTypes: List<String>, onPicked: (PickedFile?) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    return remember { { callback.value(null) } }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
