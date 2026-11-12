# Changelog

## 0.1.0 (not released yet)

### Navigation
- **Turn-by-turn navigation.** Plan a route and tap **Start**. You get the next turn and its
  distance, time left, arrival time and voice prompts (which you can mute). If you leave the
  route, a new one is calculated. Navigation keeps going with the screen off.
- **Android Auto.** "Navigate to" a place from the car starts the same guidance on the car screen,
  with the trip on the car's own displays.

### Planning routes
- **Reshape a route by dragging.** Hold a stop and drag it to move it, or hold the route line and
  drag to add a stop there. The route is recalculated when you let go, and Undo takes back the
  whole drag.

### Maps
- **Map styles.** Choose Topo, Classic, Osmarender, Biker or Motorider, or import your own
  mapsforge style (`.xml` or `.zip`). Styles that have options get switches in Layers. In Topo you
  can hide land cover, buildings, boundaries, railways and lifts, names, peaks and springs, or
  points of interest. See [docs/map-styles.md](docs/map-styles.md).
- **A world map built in (Android).** Zoomed out, and around the countries you've downloaded,
  the offline map now shows coastlines, lakes, big rivers, country borders and the names of
  countries, seas, cities and towns (from Natural Earth), even with nothing downloaded. Before,
  it was blank beyond your maps. Downloaded maps keep their own, exact data. The 3 MB World
  overview download isn't needed any more; you can delete it.
- **Country borders at every zoom.** In Topo, disputed borders and lines of control are drawn
  with short dashes.
- **Cliffs and ridges** now show on the Topo map. They come with the Marked trails download; tap
  it again for areas you downloaded before.
- **More online maps:** OpenStreetMap Germany, Esri Topo and Street, and national maps for
  Switzerland, Austria, Norway, France and the US. OpenStreetMap France is now the default,
  because the standard OpenStreetMap tile server doesn't allow this app.
- **Your own tile sources** can be exported as a list and imported on another phone.
- **More raster map formats:** MBTiles (from QGIS, GDAL, MOBAC and others) and SQLiteDB (from
  Locus, OsmAnd and RMaps), alongside `.rmap`. Large files are read where they are, not copied.
- **Background downloads.** Terrain and trail downloads keep going when you leave the app or turn
  the screen off, with progress in a notification (Android).

### Party
- **Invites are web links** (`https://adam2314.github.io/maparound/party#…`), so messaging apps
  make them tappable and the QR code opens from the phone's camera. The page opens MapAround, or
  Google Play if it isn't installed; the party's code never reaches the website. Older
  `maparound://` invites still work.
- **Choose how others see you:** your initial or an icon (walker, hiker, runner, skier, kayaker,
  cyclist, e-bike, motorcycle, car, 4×4, van, truck and more), in any colour. Friends on older
  versions see your initial in the nearest of their colours.

### Map tools
- **Radio link check.** In the line-of-sight tool, switch on **Radio link** and set the antenna
  height at each end and the frequency: a LoRa band, 2.4 GHz, 5.8 GHz, VHF, PMR, or any value.
  The profile then shows the Fresnel zone, the space around the direct line that a radio signal
  needs clear. You get a verdict (clear, partly blocked or blocked) and an estimate of the signal
  loss.

### Good to know
- On iPhone, map styles are limited to Topo and its options, and raster maps and background
  downloads aren't available yet.
- Voice prompts are in English only, and navigation doesn't give street names or lanes.
- Navigation and Android Auto haven't been tried on the road yet; feedback is welcome.
