<p align="center"><img src="docs/store/icon-512.png" width="128" alt="MapAround icon"></p>

# MapAround

An Android map browser built around OpenStreetMap data. You can download maps per country and use
them with no internet connection. It also plans routes and guides you along them turn by turn,
records tracks, shows marked hiking trails,
shares your group's live positions (online and off-grid over LoRa radios), and includes a compass,
a barometer and a GNSS satellite-reception view.

Licence: **GPL-3.0** (see [LICENSE](LICENSE)); bundled components keep their own licences (below).

## Features

| Tab | What it does |
|---|---|
| **Map** | Pan/zoom map, my-location dot with accuracy circle and follow mode, long-press to drop a pin (coordinates, DMS, distance/bearing from you, copy, open in another app). Buttons for **search**, layers, the **route planner**, **map tools**, the **3D view** and **track recording**; zoom with pinch or double-tap. |
| **Search** (on the map, magnifier) | Your saved places, coordinates in any format and map links, and names in the **offline maps** around the map centre (towns, peaks, springs, streets, paths, hotels…, within 25 km, works without internet) as you type; **online** search of OpenStreetMap anywhere (Photon, with Nominatim as a fallback) when you press search. Picking a result drops a labelled pin there. |
| **Map tools** (on the map, ruler button) | **Azimuth & range** between two points (true and magnetic bearing, mils, back bearing, height difference, slope, place B by bearing + distance). **Line of sight** from A to B with a terrain profile and where the view is blocked; as a **radio link**, with antenna heights at both ends and a frequency (LoRa 433/868/915 MHz, 2.4 and 5.8 GHz, VHF, PMR446, FRS/GMRS or any), it draws the first Fresnel zone on the profile and gives its clearance (60% rule) and the path loss (free space + terrain). **Viewshed**: a circle (250 m–25 km) showing ground in sight (green) and hidden (red). **Coordinate converter**: one point in decimal degrees, deg-min, deg-min-sec, UTM, MGRS, Israeli ITM, Plus Code and Maidenhead; type or paste any of them to jump there; pick the format used on pins. |
| **Route planner** (on the map) | Tap points (start, stops, end); tap a numbered point to remove it. Hold a stop and drag it to move it, or hold the line and drag to add a stop there; the line follows your finger and the route is calculated again when you let go. Profiles **Hike / Bike / Car** follow paths and roads; **Line** draws straight lines to measure. Shows distance, climb/descent, estimated time and an elevation profile; undo, reverse, save to the library. **Start** navigates: the next turn and its distance, time left and arrival, voice prompts (switchable), a new route when you leave it, and it keeps going with the screen off. |whats
| **3D view** (on the map, cube button) | The map in 3D, streamed as you move: terrain relief with the map draped on it (your offline topo style, or the online layer, or satellite), detailed near you and coarser to the horizon, and buildings extruded from the offline map close up (heights from OSM `height` / `building:levels`). One finger pans, pinch zooms, two fingers turn and tilt; relief exaggeration ×1–4, buildings on/off, face north. |
| **Places** (Library → Places) | Your own points of interest: save from any pin or your location with a name, category (⭐ favourite, ⛺ camp, 💧 water, 🔭 viewpoint, 🅿️ parking, ⛽ fuel, 🍴 food, ⚠️ danger, 📍 other) and a note. Shown on the map (Layers → Saved places); tap one for route here, share, viewshed, edit, delete. The list searches names and notes, filters by category and sorts by distance, name or date. Import waypoints from any GPX file, share or export places as GPX. |
| **Library** | Routes & tracks, and Places. Library of saved routes and tracks (GPX files): show/hide on the map, elevation profile & stats, share as GPX, rename, delete, **import GPX** from other apps. **Record** a track with GPS; it keeps recording with the screen off. |
| **Party** | Live location sharing for a group (hiking, convoys). Create a party and invite with a QR code or link; members appear on the map with name, their chosen icon (initial; walker, hiker, runner, Nordic walker, skier, kayaker; cyclist, bicycle, e-bike, motorcycle, moped; car, 4×4, van, truck, caravan) on their colour (16 presets or any mix), age and how their update arrived. Works **online** (MQTT) and **off-grid over LoRa** through Meshtastic radios paired by Bluetooth; a phone with both acts as a **gateway** between them. SOS alerts, meeting point, route to a member, short messages, "hidden" mode. End-to-end encrypted. |
| **Sharing** | Share any point (long-press pin, your location dot, party members, meeting point) as a **link** (Google Maps + OpenStreetMap), a **GPX waypoint**, or open it in another app. Share routes as **GPX + links** (BRouter-Web rebuilds the route in any browser; Google Maps directions). MapAround also **opens** what others send: `geo:` links, Google/OSM/Apple/Waze/BRouter links, text with coordinates (also degrees-minutes-seconds), GPX files, mapsforge `.map` files, and party invites. |
| **Offline** | Browse the per-country catalog on download.mapsforge.org (continents → countries → regions), download with the system DownloadManager (background, resumable, Wi‑Fi-only option, storage check), update when the server has a newer file, delete, or import any mapsforge `.map` file (e.g. OpenAndroMaps). A 3 MB world overview map is used for zoomed-out views. Each map's **⋮** menu adds extras for its area: **contours & hill shading**, **offline routing** and **marked trails**; they keep downloading with the app in the background, with their progress in a notification. **Raster maps**: add TwoNav / CompeGPS **`.rmap`** files (e.g. scanned 1:50,000 topo sheets) and tile databases, **MBTiles** (from QGIS, GDAL, MOBAC, MapTiler…) and **SQLiteDB** (Locus, OsmAnd, RMaps); big files are read where they are, not copied. |
| **Layers** (on the map) | *Offline vector* (all installed maps rendered together) in a **style**: Topo, Classic, Osmarender, Biker, Motorider or one you **import** (a mapsforge render theme, `.xml` or `.zip`), each with its own **options** to show or hide parts of the map (Topo: land cover, buildings, boundaries, railways | **Layers** (on the map) | *Offline vector* (all installed maps rendered together) in the **Topo** or Classic style, with switches for **contour lines** and **hill shading**; a **marked hiking trails** overlay; or online raster sources: OpenStreetMap, OpenTopoMap, CyclOSM, Humanitarian, **Satellite** (Esri World Imagery), plus your own XYZ URL templates (`{s} {z} {x} {y} {-y}`). Online tiles are cached on disk. lifts, names, peaks | **Layers** (on the map) | *Offline vector* (all installed maps rendered together) in the **Topo** or Classic style, with switches for **contour lines** and **hill shading**; a **marked hiking trails** overlay; or online raster sources: OpenStreetMap, OpenTopoMap, CyclOSM, Humanitarian, **Satellite** (Esri World Imagery), plus your own XYZ URL templates (`{s} {z} {x} {y} {-y}`). Online tiles are cached on disk. springs, points of interest); switches for **contour lines** and **hill shading**; a **marked hiking trails** overlay. Or **online** raster sources: OpenStreetMap France and Germany, OpenTopoMap, CyclOSM, Humanitarian, Esri World Topo and Street, **Satellite** (Esri World Imagery), and national maps (swisstopo, basemap.at, Kartverket, IGN plan | **Layers** (on the map) | *Offline vector* (all installed maps rendered together) in the **Topo** or Classic style, with switches for **contour lines** and **hill shading**; a **marked hiking trails** overlay; or online raster sources: OpenStreetMap, OpenTopoMap, CyclOSM, Humanitarian, **Satellite** (Esri World Imagery), plus your own XYZ URL templates (`{s} {z} {x} {y} {-y}`). Online tiles are cached on disk. aerial, USGS Topo), plus your own XYZ URL templates (`{s} {z} {x} {y} {-y}`); **import and export** the list of your own. Online tiles are cached on disk. See [docs/map-styles.md](docs/map-styles.md). **Background of the offline map**: its own colours, **satellite imagery**, or one of your **raster maps**, with the vector map's roads, trails, contours and labels drawn on top (switchable). |
| **Android Auto** | The map on the car screen: the same offline/online map, contours, hill shading and trails as the phone, your position (follow mode), the planned route, shown tracks, the recording and party members. Speed, altitude, heading and party status at the top; **Satellite** toggle, re-centre, pan and zoom. "Navigate to" a place from the car routes there and starts **turn-by-turn navigation**: the next maneuver, time and distance left, voice through the car's speakers, and the trip on the car's own displays; Start / Stop / Mute on the car screen. |
| **Sensors** | **Compass:** rotation-vector heading with smoothing, magnetic or true north (declination from your position), calibration warning (tilt is in Motion). **Speed:** GPS ground speed (km/h, mph, knots, m/s) with its accuracy, heading of travel, walking pace, a trip counter (distance, max, average while moving, moving/total time) and a 10-minute chart; keeps the screen on. **Motion:** inclinometer for vehicles (pitch and roll drawn as a car from the side and behind, amber at 15°, red at 25°, **Set as level** for a phone in a holder); accelerometer (total g, x/y/z, movement without gravity, peak, a G-meter for braking/accelerating and cornering, 10-second chart); gyroscope (turn rate left/right, x/y/z °/s, chart); sensor model and range. **Barometer:** pressure (hPa / inHg / mmHg), barometric altitude, calibration by known elevation, GPS altitude or QNH, live trend chart with hPa/h. **Satellites:** live GNSS reception — fix state, satellites in use / in view, sky plot, per-satellite C/N₀ signal bars, constellation and band, TTFF, accuracy, speed, course. |

