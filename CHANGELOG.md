# Changelog

## 0.5.3 (not released yet)

### Navigation and screens
- **Starting navigation no longer crashes the app** after Android Auto has been connected. The
  app told the car screen that navigation started; when the car was gone (or not ready) that
  failed and took the app down, on the phone and on the car. Navigation now goes on on the phone,
  and the car is told again a little later.
- **The map on another screen.** With a monitor or TV connected (USB-C or HDMI) or a wireless
  display, **Map layers** offers **Map on another screen**: the full map there, following you and
  closer while navigating, with the same route, tracks and party; the phone keeps its own screen.
  The switch only shows while such a screen is connected.
- **Back does what you'd expect.** On Library, Party, Offline or Sensors it goes to the map; on
  the map it first closes what's open (a place's card, the route planner, the tools, the data
  view), and only then leaves the app. The app uses Android's own back (as Android 16 requires),
  so the back gesture's preview works too.

### 3D on the web
- **Opening the 3D view waits for the relief, then moves in one smooth go.** The map stays as it
  is ("Loading the relief…") while the relief and the tilted view's tiles load, the camera's place
  above the ground worked out first; then a single eased tilt into 3D, with nothing popping in or
  jumping after it. Without a network it goes ahead after a few seconds.

### Android Auto
- **Navigating from the phone shows on the car.** When navigation starts and Android Auto shows
  another app (or MapAround hasn't been opened on the car yet), the next turn appears on the car
  screen as a card (once as a pop-up), kept up to date. A tap opens MapAround on the car screen in
  navigation: the map, the maneuver card and the arrival time. (Android Auto doesn't let an app
  take over the car screen by itself; the card gets you there in one tap.)

### 3D on Android
- **Opening the 3D view waits for what it shows, then tilts in one go**, as on the web. The 2D map
  stays (with "Loading the relief…") while the relief's elevation data comes, if it's missing, and
  the tiles, buildings and names of the view load; the view then appears straight down, as the map
  was, everything already on it, and turns into 3D in one eased move. No more tiles fading in or the
  view starting again when the relief arrives halfway. Without a connection it goes ahead after a
  few seconds.

### The map online, as nice as offline
- **Where no map is downloaded, the map reads it over the internet** (Android), in the same style:
  MapAround's own map where there is one, with the marked trails, path grades and 4×4 tracks, else
  the basic one from mapsforge.org. Only the parts on screen are read (a town and the country around
  it: a few megabytes), and kept for a while, up to 512 MB. The banner says **shown online**, and
  **Get map** still downloads it for when there's no signal. Without a signal what was seen still
  shows, and the rest fills in by itself when the signal is back. A switch in **Map layers**
  (**Online where none is downloaded**) turns it off. Also on the car screen, the second screen and
  in 3D.

### GeoTIFF maps
- **GeoTIFF scans and orthophotos as raster maps** (Android), added like the other raster maps and
  read where they are. Placed by the coordinate system in the file: latitude/longitude, UTM on
  WGS 84 and the other usual datums, Web Mercator, the Israeli grid, Gauss-Krüger, the Greek,
  British, Irish, Nordic, Polish, Croatian, Slovenian and New Zealand grids, Lambert-93, Belgian
  Lambert, or a Transverse Mercator / Lambert / Mercator projection the file describes. LZW,
  Deflate, PackBits, JPEG or WebP, tiled or in strips, BigTIFF too; transparent where the file has
  no data. A big one without reduced copies of its own gets them made once (with its progress), so
  it's quick zoomed out.
- **ECW** can't be opened (its decoder isn't open source); picking one says how to turn it into a
  GeoTIFF with QGIS or GDAL.
- **All raster maps draw better**: thin lines (contours, grids) no longer break up zoomed out,
  slanted lines no longer step, and edges fade out instead of a dark fringe. After an update their
  tiles are made again rather than kept from before.

