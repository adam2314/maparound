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
