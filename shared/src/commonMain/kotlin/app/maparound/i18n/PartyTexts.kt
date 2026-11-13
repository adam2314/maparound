package app.maparound.i18n

/** The party: sharing positions in a group, invites, members, the meeting point, SOS, messages, radios. */
open class PartyTexts {
    // ---- the Party screen: starting or joining a party ----
    /** The screen's title before there's a party. */
    open val title = "Party"

    /** The name a party gets when it's started without one (also an invite without a name). */
    open val defaultPartyName = "Party"

    /** My name in a party joined without one. */
    open val defaultMyName = "Me"
    open val intro =
        "See your group live on the map, online and off-grid over LoRa radios (Meshtastic). " +
            "Everything is end-to-end encrypted with the party's key."
    open val startParty = "Start a party"
    open val partyNameHint = "Party name, e.g. Desert run"
    open val yourName = "Your name"
    open val createAndInvite = "Create & invite"
    open val joinAParty = "Join a party"
    open val scanInviteQr = "Scan invite QR code"
    open val orPasteInviteLink = "…or paste the invite link"
    open val joinWithLink = "Join with link"
    open val invalidLink = "That isn't a valid party link"
    open val notAPartyQr = "That QR code isn't a MapAround party"

    // ---- joining and leaving ----
    open fun joinQuestion(name: String) = "Join “$name”?"
    open val joinExplanation = "Everyone in the party sees your live location while sharing is on."

    /** Joining another party while in one. */
    open fun leaveWarning(name: String) = "You'll leave “$name”."
    open val join = "Join"
    open val leave = "Leave"
    open val leaveQuestion = "Leave the party?"
    open val leaveExplanation = "Your position is removed for the others. You can rejoin with the invite."

    // ---- watching (the web app) ----
    open val watchIntro =
        "See your group live on the map: where everyone is, SOS alerts and messages. Open the party's " +
            "invite link, or paste it here. The web app only watches; to share your own position, " +
            "join with the MapAround app."
    open val watchAParty = "Watch a party"
    open val inviteLink = "Invite link"
    open val watch = "Watch"
    open fun watchQuestion(name: String) = "Watch “$name”?"
    open val watchExplanation =
        "You'll see where everyone in the party is, their SOS alerts and messages, while this page is open. " +
            "The web app only watches: it doesn't share your position, and the party doesn't see you. " +
            "To share yours, join with the MapAround app."

    /** Watching another party while watching one. */
    open fun stopWatchingWarning(name: String) = "You'll stop watching “$name”."

    /** The switch that connects the watcher. */
    open val watching = "Watching"
    open val watchingOnSubtitle = "Seeing the party live while this page is open"
    open val watchingOffSubtitle = "Off: not connected"
    open val onlyWatching = "Only watching: your position isn't shared, and the party doesn't see you."
    open val stopWatching = "Stop watching"
    open val stopWatchingQuestion = "Stop watching?"
    open val watchAgainHint = "You can watch again with the invite link."
    open val nothingYet = "No one in the party has sent anything yet."
    open val turnWatchingOnToSee = "Turn watching on to see the party."
    open val turnWatchingOnToConnect = "Turn watching on to connect."

    // ---- in a party ----
    /** The QR button at the top (content description). */
    open val invite = "Invite"

    /** The switch that runs the party (sending and receiving). */
    open val partyOn = "Party on"
    open val partyOnSubtitle = "Sending and receiving, also with the screen off"
    open val partyOffSubtitle = "Off: not sending or receiving"
    open val shareMyLocation = "Share my location"
    open val sharingOnSubtitle = "Others see you"
    open val sharingOffSubtitle = "Hidden: you see them, they don't see you"

    /** Me, by my avatar. */
    open fun youName(name: String) = "You: $name"
    open fun members(n: Int) = "Members ($n)"
    open val waitingForOthers = "Waiting for others… share the invite (QR icon at the top)."

    // ---- members ----
    /** A member who hasn't said their name yet; [code] is 4 letters/digits of their id. */
    open fun memberFallbackName(code: String) = "Member $code"
    open val noPositionYet = "no position yet"
    open fun routeTo(name: String) = "Route to $name"

    /** How far someone is and which way: "1.2 km NE". */
    open fun distanceDirection(distance: String, direction: String) = "$distance $direction"

