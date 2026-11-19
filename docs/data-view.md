# The data view

The **data view** turns the map into a way to explore places through their numbers: how many people
live there, how old they are, how rich, how high, how long they live, what time it is there, what
the weather is doing, what the streets look like, and where people and buses go. It's in the web
app, under **Layers → Data view**.

**The goal:** more data to explore the world. The first version covers every country and one
country in depth (Israel, down to each of its localities). This page describes what's there, where
the data comes from, how it's built, how to add more, and where it goes next.

## What it shows now

### The explorer

The explorer is a drawer of its own along the screen's start edge (the left; the right in Hebrew).
It opens when the data view is switched on, and again from the **Data layers** button on the
view's panel. On a phone it covers most of the map, over a dimmed strip that closes it, with
**Show the map** at its foot; on a wider screen (600 dp and more) it stays open beside the map,
which can be used meanwhile, and the panel and credits move over to its side.

From the top:
- **Search.** Finds a layer by its name, its branch, where its data comes from, or words about it
  ("clock", "UTC", "heat map", "rain", "שעון"), in English or Hebrew.
- **On the map.** What's shown now, as chips: tapping one (or its ✕) takes it off; **Clear all**
  takes everything off.
- **The tree** of everything the view can show, each layer with where its data comes from under
  its name. A round button means one of a kind (one country figure, or the time zones; one way to
  colour the localities; one weather colour, a map's or the points'); a check box, one that goes over
  the others.
- **Leave the data view** at its foot.

The tree's branches:

| Branch | Layers |
|---|---|
| World | the five country figures (one at a time) |
| Israel | the eleven ways to colour its localities |
| Time | Time zones (instead of a country figure) |
| Weather | Rain radar, Wind (moving), Temperature map, Clouds, Rain and snow (forecast), Temperature now, Wind now |
| Look around | Street photos |
| Movement | GPS traces (over any of the others), Buses now (Israel) |
| Cameras | Cameras on the map; Live webcams (coming soon) |

- **On and off.** Picking a layer shows it; picking the one that's on turns it off, so the
  countries or the localities can be hidden altogether. The panel over the map shows the legends
  and notes of what's on.
- **Over the map.** The weather, the photos and the buses are drawn over the map itself: turning
  one on takes away the colours under it (countries, time zones, localities), which can be picked
  again. Several of them can be on at once, with each one's note under the legend.
- **Folding.** Branches of up to five layers start open; a longer one (Israel) starts folded and
  shows how many of its layers are on. The drawer remembers what was opened and searched while
  it's closed.
- Picking one of Israel's layers from far away flies the map there.

### Countries (zoomed out)

Every country is coloured by one figure, chosen in the panel's picker. The legend's colours run light
to dark in seven classes.

| Figure | Classes (breaks) | Source |
|---|---|---|
| Population | 1, 5, 20, 50, 100, 300 million | Our World in Data (UN World Population Prospects) |
| Population density | 10, 25, 50, 100, 200, 500 per km² | Our World in Data (UN WPP) |
| GDP per person | $2,000, 5,000, 10,000, 20,000, 35,000, 50,000 (PPP) | Our World in Data (World Bank WDI) |
| Life expectancy | 60, 65, 70, 75, 80, 83 years | Our World in Data (UN WPP) |
| Median age | 20, 25, 30, 35, 40, 45 years | Our World in Data (UN WPP) |

Each figure is the latest year with a value. Projections are left out: the population-density and
median-age charts run to 2100.

