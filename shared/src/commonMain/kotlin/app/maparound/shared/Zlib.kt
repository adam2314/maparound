package app.maparound.shared

import okio.Path

// zlib, which Okio has on Android and iOS but not in the browser.

/** [data] as a zlib stream (as in PNG's IDAT chunk). */
expect fun zlibDeflate(data: ByteArray): ByteArray

/** Unpacks the gzip file [gz] into [out] (on [fileSystem]). */
expect fun gunzipFile(gz: Path, out: Path)