Map extras per area (all optional):

| Extra | Source | Size | Notes |
|---|---|---|---|
| Contours & hill shading | AWS Terrain Tiles | 30 m: ~26 MB per 1°×1°; 90 m: ~3 MB | Contour lines are generated on the phone. |
| Offline routing | brouter.de (BRouter) | 5°×5° blocks, 2–300 MB each | Without it, routes are calculated online by brouter.de. |
| Marked trails & cliffs | OpenStreetMap via Overpass API | ~0.5–8 MB per 1°×1° on the phone | Without it, trails are an online overlay (waymarkedtrails.org) and the Topo style has no cliffs (the mapsforge maps leave them out). |

Missing sensors (no barometer, no GPS, and so on) show an explanation instead of crashing.

## Build

Requirements: Android Studio (Ladybug or newer) **or** JDK 17 + Android SDK 35.

```bash
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :shared:testDebugUnitTest # shared logic on the JVM: catalog, contours, elevation, GPX, routing
                                    # geometry & navigation, trails, party, links, map tools & coordinates, tilt & trip
                                    # stats, places, search, RMAP, 3D tiles & buildings, mapsforge reader
./gradlew :app:testDebugUnitTest    # Android-side: map layers & theme, trail rendering, BRouter, MQTT
# optional live tests: MQTT_LIVE=1 (public broker), BROUTER_SEGMENTS=<dir with E35_N30.rd5>,
# VIEWSHED_DEM=<dir with N47E009.hgt> (viewshed on real terrain; PNGs to VIEWSHED_OUT),
# MAP_FILE=<a mapsforge .map> (reader vs mapsforge, offline name search on a real map)
./gradlew :app:installDebug         # install on a connected phone
```

