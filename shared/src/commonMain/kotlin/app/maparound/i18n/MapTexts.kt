package app.maparound.i18n

/** The map screen's texts: the map and its buttons, pins, layers, What's here?, photos, sharing, recording, the 3D view. */
open class MapTexts {
    // ---- the map's buttons ----
    open val searchPlaces = "Search places"
    open val mapLayers = "Map layers"
    open val planRoute = "Plan a route"
    open val mapToolsButton = "Map tools: azimuth, line of sight, viewshed, coordinates"
    open val view3dHere = "3D view of this area"
    open val recordTrack = "Record a track"
    open val myLocation = "My location"
    open val waitingForFix = "Waiting for a location fix…"

    // ---- no offline map ----
    open val noOfflineMapsYet = "No offline maps yet"
    open val noOfflineMapsHint = "Download a country to browse without internet, or pick an online map in Layers."

    /** The button that gets the map made for where the map looks ("Get Bavaria"). */
    open fun getRegion(title: String) = "Get $title"
    open val allMaps = "All maps"
    open val getOfflineMaps = "Get offline maps"
    open val noOfflineMapHere = "No offline map here"
    open val getMap = "Get map"

    // ---- terrain the map gets by itself ----
    /** The map's own terrain download, as the Offline tab names it ("Downloading terrain · contours here"). */
    open val contoursHere = "contours here"

    /** Tile [done] of [total]; no count for one tile. */
    open fun gettingTerrain(done: Int, total: Int) =
        "Getting terrain for contours" + (if (total > 1) " ($done of $total)" else "") + "…"

    // ---- credits under the map ----
    open val osmAttribution = "© OpenStreetMap contributors · mapsforge"
    open val terrainAttribution = "terrain: Mapzen, AWS Terrain Tiles"

    // ---- what other apps shared, on the map ----
    open fun importedTrack(name: String, waypoints: Int) =
        "Imported “$name” into Library" + (if (waypoints > 0) " and $waypoints waypoint${if (waypoints == 1) "" else "s"} into Places" else "")
    open val placesAlreadySaved = "Those places are already saved"
    open fun placesAdded(n: Int) = "Added $n place${if (n == 1) "" else "s"} to Library → Places"

    // ---- the pin card ----
    /** "My location (±12 m)", or without the accuracy when there's none. */
    open fun myLocationWithin(accuracyM: Int?) = if (accuracyM == null) "My location" else "My location (±$accuracyM m)"

    /** "1.2 km from you · 45° NE" */
    open fun fromYou(distance: String, degrees: Int, direction: String) = "$distance from you · $degrees° $direction"
    open val whatsHere = "What's here?"
    open val wikipedia = "Wikipedia"
    open val routeHere = "Route here"
    open val shareLocation = "Share location"
    open val copyCoordinates = "Copy coordinates"
    open val saveAsPlace = "Save as a place"
    open val whatCanBeSeen = "What can be seen from here"
    open val meetHere = "Meet here"

    /** The meeting point's name when the pin has none (the party sees it). */
    open val meetingPoint = "Meeting point"

    // ---- party members, radios and the meeting point on the map ----
    /** The meeting point's marker. */
    open val meetMarker = "Meet"
    open fun radioNamed(name: String) = "Radio $name"

    /** How far and which way: "1.2 km NE". */
    open fun distanceToward(distance: String, direction: String) = "$distance $direction"
    open fun battery(pct: Int) = "battery $pct%"

    /** How a member's position came: "via internet + radio". */
    open fun via(ways: String) = "via $ways"

