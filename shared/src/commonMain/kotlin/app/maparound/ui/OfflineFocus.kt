package app.maparound.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A region of the download list for the Offline tab to show when it opens (the map screen's
 * "No offline map here"): its folder, filtered down to it.
 */
object OfflineFocus {
    /** The region's path ("europe/germany/bayern"), until the Offline tab takes it. */
    val region = MutableStateFlow<String?>(null)

    fun show(path: String) {
        region.value = path
    }
}
