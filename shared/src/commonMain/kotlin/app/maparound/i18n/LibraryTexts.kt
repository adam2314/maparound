package app.maparound.i18n

/** The library: routes, tracks and places, their lists, details, editing, import and export, and profiles. */
open class LibraryTexts {
    // ---- the Library tab ----
    open val routes = "Routes"
    open val places = "Places"

    // ---- routes & tracks: the list ----
    open val routesAndTracks = "Routes & tracks"
    open val importGpxFile = "Import a GPX file"
    open fun importedTrack(name: String) = "Imported $name"

    /** A GPX import that failed (routes or places), with the reason. */
    open fun importFailed(reason: String?) = "Import failed: $reason"
    open val recordingYourTrack = "Recording your track"
    open val recordingKeepsGoing = "Keeps recording with the screen off. Stop to save it here."
    open val record = "Record"
    open val planARoute = "Plan a route"

    /** Under "Plan a route"; "Line" is the route planner's measuring mode. */
    open val planHint = "Plan: tap points on the map for a hiking, bike or car route, or choose Line to measure. " +
        "Routes are calculated on the phone where routing data is downloaded, otherwise online."
    open val nothingSavedYet = "Nothing saved yet"
    open fun savedCount(n: Int) = "Saved ($n)"
    open val nothingSavedHint = "Saved routes and recorded tracks appear here. You can also import GPX files from other apps."
    open val hideOnMap = "Hide on map"
    open val shareGpxAndLinks = "Share (GPX + links)"
    open val shareLinkOnly = "Share link only"

    // ---- routes & tracks: details and deleting ----
    /** Heights in metres. */
    open fun highestLowest(highest: Int, lowest: Int?) = "Highest $highest m · lowest $lowest m"
    open fun confirmDeleteTrack(name: String) = "Delete $name?"
    open val gpxFileRemoved = "The GPX file is removed from this phone."

    // ---- kinds of routes and tracks ----
    open val kindPlanned = "Planned route"
    open val kindDrawn = "Drawn route"
    open val kindRecorded = "Recorded track"
    open val kindImported = "Imported"

    // ---- names the app gives routes and tracks ----
    /** A recorded track's name: "Track 2026-11-11 14:05". */
    open fun recordedTrackName(dateTime: String) = "Track $dateTime"

    /** A track kept from a recording that was cut off (app killed). */
    open fun recoveredTrackName(name: String) = "$name (recovered)"

    /** An imported track's name when its file gives none. */
    open val importedTrackName = "Imported"

    /** Why a GPX file can't be imported as a track (shown after "Import failed: "). */
    open val noTrackInGpx = "No track or route in this GPX file"

    // ---- the recording notification (Android) ----
    open val recordingChannel = "Track recording"
    open val recordingChannelDescription = "Shown while MapAround records your track"
    open val recordingTrack = "Recording track"
    open val waitingForGps = "Waiting for GPS…"
    open val stopAndSave = "Stop & save"

    // ---- places: add / edit ----
    open val savePlace = "Save place"
    open val editPlace = "Edit place"
    open val placeName = "Name"
    open val placeNote = "Note"

    // ---- places: the card on the map ----
    /** "1.2 km NE": how far a place is from the user, and which way. */
    open fun distanceDirection(distance: String, direction: String) = "$distance $direction"
    open val routeHere = "Route here"
    open val sharePlace = "Share place"
    open val whatCanBeSeenFromHere = "What can be seen from here"
    open val deletePlace = "Delete place"
    open fun confirmDeletePlace(name: String) = "Delete “$name”?"

    // ---- places: the list ----
    open val noNewPlacesInFile = "No new places in that file"
    open fun importedPlaces(n: Int) = if (n == 1) "Imported 1 place" else "Imported $n places"
    open val importPlacesFromGpx = "Import places from a GPX file"

    /** The title of the places shared from the list (GPX name, share subject). */
    open val sharedPlacesTitle = "MapAround places"
    open val shareListedPlaces = "Share the listed places as GPX"
    open val searchNamesAndNotes = "Search names and notes"

    /** Clears the search field. */
    open val clear = "Clear"

    /** The filter chip for every category, with the number of places. */
    open fun allCount(n: Int) = "All $n"
    open val noSavedPlaces = "No saved places yet"
    open val noSavedPlacesHint =
        "Long-press the map and tap the bookmark on the pin to save a place, or import waypoints from a GPX file."
    open fun moreFor(name: String) = "More for $name"

    // ---- places: sorting ----
    open val sortNearest = "Nearest"
    open val sortName = "Name"
    open val sortNewest = "Newest"

    // ---- places: categories ----
    open val categoryFavorite = "Favourite"
    open val categoryCamp = "Camp"
    open val categoryWater = "Water"
    open val categoryView = "Viewpoint"
    open val categoryParking = "Parking"
    open val categoryFuel = "Fuel"
    open val categoryFood = "Food"
    open val categoryDanger = "Danger"
    open val categoryOther = "Other"
}

