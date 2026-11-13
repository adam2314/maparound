package app.maparound.i18n

/** Planning routes and navigating: the planner, turn-by-turn guidance, voice prompts, Android Auto. */
open class NavTexts {
    // ---- route profiles (the planner's ways of travelling) ----
    open val profileHike = "Hike"
    open val profileBike = "Bike"
    open val profileCar = "Car"

    /** Straight lines between the tapped points: "draw & measure". */
    open val profileLine = "Line"

    // ---- routing errors (shown in the route planner) ----
    open val noRouter = "No router on this device"
    open fun routingFailed(message: String?) = "Routing failed: $message"
    open val addTwoPoints = "Add at least two points"

    /** [segments]: the routing data files missing there ("E5_N45, E10_N45"). */
    open fun noInternetNoRoutingData(segments: String) =
        "No internet, and no offline routing data here ($segments). Download it in Offline maps, or use Line mode."
    open val pointNotNearRoad = "A point is not near a road or path."
    open val noRoutingDataForPoint = "There is no routing data for one of the points (open sea?)."
    open fun routingServerError(code: Int) = "Routing server error (HTTP $code)"
    open fun couldNotReachServer(message: String?) = "Couldn't reach the routing server: $message"

    /** [name]: a BRouter profile file ("hiking-mountain"). */
    open fun routingProfileMissing(name: String) = "Routing profile $name is missing"
    open val noRouteFound = "No route found"
    open val pointOutsideRoutingData = "A point is outside the downloaded routing data, or not near a road or path."
    open val noRouteBetweenPoints = "No route found between these points."

    /** The web app's: the routing server found no route. */
    open val noRouteHere = "No route here (a point may be away from roads and paths, or at sea)"

    // ---- what to do at a turn (on the screen, and said) ----
    open val continueStraight = "Continue straight"
    open val turnSlightLeft = "Turn slightly left"
    open val turnLeft = "Turn left"
    open val turnSharpLeft = "Turn sharply left"
    open val turnSlightRight = "Turn slightly right"
    open val turnRight = "Turn right"
    open val turnSharpRight = "Turn sharply right"
    open val keepLeft = "Keep left"
    open val keepRight = "Keep right"
    open val exitLeft = "Take the exit on the left"
    open val exitRight = "Take the exit on the right"
    open val uTurn = "Make a U-turn"
    open val enterRoundabout = "Enter the roundabout"

    /** [exit]: which exit, 1 = the first. */
    open fun roundaboutExit(exit: Int) = "At the roundabout, take the ${ordinal(exit)} exit"

    /** At a stop with no turn there (not shown on its own). */
    open val continueOn = "Continue"

    /** At the end (not shown on its own). */
    open val arrive = "Arrive"

    /** A roundabout exit's place: "1st", "2nd", "11th". */
    open fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    // ---- stops along the route ----

    /** A planner stop: its number; 0 = the start of a route we are being led to; null = a stop without a number. */
    open fun stopName(stop: Int?) = when (stop) {
        null -> "the stop"
        0 -> "the start of your route"
        else -> "stop $stop"
    }

    // ---- instructions on the screen ----
    open val arriveAtDestination = "Arrive at your destination"
    open fun reachStop(stop: Int?) = "Reach ${stopName(stop)}"

    /** A turn at one of the stops: "Turn left at stop 2"; [action] is one of the turns above. */
    open fun actionAtStop(action: String, stop: Int?) = "$action at ${stopName(stop)}"

    // ---- voice prompts ----

    /** Said a while before a turn: "In 300 metres, turn left". [what]: [youArrive], [youReachStop] or an instruction. */
    open fun inDistance(distance: String, what: String) = "In $distance, $what"

    /** Inside a sentence: "In 300 metres, you arrive at your destination". */
    open val youArrive = "you arrive at your destination"
    open fun youReachStop(stop: Int?) = "you reach ${stopName(stop)}"

    /** Said on arriving. */
    open val arrivedAtDestination = "You have arrived at your destination"
    open fun reachedStop(stop: Int?) = "You have reached ${stopName(stop)}"

    /** Joined to an announcement when the next turn follows closely: "then turn right". */
    open fun then(what: String) = "then $what"

    /** The first words of a route whose first turn is far away: "Continue for 2 kilometres". */
    open fun continueFor(distance: String) = "Continue for $distance"
    open val recalculatingRoute = "Recalculating the route"
    open fun couldNotRecalculate(message: String?) = "Couldn't recalculate the route: $message"