Each country is named on the map in the app's language (Hebrew or English), at Natural Earth's
label point, which is placed by hand: France's name sits in France, not in its overseas parts. Big
countries are named from far out and small ones as you zoom in (Natural Earth's own zoom for each,
one less for MapLibre's bigger tiles). Where names would overlap, the bigger country's is shown.
A few of Natural Earth's names are changed in `make_countries.py`:
- China and the United States get their short names.
- Northern Cyprus loses "Turkish Republic of".
- Palestine is "פלסטין" in Hebrew rather than Natural Earth's "ארץ ישראל", which names the land,
  not the state.

Tapping a country opens a card with:
- its **flag** and name;
- its **area**, in km², square miles or square nautical miles, following the distance unit in
  Settings → Units & formats;
- its **capital** (or capitals: South Africa has three);
- its **official languages**, the five most spoken worldwide, by name, then how many more
  (Zimbabwe has sixteen);
- every figure loaded so far, each with its year;
- **Wikipedia**, which opens its article: the Hebrew one when the app is in Hebrew and there is one,
  else the English.

The flags are the app's own files (`webApp/.../resources/flags/<ISO>.png`), each country's flag on
Wikidata as Wikimedia Commons renders it, 160 pixels wide, about 4 kB each. A card loads only its
own flag, and the app keeps it for offline use. Only flags under a free licence are included:
- 228 are public domain, 6 are CC0, and 1 is CC BY 2.5 (Saint Barthélemy's, credited);
- `flags/LICENCES.md` names each flag's file, author and licence;
- Oman's flag is left out: it's under Oman's own government licence.

The area, capitals and languages are Wikidata's (CC0), bundled with the outlines, so they work
offline:
- **Area:** the country's total area, the largest of its figures that cover the whole country.
- **Capitals:** the current ones.
- **Languages:** those of the whole country. Where there are none (Belgium's are each a region's),
  those of its parts.

A few gaps and oddities are filled by hand in `make_countries.py`:
- the United States' English (its statement is deprecated on Wikidata);
- Uruguay's Spanish;
- China's Mandarin (Wikidata says "Putonghua");
- Antigua's capital and language;
- Palestine's facts are the State of Palestine's, with Ramallah, the seat of its government, as its
  capital.

### Israel's localities (zoomed in, from the app's zoom 7)

Each locality is a circle as big as its population: the radius grows with the square root, so a
circle's area follows its head count. The **Colour** picker chooses what the colour shows.

| Colour by | Classes |
|---|---|
| Size | <1,000 · 1,000–4,999 · 5,000–19,999 · 20,000–99,999 · 100,000+ people |
| Children (0–18) | <25% · 25–30% · 30–35% · 35–45% · 45%+ of residents |
| Young adults (19–45) | <30% · 30–35% · 35–40% · 40–45% · 45%+ |
| Aged 65 and over | <8% · 8–12% · 12–16% · 16–20% · 20%+ |
| Arab residents | <10% · 10–50% · 50–90% · 90%+ |
| Kind of place | Jewish town or city · Arab town · moshav · kibbutz · community settlement · moshava · Arab or Bedouin village · institution, other |
| Religion | Jewish · Arab · Bedouin · mixed |
| Founded | before 1948 · 1948–1966 · 1967–1989 · 1990 and after |
| Altitude | below sea level · 0–199 · 200–499 · 500–799 · 800 m and over |
| Socio-economic cluster | 1–2 · 3–4 · 5–6 · 7–8 · 9–10 |
| District | Jerusalem · Northern · Haifa · Central · Tel Aviv · Southern · Judea and Samaria |

A grey circle means that place has no data for the chosen figure.

Tapping a locality opens a card: its Hebrew and English names, kind, district, residents, the Arab
share (from 1%), founding year, altitude, socio-economic cluster, and a bar chart of the six age
groups. **Pin it** drops the map's pin there, so *What's here?*, Wikipedia, routing and sharing
work as for any place.

### Time zones

The world's time zones, the seas' included, each coloured by its offset from UTC now: the colours
go round the colour wheel once from UTC−12 to UTC+14 (which meet at the date line), lighter and
darker in turn so that neighbouring hours always differ. Half and quarter hours go with the hour
nearer zero (+5:30 with +5, −3:30 with −3), and each zone's label says its exact offset. Labels
appear as the map zooms in, the biggest zones' first, so zoomed out Europe isn't a crowd of "+1".

Tapping a zone opens a card: the place its name is for ("Jerusalem", "Asia · Asia/Jerusalem"; at
sea, "Nautical time zone · Etc/GMT+4"), its local time (ticking), its offset now, how far it is
ahead of or behind the device's clock, and its next clock change in the coming year, with the date
and the local time it happens at. Offsets are worked out again every quarter of an hour, so the
map follows a change while it's open.

The clocks come from the browser's own time-zone rules (`Intl`; the Kotlin date library has no
time-zone data on the web): the web app hands them to the view (`browserZoneOffsetSeconds`, given
to `StatsData` in `WebApp.kt`).

### GPS traces

OpenStreetMap's public GPS traces (the lines people uploaded to OpenStreetMap), drawn over the
map and the view's colours and under Israel's localities. The denser the lines, the more people went
that way; their colours show the direction of travel. It's in **Settings → Sources** (Data view), so
it can be turned off or replaced with other tiles.

### Rain radar

RainViewer's radar picture of the past ten minutes over the map, a new one every ten minutes (the
panel says when it's from). Light blue is light rain, dark blue heavy, yellow to red very heavy.
RainViewer serves its pictures to zoom 7 only (deeper tiles say "Zoom Level Not Supported"), so
from the app's zoom 8 they're enlarged: good for where the rain is, not for which street.

### Weather now

The temperature or the wind at points over the view, about forty of them on a lattice of whole
steps of latitude and longitude, so moving the map asks only for the points it brings in, and
each point is kept for a quarter of an hour (Open-Meteo's forecasts now are per quarter hour).
Temperatures are coloured in eight classes from below −10° to 35° and over, the wind in five
from calm to gale; tapping a class hides those points, as in the other legends.

Temperatures are in °F where heights are in feet (the US's units), else °C; wind speeds in the
speed unit chosen (Settings → Units & formats), with the compass point the wind comes from.

Tapping a point opens its forecast: the weather now (what it's like, feels like, wind and gusts,
humidity, clouds), the next 24 hours every three (temperature, chance of rain), the next seven
days (what they're like, low and high, rain), and today's sunrise and sunset, all by the clocks
there (Open-Meteo works out the place's time zone).

### Weather maps

NOAA's GFS forecast drawn over the whole map, the way weather sites show it:
- **Wind, moving** (a check box, over any of the others): thousands of particles drifting with the
  forecast wind 10 m above the ground, each leaving a fading trail, so stronger wind makes faster,
  longer streaks. They're white over a colour field, and dark over clouds and on the plain map. After
  the map moves, a new set of particles fills the view.
- **Temperature map**, **Clouds**, **Rain and snow (forecast)** (one at a time, instead of the
  temperature or wind points):
  - the temperature 2 m above the ground, from −50° to 50° (purple, blue, green, yellow, dark red);
  - the total cloud cover, from clear to white;
  - the forecast rain or snow rate, from light blue at 0.1 mm/h to red at 20.

  The colours blend smoothly between the model's points (every half degree, about 55 km), so they
  show the weather's shape, not a street's.
- **The hour.** A slider under the legend runs through the next 48 hours, every three, starting at
  the hour nearest now. Its label gives the day and time by your clock, and how far that is from now.
- **Tapping the map** opens the forecast there (Open-Meteo's, as for a weather point).
- The panel names the run ("NOAA's GFS model, run at 06:00 UTC"). NOAA makes a new run every six
  hours, and while the layer is on the app checks for it every half hour.

**How it's made.** MapAround's pipeline (`weather.py` in the my-pipeline repository) runs on GitHub
Actions at 04:40, 10:40, 16:40 and 22:40 UTC. It downloads the newest complete GFS run from NOAA's
NOMADS server at 0.5°, using NOMADS's filter so that only the fields it needs come down. For each
hour it writes two PNG pictures of the whole world, 720 × 361, a pixel per half degree from 180°W
and 90°N:

| Picture | Red | Green | Blue |
|---|---|---|---|
| `wt_fNNN.png` | wind east (u), −40 to 40 m/s | wind north (v), −40 to 40 m/s | temperature 2 m up, −50 to 50 °C |
| `cp_fNNN.png` | cloud cover, 0–100 % | √(rain rate), 0–20 mm/h (the root, so light rain shows) | sea-level pressure, 950–1050 hPa (not shown yet) |

`manifest.json` lists the run, the grid, the ranges and the hours. The files go on the repository's
`weather` branch, replaced at each run with no history kept. Browsers read them from
raw.githubusercontent.com, which lets other sites read its files (GitHub's release downloads don't).
A picture is about 0.3 MB, so each hour shown is about 0.6 MB, and the browser keeps the last eight
pictures.

The web map draws them with WebGL (`webApp/.../resources/weather.js`), as a MapLibre custom layer
under the labels:
- **The field:** the picture, coloured by the scale in a shader.
- **The wind's particles:**
  - their positions are kept in a texture, in its colours;
  - the graphics card moves them with the wind every frame;
  - they're drawn into a trail texture that fades a little every frame, and that is laid over the
    map.

The technique is Vladimir Agafonkin's [webgl-wind](https://github.com/mapbox/webgl-wind) (ISC
licence).

### Street photos

Panoramax's photos (the open, federated catalogue of street-level pictures, some all the way
round): zoomed out, dots where there are some; from the app's zoom 7, orange lines along the ways
they were taken; from zoom 16, a dot for each photo. Tapping one shows it with its date, author,
licence and server; tapping the picture shows it over the whole screen (an all-round one at the
screen's height, dragged sideways to look around). **Open in Panoramax** opens it in Panoramax's
own viewer, which turns all-round photos and walks along their sequence.

**Street View here** opens Google Street View at that place, looking the same way, as a plain
Google Maps link: Google's terms don't allow its pictures on other maps, and its API needs a paid
key, so the app links to it and shows none of it.

### Buses now (Israel)

Israel's buses where they are, from the Ministry of Transport's real-time feed (SIRI) as the
Public Knowledge Workshop's **Open Bus** project republishes it: a snapshot a minute, about a
minute or two behind. From the app's zoom 11 on Israel (closer in, with each line's number), the
view and a margin around it are asked for every minute and when the map moves further. A bus is
shown at its last position in the newest snapshot that's been read in full, and only if that
position is at most four minutes older than the snapshot (older ones are rides that have ended).

Tapping a bus shows its line and operator, where it goes and where from (city and stop, from the
day's timetable, GTFS), its speed and heading, when it was there, and its number. The lines are
looked up once a day for the lines seen.

### Cameras

**Cameras on the map:** the cameras OpenStreetMap's volunteers have mapped, read for the area on
screen (from the app's zoom 10) through an Overpass server: speed cameras (red, with the limit they
enforce up close), security cameras (purple) and webcams (blue). The legend filters them by kind.
Tapping one shows what it is, its name, the speed limit (in the speed unit chosen), which way it
faces, what it watches (a public place, outdoors, indoors, traffic), whether it reads number plates,
who runs it, and links to its webcam's page and to its page on OpenStreetMap.

Coverage is what volunteers have mapped: about 190 speed cameras and 240 security cameras in
Israel, more in many European and US cities, and few webcams. An area is asked for once a day,
with a margin around the view (out to whole twentieths of a degree), and kept in memory. Overpass
servers get busy: when they turn the request away, the panel says so and the app asks again after
5 s, then 10, 20, up to two minutes, as Overpass asks clients to.

**Live webcams** (coming soon): webcams' pictures now, from Windy's Webcams API. It needs a key
(free, from a Windy account); the explorer shows it greyed, as coming soon, until then.

### In both levels

- **The legend filters.** Tapping a class hides those countries or places; tapping it again brings
  them back. The grey "No data" class can be hidden too.
- **The panel's button** jumps between the levels: to Israel at zoom 7, or out to the whole world.
- **Credits** are added to the map's credits line while the view is on.
- **Both languages.** The view works in English and in Hebrew, right to left.

## Where the data comes from

| Data | Publisher | Licence | How it's read | Size, first use |
|---|---|---|---|---|
| Country outlines, names and label points | [Natural Earth](https://www.naturalearthdata.com), 1:50m Admin 0 | public domain | bundled: `webApp/.../resources/countries.json` | 0.9 MB (about 0.25 MB compressed) |
| Countries' area, capitals, official languages and Wikipedia articles | [Wikidata](https://www.wikidata.org) (P2046, P36, P37, the sitelinks; matched by Natural Earth's Wikidata id) | CC0 | bundled, in the same file | about 40 kB |
| Countries' flags | [Wikimedia Commons](https://commons.wikimedia.org), each country's flag on Wikidata (P41) | public domain, CC0 or CC BY (`flags/LICENCES.md`) | bundled: `webApp/.../resources/flags/`, one loaded when its card opens | about 4 kB each, 1 MB in all |
| Country figures | [Our World in Data](https://ourworldindata.org) grapher CSVs | CC BY 4.0, and each source's own terms | downloaded per figure, when chosen | 0.3–2.2 MB each |
| Localities of Israel | Central Bureau of Statistics (הלמ"ס), *localities-in-israel* on [data.gov.il](https://data.gov.il) | marked open | CKAN API: the dataset's newest table | about 1.5 MB |
| Residents by age group | Population and Immigration Authority, *residents_in_israel_by_communities_and_age_groups* | **none stated on the portal** | CKAN API, newest table (updated monthly) | about 0.3 MB |
| Socio-economic clusters (2019) | *social_economic_cluster* on data.gov.il | marked open | CKAN API | small |
| Time zones | [timezone-boundary-builder](https://github.com/evansiroky/timezone-boundary-builder) 2026f, *timezones-with-oceans* | ODbL (from OpenStreetMap: © OpenStreetMap contributors) | bundled: `webApp/.../resources/timezones.bin` | 0.3 MB |
| GPS traces | [OpenStreetMap](https://www.openstreetmap.org/traces)'s public GPS traces, as tiles from `gps.tile.openstreetmap.org` | © OpenStreetMap contributors; OpenStreetMap's tile usage policy | map tiles, as the map shows them | per tile |
| Rain radar | [RainViewer](https://www.rainviewer.com/api.html) | free for personal and community projects, with credit ("Radar © RainViewer") | its list of pictures (`weather-maps.json`), then map tiles to zoom 7 | about 1 kB, then per tile |
| Weather maps | [NOAA](https://www.nco.ncep.noaa.gov/pmb/products/gfs/)'s GFS model at 0.5°, made into pictures by MapAround's pipeline (`raw.githubusercontent.com/adam2314/my-pipeline/weather/`, or your own in Settings → Sources) | public domain (US government work); credited | `manifest.json`, then two pictures for each hour shown | about 1 kB, then about 0.6 MB an hour |
| Weather now and forecasts | [Open-Meteo](https://open-meteo.com) | data CC BY 4.0; its free API is for non-commercial use (under 10,000 requests a day) | its forecast API: the points over the view in one request, a forecast when one is tapped | a few kB |
| Street photos | [Panoramax](https://panoramax.fr) (`api.panoramax.xyz`, the catalogue of all its servers) | each photo's own, mostly CC BY-SA 4.0 or Etalab 2.0 (shown with it) | vector map tiles to zoom 15; a tapped photo's details and pictures | per tile; about 0.5 MB a photo |
| Cameras | [OpenStreetMap](https://www.openstreetmap.org) (`man_made=surveillance`, `highway=speed_camera`, webcam links), through Overpass (overpass-api.de, then overpass.private.coffee; or your own) | ODbL (© OpenStreetMap contributors) | an Overpass query for the area on screen, kept a day | a few to some tens of kB |
| Buses now | [Open Bus](https://open-bus-stride-api.hasadna.org.il/docs) (the Public Knowledge Workshop), from the Ministry of Transport's SIRI and GTFS | the Ministry's open data, republished; credit given | its API: the newest snapshot, the buses in the area, the lines seen | 0.1–1 MB a minute, by the area |

All the online sources allow a web page to read them directly (CORS `*`), so the browser fetches
them itself; no server is involved.

**Left out, and why:**
- **Planes:** adsb.lol and OpenSky both answer, but neither lets another site read them in the
  browser (no CORS for other sites), so planes need a small relay server. OpenSky also limits
  anonymous use to about 400 requests a day.
- **Mapillary:** needs an access key (free, but registered); Panoramax doesn't.
- **Google Street View inside the app:** not allowed by Google's terms on a non-Google map; the
  photo card links to it instead.
- **Boats (AIS):** aisstream.io's key must stay on a server, so boats need a relay too.
- **Traffic cameras' pictures:** no open source covers Israel (Netivei Israel's cameras have no open
  feed); OpenTrafficCamMap (MIT) is US-only and its pictures come from servers browsers can't read
  from another site. Windy's webcams are the planned way to see pictures.
- **The World Bank's own API:** its terms allow non-commercial use only and forbid derivative works,
  which doesn't fit a GPL app, and it didn't answer when tested. Its development indicators reach
  us through Our World in Data instead.
- **The CIA World Factbook:** discontinued in February 2026. The CIA's announcement doesn't say
  where the data now lives. Its last edition is public domain, but frozen.

**Before relying on the age data publicly**, check the Population Authority's terms: the portal
lists no licence for it.

## How it's built

All the logic is shared Kotlin; only the web app switches it on.

| Part | Where | What it does |
|---|---|---|
| Israel's grid | `shared/.../stats/Itm.kt` | The CBS gives each locality's place as one number, 12 digits: east and north in metres on the Israeli Transverse Mercator. This turns it into latitude and longitude (inverse transverse Mercator on GRS80; the datum's few metres' offset is left out). |
| Localities | `shared/.../stats/Localities.kt` | Reads the three data.gov.il tables and joins them by locality code. Field names are matched by their start (the CBS puts the year in some). Places without people or coordinates are left out. |
| Country figures | `shared/.../stats/CountryStats.kt` | Reads an Our World in Data CSV and keeps the latest year with a value per ISO 3166 code, up to this year. |
| Classes and layers | `shared/.../stats/StatsLayers.kt` | Each figure's classes, colours and legend labels, and the GeoJSON for the map: countries with a `color` per feature, localities with `color` and a radius `r`. |
| Loading and cache | `shared/.../stats/StatsData.kt` | Downloads each source the first time it's needed and keeps it for a week in the app's files (`/data/stats` on the web); offline, it uses the kept copy however old. |
| Time zones | `shared/.../stats/TimeZones.kt` | Reads `timezones.bin`; finds the zone at a place (outer rings and their holes: Lesotho is a hole in South Africa's zone); the offsets' classes and colours; the next clock change (a day at a time, then halving to the minute); and the GeoJSON: each zone's area and a label point in its biggest part. |
| Weather | `shared/.../stats/Weather.kt` | The lattice of points over a view, Open-Meteo's answers (now, forecast), the classes and colours, °C or °F, the points' GeoJSON, what a weather code says; and `RainRadar`: RainViewer's pictures as a map layer. |
| Weather maps | `shared/.../stats/WeatherMap.kt` | The pipeline's manifest (run, hours, grid, ranges), the hour nearest now, each field's colour scale and legend, and the spec the web map draws from. |
| Weather maps' drawing | `webApp/.../resources/weather.js` | The WebGL layer: the field coloured by its scale, and the wind's particles with their trails. Web only. |
| Weather maps' pictures | `weather.py` in the my-pipeline repository | Picks the newest complete GFS run, downloads its fields for each hour, and writes the PNGs and `manifest.json` (its workflow publishes them every six hours). |
| Street photos | `shared/.../stats/StreetPhotos.kt` | Panoramax's tiles and a photo's details (pictures, date, author, licence, all-round or not), its viewer's link and Street View's. |
| Buses | `shared/.../stats/Buses.kt` | Open Bus's newest snapshot, the buses in an area (each ride's last position), the lines from the timetable (number, operator, from and to), Israel's day, the GeoJSON. |
| Cameras | `shared/.../stats/Cameras.kt` | The Overpass query and its answer (kind, limit, direction as degrees or a compass point, webcam link), the area asked for around a view, the GeoJSON. |
| Live layers on screen | `ui/DataLiveUi.kt` | Keeps the radar, the weather points and the buses up to date while they're on (asking again when the view moves), and the weather maps' run and hour (with the hour's slider); their notes and credits; the weather, photo and bus cards. |
| Map layers | `MapStyle.kt` | Three GeoJSON sources: the time zones (a fill, white borders, and labels from the zoom their size allows), a fill for the countries (to MapLibre's zoom 7) and circles for the localities (from 6); rasters for the GPS traces and the rain radar, and Panoramax's vector tiles, between the fills and the
circles; the buses and the weather's points over them. The ids of what can be tapped start with `tap-`, so a tap reports the feature's `tap` key (`data:c:ISR`, `data:l:3000`, `data:z:Asia/Jerusalem`). |
| Screen | `ui/DataViewUi.kt`, `ui/MapScreen.kt` | The explorer's drawer (`DataExplorerDrawer`: search, what's on, the tree), the panel (`DataViewPanel`: legend, notes, level button), the country, locality and time-zone cards, and the Layers switch. |
| Texts | `i18n/StatsTexts.kt`, `i18n/LiveTexts.kt` | English and Hebrew. |
| Countries file | `tools/stats/make_countries.py` | Builds `countries.json` from Natural Earth (ISO code, English and Hebrew names, the outline simplified to about 1.5 km, the label point and its zoom) and Wikidata's query service (area, capitals and official languages, named in English and Hebrew, the languages most spoken first; Wikipedia titles); `--no-wikidata` builds it offline without them. With `--flags <folder>` it also writes the flags from Commons (free licences only) and their `LICENCES.md`. |
| Time zones file | `tools/timezones/make_timezones.py` | Builds `timezones.bin` from timezone-boundary-builder's GeoJSON: each zone's IANA name and polygons, simplified to about 2 km (smaller islands and slivers left out), as varint deltas. |

The view appears only where the app provides `LocalStats` (the web app, in `WebApp.kt` and
`Main.kt`). Android and iPhone would need only that: a `StatsData` with a cache folder and the
outlines file from their assets.

Tests: `shared/src/commonTest/.../stats/StatsTest.kt` covers the grid conversion, the tables, the
CSV, the classes and the layers; `TimeZonesTest.kt` the time zones' file, lookup (with holes),
classes, labels, GeoJSON and clock changes; `androidUnitTest/.../TimeZonesFileTest.kt` the real
file (known places, Israel's next change, every zone known to Java's time-zone rules);
`LiveDataTest.kt` the weather's lattice, Open-Meteo's, RainViewer's, Panoramax's, Open Bus's and
Overpass's answers (from real ones), the weather's classes and units, the buses' lines and places,
and the cameras' kinds, directions and area; `WeatherMapTest.kt` the weather maps' manifest, the
hour nearest now, the spec and the legends. In my-pipeline, `test_weather.py` covers splitting
NOAA's GRIB files and packing the pictures.

## Adding data

### Another layer in the explorer

Add a leaf to its branch in `Explorer` (`ui/DataViewUi.kt`), or a new branch, with its title and
the words that should find it (in `StatsTexts` or `LiveTexts`, English and Hebrew); keep its state
in `DataLayers`. Draw it in `MapStyle.overlayLayers` and fill it from `MapScreen`'s data-view
effect, or, for data about now, from `rememberLiveLayers` (`ui/DataLiveUi.kt`) through
`setDataPoints` (points) or `setDataTiles` (tiles). A layer over tiles that aren't ours is tapped
through its metadata (`"mr:tap"`: the key's prefix, `"mr:tapKey"`: the property after it), as the
street photos are. An online source goes in `SourceKind` (group `DATA`) so Settings → Sources lists
it, can turn it off, and (where it has an address) point it at another server.

### Updating the time zones

Download `timezones-with-oceans.geojson.zip` from timezone-boundary-builder's latest release and
run `python tools/timezones/make_timezones.py combined-with-oceans.json
webApp/src/wasmJsMain/resources/timezones.bin`. Zones that the browsers don't know yet show as
"no data" until they do.

### Another figure for the countries

1. Find the chart on Our World in Data. Its CSV is
   `https://ourworldindata.org/grapher/<slug>.csv?v=1&csvType=full&useColumnShortNames=true`.
   Check that the value is the first column after `year`; the parser takes that column.
2. Add an entry to `CountryIndicator` with the slug and six breaks.
3. Add its name and its value format to `StatsTexts`, in English and Hebrew.
4. Add the source to the credits if it isn't Our World in Data's own.

### Another way to colour the localities

Add an entry to `LocalityIndicator` with its `classes` and its `classOf`, then its name in
`StatsTexts`. If it needs a new field, read it in `IsraelLocalities.parse`, match the field name by
its start, and add it to `Locality`.

### Another country's places

Write an adapter like `IsraelLocalities`: read the publisher's table, turn its coordinates into
latitude and longitude, and produce `Locality`-like records. Countries publish different figures,
so the classes may need to be per country.

### Regions (states, districts)

Country outlines are bundled; region outlines are too many to bundle. Load them per country when
zoomed in: [geoBoundaries](https://www.geoboundaries.org) has ADM1 and ADM2 for every country, under
CC BY 4.0 (credit required), callable from the browser.

## Next: more data to explore the world

Ordered roughly by value for the effort.

**1. Make what's there go further.**
- **Time:** a year slider for the countries. Our World in Data's CSVs already hold every year, so it
  costs no new download.
- **More country figures:** for example fertility, urban share, CO₂ per person, internet use,
  literacy, forest cover, from Our World in Data (check each chart's source licence).
- **Find and compare:** a searchable list of places sorted by the chosen figure, and two places side
  by side.
- **Share a view:** the figure and level in the page's address, as the map's `#map=` is already.

**2. Places everywhere, not only in Israel.** [Wikidata](https://www.wikidata.org) (CC0: no
conditions) has population, elevation, founding date and area for cities and towns worldwide,
with names in many languages. Query the places in view through its SPARQL service, and cache them
by area. Its limits apply: 60-second queries, 5 at a time per address, a User-Agent required.

**3. Regions of every country.** geoBoundaries outlines, with regional figures where they're open:
national statistics offices, and Eurostat for Europe (its terms and API to check: its site blocked
the research request).

**4. Israel in more depth.** Still on data.gov.il, with each dataset's licence checked:
- municipal indices (education, budgets and more, about 1.4 million rows, so filter them before
  loading)
- election results by locality

**5. Data that fits a hiking and off-road app.** Protected areas, climate normals (temperature and
rain by month), daylight (sunrise and sunset by place and date, worked out on the device like the
time zones' clocks), the rain radar's past two hours as an animation, Israel's own weather
stations and radar (the Israel Meteorological Service gives a token on request), planes and boats
through a small relay, and Windy's live webcams once there's a key. Licences vary here: the main world protected-areas database restricts
commercial use, for example. Check each one before using it.

**6. Android and iPhone.** The code is shared; provide `StatsData` on each.

### Principles for adding data

- **Licences:** open and compatible with a GPL app that's redistributed. Credit every source on the
  map, and check each dataset's own licence, not only the portal's.
- **Access:** readable from a browser (CORS), or built into a small file by a tools script.
- **Size and caching:** download data only when first used, keep it, and show it offline.
- **Languages:** names and labels in English and Hebrew, and Hebrew reads right to left.
- **Colours:** palettes that colour-blind users can tell apart, with grey for "no data".
- **Privacy:** no tracking. Requests go straight to the publishers, carrying nothing about the user.
