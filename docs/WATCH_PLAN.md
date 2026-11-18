# MapAround on watches: plan

Goal: MapAround on the wrist for hiking and off-road. The watch shows the way (next turn, the route,
the Topo map), controls recording, and keeps the party in reach (where everyone is, SOS). **Android
watches (Wear OS) first**; other watches get what notifications can carry.

## Principles

- **The watch stands on its own for the map.** Like Suunto's and Garmin's, it keeps its own offline
  map and draws it itself. Nothing is drawn on the phone and sent over: that would tie the watch to
  the phone and Bluetooth, and drain both batteries.
- **Same data as the phone.** The same mapsforge maps (with MapAround's marked trails), the same
  elevation files, the same Topo style. No watch-only format until one is needed (phase 4).
- **Battery first.** Draw only what changed, keep what was drawn, redraw only on a move or a zoom;
  in ambient mode (the dimmed screen) show just the route and where you are.
- **Small steps that each ship.** Each phase is useful on its own.

## Decisions (2026-11-18)

- Wear OS first. Garmin, Suunto, Amazfit and the rest get phase 1 (notifications); their own apps
  (Connect IQ and so on) maybe later. Apple Watch comes with the iPhone app.
- The watch map is drawn **on the watch with mapsforge and Topo** (`topo.xml` is a mapsforge
  theme; the phone translates it for MapLibre, the watch can use it as written).
- No pictures rendered on the phone.

## Where things stand

What a watch shows of MapAround today, through the phone's notifications (watches mirror the phone's
notifications except ongoing ones; to confirm on a paired watch emulator):

| Phone notification | Ongoing? | On the watch |
|---|---|---|
| Navigation (next turn, Stop) | yes, alerts once | no: turns never reach the wrist |
| Recording a track | yes | no |
| Party running | yes | no |
| Party SOS, joins, messages | no | yes |
| Downloads | yes | no (fine) |

## Phase 1: turn alerts on any watch (phone code only): done (2026-11-18)

Every watch that mirrors notifications buzzes at each turn, with no watch app.

**As built:** `NavigationSession.announcements` gives every instruction (whether the voice is on or
not); `NavigationService` posts each as notification 46 on the "Turn alerts" channel (vibration, no
sound, importance default: no pop-up over other apps), not ongoing, auto-cancelled, gone after a
minute, with Stop navigation; skipped while one of MapAround's screens is in front. No setting of
its own: the channel is turned off in Android's settings like any other. Checked on the phone
emulator (navigating in the background, off the route: "In 180 metres, turn left · 4.6 km left",
not ongoing) and by unit tests; **still to check on a paired watch**, Wear OS emulator or real.

- **What:** a short, ordinary (not ongoing) notification at the moments navigation already speaks
  its instructions: "In 200 m, left onto Derech HaShalom", then "Turn left now". Each replaces the
  last (one tag), and goes once the turn is behind (`setTimeoutAfter`). Also: arrived; off the
  route, recalculating. Recording: optional quiet summaries (time, distance) every 15 minutes.
- **Where:** `app/.../routing/Navigation.kt` (beside the ongoing notification), a new channel
  "Turn alerts" with vibration; the moments come from `NavigationSession`'s announcements.
- **Setting:** Settings → Screen, or navigation's own settings: "Turn alerts on the watch" (on by
  default). On the phone they show quietly (no heads-up over the map while MapAround is open).
- **To check:** that a quiet channel still makes the watch vibrate; if not, the channel alerts and
  the phone side stays as unobtrusive as Android allows.
- **Done when:** on a Wear OS emulator paired with the phone emulator, a simulated route buzzes the
  watch at each turn, in English and Hebrew.

## Phase 2: the Wear OS app, without a map

A `wear/` module in this repo: the same package (`app.maparound`), Wear OS 3 and later
(API 30+), Compose for Wear OS. It reuses `:shared` (geo math, GPX, the party's protocol, texts in
English and Hebrew); R8 drops what the watch doesn't use.