    // ---- the Layers sheet ----
    open val sectionOffline = "Offline"
    open val offlineVectorMaps = "Offline vector maps"
    open val noMapsDownloadedYet = "No maps downloaded yet"
    open fun mapFilesOnDevice(n: Int) = "$n map file(s) on this device"
    open val style = "Style"
    open fun stylesAdded(n: Int, first: String) = if (n == 1) "Added the style \"$first\"" else "Added $n styles"
    open val couldntImportStyle = "Couldn't import that style"
    open fun styleRemoved(name: String) = "Removed the style \"$name\""
    open val importStyle = "Import a style (.xml or .zip)"
    open val terrain = "Terrain"
    open val contourLines = "Contour lines"
    open val contoursOnlyIn = "Drawn by the Topo style and imported styles"
    open val hillShading = "Hill shading"
    open val terrainComesByItself =
        "Terrain for contours & shading comes by itself as you look closer at your maps. All of a map's: Offline maps →"
    open val backgroundHeader = "Background of the offline map"
    open val mapColours = "Map colours"
    open val vectorAsItIs = "The vector map as it is"
    open val satelliteImagery = "Satellite imagery"
    open val satelliteUnder = "Esri World Imagery under roads, trails & labels (online, cached)"
    open fun rasterMap(projection: String) = "Raster map · $projection"
    open val addRasterMap = "Add a raster map (.rmap, .mbtiles, .sqlitedb)"
    open val vectorOnTop = "Roads, trails & labels on top"
    open val vectorOnTopHint = "From the offline vector map, drawn over the background"
    open val overlays = "Overlays"
    open val markedTrails = "Marked hiking trails"
    open val trailsOfflineInTopo = "Offline in the Topo style; online overlay on other maps"
    open val trailsOnline = "Online overlay (waymarkedtrails.org). Download them per map for offline use."
    open val savedPlaces = "Saved places"
    open val savedPlacesHint = "Your places from Library → Places"
    open val sectionOnline = "Online"
    open val addCustomSource = "Add custom tile source"
    open val importList = "Import list"
    open val exportList = "Export list"

    /** The title of the shared list of tile sources. */
    open val tileSourcesFileTitle = "MapAround tile sources"
    open val tileSourcesAlreadyListed = "Those tile sources are already in the list"
    open fun tileSourcesAdded(n: Int, first: String) = if (n == 1) "Added the tile source \"$first\"" else "Added $n tile sources"
    open val couldntReadList = "Couldn't read that list"
    open fun removeNamed(name: String) = "Remove $name"

    // ---- a style's options ----
    open val variant = "Variant"

    /** Above the style's overlays to switch on and off. */
    open val show = "Show"

    /** The Topo style's options by their id in topo.xml; null: the name the theme gives. */
    open fun topoOption(id: String): String? = null

    // ---- the offline maps' styles ----
    open val styleClassic = "Classic"
    open val styleTopoAbout = "MapAround's hiking style: contours, trails, peaks"
    open val styleClassicAbout = "mapsforge's default style"
    open val styleOsmarenderAbout = "The classic OpenStreetMap look"
    open val styleBikerAbout = "Cycle routes and cycleways first"
    open val styleMotoriderAbout = "Roads by type, for riding and driving"

    /** An imported style: the folder it came in. */
    open fun importedStyle(folder: String) = "Imported · $folder"
    open val notAStyle = "That isn't a mapsforge map style (render theme XML)"
    open val noStyleInFile = "No map style (render theme XML) in that file"
    open fun styleUnreadable(reason: String) = "The map style can't be read: $reason"
    open val unknownError = "unknown error"
    open fun zipFileOutside(name: String) = "The zip has a file outside its folder: $name"
    open val zipTooManyFiles = "The zip has too many files"
    open val zipTooLarge = "The zip is too large once unpacked"
    open val styleImportOnIphoneLater = "Importing map styles comes to iPhone in a later version"

