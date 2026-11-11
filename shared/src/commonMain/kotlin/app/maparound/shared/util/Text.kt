package app.maparound.shared.util

/** Unicode canonical decomposition (NFD): "é" becomes "e" plus a combining accent. */
expect fun nfd(s: String): String
