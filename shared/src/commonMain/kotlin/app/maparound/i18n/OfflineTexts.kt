package app.maparound.i18n

/** Offline maps: the download list, installed maps, terrain, trails, routing data, raster maps, and their messages. */
open class OfflineTexts {
    // ---- the Offline maps screen ----
    open val screenTitle = "Offline maps"
    open val importMapFile = "Import a .map file"
    open val addRasterMap = "Add a raster map"
    open fun imported(title: String) = "Imported $title"
    open fun importFailed(error: String?) = "Import failed: $error"
    open val networkError = "Network error"
    open val worldOverview = "World overview"
    open val worldOverviewText = "3 MB background map for zoomed-out views. Recommended."

    /** The button that downloads the world overview map. */
    open val getWorldMap = "Get"
    open val onThisDevice = "On this device"
    open val noMapsYet = "No offline maps yet. Pick a region below, or import a mapsforge .map file " +
        "(e.g. from OpenAndroMaps) with the folder button above."

    /** Beside mapsforge's world overview map where the app has its own world map. */
    open val worldNotNeeded = "not needed any more, the app has its own"
    open val terrainTag = "terrain ✓"
    open val routingTag = "routing ✓"
    open val trailsTag = "trails ✓"
    open fun optionsFor(title: String) = "Options for $title"
    open val contoursAndHillShading = "Contours & hill shading"
    open val offlineRouting = "Offline routing"
    open val markedTrails = "Marked trails"
    open val markedTrailsInMap = "Marked trails: in this map"
    open val deleteMap = "Delete map"

    // ---- section headings ----
    open val rasterMaps = "Raster maps"

    /** The terrain section's heading, and what's downloading in the downloads notification. */
    open val terrain = "Terrain"

    /** The downloads section's heading, and a download's state. */
    open val downloading = "Downloading"
    open val downloadMaps = "Download maps"

    // ---- terrain on the screen ----
    open fun downloadingTerrain(label: String) = "Downloading terrain · $label"
    open fun terrainProgress(done: Int, total: Int, failed: Int) =
        "$done / $total tiles" + (if (failed > 0) " · $failed failed" else "") + " · keep MapAround open"
    open val stopTerrainDownload = "Stop terrain download"
    open val elevationData = "Elevation data"
    open fun elevationTiles(n: Int) = "$n tiles (1°×1°) · contour lines & hill shading"
    open val deleteAllTerrain = "Delete all terrain data"
    open val tipAddOns = "Tip: use ⋮ next to a map to add contour lines & hill shading, offline routing and marked trails."
    open val tipTerrain =
        "Tip: use ⋮ next to a map to add contour lines & hill shading for all of it (they also come by themselves where you look closer)."

    // ---- routing data on the screen ----
    /** A block of routing data ("E5_N45"); also the title of its download. */
    open fun routingData(name: String) = "Routing data $name"
    open val cancelDownload = "Cancel download"
    open fun routingBlock(size: String) = "5°×5° block · $size"
    open fun deleteRoutingData(name: String) = "Delete routing data $name"

    // ---- marked trails on the screen ----
    open fun downloadingTrails(label: String) = "Downloading trails · $label"
    open fun trailsProgress(done: Int, total: Int, failed: Int) =
        "$done / $total areas" + (if (failed > 0) " · $failed failed" else "") + " · keep MapAround open"
    open val stopTrailDownload = "Stop trail download"
    open val trailData = "Trail data"
    open fun trailAreas(n: Int) = "$n areas (1°×1°) · shown in the Topo style"
    open val deleteAllTrails = "Delete all trail data"

    // ---- the list to download from ----
    open val downloadOverWifiOnly = "Download over Wi‑Fi only"
    open val basicMaps = "Basic maps from mapsforge.org"
    open val basicMapsText = "The older maps, without the marked trails and outdoor details"
    open val filter = "Filter"
    open val cantLoadMapList = "Can't load the map list"
    open val retry = "Retry"
    open val continent = "Continent"
    open val regions = "Regions"
    open val allRegions = "All regions"

    // ---- a map in the list ----
    open val tooBigBasic = "Too big for the free build machines yet (needs funding)"
    open val tooBig = "Not available: too big for the free build machines, needs funding"
    open val notBuiltBasic = "Not built by MapAround yet"
    open val notAvailableYet = "Not available yet"
    open val basicMapNote = "basic map from mapsforge.org, no marked trails"
    open fun updated(date: String) = "updated $date"
    open val installed = "Installed"
    open val update = "Update"
    open fun downloadMap(title: String) = "Download $title"

