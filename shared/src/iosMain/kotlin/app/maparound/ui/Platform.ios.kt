package app.maparound.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.maparound.ios.pickDocument
import app.maparound.shared.fileSystem
import okio.Path.Companion.toPath

/** The Files picker; the picked file (a temporary copy) is read and removed. */
@Composable
actual fun rememberFilePicker(mimeTypes: List<String>, onPicked: (PickedFile?) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    return remember {
        {
            pickDocument { url ->
                val file = url?.path?.let { path ->
                    runCatching {
                        val p = path.toPath()
                        PickedFile(url?.lastPathComponent, fileSystem.read(p) { readByteArray() }).also { fileSystem.delete(p, mustExist = false) }
                    }.getOrNull()
                }
                callback.value(file)
            }
        }
    }
}

/** iOS has no back button; screens close with their own controls and the edge swipe of sheets. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
