package app.maparound.i18n

/** The sensors: compass, barometer, speed, motion, satellites. */
open class SensorTexts {
    // ---- the tabs ----
    open val compass = "Compass"
    open val speed = "Speed"
    open val motion = "Motion"
    open val barometer = "Barometer"
    open val satellites = "Satellites"

    // ---- shared by the sensor screens ----
    open val allowLocation = "Allow location"
    open val reset = "Reset"
    open val waitingForData = "Waiting for data…"
    open val now = "now"

    /** The compass points on the dials, clockwise from north: N, E, S, W. */
    open val dialCardinals = listOf("N", "E", "S", "W")

    /** A chart's time span: "last 10 s". */
    open fun lastSeconds(n: Int) = "last $n s"
    open fun lastMinutes(n: Int) = "last $n min"

    /** [hours] with one decimal: "last 1.5 h". */
    open fun lastHours(hours: String) = "last $hours h"
    open val unitMps2 = "m/s²"
    open val unitDegPerS = "°/s"
    open val unitRadPerS = "rad/s"

    /** A turn rate: "12°/s". */
    open fun degPerS(value: String) = "$value°/s"

    // ---- compass ----
    open val noCompass = "No compass sensor"
    open val noCompassBody = "This device has no magnetometer, so a compass isn't available."

    /** Under the heading: its compass point and the north it's measured from ("NE · true"). */
    open fun headingFrom(cardinal: String, fromTrueNorth: Boolean) = cardinal + if (fromTrueNorth) " · true" else " · magnetic"
    open val waitingForSensor = "Waiting for sensor…"
    open val magneticNorth = "Magnetic"
    open val trueNorth = "True north"
    open val trueNorthNeedsLocation = "True north needs your location to compute magnetic declination."
    open val needsCalibration = "Compass needs calibration: wave your phone in a figure‑8 a few times, away from metal and magnets."
    open val sensorAccuracy = "Sensor accuracy"
    open val declination = "Declination"

    /** Magnetic declination east or west of true north: "4.5° E". */
    open fun declinationValue(degrees: String, east: Boolean) = "$degrees° ${if (east) "E" else "W"}"
    open val needsLocation = "needs location"
    open val holdFlat = "Hold the phone flat for the most accurate reading."
    open val accuracyHigh = "High"
    open val accuracyMedium = "Medium"
    open val accuracyLow = "Low"
    open val accuracyUnreliable = "Unreliable"
    open val accuracyUnknown = "Unknown"

    // ---- barometer ----
    open val noBarometer = "No barometer"
    open val noBarometerBody = "This device has no air‑pressure sensor. Many mid‑range and flagship phones have one; budget models often don't."
    open val airPressure = "Air pressure"

    /** The pressure in other units: "29.92 inHg · 760 mmHg". */
    open fun otherPressureUnits(inHg: String, mmHg: String) = "$inHg inHg · $mmHg mmHg"
    open val barometricAltitude = "Barometric altitude"

    /** Under the altitude: the sea-level pressure it's computed from, set by the user or the standard one. */
    open fun seaLevelReference(hPa: String, calibrated: Boolean) =
        "Sea‑level reference $hPa hPa " + if (calibrated) "(calibrated)" else "(standard atmosphere)"
    open val calibrate = "Calibrate"
    open val uncalibratedWarning = "Uncalibrated altitude can be off by 100 m or more as the weather changes."
    open val trend = "Trend"
    open val collectingTrend = "Collecting data… keep the app open for 10+ minutes to see a trend."

    /** [hPa] signed ("+1.2"), [label] one of [fallingFast]…[risingFast]. */
    open fun trendPerHour(hPa: String, label: String) = "$hPa hPa per hour · $label"
    open val fallingFast = "falling fast"
    open val falling = "falling"
    open val steady = "steady"
    open val rising = "rising"
    open val risingFast = "rising fast"
    open val trendHint = "Falling more than ~1 hPa/h often means worsening weather; rising pressure usually means clearing."