    // ---- downloading and deleting ----
    open fun downloadingMap(title: String) = "Downloading $title…"
    open fun confirmDownloadTitle(title: String) = "Download $title?"

    /** [size] as the server prints it ("528M"), null when unknown; [free]: free space on the device. */
    open fun confirmDownloadText(size: String?, free: String, overWifi: Boolean) =
        "This map is ${size ?: "large"}B ($free free on the device). " +
            if (overWifi) "It will download over Wi‑Fi." else "Consider using Wi‑Fi."
    open fun downloadingRoutingFor(title: String) = "Downloading routing data for $title…"
    open fun downloadingTrailsFor(title: String) = "Downloading marked trails for $title…"
    open fun downloadingTerrainFor(title: String) = "Downloading terrain for $title…"
    open val confirmDeleteTrailsTitle = "Delete trail data?"
    open val confirmDeleteTrailsText = "Marked trails are then shown only online, as an overlay."
    open val confirmDeleteTerrainTitle = "Delete terrain data?"
    open fun confirmDeleteTerrainText(size: String) = "Removes contour lines and hill shading for all maps and frees $size."
    open fun confirmDeleteMapTitle(title: String) = "Delete $title?"
    open fun confirmDeleteMapText(size: String) = "Frees $size. You can download it again later."

    // ---- the terrain dialog ----
    open fun terrainDialogText(title: String) =
        "Elevation data for $title, in 1°×1° tiles from the open Terrain Tiles dataset. " +
            "Contour lines are drawn from it on your device, so they work offline."
    open fun installedTiles(n: Int) = "Installed ✓ ($n tiles)"
    open fun tilesToDownload(missing: Int, total: Int) = "$missing of $total tiles to download"
    open val highDetail = "High detail (30 m)"
    open fun upTo(size: String) = "Up to $size"
    open val standardDetail = "Standard (90 m)"
    open fun upToSmaller(size: String) = "Up to $size · 9× smaller, less detail"
    open fun standardOnly(size: String) = "Standard detail (90 m) · about $size to download"

    // ---- the routing dialog ----
    open fun routingDialogText(title: String) =
        "Lets the route planner work without internet in $title. Without it, routes are " +
            "calculated online by brouter.de. The data comes in 5°×5° blocks, so one block can cover several countries."
    open val installedCheck = "Installed ✓"
    open fun total(size: String) = "Total $size"
    open val cantCheckSizes = "Couldn't check sizes: no connection to brouter.de."

    // ---- the trails dialog ----
    open val trailsDialogText =
        "Waymarked hiking routes from OpenStreetMap (for example red-white-red or the colour-marked trails), " +
            "drawn in their colours with their names in the Topo style. Without this download, they are " +
            "shown only online, as an overlay.\n\n" +
            "It also brings the cliffs, arêtes and ridges, which the downloaded maps leave out. " +
            "Areas fetched before they came along are downloaded again."
    open fun installedAreas(n: Int) = "Installed ✓ ($n areas)"
    open fun areasToDownload(missing: Int, total: Int) = "$missing of $total areas (1°×1°) to download"
    open val trailsDownloadNote =
        "Downloaded from the Overpass API one area at a time. Busy hiking regions like the Alps can take " +
            "several minutes and up to ~50 MB per area; on the phone it is about 10× smaller."

    // ---- raster maps ----
    open val addRasterMapFormats = "Add a raster map (.rmap, .mbtiles, .sqlitedb)"
    open val rasterHelp =
        "TwoNav / CompeGPS maps (e.g. scanned 1:50,000 topo sheets) and tile databases from QGIS, GDAL, MOBAC, Locus or OsmAnd. " +
            "Shown under or instead of the vector map; large files are read in place, not copied."

    /** An RMAP's line: size, pixels, its levels and its projection ([projection]). */
    open fun rmapInfo(size: String, width: Int, height: Int, levels: Int, projection: String) =
        "$size · ${width}×${height} px · $levels levels · $projection"

    /** A tile database's line (MBTiles, SQLiteDB): size, its kind, its zoom levels. */
    open fun tileDbInfo(size: String, projection: String, levels: Int) = "$size · $projection · $levels zoom levels"

