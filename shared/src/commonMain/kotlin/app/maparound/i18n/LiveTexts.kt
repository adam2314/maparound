package app.maparound.i18n

/** The data view's live layers (the web app): weather, rain radar, street photos, buses. */
open class LiveTexts {
    // ---- the explorer ----
    open val weather = "Weather"
    open val rainRadar = "Rain radar"
    open val temperatureNow = "Temperature now"
    open val windNow = "Wind now"
    open val lookAround = "Look around"
    open val streetPhotos = "Street photos"
    open val buses = "Buses now (Israel)"
    /** Words that find them (besides their names). */
    open val weatherKeywords = "weather forecast"
    open val radarKeywords = "rain radar precipitation showers storm clouds"
    open val temperatureKeywords = "temperature heat hot cold degrees"
    open val windKeywords = "wind gusts storm breeze"
    open val photosKeywords = "photos pictures street view streetview panorama 360 panoramax mapillary"
    open val busesKeywords = "bus buses public transport transit live real time where is my bus line"
    /** Where each layer's data comes from, under its name in the explorer. */
    open val radarSource = "RainViewer · the last 10 minutes"
    open val weatherSource = "Open-Meteo · tap a point for its forecast"
    open val photosSource = "Panoramax · open street-level photos"
    open val busesSource = "Open Bus · live, a minute or two behind"

    open val windAnimation = "Wind, moving"
    open val weatherMaps = "Weather maps"
    open val weatherMapsSource = "NOAA's forecast · the next 48 hours"
    open val weatherMapsKeywords = "weather maps forecast gfs noaa animation particles flow"
    open val cloudsKeywords = "clouds cloud cover overcast sunny sky"
    open fun weatherMapTitle(f: app.maparound.stats.WeatherMapField) = when (f) {
        app.maparound.stats.WeatherMapField.TEMPERATURE -> "Temperature map"
        app.maparound.stats.WeatherMapField.CLOUDS -> "Clouds"
        app.maparound.stats.WeatherMapField.PRECIP -> "Rain and snow (forecast)"
    }
    open val gettingWeatherMaps = "Getting the forecast…"
    open fun weatherMapsNote(run: String) = "NOAA's GFS model, run at $run UTC. Tap the map for the forecast there."
    open val now = "now"
    /** How far the weather map's hour is from now: "+6 h", "−3 h". */
    open fun hoursFromNow(h: Int) = ltr(if (h > 0) "+$h h" else "−${-h} h")
    open val weatherMapsCredits = "weather maps: NOAA GFS"
    open val cameras = "Cameras"
    open val camerasLayer = "Cameras on the map"
    open val camerasSource = "OpenStreetMap · speed, security and webcams"
    open val camerasKeywords = "camera cameras speed camera enforcement radar traffic cctv surveillance security webcam"
    open val webcams = "Live webcams"
    open val webcamsSource = "Windy · their pictures now"
    open val webcamsKeywords = "webcam webcams live view camera windy picture now"
    open val comingSoon = "Coming soon"
    open val cameraKinds = listOf("Speed camera", "Security camera", "Webcam")

    // ---- notes under the legend ----
    open fun radarAt(time: String) = "Rain radar at $time, a new picture every 10 minutes."
    open val radarScale = "Light blue: light rain · dark blue: heavy · yellow to red: very heavy. Sharp to zoom 8, enlarged closer in."
    open val gettingRadar = "Getting the rain radar…"
    open fun weatherAt(time: String) = "At $time. Tap a point for its forecast."
    open val gettingWeather = "Getting the weather…"
    open val photosNote = "Orange lines: where people took photos along the way. Zoom in and tap a dot to see one."
    open val photosZoomIn = "Zoom in for the photos themselves."
    open fun busesAt(count: Int, time: String) = "$count buses, as of $time (a minute or two behind). Tap one for its line."
    open val busesZoomIn = "Zoom in on Israel to see its buses."
    open val noBuses = "No buses here right now."
    open val gettingBuses = "Getting the buses…"
    open fun camerasHere(count: Int) = "$count cameras here, as volunteers mapped them on OpenStreetMap (some are missing). Tap one for more."
    open val camerasZoomIn = "Zoom in to see the cameras."
    open val noCameras = "No cameras mapped here."
    open val gettingCameras = "Getting the cameras…"
    open val camerasBusy = "OpenStreetMap's servers are busy (or you're offline): trying again shortly."
    open val couldntGet = "Couldn't get it. Online?"
    open val loading = "Loading…"

