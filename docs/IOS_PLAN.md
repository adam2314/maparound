# MapAround for iOS: plan

Goal: an iPhone version with the same features as the Android app, except the **Satellites**
(GNSS reception) tab, which iOS has no API for. Same offline data (mapsforge `.map` country maps,
terrain, trails, routing data, RMAP raster maps), same GPX files, and party members on Android
and iOS see each other.

## Decisions (2026-11-11)

- **Builds**: GitHub Actions macOS runners (project on GitHub; iOS app built and tested on the
  simulator in CI). No local Mac.
- **UI**: shared Compose Multiplatform screens for Android and iOS.
- **Apple Developer Program**: not yet. Work on the simulator in CI until installing on the
  iPhone; join before TestFlight and before requesting the CarPlay entitlement.

## Progress

- **Phase 0**: CI builds and tests on the iOS simulator and publishes a screenshot to the
  `ci-screenshots` branch. Spike (a) works: the Kotlin mapsforge reader → vector tiles →
  MapLibre draws Liechtenstein offline. Spikes (b) Compose + MapLibre and (c) Metal are still open.
- **Phase 1 (shared core)**: the pure core lives in `shared/src/commonMain` under its old
  package names (so Android code barely changed): coordinates, sight & viewshed, tilt & trip,
  3D tiles & meshes, routing geometry, contours, elevation tiles (memory-mapped on both) & HGT
  processing, RMAP, places, GPX, trail cells (same binary files as before), links, party
  protocol & engine, Meshtastic protobufs, map catalog & names, and the search engine (offline
  names through the ported reader, checked name-for-name against mapsforge; online URLs and JSON).
  JVM-only APIs replaced by `app.maparound.shared.util` (formatting identical to
  `String.format`, ISO times, URL coding, bytes, streaming XML, NFD, bit set) and `shared.crypto`
  (javax.crypto on Android; CommonCrypto + a common GCM on iOS, checked against published test
  vectors). Tests run on the JVM and the iOS simulator.
- **Still Android-only, by design until later phases**: sessions that hold app state
  (`ToolsSession`, `PlannerSession`, `PartySession`; they move with the UI in phase 2 behind
  platform interfaces), downloads and HTTP (phase 3), map layers & rendering (mapsforge; iOS uses
  MapLibre), BRouter (phase 6), sensors, BLE and MQTT transports (phases 3 and 5).

## 1. What changes on iOS

| Android today | Why it can't go to iOS as is | iOS answer |
|---|---|---|
| mapsforge renders the map (`MapView`, Topo theme, hill shading) | mapsforge is Java/Android only | **MapLibre Native iOS** renders; our own code turns the same `.map` files into vector tiles (§3) |
| BRouter (Java) for offline routes | no JVM on iOS | online routes (brouter.de) first; offline BRouter later, transpiled with J2ObjC or ported to Kotlin (§6, phase 6) |
| Jetpack Compose UI | Android only | **Compose Multiplatform** (stable on iOS since 1.8): the same screens, shared |
| OpenGL ES 3D view | GL is deprecated on iOS | the same tile/mesh code, drawn with **Metal** |
| Foreground services (recording, party) | not on iOS | background location mode (`allowsBackgroundLocationUpdates`), BLE background mode |
| Android Auto | — | **CarPlay** (needs Apple's CarPlay navigation entitlement) |
| DownloadManager | — | `URLSession` background downloads |
| `geo:` links, intent filters | — | Share Extension (links/text from other apps), document types (`.gpx`, `.map`, `.rmap`), `maparound://` URL scheme |
| GNSS satellite list | no iOS API | dropped (agreed) |

## 2. Architecture

```
MapAround/
  shared/            Kotlin Multiplatform (Android + iOS)
    commonMain/      all logic + all Compose screens
    androidMain/     platform services (Android)
    iosMain/         platform services (iOS)
  androidApp/        today's app shell: activity, services, mapsforge map, Android Auto, BRouter
  iosApp/            Xcode project (Swift): app entry, MapLibre map, Metal 3D, CarPlay, Share Extension
  brouter/           unchanged (Android only until phase 6)
```

- **Shared logic** (already plain Kotlin and unit-tested, so it moves almost as is): coordinates &
  converter, line of sight & viewshed, contours, terrain processing, GPX, places, search ranking,
  party protocol & Meshtastic protobufs, trails, tilt & trip stats, link parsing, 3D tile
  selection, meshes & buildings, RMAP reader & calibration.
- **Shared UI**: today's Compose screens (Map panels, Library, Places, Party, Offline, Sensors,
  Tools, Search, 3D controls) move to `commonMain`. Android-only calls behind small interfaces:
  file picker, permissions, share sheet, haptics, keep-screen-on.
