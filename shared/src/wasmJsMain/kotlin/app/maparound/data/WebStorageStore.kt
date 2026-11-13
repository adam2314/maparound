package app.maparound.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private fun lsGet(key: String): String? = js("localStorage.getItem(key)")

private fun lsSet(key: String, value: String): Unit = js("{ localStorage.setItem(key, value); }")

private fun lsRemove(key: String): Unit = js("{ localStorage.removeItem(key); }")

/** The browser's localStorage as a [KeyValueStore]; keys start with "maparound.[name]." (one store per name). */
class WebStorageStore(name: String) : KeyValueStore {
    private val prefix = "maparound.$name."

    override fun contains(key: String) = lsGet(prefix + key) != null
    override fun getString(key: String): String? = lsGet(prefix + key)
    override fun getBoolean(key: String, default: Boolean) = lsGet(prefix + key)?.toBooleanStrictOrNull() ?: default
    override fun getFloat(key: String, default: Float) = lsGet(prefix + key)?.toFloatOrNull() ?: default
    override fun getInt(key: String, default: Int) = lsGet(prefix + key)?.toIntOrNull() ?: default
    override fun putString(key: String, value: String) = lsSet(prefix + key, value)
    override fun putBoolean(key: String, value: Boolean) = lsSet(prefix + key, value.toString())
    override fun putFloat(key: String, value: Float) = lsSet(prefix + key, value.toString())
    override fun putInt(key: String, value: Int) = lsSet(prefix + key, value.toString())
    override fun remove(key: String) = lsRemove(prefix + key)

    override fun getStringSet(key: String): Set<String>? = lsGet(prefix + key)?.let { s ->
        runCatching { Json.parseToJsonElement(s).jsonArray.map { it.jsonPrimitive.content }.toSet() }.getOrNull()
    }

    override fun putStringSet(key: String, value: Set<String>) = lsSet(prefix + key, JsonArray(value.map { JsonPrimitive(it) }).toString())
}
