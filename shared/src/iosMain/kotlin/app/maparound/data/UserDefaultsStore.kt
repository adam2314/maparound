package app.maparound.data

import platform.Foundation.NSUserDefaults

/** NSUserDefaults (one suite per store, like Android's preference files) as a [KeyValueStore]. */
class UserDefaultsStore(suiteName: String) : KeyValueStore {
    private val d: NSUserDefaults = NSUserDefaults(suiteName = suiteName)

    override fun contains(key: String) = d.objectForKey(key) != null
    override fun getString(key: String): String? = d.stringForKey(key)
    override fun getBoolean(key: String, default: Boolean) = if (contains(key)) d.boolForKey(key) else default
    override fun getFloat(key: String, default: Float) = if (contains(key)) d.floatForKey(key) else default
    override fun getInt(key: String, default: Int) = if (contains(key)) d.integerForKey(key).toInt() else default
    override fun putString(key: String, value: String) = d.setObject(value, key)
    override fun putBoolean(key: String, value: Boolean) = d.setBool(value, key)
    override fun putFloat(key: String, value: Float) = d.setFloat(value, key)
    override fun putInt(key: String, value: Int) = d.setInteger(value.toLong(), key)
    override fun remove(key: String) = d.removeObjectForKey(key)
    override fun getStringSet(key: String): Set<String>? = d.stringArrayForKey(key)?.mapNotNull { it as? String }?.toSet()
    override fun putStringSet(key: String, value: Set<String>) = d.setObject(value.toList(), key)
}