- **The map** is a shared interface the screens talk to, `MapSurface`, with what `MapController`
  already offers: base layer & background, position, pin, planned route, tracks, recording,
  places, party markers, tool overlays (lines, viewshed image, ring), camera, taps.
  - Android: today's `MapController` (mapsforge) implements it, unchanged in behaviour.
  - iOS: a Swift class implements it with MapLibre, embedded in Compose (`UIKitView`).
- **Platform services** behind interfaces (implemented per platform): location & heading,
  motion (accelerometer, gyro, gravity), barometer, BLE (Meshtastic), MQTT, downloads, files &
  security-scoped bookmarks, crypto (AES-GCM: javax.crypto / CryptoKit), background work.

### Alternatives considered

- **SwiftUI rewrite + shared logic only**: most native feel, but every screen written twice and
  kept in sync forever. Not worth it when the whole UI is already Compose.
- **Port mapsforge's renderer to Kotlin/Skia**: identical look on both platforms, but a large,
  risky port of a renderer that MapLibre does better on the GPU (smooth zoom, rotation, labels).
- **Move Android to MapLibre too**: one map engine, but rewrites a map that works well today.
  Possible later once the iOS map is proven; not part of this plan.

## 3. The map on iOS (MapLibre + our data)

MapLibre draws vector tiles (MVT) with a JSON style. A small **on-device tile service** in shared
Kotlin produces everything MapLibre asks for, offline, from the files the Android app already
uses; tiles are served under a custom URL scheme to MapLibre (a `URLProtocol` on its network
configuration; a localhost server as a fallback):

| Source | Made from | Served as |
|---|---|---|
| `maparound://osm/{z}/{x}/{y}` | mapsforge `.map` files, read by a **Kotlin port of the mapsforge map reader** (header, index, ways & POIs per zoom interval; ~3k lines of Java, no rendering code) | MVT, tags kept as feature properties |
| `maparound://contours/…` | terrain tiles via our `Contours` code | MVT lines with `ele` |
| `maparound://dem/…` | terrain tiles | Terrarium-encoded PNG → MapLibre `hillshade` layer |
| `maparound://trails/…` | trail cells via `TrailCellIO` | MVT lines with colour & offset |
| `maparound://raster/{id}/…` | RMAP via `RasterRenderer` (decode with ImageIO) | PNG |
| satellite / OSM / OpenTopoMap | online | normal raster sources (MapLibre caches) |

- **Topo style**: `topo.xml` translated to a MapLibre style JSON (same colours, widths per zoom,
  labels, symbols). Hybrid = the same style without fill layers over a satellite or RMAP source.
- **Fonts & icons**: glyph PBFs (Noto Sans incl. Hebrew/Arabic) and a sprite sheet built from
  the theme's SVG symbols, bundled in the app (works offline).
- **Overlays** (routes, tracks, recording, pins, places, party, tool lines): GeoJSON sources;
  viewshed: an image source on its four corners.
- **Search**: the reader's `readNamedItems` port gives the same offline name search.
- **3D view**: shared `TileSelector`/`TileMesh`/`Buildings`; textures from MapLibre's
  `MLNMapSnapshotter` (same style) or online tiles; drawn with Metal. (MapLibre Native has no 3D
  terrain of its own as far as I know, so we keep ours.)

## 4. Feature by feature

| Feature | Shared | iOS-specific work |
|---|---|---|
| Offline maps catalog & downloads | catalog, file naming, header check | `URLSession` background downloads |
| Map, layers, Topo/Classic, contours, hill shading | tile service, style choices | MapLibre view, style JSON |
| Background: satellite / RMAP + hybrid | RMAP reader, renderer | file picker + security-scoped bookmark (big files stay in place) |
| Route planner, profiles, elevation profile | planner session, Geo | online BRouter first; offline in phase 6 |
| Record tracks (screen off) | recorder logic, GPX | background location mode, Live Activity optional |
| Library: routes & tracks, Places | all | Files app import, share sheet |
| Marked trails | Overpass parsing, cells | — |
| Party (MQTT + Meshtastic LoRa, gateway, SOS) | engine, protocol, crypto interface | MQTT client (KMP library or CocoaMQTT), CoreBluetooth, CryptoKit |
| Map tools: azimuth, sight, viewshed, coordinates | all | declination from `CLHeading` / WMM |
| 3D view | tile selection, meshes, buildings | Metal renderer, snapshotter textures |
| Search (offline + online) | all | — |
| Sharing out / in | link parsing & building | Share Extension, document types, URL scheme |
| Sensors: compass, speed, motion, barometer | tilt, trip | Core Location heading, Core Motion, `CMAltimeter` (pressure, relative & absolute altitude) |
| Satellites tab | — | **not on iOS** |
| Android Auto → CarPlay | renderer status text | `CPMapTemplate` + MapLibre in the CarPlay window; entitlement |

