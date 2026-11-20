package app.maparound.party.link

// Plain Kotlin: Auto mode (docs/LINK_PROTOCOL.md §3, "Auto mode"): the mode on one connection,
// from what the phone knows. Nothing of this goes on the wire but the mode it picks. Unit-tested.

/** What Auto decides by, for one connection of one party, at [nowS] (Unix seconds). */
data class AutoInputs(
    val nowS: Long,
    val batteryPct: Int? = null,
    val charging: Boolean = false,
    val moving: Boolean = false,
    /** How long I've been still (0 while moving). */
    val stillForS: Long = 0,
    /** To the nearest other member's last known position, metres; null: none known. */
    val nearestMemberM: Double? = null,
    /** To the meeting point, metres; null: none set. */
    val meetingPointM: Double? = null,
    val mySos: Boolean = false,
    /** Someone else's SOS is active in the party. */
    val sosInParty: Boolean = false,
    /** Party Live runs until then (Unix seconds); 0: not on. */
    val partyLiveUntil: Long = 0,
    /** Nobody else has been heard on this connection for this long. */
    val aloneForS: Long = 0,
    /** A radio's mesh is busy (Meshtastic's channel utilisation over 25%). */
    val meshBusy: Boolean = false,
)

/**
 * Auto, or a [pinned] mode, on one connection. Livelier at once; calmer only once the reason has
 * been gone for [CALM_DOWN_S], so the mode doesn't flap. A pinned mode applies at once; an SOS
 * and party Live override it.
 */
class AutoMode(var pinned: LinkMode? = null) {
    private var current: LinkMode? = null
    private var calmerSince: Long? = null

    fun update(inputs: AutoInputs): ModeInfo {
        val target = target(inputs, pinned)
        val now = inputs.nowS
        val was = current
        current = when {
            was == null || ModeTable.rank(target) > ModeTable.rank(was) -> target.also { calmerSince = null }
            target == was -> target.also { calmerSince = null }
            pinned != null && !overridden(inputs) -> target.also { calmerSince = null }
            else -> {
                val since = calmerSince ?: now.also { calmerSince = it }
                if (now - since >= CALM_DOWN_S) target.also { calmerSince = null } else was
            }
        }
        return ModeInfo(current!!, auto = pinned == null)
    }

    companion object {
        const val CALM_DOWN_S = 300L
        const val LIVE_BATTERY = 30
        const val SAVER_BATTERY = 20
        const val NEAR_MEMBER_M = 300.0
        const val NEAR_MEETING_M = 1000.0
        const val STILL_S = 600L
        const val ALONE_S = 1800L

        /** The mode the rules call for now, before any waiting. */
        fun target(i: AutoInputs, pinned: LinkMode?): LinkMode {
            if (i.mySos) return LinkMode.LIVE
            val lowBattery = i.batteryPct != null && i.batteryPct <= SAVER_BATTERY && !i.charging
            if (i.partyLiveUntil > i.nowS) return stepDown(if (lowBattery) LinkMode.NORMAL else LinkMode.LIVE, i.meshBusy)
            if (pinned != null) return pinned
            val liveOk = i.batteryPct == null || i.batteryPct > LIVE_BATTERY || i.charging
            val near = (i.nearestMemberM ?: Double.MAX_VALUE) <= NEAR_MEMBER_M || (i.meetingPointM ?: Double.MAX_VALUE) <= NEAR_MEETING_M
            val mode = when {
                liveOk && (i.sosInParty || (i.moving && (near || i.charging))) -> LinkMode.LIVE
                lowBattery || i.stillForS >= STILL_S || i.aloneForS >= ALONE_S -> LinkMode.SAVER
                else -> LinkMode.NORMAL
            }
            return stepDown(mode, i.meshBusy)
        }

        private fun overridden(i: AutoInputs) = i.mySos || i.partyLiveUntil > i.nowS

        private fun stepDown(mode: LinkMode, busy: Boolean): LinkMode = if (!busy) mode else when (mode) {
            LinkMode.LIVE -> LinkMode.NORMAL
            else -> LinkMode.SAVER
        }
    }
}
