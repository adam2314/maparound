# Changelog

## 0.5.0 (not released yet)

### Languages
- **MapAround in Hebrew (עברית)**, right to left: every screen, message, notification, unit and
  date, the turn instructions and the voice prompts (with a Hebrew voice on the phone), place
  types, Android Auto, and the web app. It follows the phone's language, or choose it in
  **Settings** (the gear in the Library): the phone's language, English or עברית. Wikipedia and
  place names follow it too.

### Maps
- **MapAround's own offline maps.** The download list is now MapAround's build of OpenStreetMap,
  updated every month, with marked hiking trails, cliffs and ridges, path difficulty (SAC scale),
  trail visibility, surfaces, 4×4-only tracks, fords, glaciers, huts and water points built in.
  The Marked trails download isn't needed for these maps. Maps from mapsforge.org you already have
  are offered as updates. The biggest whole countries (France, Germany, Italy, Great Britain,
  Japan…) need funding to be built; their regions can be downloaded. See
  [my-pipeline](https://github.com/adam2314/my-pipeline).
- **The mapsforge.org maps are still there in the transition.** Regions MapAround hasn't built yet,
  and the biggest whole countries, can be downloaded from mapsforge.org as basic maps (without the
  marked trails); the switch "Basic maps from mapsforge.org" shows their whole list.
- **Terrain when it's needed.** Line of sight, the viewshed and the 3D view now download the
  elevation data their area is missing by themselves (a few tiles, not on mobile data with Wi‑Fi
  only on), show the progress, and carry on when it's in; no more trip to Offline maps first.
  So does the map for its contour lines and hill shading, where your offline maps are, once you
  zoom in (from zoom 10).
- **Smooth 3D.** The 3D view tilts in from the map as it opens and eases back to a flat, north-up map
  as it closes; the map then shows where you were looking in 3D.
- **A zoom slider with the scale.** The map's button column has + and −, and between them a
  slider: drag it to zoom, tap it to jump there. Its thumb shows the scale now, a round distance
  in metres or kilometres above and in feet or miles below, each with a bar as long as it is on the
  map. It replaces the scale bar in the map's corner. On Android, iPhone and the web.
- **More of the 3D view.** The relief and buildings settings are behind a button (next to
  satellite), so the view is clear, and the 3D view now credits what it shows, as the map does:
  the map's sources, the imagery when it's on, and the relief data.
- **Buildings on slopes in 3D.** A building on a hillside sank into the slope on its uphill side.
  Now its roof is its height above the highest corner and its walls go down into the ground below
  the lowest, following the relief as you raise it. On Android and in the web app.
- **A better 3D view, on the whole globe.** Zoom out and the Earth hangs in space with its
  glowing atmosphere; zoom in down to a hiking path. The map on the terrain is drawn like the 2D
  map, names stand up over the mountains (peaks with their heights, villages, huts; hidden
  behind ridges), there's a sky with haze toward the horizon, new ground fades in, and a flick
  keeps the map gliding.
- **The map is drawn on the GPU (MapLibre), on the phone and in Android Auto.** Smooth zooming
  and turning, crisp labels at every zoom, and a compass when the map is turned. The offline maps,
  styles, terrain, trails, raster maps and backgrounds are the same as before; the app makes the
  map's tiles from them on the phone. Turn the map with two fingers.
- **A world map built in (Android).** Zoomed out, and around the countries you've downloaded,
  the offline map now shows coastlines, lakes, big rivers, country borders and the names of
  countries, seas, cities and towns (from Natural Earth), even with nothing downloaded. Before,
  it was blank beyond your maps. Downloaded maps keep their own, exact data. The 3 MB World
  overview download isn't needed any more; you can delete it.
- **No offline map here? The map says which one to get.** Looking at a place none of your maps
  covers, the map names the region whose map is made for it (a state of a big country, else the
  country: "Bayern, Germany"), and *Get map* opens the download list right there. On Android
  and the web.
- **A legend for every map.** Layers → Legend: for the offline styles, made from the style itself
  (roads, track grades, paths, marked trails, water, land, terrain, symbols, exactly as drawn,
  with the style options that are on); for the online maps, their makers' legend.
- **Borders around your maps.** From zoom 8, the world's borders, rivers and town names were cut
  out of each downloaded map's whole rectangle, though the map only covers its region: a
  neighbour's border inside the rectangle (the Rhine north of Liechtenstein) was missing. Now only
  the region is cut (its outline, from the download list's regions).
- **A member's battery in words** ("battery 80%") in the party list: the browser had no battery
  sign to show.
- **Borders in Israel and Palestine, and maps like it.** From zoom 8 their borders were gone: the
  world's were left out for the map to draw its own, but the map's border lines don't say they're
  countries' (OpenStreetMap keeps that on the border's relation). Now the world's borders stay
  wherever a map has none of its own, the West Bank's and Gaza's dashed, up to zoom 12 (closer in
  they're too coarse to show). MapAround's maps from the next build carry their borders themselves.
- **Place icons on Android.** The style's icons (huts, springs, viewpoints…) are SVGs with a size
  but no view box; drawn small they showed only their top-left corner. They're whole now.
- **Country borders at every zoom.** In Topo, disputed borders and lines of control are drawn
  with short dashes.
- **Tap a place on the map.** Peaks, springs, huts, viewpoints, villages and the other places the
  map labels can be tapped: the pin shows what it is (Peak · 2085 m, Alpine hut, Drinking water…)
  with its name in your language and the local one, ready to route to, share or save.
- **What's here?** From any pin: the places the map shows around it, nearest first (offline; tap
  one to go there), and Wikipedia articles about places nearby, in your language and in English
  (online). On Android, iPhone and the web.
- **Wikipedia from the map.** MapAround's maps now keep each place's Wikipedia and Wikidata links
  (from the November build on): a tapped peak, castle, lake or village with an article gets a
  *Wikipedia* button that opens it in your language when there is one.
- **Pictures of places.** A tapped place with an article shows its picture on the pin, and
  *What's here?* shows the photos taken around the point (from Wikimedia Commons), and the
  nearby articles with their pictures. Tap a photo to see it big, with who took it, its licence
  and *Where it was taken* on the map. Online; on Android, iPhone and the web.
- **Hebrew names in offline search on Android.** A phone set to Hebrew (or Indonesian) now gets
  the map's names in its language where the map has them; it got the local names.

### Web app
- **MapAround in the browser** at [adam2314.github.io/maparound/app](https://adam2314.github.io/maparound/app/),
  installable and usable offline: the map (online maps and the built-in world map), search, the
  route planner with online routes, the library (routes and places, GPX import and export), the
  map tools and your position on the map. Terrain and recording come next.
- **The 3D view in the browser.** The cube button shows the map on the relief, as a globe in space
  when zoomed out, with buildings close up and satellite imagery on request; one finger moves,
  pinch zooms, two fingers turn and tilt (with a mouse: the wheel zooms, the right button turns
  and tilts). Esc closes it, and the 2D map then shows where you were looking in 3D.
- **The 3D camera stays above the ground.** Opening 3D close in, in the mountains, could leave the
  camera inside the hill with only sky to see, and so could raising the relief or tilting low on a
  slope. The view now settles onto the ground as the relief arrives, and tilts up where the hill
  behind would be in the way (back to your tilt when it's clear).
- **Line of sight and viewshed in the browser**, with the terrain they need fetched as they go.
- **Contour lines and hill shading on the web's offline maps.** The terrain they're drawn from
  comes by itself as you look closer at your maps, or for a whole map from Offline → ⋮ →
  Contours & hill shading.
- **Hebrew and Arabic names read the right way** on the web map (they came out backwards), offline too.
- **Offline maps in the browser.** Download MapAround's maps (with the marked trails) from the
  Offline tab, then add the file with the file button: the map is kept in the browser's storage
  and drawn offline, like on the phone. A `.map` file you already have can be added the same way.
- **The address follows the map** (`#map=zoom/lat/lon`, as on openstreetmap.org): reload, bookmark
  or share the link and the map opens at the same place and zoom. A pin is in it too, as
  OpenStreetMap's pinpoint (`?mlat=…&mlon=…`), so the link opens with the pin.
- **A data view in the browser.** Layers → *Data view*: the countries coloured by population,
  density, GDP per person, life expectancy or median age (Our World in Data), and, zoomed in on
  Israel, its localities as circles as big as their population, coloured by size, children, young
  adults or people 65 and over, Arab residents, kind of place (city, kibbutz, moshav, community
  settlement…), religion, founding year, altitude, socio-economic cluster or district (the Central
  Bureau of Statistics' and the Population Authority's data, from data.gov.il). Tap a colour in the
  legend to hide those; tap a country or a locality for its figures and an age chart, and *Pin it*
  for What's here and Wikipedia. Downloaded when first used and kept for a week, so it works
  offline too. In Hebrew as well.
- **Join a party in the browser.** An invite link now also offers *Join in your browser*, and the
  web app can start a party too: a full member, sharing your position (the browser's) with the
  group, with SOS, the meeting point, messages and the invite's QR code, through the same
  encrypted relays as the app. Online only (no LoRa radio), and your position goes while the page
  is open; SOS alerts and messages show as notifications while it's in the background.
- **Shared points open in MapAround's web app.** Sharing a point now also gives a MapAround link
  (OpenStreetMap's pinpoint form, on MapAround's site), next to the Google Maps and OpenStreetMap
  ones: whoever gets it sees the pin in MapAround, in any browser.

### Party
- **Invites are web links** (`https://adam2314.github.io/maparound/party#…`), so messaging apps
  make them tappable and the QR code opens from the phone's camera. The page opens MapAround, or
  Google Play if it isn't installed; the party's code never reaches the website. Older
  `maparound://` invites still work.

### Good to know
- The 3D globe is on Android and in the web app, the built-in world map on Android and in the web
  app; iPhone gets both later.

## 0.1.0 (first Google Play upload)

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
