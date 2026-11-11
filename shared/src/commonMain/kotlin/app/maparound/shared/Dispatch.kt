package app.maparound.shared

import kotlinx.coroutines.CoroutineDispatcher

/** For blocking file and network work (Dispatchers.IO on both platforms). */
expect val ioDispatcher: CoroutineDispatcher
