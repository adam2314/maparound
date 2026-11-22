---
title: Offline maps · MapAround guide
lang: en
---

[עברית]({{ '/he/guide/offline-maps' | relative_url }}) · [All guides]({{ '/guide/' | relative_url }})

# Offline maps

An offline map is a file on your phone that MapAround draws the map from, so the map works with no
signal at all: in a canyon, on a ridge, abroad without data. You get them in the **Offline** tab,
on the **Offline maps** screen.

## MapAround's own maps

The maps in **Download maps** are MapAround's build of [OpenStreetMap](https://www.openstreetmap.org),
made every month by an open pipeline ([my-pipeline](https://github.com/adam2314/my-pipeline)) in
the mapsforge `.map` format. Besides roads, paths, places and land cover, they have the outdoor
details built in:

- marked hiking trails, in their colours, with their names;
- cliffs and ridges;
- how hard a path is (the SAC scale) and how visible the trail is on the ground;
- surfaces, and tracks that only a 4×4 can drive;
- fords, glaciers, huts and water points.

The list goes by continent, country and region. The biggest whole countries (France, Germany,
Italy, Great Britain, Japan and a few more) need funding to be built; download their regions
instead.

**Basic maps from mapsforge.org** (a switch above the list) shows the maps of
[mapsforge.org](https://mapsforge.org) instead: the older maps, without the marked trails and the
outdoor details. They fill gaps while MapAround's list grows.

**World overview.** Android and the web app have a world map built in, drawn when you zoom out and
around your maps, so the map is never blank. On iPhone, the 3 MB **World overview** download does
this.

## Downloading a map

1. In **Download maps**, open a continent, then a country, and tap the download button beside a
   map. **Filter** finds a name in the list.
2. The app tells you the map's size and the free space on your phone before it starts.
3. With **Download over Wi‑Fi only** on, the download waits for Wi‑Fi instead of using mobile data.

On Android, downloads carry on in the background and pick up again after a dropped connection.
Looking at a place none of your maps covers, the map says **No offline map here** and names the
map to get; **Get map** opens the list right there.

When MapAround builds a newer version of a map you have, the list shows **Update** beside it.

## Online where no map is downloaded (Android)

Where none of your maps reaches, the map reads the region's map over the internet as you look
around, in the same style: MapAround's map where there is one (marked trails and all), else the
basic map from mapsforge.org. Only the parts you look at are read, a few megabytes for a town and
the country around it, and they're kept for a while (up to 512 MB; the oldest go first). The
banner then says **shown online**, and **Get map** downloads the map for when there's no signal.

With no signal, places you looked at before still show; the others fill in by themselves once
there's a connection again. To use no data for it, turn off **Map layers → Online where none is
downloaded**.

## On this device

**On this device** lists your maps and the space they take. Tap a map to see it on the map. The
**⋮** button beside a map adds extras for its area (MapAround's maps have the marked trails built
in: see [Marked trails]({{ '/guide/marked-trails' | relative_url }})):

<p class="shots"><img src="{{ '/guide/img/offline-list.webp' | relative_url }}" alt="Offline maps: the map on the phone with its terrain, routing and trails, and the raster maps" width="720" height="1195" loading="lazy"><img src="{{ '/guide/img/offline-menu.webp' | relative_url }}" alt="A map's ⋮ menu: contours and hill shading, offline routing, delete" width="720" height="1195" loading="lazy"></p>

- **Contours & hill shading**: elevation data for the whole map. See [Elevation data]({{ '/guide/elevation' | relative_url }}).
- **Offline routing**: route planning without a connection (Android). See
  [Offline routing]({{ '/guide/offline-routing' | relative_url }}).
- **Delete map** frees its space. You can download it again later.

## Importing a map file

**Import a map file**, at the top of the screen, takes any kind of map MapAround reads:

- **A mapsforge `.map` file**, for example one downloaded from the web app or from
  [OpenAndroMaps](https://www.openandromaps.org). It's copied into the app. A file named the way
  MapAround's downloads are, such as `europe__liechtenstein.map`, is recognised as that map, so its
  updates show in the list.
- **Raster maps** (`.rmap`, `.mbtiles`, `.sqlitedb`), on Android. They're read where they are, not
  copied. See [Raster maps]({{ '/guide/raster-maps' | relative_url }}).

On Android you can also tap a downloaded `.map` file in Chrome's downloads or the Files app and
choose **Open with MapAround**, or share it to MapAround.

## In the web app

The [web app]({{ '/app/' | relative_url }}) keeps its maps in your browser's own storage for the
site. Getting one takes two steps: tapping download in the list starts a normal browser download
(GitHub, where the maps are hosted, doesn't let web pages read the files directly), and then you
add the file with the import button. The mapsforge.org maps aren't offered there. Clearing the
site's data in the browser deletes the maps.

## Space

A map takes from a few megabytes for a small region to several hundred megabytes or more for a
big country. The list shows each map's size, and **On this device** the total. The extras are
listed in their own sections on the same screen, each with a bin to delete them.

## Privacy

Using an offline map sends nothing anywhere. The list of maps comes from GitHub, and the maps
themselves from GitHub's download servers (or mapsforge.org for the basic maps), which see your
IP address and which maps you download. Reading a map online (above) asks the same servers for the
parts you look at, so they can tell roughly where on the map you are looking. See the
[privacy policy]({{ '/privacy' | relative_url }}).

## Licence

Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), under the Open
Database License (ODbL).