/** Hebrew. */
object LibraryTextsHe : LibraryTexts() {
    override val routes = "מסלולים"
    override val places = "מקומות"

    override val routesAndTracks = "מסלולים והקלטות"
    override val importGpxFile = "ייבוא קובץ GPX"
    override fun importedTrack(name: String) = "יובא: $name"
    override fun importFailed(reason: String?) = "הייבוא נכשל: $reason"
    override val recordingYourTrack = "המסלול שלך מוקלט"
    override val recordingKeepsGoing = "ההקלטה נמשכת גם כשהמסך כבוי. בעצירה היא תישמר כאן."
    override val record = "הקלטה"
    override val planARoute = "תכנון מסלול"
    override val planHint = "תכנון: אפשר להקיש על נקודות במפה למסלול הליכה, אופניים או רכב, או לבחור \"קו\" למדידה. " +
        "המסלול מחושב בטלפון כשנתוני הניווט של האזור הורדו, ואחרת אונליין."
    override val nothingSavedYet = "עדיין לא נשמר כלום"
    override fun savedCount(n: Int) = "שמורים ($n)"
    override val nothingSavedHint = "מסלולים שמורים ומסלולים מוקלטים יופיעו כאן. אפשר גם לייבא קובצי GPX מאפליקציות אחרות."
    override val hideOnMap = "הסתרה מהמפה"
    override val shareGpxAndLinks = "שיתוף (GPX + קישורים)"
    override val shareLinkOnly = "שיתוף הקישור בלבד"

    override fun highestLowest(highest: Int, lowest: Int?) = "גובה מרבי $highest מ׳ · מזערי $lowest מ׳"
    override fun confirmDeleteTrack(name: String) = "למחוק את $name?"
    override val gpxFileRemoved = "קובץ ה־GPX יימחק מהטלפון הזה."

    override val kindPlanned = "מסלול מתוכנן"
    override val kindDrawn = "מסלול משורטט"
    override val kindRecorded = "מסלול מוקלט"
    override val kindImported = "מסלול מיובא"

    override fun recordedTrackName(dateTime: String) = "מסלול מוקלט $dateTime"
    override fun recoveredTrackName(name: String) = "$name (שוחזר)"
    override val importedTrackName = "מסלול מיובא"
    override val noTrackInGpx = "אין מסלול בקובץ ה־GPX הזה"

    override val recordingChannel = "הקלטת מסלול"
    override val recordingChannelDescription = "מוצגת בזמן הקלטת מסלול ב־MapAround"
    override val recordingTrack = "הקלטת מסלול פעילה"
    override val waitingForGps = "המתנה ל־GPS…"
    override val stopAndSave = "עצירה ושמירה"

    override val savePlace = "שמירת מקום"
    override val editPlace = "עריכת מקום"
    override val placeName = "שם"
    override val placeNote = "הערה"

    override fun distanceDirection(distance: String, direction: String) = "$distance ל$direction"
    override val routeHere = "מסלול לכאן"
    override val sharePlace = "שיתוף המקום"
    override val whatCanBeSeenFromHere = "מה רואים מכאן"
    override val deletePlace = "מחיקת המקום"
    override fun confirmDeletePlace(name: String) = "למחוק את \"$name\"?"

    override val noNewPlacesInFile = "אין בקובץ הזה מקומות חדשים"
    override fun importedPlaces(n: Int) = if (n == 1) "יובא מקום אחד" else "יובאו $n מקומות"
    override val importPlacesFromGpx = "ייבוא מקומות מקובץ GPX"
    override val sharedPlacesTitle = "מקומות מ־MapAround"
    override val shareListedPlaces = "שיתוף המקומות שברשימה כקובץ GPX"
    override val searchNamesAndNotes = "חיפוש בשמות ובהערות"
    override val clear = "ניקוי"
    override fun allCount(n: Int) = "הכול $n"
    override val noSavedPlaces = "עדיין אין מקומות שמורים"
    override val noSavedPlacesHint =
        "כדי לשמור מקום: לחיצה ארוכה על המפה ואז על הסימנייה שבסיכה. אפשר גם לייבא נקודות דרך מקובץ GPX."
    override fun moreFor(name: String) = "עוד עבור $name"

    override val sortNearest = "מרחק"
    override val sortName = "שם"
    override val sortNewest = "תאריך"

    override val categoryFavorite = "מועדף"
    override val categoryCamp = "חניון לילה"
    override val categoryWater = "מים"
    override val categoryView = "תצפית"
    override val categoryParking = "חניה"
    override val categoryFuel = "דלק"
    override val categoryFood = "אוכל"
    override val categoryDanger = "סכנה"
    override val categoryOther = "אחר"
}
