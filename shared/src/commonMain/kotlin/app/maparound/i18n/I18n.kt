package app.maparound.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.maparound.shared.util.deviceLanguage

/** The languages the app speaks: [code] (ISO 639-1), the language's own name, and its direction. */
enum class Language(val code: String, val nativeName: String, val rtl: Boolean) {
    EN("en", "English", false),
    HE("he", "עברית", true),
    ;

    companion object {
        /** The language for a code ("he", "he-IL", Java's old "iw"), or null when the app doesn't speak it. */
        fun of(code: String?): Language? {
            val c = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
            val modern = if (c == "iw") "he" else c
            return entries.firstOrNull { it.code == modern }
        }
    }
}

/**
 * The app's language: the user's choice (Settings), or the device's when the app speaks it, else
 * English. [language] is snapshot state, so screens drawn with [T] are drawn again in a new one.
 */
object I18n {
    var language: Language by mutableStateOf(Language.EN)
        private set

    /** The user's choice: a [Language.code], or "" for the device's language. */
    var choice: String = ""
        private set

    /** Sets the language from the user's choice ("" = the device's; English when the app doesn't speak it). */
    fun apply(choice: String) {
        this.choice = choice
        language = Language.of(choice.ifEmpty { runCatching { deviceLanguage() }.getOrDefault("en") }) ?: Language.EN
    }

    val rtl: Boolean get() = language.rtl

    /**
     * The language for what comes from elsewhere (Wikipedia, place names): the one chosen in the
     * app, else the device's, which the app may not speak itself ("de" while the app is English).
     */
    val contentLanguage: String get() = choice.ifEmpty { runCatching { deviceLanguage() }.getOrDefault("en") }

    internal val texts: Texts
        get() = when (language) {
            Language.EN -> Texts.English
            Language.HE -> Texts.Hebrew
        }
}

/** The app's texts in its language now: `Text(T.map.whatsHere)`. */
val T: Texts get() = I18n.texts

/**
 * [s] kept left to right inside right-to-left text (coordinates, numbers with spaces or dashes
 * between them, file names, addresses): a bidi isolate in a right-to-left language, as it is
 * otherwise. For display only: not for what's copied, shared or saved.
 */
fun ltr(s: String): String = if (I18n.rtl) "⁦$s⁩" else s
