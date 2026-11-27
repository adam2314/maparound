# The 3D view on iOS: Metal port (plan)

Status (2026-11-27): planned, not started. The iPhone's 3D button shows a placeholder ("The 3D
view comes to iPhone in a later version", `shared/src/iosMain/.../ui/MainViewController.kt`).
Part of phase 4 in [IOS_PLAN.md](IOS_PLAN.md). Chosen over running the web app's MapLibre GL JS
3D in a web view: native, the same look and behaviour as Android's, offline the same way.

## Goal

The Android 3D view on the iPhone: terrain on the whole globe, the map draped on it (offline
style, satellite, raster backgrounds, the grid), buildings close up, sky and haze, names standing
up (places, peaks, huts), markers (my position, the parties'), the my-position button, the same
gestures, the intro (easing in from the 2D map) and the way back, and the relief's settings
(exaggeration, buildings, satellite).

## What there is

**Shared already (commonMain)**
- The screen and its contract, `ui/Scene3DScreen.kt`: `Scene3DScreen` (buttons, compass, scale,
  settings, credits, my-position button), `Scene3DViewArgs` (what the platform view gets),
  `Scene3DControl` (what it gives back: camera, status, zoom, `faceNorth`, `lookAt`, `canLookAt`,
  `setMarkers`, `leave`), `Scene3DSetup`.
- `three/`: `Globe.kt` (WGS84, `GlobeCamera`, `Mat4`), `Tiles.kt` (`TileId`, `TileSelector`,
  `TileMesh`, `TileLook`: which picture a tile wears and its fade), `Mesh.kt` (`Buildings`:
  footprints extruded and triangulated), `Relief.kt` (`ReliefTiles`: web relief tiles decoded and
  sampled), `Label3D`, `Marker3D` (image names as the 2D map's, `MarkerIcons`), `Camera3D`.
- Elevation (`tools/Elevation`, `terrain/DemIndex`, memory-mapped 1° tiles on both platforms).

**Android only (`app/.../three/`, about 2,300 lines)**
- `Scene3DView.kt` (1,271): the GL view and its gestures; `Renderer3D`: the frame (gestures
  applied, animation, ground settling, intro), which tiles to want (`wishes`, `marginWishes`,
  `introWishes`), upload and eviction, drawing tiles (two pictures cross-fading), sky, buildings;
  the GLSL shaders.
- `TerrainEngine.kt` (283): background queues for meshes, pictures, buildings and labels;
  `groundAt`.
- `TileTextures.kt` (324): tile pictures drawn by an offscreen MapLibre map with the same style
  (`MapStyle.build(forTextures = true)`), with a world fallback, a disk cache, the grid drawn on.
- `OnlineRelief.kt` (136): relief tiles from the web where no elevation is downloaded.
- `LabelOverlay.kt` (291): names and markers placed on the screen (projected, beyond the horizon
  left out, behind ridges hidden or faint, the most important first) and drawn over the GL view.
- `ui/Scene3DAndroidView.kt`: wiring (engine, textures, relief, intro loading before the view).

**iOS spike (phase 0 c)**: `iosApp/MapAround/TerrainView.swift` + `Terrain.metal` draw one tile
meshed by the shared code (`iosMain/.../three/TerrainTileMesh.kt`), draped with an
`MLNMapSnapshotter` picture. Swift implementing Kotlin interfaces is the app's pattern already:
`MapLibreMap.swift` is the `NativeMapView`, made by a factory `MainViewController` takes (`newMap`).

## Steps

1. **Move what isn't drawing into commonMain**, Android unchanged for the user, with JVM tests:
   - From `Renderer3D`: camera, motion and gesture maths (`turnTo`, pan, fling, `zoomTo`,
     `settleGround`, the intro), the tile wishes, the eviction policy, the status line, as a
     `Scene3DCore` that tells a small GPU interface what to upload, keep and draw each frame.
   - `TerrainEngine`'s queues and loading, the picture type behind an interface (Android
     `Bitmap`, iOS `CGImage` or texture bytes).
   - `LabelOverlay`'s layout (projection, horizon, ridges, priorities, markers first) as a
     shared `LabelLayout` returning what to draw where; each platform only draws it.
   - `OnlineRelief` (it needs `httpGet` and `fileSystem`, both common).
2. **Metal renderer (Swift)**: an `MTKView` whose renderer implements that GPU interface. Ports
   of the three GLSL programs to Metal (terrain: two pictures cross-fading, exaggeration, light,
   haze; sky; buildings), index buffers per grid size, texture upload with mipmaps and
   anisotropy, depth, MSAA. Start from the spike's pipeline.
3. **Tile pictures**: `MLNMapSnapshotter` per tile with the same style JSON, through the app's
   tile server (`TileServer.swift`, the `maparound://` scheme); the disk cache and fallback logic
   from step 1; the grid drawn with Core Graphics.
4. **Names and markers**: a transparent `UIView` over the `MTKView` drawing what `LabelLayout`
   placed: text with Core Text (Hebrew and Arabic too), markers with `MarkerImages.swift` (the
   2D map's marker images, by the same names as `Marker3D.image`).
5. **Gestures**: UIKit recognizers (pan with fling, pinch, rotate, two-finger tilt, double tap)
   into the shared core's gesture queue, as Android's `Scene3DView` feeds `Renderer3D`.
6. **Wire it up**: a Swift class implementing `Scene3DControl` (`canLookAt = true`,
   `setMarkers`, `leave` with its easing), made by a factory `MainViewController` takes (as
   `newMap`), hosted in a `UIKitView` in place of the placeholder; `firstFrameLater` and
   `onFirstFrame`, and loading the intro's tiles before the view shows, as Android does.
7. **Check**: CI on the simulator (screenshots of the intro, a turned view, the satellite, names
   and markers); on an iPhone through TestFlight for speed and memory.

## Notes

- Party markers need the party on iOS (phase 5); my position works from step 4.
- Builds and Metal debugging only on macOS (CI or a Mac); step 1 can be done and tested here.
- Watch: memory for tile pictures (iOS kills big apps sooner), `MLNMapSnapshotter` speed per tile
  (Android draws tiles with an offscreen map; the snapshotter may be slower), Kotlin/Native
  threading for the background queues.