**Phone ↔ watch:** the Wearable Data Layer (Google Play services, no account or server).
- *Data* (state, kept in sync): navigation (route line, next maneuver, distance, arrival time),
  recording (time, distance, climb), the party (members' positions, the meeting point), the pin.
- *Messages* (commands): stop navigation, start/pause/stop recording, send SOS, cancel SOS.

**On the watch:**
- **Navigate:** the next turn as a big arrow, its distance, the street or trail; vibration at each
  turn; Stop. While navigating it shows on the watch face (Ongoing Activity).
- **Route view (breadcrumb):** the route line, the track so far, waypoints and you, north-up or
  heading-up, zoomed with the crown. No map data: almost free on battery, and it already answers
  "which way at this junction?" (Suunto's navigation started this way).
- **Record:** start, pause, stop; time, distance, climb, speed.
- **Party:** members with distance and an arrow toward each, the meeting point, **SOS** (hold to
  send).
- **Compass to a point:** the pin, the meeting point or the next waypoint, with distance.
- **Tile:** the next turn while navigating, else the recording, else the nearest party members.
- **Complication:** distance to the destination or the next turn, for any watch face.

**Done when:** the phone navigates, records and parties while the watch shows and controls it all,
on the emulators and on a real watch; English and Hebrew (right to left on the round screen).

## Phase 3: Topo on the watch

**Spike first (a few days):** on a Wear OS emulator and a real watch, draw Israel's map (98 MB)
with `topo.xml`, contours and hill shading at zooms 13 to 16, and measure the time per tile and the
memory. Go on if a screen's tiles draw in about a second and memory stays well under the watch's
limit; else, contours traced on the phone (or by the pipeline) instead of on the watch, and hill
shading at fewer zooms.

**The map:**
- **Drawing:** mapsforge's Android renderer (`mapsforge-map-android`, the same 0.25 the app has)
  with `topo.xml`, in tiles kept in a cache on the watch.
- **A watch preset of Topo:** thicker lines, larger and fewer labels, buildings and most points of
  interest off. `topo.xml` already has switchable layers (land cover, buildings, boundaries…), so
  this is a preset and a scale factor (`userScaleFactor`, text scale), not a second theme.
- **Contours:** traced on the watch from the elevation files by the shared tracer
  (`shared/.../terrain/Contours.kt`), served to mapsforge by a small `MapDataStore` as ways tagged
  `contour=minor|major` and `ele=<metres>`, which `topo.xml` already styles.
- **Hill shading:** mapsforge's own (`HillsRenderConfig` with `MemoryCachingHgtReaderTileSource`)
  from the same `.hgt` files.
- **Marked trails, path difficulty, dirt roads' passability:** already in MapAround's maps and in
  the theme.

**The data on the watch:**
- The region's map, downloaded **by the watch** over Wi-Fi from the same catalog as the phone's
  (GitHub releases), or sent from the phone when the watch has no Wi-Fi.
- Elevation files only for where you hike (one square of a degree is roughly 10 to 25 MB), chosen on
  the phone ("for this route", "around here").
- A small offline maps screen on the watch: what's there, the space it takes, delete.

**Using it:** the crown zooms, a swipe pans, a tap re-centres; north-up or heading-up; the route and
the party on the map; ambient mode falls back to the route view.

## Phase 4: later

- **Recording on the watch's own GPS**, the phone left behind, synced to the Library afterwards.
- **A watch edition of each map** from the build pipeline (no buildings, fewer points of interest,
  simpler land cover), if the full maps turn out heavy on the watch.
- **Garmin** (Connect IQ), and other watch makers with apps of their own, if there's demand.
- **Apple Watch**, with the iPhone app.

## Distribution

- Published in MapAround's own Play listing: the watch build beside the phone's (same package, same
  signing key), with the **Wear OS form factor** turned on in Play Console, watch screenshots, and
  Google's review of the watch app.
- Built in CI with the phone app. **Nothing is uploaded to Play without an explicit go-ahead.**
- The Data Layer is Google Play services: fine for the Play build; a build without Play services
  (F-Droid, say) would have no watch link.

## Testing

- **Emulators:** a Wear OS emulator (round, 454×454) paired with the phone emulator; routes
  simulated with mock locations.
- **Unit tests:** when turn alerts fire, the Data Layer's encoding of navigation, recording and the
  party, the contour store's ways.
- **A real watch** for battery and rendering speed (which one: to decide).

## Open questions

- Which watch to test on (Pixel Watch, Galaxy Watch…), and whether you wear a Wear OS watch at all.
- Whether a quiet notification channel still vibrates the watch (phase 1).
- Rendering speed of contours and hill shading on the watch (the phase 3 spike decides).