    // ---- online maps (their names that aren't names of their own, and what they are) ----
    open val srcHumanitarian = "Humanitarian"
    open val srcSatellite = "Satellite"
    open val srcIgnAerial = "IGN aerial"
    open val srcOsmFrance = "OpenStreetMap, OSM France's style"
    open val srcOsmGermany = "OpenStreetMap, the German community's style"
    open val srcOpenTopoMap = "Topographic, contour lines"
    open val srcCyclOsm = "Cycling & outdoor"
    open val srcHot = "Humanitarian OSM style"
    open val srcEsriTopo = "Topographic, worldwide"
    open val srcEsriStreet = "Street map, worldwide"
    open val srcEsriImagery = "Esri World Imagery (aerial / satellite)"
    open val srcSwisstopo = "Switzerland · national topographic map"
    open val srcBasemapAt = "Austria · official base map"
    open val srcKartverket = "Norway · topographic map"
    open val srcIgnPlan = "France · IGN's map"
    open val srcIgnOrtho = "France · aerial photos"
    open val srcUsgsTopo = "United States · topographic map"
    open val urlMustStartHttps = "URL must start with https://"
    open val urlMustHaveZxy = "URL must contain {z}, {x} and {y}"
    open val noTileSourcesFound = "No tile sources found. Expected URL templates with {z}, {x} and {y}."

    // ---- a custom tile source ----
    open val customSource = "Custom tile source"
    open val customSourceHelp =
        "Any XYZ raster tile service, e.g. https://{s}.example.com/{z}/{x}/{y}.png. Check the provider's usage terms."
    open val nameField = "Name"
    open val urlTemplate = "URL template"
    open val maxZoom = "Max zoom"

    // ---- the route planner ----
    open val closePlanner = "Close route planner"
    open val plannerTapStart = "Tap the map to set the start. Tap a numbered point to remove it."
    open val plannerTapNext = "Now tap the destination, then more stops. To change the route, hold a stop or the line and drag it."
    open val routedOffline = "Routed on this phone (BRouter)"
    open val routedOnline = "Routed online by brouter.de"
    open val straightWithHeights = "Straight lines · heights from terrain data"
    open val straightNoHeights = "Straight lines · add terrain data in Offline maps for heights"
    open val reverseDirection = "Reverse direction"
    open val clearAllPoints = "Clear all points"
    open val shareRoute = "Share route"
    open val saveRoute = "Save route"

    /** A route's name the app gives it: "Route 11 Nov". */
    open fun routeName(date: String) = "Route $date"

    /** A drawn route's (straight lines): "Drawn route 11 Nov 14:05". */
    open fun drawnRouteName(date: String) = "Drawn route $date"
    open fun savedToRoutes(name: String) = "Saved \"$name\" to Routes"
    open fun savedToPlaces(name: String) = "Saved “$name” to Places"

    // ---- What's here? ----
    /** The sheet's title when the point has no name. */
    open val whatsHereTitle = "What's here"
    open fun aboutOnWikipedia(title: String) = "About $title on Wikipedia"
    open val wikipediaNeedsConnection = "Wikipedia needs a connection."
    open val photosAround = "Photos around here"
    open val photosNeedConnection = "Photos need a connection."
    open val lookingForPhotos = "Looking for photos taken around here…"
    open val noPhotosAround = "No photos taken within 10 km on Wikimedia Commons."
    open val aroundOnMap = "Around here on the map"
    open val noPlacesAround = "No other named places on the map here at this zoom."
    open val onWikipediaNearby = "On Wikipedia, nearby"
    open val lookingForArticles = "Looking for articles about places around here…"
    open fun noArticlesWithin(km: Int) = "No articles about places within $km km."

    // ---- a photo ----
    open fun photoBy(author: String) = "Photo: $author"
    open val fromCommons = "From Wikimedia Commons"
    open val fromCommonsOffline = "From Wikimedia Commons; its author and licence need a connection."
    open val photoPage = "Photo's page"
    open val whereTaken = "Where it was taken"

    // ---- sharing a point or a route ----
    open val sendAsLink = "Send as a link"
    open val sendAsLinkHint = "Google Maps and OpenStreetMap links; opens for anyone"
    open val sendAsGpx = "Send as GPX waypoint"
    open val sendAsGpxHint = "For GPS devices and apps like OsmAnd or Garmin"
    open val openElsewhere = "Open in another app"
    open val openElsewhereHint = "Navigation or other map apps"

    /** A shared point's name when it has none. */
    open val location = "Location"
    open val openWith = "Open with"
    open val shareRouteLink = "Share route link"
    open val noAppForThis = "No app can handle this"