    // ---- calibrating the altimeter ----
    open val calibrateAltimeter = "Calibrate altimeter"
    open val calibrateIntro =
        "Enter the elevation where you are (from a signpost, summit or map), or today's sea‑level pressure (QNH) from a weather report."
    open val knownElevation = "Known elevation (m)"
    open fun useGpsAltitude(meters: Int) = "Use GPS altitude ($meters m)"
    open val allowLocationForGpsAltitude = "Allow location to use GPS altitude"
    open val waitingForGpsAltitude = "Waiting for a GPS altitude…"
    open val orSeaLevelPressure = "or sea‑level pressure (hPa)"
    open val apply = "Apply"

    // ---- speed ----
    open val locationNeeded = "Location needed"
    open val speedNeedsLocation = "Ground speed comes from GPS. Allow location to see it."
    open val groundSpeedGps = "Ground speed (GPS)"
    open val waitingForGps = "Waiting for GPS… go outside with a view of the sky."
    open fun gpsFixLost(seconds: Long) = "GPS fix lost $seconds s ago"

    /** Direction of travel: "heading 45° NE". */
    open fun heading(degrees: Int, cardinal: String) = "heading $degrees° $cardinal"

    /** "pace 12:30 /km", [value] from [perKm]. */
    open fun pace(value: String) = "pace $value"

    /** Minutes and seconds per kilometre: "12:30 /km". */
    open fun perKm(minSec: String) = "$minSec /km"
    open val stopped = "stopped"
    open val trip = "Trip"
    open val distance = "Distance"
    open val maxSpeed = "Max speed"
    open val averageWhileMoving = "Average while moving"
    open val movingTime = "Moving time"
    open val totalTime = "Total time"
    open val tripHint =
        "Counts while MapAround is open. Below 1.8 km/h counts as stopped. To keep a full log, record a track on the map."
    open val speedLast10Minutes = "Speed, last 10 minutes"

    // ---- motion ----
    open val noMotionSensors = "No motion sensors"
    open val noMotionSensorsBody = "This device reports no accelerometer or gyroscope."
    open val noGyroscope = "No gyroscope"
    open val noGyroscopeBody = "This device has no gyroscope; turn rates aren't available."
    open val tiltTitle = "Tilt (inclinometer)"

    /** The vehicle seen from the side: nose up or down. */
    open val pitch = "Pitch"

    /** The vehicle seen from behind: leaning to a side. */
    open val roll = "Roll"
    open val noseUp = "nose up"
    open val noseDown = "nose down"
    open val level = "level"
    open val rightSideDown = "right side down"
    open val leftSideDown = "left side down"
    open val setAsLevel = "Set as level"
    open fun tiltHint(calibrated: Boolean) =
        (if (calibrated) "Zeroed to your holder. " else "Measured against the phone lying flat or standing upright. ") +
            "In a vehicle: mount the phone, stop on level ground and tap Set as level. " +
            "Colours change at 15° and 25°; check your vehicle's own limits."
    open val accelerometer = "Accelerometer"

    /** Next to the acceleration in g, gravity included. */
    open val totalWithGravity = "total, with gravity"

    /** Acceleration without gravity now and its peak, in g: "Movement 0.12 g · peak 0.80 g". */
    open fun movement(current: String, peak: String) = "Movement $current g · peak $peak g"

    /** Acceleration along the vehicle, in g. */
    open fun forwardG(g: String, speedingUp: Boolean) = "${if (speedingUp) "Speeding up" else "Braking"} $g g"

    /** Acceleration across the vehicle, in g; the arrow points to the physical side (not mirrored). */
    open fun sidewaysG(g: String, right: Boolean) = "Sideways $g g ${if (right) "→" else "←"}"
    open val resetPeak = "Reset peak"

    /** The unit of the acceleration chart. */
    open val accelGravityRemoved = "m/s², gravity removed"
    open val gyroscope = "Gyroscope"
    open val turningLeft = "turning left"
    open val turningRight = "turning right"
    open val notTurning = "not turning"
    open fun totalRotation(value: String) = "Total rotation $value°/s"

    /** A sensor's measuring range: "range ±78 m/s²". */
    open fun range(value: String, unit: String) = "range ±$value $unit"
    open fun upToHz(hz: Int) = "up to $hz Hz"