    /** A distance as it is said: "50 metres". */
    open fun spokenMetres(m: Int) = "$m metres"

    /** "1 kilometre", "2.5 kilometres"; [km] is written with a dot ("2.5"). */
    open fun spokenKilometres(km: String) = if (km == "1") "1 kilometre" else "$km kilometres"

    // ---- the navigation banner and panel ----
    open val recalculating = "Recalculating…"
    open val youHaveArrived = "You have arrived"
    open val offRoute = "Off the route"

    /** Under "Off the route": how far from it, "40 m away". */
    open fun offRouteBy(distance: String) = "$distance away"
    open val waitingForGps = "Waiting for GPS…"

    /** Over the small arrow of the turn after the next one. */
    open val thenLabel = "then"

    /** "Arrival 14:35" */
    open fun arrival(time: String) = "Arrival $time"
    open val muteVoice = "Mute voice guidance"
    open val unmuteVoice = "Turn voice guidance on"

    /** Ends navigation (phone, notification, car). */
    open val stopNavigation = "Stop"

    // ---- the navigation notification ----
    open val navigating = "Navigating"

    /** To the next turn: "In 300 m". */
    open fun inDistanceShort(distance: String) = "In $distance"

    /** To the destination: "3.2 km left". */
    open fun distanceLeft(distance: String) = "$distance left"

    /** "arrival 14:35", inside a line. */
    open fun arrivalInLine(time: String) = "arrival $time"
    open val channelName = "Navigation"
    open val channelDescription = "The next turn, shown while MapAround navigates"

    // ---- Android Auto ----

    /** The trip's end, for the car's own displays. */
    open val destination = "Destination"

    /** Turns the voice off. */
    open val mute = "Mute"

    /** Turns the voice on. */
    open val voice = "Voice"

    /** Switches back from satellite imagery to the map. */
    open val carMap = "Map"
    open val carSatellite = "Satellite"
    open val carStarting = "Starting…"
    open val carAllowLocation = "Allow location in MapAround on your phone"

    /** The planned route's length: "route 12.5 km". */
    open fun carRoute(distance: String) = "route $distance"
    open val carPartySos = "SOS in party!"

    /** How many of the party are on the map. */
    open fun carPartyOnMap(n: Int) = "party: $n on map"

    /** A track is being recorded. */
    open val carRecording = "recording"
    open val carNoOfflineMaps = "no offline maps: download them on the phone"
}

/** Hebrew. */
object NavTextsHe : NavTexts() {
    override val profileHike = "הליכה"
    override val profileBike = "אופניים"
    override val profileCar = "רכב"
    override val profileLine = "קו"

    override val noRouter = "אין במכשיר הזה מנוע לחישוב מסלולים"
    override fun routingFailed(message: String?) = "חישוב המסלול נכשל: $message"
    override val addTwoPoints = "יש להוסיף לפחות שתי נקודות"
    override fun noInternetNoRoutingData(segments: String) =
        "אין חיבור לאינטרנט, ואין כאן נתוני ניווט אופליין (${ltr(segments)}). " +
            "אפשר להוריד אותם במפות אופליין, או להשתמש במצב \"קו\"."
    override val pointNotNearRoad = "אחת הנקודות אינה קרובה לכביש או לשביל."
    override val noRoutingDataForPoint = "אין נתוני ניווט לאחת הנקודות (ים פתוח?)."
    override fun routingServerError(code: Int) = "שגיאה בשרת הניווט (HTTP $code)"
    override fun couldNotReachServer(message: String?) = "אין גישה לשרת הניווט: $message"
    override fun routingProfileMissing(name: String) = "פרופיל הניווט ${ltr(name)} חסר"
    override val noRouteFound = "לא נמצא מסלול"
    override val pointOutsideRoutingData = "אחת הנקודות נמצאת מחוץ לנתוני הניווט שהורדו, או רחוקה מכביש או משביל."
    override val noRouteBetweenPoints = "לא נמצא מסלול בין הנקודות האלה."
    override val noRouteHere = "אין כאן מסלול (אולי אחת הנקודות רחוקה מכבישים ומשבילים, או בים)"