    /** A raster map's projection as it was saved when added (from the file's calibration, in English), for display. */
    open fun projection(name: String) = name
    open fun moreFor(name: String) = "More for $name"
    open val addAnotherRasterMap = "Add another raster map"
    open fun couldNotAddMap(error: String) = "Couldn't add the map: $error"

    /** What went wrong when nothing says what. */
    open val error = "error"

    /** The name of a raster map whose file has none. */
    open val rasterMapDefaultName = "Raster map"
    open val cantOpenFile = "Can't open the file"
    open val dbCoverageEmpty = "The database's coverage is empty"
    open val calibrationInvalid = "The map's calibration doesn't give a valid position"
    open val cantReadDatabase = "Can't read the database"
    open val notRmapFile = "Not an RMAP file"
    open fun unsupportedProjection(text: String) = "Unsupported map projection: $text"
    open val notSqliteDatabase = "Not an SQLite database"
    open val noTilesNotTileDb = "No tiles in this database: it isn't an MBTiles or SQLiteDB map"
    open val vectorMbtiles = "This MBTiles holds vector map data, not images; MapAround reads raster MBTiles"
    open val ellipticalMercator = "This map uses the elliptical Mercator projection (Yandex), which MapAround can't show"
    open val dbHasNoTiles = "The database has no tiles"

    // ---- where the maps come from (the list's heading) ----
    open val attributionMapsforge = "OpenStreetMap data via mapsforge.org"
    open val attributionMapAround = "OpenStreetMap data with marked trails, built by MapAround"
    open val cantReadMapList = "Can't read MapAround's list of maps (no connection?)"

    // ---- map downloads (Android, iPhone) ----
    open fun alreadyDownloading(title: String) = "$title is already downloading"
    open fun notEnoughStorage(needed: String, free: String) = "Not enough storage: $needed needed, $free free"

    /** The system's download notification (Android). */
    open fun mapDownloadTitle(title: String) = "$title map"
    open val mapDownloadDescription = "MapAround offline map"
    open fun couldNotStartDownload(error: String?) = "Could not start download: $error"
    open val queued = "Queued"
    open val waitingForWifi = "Waiting for Wi‑Fi"
    open val waitingForNetwork = "Waiting for network"
    open val retryingSoon = "Retrying soon"
    open val paused = "Paused"
    open fun mapInstalled(title: String) = "$title map installed"
    open fun notValidMap(title: String) = "$title: downloaded file is not a valid map"
    open fun downloadFailedCode(title: String, code: Int) = "$title download failed (code $code)"
    open fun downloadFailedHttp(title: String, status: Int) = "$title download failed (HTTP $status)"
    open fun couldNotStoreDownload(title: String) = "$title: could not store the download"
    open fun downloadFailedError(title: String, error: String?) = "$title download failed: $error"

    /** A map download's name when it has none. */
    open val map = "Map"

    // ---- importing a .map file ----
    open val cannotOpenFile = "Cannot open file"
    open val notMapsforgeFile = "Not a mapsforge .map file"
    open val couldNotStoreFile = "Could not store file"
    open val mapNotStored = "The map wasn't stored"
    open val fileIncomplete = "The file is incomplete (still downloading?)"
    open val browserStorageFull = "Not enough storage for this map in the browser"
    open val browserNoFileStorage = "This browser has no file storage for maps"

    /** The web app: the browser downloads the map as any file; [sizeLabel] as the server prints it ("528M"). */
    open fun browserDownloading(title: String, sizeLabel: String?): String {
        val size = sizeLabel?.let { " (${it}B)" }.orEmpty()
        return "Your browser is downloading $title$size. When it's done, add it with the file button at the top."
    }

    // ---- terrain downloads ----
    open val terrainAlreadyRunning = "A terrain download is already running"
    open fun terrainAlreadyInstalled(label: String) = "Terrain for $label is already installed"
    open fun terrainTooMuch(size: String, highDetail: Boolean) =
        "That's too much terrain for one download ($size). " +
            if (highDetail) "Choose standard detail or a smaller region." else "Choose a smaller region."
    open fun notEnoughStorageUpTo(needed: String, free: String) = "Not enough storage: up to $needed needed, $free free"
    open val wifiOnlyOnMobileData = "Wi‑Fi only is on and you're on mobile data"
    open fun terrainReady(label: String) = "Contours & hill shading ready for $label"
    open fun terrainFailed(label: String, failed: Int, total: Int) =
        "Terrain for $label: $failed of $total tiles failed. Tap again to retry."
    open val terrainStopped = "Terrain download stopped"

