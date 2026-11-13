package app.maparound.shared

import okio.Buffer
import okio.Deflater
import okio.DeflaterSink
import okio.GzipSource
import okio.Path
import okio.buffer
import okio.use

actual fun zlibDeflate(data: ByteArray): ByteArray {
    val out = Buffer()
    DeflaterSink(out, Deflater()).use { it.write(Buffer().write(data), data.size.toLong()) }
    return out.readByteArray()
}

actual fun gunzipFile(gz: Path, out: Path) {
    GzipSource(fileSystem.source(gz)).buffer().use { input -> fileSystem.write(out) { writeAll(input) } }
}