    /** The text sent with places' GPX file. */
    open fun placesShareText(title: String, n: Int) = "$title: $n places (GPX attached)"

    /** In the text sent with a route: its link on brouter.de. */
    open fun openTheRoute(link: String) = "Open the route: $link"

    /** In the text sent with a track: where it starts (a map link). */
    open fun routeStartsAt(link: String) = "Start: $link"
    open val gpxAttached = "(GPX file attached, if your app received it)"

    // ---- what other apps share with MapAround ----
    open val noLocationShared = "No location found in what was shared"
    open val openingSharedLink = "Opening the shared link…"
    open val couldntOpenLink = "Couldn't open the link (no internet?)"
    open val linkWithoutCoordinates = "That link has an address but no coordinates"
    open fun importingMap(name: String) = "Importing map $name…"
    open fun opening(name: String) = "Opening $name…"
    open fun offlineMapAdded(title: String) = "Offline map $title added"
    open fun couldntAddMap(reason: String?) = "Couldn't add the map: $reason"
    open val cantOpenFile = "Can't open the file"

    /** The name of what a file brings when the file has none. */
    open val imported = "Imported"
    open fun couldntReadGpx(reason: String?) = "Couldn't read the GPX: $reason"
    open val notGpx = "That file isn't a GPX file"
    open val noTrackInGpx = "No track, route or waypoint in this GPX file"
    open val rasterMapsOnIphoneLater = "Raster maps: Coming to iPhone in a later version"

    // ---- recording ----
    open fun gpsAccuracy(m: Int) = "GPS ±$m m…"
    open val waitingForGps = "Waiting for GPS…"

    // ---- the 3D view ----
    open val close3d = "Close 3D view"
    open val faceNorth = "Face north"

    /** The 3D view's button that shows its settings. */
    open val view3dSettings = "Relief and buildings"
    open val backToMap = "Back to the map"

    /** The relief's exaggeration: "Relief ×1.5". */
    open fun relief(times: String) = "Relief ×$times"
    open val buildingsCloseUp = "Buildings (close up)"
    open val gestures3d = "1 finger: move · pinch: zoom · 2 fingers: turn and tilt · double-tap: zoom in"
    open fun loadingTiles(n: Int) = "Loading $n tiles…"
    open val loadingRelief = "Loading the relief…"

    /** The 3D view's terrain download, as the Offline tab names it ("Downloading terrain · the 3D view"). */
    open val the3dView = "the 3D view"
    open fun gettingRelief(done: Int, total: Int) = "Getting the relief data ($done of $total)…"
}

/** Hebrew. */
object MapTextsHe : MapTexts() {
    override val searchPlaces = "חיפוש מקומות"
    override val mapLayers = "שכבות מפה"
    override val planRoute = "תכנון מסלול"
    override val mapToolsButton = "כלי מפה: אזימוט, קו ראייה, שדה ראייה, קואורדינטות"
    override val view3dHere = "תצוגת תלת־ממד של האזור"
    override val recordTrack = "הקלטת מסלול"
    override val myLocation = "המיקום שלי"
    override val waitingForFix = "בהמתנה לקליטת מיקום…"

    override val noOfflineMapsYet = "עדיין אין מפות אופליין"
    override val noOfflineMapsHint = "אפשר להוריד מדינה כדי לגלוש במפה בלי אינטרנט, או לבחור מפה אונליין בשכבות."
    override fun getRegion(title: String) = "הורדת $title"
    override val allMaps = "כל המפות"
    override val getOfflineMaps = "הורדת מפות אופליין"
    override val noOfflineMapHere = "אין כאן מפת אופליין"
    override val getMap = "הורדת המפה"

    override val contoursHere = "קווי גובה כאן"
    override fun gettingTerrain(done: Int, total: Int) =
        "הורדת נתוני גובה לקווי הגובה" + (if (total > 1) " ($done מתוך $total)" else "") + "…"