    /** Heard from less than a minute ago. */
    open val agoNow = "now"
    open fun joinedParty(name: String) = "$name joined the party"
    open fun leftParty(name: String) = "$name left the party"

    // ---- SOS ----
    open val sendSosQuestion = "Send SOS?"
    open val sosExplanation = "Everyone in the party gets an alert with your position, over every connection you have."
    open val sosWhatHappened = "What happened (optional)"
    open val sendSos = "Send SOS"
    open val sosActiveTapToCancel = "SOS is active. Tap to cancel"

    /** A member in the list while their SOS is on. */
    open fun sosName(name: String) = "SOS · $name"

    /** The alert on screen; [text] is what they wrote (may be empty). */
    open fun sosFrom(name: String, text: String) = "SOS from $name! $text"
    open fun sosCancelled(name: String) = "$name cancelled the SOS"

    /** The SOS notification's title. */
    open fun sosNotificationTitle(name: String) = "SOS from $name"

    /** The SOS notification's text when they wrote nothing. */
    open val sosNeedsHelp = "Needs help. Tap to see where."

    // ---- the meeting point ----
    /** A meeting point whose label says nothing more (as [MapTexts.meetingPoint]). */
    open val meetingPoint = "Meeting point"
    open fun meetingPointNamed(label: String) = "Meeting point: $label"

    /** [meetHere] is the map button's text ([MapTexts.meetHere]). */
    open fun setMeetingPointHint(meetHere: String) = "Set a meeting point: long-press the map, then “$meetHere”."
    open val routeToMeetingPoint = "Route to meeting point"

    /** Removes the meeting point. */
    open val clearMeetingPoint = "Clear"
    open val newMeetingPoint = "New meeting point"
    open val tapToSeeOnMap = "Tap to see it on the map"

    // ---- other radios ----
    open fun otherRadios(n: Int) = "Other radios in range ($n)"

    // ---- messages ----
    open val messages = "Messages"

    /** Me, as the sender of a message. */
    open val you = "You"
    open val messagesShowHere = "The party's messages show here."
    open val messagesHint = "Short messages reach everyone, also over radio."
    open val messagePlaceholder = "Message"
    open val send = "Send"

    // ---- the invite ----
    open fun inviteTo(name: String) = "Invite to “$name”"
    open val inviteExplanation =
        "Others scan this with their phone's camera or in MapAround's Party tab, or open the link. " +
            "Anyone with it can see the party's positions, so share it only with your group."

    /** The text shared with the invite link. */
    open fun inviteShareText(name: String, link: String) = "Join my MapAround party “$name”: $link"

    /** The share sheet's title. */
    open val shareInvite = "Share invite"
    open val shareLink = "Share link"

    // ---- me in the party: name, icon, colour ----
    open val youInThisParty = "You in this party"
    open val nameLabel = "Name"
    open val howOthersSeeYou = "How the others see you on the map"
    open val colour = "Colour"
    open val hue = "Hue"
    open val shade = "Shade"

    /** The name's first letter, as an icon (and its group). */
    open val initial = "Initial"
    open val groupOnFoot = "On foot"
    open val groupTwoWheels = "On two wheels"
    open val groupVehicles = "Vehicles"
    open val iconWalker = "Walker"
    open val iconHiker = "Hiker"
    open val iconRunner = "Runner"
    open val iconNordicWalker = "Nordic walker"
    open val iconSkier = "Skier"
    open val iconKayaker = "Kayaker"
    open val iconCyclist = "Cyclist"
    open val iconBicycle = "Bicycle"
    open val iconEbike = "E-bike"
    open val iconMotorcycle = "Motorcycle"
    open val iconMoped = "Moped"
    open val iconCar = "Car"
    open val iconOffroad = "4×4"
    open val iconVan = "Van"
    open val iconTruck = "Truck"
    open val iconCaravan = "Caravan"

    // ---- connections ----
    open val connections = "Connections"
    open val online = "Online"

    /** A connection switched off. */
    open val off = "Off"
    open val loraRadio = "LoRa radio"
    open val noRadioPaired = "No radio paired"
    open val pair = "Pair"