### Tracks as links
- **Share a track or route as a link.** Sharing from Library now adds a link with the line in it
  (…/maparound/app/#track=…): whoever gets it sees the track in the web app with nothing to
  install, or in MapAround when they share the link to it. Long tracks are thinned to 500 points
  with their shape kept; heights go along, so the climb and the elevation profile come too. The
  line is after the `#`, so it never reaches the website. Opened again, the same link doesn't save
  the track twice.
- **Open GPX files from the web.** A link to a .gpx file shared to MapAround is downloaded and
  imported, waypoints and all. The web app opens them too, as …/maparound/app/?gpx=<link>, when
  the website holding the file allows it (GitHub's raw files do).
- The message after importing a shared track or places now shows; it was cut off before.

### The map in other websites
- **Put the map on any web page** with an iframe of the web app's address plus `?embed`: just the
  map, with its layers and the 3D view, and **Open in MapAround** to go on in the full web app. It
  shows a place (`#map=`), a pin (`mlat`/`mlon`), a track from a link or a GPX file on a website
  that allows it (`gpx=`), with `layer=`, `trails=0` and `lang=` to choose the map, leave out the
  marked trails and set the language. It reads and saves nothing of the visitor's and doesn't ask for their location.
- **Embed in a website** in a track's menu in Library copies the code, the line inside it, ready to
  paste into a page.
- A guide: [The map in your website](https://adam2314.github.io/maparound/guide/embed).

### The party
- **More about each member**: their speed and the way they're heading, height, how exact the
  position is and battery, and the time it was taken next to how long ago, in the members list and
  on their card on the map. Speeds in the unit chosen in Settings.
- **Messages in the order they were written**, each with its time. One that comes late (by radio,
  or kept by the server) goes before the newer ones instead of at the bottom.
- **A member's history, replayed.** **History** in the members list (and on their card on the map)
  shows their way so far on the map in their colour and plays it: their marker goes along it with
  the time, the distance so far, speed, height and battery then. Pause, drag to any moment, play
  from the start, ×10 to ×1200 (long stops are skipped). It's what your device received since you
  joined, positions that came late in their place; kept between runs, deleted when you leave the
  party.

### Map tools
- **Pick a point by tapping it, move it by dragging it.** Tapping A or B on the map picks it (a
  yellow ring shows which; further taps on the map move it), and pressing on a point and dragging
  moves it at once, with the result following (Android and the web; a long press then drag
  everywhere).
- **The panels over the map fold away.** The tools, the route planner and a replayed history have a
  handle: tap it or drag it down and only one line stays (the result, the route's length and time,
  the moment replayed, with their main buttons), so the map shows; tap or drag up for the rest.
- On the web, dragging a route stop after a long press works again (the moves weren't reaching it).

### Supporting MapAround
- **A page on what donations pay for** ([Support MapAround](https://adam2314.github.io/maparound/support),
  in English and Hebrew). MapAround stays free; donations go to a better web experience (the maps
  hosted where the browser may read them, a domain) and to building the offline maps (the biggest
  countries), and the app stores, with their costs, through GitHub Sponsors; and other ways to help
  (testing, OpenStreetMap, translating). Linked from Settings, from maps that need funding in Offline maps,
  from the website, and as the repository's Sponsor button.
- **The Android beta on the website**: the front page's Google Play button and the party invite
  page lead to a page on joining the beta (the testers' group, then Google Play), in English and
  Hebrew; an invite opened without the app goes there too.

### The Topo style
- **Bike paths you can see.** Bike paths, and the many paths shared by walkers and bikes (in
  parks like HaYarkon), are blue with a white dashed line down the middle, like a painted lane,
  and drawn over the streets they run beside (in Tel Aviv they were hidden under them). Bike
  trails out in the open (singletracks) keep their difficulty colours with a blue line along them.
- **Path colours on the map itself.** The difficulty colours of paths only showed in the legend:
  the map read them from the wrong data. They're on the map now.
- **4×4 dirt roads as a double line** too, its edges dash-dot, after any vehicle (solid edges)
  and high vehicles (dashed).
- **Dirt roads are just their two edges**, with the ground showing between them: no white band
  down the middle any more (any vehicle, high vehicles, 4×4). **Paths** lose their white casing
  too: just the line in its difficulty colour.
- **Marked trails on dirt roads colour the road's edges.** No more waymark dots down the middle
  of a marked jeep road: its two edges take the trail's colour, still solid, dashed or dash-dot
  for how passable it is. Two colours: one on each edge; a third and a fourth: a second line
  outside the edges. On paths and roads the dots stay. Trail data downloaded before is fetched
  again by itself a little after the app starts (over Wi‑Fi if that's set), since it didn't know
  which roads the trails run on; until then the trails draw as before. MapAround's own maps get
  it when they're next built.
- **A bike sign along bike paths and bike trails**, every so often, from zoom 15. With it, the
  icons the style always had along lines show too: one-way arrows on streets from zoom 16, and
  cable cars, gondolas and chair lifts.
- **Cliffs you notice**: a thicker line and bigger teeth (on the side it drops to), bigger still
  from zoom 15.
- The map draws a trail's colours as one layer each, not one per colour: the many trail lines
  cost the map less.
- **Everything bigger.** Names and icons are 2 points bigger, and lines half as wide again (a
  path's white edge 4.4 → 6.6, a dirt road 6 → 9, a trail's dots 3.8 → 5.7), their spacing
  and dashes with them, so double lines and trails side by side keep their shape. The legend's
  names are 2 points bigger too, with bigger samples.

## 0.5.2

### 3D
- **The 3D view keeps the map's buttons.** The same column as on the map, in the same places:
  search (the view goes to what you pick), layers, route (greyed out in 3D), and the 3D button,
  lit while in 3D, which switches back; the tools button becomes the relief's (relief, buildings,
  satellite imagery), and the zoom slider shows the scale at the view's middle. A compass appears
  on the other side while the view is turned; a tap faces north. The 3D view credits what it
  shows, as the map does: the map's sources, the imagery when it's on, and the relief data.
- **A steadier 3D view.** Panning over ridges and gullies no longer bobs the view: on Android the
  point the camera turns around follows the ground averaged around it, eased; in the web app the
  view's height holds while you move and the middle is grounded with the camera still, also as
  finer elevation arrives (no more drifting after a stop). Gestures are applied once per frame, a
  pinch no longer wobbles the view round (a twist turns it once it's meant), tilting keeps every
  bit of the move, the zoom slider eases and no longer sinks the view, names keep up with the
  ground, tiles reach the screen a few at a time instead of in one long frame, a tile near the
  edge of a finer level no longer flips back and forth, and new elevation data rebuilds the
  Android view once a download is over instead of after every tile.

### The Topo style
- **Dirt roads by how passable they are, paths by difficulty.** Dirt roads show whether any car,
  a high vehicle or only a 4×4 gets through, or none (from the map's smoothness and track grade);
  paths are coloured by difficulty: easy green, medium blue, hard black, very hard red with ticks,
  paved green, sand orange, hard to see dotted. Marked trails are drawn as their waymarks, colour
  and white, so the path's own line shows through. Main roads are red, secondary green, regional
  brown; seasonal streams are dashed. The legend shows it all.

### Offline maps and sources
- **The legend and what the maps contain, on the website.** The Topo style's full legend, each
  feature with its OpenStreetMap tags, the zoom it shows from and its colours and widths, and every
  tag MapAround's maps keep and from which zoom (English and Hebrew). Layers → Legend links there.
  Made from the style and the pipeline's tag list by `tools/map-docs`, so they stay in step.
- **One import for every map file.** **Import a map file** in Offline maps takes a vector map
  (.map, e.g. one downloaded from the web app: it's recognised as that MapAround map, updates and
  all) and, on Android, a raster map (.rmap, .mbtiles, .sqlitedb) alike.
- **Every section explains itself.** Maps on the device, raster maps, terrain, offline routing,
  marked trails, the map downloads and the party's server each say what they are, with a link to
  where the data comes from and to a new **guide** on the website (English and Hebrew).
- **Your own elevation source.** Terrain → Source → Change takes another address for the 1°
  elevation tiles (contours, hill shading, line of sight, the viewshed, the 3D view), and in the
  web app for the 3D view's tiles (Terrarium or Mapbox Terrain-RGB). The map's credits name it.
- **Your own party server.** Party → Connections → Server → Change moves the party to your own
  MQTT server (a wss:// address, for phones and browsers alike). The invite link and QR code carry
  it, so everyone who joins meets there; new parties start on it. Everything stays end-to-end
  encrypted.

### Navigation
- **Navigation looks ahead.** While navigating the map tilts, turns so the way ahead is up, and
  puts you low on the screen, so most of it shows what's coming. The faster you go, the further
  out it zooms: close in on foot, a few streets ahead on a bike, well down the road in a car. A
  round badge shows your speed, in the unit chosen on the Speed screen. The tab bar steps aside
  while navigating so the map has the whole screen; Stop brings it back with the flat, north-up
  map. Drag the map to look around; the location button goes back to the navigation view. On
  Android and in the web app (the iPhone keeps the flat map that follows you for now).

## 0.5.1

0.5.0 went to Google Play's testing track only; 0.5.1 has all of the below.

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
  encrypted relays as the app. Your position goes while the page is open; SOS alerts and messages
  show as notifications while it's in the background.
- **A LoRa radio in the browser.** In Chrome or Edge (Android, Windows, Mac, Linux, Chromebooks),
  the web party pairs with a Meshtastic radio over Bluetooth, as the app does: positions, alerts and
  messages go over the radio too, at the radio's pace, and as a gateway the page relays between the
  radio and online, so off-grid and online members see each other. Other radios' positions show too.
  Not on iPhone (its browsers have no Web Bluetooth) or in Firefox.
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
