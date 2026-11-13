package app.maparound.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.maparound.shared.ioDispatcher
import app.maparound.shared.net.httpGetAsync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** A picture from its encoded bytes (JPEG, PNG, WebP), or null when they aren't one. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** The pictures shown lately, by address, so scrolling back or reopening doesn't load them again. */
private object ImageCache {
    private const val MAX = 80
    private val map = LinkedHashMap<String, ImageBitmap>()

    operator fun get(url: String): ImageBitmap? = map.remove(url)?.also { map[url] = it }

    operator fun set(url: String, image: ImageBitmap) {
        map.remove(url)
        map[url] = image
        while (map.size > MAX) map.remove(map.keys.first())
    }
}

/** Loads the picture at [url] (from the cache when it's there), or null when it can't be had. */
suspend fun loadImage(url: String): ImageBitmap? {
    ImageCache[url]?.let { return it }
    return try {
        val bytes = httpGetAsync(url)
        withContext(ioDispatcher) { decodeImage(bytes) }?.also { ImageCache[url] = it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/**
 * The picture at [url], loaded from the internet; [placeholder] colours the space until it's in
 * (and stays when it can't be loaded).
 */
@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    var image by remember(url) { mutableStateOf(ImageCache[url]) }
    LaunchedEffect(url) {
        if (image == null) image = loadImage(url)
    }
    Box(modifier.background(if (image == null) placeholder else Color.Transparent)) {
        image?.let { Image(it, contentDescription, Modifier.matchParentSize(), contentScale = contentScale) }
    }
}