    /** Forgets the paired radio. */
    open val forget = "Forget"
    open val gateway = "Gateway"
    open val gatewaySubtitle = "Relay between radio and online, so off-grid and online members see each other"
    open val turnPartyOnToConnect = "Turn the party on to connect."
    open val connected = "Connected"

    /** Connected, and to what: a server, a radio's name. */
    open fun connectedTo(detail: String) = "Connected · $detail"

    // A connection's state, when there's nothing more to say.
    open val stateOff = "off"
    open val stateConnecting = "connecting"
    open val stateConnected = "connected"
    open val stateError = "error"

    // How a member was heard: "online+radio".
    open val viaOnline = "online"
    open val viaRadio = "radio"
    open val viaNearby = "nearby"

    // ---- what a connection is doing ----
    open val bluetoothOff = "Bluetooth is off"
    open val bluetoothPermissionNeeded = "Bluetooth permission needed"
    open fun connectingToRadio(name: String) = "Connecting to $name…"
    open val pairWithRadioPin = "Pair with the radio: enter the PIN on its screen (or 123456)"
    open val pairingFailed = "Pairing failed or was cancelled"
    open val radioOutOfRange = "Radio out of range, reconnecting…"
    open val couldntConnectRetrying = "Couldn't connect, retrying…"
    open val notAMeshtasticRadio = "This device is not a Meshtastic radio"
    open fun reconnectingTo(host: String) = "Reconnecting to $host…"
    open fun subscribeFailed(message: String?) = "Subscribe failed: $message"

    /** A server and what went wrong with it ([problem] may be a system's error message). */
    open fun hostProblem(host: String, problem: String) = "$host: $problem"
    open val cantConnect = "can't connect"
    open val noAnswer = "no answer"
    open val connectionClosed = "connection closed"
    open fun refusedConnection(host: String, code: Int) = "$host refused the connection (code $code)"
    open fun refusedSubscription(host: String) = "$host refused the subscription"

    /** Reconnecting after the connection was lost; [why] says how. */
    open fun reconnectingBecause(why: String) = "Reconnecting… ($why)"

    // ---- pairing a radio (Android) ----
    open val scanPartyQrPrompt = "Scan the party QR code"
    open val partyQrCode = "Party QR code"
    open val bluetoothPermission = "Bluetooth permission"
    open val bluetoothPermissionExplanation = "MapAround needs Bluetooth access to find and talk to your Meshtastic radio."
    open val allow = "Allow"
    open fun scanFailed(code: Int) = "Scan failed ($code)"
    open val turnOnBluetoothFirst = "Turn on Bluetooth first"
    open val pairMeshtasticRadio = "Pair a Meshtastic radio"
    open val pairRadioInstructions =
        "Switch the radio on. All radios in the party must use the same Meshtastic region and channel (the default one works; " +
            "party messages are encrypted separately). Close the official Meshtastic app if it is connected to the radio."
    open val searching = "Searching…"

    // ---- the sharing notification and alert channels (Android) ----
    open fun notificationTitle(name: String) = "Party: $name"

    /** The notification's text before the first status. */
    open val connecting = "Connecting…"

    /** In the notification's summary, while no connection is up yet. */
    open val summaryConnecting = "connecting…"

    /** The other members, in the notification's summary. */
    open fun others(n: Int) = if (n == 1) "1 other" else "$n others"

    /** In the notification's summary while my location isn't shared. */
    open val hidden = "hidden"

    /** In the notification's summary while my SOS is on. */
    open val sosActiveCaps = "SOS ACTIVE"
    open val channelSharing = "Party location sharing"
    open val channelSharingDescription = "Shown while MapAround shares your location with your party"
    open val channelSos = "Party SOS alerts"
    open val channelMessages = "Party messages"
}

/** Hebrew. */
object PartyTextsHe : PartyTexts() {
    /** Right-to-left mark: keeps a line that starts with Latin letters ("SOS") right to left. */
    private const val RLM = "\u200F"