    // ---- a tapped weather point ----
    open val weatherHere = "Weather here"
    open fun atHeight(height: String) = "at $height"
    open fun feelsLike(t: String) = "feels like $t"
    open fun wind(dir: String, speed: String) = "Wind $dir $speed"
    open fun gusts(speed: String) = "gusts $speed"
    open fun humidity(pct: Int) = "Humidity ${ltr("$pct%")}"
    open fun clouds(pct: Int) = "Clouds ${ltr("$pct%")}"
    open val nextHours = "Next hours (local time there)"
    open val nextDays = "Next 7 days"
    open val today = "Today"
    open fun sun(rise: String, set: String) = "Sunrise $rise · sunset $set"
    open fun mm(v: String) = "$v mm"
    open val weatherCredit = "Weather data by Open-Meteo.com (CC BY 4.0)"
    open val weatherKinds = listOf(
        "Clear", "Partly cloudy", "Cloudy", "Fog", "Drizzle", "Rain", "Freezing rain", "Snow", "Showers",
        "Snow showers", "Thunderstorm",
    )
    /** Monday first. */
    open val weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    // ---- a tapped photo ----
    open val panorama = "All-round photo (360°)"
    open fun takenOn(date: String) = "Taken $date"
    open val openInPanoramax = "Open in Panoramax"
    open val streetView = "Street View here"
    open val photoCredit = "Photo: Panoramax"
    open val bigger = "Bigger"

    // ---- a tapped bus ----
    open fun line(number: String) = "Line $number"
    open fun to(place: String) = "To $place"
    open fun from(place: String) = "From $place"
    open fun positionAt(time: String, ago: String) = "Here at $time ($ago)"
    open fun vehicle(ref: String) = "Bus no. ${ltr(ref)}"
    open val unknownLine = "This line isn't in today's timetable."
    open val stopped = "Stopped"
    open val busesCredit = "Open Bus (Public Knowledge Workshop), from the Ministry of Transport's real-time data"

    // ---- a tapped camera ----
    open fun speedLimit(v: String) = "Speed limit: $v"
    open fun facing(dir: String) = "Facing $dir"
    open fun operatedBy(op: String) = "Operated by $op"
    open fun watches(what: String) = when (what) {
        "public" -> "Watching a public place"
        "outdoor" -> "Outdoors"
        "indoor" -> "Indoors"
        "traffic" -> "Watching traffic"
        else -> null
    }
    open val readsPlates = "Reads number plates (ALPR)"
    open val openWebcam = "Open the webcam"
    open val openInOsm = "Open in OpenStreetMap"
    open val camerasCredit = "Mapped by OpenStreetMap contributors (ODbL)"

    // ---- credits on the map ----
    open val radarCredits = "radar: RainViewer"
    open val weatherCredits = "weather: Open-Meteo.com"
    open val photosCredits = "photos: Panoramax contributors"
    open val busesCredits = "buses: Open Bus (Hasadna), Ministry of Transport"
    open val camerasCredits = "cameras: © OpenStreetMap contributors"

    // ---- Settings → Sources ----
    open val radarSourceNote = "RainViewer's radar pictures of the past two hours"
}