    override val continueStraight = "להמשיך ישר"
    override val turnSlightLeft = "פנייה קלה שמאלה"
    override val turnLeft = "פנייה שמאלה"
    override val turnSharpLeft = "פנייה חדה שמאלה"
    override val turnSlightRight = "פנייה קלה ימינה"
    override val turnRight = "פנייה ימינה"
    override val turnSharpRight = "פנייה חדה ימינה"
    override val keepLeft = "להישאר משמאל"
    override val keepRight = "להישאר מימין"
    override val exitLeft = "יציאה משמאל"
    override val exitRight = "יציאה מימין"
    override val uTurn = "פניית פרסה"
    override val enterRoundabout = "כניסה לכיכר"
    override fun roundaboutExit(exit: Int) = "בכיכר, יציאה ${ordinal(exit)}"
    override val continueOn = "להמשיך"
    override val arrive = "הגעה"

    private val ordinals = listOf("ראשונה", "שנייה", "שלישית", "רביעית", "חמישית", "שישית", "שביעית", "שמינית", "תשיעית", "עשירית")

    /** Feminine, as it goes with "יציאה": "שלישית"; from 11 on "מספר 11". */
    override fun ordinal(n: Int): String = ordinals.getOrNull(n - 1) ?: "מספר $n"

    override fun stopName(stop: Int?) = when (stop) {
        null -> "העצירה"
        0 -> "תחילת המסלול"
        else -> "עצירה $stop"
    }

    /** [stopName] after ל or ב: "לעצירה 2", "בתחילת המסלול", "לעצירה". */
    private fun stopAfter(prefix: String, stop: Int?) = prefix + when (stop) {
        null -> "עצירה"
        0 -> "תחילת המסלול"
        else -> "עצירה $stop"
    }

    override val arriveAtDestination = "הגעה ליעד"
    override fun reachStop(stop: Int?) = "הגעה ${stopAfter("ל", stop)}"
    override fun actionAtStop(action: String, stop: Int?) = "$action ${stopAfter("ב", stop)}"

    override fun inDistance(distance: String, what: String) = "בעוד $distance, $what"
    override val youArrive = "הגעה ליעד"
    override fun youReachStop(stop: Int?) = "הגעה ${stopAfter("ל", stop)}"
    override val arrivedAtDestination = "הגעה ליעד"
    override fun reachedStop(stop: Int?) = "הגעה ${stopAfter("ל", stop)}"
    override fun then(what: String) = "ואז $what"
    override fun continueFor(distance: String) = "להמשיך במסלול לאורך $distance"
    override val recalculatingRoute = "מחשב מסלול מחדש"
    override fun couldNotRecalculate(message: String?) = "לא ניתן לחשב מסלול מחדש: $message"

    /** Words, not "מ׳", so that the voice reads them right: "200 מטר". */
    override fun spokenMetres(m: Int) = "$m מטר"
    override fun spokenKilometres(km: String) = when (km) {
        "1" -> "קילומטר"
        "1.5" -> "קילומטר וחצי"
        else -> "$km קילומטר"
    }

    override val recalculating = "מחשב מסלול מחדש…"
    override val youHaveArrived = "הגעה ליעד"
    override val offRoute = "מחוץ למסלול"
    override fun offRouteBy(distance: String) = "$distance מהמסלול"
    override val waitingForGps = "ממתין ל־GPS…"
    override val thenLabel = "ואז"
    override fun arrival(time: String) = "הגעה ב־$time"
    override val muteVoice = "השתקת ההנחיות הקוליות"
    override val unmuteVoice = "הפעלת ההנחיות הקוליות"
    override val stopNavigation = "סיום"

    override val navigating = "ניווט פעיל"
    override fun inDistanceShort(distance: String) = "בעוד $distance"
    override fun distanceLeft(distance: String) = "$distance ליעד"
    override fun arrivalInLine(time: String) = "הגעה ב־$time"
    override val channelName = "ניווט"
    override val channelDescription = "הפנייה הבאה, בזמן ניווט ב־MapAround"

    override val destination = "יעד"
    override val mute = "השתקה"
    override val voice = "קול"
    override val carMap = "מפה"
    override val carSatellite = "לוויין"
    override val carStarting = "בטעינה…"
    override val carAllowLocation = "יש לאשר ל־MapAround גישה למיקום בטלפון"
    override fun carRoute(distance: String) = "מסלול $distance"
    override val carPartySos = "SOS בקבוצה!"
    override fun carPartyOnMap(n: Int) = "קבוצה: $n במפה"
    override val carRecording = "בהקלטה"
    override val carNoOfflineMaps = "אין מפות אופליין: אפשר להוריד אותן בטלפון"
}
