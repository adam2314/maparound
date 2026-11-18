package app.maparound.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import app.maparound.i18n.I18n
import app.maparound.i18n.T
import app.maparound.watch.WatchState
import com.google.common.util.concurrent.ListenableFuture

/**
 * MapAround's tile (a swipe from the watch face): while navigating, the next turn's arrow, its
 * distance and the instruction, and what's left; while recording, its distance and climb; else
 * how to start. A tap opens the app. Asked to update when the phone's state changes ([WatchStore]);
 * the watch decides how often it does (the watch face's Ongoing Activity is the one that's live).
 */
class MapAroundTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val s = WatchStore.current(this)
        I18n.apply(s?.language.orEmpty())
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(resourcesVersion(s))
            // Navigating: looked at again after a minute at most, in case an update was skipped.
            .setFreshnessIntervalMillis(if (s?.nav != null) 60_000L else 0L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(s)))
            .build()
        return done(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val b = ResourceBuilders.Resources.Builder().setVersion(requestParams.version)
        WatchStore.current(this)?.nav?.turn?.let { turn ->
            val exit = WatchStore.current(this)?.nav?.exit ?: 0
            b.addIdToImageMapping(
                TURN,
                ResourceBuilders.ImageResource.Builder()
                    .setInlineResource(
                        ResourceBuilders.InlineImageResource.Builder()
                            .setData(turnPng(turn, exit, ARROW_PX))
                            .setWidthPx(ARROW_PX)
                            .setHeightPx(ARROW_PX)
                            .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                            .build(),
                    )
                    .build(),
            )
        }
        return done(b.build())
    }

    /** The resources change with the arrow only. */
    private fun resourcesVersion(s: WatchState?) = s?.nav?.turn?.let { "${it.name}-${s.nav?.exit}" } ?: "none"

    private fun layout(s: WatchState?): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        val nav = s?.nav
        val rec = s?.recording
        when {
            nav != null -> {
                if (nav.turn != null) {
                    column.addContent(LayoutElementBuilders.Image.Builder().setResourceId(TURN).setWidth(dp(44f)).setHeight(dp(44f)).build())
                }
                nav.distance?.let { column.addContent(text(it, 30f, bold = true)) }
                column.addContent(text(nav.title, 15f, lines = 2))
                listOfNotNull(nav.left, nav.arrival).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    column.addContent(gap())
                    column.addContent(text(it, 12f, color = GREY))
                }
            }
            rec != null -> {
                column.addContent(text(T.watch.recording, 14f, color = RED))
                column.addContent(text(rec.distance, 30f, bold = true))
                if (rec.climb.isNotEmpty()) column.addContent(text(rec.climb, 15f))
            }
            else -> {
                column.addContent(text("MapAround", 18f, bold = true))
                column.addContent(gap())
                column.addContent(text(T.watch.openOnPhone, 13f, color = GREY, lines = 3))
            }
        }
        // The whole tile opens the app.
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(WearActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(column.build())
            .build()
    }

    private fun text(t: String, size: Float, bold: Boolean = false, color: Int = WHITE, lines: Int = 1) =
        LayoutElementBuilders.Text.Builder()
            .setText(t)
            .setMaxLines(lines)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .setColor(argb(color))
                    .build(),
            )
            .build()

    private fun gap() = LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build()

    private fun <T> done(value: T): ListenableFuture<T> = CallbackToFutureAdapter.getFuture { it.set(value) }

    private companion object {
        const val TURN = "turn"
        const val ARROW_PX = 96
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFFBDBDBD.toInt()
        const val RED = 0xFFEF5350.toInt()
    }
}