    override val osmAttribution = "© תורמי OpenStreetMap · mapsforge"
    override val terrainAttribution = "נתוני גובה: Mapzen, AWS Terrain Tiles"

    override fun importedTrack(name: String, waypoints: Int) = when (waypoints) {
        0 -> "\"$name\" יובא לספרייה"
        1 -> "\"$name\" יובא לספרייה, ונקודת דרך אחת למקומות"
        2 -> "\"$name\" יובא לספרייה, ושתי נקודות דרך למקומות"
        else -> "\"$name\" יובא לספרייה, ו־$waypoints נקודות דרך למקומות"
    }
    override val placesAlreadySaved = "המקומות האלה כבר שמורים"
    override fun placesAdded(n: Int) = when (n) {
        1 -> "מקום אחד נוסף לספרייה ← מקומות"
        2 -> "שני מקומות נוספו לספרייה ← מקומות"
        else -> "$n מקומות נוספו לספרייה ← מקומות"
    }

    override fun myLocationWithin(accuracyM: Int?) = if (accuracyM == null) "המיקום שלי" else "המיקום שלי (±$accuracyM מ׳)"
    override fun fromYou(distance: String, degrees: Int, direction: String) = "$distance ממך · $degrees° $direction"
    override val whatsHere = "מה יש כאן?"
    override val wikipedia = "ויקיפדיה"
    override val routeHere = "מסלול לכאן"
    override val shareLocation = "שיתוף מיקום"
    override val copyCoordinates = "העתקת קואורדינטות"
    override val saveAsPlace = "שמירה כמקום"
    override val whatCanBeSeen = "מה רואים מכאן"
    override val meetHere = "להיפגש כאן"
    override val meetingPoint = "נקודת מפגש"

    override val meetMarker = "מפגש"
    override fun radioNamed(name: String) = "מכשיר קשר $name"
    override fun distanceToward(distance: String, direction: String) = "$distance לכיוון $direction"
    override fun battery(pct: Int) = "סוללה $pct%"
    override fun via(ways: String) = "דרך $ways"

    override val sectionOffline = "אופליין"
    override val offlineVectorMaps = "מפות וקטוריות אופליין"
    override val noMapsDownloadedYet = "עדיין לא הורדו מפות"
    override fun mapFilesOnDevice(n: Int) = when (n) {
        1 -> "קובץ מפה אחד במכשיר"
        2 -> "שני קובצי מפה במכשיר"
        else -> "$n קובצי מפה במכשיר"
    }
    override val style = "סגנון"
    override fun stylesAdded(n: Int, first: String) = when (n) {
        1 -> "הסגנון \"$first\" נוסף"
        2 -> "נוספו שני סגנונות"
        else -> "נוספו $n סגנונות"
    }
    override val couldntImportStyle = "לא ניתן לייבא את הסגנון"
    override fun styleRemoved(name: String) = "הסגנון \"$name\" הוסר"
    override val importStyle = "ייבוא סגנון (⁦.xml⁩ או ⁦.zip⁩)"
    override val terrain = "תבליט"
    override val contourLines = "קווי גובה"
    override val contoursOnlyIn = "מוצגים בסגנון Topo ובסגנונות מיובאים"
    override val hillShading = "הצללת תבליט"
    override val terrainComesByItself =
        "נתוני הגובה לקווי הגובה ולהצללה יורדים מעצמם כשמתקרבים במפה. לכל שטח המפה: מפות אופליין ←"
    override val backgroundHeader = "רקע מפת האופליין"
    override val mapColours = "צבעי המפה"
    override val vectorAsItIs = "המפה הווקטורית כמו שהיא"
    override val satelliteImagery = "צילומי לוויין"
    override val satelliteUnder = "Esri World Imagery מתחת לכבישים, לשבילים ולשמות (אונליין, נשמר במטמון)"
    override fun rasterMap(projection: String) = "מפה רסטרית · $projection"
    override val addRasterMap = "הוספת מפה רסטרית (⁦.rmap, .mbtiles, .sqlitedb⁩)"
    override val vectorOnTop = "כבישים, שבילים ושמות מעל"
    override val vectorOnTopHint = "ממפת האופליין הווקטורית, מעל הרקע"
    override val overlays = "שכבות־על"
    override val markedTrails = "שבילי הליכה מסומנים"
    override val trailsOfflineInTopo = "אופליין בסגנון Topo; שכבה אונליין במפות אחרות"
    override val trailsOnline = "שכבה אונליין (waymarkedtrails.org). אפשר להוריד את השבילים לכל מפה לשימוש אופליין."
    override val savedPlaces = "מקומות שמורים"
    override val savedPlacesHint = "המקומות שלך מספרייה ← מקומות"
    override val sectionOnline = "אונליין"
    override val addCustomSource = "הוספת מקור מפה מותאם"
    override val importList = "ייבוא רשימה"
    override val exportList = "ייצוא רשימה"
    override val tileSourcesFileTitle = "מקורות מפה של MapAround"
    override val tileSourcesAlreadyListed = "מקורות המפה האלה כבר ברשימה"
    override fun tileSourcesAdded(n: Int, first: String) = when (n) {
        1 -> "מקור המפה \"$first\" נוסף"
        2 -> "נוספו שני מקורות מפה"
        else -> "נוספו $n מקורות מפה"
    }
    override val couldntReadList = "לא ניתן לקרוא את הרשימה"
    override fun removeNamed(name: String) = "הסרת $name"

