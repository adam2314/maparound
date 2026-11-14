package app.maparound.i18n

/**
 * All the app's texts, by part of the app. Each part is an open class holding the English (the
 * source), with an object per translation below it that overrides what it has translated; what a
 * translation lacks stays English. Texts with values are functions (`fun kmAway(d: String)`), so
 * each language puts the values where its grammar wants them, and counts get their plural forms.
 *
 * To add a language: a [Language], an object for it in each part's file, and a [Texts] here.
 */
class Texts(
    val common: CommonTexts,
    val map: MapTexts,
    val offline: OfflineTexts,
    val party: PartyTexts,
    val tools: ToolsTexts,
    val library: LibraryTexts,
    val sensors: SensorTexts,
    val nav: NavTexts,
    val poi: PoiTexts,
    val legend: LegendTexts,
    val stats: StatsTexts,
) {
    companion object {
        val English = Texts(
            CommonTexts(), MapTexts(), OfflineTexts(), PartyTexts(), ToolsTexts(), LibraryTexts(), SensorTexts(),
            NavTexts(), PoiTexts(), LegendTexts(), StatsTexts(),
        )
        val Hebrew = Texts(
            CommonTextsHe, MapTextsHe, OfflineTextsHe, PartyTextsHe, ToolsTextsHe, LibraryTextsHe, SensorTextsHe,
            NavTextsHe, PoiTextsHe, LegendTextsHe, StatsTextsHe,
        )
    }
}
