# Changelog

## 0.5.4 (not released yet)

### Navigation on a watch
- **A Wear OS app** (first version, built with the phone app): the next turn with its arrow and
  distance, the instruction, distance left and arrival time, and Stop; or the recording's time,
  distance and climb, with Stop & save; or Record. It follows the phone over Google's watch link, in
  the phone's language (Hebrew right to left). A **tile** (swipe from the watch face) shows the
  next turn or the recording, and while either goes on the app's icon is on the **watch face** with
  the turn, or the recording's ticking time and distance. **Route** shows the route around you
  without a map: its line, turns and stops, and you as an arrow (from the watch's own location),
  north up or the way you go, zoomed with the crown, with the next turn and a scale. Not yet tried
  with a real phone and watch together.
- **Turn alerts on any watch.** While navigating with MapAround in the background, each
  instruction ("In 180 metres, turn left", off the route, arrived) is also a short notification that
  vibrates, so a watch that mirrors the phone's notifications buzzes at each turn: Wear OS, Galaxy
  Watch, Garmin, Suunto, Amazfit and others, with no watch app. Also with the voice off, for quiet
  hikes. Each replaces the one before and goes after a minute; "Turn alerts" can be turned off in
  Android's notification settings for MapAround. (The next-turn notification itself is ongoing,
  and watches don't show those.)

### Settings: one place
- **Settings is a screen of its own**, from the gear at the top of every screen but the map
  (Library, Party, Offline, Sensors). Each page has its back arrow, and the phone's Back goes from a
  page (Language, say) to the Settings list, then out: as a bottom sheet, Back closed the whole of
  Settings on Android.
- **Units & formats.** Distance (km, miles or nautical miles; feet or metres close by), height
  (metres or feet), speed, direction (compass points, degrees, both, or mils), time (24 or 12
  hours) and date order (17 Nov 2026, Nov 17 2026, 2026-11-17), plus coordinates. Quick choices:
  Automatic, Metric, Imperial, Nautical. Automatic follows the device's region and clock; the
  region counts only where the time zone agrees, so an "English (US)" phone in Israel stays metric.
  They apply on the map's cards, the party, the tools, Library, Speed, the satellites screen, the
  scale line (in the chosen unit only), charts, the car screen, and navigation's spoken instructions
  ("In 350 feet").
- **Your formats everywhere.** Each of these now follows Settings → Units & formats:
  - **Coordinates:** in the format chosen alone, on the pin card (it showed decimal degrees or DMS
    besides; tapping it still opens the converter with every format), in What's here, the weather
    card, the meeting point, the satellites screen and the share sheet's **Copy coordinates**.
  - **Directions:** as chosen (compass points, degrees, both or mils) on the pin card ("from you"),
    in the measuring and line-of-sight tools, and in Speed's course; the measuring tool's
    magnetic and back bearings too, with mils besides unless directions are in mils already. The
    compass's big readout is in mils when directions are.
  - **Heights and lengths:** in a locality's altitude and the line-of-sight and radio
    explanations (they were in metres).
  - **Speed:** the pace is per mile or nautical mile when distances are, and "stopped" says its
    speed in your unit.
- **How long the screen stays on**, with the app open and while navigating: the phone's own setting,
  2, 5, 10 or 30 minutes after the last touch, or always on. As before until changed: the phone's
  setting with the app open, always on while navigating. (It never goes off sooner than the phone's
  own setting; the Speed screen keeps it on as before.)
- **Party** in Settings: your name and look (also before joining a party: the next one starts with
  them, and a new party's name field is filled in), the server parties meet on (invites include
  it), the online switch and the radio gateway.
- **Sharing**: a shared point, place, route or track, and the GPX file sent with it, now carries one
  map link: **MapAround's** (it opens the place or the line itself, in the app or in any browser), no
  Google Maps or OpenStreetMap ones. Settings → Sharing chooses another instead: Google Maps (a
  planned route as directions), OpenStreetMap (a planned route on BRouter-Web), or all of them, as
  before. A shared point's coordinates are in the format chosen (Settings → Units & formats), as on
  its pin; opened in MapAround, a point without a name doesn't take them for one.
- **Sources**: every online service the app uses, in one list: the map list, the online map,
  satellite imagery, marked trails (online and to download), elevation, online routing and its
  data, search, Wikipedia and photos, the party's server, and (on the web) the data view's GPS
  traces. Each one uses the default, your own
  address (**Check** sends one request and says how long it took), or is off where the app can do
  without it (online search, routing, Wikipedia and photos, maps over the internet). Your own
  address for the map list, Overpass and search is tried first, then the defaults.
- **Regional defaults.** The online map is now **Automatic** until you pick one: from zoom 8 a
  country's national map where there is one (swisstopo, basemap.at, Kartverket, Plan IGN, USGS
  Topo), OpenStreetMap France elsewhere. In France the satellite background is IGN's aerial photos.