    /** When the 3D view, line of sight or viewshed need more terrain than is fetched without asking. */
    open fun areaNeedsTiles(n: Int) =
        "This area needs $n terrain tiles: download them with the map (Offline maps → ⋮ → Terrain)."

    // ---- trail downloads ----
    open val trailsAlreadyRunning = "A trail download is already running"
    open fun trailsAlreadyInstalled(label: String) = "Trails for $label are already installed"
    open fun trailsTooLarge(cells: Int) = "That area is too large for one trail download ($cells cells)"
    open fun trailsReady(label: String) = "Marked trails and cliffs ready for $label"
    open fun trailsFailed(label: String, failed: Int, total: Int) =
        "Trails for $label: $failed of $total areas failed. Tap again to retry."
    open val trailsStopped = "Trail download stopped"

    // ---- routing data downloads ----
    open val routingDownloadDescription = "MapAround offline routing"
    open fun routingReady(name: String) = "Offline routing ready for $name"
    open fun routingFailed(name: String) = "Routing data $name failed to download"

    // ---- the downloads notification (Android) ----
    open val trails = "Trails"

    /** "Terrain for Austria": what downloads, for which map. */
    open fun whatFor(what: String, label: String) = "$what for $label"

    /** "Terrain and trails": several downloads at once. */
    open fun together(whats: List<String>) = whats.joinToString(" and ") { it.lowercase() }.replaceFirstChar { it.uppercase() }
    open fun areasDone(done: Int, total: Int) = "$done of $total area${if (total == 1) "" else "s"}"
    open val downloadsFinished = "Downloads finished"
    open val channelDownloads = "Downloads"
    open val channelDownloadsDescription = "Terrain and trail downloads in progress"

    // ---- not there yet (the web app, iPhone) ----
    open val comingToWeb = "Coming to the web app in a later version"
    open val comingToIphone = "Coming to iPhone in a later version"

    /** [later]: [comingToWeb] or [comingToIphone]. */
    open fun trailsLater(later: String) = "Marked trails: $later"
    open fun routingLater(later: String) = "Offline routing: $later"
    open fun rastersLater(later: String) = "Raster maps: $later"

    // ---- routes in the web app (brouter.de) ----
    open val addTwoPoints = "Add at least two points"
    open val noRouteHere = "No route here (a point may be away from roads and paths, or at sea)"
    open fun cantReachRoutingServer(error: String?) = "Couldn't reach the routing server: $error"
}

/** Hebrew. */
object OfflineTextsHe : OfflineTexts() {
    /** Kept left to right (file names, 1°×1°): these texts are only shown right to left. */
    private fun iso(s: String) = "\u2066$s\u2069"

    private fun tiles(n: Int) = if (n == 1) "אריח אחד" else "$n אריחים"
    private fun areas(n: Int) = if (n == 1) "אזור אחד" else "$n אזורים"

    // ---- the Offline maps screen ----
    override val screenTitle = "מפות אופליין"
    override val importMapFile = "ייבוא קובץ ${iso(".map")}"
    override val addRasterMap = "הוספת מפת רסטר"
    override fun imported(title: String) = "המפה $title יובאה"
    override fun importFailed(error: String?) = "הייבוא נכשל: $error"
    override val networkError = "שגיאת רשת"
    override val worldOverview = "מפת עולם כללית"
    override val worldOverviewText = "מפת רקע של 3 MB לתצוגה מרחוק. מומלצת."
    override val getWorldMap = "הורדה"
    override val onThisDevice = "במכשיר הזה"
    override val noMapsYet = "עדיין אין מפות אופליין. אפשר לבחור אזור למטה, או לייבא קובץ ${iso(".map")} של mapsforge " +
        "(למשל מ־OpenAndroMaps) בכפתור התיקייה למעלה."
    override val worldNotNeeded = "כבר לא נחוצה, לאפליקציה יש מפת עולם משלה"
    override val terrainTag = "נתוני גובה ✓"
    override val routingTag = "ניתוב ✓"
    override val trailsTag = "שבילים ✓"
    override fun optionsFor(title: String) = "אפשרויות עבור $title"
    override val contoursAndHillShading = "קווי גובה והצללת תבליט"
    override val offlineRouting = "ניתוב אופליין"
    override val markedTrails = "שבילים מסומנים"
    override val markedTrailsInMap = "שבילים מסומנים: כלולים במפה"
    override val deleteMap = "מחיקת המפה"