object LiveTextsHe : LiveTexts() {
    override val weather = "מזג אוויר"
    override val rainRadar = "מכ״ם גשם"
    override val temperatureNow = "טמפרטורה עכשיו"
    override val windNow = "רוח עכשיו"
    override val lookAround = "להסתכל מסביב"
    override val streetPhotos = "תמונות רחוב"
    override val buses = "אוטובוסים עכשיו (ישראל)"
    override val weatherKeywords = "מזג אוויר תחזית weather forecast"
    override val radarKeywords = "גשם מכ״ם מכם משקעים ממטרים סערה עננים rain radar"
    override val temperatureKeywords = "טמפרטורה חום חם קור קר מעלות temperature"
    override val windKeywords = "רוח רוחות משבים סערה wind"
    override val photosKeywords = "תמונות צילומים סטריט וויו פנורמה 360 panoramax street view photos"
    override val busesKeywords = "אוטובוס אוטובוסים תחבורה ציבורית קו קווים זמן אמת איפה האוטובוס bus"
    override val windAnimation = "רוח, בתנועה"
    override val weatherMaps = "מפות מזג אוויר"
    override val weatherMapsSource = "התחזית של ${ltr("NOAA")} · 48 השעות הקרובות"
    override val weatherMapsKeywords = "מפות מזג אוויר מפה תחזית אנימציה זרימה gfs noaa maps forecast"
    override val cloudsKeywords = "עננים עננות מעונן שמש שמיים clouds"
    override fun weatherMapTitle(f: app.maparound.stats.WeatherMapField) = when (f) {
        app.maparound.stats.WeatherMapField.TEMPERATURE -> "מפת טמפרטורה"
        app.maparound.stats.WeatherMapField.CLOUDS -> "עננים"
        app.maparound.stats.WeatherMapField.PRECIP -> "גשם ושלג (תחזית)"
    }
    override val gettingWeatherMaps = "מוריד את התחזית…"
    override fun weatherMapsNote(run: String) = "מודל GFS של NOAA, ריצה ב־${ltr(run)} UTC. הקשה על המפה מציגה את התחזית שם."
    override val now = "עכשיו"
    override fun hoursFromNow(h: Int) = if (h > 0) "בעוד $h שע׳" else "לפני ${-h} שע׳"
    override val weatherMapsCredits = "מפות מזג אוויר: NOAA GFS"
    override val cameras = "מצלמות"
    override val camerasLayer = "מצלמות על המפה"
    override val camerasSource = "OpenStreetMap · מהירות, אבטחה ומצלמות רשת"
    override val camerasKeywords = "מצלמה מצלמות מצלמת מהירות אכיפה מכמונת רמזור אבטחה מעקב מצלמת רשת camera speed cctv webcam"
    override val webcams = "מצלמות רשת חיות"
    override val webcamsSource = "Windy · מה רואים שם עכשיו"
    override val webcamsKeywords = "מצלמת רשת מצלמות רשת שידור חי תמונה עכשיו webcam windy"
    override val comingSoon = "בקרוב"
    override val cameraKinds = listOf("מצלמת מהירות", "מצלמת אבטחה", "מצלמת רשת")
    override val radarSource = "RainViewer · 10 הדקות האחרונות"
    override val weatherSource = "Open-Meteo · הקשה על נקודה מציגה תחזית"
    override val photosSource = "Panoramax · תמונות רחוב פתוחות"
    override val busesSource = "Open Bus · בזמן אמת, באיחור של דקה־שתיים"

    override fun radarAt(time: String) = "מכ״ם הגשם ב־${ltr(time)}, תמונה חדשה כל 10 דקות."
    override val radarScale = "תכלת: גשם קל · כחול כהה: חזק · צהוב עד אדום: חזק מאוד. חד עד זום 8, מוגדל מקרוב יותר."
    override val gettingRadar = "מוריד את מכ״ם הגשם…"
    override fun weatherAt(time: String) = "ב־${ltr(time)}. הקשה על נקודה מציגה את התחזית שם."
    override val gettingWeather = "מוריד את מזג האוויר…"
    override val photosNote = "קווים כתומים: איפה צילמו לאורך הדרך. יש להתקרב ולהקיש על נקודה כדי לראות תמונה."
    override val photosZoomIn = "יש להתקרב כדי לראות את התמונות עצמן."
    override fun busesAt(count: Int, time: String) = "${ltr("$count")} אוטובוסים, נכון ל־${ltr(time)} (באיחור של דקה־שתיים). הקשה על אוטובוס מציגה את הקו."
    override val busesZoomIn = "יש להתקרב לישראל כדי לראות את האוטובוסים."
    override val noBuses = "אין כאן אוטובוסים כרגע."
    override val gettingBuses = "מוריד את האוטובוסים…"
    override fun camerasHere(count: Int) = "${ltr("$count")} מצלמות כאן, כפי שמתנדבים מיפו אותן ב־OpenStreetMap (חלקן חסרות). הקשה על מצלמה מציגה פרטים."
    override val camerasZoomIn = "יש להתקרב כדי לראות את המצלמות."
    override val noCameras = "לא מופו כאן מצלמות."
    override val gettingCameras = "מוריד את המצלמות…"
    override val camerasBusy = "השרתים של OpenStreetMap עמוסים (או שאין חיבור): מנסה שוב בעוד רגע."
    override val couldntGet = "לא ניתן להוריד. יש חיבור?"
    override val loading = "טוען…"