## 5. Making the Android code multiplatform

Most code is already free of Android, but uses JVM-only APIs. Replacements:

| JVM API used today | Multiplatform replacement |
|---|---|
| `java.io.File`, `RandomAccessFile`, mmap, `ByteBuffer` | Okio (`FileSystem`, `FileHandle`); mmap via `expect/actual` |
| SAX (`javax.xml.parsers`) for GPX, places, Overpass | xmlutil or a small pull parser of our own |
| `org.json` (Android) | kotlinx.serialization |
| `HttpURLConnection` | Ktor client |
| `javax.crypto` AES-GCM | `expect/actual` (javax.crypto / CryptoKit) |
| `java.text.Normalizer` (search) | `expect/actual` (NFD on both) |
| `java.time` | kotlinx-datetime |
| `String.format` | small formatting helpers |
| mapsforge classes in shared code (`BoundingBox`, `Tile`, `MapFile`) | our own types + the ported reader |

The Android app keeps mapsforge for drawing and calls the same shared code; its behaviour must
not change. All existing unit tests move with the code and run on JVM **and** the iOS simulator.

## 6. Phases

Each phase ends with something usable and all tests green on both platforms.

0. **Set-up & spikes**: git + GitHub, macOS CI (GitHub Actions) building the iOS app and running
   tests on the simulator; KMP skeleton. Spikes: (a) mapsforge reader → MVT → MapLibre for one
   country (speed, memory, Hebrew/Arabic labels); (b) Compose Multiplatform screen with a
   MapLibre `UIKitView` inside; (c) Metal drawing one 3D tile.
1. **Shared core**: move the pure code to `shared/commonMain` with the replacements above;
   Android unchanged for the user.
2. **Shared UI**: move the Compose screens; Android keeps working through `MapSurface` on
   mapsforge.
3. **iOS MVP**: map from downloaded `.map` files with the Topo style, terrain (contours, hill
   shading), location & follow, pins, layers, downloads, places, search, coordinate converter,
   sharing, compass, speed, motion, barometer.
4. **Routes & outdoors**: planner (online routing), track recording in the background, GPX
   library, marked trails, map tools (sight, viewshed), backgrounds (satellite, RMAP), 3D view.
5. **Party**: MQTT, Meshtastic over BLE (background), gateway, SOS; Android ↔ iOS interop test.
6. **Offline routing & car**: BRouter on iOS (J2ObjC of its pure-Java core, or a Kotlin port),
   CarPlay, polish, TestFlight.

## 7. What's needed outside the code

- **A Mac or macOS CI.** iOS apps can only be built with Xcode on macOS. This Windows PC can write
  and test the shared code; builds would run on GitHub Actions macOS runners (or a Mac / cloud Mac).
- **Apple Developer Program** (99 USD/year) to install on your iPhone beyond 7-day test installs
  and to use TestFlight.
- **CarPlay navigation entitlement**: requested from Apple; approval isn't guaranteed.
- **App Review** (only if published): background location for party sharing and the use of
  community tile servers need clear justifications.

## 8. Risks

- **On-device vector tiles**: converting `.map` data to MVT fast enough for smooth panning. Spike
  first; cache converted tiles on disk if needed.
- **Style parity**: `topo.xml` → MapLibre style is manual work; the look will be close, not
  identical.
- **Compose on iOS feel**: scrolling, text input and back gestures differ slightly from native
  apps; acceptable for this app, revisit per screen if needed.
- **BRouter on iOS**: J2ObjC adds build complexity; a Kotlin port is cleaner but larger.
- **Background limits**: iOS can suspend the app more aggressively than Android; party updates
  over MQTT rely on location wake-ups.
