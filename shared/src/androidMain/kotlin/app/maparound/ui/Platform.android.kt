package app.maparound.ui

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberFilePicker(mimeTypes: List<String>, onPicked: (PickedFile?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            callback.value(null)
        } else {
            scope.launch {
                val file = withContext(Dispatchers.IO) {
                    runCatching {
                        val resolver = context.contentResolver
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                        resolver.openInputStream(uri)?.use { PickedFile(name, it.readBytes()) }
                    }.getOrNull()
                }
                callback.value(file)
            }
        }
    }
    return { launcher.launch(mimeTypes.toTypedArray()) }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