    // ---- satellites ----
    open val locationPermissionNeeded = "Location permission needed"
    open val gnssNeedsLocation =
        "Satellite reception data comes from the phone's GNSS (GPS) receiver, which requires location access."
    open val locationOff = "Location is turned off"
    open val turnOnLocation = "Turn on location to start the satellite receiver."
    open val openSettings = "Open settings"
    open val skyView = "Sky view"
    open val skyViewLegend = "Filled = used in fix · rings at 0°, 30°, 60° elevation"
    open val signalStrength = "Signal strength (C/N₀, dB‑Hz)"
    open val searching = "Searching… go outdoors with a clear view of the sky. A cold start can take a minute or two."
    open val startingReceiver = "Starting the GNSS receiver…"
    open val noFix = "No fix"
    open val fix3d = "3D fix"
    open val fix2d = "2D fix"

    /** Under "8 / 20": satellites used in the fix / in view. */
    open val inUseInView = "in use / in view"
    open val position = "Position"
    open val altitude = "Altitude"

    /** "312 m (MSL)": above mean sea level, or the raw GPS (ellipsoid) altitude. */
    open fun altitudeValue(meters: Int, msl: Boolean) = "$meters m" + if (msl) " (MSL)" else " (GPS)"
    open val accuracy = "Accuracy"

    /** "±4 m · ±6 m vert.": horizontal and, if known, vertical accuracy. */
    open fun accuracyValue(horizontal: Int, vertical: Int?) =
        "±$horizontal m" + (vertical?.let { " · ±$it m vert." } ?: "")
    open val course = "Course"
    open val lastFix = "Last fix"
    open fun secondsAgo(n: Int) = "$n s ago"
    open val timeToFirstFix = "Time to first fix"
    open val waitingForFirstFix = "Waiting for the first fix…"
    open val usedInFix = "Used in fix"

    /** A satellite's place in the sky: "el 45° · az 270°". */
    open fun elevationAzimuth(elevation: Int, azimuth: Int) = "el $elevation° · az $azimuth°"

    /** A satellite system other than GPS, GLONASS, Galileo, BeiDou, QZSS, SBAS and NavIC. */
    open val otherConstellation = "Other"
}

/** Hebrew. */
object SensorTextsHe : SensorTexts() {
    override val compass = "מצפן"
    override val speed = "מהירות"
    override val motion = "תנועה"
    override val barometer = "ברומטר"
    override val satellites = "לוויינים"

    override val allowLocation = "מתן גישה למיקום"
    override val reset = "איפוס"
    override val waitingForData = "בהמתנה לנתונים…"
    override val now = "עכשיו"
    override val dialCardinals = listOf("צ", "מז", "ד", "מע")
    override fun lastSeconds(n: Int) = "$n שנ׳ אחרונות"
    override fun lastMinutes(n: Int) = "$n דק׳ אחרונות"
    override fun lastHours(hours: String) = "$hours ש׳ אחרונות"
    override val unitMps2 = "מ׳/שנ׳²"
    override val unitDegPerS = "°/שנ׳"
    override val unitRadPerS = "רד׳/שנ׳"
    override fun degPerS(value: String) = "${ltr("$value°")}/שנ׳"

    override val noCompass = "אין חיישן מצפן"
    override val noCompassBody = "במכשיר הזה אין מגנטומטר, ולכן אין מצפן."
    override fun headingFrom(cardinal: String, fromTrueNorth: Boolean) = cardinal + if (fromTrueNorth) " · צפון אמיתי" else " · צפון מגנטי"
    override val waitingForSensor = "בהמתנה לחיישן…"
    override val magneticNorth = "צפון מגנטי"
    override val trueNorth = "צפון אמיתי"
    override val trueNorthNeedsLocation = "כדי להציג צפון אמיתי נדרש המיקום, לחישוב הסטייה המגנטית."
    override val needsCalibration =
        "המצפן זקוק לכיול: יש להניע את הטלפון כמה פעמים בתנועת שמינייה, הרחק ממתכת וממגנטים."
    override val sensorAccuracy = "דיוק החיישן"
    override val declination = "סטייה מגנטית"
    override fun declinationValue(degrees: String, east: Boolean) = "${ltr("$degrees°")} ${if (east) "מזרחה" else "מערבה"}"
    override val needsLocation = "נדרש מיקום"
    override val holdFlat = "לקריאה המדויקת ביותר, כדאי להחזיק את הטלפון שטוח."
    override val accuracyHigh = "גבוה"
    override val accuracyMedium = "בינוני"
    override val accuracyLow = "נמוך"
    override val accuracyUnreliable = "לא אמין"
    override val accuracyUnknown = "לא ידוע"

