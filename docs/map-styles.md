# Map styles and online sources

The offline maps are drawn with a **style**: a mapsforge *render theme*, an XML file. Pick one in
the map's **Layers** sheet under *Offline vector maps → Style*. Below it are the style's
**options**, switches that show or hide parts of the map. You can import your own styles.

Online maps are XYZ tile **sources**: the built-in ones, and any you add or import as a list.

## Built-in styles

| Style | What it is | Options |
|---|---|---|
| **Topo v2** (the default) | Topo in the look of [Tracestrack Topo](https://wiki.openstreetmap.org/wiki/Tracestrack_Maps) (`app/src/main/assets/themes/topo2.xml`): light green forests, grey contours, OpenStreetMap Carto's road colours at Tracestrack's widths; Topo's dirt roads, paths by difficulty, bike paths, marked trails and points of interest | Topo's |
| **Topo** | MapAround's first hiking style (`app/src/main/assets/themes/topo.xml`): contours, marked trails, peaks | see below |
| **Classic** | mapsforge's default theme | none |
| **Osmarender** | the classic OpenStreetMap look | none |
| **Biker** | mapsforge's cycling theme | its own |
| **Motorider** | mapsforge's road theme | its own |

A style picked stays; until one is, and when the picked one is gone, the map is in Topo v2. An
embedded map (the web's `?embed`) has Topo v2 only. On iPhone and on the web, Topo v2 and Topo are
available, with all their options.
Every platform turns the theme into a MapLibre style; area patterns aren't drawn yet.

Topo v2 takes its colours and road widths (zoom by zoom, from `roads.mss`) from Tracestrack's stylesheet
([tracestrack/tracestrack-topo-map](https://github.com/tracestrack/tracestrack-topo-map), CC0-1.0),
and borders, protected areas and railways from OpenStreetMap Carto, which Tracestrack follows. Its
peak and saddle icons are Tracestrack's (CC0), in `themes/symbols/tracestrack/`. Its dirt roads,
paths, bike paths and marked trails are Topo's rules as they are, so a change there goes in both
files. It isn't Tracestrack's map, only its look.

### Topo's options

Topo v2 has the same. All are on at first.

| Option | What it hides when off |
|---|---|
| Land cover | forest, fields, parks, built-up areas: a plain cream map |
| Buildings & barriers | buildings, walls, fences |
| Boundaries & protected areas | country/region borders, national parks, nature reserves |
| Railways, ferries, ski pistes & lifts | rail lines, ferry routes, pistes, aerial ways |
| Road & path names | street and path labels |
| Place names | towns, villages, localities |
| Peaks, springs & caves | peaks with their height, passes, springs, caves |
| Points of interest | shops, food, lodging, parking and the other POI symbols |

Contour lines, hill shading and marked trails have their own switches in the same sheet, since
they come from separate downloads.

## Importing a style (Android)

*Layers → Offline vector maps → Import a style (.xml or .zip)*, then pick:

- **an `.xml` render theme**, if all its symbols come from mapsforge (`src="jar:…"`, see below) or
  it has none;
- **a `.zip`** with one or more themes and the folders of symbols they use. Every theme in it
  becomes a style.

The import is checked with mapsforge's own reader first, and refused with the reason if it can't be
read. Imported styles are kept in the app's files (`files/themes/<name>/`). The bin icon next to
one removes it, and removing the last style of an import also removes its symbols.

Where to find styles:
- [mapsforge's list of themes](https://github.com/mapsforge/mapsforge/blob/master/docs/Mapsforge-Themes.md);
- OpenAndroMaps' *Elevate* and *Voluntary* themes. They are written for OpenAndroMaps' own maps,
  which tag some things differently from the maps on download.mapsforge.org, so some features may
  be missing with those maps.

Check each theme's licence before you share it further.

## Writing a style

A render theme is a list of **rules** that match map elements by tag and zoom, with the
**instructions** that draw them. The full reference is mapsforge's
[Rendertheme documentation](https://github.com/mapsforge/mapsforge/blob/master/docs/Rendertheme.md)
and its [XML schema](https://github.com/mapsforge/mapsforge/blob/master/resources/renderTheme.xsd).
In short:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<rendertheme xmlns="http://mapsforge.org/renderTheme" version="5"
    map-background="#F8F4E8" map-background-outside="#EEEEEE">

    <!-- e = node | way | any; k / v = keys / values, | separates choices, ~ = absent, * = any -->
    <rule e="way" k="natural" v="water" closed="yes">
        <area fill="#9CC3E6" />
    </rule>
    <rule e="way" k="highway" v="path|footway" zoom-min="13">
        <line stroke="#A0522D" stroke-width="0.6" stroke-dasharray="3,2" />
        <pathText k="name" font-size="11" fill="#5B3A1E" stroke="#FFFFFF" stroke-width="2" />
    </rule>
    <rule e="node" k="natural" v="peak" zoom-min="12">
        <symbol src="jar:symbols/peak.svg" symbol-width="12" />
        <caption k="ele" dy="-12" font-size="10" fill="#4A3B2A" />
    </rule>
</rendertheme>
```

- Instructions: `area`, `line`, `lineSymbol`, `caption`, `pathText`, `symbol`, `circle`, and
  `hillshading` (where the hill shading goes in the drawing order).
- Rules nest, and an inner rule only applies within its outer one. Drawing follows the order in the
  file; labels and symbols are placed after everything else.
- Sea: mapsforge maps cover the sea with areas tagged `natural=sea` or `issea`, and the land in
  them with `natural=nosea` or `land`. If you fill the sea, draw `nosea`/`land` afterwards in the
  land colour (as Topo's first rules do), or the whole map turns blue.
- Symbols: `jar:symbols/…` and `jar:patterns/…` use the ones that come with mapsforge (in the
  [mapsforge-themes](https://github.com/mapsforge/mapsforge/tree/master/mapsforge-themes/src/main/resources/assets)
  module); `file:symbols/x.svg` is a path relative to the theme's XML (put both in the `.zip`).

### Options: the style menu

A `<stylemenu>` (first in the theme) makes parts of the theme switchable. Rules and instructions
get a category with `cat="…"`. Each **layer** of the menu names the categories it draws. Layers
with `visible="true"` are the *variants* to pick from, and the **overlays** they list become the
switches (`enabled="true"` means on at first). A layer with `parent="…"` also gets its parent's
categories and overlays. A rule without `cat` is always drawn.

```xml
<stylemenu id="mystyle" defaultvalue="hike" defaultlang="en">
    <layer id="pois" enabled="true">
        <name lang="en" value="Points of interest" />
        <cat id="pois" />
    </layer>
    <layer id="base">
        <cat id="base" />
    </layer>
    <layer id="hike" parent="base" visible="true">
        <name lang="en" value="Hiking" />
        <cat id="paths" />
        <overlay id="pois" />
    </layer>
    <layer id="drive" parent="base" visible="true">
        <name lang="en" value="Driving" />
        <cat id="roads" />
        <overlay id="pois" />
    </layer>
</stylemenu>

<rule cat="pois" e="node" k="amenity" v="*"> … </rule>
```

Topo wraps whole sections in `<rule cat="…" e="any" k="*" v="*">`. That is an easy way to make an
existing theme switchable without touching its rules.

### MapAround's own map data

MapAround adds ways that aren't in the map files. Imported styles (like Topo and Topo v2) get
all of them, mapsforge's built-in ones only the country borders. Style them to show them:

| Ways | Tags | From |
|---|---|---|
| Contour lines | `contour=minor` or `contour=major`, `ele=<metres>` | the terrain download, traced on the phone; intervals depend on the zoom |
| Marked hiking trails | `trail=hiking`, `trail_colour=red\|blue\|green\|yellow\|black\|white\|orange\|purple…`, `trail_offset=0\|l05\|r05…`, on a dirt road `trail_track=any\|high\|4x4\|minor` (how Topo draws the road), and on the first colour of a way `trail_n=<colours on it>`, `name`, `ref` | the marked-trails download; one way per waymark colour, offset side by side |
| Cliffs, arêtes, ridges, earth banks | `natural=cliff\|arete\|ridge\|earth_bank`, as in OSM | the marked-trails download (the mapsforge maps leave these out); from zoom 13, also when the trail lines are switched off |
| The world map: sea and land, lakes, rivers | `natural=sea` (the tile) and `natural=nosea` (land) like the mapsforge maps, `natural=water`, `waterway=river` with `name` | built into the app (Natural Earth): up to zoom 7 everywhere (the country maps start at 8), and above that outside the downloaded maps (their rectangles for sea, land and lakes; their regions' outlines for rivers, borders and names). Every style gets these, built-in ones too |
| Country borders | `boundary=administrative`, `admin_level=2`, and `disputed=yes` on disputed borders, lines of control and undefined frontiers | the same |
| Names (points) | `place=country`, `place=city` (`capital=yes` for capitals), `place=town`, with `population`; `place=ocean`, `place=sea` (also gulfs, bays, straits); `natural=water` for lakes; all with `name` | the same, each from the zoom Natural Earth suggests for it |

See the contour and trail sections of `topo.xml` for working examples.

### The legend

Layers → Legend shows any style's legend, imported ones too, made from the style's own rules:
sample features (a road of each kind, a track of each `tracktype`, a path of each `sac_scale`, a
trail of each colour, water, land cover, terrain, borders, huts and other places) are matched
against the rules as the map does, close up, with the style options that are on. Whatever the
style draws for one is its sample; a variant (a track grade, a path's difficulty) shows only when
the style draws it differently from the plain kind. Nothing to add to a style for it.

To check a style on a PC, `tools/topo-preview` renders any theme file with the same engine (see
the README).

The website has the same legend for Topo, in full: [the map legend](https://maparound.app/map/legend)
with each feature's tags, the zoom it shows from and its colours and widths, and
[what the maps contain](https://maparound.app/map/schema), every tag my-pipeline's
`tag-mapping.xml` keeps (in English and Hebrew). They're made by `tools/map-docs` from `topo.xml`, the
app's legend code and a my-pipeline checkout; run it again after changing either:

```
./gradlew :shared:compileDebugKotlinAndroid
./gradlew -p tools/map-docs run --args="--pipeline ../my-pipeline"
```

## Online sources

The built-in sources are listed in `shared/…/map/TileSources.kt`:
- worldwide: OpenStreetMap France, OpenStreetMap Germany, OpenTopoMap, CyclOSM, Humanitarian, Esri
  World Topo, Esri World Street, Satellite (Esri World Imagery);
- national open data, for their own country only: swisstopo (Switzerland), basemap.at (Austria),
  Kartverket Topo (Norway), Plan IGN and IGN aerial (France), USGS Topo (United States).

The standard OpenStreetMap server (tile.openstreetmap.org) isn't among them because it refuses
this app. Its [tile usage policy](https://operations.osmfoundation.org/policies/tiles/) asks
apps for a User-Agent with contact details, and heavy users need their own tile server.

**Add custom tile source** takes any XYZ template:

| Placeholder | Becomes |
|---|---|
| `{z}` `{x}` `{y}` | zoom, column, row (Google/OSM numbering) |
| `{-y}` | the row counted from the bottom (TMS) |
| `{s}` | a subdomain, rotating through a, b, c |

WMTS services in Web Mercator work too, with `TILEMATRIX={z}&TILEROW={y}&TILECOL={x}`, as Plan IGN
does.

### Importing and exporting the list

*Layers → Online → Export list* shares your custom sources as `maparound-tile-sources.json`, and
**Import list** reads it back on another phone. Sources already in the app are skipped.

```json
{
  "type": "maparound-tile-sources",
  "version": 1,
  "sources": [
    {
      "name": "My topo",
      "url": "https://{s}.tiles.example.org/topo/{z}/{x}/{y}.png",
      "maxZoom": 17,
      "attribution": "© Example",
      "subdomains": ["a", "b"]
    }
  ]
}
```

Import also takes a plain JSON array of such entries (`url` may be named `urlTemplate` or
`template`, `maxZoom` `max_zoom`). It also takes a text file with one template per line, optionally
after a name, with `#` for comments:

```
# my sources
Hiking map | https://hike.example.com/{z}/{x}/{y}.png
https://tiles.example.net/{z}/{x}/{-y}.jpg
```

Check each provider's terms of use before adding it.