The shared tests also run on the iOS simulator in GitHub Actions (`.github/workflows/ios.yml`,
macOS runners); its latest log is on the `ci-ios-shared` branch and a simulator screenshot of the
iOS app on `ci-screenshots`.

minSdk 26 (Android 8.0), targetSdk 36 (Android 16, as Google Play requires). Uses Kotlin, Jetpack Compose (Material 3) and
[mapsforge](https://github.com/mapsforge/mapsforge) 0.25 for map rendering. It has no Google Play
services dependency, so it also works on de-Googled phones.

## Topo style, contours & hill shading

The Topo style (`app/src/main/assets/themes/topo.xml`) is a hiking map:
- cream base, soft land cover;
- brown contours with elevation labels on the major lines;
- brown trails with a white casing, double-line tracks by grade;
- colour-coded roads, and peaks with their elevation.

The point-of-interest sections come from the mapsforge default theme. `topo.xml` is the only source
for the style: edit it directly, then check the result with the preview tool below. Sample renders
are in [`docs/topo-preview/`](docs/topo-preview/).

Its `<stylemenu>` makes it modular: land cover, buildings, boundaries, railways & lifts, names,
peaks & springs and points of interest are options in Layers, and each one switches a category of
rules (`cat="…"`). The same works for any style with a menu, built in or imported (Android). The
shared `StyleMenu` turns the choices into categories for mapsforge on Android and for the MapLibre
conversion on iOS. How to write or import a style, and the extra map data MapAround gives it
(contours, marked trails), is in [docs/map-styles.md](docs/map-styles.md).

How the terrain works (`app/src/main/java/app/maparound/terrain/`, plain Kotlin with no Android
dependencies):
- **`ContourDataStore`** is a virtual mapsforge data source. For each tile it samples the
  elevation grid, traces contours with marching squares (`Contours`) and returns them as ways
  tagged `contour=minor|major` + `ele`. The renderer then styles, smooths (`curve="cubic"`) and
  labels them like any other map feature.
  - Intervals adapt to zoom: 100/500 m at z11, 50/250 m at z12, 20/100 m at z13–14, 10/50 m from z15.
  - Contours are cut at the maps' coastline.
- **Hill shading** is mapsforge's own (`AdaptiveClasyHillShading`) on the same `.hgt` files.
- **`HgtProcessor` and `CoastMask`** run once per downloaded tile:
  - optionally reduce the tile to 90 m;
  - flatten the sea floor and the blocky land data that spills past the OSM coastline to 0 m,
    which mapsforge's sea rendering can't hide;
  - keep real below-sea-level land (Dead Sea and Jordan rift, Death Valley and similar basins).

### Previewing the style on a PC

`tools/topo-preview` renders PNGs with the same engine, theme and contour code, with no phone needed:

```bash
./gradlew :shared:compileDebugKotlinAndroid   # the shared code it uses (again after changing it)
./gradlew -p tools/topo-preview run --args="prep raw-hgt/ dem/ high maps/"
./gradlew -p tools/topo-preview run --args="app/src/main/assets/themes/topo.xml maps/ dem/ out/ alps:47.13:9.58:15"
# marked trails: convert an Overpass XML download, then add trails=<dir> when rendering
./gradlew -p tools/topo-preview run --args="trailprep N47E009.xml trails/N47E009.trails"
./gradlew -p tools/topo-preview run --args="app/src/main/assets/themes/topo.xml maps/ dem/ out/ trails=trails/ alps:47.13:9.58:13"
```

Get the raw `.hgt` files from `https://elevation-tiles-prod.s3.amazonaws.com/skadi/N47/N47E009.hgt.gz` and unzip them. In Git Bash
on Windows, prefix the commands with `MSYS_NO_PATHCONV=1`.

## Routes, tracks & trails

- **Route planner** (`routing/`): `PlannerSession` holds the waypoints and recalculates shortly after
  each change. A long press within 24 dp of a stop, or 16 dp of the line, picks that stop or a new
  one on that leg up (`RouteEdit`); while it's dragged, the legs next to it are drawn straight and
  the route is calculated once it's let go (one undo step). `RoutePlanner` uses the embedded **BRouter** engine (`OfflineRouter`, vendored in
  [`brouter/`](brouter/README.md), MIT) when the routisong data for every point is downloaded,
  otherwise the public brouter.de server; if an offline route runs off the downloaded area, it
  falls back to the server when online. Profiles: `hiking-mountain`, `trekking` (bike), `car-vario`.
  Hiking times use the DIN 33466 rule (4 km/h, 300 m/h up, 500 m/h down); bike and car times come
  from BRouter. **Line** mode measures straight lines, with heights sampled from the terrain data.