    override val noBarometer = "אין ברומטר"
    override val noBarometerBody =
        "במכשיר הזה אין חיישן לחץ אוויר. ברוב הטלפונים מהביניים ומעלה יש חיישן כזה; בדגמים הזולים לרוב אין."
    override val airPressure = "לחץ אוויר"
    override fun otherPressureUnits(inHg: String, mmHg: String) = "$inHg אינץ׳ כספית · $mmHg מ״מ כספית"
    override val barometricAltitude = "גובה ברומטרי"
    override fun seaLevelReference(hPa: String, calibrated: Boolean) =
        "לחץ בגובה פני הים: $hPa hPa " + if (calibrated) "(מכויל)" else "(אטמוספרה תקנית)"
    override val calibrate = "כיול"
    override val uncalibratedWarning = "בלי כיול, הגובה עלול לסטות ב־100 מ׳ ויותר כשמזג האוויר משתנה."
    override val trend = "מגמה"
    override val collectingTrend = "איסוף נתונים… כדי לראות מגמה, יש להשאיר את האפליקציה פתוחה 10 דקות לפחות."
    override fun trendPerHour(hPa: String, label: String) = "${ltr(hPa)} hPa לשעה · $label"
    override val fallingFast = "יורד מהר"
    override val falling = "יורד"
    override val steady = "יציב"
    override val rising = "עולה"
    override val risingFast = "עולה מהר"
    override val trendHint =
        "ירידה של יותר מכ־1 hPa לשעה מעידה לרוב על מזג אוויר שמחמיר; לחץ שעולה מעיד בדרך כלל על התבהרות."

    override val calibrateAltimeter = "כיול מד הגובה"
    override val calibrateIntro =
        "אפשר להזין את הגובה של המקום הנוכחי (משלט, מפסגה או מהמפה), או את הלחץ בגובה פני הים (QNH) של היום, מדיווח מזג האוויר."
    override val knownElevation = "גובה ידוע (מ׳)"
    override fun useGpsAltitude(meters: Int) = "שימוש בגובה מה־GPS: $meters מ׳"
    override val allowLocationForGpsAltitude = "מתן גישה למיקום, לשימוש בגובה מה־GPS"
    override val waitingForGpsAltitude = "בהמתנה לגובה מה־GPS…"
    override val orSeaLevelPressure = "או הלחץ בגובה פני הים (hPa)"
    override val apply = "החלה"

    override val locationNeeded = "נדרש מיקום"
    override val speedNeedsLocation = "המהירות מחושבת לפי ה־GPS. כדי לראות אותה, יש לאפשר גישה למיקום."
    override val groundSpeedGps = "מהירות קרקע (GPS)"
    override val waitingForGps = "בהמתנה ל־GPS… כדאי לצאת למקום פתוח עם שמיים גלויים."
    override fun gpsFixLost(seconds: Long) = "קליטת ה־GPS אבדה לפני $seconds שנ׳"
    override fun heading(degrees: Int, cardinal: String) = "כיוון ${ltr("$degrees°")} $cardinal"
    override fun pace(value: String) = "קצב $value"
    override fun perKm(minSec: String) = "$minSec לק״מ"
    override val stopped = "ללא תנועה"
    override val trip = "טיול"
    override val distance = "מרחק"
    override val maxSpeed = "מהירות מרבית"
    override val averageWhileMoving = "ממוצע בתנועה"
    override val movingTime = "זמן בתנועה"
    override val totalTime = "זמן כולל"
    override val tripHint =
        "נמדד כל עוד MapAround פתוחה. מתחת ל־1.8 קמ״ש נחשב עמידה במקום. לתיעוד מלא, אפשר להקליט מסלול במפה."
    override val speedLast10Minutes = "מהירות, 10 הדקות האחרונות"