    // ---- section headings ----
    override val rasterMaps = "מפות רסטר"
    override val terrain = "נתוני גובה"
    override val downloading = "בהורדה"
    override val downloadMaps = "הורדת מפות"

    // ---- terrain on the screen ----
    override fun downloadingTerrain(label: String) = "מוריד נתוני גובה · $label"
    override fun terrainProgress(done: Int, total: Int, failed: Int) =
        "$done מתוך $total אריחים" + (if (failed > 0) " · $failed נכשלו" else "") + " · יש להשאיר את MapAround פתוחה"
    override val stopTerrainDownload = "עצירת ההורדה של נתוני הגובה"
    override val elevationData = "אריחי גובה"
    override fun elevationTiles(n: Int) = "${tiles(n)} (${iso("1°×1°")}) · קווי גובה והצללת תבליט"
    override val deleteAllTerrain = "מחיקת כל נתוני הגובה"
    override val tipAddOns = "טיפ: בתפריט ⋮ שליד מפה אפשר להוסיף לה קווי גובה והצללת תבליט, ניתוב אופליין ושבילים מסומנים."
    override val tipTerrain =
        "טיפ: בתפריט ⋮ שליד מפה אפשר להוסיף קווי גובה והצללת תבליט לכל השטח שלה (הם גם מגיעים מעצמם במקומות שמתקרבים אליהם)."

    // ---- routing data on the screen ----
    override fun routingData(name: String) = "נתוני ניתוב $name"
    override val cancelDownload = "ביטול ההורדה"
    override fun routingBlock(size: String) = "משבצת ${iso("5°×5°")} · $size"
    override fun deleteRoutingData(name: String) = "מחיקת נתוני הניתוב $name"

    // ---- marked trails on the screen ----
    override fun downloadingTrails(label: String) = "מוריד שבילים · $label"
    override fun trailsProgress(done: Int, total: Int, failed: Int) =
        "$done מתוך $total אזורים" + (if (failed > 0) " · $failed נכשלו" else "") + " · יש להשאיר את MapAround פתוחה"
    override val stopTrailDownload = "עצירת ההורדה של השבילים"
    override val trailData = "נתוני שבילים"
    override fun trailAreas(n: Int) = "${areas(n)} (${iso("1°×1°")}) · מוצגים בסגנון Topo"
    override val deleteAllTrails = "מחיקת כל נתוני השבילים"

    // ---- the list to download from ----
    override val downloadOverWifiOnly = "הורדה רק ב־Wi‑Fi"
    override val basicMaps = "מפות בסיסיות מ־mapsforge.org"
    override val basicMapsText = "המפות הישנות, בלי השבילים המסומנים ופרטי השטח"
    override val filter = "סינון"
    override val cantLoadMapList = "אי אפשר לטעון את רשימת המפות"
    override val retry = "לנסות שוב"
    override val continent = "יבשת"
    override val regions = "אזורים"
    override val allRegions = "כל האזורים"

    // ---- a map in the list ----
    override val tooBigBasic = "עדיין גדולה מדי לשרתי הבנייה החינמיים (נדרש מימון)"
    override val tooBig = "לא זמינה: גדולה מדי לשרתי הבנייה החינמיים, נדרש מימון"
    override val notBuiltBasic = "עדיין לא נבנתה על ידי MapAround"
    override val notAvailableYet = "עדיין לא זמינה"
    override val basicMapNote = "מפה בסיסית מ־mapsforge.org, בלי שבילים מסומנים"
    override fun updated(date: String) = "עודכנה $date"
    override val installed = "מותקנת"
    override val update = "עדכון"
    override fun downloadMap(title: String) = "הורדת $title"