- **Navigation** (`routing/Navigator`, `NavigationSession`, `Guidance`): the turns come from
  BRouter's turn hints (`turnInstructionMode` 2 offline, `timode=2` online), plus each stop and the
  arrival; straight lines get turns from their shape at the stops. Each GPS fix is matched to the
  route near where you were (the direction of travel tells apart the two legs of an out-and-back),
  then the next maneuver is announced up to three times: far ahead, near ("In 300 metres, turn
  right", with "then …" if the next one follows closely) and at the turn. The distances depend on the
  profile and grow with speed. After 3–4 fixes 30–60 m off the route, a new route is calculated from
  where you are to the stops still ahead (offline, or online without routing data), at most every
  15 s. A foreground service (`NavigationService`) keeps the fixes coming with the screen off and
  shows the next turn in its notification; the voice is Android's TextToSpeech, as navigation
  guidance (music ducks). Navigation ends two minutes after you arrive.
- **Tracks** (`tracks/`): every route and track is a GPX 1.1 file in `files/tracks/`.
  `RecordingService` is a location foreground service; each fix (accuracy ≤ 40 m, ≥ 3 m from the
  last) is appended to `recording.journal` first. If Android restarts the service after killing
  the app, the same track continues; a cut-off recording is saved as a "(recovered)" track. Climb
  uses a 4 m hysteresis to ignore GPS noise.
- **Marked trails** (`trails/`): OSM `route=hiking|foot` relations are downloaded per 1°×1° cell from
  the Overpass API and stored compactly (`TrailCellIO`, ~10× smaller than the download).
  `TrailDataStore` feeds them to the renderer one line per waymark colour (from `osmc:symbol` or
  `colour`), offset side by side where routes share a path, as Waymarked Trails does. The same
  request also fetches `natural=cliff|arete|ridge|earth_bank` ways, which the mapsforge maps leave
  out; the data store gives them to the renderer as in OSM (from zoom 13, also with the trail lines
  off), so Topo's cliff rules draw them. Cells from before (format 1) are fetched again for them.

## Party location sharing

- **Party = 16-byte id + 32-byte key**, shared as `maparound://party/<code>?n=<name>` (QR code or
  link). Every message is **AES-256-GCM** encrypted end to end (`party/Protocol.kt`); a position
  update is 54 bytes. Message types: position (with speed, heading, battery, SOS flag), hello
  (name, colour, icon, Meshtastic node), SOS, meeting point, text, leave. Hello first carries a
  colour index and the node and name, as the first app versions read it (they get the nearest of
  their 8 colours); the icon code and the full RGB colour follow, so newer versions show both
  (`party/PartyLook.kt`). The icons are flattened to polygons there, so the list, the Android map
  and the iOS map draw the same shapes.
- **Transports** (`party/`), each a module the router drives:
  - **Online** (`MqttTransport`): MQTT over TLS, topics `maparound/v1/<party tag>/<sender>/<kind>`.
    Positions, names, SOS and meeting point are *retained*, so whoever joins sees everyone at
    once. For now it uses free public brokers (broker.emqx.io, test.mosquitto.org,
    broker.hivemq.com, tried in that order). They only ever see ciphertext, but they do see the
    party's topic, member ids, timing and IP addresses; move to your own Mosquitto for real use.
  - **LoRa** (`MeshtasticTransport`): talks to a Meshtastic radio over Bluetooth LE with the
    official phone API. Party packets travel as `PRIVATE_APP` (port 256) broadcasts on the radio's
    primary channel. Positions broadcast by other Meshtastic radios (e.g. a standalone tracker in
    another vehicle) are shown as grey "radio" markers.
- **Router** (`PartyEngine`, `PartySession`): drops duplicates (the same update can arrive online
  and by radio), keeps the newest position per member, and on a **gateway** phone relays packets
  between online and radio unchanged (they stay encrypted).
- **Send rates** (`SendPolicy`; above 15 km/h counts as driving):

  | | Online | LoRa |
  |---|---|---|
  | Hiking | every 10 s when moved ≥ 10 m | every 2 min when moved ≥ 40 m |
  | Driving | every 5 s | every 45 s when moved ≥ 200 m |
  | Standing still | every 60 s | every 10 min |
  | SOS | every 10 s | every 30 s |

  LoRa airtime is scarce and shared (e.g. EU868 duty-cycle limits); these keep a group of about
  10–20 radios usable.
- **Background**: `PartyService` is a location foreground service with a notification; it keeps
  sharing with the screen off and resumes after a restart. SOS and messages raise notifications.

### Setting up Meshtastic radios

1. Flash/configure each board with the official Meshtastic app or web flasher: same **region**
   (your country's band) and the same **primary channel** on every radio. The default channel
   works; party content is encrypted with the party key anyway.
2. Close the official Meshtastic app (only one phone app can be connected to a radio).
3. In MapAround: Party → Connections → **Pair** → pick the radio. Android asks for the radio's
   Bluetooth PIN: shown on its screen, or `123456` on boards without one.
4. Keep **Gateway** on for at least one phone that has mobile data, so radio-only members and
   online members see each other.

## Map tools

Open them with the ruler button on the map. Tap the map to place **A**, then **B**; further taps
move B (tap the A chip to move A instead). The tools use the downloaded terrain (contours & hill
shading extra); azimuth and the converter work without it.

- **Azimuth & range** (`tools/Sight.kt` → `Geodesy`): great-circle distance and initial bearing
  (true north), magnetic bearing from Android's World Magnetic Model (`GeomagneticField`), NATO mils
  (6400), back bearing, and with terrain the heights at A and B, height difference, vertical angle and
  slant range. The compass button places B from A by a bearing (degrees or mils, true or magnetic) and
  a distance (`2.5 km`, `800 m`, `3 mi`, `2 nm`).
- **Line of sight** (`LineOfSight`): samples the terrain along A→B at the terrain's own resolution
  (about 30 m), lowers far ground for Earth curvature with standard refraction
  (drop = d²/2R × (1 − 0.13), about 7 m at 10 km), and checks the sight line from the eye at A
  (default 1.7 m) to the target at B (default 1.7 m). It reports in sight / hidden, the blocking
  point and by how much, or the clearance. The map line is green where A sees the ground and red where
  it doesn't; the panel draws the profile with the sight line.
- **Radio link** (the Sight tool's switch; `LineOfSight.Fresnel`): the heights become antenna heights
  and the line runs antenna to antenna, with radio's effective Earth radius (k = 4/3, about 6 m drop
  at 10 km). Along the path it computes the first Fresnel zone, r = √(λ·d₁·d₂ / d), and how much of
  it the ground leaves clear; the verdict is clear (60% or more of the zone clear), partly blocked or
  blocked. Path loss is free space (32.44 + 20 log d[km] + 20 log f[MHz]) plus the tightest point as a
  single knife edge, J(v) of ITU-R P.526. The profile shows the zone as a lens, its 60% line dashed,
  and the ground green / amber / red by how far it reaches into it. Antenna gain, cable loss, trees
  and buildings aren't included.
- **Viewshed** (`Viewshed`): radial sweep from A, one ray to every edge cell of a grid of up to
  601×601 cells laid out in Web Mercator (so it lines up with map pixels). Each ray samples terrain at
  the same spacing as the line of sight, so both tools agree (checked against brute-force sight lines
  on real Alpine terrain: 98–99.8 % of cells match). Target height 0 = the ground itself; set it to
  e.g. 2 m to see where a person or a car would be visible. Typical time on a PC: 0.1–0.3 s.
- **Summits**: terrain data rounds peaks off (Säntis is 2449 m in the 1″ data vs 2502 m), and a
  point tapped on a summit often sits just below a nearby crest that hides half the view. When ground
  within 100 m of A is 3 m+ higher, the panel says so and offers **Move A up**.
- **Coordinate converter** (`tools/Coords.kt`): writes and reads
  - decimal degrees, degrees + minutes, degrees + minutes + seconds (N/S/E/W before or after, °′″ or
    plain spaces, `lat/lon` labels);
  - **UTM** and **MGRS** (WGS 84, 80°S–84°N with the Norway/Svalbard zone exceptions; MGRS at 1 m to
    100 km precision, with or without spaces). Krüger series: within 5 mm of PROJ;
  - **Israeli Transverse Mercator** (ITM, EPSG:2039) with EPSG's "Israel 1993 to WGS 84 (2)"
    7-parameter transformation (±0.5 m; the old 3-parameter shift is ~10 m off), around Israel only;
  - **Plus Codes** (Open Location Code, full or short codes near the map centre, e.g. `GM+MR Jerusalem`);
  - **Maidenhead** locators (amateur radio, 4–8 characters).

  Reference values in `CoordsTest` come from pyproj (EPSG), the `mgrs` library and Google's
  `openlocationcode`. The chosen format is shown on pins and copied by their copy button. Grid
  references shared to MapAround as text (e.g. an MGRS string in a message) open as a pin.

## Android Auto

MapAround is a **navigation-category car app** built with the Android for Cars App Library
(`car/`). Android Auto hands the app a drawing surface; `CarMapRenderer` puts a private virtual
display on it and shows an ordinary mapsforge `MapView` there (in a `Presentation`), with its own
tile caches and text scaled for the car screen. It follows the same live data as the phone (base map
and style, planned route, tracks, recording, party). Pan, pinch and double-tap come from the car's
touch screen; the template has zoom buttons, re-centre and a satellite toggle. While navigating
(`CarNavigation`), the template's routing card shows the next maneuver (arrows drawn from the same
shapes as the phone's, `TurnGlyph`), `NavigationManager` gets the trip for the car's cluster or
head-up display, and stopping navigation from the car stops it on the phone too.

**Using it with a sideloaded build** (Android Auto only lists Play Store apps unless you allow others):

1. On the phone open **Android Auto settings** (Settings → Connected devices → Connection
   preferences → Android Auto, or search for "Android Auto").
2. Scroll to **Version** and tap it about 10 times until it offers developer settings; accept.
3. ⋮ menu → **Developer settings** → enable **Unknown sources**.
4. Connect to the car (cable or wireless). MapAround appears in the car's app launcher.

**Testing on a PC without a car** (Desktop Head Unit, SDK package `extras;google;auto`):

1. Steps 1–2 above, then ⋮ menu → **Start head unit server**.
2. `adb forward tcp:5277 tcp:5277`
3. Run `<sdk>/extras/google/auto/desktop-head-unit.exe` (e.g. `-c config/default_720p.ini`).

The car map uses the phone's location permission (grant it once in the phone app). Release builds
only accept known Android Auto hosts (`hosts_allowlist_sample`); debug builds accept any host,
including the desktop head unit. Not yet tried in a real car: whether GPS keeps updating with the
phone locked depends on how the Android Auto host keeps the app running.

## Places and the 3D view

- **Places** (`places/`) are stored as GPX waypoints in `files/places.gpx` (name, `<desc>` note,
  `<ele>`, `<time>`, a Garmin-style `<sym>`, and `<type>maparound:camp` etc. for the exact
  category), so the file opens in other apps as it is. Imported waypoints from other apps get a
  category guessed from their `<sym>`/`<type>` (Campground → camp, Drinking Water → water, Summit →
  viewpoint…); duplicates (same name within 10 m) are skipped. A GPX shared to MapAround adds its
  waypoints to Places (a single waypoint just opens as a pin).
- **3D view** (`three/`) streams map tiles around the camera, like a 3D globe app:
  - **Which tiles**: a quadtree over Web Mercator tiles (`TileSelector`). A tile is split while the
    eye is closer than 1.4× its size, so ground near you gets zoom 15–17 tiles and the horizon
    zoom 11–12 ones; tiles farther than ~6× the camera distance or behind the camera are skipped
    (≤110 tiles). While a tile's children load, the tile itself (or an ancestor) is drawn, so
    there are no holes; coarse tiles load first, then the nearest detail.
  - **Each tile**: the map picture is drawn by mapsforge's own renderer from the offline maps (same
    topo style, contours, hill shading and trails as the 2D map, 512 px), or downloaded from the
    online layer you use (cached for a month in `cache/tiles3d/`); the satellite button switches to
    Esri imagery. The terrain is a 33×33 grid from the elevation data, with a "skirt" around the
    edge that hides cracks between tiles of different detail.
  - **Buildings** load for zoom-16 tiles within ~2.5 km: ways tagged `building` from the offline
    `.map` files, triangulated by ear clipping and extruded from the lowest ground under their
    corners to `height`, or `building:levels` × 3 m (+ `roof:levels`), or a typical height for the
    building type.
  - OpenGL ES 3 with 4× multisampling, anisotropic texture filtering, light from the north-west and
    distance fog; GPU memory is bounded (oldest unused tiles are dropped). One finger pans, pinch
    zooms, two fingers twist to turn and slide up/down to tilt, double-tap zooms in.

## Backgrounds, raster maps & search

- **Hybrid map** (`map/TopoTheme.kt`, `map/MapController.kt`): with a background chosen in
  Layers, the offline vector map is drawn transparently on top of it in a variant of its style
  with every area fill removed (land use, water, buildings) and a transparent background, so only
  roads, paths, marked trails, contours, symbols and labels remain; hill shading is off (the
  picture has its own light). "Roads, trails & labels on top" can be switched off to see the
  background alone. The 3D view and the Android Auto map use the same background.
- **Raster maps** (`raster/`): TwoNav / CompeGPS `.rmap` files (a JPEG tile pyramid plus the
  CompeGPS calibration text), read as laid out by QMapShack's reader: little-endian header,
  64-bit offsets, per-level tile tables. Picked files are opened in place with persistent
  read access (nothing is copied). The calibration's projection is taken from the text: UTM
  (`0,UTM,<zone>,<N|S>`), latitude/longitude (`1`), Mercator (`2`), German Gauss-Krüger zones
  (`114`, `117`, DHDN), and the **Israeli grid (ITM)** by name or when the points are in its
  range; an unknown projection with longitude/latitude points is placed approximately. The
  pixel-to-map transform is a least-squares affine fit through all calibration points (they
  don't have to be the corners). Tiles are reprojected to Web Mercator on worker threads: exact
  on a 16×16 grid per tile, interpolated in between, from the file's level that best matches the
  zoom, smoothed when zoomed in past the scan's resolution.
- **Tile databases** (`raster/TileDatabase`, `SqliteFile`): raster MBTiles (`tiles` table in TMS
  rows, or mbutil's `map` + `images` behind a `tiles` view) and SQLiteDB (`tiles` with x, y, z = 17 −
  zoom unless its `info` says `simple`, rows from the north unless `inverted_y`). They're read with
  MapAround's own read-only SQLite reader (schema, b-trees, overflow pages, index lookups) through
  the same positional reads as RMAP: Android's SQLite can't open a file picked through the system's
  file picker, and this way nothing is copied and the code can serve iOS later. Names, zooms and
  bounds come from the metadata, else from the tiles. Past the most detailed level, tiles are
  enlarged; up to two levels above the least detailed one, put together from smaller tiles. Vector
  MBTiles and elliptical-Mercator (Yandex) SQLiteDB files are refused with a message.
- **Search** (`search/`): as you type, saved places and coordinates (any format) appear at once,
  and the offline maps are read around the map centre (25 km; names in every language the map
  stores, accents and Hebrew vowel points ignored; pieces of the same river or street merged).
  Online search (Photon by komoot, results near the map first; Nominatim if Photon fails) runs
  only when you press search, as those free services ask.

## Sharing & opening links and files

- **Out** (`share/Sharing.kt`): points as text (name, coordinates, Google Maps and OpenStreetMap
  links), as a GPX `<wpt>` file, or as a `geo:` link to another app; routes and tracks as their
  GPX file plus text. Planned routes save their stops as a GPX `<rte>`, so the text can include a
  **BRouter-Web link** (`brouter.de/brouter-web/#…&lonlats=lon,lat;…&profile=…`) that rebuilds the
  same route in any browser, and a Google Maps directions link (up to 9 stops).
- **In** (`share/IncomingShare.kt`, `share/SharedLinks.kt`): `MainActivity` takes
  - `VIEW` of `geo:` URIs, map links (Google, OSM, Apple, BRouter-Web) and GPX files;
  - `SEND` of text (links, coordinates, the "name + short link" text Google Maps and WhatsApp send)
    and of GPX / `.map` files.

  Points open on the map as a labelled pin, shared routes open in the planner, GPX tracks are added
  to Routes and shown, a GPX with only waypoints shows its first point, `.map` files become offline
  maps. Google short links (`maps.app.goo.gl`) are resolved over the internet first.
- Map links are not verified App Links, so Android opens them in the browser by default. To open
  them straight in MapAround, enable *Settings → Apps → MapAround → Open by default → Open supported
  links*; sharing a link **to** MapAround always works.

## Code layout

Everything that doesn't need Android — the logic and all the Compose screens — lives in `shared/`
(Kotlin Multiplatform, Android + iOS) under the same package names. The app module keeps what only
Android can do (mapsforge rendering, services, DownloadManager, sensors, BLE, MQTT, BRouter, Android
Auto) and plugs it into the shared screens through small interfaces (`MapSurface`,
`DeviceSensors`, `OfflineData`, `PartyController`…; see `docs/IOS_PLAN.md`, which also describes
the iOS version this is for).

```
shared/src/
  commonMain/kotlin/app/maparound/
    ui/                    all Compose screens and the app frame (MapAroundRoot), theme, formatting
    map/                   MapSurface (the map as the screens drive it), BaseMap, markers, tile sources
                           and their import/export (SourceList), styles and their options (StyleMenu)
    map/maplibre/          the map on MapLibre (iOS): MapLibreSurface, the style made from topo.xml
                           (ThemeStyle), GeoJSON overlays, the in-app tile service (vector tiles,
                           contours, Terrarium elevation, fonts)
    tools/ sensors/ three/ search/ routing/ terrain/ raster/ places/ tracks/ trails/ share/ party/ data/
                           the shared parts of the app packages below: geodesy, sight & viewshed,
                           coordinates, tilt & trip, sensor interfaces, 3D tiles & meshes, search
                           engine, routing geometry, planner (dragging stops: RouteEdit) & navigation, turn arrows, contours, elevation tiles
                           (memory-mapped), HGT processing, RMAP, places, GPX & track library, trail
                           cells, links, party protocol, engine & state, Meshtastic codec, map
                           catalog, settings (Prefs) and the offline-data interfaces
    shared/mapsforge/      a Kotlin port of the mapsforge map reader (no rendering)
    shared/mvt/            mapsforge data → vector tiles (for MapLibre on iOS)
    shared/util/           what Java has and iOS doesn't: number formatting, ISO times, URL coding,
                           byte buffers, a streaming XML reader, Unicode NFD, a bit set
    shared/crypto/         HMAC-SHA-256, AES-GCM, secure random (javax.crypto / CommonCrypto)
    shared/net/            HTTP GET and downloads (HttpURLConnection / NSURLSession)
  androidMain/             the Android halves (files, memory mapping, crypto, NFD, HTTP)
  iosMain/                 the iOS halves, and the iOS app's services: IosApp (its objects), Core
                           Location & Core Motion sensors, URLSession map downloads, share sheet,
                           Files picker, MainViewController (the Compose app for Swift)
  commonTest/              tests that run on the JVM and the iOS simulator
iosApp/                    the iOS app shell (Swift, XcodeGen project): MLNMapView wrapper, marker
                           images, the local tile server; fetch-fonts.sh / fetch-symbols.sh get the
                           label fonts and map symbols before a build. Built and run in CI
app/src/main/java/app/maparound/
  MapAroundApp.kt          app init + tiny service locator (AppGraph)
  data/                    prefs, installed maps, remote catalog, map/terrain/trail downloaders, the
                           download service that keeps terrain & trail downloads going
  map/                     MapController (mapsforge MapView + layers), online tile sources, the styles
                           (AndroidMapThemes: built in and imported)
  terrain/                 elevation tiles, contour tracing, contour data source, coast mask
  routing/                 BRouter wrapper, online router, routing data, navigation service & voice
  tracks/                  GPX read/write, track library, GPS recording service
  trails/                  marked hiking trails: Overpass parsing, cell files, index, data source
  share/                   link parsing/building, sharing out, handling what other apps send in
  tools/                   map tools: geodesy, line of sight, viewshed, coordinate formats, tools session
  places/                  saved places (POIs): categories, GPX read/write, search, repository
  raster/                  raster maps: RMAP reader & calibration (projections, least-squares fit), reprojecting tile renderer,
                           tile databases (MBTiles, SQLiteDB) on a small SQLite file reader
  search/                  place search: name matching & ranking, offline map names, online (Photon / Nominatim)
  three/                   3D view: terrain mesh, building extrusion & triangulation, scene loader, OpenGL ES 3 renderer
  car/                     Android Auto: car app service, session, map renderer on the car surface, template screen, navigation
  party/                   party key & encryption, Meshtastic protobuf codec, engine, MQTT & BLE transports, service
  sensors/                 location, GNSS status, compass, motion (tilt, accelerometer, gyro), speed & trip, barometer
  ui/                      Android root (permissions, start-up), platform actions, Satellites tab, 3D view host
brouter/                   vendored BRouter routing engine (Java, MIT)
tools/topo-preview/        renders map previews on a PC (see Topo style, above)
tools/icon/                draws the app icon for the stores and iOS (docs/store/, the iOS assets)
```

Maps are stored in the app's external files folder (`Android/data/app.maparound/files/maps`) as
`<continent>__<country>[__<region>].map`. Downloads land as `*.map.part` and are renamed only after
they finish and pass a header check, so the renderer never opens a half-written file.

Terrain is stored next to the maps in `files/dem/` as processed `N46E007.hgt`-style tiles. One tile
is shared by every map that overlaps it. Tiles are prepared in `files/dem-tmp/`, which is cleared on
start-up. Routing data is in `files/brouter/segments4/` (the BRouter profiles are copied from the APK
to internal storage), marked trails in `files/trails/`, and routes & tracks as GPX in `files/tracks/`.
Online tiles are cached in the app's cache folder, one cache per source.

## Privacy & permissions

The app's privacy policy (also for the stores) is [docs/privacy.md](docs/privacy.md).

- **Location** (fine/coarse): used for the position dot, true-north declination, GPS altitude for
  barometer calibration, the Speed and Satellites tabs, the map tools, track recording, navigation
  and the Android Auto map. It stays on the device, except when navigation recalculates a route
  online (below).
- **Foreground service (location) & notifications**: while you record a track, navigate or the party
  is on; a notification is shown the whole time. No background location access is requested.
- **Foreground service (data sync) & wake lock**: while terrain or trails download, so they go on
  with the app in the background and the screen off; the notification shows their progress.
- **Voice**: navigation prompts use the phone's own text-to-speech engine.
- **Bluetooth** (scan & connect): only to find and talk to your Meshtastic radio.
- **Camera**: only to scan a party QR code.
- **Android Auto** (`androidx.car.app.NAVIGATION_TEMPLATES`, `androidx.car.app.ACCESS_SURFACE`): lets
  the app show its map on a car screen as a navigation app. Nothing is sent to the car maker or
  Google beyond what Android Auto itself handles.
- **Party sharing**: your position goes only to the party (encrypted with its key). Anyone you give
  the invite link or QR code to can see the party's positions; "Share my location" off hides you.
- **Internet**: used for map, terrain, routing-data and trail downloads, online tiles (2D and 3D),
  online route calculation, and online search (the search text and the map centre go to
  photon.komoot.io, or nominatim.openstreetmap.org as a fallback; offline search sends nothing).
  For online routes the tapped points are sent to brouter.de, and so is your position when
  navigation recalculates a route without offline routing data. There are no analytics, accounts
  or tracking.

## Known limitations

- **On iPhone, keep MapAround open while terrain downloads** (Android goes on in the background),
  and the offline maps have the Topo style only (importing styles is Android-only).
  Restarting a download from the map's ⋮ menu fetches only the missing areas.
- **Terrain coastlines follow the maps installed when it was downloaded**; after adding a
  neighbouring coastal map, re-download that area's terrain.
- **Contours and offline trails need the Topo style** or an imported style that draws them (the
  others show hill shading and the online trail overlay). Contours start at zoom 11.
- **Navigation has no street names or lanes**: BRouter's data has neither.
- **English only**, voice prompts included.
- **Line of sight, radio links and viewsheds use bare ~30 m terrain**: no trees or buildings, and
  rounded peaks; radio loss is a single-obstacle estimate. Treat them as a guide.
- **Off-grid party sharing needs Meshtastic radios**; there is no phone-to-phone Bluetooth yet.
- **Not yet tried for real**: navigation on the road, Android Auto in a car, and the LoRa transport
  with real radios.
- **Raster maps: `.rmap`, MBTiles and SQLiteDB only** (no GeoTIFF, OziExplorer or KMZ yet), with no
  rotated, Lambert or Cassini `.rmap` maps or old Israeli grid;
  datums other than DHDN and Israel 1993 are treated as WGS 84.
- **Overpass, brouter.de and public MQTT brokers are free community servers**: they can be slow,
  busy or down, and a published app with many users needs its own.

## Data sources & licences

- **Offline maps**: © OpenStreetMap contributors (ODbL), converted by the mapsforge project.
- **Terrain**: [Terrain Tiles](https://registry.opendata.aws/terrain-tiles/) on AWS Open Data, © Mapzen and its sources (SRTM, GMTED, ETOPO1 and others; see the dataset's attribution page).
- **Online tiles** (list and placeholders in [docs/map-styles.md](docs/map-styles.md)). The app sends an identifying User-Agent and never bulk-prefetches; the 3D view with an online layer loads the tiles it shows (more than a flat map does) and caches them for a month. For a published app with many users, switch to your own or a commercial tile provider.
  - **OpenStreetMap France / Germany, CyclOSM, Humanitarian** (OSM France), **OpenTopoMap** (CC-BY-SA): community servers with fair-use limits. The standard tile.openstreetmap.org server isn't used: it refuses this app under its [tile usage policy](https://operations.osmfoundation.org/policies/tiles/), which asks for contact details in the User-Agent.
  - **Esri World Topo, World Street, World Imagery**: check Esri's terms before any commercial or large-scale use.
  - **National open data**: swisstopo (© swisstopo, free use with attribution), basemap.at (CC BY 4.0), Kartverket (CC BY 4.0), IGN Géoplateforme (Etalab open licence 2.0), USGS The National Map (public domain).
- **Map styles**: Topo's point-of-interest sections and the Classic, Osmarender, Biker and Motorider styles come from [mapsforge](https://github.com/mapsforge/mapsforge) (LGPL-3.0). Imported styles keep their authors' licences.
- **Party**: Meshtastic phone API and protocol definitions ([meshtastic/protobufs](https://github.com/meshtastic/protobufs), GPL-3.0; a minimal encoder for the messages used is in `party/MeshProto.kt`). MQTT via [Eclipse Paho](https://github.com/eclipse-paho/paho.mqtt.java) (EPL-2.0 / EDL-1.0). QR codes via [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded) (Apache-2.0).
- **Android Auto**: [Android for Cars App Library](https://developer.android.com/training/cars/apps) (`androidx.car.app`, Apache-2.0).
- **Magnetic declination**: the World Magnetic Model built into Android (`GeomagneticField`).
- **Online search**: [Photon](https://photon.komoot.io) by komoot and [Nominatim](https://nominatim.org) (OpenStreetMap data, ODbL; fair-use policies: no bulk queries, a request at a time).
- **RMAP format**: as read by [QMapShack](https://github.com/Maproom/qmapshack)'s `CMapRMAP` (GPL-3.0); MapAround's reader is its own Kotlin implementation.
- **Routing**: [BRouter](https://github.com/abrensch/brouter) (MIT) and its data and server at brouter.de, from OpenStreetMap (ODbL).
- **Marked trails**: © OpenStreetMap contributors (ODbL), downloaded through the [Overpass API](https://wiki.openstreetmap.org/wiki/Overpass_API) (fair-use policy). The online overlay is from [Waymarked Trails](https://waymarkedtrails.org); I couldn't find written tile-usage terms, so check with them before any large-scale use.
