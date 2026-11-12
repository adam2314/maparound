package app.maparound.map

import app.maparound.shared.util.Xml
import app.maparound.shared.util.XmlHandler

/**
 * A mapsforge render theme's options: its `<stylemenu>`. The menu's visible layers are the
 * theme's *styles*. Each style lists *overlays* that can be switched on and off, and every layer
 * names the rule categories (`cat="…"` in the theme) it draws. [categories] turns the user's
 * [StyleChoice] into the categories to render, the way mapsforge's own apps do: the style's
 * categories plus those of its overlays that are on. A layer with a `parent` also gets the
 * parent's categories and overlays.
 *
 * Plain Kotlin, so the Android renderer, the iOS style converter and the Layers sheet agree.
 * See docs/map-styles.md for writing one.
 */
class StyleMenu(
    val id: String,
    val defaultStyle: String,
    val defaultLang: String,
    /** By id, in the theme's order. */
    val layers: Map<String, Layer>,
) {
    class Layer(
        val id: String,
        val names: Map<String, String>,
        /** A style of its own (shown as a choice), not only an overlay. */
        val visible: Boolean,
        /** As an overlay: on unless the user switches it off. */
        val enabled: Boolean,
        val categories: Set<String>,
        val overlays: List<String>,
    )

    /** The styles to choose from. */
    val styles: List<Layer> get() = layers.values.filter { it.visible }

    /** The chosen style, or the theme's default when that is gone. */
    fun style(id: String?): Layer? =
        id?.let { layers[it] }?.takeIf { it.visible } ?: layers[defaultStyle] ?: styles.firstOrNull()

    fun overlays(style: Layer): List<Layer> = style.overlays.mapNotNull { layers[it] }

    /** A layer's name in English, else the theme's default language, else any. */
    fun name(layer: Layer): String = layer.names["en"] ?: layer.names[defaultLang] ?: layer.names.values.firstOrNull() ?: layer.id

    /** Whether [overlay] is on in [choice]. */
    fun isOn(overlay: Layer, choice: StyleChoice): Boolean = choice.overlays[overlay.id] ?: overlay.enabled

    /** The rule categories to draw for [choice]. */
    fun categories(choice: StyleChoice): Set<String> {
        val style = style(choice.style) ?: return emptySet()
        val out = LinkedHashSet(style.categories)
        for (o in overlays(style)) if (isOn(o, choice)) out += o.categories
        return out
    }

    companion object {
        /** The menu of a theme, or null when it has none (then every rule is drawn). */
        fun parse(themeXml: String): StyleMenu? {
            var menu: StyleMenu? = null
            val layers = LinkedHashMap<String, Layer>()
            var menuId = ""
            var defaultStyle = ""
            var defaultLang = "en"
            // The layer being read.
            var id: String? = null
            var visible = false
            var enabled = false
            val names = LinkedHashMap<String, String>()
            val cats = LinkedHashSet<String>()
            val overlays = ArrayList<String>()
            try {
                Xml.parse(themeXml, object : XmlHandler {
                    override fun start(name: String, attrs: Map<String, String>) {
                        when (name.substringAfter(':')) {
                            "stylemenu" -> {
                                menuId = attrs["id"].orEmpty()
                                defaultStyle = attrs["defaultvalue"].orEmpty()
                                defaultLang = attrs["defaultlang"] ?: "en"
                            }
                            "layer" -> {
                                id = attrs["id"]
                                visible = attrs["visible"] == "true"
                                enabled = attrs["enabled"] == "true"
                                names.clear()
                                cats.clear()
                                overlays.clear()
                                attrs["parent"]?.let { layers[it] }?.let { p ->
                                    cats += p.categories
                                    overlays += p.overlays
                                }
                            }
                            "name" -> if (id != null) attrs["value"]?.let { names[attrs["lang"] ?: defaultLang] = it }
                            "cat" -> if (id != null) attrs["id"]?.let { cats += it }
                            "overlay" -> if (id != null) attrs["id"]?.let { if (it !in overlays) overlays += it }
                            "rule" -> throw Done // the menu comes first: no need to read the rules
                        }
                    }

                    override fun end(name: String) {
                        when (name.substringAfter(':')) {
                            "layer" -> id?.let {
                                layers[it] = Layer(it, LinkedHashMap(names), visible, enabled, LinkedHashSet(cats), ArrayList(overlays))
                                id = null
                            }
                            "stylemenu" -> {
                                menu = StyleMenu(menuId, defaultStyle, defaultLang, LinkedHashMap(layers))
                                throw Done
                            }
                        }
                    }
                })
            } catch (_: Done) {
            }
            return menu
        }

        private object Done : RuntimeException()
    }
}

/** What the user chose in a theme's [StyleMenu]: a style, and overlays switched from their defaults. */
data class StyleChoice(val style: String? = null, val overlays: Map<String, Boolean> = emptyMap())