    override val title = "קבוצה"
    override val defaultPartyName = "קבוצה"
    override val defaultMyName = "אני"
    override val intro =
        "הקבוצה שלך על המפה בזמן אמת, באינטרנט וגם בלי קליטה, דרך מכשירי קשר LoRa של Meshtastic. " +
            "הכול מוצפן מקצה לקצה במפתח של הקבוצה."
    override val startParty = "יצירת קבוצה"
    override val partyNameHint = "שם הקבוצה, למשל טיול במדבר"
    override val yourName = "השם שלך"
    override val createAndInvite = "יצירה והזמנה"
    override val joinAParty = "הצטרפות לקבוצה"
    override val scanInviteQr = "סריקת קוד QR של הזמנה"
    override val orPasteInviteLink = "…או להדביק את קישור ההזמנה"
    override val joinWithLink = "הצטרפות עם הקישור"
    override val invalidLink = "זה לא קישור תקין לקבוצה"
    override val notAPartyQr = "קוד ה־QR הזה לא שייך לקבוצה של MapAround"

    override fun joinQuestion(name: String) = "להצטרף לקבוצה \"$name\"?"
    override val joinExplanation = "כל עוד השיתוף פעיל, כל המשתתפים בקבוצה רואים את המיקום שלך בזמן אמת."
    override fun leaveWarning(name: String) = "פעולה זו תוציא אותך מהקבוצה \"$name\"."
    override val join = "הצטרפות"
    override val leave = "יציאה"
    override val leaveQuestion = "לצאת מהקבוצה?"
    override val leaveExplanation = "המיקום שלך יוסר אצל האחרים. אפשר להצטרף שוב בעזרת ההזמנה."

    override val watchIntro =
        "הקבוצה שלך על המפה בזמן אמת: איפה כל אחד נמצא, התרעות SOS והודעות. אפשר לפתוח את קישור ההזמנה " +
            "של הקבוצה, או להדביק אותו כאן. גרסת הדפדפן רק צופה; כדי לשתף את המיקום שלך, " +
            "יש להצטרף דרך אפליקציית MapAround."
    override val watchAParty = "צפייה בקבוצה"
    override val inviteLink = "קישור הזמנה"
    override val watch = "צפייה"
    override fun watchQuestion(name: String) = "לצפות בקבוצה \"$name\"?"
    override val watchExplanation =
        "כל עוד הדף הזה פתוח, יוצגו כאן המיקומים של כל המשתתפים בקבוצה, התרעות ה־SOS וההודעות שלהם. " +
            "גרסת הדפדפן רק צופה: היא לא משתפת את המיקום שלך, והקבוצה לא רואה אותך. " +
            "כדי לשתף את המיקום שלך, יש להצטרף דרך אפליקציית MapAround."
    override fun stopWatchingWarning(name: String) = "הצפייה בקבוצה \"$name\" תיפסק."
    override val watching = "צפייה"
    override val watchingOnSubtitle = "הקבוצה מוצגת בזמן אמת כל עוד הדף פתוח"
    override val watchingOffSubtitle = "כבוי: אין חיבור"
    override val onlyWatching = "צפייה בלבד: המיקום שלך לא משותף, והקבוצה לא רואה אותך."
    override val stopWatching = "הפסקת הצפייה"
    override val stopWatchingQuestion = "להפסיק לצפות?"
    override val watchAgainHint = "אפשר לחזור לצפות בעזרת קישור ההזמנה."
    override val nothingYet = "עדיין לא התקבל דבר מהקבוצה."
    override val turnWatchingOnToSee = "כדי לראות את הקבוצה, יש להפעיל את הצפייה."
    override val turnWatchingOnToConnect = "כדי להתחבר, יש להפעיל את הצפייה."

    override val invite = "הזמנה"
    override val partyOn = "קבוצה פעילה"
    override val partyOnSubtitle = "שליחה וקבלה, גם כשהמסך כבוי"
    override val partyOffSubtitle = "כבוי: אין שליחה או קבלה"
    override val shareMyLocation = "שיתוף המיקום שלי"
    override val sharingOnSubtitle = "האחרים רואים אותך"
    override val sharingOffSubtitle = "מוסתר: האחרים מוצגים לך, אבל הם לא רואים אותך"
    override fun youName(name: String) = "אני: $name"
    override fun members(n: Int) = "משתתפים ($n)"
    override val waitingForOthers = "ממתינים לאחרים… אפשר לשתף את ההזמנה (סמל ה־QR למעלה)."