    override val variant = "גרסה"
    override val show = "מה להציג"
    override fun topoOption(id: String): String? = when (id) {
        "landcover" -> "כיסוי קרקע (יער, שדות, שטח בנוי)"
        "buildings" -> "מבנים וגדרות"
        "boundaries" -> "גבולות ושטחים מוגנים"
        "transport" -> "מסילות, מעבורות, מסלולי סקי ורכבלים"
        "waynames" -> "שמות כבישים ושבילים"
        "places" -> "שמות מקומות"
        "natural" -> "פסגות, מעיינות ומערות"
        "pois" -> "נקודות עניין"
        else -> null
    }

    override val styleClassic = "קלאסי"
    override val styleTopoAbout = "סגנון הטיולים של MapAround: קווי גובה, שבילים, פסגות"
    override val styleClassicAbout = "סגנון ברירת המחדל של mapsforge"
    override val styleOsmarenderAbout = "המראה הקלאסי של OpenStreetMap"
    override val styleBikerAbout = "מסלולי אופניים ושבילי אופניים קודם כול"
    override val styleMotoriderAbout = "כבישים לפי סוג, לרכיבה ולנהיגה"
    override fun importedStyle(folder: String) = "מיובא · $folder"
    override val notAStyle = "זה לא סגנון מפה של mapsforge (render theme XML)"
    override val noStyleInFile = "אין בקובץ הזה סגנון מפה (render theme XML)"
    override fun styleUnreadable(reason: String) = "לא ניתן לקרוא את סגנון המפה: $reason"
    override val unknownError = "שגיאה לא ידועה"
    override fun zipFileOutside(name: String) = "בקובץ ה־zip יש קובץ מחוץ לתיקייה שלו: $name"
    override val zipTooManyFiles = "בקובץ ה־zip יש יותר מדי קבצים"
    override val zipTooLarge = "קובץ ה־zip גדול מדי אחרי החילוץ"
    override val styleImportOnIphoneLater = "ייבוא סגנונות מפה יגיע לאייפון בגרסה מאוחרת יותר"

