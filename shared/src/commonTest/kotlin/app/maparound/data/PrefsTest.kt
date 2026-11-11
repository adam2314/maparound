package app.maparound.data

import app.maparound.map.OnlineSource
import app.maparound.sensors.SpeedUnit
import app.maparound.sensors.Vec3
import app.maparound.tools.CoordFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrefsTest {
    private class MemoryStore : KeyValueStore {
        val m = HashMap<String, Any>()
        override fun contains(key: String) = key in m
        override fun getString(key: String) = m[key] as String?
        override fun getBoolean(key: String, default: Boolean) = m[key] as Boolean? ?: default
        override fun getFloat(key: String, default: Float) = m[key] as Float? ?: default
        override fun getInt(key: String, default: Int) = m[key] as Int? ?: default
        override fun putString(key: String, value: String) { m[key] = value }
        override fun putBoolean(key: String, value: Boolean) { m[key] = value }
        override fun putFloat(key: String, value: Float) { m[key] = value }
        override fun putInt(key: String, value: Int) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String) = m[key] as Set<String>?
        override fun putStringSet(key: String, value: Set<String>) { m[key] = value }
    }

    @Test
    fun valuesSurviveARestart() {
        val store = MemoryStore()
        val p = Prefs(store)
        assertEquals(Prefs.STANDARD_PRESSURE, p.seaLevelPressure.value)
        assertNull(p.lastPosition())
        p.setSeaLevelPressure(1003.5f)
        p.setSpeedUnit(SpeedUnit.KNOTS)
        p.setCoordFormat(CoordFormat.MGRS)
        p.setLevelUp(Vec3(0.1f, 0.2f, 9.7f))
        p.setContours(false)
        p.saveLastPosition(MapPos(47.14, 9.52, 13))
        p.addCustomSource(OnlineSource("c1", "My tiles", "https://t.example.com/{z}/{x}/{y}.png", 18, "My tiles", custom = true))

        val again = Prefs(store)
        assertEquals(1003.5f, again.seaLevelPressure.value)
        assertEquals(SpeedUnit.KNOTS, again.speedUnit.value)
        assertEquals(CoordFormat.MGRS, again.coordFormat.value)
        assertEquals(Vec3(0.1f, 0.2f, 9.7f), again.levelUp.value)
        assertEquals(false, again.contours.value)
        assertEquals(13, again.lastPosition()?.zoom)
        assertEquals(listOf("c1"), again.customSources.value.map { it.id })
        assertEquals(18, again.customSources.value.single().maxZoom)

        again.setLevelUp(null)
        assertNull(Prefs(store).levelUp.value)
        again.setBaseLayer("c1")
        again.removeCustomSource("c1")
        assertEquals("osm", Prefs(store).baseLayer.value) // removing the source in use falls back
    }

    @Test
    fun readsSourcesSavedByTheOldAndroidVersion() {
        val store = MemoryStore()
        // As org.json wrote them (escaped slashes too), maxZoom missing on the second.
        store.putString(
            "custom_sources",
            """[{"id":"a","name":"Alpha","url":"https:\/\/a.example\/{z}\/{x}\/{y}.png","maxZoom":17},""" +
                """{"id":"b","name":"Beta","url":"https://b.example/{z}/{x}/{y}"}]""",
        )
        val s = Prefs(store).customSources.value
        assertEquals("https://a.example/{z}/{x}/{y}.png", s[0].urlTemplate)
        assertEquals(17, s[0].maxZoom)
        assertEquals(19, s[1].maxZoom)
        store.putString("custom_sources", "not json")
        assertEquals(emptyList(), Prefs(store).customSources.value)
    }
}