- **The first time, the map opens where you are.** With your location allowed, the map moves to
  it once (zoom 12) instead of showing the whole world; a link, pin or invite you opened wins.
- **Starting faster.** Android reads the lists of maps (MapAround's, and mapsforge.org's few
  hundred folders) at most once a day instead of at every start. The web app asks for its own
  files all at once rather than one after another (a small gain; most of the wait is the app
  itself).

### Navigating a saved route
- **Navigate** in a route's menu in Library (and on its card). A route you planned is calculated
  again along its stops, for its turns. A recorded or imported track is followed as it is: you choose
  on foot, by bike or by car, its sharper bends are announced (GPS jitter smoothed out), and if you
  leave it, the way back goes to its nearest point ahead, by the router, or straight when there's
  none, then on along it. Far from it with no route there, it says how far and picks it up when you
  get there.

### 3D on Android: solid ground
- **The ground keeps its shape while it loads.** A tile's terrain and its map picture now load
  apart: the terrain takes a fraction of a second, the picture longer. Moving to a new area, the
  mountains are there at once in their own shape, wearing a softer picture until the sharp one
  comes. Before, they slumped into coarse blobs (or went flat) and rose again level by level.
- **No more popping.** A sharper picture fades in over the softer one, and a more detailed tile
  grows out of the coarser one's shape, each in about a third of a second. Tile edges stay where
  they are, so neighbours always meet.
- **Places seen before show at once.** The map pictures the 3D view draws are kept on the phone
  (up to 150 MB, by map style, for a month). Reopening the 3D view over the same area, its first
  view was ready in about half a second instead of one and a half.
- **Ground around the view loads too**, after what's in view, so turning shows loaded ground
  sooner.
- **Distant mountains have relief.** Where no elevation has been downloaded (beyond the area
  around you, or before the download is done), the 3D view uses small relief tiles from the 3D
  relief source in Settings → Sources (Terrain Tiles by default, about 100 kB each, kept on the
  phone).
- **A regional map has no holes when you zoom out.** With swisstopo, IGN or another regional map
  (or the automatic one in those countries), tiles that server hasn't got (the whole world, or
  past its border) show OpenStreetMap France (Esri for satellite) instead of staying blank.

### 2D and 3D, on the phone and the car
- **The 3D button says 2D or 3D**, where it goes. While navigating it switches navigation's view
  between tilted, the way ahead up (3D), and flat, north up (2D); otherwise it opens the 3D view, and
  its "2D" brings back the flat map, north up.
- **The map no longer stays tilted after navigation.** Navigation stopped elsewhere (on the car, in
  the notification, from another tab) left the phone's map tilted and turned, and the reset to flat
  could be cut short by the next GPS fix. It now always goes back flat and north up, at once.