    override val srcHumanitarian = "הומניטרית"
    override val srcSatellite = "לוויין"
    override val srcIgnAerial = "IGN – צילומי אוויר"
    override val srcOsmFrance = "OpenStreetMap, בסגנון OSM צרפת"
    override val srcOsmGermany = "OpenStreetMap, בסגנון הקהילה הגרמנית"
    override val srcOpenTopoMap = "טופוגרפית, עם קווי גובה"
    override val srcCyclOsm = "אופניים ופעילות בטבע"
    override val srcHot = "סגנון OSM הומניטרי"
    override val srcEsriTopo = "טופוגרפית, כל העולם"
    override val srcEsriStreet = "מפת רחובות, כל העולם"
    override val srcEsriImagery = "Esri World Imagery (צילומי אוויר / לוויין)"
    override val srcSwisstopo = "שווייץ · המפה הטופוגרפית הלאומית"
    override val srcBasemapAt = "אוסטריה · מפת הבסיס הרשמית"
    override val srcKartverket = "נורווגיה · מפה טופוגרפית"
    override val srcIgnPlan = "צרפת · המפה של IGN"
    override val srcIgnOrtho = "צרפת · צילומי אוויר"
    override val srcUsgsTopo = "ארצות הברית · מפה טופוגרפית"
    override val urlMustStartHttps = "הכתובת צריכה להתחיל ב־⁦https://⁩"
    override val urlMustHaveZxy = "הכתובת צריכה לכלול את {z}, {x} ו־{y}"
    override val noTileSourcesFound = "לא נמצאו מקורות מפה. צריך תבניות כתובת עם {z}, {x} ו־{y}."

    override val customSource = "מקור מפה מותאם"
    override val customSourceHelp =
        "כל שירות אריחי רסטר מסוג XYZ, למשל ⁦https://{s}.example.com/{z}/{x}/{y}.png⁩. כדאי לבדוק את תנאי השימוש של הספק."
    override val nameField = "שם"
    override val urlTemplate = "תבנית כתובת (URL)"
    override val maxZoom = "זום מרבי"

    override val closePlanner = "סגירת תכנון המסלול"
    override val plannerTapStart = "יש להקיש על המפה כדי לקבוע נקודת התחלה. הקשה על נקודה ממוספרת מסירה אותה."
    override val plannerTapNext =
        "עכשיו יש להקיש על היעד, ואז על עצירות נוספות. כדי לשנות את המסלול, אפשר ללחוץ לחיצה ארוכה על עצירה או על הקו ולגרור."
    override val routedOffline = "המסלול חושב בטלפון (BRouter)"
    override val routedOnline = "המסלול חושב אונליין ב־brouter.de"
    override val straightWithHeights = "קווים ישרים · גבהים מנתוני הגובה"
    override val straightNoHeights = "קווים ישרים · לגבהים, אפשר להוסיף נתוני גובה במפות אופליין"
    override val reverseDirection = "היפוך כיוון"
    override val clearAllPoints = "ניקוי כל הנקודות"
    override val shareRoute = "שיתוף המסלול"
    override val saveRoute = "שמירת מסלול"
    override fun routeName(date: String) = "מסלול $date"
    override fun drawnRouteName(date: String) = "מסלול משורטט $date"
    override fun savedToRoutes(name: String) = "\"$name\" נשמר במסלולים"
    override fun savedToPlaces(name: String) = "\"$name\" נשמר במקומות"

    override val whatsHereTitle = "מה יש כאן"
    override fun aboutOnWikipedia(title: String) = "על $title בוויקיפדיה"
    override val wikipediaNeedsConnection = "ויקיפדיה דורשת חיבור לאינטרנט."
    override val photosAround = "תמונות מהסביבה"
    override val photosNeedConnection = "התמונות דורשות חיבור לאינטרנט."
    override val lookingForPhotos = "חיפוש תמונות שצולמו בסביבה…"
    override val noPhotosAround = "אין ב־Wikimedia Commons תמונות שצולמו בטווח 10 ק״מ."
    override val aroundOnMap = "בסביבה, על המפה"
    override val noPlacesAround = "אין כאן על המפה מקומות נוספים עם שם, בזום הזה."
    override val onWikipediaNearby = "בוויקיפדיה, בקרבת מקום"
    override val lookingForArticles = "חיפוש ערכים על מקומות בסביבה…"
    override fun noArticlesWithin(km: Int) = "אין ערכים על מקומות בטווח $km ק״מ."