    override fun memberFallbackName(code: String) = "משתתף $code"
    override val noPositionYet = "עדיין אין מיקום"
    override fun routeTo(name: String) = "מסלול אל $name"
    override fun distanceDirection(distance: String, direction: String) = "$distance ל$direction"
    override val agoNow = "עכשיו"
    override fun joinedParty(name: String) = "הצטרפות לקבוצה: $name"
    override fun leftParty(name: String) = "יציאה מהקבוצה: $name"

    override val sendSosQuestion = "לשלוח SOS?"
    override val sosExplanation = "כל המשתתפים בקבוצה יקבלו התרעה עם המיקום שלך, בכל החיבורים הזמינים."
    override val sosWhatHappened = "מה קרה (לא חובה)"
    override val sendSos = "שליחת SOS"
    override val sosActiveTapToCancel = "התרעת SOS פעילה. הקשה לביטול"
    override fun sosName(name: String) = "${RLM}SOS · $name"
    override fun sosFrom(name: String, text: String) = "קריאת SOS מאת $name! $text"
    override fun sosCancelled(name: String) = "קריאת ה־SOS של $name בוטלה"
    override fun sosNotificationTitle(name: String) = "קריאת SOS מאת $name"
    override val sosNeedsHelp = "נדרשת עזרה. הקשה להצגת המיקום."

    override val meetingPoint = "נקודת מפגש"
    override fun meetingPointNamed(label: String) = "נקודת מפגש: $label"
    override fun setMeetingPointHint(meetHere: String) = "לקביעת נקודת מפגש: לחיצה ארוכה על המפה, ואז \"$meetHere\"."
    override val routeToMeetingPoint = "מסלול לנקודת המפגש"
    override val clearMeetingPoint = "הסרה"
    override val newMeetingPoint = "נקודת מפגש חדשה"
    override val tapToSeeOnMap = "הקשה להצגה במפה"

    override fun otherRadios(n: Int) = "מכשירי קשר נוספים בטווח ($n)"

    override val messages = "הודעות"
    override val you = "אני"
    override val messagesShowHere = "ההודעות של הקבוצה יופיעו כאן."
    override val messagesHint = "הודעות קצרות מגיעות לכולם, גם דרך מכשיר הקשר."
    override val messagePlaceholder = "הודעה"
    override val send = "שליחה"

    override fun inviteTo(name: String) = "הזמנה לקבוצה \"$name\""
    override val inviteExplanation =
        "אפשר לסרוק את הקוד במצלמת הטלפון או בלשונית \"קבוצה\" של MapAround, או לפתוח את הקישור. " +
            "כל מי שיש לו את הקישור יכול לראות את המיקומים בקבוצה, לכן כדאי לשתף אותו רק עם הקבוצה שלך."
    override fun inviteShareText(name: String, link: String) = "הזמנה לקבוצה \"$name\" ב־MapAround: $link"
    override val shareInvite = "שיתוף ההזמנה"
    override val shareLink = "שיתוף הקישור"

    override val youInThisParty = "הפרופיל שלך בקבוצה"
    override val nameLabel = "שם"
    override val howOthersSeeYou = "כך האחרים יראו אותך במפה"
    override val colour = "צבע"
    override val hue = "גוון"
    override val shade = "בהירות"
    override val initial = "אות ראשונה"
    override val groupOnFoot = "ברגל"
    override val groupTwoWheels = "על שני גלגלים"
    override val groupVehicles = "כלי רכב"
    override val iconWalker = "הליכה"
    override val iconHiker = "טיול רגלי"
    override val iconRunner = "ריצה"
    override val iconNordicWalker = "הליכה נורדית"
    override val iconSkier = "סקי"
    override val iconKayaker = "קיאק"
    override val iconCyclist = "רכיבת אופניים"
    override val iconBicycle = "אופניים"
    override val iconEbike = "אופניים חשמליים"
    override val iconMotorcycle = "אופנוע"
    override val iconMoped = "קטנוע"
    override val iconCar = "מכונית"
    override val iconOffroad = "רכב שטח"
    override val iconVan = "ואן"
    override val iconTruck = "משאית"
    override val iconCaravan = "קרוואן"

