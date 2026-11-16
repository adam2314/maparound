package app.maparound.data

/** Small saved settings: SharedPreferences on Android, NSUserDefaults on iOS. */
interface KeyValueStore {
    fun contains(key: String): Boolean
    fun getString(key: String): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getFloat(key: String, default: Float): Float
    fun getInt(key: String, default: Int): Int
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
    fun putFloat(key: String, value: Float)
    fun putInt(key: String, value: Int)
    fun getStringSet(key: String): Set<String>?
    fun putStringSet(key: String, value: Set<String>)
    fun remove(key: String)
}

/** Settings kept only while the app runs: the web map embedded in another website saves nothing. */
class MemoryStore : KeyValueStore {
    private val m = HashMap<String, Any>()
    override fun contains(key: String) = key in m
    override fun getString(key: String) = m[key] as? String
    override fun getBoolean(key: String, default: Boolean) = m[key] as? Boolean ?: default
    override fun getFloat(key: String, default: Float) = m[key] as? Float ?: default
    override fun getInt(key: String, default: Int) = m[key] as? Int ?: default
    override fun putString(key: String, value: String) { m[key] = value }
    override fun putBoolean(key: String, value: Boolean) { m[key] = value }
    override fun putFloat(key: String, value: Float) { m[key] = value }
    override fun putInt(key: String, value: Int) { m[key] = value }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String) = m[key] as? Set<String>
    override fun putStringSet(key: String, value: Set<String>) { m[key] = value.toSet() }
    override fun remove(key: String) { m.remove(key) }
}