    override fun photoBy(author: String) = "צילום: $author"
    override val fromCommons = "מתוך Wikimedia Commons"
    override val fromCommonsOffline = "מתוך Wikimedia Commons; פרטי הצלם והרישיון דורשים חיבור לאינטרנט."
    override val photoPage = "דף התמונה"
    override val whereTaken = "מקום הצילום"

    override val sendAsLink = "שליחה כקישור"
    override val sendAsLinkHint = "קישורים ל־Google Maps ול־OpenStreetMap; נפתחים אצל כל אחד"
    override val sendAsGpx = "שליחה כנקודת דרך GPX"
    override val sendAsGpxHint = "למכשירי GPS ולאפליקציות כמו OsmAnd או Garmin"
    override val openElsewhere = "פתיחה באפליקציה אחרת"
    override val openElsewhereHint = "אפליקציות ניווט או מפות אחרות"
    override val location = "מיקום"
    override val openWith = "פתיחה באמצעות"
    override val shareRouteLink = "שיתוף קישור למסלול"
    override val noAppForThis = "אין אפליקציה שיכולה לפתוח את זה"
    override fun placesShareText(title: String, n: Int) = when (n) {
        1 -> "$title: מקום אחד (מצורף GPX)"
        2 -> "$title: שני מקומות (מצורף GPX)"
        else -> "$title: $n מקומות (מצורף GPX)"
    }
    override fun openTheRoute(link: String) = "פתיחת המסלול: $link"
    override fun routeStartsAt(link: String) = "התחלה: $link"
    override val gpxAttached = "(מצורף קובץ GPX, אם האפליקציה קיבלה אותו)"

    override val noLocationShared = "לא נמצא מיקום במה ששותף"
    override val openingSharedLink = "פתיחת הקישור ששותף…"
    override val couldntOpenLink = "לא ניתן לפתוח את הקישור (אין חיבור לאינטרנט?)"
    override val linkWithoutCoordinates = "בקישור יש כתובת אבל אין קואורדינטות"
    override fun importingMap(name: String) = "ייבוא המפה $name…"
    override fun opening(name: String) = "פתיחת $name…"
    override fun offlineMapAdded(title: String) = "מפת האופליין $title נוספה"
    override fun couldntAddMap(reason: String?) = "לא ניתן להוסיף את המפה: $reason"
    override val cantOpenFile = "לא ניתן לפתוח את הקובץ"
    override val imported = "מיובא"
    override fun couldntReadGpx(reason: String?) = "לא ניתן לקרוא את קובץ ה־GPX: $reason"
    override val notGpx = "הקובץ הזה אינו קובץ GPX"
    override val noTrackInGpx = "אין בקובץ ה־GPX הזה מסלול או נקודת דרך"
    override val rasterMapsOnIphoneLater = "מפות רסטר: יגיעו לאייפון בגרסה מאוחרת יותר"

    override fun gpsAccuracy(m: Int) = "GPS ±$m מ׳…"
    override val waitingForGps = "בהמתנה ל־GPS…"

    override val close3d = "סגירת תצוגת התלת־ממד"
    override val faceNorth = "פנייה צפונה"
    override val view3dSettings = "תבליט ומבנים"
    override val backToMap = "חזרה למפה"
    override fun relief(times: String) = "תבליט ⁦×${times}⁩"
    override val buildingsCloseUp = "מבנים (מקרוב)"
    override val gestures3d = "אצבע אחת: הזזה · צביטה: זום · 2 אצבעות: סיבוב והטיה · הקשה כפולה: התקרבות"
    override fun loadingTiles(n: Int) = when (n) {
        1 -> "טעינת אריח אחד…"
        2 -> "טעינת שני אריחים…"
        else -> "טעינת $n אריחים…"
    }
    override val loadingRelief = "טעינת התבליט…"
    override val the3dView = "תצוגת התלת־ממד"
    override fun gettingRelief(done: Int, total: Int) = "הורדת נתוני הגובה לתבליט ($done מתוך $total)…"
}
