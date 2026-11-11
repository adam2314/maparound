package app.maparound.shared.util

import java.text.Normalizer

actual fun nfd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