    override val noMotionSensors = "אין חיישני תנועה"
    override val noMotionSensorsBody = "המכשיר הזה לא מדווח על מד תאוצה או ג׳ירוסקופ."
    override val noGyroscope = "אין ג׳ירוסקופ"
    override val noGyroscopeBody = "במכשיר הזה אין ג׳ירוסקופ, ולכן אין נתוני קצב סיבוב."
    override val tiltTitle = "הטיה (מד שיפוע)"
    override val pitch = "שיפוע אורכי"
    override val roll = "שיפוע רוחבי"
    override val noseUp = "חזית למעלה"
    override val noseDown = "חזית למטה"
    override val level = "מאוזן"
    override val rightSideDown = "צד ימין נמוך"
    override val leftSideDown = "צד שמאל נמוך"
    override val setAsLevel = "קביעה כמאוזן"
    override fun tiltHint(calibrated: Boolean) =
        (if (calibrated) "מאופס לפי המתקן שלך. " else "נמדד ביחס לטלפון כשהוא שוכב שטוח או עומד זקוף. ") +
            "ברכב: יש לקבע את הטלפון במתקן, לעצור על קרקע מישורית וללחוץ על \"קביעה כמאוזן\". " +
            "הצבעים משתנים ב־${ltr("15°")} וב־${ltr("25°")}; כדאי לבדוק את המגבלות של הרכב עצמו."
    override val accelerometer = "מד תאוצה"
    override val totalWithGravity = "סה״כ, כולל כוח הכבידה"
    override fun movement(current: String, peak: String) = "תנועה $current g · שיא $peak g"
    override fun forwardG(g: String, speedingUp: Boolean) = "${if (speedingUp) "האצה" else "בלימה"} $g g"
    override fun sidewaysG(g: String, right: Boolean) = "הצידה $g g ${if (right) "→" else "←"}"
    override val resetPeak = "איפוס השיא"
    override val accelGravityRemoved = "מ׳/שנ׳², בלי כוח הכבידה"
    override val gyroscope = "ג׳ירוסקופ"
    override val turningLeft = "פנייה שמאלה"
    override val turningRight = "פנייה ימינה"
    override val notTurning = "ללא פנייה"
    override fun totalRotation(value: String) = "סיבוב כולל ${degPerS(value)}"
    override fun range(value: String, unit: String) = "טווח ±$value $unit"
    override fun upToHz(hz: Int) = "עד $hz Hz"

    override val locationPermissionNeeded = "נדרשת הרשאת מיקום"
    override val gnssNeedsLocation =
        "נתוני קליטת הלוויינים מגיעים ממקלט ה־GNSS (GPS) של הטלפון, ולשם כך נדרשת גישה למיקום."
    override val locationOff = "המיקום כבוי"
    override val turnOnLocation = "כדי להפעיל את מקלט הלוויינים, יש להפעיל את המיקום."
    override val openSettings = "פתיחת ההגדרות"
    override val skyView = "מפת שמיים"
    override val skyViewLegend: String
        get() = "עיגול מלא = משמש לחישוב המיקום · טבעות ב־${ltr("0°, 30°, 60°")} מעל האופק"
    override val signalStrength = "עוצמת אות (C/N₀, dB‑Hz)"
    override val searching = "בחיפוש… כדאי לצאת למקום פתוח עם שמיים גלויים. התחלה קרה יכולה להימשך דקה או שתיים."
    override val startingReceiver = "הפעלת מקלט ה־GNSS…"
    override val noFix = "אין נעילה"
    override val fix3d = "נעילת 3D"
    override val fix2d = "נעילת 2D"
    override val inUseInView = "בשימוש / נראים"
    override val position = "מיקום"
    override val altitude = "גובה"
    override fun altitudeValue(meters: Int, msl: Boolean) =
        "${ltr(meters.toString())} מ׳ " + if (msl) "(מעל פני הים)" else "(GPS)"
    override val accuracy = "דיוק"
    override fun accuracyValue(horizontal: Int, vertical: Int?) =
        "±$horizontal מ׳" + (vertical?.let { " · ±$it מ׳ אנכי" } ?: "")
    override val course = "כיוון תנועה"
    override val lastFix = "עדכון אחרון"
    override fun secondsAgo(n: Int) = "לפני $n שנ׳"
    override val timeToFirstFix = "זמן לנעילה ראשונה"
    override val waitingForFirstFix = "בהמתנה לנעילה ראשונה…"
    override val usedInFix = "משמש לחישוב המיקום"
    override fun elevationAzimuth(elevation: Int, azimuth: Int) = "גובה ${ltr("$elevation°")} · אז׳ ${ltr("$azimuth°")}"
    override val otherConstellation = "אחר"
}