    override val connections = "חיבורים"
    override val online = "אונליין"
    override val off = "כבוי"
    override val loraRadio = "מכשיר קשר LoRa"
    override val noRadioPaired = "אין מכשיר קשר מותאם"
    override val pair = "התאמה"
    override val forget = "הסרה"
    override val gateway = "גשר"
    override val gatewaySubtitle = "העברה בין מכשיר הקשר לאינטרנט, כך שמשתתפים בלי קליטה ומשתתפים מחוברים יראו אלה את אלה"
    override val turnPartyOnToConnect = "כדי להתחבר, יש להפעיל את הקבוצה."
    override val connected = "מחובר"
    override fun connectedTo(detail: String) = "מחובר · $detail"
    override val stateOff = "כבוי"
    override val stateConnecting = "מתחבר"
    override val stateConnected = "מחובר"
    override val stateError = "שגיאה"
    override val viaOnline = "אונליין"
    override val viaRadio = "מכשיר קשר"
    override val viaNearby = "בסביבה"

    override val bluetoothOff = "ה־Bluetooth כבוי"
    override val bluetoothPermissionNeeded = "נדרשת הרשאת Bluetooth"
    override fun connectingToRadio(name: String) = "מתחבר ל־$name…"
    override val pairWithRadioPin = "התאמה למכשיר הקשר: יש להזין את קוד ה־PIN שמוצג במסך שלו (או 123456)"
    override val pairingFailed = "ההתאמה נכשלה או בוטלה"
    override val radioOutOfRange = "מכשיר הקשר מחוץ לטווח, מתחבר מחדש…"
    override val couldntConnectRetrying = "החיבור נכשל, מנסה שוב…"
    override val notAMeshtasticRadio = "המכשיר הזה אינו מכשיר קשר של Meshtastic"
    override fun reconnectingTo(host: String) = "מתחבר מחדש אל ${ltr(host)}…"
    override fun subscribeFailed(message: String?) = "ההרשמה לעדכונים נכשלה: $message"
    override fun hostProblem(host: String, problem: String) = "$problem (${ltr(host)})"
    override val cantConnect = "אי אפשר להתחבר"
    override val noAnswer = "אין תשובה"
    override val connectionClosed = "החיבור נסגר"
    override fun refusedConnection(host: String, code: Int) = "השרת ${ltr(host)} דחה את החיבור (קוד $code)"
    override fun refusedSubscription(host: String) = "השרת ${ltr(host)} דחה את ההרשמה לעדכונים"
    override fun reconnectingBecause(why: String) = "מתחבר מחדש… ($why)"

    override val scanPartyQrPrompt = "סריקת קוד ה־QR של הקבוצה"
    override val partyQrCode = "קוד QR של הקבוצה"
    override val bluetoothPermission = "הרשאת Bluetooth"
    override val bluetoothPermissionExplanation =
        "כדי למצוא את מכשיר הקשר של Meshtastic ולתקשר איתו, נדרשת ל־MapAround גישה ל־Bluetooth."
    override val allow = "מתן הרשאה"
    override fun scanFailed(code: Int) = "הסריקה נכשלה ($code)"
    override val turnOnBluetoothFirst = "יש להפעיל קודם את ה־Bluetooth"
    override val pairMeshtasticRadio = "התאמת מכשיר קשר של Meshtastic"
    override val pairRadioInstructions =
        "יש להדליק את מכשיר הקשר. כל מכשירי הקשר בקבוצה צריכים להשתמש באותו אזור ובאותו ערוץ של Meshtastic " +
            "(ערוץ ברירת המחדל מתאים; ההודעות של הקבוצה מוצפנות בנפרד). אם אפליקציית Meshtastic הרשמית מחוברת " +
            "למכשיר הקשר, יש לסגור אותה."
    override val searching = "מחפש…"

    override fun notificationTitle(name: String) = "קבוצה: $name"
    override val connecting = "מתחבר…"
    override val summaryConnecting = "מתחבר…"
    override fun others(n: Int) = when (n) {
        0 -> "אין עוד משתתפים"
        1 -> "עוד משתתף אחד"
        else -> "עוד $n משתתפים"
    }
    override val hidden = "מוסתר"
    override val sosActiveCaps = "SOS פעיל"
    override val channelSharing = "שיתוף מיקום בקבוצה"
    override val channelSharingDescription = "מוצג כל עוד MapAround משתפת את המיקום שלך עם הקבוצה"
    override val channelSos = "התרעות SOS בקבוצה"
    override val channelMessages = "הודעות בקבוצה"
}