    // ---- downloading and deleting ----
    override fun downloadingMap(title: String) = "מוריד את $title…"
    override fun confirmDownloadTitle(title: String) = "להוריד את $title?"
    override fun confirmDownloadText(size: String?, free: String, overWifi: Boolean) =
        (if (size != null) "גודל המפה ${size}B" else "המפה גדולה") + " (פנויים במכשיר $free). " +
            if (overWifi) "היא תורד ב־Wi‑Fi." else "כדאי להשתמש ב־Wi‑Fi."
    override fun downloadingRoutingFor(title: String) = "מוריד נתוני ניתוב עבור $title…"
    override fun downloadingTrailsFor(title: String) = "מוריד שבילים מסומנים עבור $title…"
    override fun downloadingTerrainFor(title: String) = "מוריד נתוני גובה עבור $title…"
    override val confirmDeleteTrailsTitle = "למחוק את נתוני השבילים?"
    override val confirmDeleteTrailsText = "אחרי המחיקה, שבילים מסומנים יוצגו רק אונליין, כשכבת־על."
    override val confirmDeleteTerrainTitle = "למחוק את נתוני הגובה?"
    override fun confirmDeleteTerrainText(size: String) = "קווי הגובה והצללת התבליט יוסרו מכל המפות, ויתפנו $size."
    override fun confirmDeleteMapTitle(title: String) = "למחוק את $title?"
    override fun confirmDeleteMapText(size: String) = "יתפנו $size. אפשר להוריד אותה שוב בהמשך."

    // ---- the terrain dialog ----
    override fun terrainDialogText(title: String) =
        "נתוני גובה עבור $title, באריחים של ${iso("1°×1°")} ממאגר הנתונים הפתוח Terrain Tiles. " +
            "קווי הגובה משורטטים מהם במכשיר עצמו, כך שהם עובדים גם בלי אינטרנט."
    override fun installedTiles(n: Int) = "מותקן ✓ (${tiles(n)})"
    override fun tilesToDownload(missing: Int, total: Int) = "$missing מתוך $total אריחים להורדה"
    override val highDetail = "פירוט גבוה (30 מ׳)"
    override fun upTo(size: String) = "עד $size"
    override val standardDetail = "רגיל (90 מ׳)"
    override fun upToSmaller(size: String) = "עד $size · קטן פי 9, פחות פירוט"
    override fun standardOnly(size: String) = "פירוט רגיל (90 מ׳) · כ־$size להורדה"

    // ---- the routing dialog ----
    override fun routingDialogText(title: String) =
        "מאפשר לתכנון המסלול לעבוד בלי אינטרנט ב־$title. בלי זה, המסלולים מחושבים אונליין ב־brouter.de. " +
            "הנתונים מגיעים במשבצות של ${iso("5°×5°")}, כך שמשבצת אחת יכולה לכסות כמה מדינות."
    override val installedCheck = "מותקן ✓"
    override fun total(size: String) = "סך הכול $size"
    override val cantCheckSizes = "אי אפשר לבדוק את הגדלים: אין חיבור ל־brouter.de."

    // ---- the trails dialog ----
    override val trailsDialogText =
        "מסלולי הליכה מסומנים מ־OpenStreetMap (למשל אדום־לבן־אדום או השבילים המסומנים בצבעים), " +
            "מצוירים בצבעים שלהם ועם השמות שלהם בסגנון Topo. בלי ההורדה הזו הם מוצגים רק אונליין, כשכבת־על.\n\n" +
            "ההורדה מביאה גם מצוקים, רכסים חדים וקווי רכס, שהמפות שהורדו משמיטות. " +
            "אזורים שהורדו לפני שאלה נוספו יורדו מחדש."
    override fun installedAreas(n: Int) = "מותקן ✓ (${areas(n)})"
    override fun areasToDownload(missing: Int, total: Int) = "$missing מתוך $total אזורים (${iso("1°×1°")}) להורדה"
    override val trailsDownloadNote =
        "ההורדה מ־Overpass API נעשית אזור אחר אזור. באזורי טיולים עמוסים כמו האלפים זה יכול לקחת כמה דקות " +
            "ועד כ־50 MB לאזור; בטלפון הנתונים קטנים בערך פי 10."

