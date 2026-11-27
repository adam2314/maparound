package app.maparound.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.lifecycleScope
import app.maparound.i18n.T
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.routing.RouteProfile
import app.maparound.search.SearchMerge
import app.maparound.search.SearchResult
import app.maparound.share.GeoRequest
import app.maparound.ui.Fmt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Places to go on the car screen: found for [query] (Assistant's "navigate to X" or "find X"), or
 * for what the driver types or says in the search bar. A pick goes there ([CarMapRenderer.goTo],
 * by [profile], as [mode] says) and the map comes back. [goIfClear]: a navigation request, which
 * goes at once when there's no choice to make ([SearchMerge.clear]).
 */
class CarSearchScreen(
    carContext: CarContext,
    private val renderer: CarMapRenderer,
    query: String?,
    /** Where to search around (the request's point), else where the car is. */
    private val near: GeoPoint?,
    private val profile: RouteProfile?,
    private val mode: GeoRequest.Mode,
    goIfClear: Boolean,
) : Screen(carContext) {
    private var query = query.orEmpty().trim()

    /** What's found for [query]; null while searching. */
    private var results: List<SearchResult>? = emptyList()
    private var job: Job? = null

    init {
        // Not shown yet: its first template is drawn when it's pushed.
        if (this.query.isNotEmpty()) search(this.query, goIfClear, shown = false)
    }

    private fun search(text: String, goIfClear: Boolean, shown: Boolean = true) {
        job?.cancel()
        query = text
        results = null
        if (shown) invalidate()
        job = lifecycleScope.launch {
            val found = CarSearch.find(text, near ?: renderer.here(), limit())
            results = found
            val clear = if (goIfClear) SearchMerge.clear(found) else null
            if (clear != null) pick(clear) else invalidate()
        }
    }

    /** The [i]th place shown (0 the first: Assistant's "select search result"); false when there's none. */
    fun pickAt(i: Int): Boolean {
        val r = results?.getOrNull(i) ?: return false
        pick(r)
        return true
    }

    private fun pick(r: SearchResult) {
        renderer.goTo(GeoPoint(r.lat, r.lon), profile, mode)
        screenManager.remove(this)
    }

    /** As many rows as the car allows in a list. */
    private fun limit(): Int =
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } else {
            DEFAULT_LIMIT
        }

    override fun onGetTemplate(): Template {
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = Unit
            override fun onSearchSubmitted(searchText: String) {
                if (searchText.isNotBlank()) search(searchText.trim(), goIfClear = false)
            }
        }
        val template = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setSearchHint(T.nav.carSearchHint)
            .setInitialSearchText(query)
            .setShowKeyboardByDefault(query.isEmpty())
        val found = results
        if (found == null) {
            template.setLoading(true)
        } else {
            val here = near ?: renderer.here()
            val list = ItemList.Builder()
            for (r in found) {
                val away = here?.let { Fmt.distance(Geo.distance(it.lat, it.lon, r.lat, r.lon).toFloat()) }
                val line = listOfNotNull(away, r.detail.ifBlank { null }).joinToString(" · ")
                val row = Row.Builder().setTitle(r.name).setOnClickListener { pick(r) }
                if (line.isNotEmpty()) row.addText(line)
                list.addItem(row.build())
            }
            if (found.isEmpty() && query.isNotEmpty()) list.setNoItemsMessage(T.nav.carNothingFound(query))
            template.setItemList(list.build())
        }
        return template.build()
    }

    private companion object {
        /** Hosts before car API 2 don't say; six rows fit every car screen. */
        const val DEFAULT_LIMIT = 6
    }
}
