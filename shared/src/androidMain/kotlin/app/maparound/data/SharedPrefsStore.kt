package app.maparound.data

import android.content.Context
import android.content.SharedPreferences

/** Android's SharedPreferences file [name] as a [KeyValueStore]. */
class SharedPrefsStore(context: Context, name: String) : KeyValueStore {
    val sp: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun contains(key: String) = sp.contains(key)
    override fun getString(key: String): String? = sp.getString(key, null)
    override fun getBoolean(key: String, default: Boolean) = sp.getBoolean(key, default)
    override fun getFloat(key: String, default: Float) = sp.getFloat(key, default)
    override fun getInt(key: String, default: Int) = sp.getInt(key, default)
    override fun putString(key: String, value: String) = sp.edit().putString(key, value).apply()
    override fun putBoolean(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    override fun putFloat(key: String, value: Float) = sp.edit().putFloat(key, value).apply()
    override fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()
    override fun remove(key: String) = sp.edit().remove(key).apply()
}