    // ---- raster maps ----
    override val addRasterMapFormats = "הוספת מפת רסטר (${iso(".rmap, .mbtiles, .sqlitedb")})"
    override val rasterHelp =
        "מפות TwoNav / CompeGPS (למשל גיליונות טופוגרפיים סרוקים בקנה מידה ${iso("1:50,000")}) ומאגרי אריחים " +
            "מ־QGIS, GDAL, MOBAC, Locus או OsmAnd. מוצגות מתחת למפה הווקטורית או במקומה; קבצים גדולים נקראים " +
            "במקומם, בלי להעתיק אותם."
    override fun rmapInfo(size: String, width: Int, height: Int, levels: Int, projection: String) =
        "$size · ${iso("${width}×${height}")} פיקסלים · " + (if (levels == 1) "רמה אחת" else "$levels רמות") + " · $projection"
    override fun tileDbInfo(size: String, projection: String, levels: Int) =
        "$size · $projection · " + (if (levels == 1) "רמת זום אחת" else "$levels רמות זום")
    override fun projection(name: String) = when {
        name == "Latitude/Longitude" -> "קו רוחב/קו אורך"
        name.startsWith(UNKNOWN_PROJECTION) -> "משוער (היטל לא מוכר ${iso(name.removePrefix(UNKNOWN_PROJECTION).removeSuffix(")"))})"
        else -> name
    }
    override fun moreFor(name: String) = "אפשרויות עבור $name"
    override val addAnotherRasterMap = "הוספת מפת רסטר נוספת"
    override fun couldNotAddMap(error: String) = "אי אפשר להוסיף את המפה: $error"
    override val error = "שגיאה"
    override val rasterMapDefaultName = "מפת רסטר"
    override val cantOpenFile = "אי אפשר לפתוח את הקובץ"
    override val dbCoverageEmpty = "המאגר לא מכסה שום שטח"
    override val calibrationInvalid = "הכיול של המפה לא נותן מיקום תקין"
    override val cantReadDatabase = "אי אפשר לקרוא את המאגר"
    override val notRmapFile = "זה לא קובץ RMAP"
    override fun unsupportedProjection(text: String) = "היטל המפה לא נתמך: $text"
    override val notSqliteDatabase = "זה לא מסד נתונים של SQLite"
    override val noTilesNotTileDb = "אין אריחים במאגר הזה: זו לא מפת MBTiles או SQLiteDB"
    override val vectorMbtiles = "קובץ ה־MBTiles הזה מכיל נתוני מפה וקטוריים ולא תמונות; MapAround קוראת קובצי MBTiles של רסטר"
    override val ellipticalMercator = "המפה הזו משתמשת בהיטל מרקטור אליפטי (Yandex), ש־MapAround לא יכולה להציג"
    override val dbHasNoTiles = "אין אריחים במאגר"

    // ---- where the maps come from (the list's heading) ----
    override val attributionMapsforge = "נתוני OpenStreetMap דרך mapsforge.org"
    override val attributionMapAround = "נתוני OpenStreetMap עם שבילים מסומנים, מעובדים על ידי MapAround"
    override val cantReadMapList = "אי אפשר לקרוא את רשימת המפות של MapAround (אין חיבור לאינטרנט?)"

    // ---- map downloads (Android, iPhone) ----
    override fun alreadyDownloading(title: String) = "המפה $title כבר בהורדה"
    override fun notEnoughStorage(needed: String, free: String) = "אין מספיק מקום פנוי: צריך $needed, פנויים $free"
    override fun mapDownloadTitle(title: String) = "מפת $title"
    override val mapDownloadDescription = "מפת אופליין של MapAround"
    override fun couldNotStartDownload(error: String?) = "אי אפשר להתחיל את ההורדה: $error"
    override val queued = "בתור"
    override val waitingForWifi = "בהמתנה ל־Wi‑Fi"
    override val waitingForNetwork = "בהמתנה לרשת"
    override val retryingSoon = "ניסיון חוזר בקרוב"
    override val paused = "מושהית"
    override fun mapInstalled(title: String) = "המפה $title הותקנה"
    override fun notValidMap(title: String) = "הקובץ שהורד עבור $title אינו מפה תקינה"
    override fun downloadFailedCode(title: String, code: Int) = "ההורדה של $title נכשלה (קוד $code)"
    override fun downloadFailedHttp(title: String, status: Int) = "ההורדה של $title נכשלה (HTTP $status)"
    override fun couldNotStoreDownload(title: String) = "אי אפשר לשמור את ההורדה של $title"
    override fun downloadFailedError(title: String, error: String?) = "ההורדה של $title נכשלה: $error"
    override val map = "מפה"

    // ---- importing a .map file ----
    override val cannotOpenFile = "אי אפשר לפתוח את הקובץ"
    override val notMapsforgeFile = "זה לא קובץ ${iso(".map")} של mapsforge"
    override val couldNotStoreFile = "אי אפשר לשמור את הקובץ"
    override val mapNotStored = "המפה לא נשמרה"
    override val fileIncomplete = "הקובץ לא שלם (אולי הוא עדיין בהורדה?)"
    override val browserStorageFull = "אין בדפדפן מספיק מקום למפה הזו"
    override val browserNoFileStorage = "בדפדפן הזה אין אחסון קבצים למפות"
    override fun browserDownloading(title: String, sizeLabel: String?): String {
        val size = sizeLabel?.let { " (${it}B)" }.orEmpty()
        return "הדפדפן מוריד את $title$size. בסיום, אפשר להוסיף אותה בכפתור הקובץ למעלה."
    }

    // ---- terrain downloads ----
    override val terrainAlreadyRunning = "כבר מתבצעת הורדה של נתוני גובה"
    override fun terrainAlreadyInstalled(label: String) = "נתוני הגובה עבור $label כבר מותקנים"
    override fun terrainTooMuch(size: String, highDetail: Boolean) =
        "יותר מדי נתוני גובה להורדה אחת ($size). " +
            if (highDetail) "אפשר לבחור פירוט רגיל או אזור קטן יותר." else "אפשר לבחור אזור קטן יותר."
    override fun notEnoughStorageUpTo(needed: String, free: String) = "אין מספיק מקום פנוי: צריך עד $needed, פנויים $free"
    override val wifiOnlyOnMobileData = "ההורדה מוגדרת רק ב־Wi‑Fi, והמכשיר מחובר לרשת הסלולרית"
    override fun terrainReady(label: String) = "קווי גובה והצללת תבליט מוכנים עבור $label"
    override fun terrainFailed(label: String, failed: Int, total: Int) =
        "נתוני גובה עבור $label: $failed מתוך $total אריחים נכשלו. אפשר להקיש שוב כדי לנסות מחדש."
    override val terrainStopped = "ההורדה של נתוני הגובה נעצרה"
    override fun areaNeedsTiles(n: Int) =
        "לאזור הזה צריך $n אריחי גובה: אפשר להוריד אותם עם המפה (מפות אופליין ← ⋮ ← קווי גובה והצללת תבליט)."

    // ---- trail downloads ----
    override val trailsAlreadyRunning = "כבר מתבצעת הורדה של שבילים"
    override fun trailsAlreadyInstalled(label: String) = "השבילים עבור $label כבר מותקנים"
    override fun trailsTooLarge(cells: Int) = "האזור גדול מדי להורדת שבילים אחת ($cells משבצות)"
    override fun trailsReady(label: String) = "שבילים מסומנים ומצוקים מוכנים עבור $label"
    override fun trailsFailed(label: String, failed: Int, total: Int) =
        "שבילים עבור $label: $failed מתוך $total אזורים נכשלו. אפשר להקיש שוב כדי לנסות מחדש."
    override val trailsStopped = "ההורדה של השבילים נעצרה"

    // ---- routing data downloads ----
    override val routingDownloadDescription = "ניתוב אופליין של MapAround"
    override fun routingReady(name: String) = "ניתוב אופליין מוכן עבור $name"
    override fun routingFailed(name: String) = "ההורדה של נתוני הניתוב $name נכשלה"

    // ---- the downloads notification (Android) ----
    override val trails = "שבילים"
    override fun whatFor(what: String, label: String) = "$what עבור $label"
    override fun together(whats: List<String>) = whats.joinToString(" ו")
    override fun areasDone(done: Int, total: Int) = "$done מתוך ${areas(total)}"
    override val downloadsFinished = "ההורדות הסתיימו"
    override val channelDownloads = "הורדות"
    override val channelDownloadsDescription = "הורדות של נתוני גובה ושבילים שמתבצעות עכשיו"

    // ---- not there yet (the web app, iPhone) ----
    override val comingToWeb = "בגרסה עתידית של אפליקציית הרשת"
    override val comingToIphone = "בגרסה עתידית ל־iPhone"
    override fun trailsLater(later: String) = "שבילים מסומנים: $later"
    override fun routingLater(later: String) = "ניתוב אופליין: $later"
    override fun rastersLater(later: String) = "מפות רסטר: $later"

    // ---- routes in the web app (brouter.de) ----
    override val addTwoPoints = "יש להוסיף לפחות שתי נקודות"
    override val noRouteHere = "אין כאן מסלול (ייתכן שאחת הנקודות רחוקה מכבישים ומשבילים, או בים)"
    override fun cantReachRoutingServer(error: String?) = "אין חיבור לשרת הניתוב: $error"

    /** How Rmap.kt names a projection it doesn't know (saved in English with the map). */
    private const val UNKNOWN_PROJECTION = "Approximate (unknown projection "
}