- **2D / 3D on the car.** A 2D/3D button with the car's zoom buttons: 3D tilts the car's map with
  the way the car goes up and the car low on the screen (as the phone's navigation view), 2D lays it
  flat, north up. It's the same choice as the phone's while navigating, so both screens agree.

### The coordinate converter
- **Nine more formats**: Geohash, GEOREF, Web Mercator (EPSG:3857), ECEF X Y Z, and the national
  grids of Britain (OS National Grid, "TQ 30080 80820"), Ireland (ITM), Switzerland (LV95, LV03 read
  too), the Netherlands (RD) and France (Lambert 93), besides decimal degrees, degrees and minutes,
  DMS, UTM, MGRS, the Israeli grid, Plus Codes and Maidenhead.
- **Any format in, every format out.** What you type is read as whatever it is ("RD 155000
  463000", "EPSG:2154 …", "2'600'000 / 1'200'000", "NN 166 712"); **Read as** picks the format
  where plain numbers could be several grids, each shown with your point in it to recognise yours.
- **Find a format** by name among the results ("swiss", "utm", "רשת"); a grid of another country
  says it doesn't cover the place.
- **Your location in every format** straight away, before you set a point (else the map's centre).
- **A coordinate grid over the map**, in the format chosen for coordinates: a switch in Layers and a
  button in the converter. Its lines get closer as you zoom in (about 110 dp apart): kilometres for
  UTM, MGRS and the national grids (10 m to 1000 km, labelled in km: "711", "3517"); degrees,
  minutes or seconds for the angle formats ("31.75°", "31°27.5′N", "35°23′30″E"); and for
  Maidenhead, Plus Codes, Geohash and GEOREF their cells, each with its code. A grid of another
  country where it doesn't reach is drawn in degrees.

### The party
- **The party speaks a new language, Link v1** (on Android and the web). A position is 32 bytes
  instead of 54, so more of them fit a LoRa radio's airtime. **It doesn't speak the old one:**
  everyone in a party needs this version (the same invite still works). Not yet tried between real
  phones and radios.
- **Positions go when they say something new.** From your speed and heading the others' phones
  carry your marker on; yours sends a position when that guess is off (by 15 m online, as it
  starts), when you stop or start, when a good fix comes back, and at least every minute (on the
  radio, every 10), with the way you went since the last one. The pace follows a mode chosen by
  itself:
  - **Live**, faster, near another member or the meeting point, in an SOS, or moving while
    charging;
  - **Saver**, slower, on a low battery, after 10 minutes standing still, or with no one heard
    for half an hour;
  - **Normal** otherwise, a step down when the radio's channel is busy.
- **Easier on the battery with the screen off.** The party wakes only when something is due
  instead of every second, takes a GPS fix as often as its mode needs (every 2 s in Live, 5 s
  moving in Normal, 20 s standing still, none while you're hidden and not in an SOS), and pings
  the server only when nothing else has gone to it.
- **Back online, at once.** When the network returns, it reconnects straight away (a growing pause
  only while the server can't be reached) and sends your name, position and SOS. A meeting point
  set while offline reaches the server then, as does one the server lost.
- **An invite can carry several servers**: members connect to all of them, and a phone on two of
  them joins those on either.
- On Android, the party reaches the servers over secure WebSockets, as the web app does.
- For now, members' ways so far (History) aren't kept once the app closes.
- **Only a real GPS fix goes on the map.** A position less exact than ±20 m (a phone indoors or
  just started, going by Wi-Fi or cell towers) no longer moves a member's marker or goes in their
  history, so it doesn't jump hundreds of metres. It still shows they're there: the members list
  and their card say "no GPS fix (±45 m) · heard 2 min ago", with their battery, and an SOS still
  comes through. The marker stays at their last good position; one who never had a fix is in the
  list but not on the map.
- **A member's card on the map fits narrow screens**: its buttons (History, Route here, Share) go
  on to a second line instead of off the edge.
- **The party carries on in the car.** When the party was on, MapAround on the Android Auto screen
  now starts it again (sending your position), as opening the app on the phone does. Before, with
  the phone in a pocket for the whole drive, it didn't send. Not yet tried in a car.

### The map
- **Coordinate grids no longer freeze the app.** On a tilted map (navigating, or tilted by hand),
  the view reaches toward the horizon, and the grid kept widening its spacing to cover all of it.
  A UTM, MGRS or national grid's numbers that far from its area grew without bound, so the grid
  never finished: the phone heated up, ran out of memory and the app closed. That happened on
  Android, iPhone and the web. Degree grids did the same with the world's copies side by side,
  and the Web Mercator grid over the whole world. Every grid now draws only around the map's
  centre, at its scale's spacing. Projected grids stay where they apply (UTM to within reach of
  its zone). A grid's lines are counted out, so nothing can run on. A test now runs every grid
  over hundreds of views: tilted, zoomed in to 24, the poles, the date line, the world.
- **The grid holds still and is the same in 2D and 3D.**
  - **While you pan:** the spacing follows the zoom alone, and a grid covers more than the
    screen, so small moves keep the same lines. The values are written along the lines, where
    the map keeps them in place, instead of at the screen's edge, where they jumped.
  - **UTM zone borders:** each zone's grid runs to its own border, so crossing one shows both
    instead of a jump. Zoomed out over many zones, the zones and bands themselves ("32T").
  - **3D on Android:** the grid is drawn into the map on the terrain, hiding behind ridges as
    the map does. Turning it on or off, or another format, redraws the 3D view where it is.
- **The scale is a line in the corner again**, as before the zoom slider: in the top corner away
  from the buttons, one round distance above the line, in the distance unit chosen only (km and
  m; miles, and feet close in; nautical miles), with a tick at each end, black on a white halo. The zoom slider keeps a plain thumb. The
  compass (Android's, and the 3D view's) sits under the scale. On Android, iPhone and the web, in
  2D and 3D.

### The web app
- **The 3D view turns around the place you right-click.** A right-button drag (or ctrl, shift or
  alt + drag) turns and tilts the camera around the spot it starts on, which stays under the
  pointer, marked with a ring while turning: a peak or a valley can be looked at from every side.
  The tilt stops before the camera would go into a hill; started on the sky, it turns around the
  middle of the view as before.
- **3D: no more walls of streaks.** Tilted steeply with a valley or more in view, the relief could
  turn into vertical stripes (MapLibre's globe turning flat between its zooms 10 and 12). The 3D
  view is now flat from zoom 9 and a globe only further out, where its curve shows.
- **Tooltips**: resting the mouse on an icon button (the map's buttons, zoom, the panels, Library,
  Party, Offline, the tools) says what it does. On phones, a long press shows the same.
- **The data view's explorer.** Its panel opens on a tree of what it can show: World (the five
  country figures), Israel (the eleven ways to colour its localities), Time and Movement, with a
  search box that also finds a layer by what it's about ("clock", "heat map", "שעון"). Picking a
  layer shows its legend; picking the one that's on turns it off, so the countries or the
  localities can be hidden.
- **Time zones** (Time): the world's time zones, coloured by their hours from UTC now and labelled
  ("+2", "−3:30"), the sea's too. Tapping one shows its local time (ticking), its offset, how far
  ahead or behind your clock it is, and when its clocks next change ("Clocks go forward 1 h on
  26 Mar 2027 at 02:00"). Built in (0.3 MB, from timezone-boundary-builder, © OpenStreetMap
  contributors), so it works offline; the clocks come from the browser's own time-zone rules.
- **GPS traces** (Movement): OpenStreetMap's public GPS traces over the map, to see where people
  actually walk, ride and drive. It's in Settings → Sources, where it can be turned off or pointed
  at other tiles.
- **Weather** in the data view: the **rain radar** (RainViewer, the past ten minutes, a new picture
  every ten), and the **temperature** or **wind now** at points over the view (Open-Meteo), in °C or
  °F and your speed unit. Tapping a point shows its forecast: now, the next hours, seven days,
  sunrise and sunset, by the clocks there.
- **Countries named on the map**, in the app's language (Hebrew or English): the big ones from far
  out, the small ones as you zoom in. Tapping a country now also shows its **flag**, its **area**
  (km², square miles or square nautical miles, as your distance unit), its **capital** and its
  **official languages**, and a **Wikipedia** link (Hebrew Wikipedia when the app is in Hebrew).
  These come from Wikidata and Wikimedia Commons and are built in, so they work offline.
- **Weather maps** (Weather), like a weather site's:
  - **Wind, moving:** streams of particles follow NOAA's forecast wind across the map.
  - **Temperature**, **clouds** or **rain and snow:** shown as smooth colours, with a legend.
  - **A slider** steps through the next 48 hours, every three, starting at the hour nearest now.
  - **Tapping the map** opens the forecast there.

  The graphics card draws them from NOAA's GFS model (public domain), which MapAround's own
  pipeline turns into pictures every six hours. They're in Settings → Sources, where they can be
  turned off or pointed elsewhere.
- **Street photos** (Look around): Panoramax's open street-level photos, as lines where they were
  taken and dots up close. Tapping one shows the photo (all-round ones can be looked around by
  dragging), its date, author and licence, with **Open in Panoramax** and **Street View here**
  (a link to Google Street View at that spot).
- **Buses now** (Movement, Israel): every bus where it is now, from the Ministry of Transport's
  real-time feed via the Open Bus project, a minute or two behind, with each line's number up close.
  Tapping one shows its line, operator, where it's going, its speed and when it was there.
- **Cameras** (a new branch): **Cameras on the map**, the speed cameras, security cameras and
  webcams OpenStreetMap's volunteers have mapped (red, purple, blue; a speed camera's limit up
  close), from zoom 10. Tapping one shows its limit, which way it faces, what it watches and who
  runs it, with its webcam's page and its OpenStreetMap page. When OpenStreetMap's servers are busy
  it says so and tries again by itself. **Live webcams** (Windy) is there as coming soon.
- **The explorer has a drawer of its own**, opening when the data view is switched on (and from
  the panel's **Data layers**): a search box, what's on the map as chips (tap one to take it off,
  or **Clear all**), and the tree, each layer with where its data comes from; round buttons for
  one-of-a-kind layers, check boxes for those that go over the others. On a phone it covers the
  map until **Show the map**; on a wider screen it stays open beside the map. The panel over the
  map now shows only the legends and notes of what's on.
- The new sources are in Settings → Sources (the radar can be turned off; the weather, photos and buses can
  also be pointed at another server).
- **MapAround's maps** (a new branch, MapAround): every region coloured by how its offline map
  stands: green, built in the latest monthly run; orange, an older map; red, none yet (too big for
  the free build machines, or its build failed). A country whose regions are maps of their own
  (France, the United States' states) is shown through them. Tapping a region shows the day its map
  was built and its size, and how its country stands as one map, with a way to help fund a bigger
  build machine. `data=maps` in the address opens the map with it, as the **support page** now
  embeds it, in English and Hebrew.
- The data view's credits name the countries' and Israel's sources only while those are shown.
- **The explorer's drawer folds away** with the arrow at its top, and the data stays on the map; a
  tab on the map's edge brings it back, also while a country's card is open. (Its ✕ looked like
  leaving the data view, which is at the bottom.)
- **The tapped country is highlighted** while its card is open: outlined in white and a little
  lighter than the others.
- **Cards keep a readable width on a big screen.** A country's, a region's, the weather's and the
  others, a place's or a member's, and the offer of offline maps no longer stretch across a wide
  window: they're at most about the width of a small tablet, at the bottom corner (the offer at
  the top). Phones look the same.
- **Online vector maps** (Layers → Online): OpenFreeMap's **Liberty** and **Positron** (light grey,
  quiet under routes and the data view), drawn in the browser from OpenStreetMap data updated every
  week: sharp at every zoom, the names in the app's language (Hebrew names in Hebrew), with
  MapAround's routes, trails, party and data view over them. Free, with no account or key. If
  OpenFreeMap can't be reached, OSM France's map shows instead. `layer=ofm_liberty` or
  `layer=ofm_positron` for an embedded map; the support page's map of the builds is on Positron.

## 0.5.3 (beta, version code 9)

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

### MapAround's own address: maparound.app
- **The website, the web app and invites moved to maparound.app**: the web app is
  maparound.app/app, invites maparound.app/party, the guides maparound.app/guide. Links shared
  before (adam2314.github.io/maparound/…) lead there, and the app still opens old invites and links
  (in Android too). Shared points, tracks, embed codes and guide links use the new address.

### The map in other websites
- **Put the map on any web page** with an iframe of the web app's address plus `?embed`: just the
  map, with its layers and the 3D view, and **Open in MapAround** to go on in the full web app. It
  shows a place (`#map=`), a pin (`mlat`/`mlon`), a track from a link or a GPX file on a website
  that allows it (`gpx=`), with `layer=`, `trails=0` and `lang=` to choose the map, leave out the
  marked trails and set the language. It reads and saves nothing of the visitor's and doesn't ask for their location.
- **Embed in a website** in a track's menu in Library copies the code, the line inside it, ready to
  paste into a page.
- A guide: [The map in your website](https://maparound.app/guide/embed).

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
- **A page on what donations pay for** ([Support MapAround](https://maparound.app/support),
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
- **Dirt roads are a double line from zoom 12** (one zoom level further out than before), and the
  marked trails on them colour their edges there too, instead of waymark dots.
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