    override val weatherHere = "מזג האוויר כאן"
    override fun atHeight(height: String) = "בגובה ${ltr(height)}"
    override fun feelsLike(t: String) = "מורגש כמו ${ltr(t)}"
    override fun wind(dir: String, speed: String) = "רוח $dir ${ltr(speed)}"
    override fun gusts(speed: String) = "משבים ${ltr(speed)}"
    override fun humidity(pct: Int) = "לחות ${ltr("$pct%")}"
    override fun clouds(pct: Int) = "עננות ${ltr("$pct%")}"
    override val nextHours = "השעות הקרובות (לפי השעון שם)"
    override val nextDays = "7 הימים הקרובים"
    override val today = "היום"
    override fun sun(rise: String, set: String) = "זריחה ${ltr(rise)} · שקיעה ${ltr(set)}"
    override fun mm(v: String) = ltr("$v מ״מ")
    override val weatherCredit = "נתוני מזג אוויר: Open-Meteo.com (CC BY 4.0)"
    override val weatherKinds = listOf(
        "בהיר", "מעונן חלקית", "מעונן", "ערפל", "טפטוף", "גשם", "גשם קופא", "שלג", "ממטרים", "ממטרי שלג", "סופת רעמים",
    )
    override val weekdays = listOf("ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "שבת", "א׳")

    override val panorama = "תמונה מסביב (360°)"
    override fun takenOn(date: String) = "צולמה ב־$date"
    override val openInPanoramax = "פתיחה ב־Panoramax"
    override val streetView = "Street View כאן"
    override val photoCredit = "תמונה: Panoramax"
    override val bigger = "הגדלה"

    override fun line(number: String) = "קו ${ltr(number)}"
    override fun to(place: String) = "אל $place"
    override fun from(place: String) = "מ־$place"
    override fun positionAt(time: String, ago: String) = "היה כאן ב־${ltr(time)} ($ago)"
    override fun vehicle(ref: String) = "אוטובוס מס׳ ${ltr(ref)}"
    override val unknownLine = "הקו הזה לא נמצא בלוח הזמנים של היום."
    override val stopped = "עומד"
    override val busesCredit = "Open Bus (הסדנא לידע ציבורי), מנתוני הזמן האמת של משרד התחבורה"

    override fun speedLimit(v: String) = "מהירות מותרת: ${ltr(v)}"
    override fun facing(dir: String) = "מופנית לכיוון $dir"
    override fun operatedBy(op: String) = "מופעלת על ידי $op"
    override fun watches(what: String) = when (what) {
        "public" -> "צופה במרחב ציבורי"
        "outdoor" -> "בחוץ"
        "indoor" -> "בפנים"
        "traffic" -> "צופה בתנועה"
        else -> null
    }
    override val readsPlates = "קוראת לוחיות רישוי (ALPR)"
    override val openWebcam = "פתיחת מצלמת הרשת"
    override val openInOsm = "פתיחה ב־OpenStreetMap"
    override val camerasCredit = "מופו על ידי תורמי OpenStreetMap (ODbL)"

    override val radarCredits = "מכ״ם: RainViewer"
    override val weatherCredits = "מזג אוויר: Open-Meteo.com"
    override val photosCredits = "תמונות: תורמי Panoramax"
    override val busesCredits = "אוטובוסים: Open Bus (הסדנא), משרד התחבורה"
    override val camerasCredits = "מצלמות: © תורמי OpenStreetMap"

    override val radarSourceNote = "תמונות המכ״ם של RainViewer משעתיים אחרונות"
}
