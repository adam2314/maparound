---
title: Raster maps · MapAround guide
lang: en
---

[עברית]({{ '/he/guide/raster-maps' | relative_url }}) · [All guides]({{ '/guide/' | relative_url }})

# Raster maps

A raster map is a map made of pictures: a scanned paper map, an official topographic sheet, or
tiles saved from a map website. MapAround shows it under its own vector map or instead of it, with
no connection. Raster maps work on Android for now; iPhone comes later, and the web app doesn't
read them yet.

## What MapAround reads

- **GeoTIFF** (`.tif`), the format of most scanned maps and orthophotos from mapping agencies and
  GIS software. MapAround places it by the coordinate system in the file: latitude/longitude, UTM
  (WGS 84, ETRS89, NAD83/27, ED50, GDA), Web Mercator, the Israeli grid (ITM), the German
  Gauss-Krüger zones, the Greek, British, Irish, Swedish, Finnish, Polish, Croatian, Slovenian and
  New Zealand grids, Lambert-93 and Belgian Lambert, or a Transverse Mercator, Lambert or Mercator
  projection described in the file. Uncompressed, LZW, Deflate, PackBits, JPEG or WebP; tiled or
  in strips; colour, grey, palette or black and white; transparent where the file has no data.
  A big GeoTIFF with no reduced copies of its own gets them made once when you add it (it shows
  how far along), so it draws quickly zoomed out.
- **TwoNav / CompeGPS maps** (`.rmap`), for example scanned 1:50,000 topographic sheets. MapAround
  places them by the calibration in the file. It understands UTM, latitude/longitude, Mercator,
  the German Gauss-Krüger zones and the Israeli grid (ITM); a map in another projection is placed
  approximately, and the list says so.
- **Tile databases**: raster **MBTiles** (`.mbtiles`) and **SQLiteDB** (`.sqlitedb`), as made by
  [QGIS](https://qgis.org) (its *Generate XYZ tiles (MBTiles)* tool), [GDAL](https://gdal.org),
  [MOBAC](https://mobac.sourceforge.io) (Mobile Atlas Creator), Locus or OsmAnd.

MBTiles files that hold vector data rather than pictures, and maps in Yandex's elliptical Mercator
projection, can't be shown; MapAround says so when you add one. Nor can GeoTIFFs of measurements
(elevation, temperature) rather than a picture.

**ECW and JPEG 2000** (`.ecw`, `.jp2`) can't be opened: ECW's decoder isn't open source. Turn the
file into a GeoTIFF on a computer first, with [QGIS](https://qgis.org) (right-click the layer →
**Export → Save As…**, format **GeoTIFF**) or with GDAL: `gdal_translate -of COG map.ecw map.tif`.

## Adding one

1. Copy the file to your phone (Downloads, or any folder).
2. On the **Offline maps** screen, tap **Import a map file** at the top, or **Add a raster map
   (.tif, .rmap, .mbtiles, .sqlitedb)** in the **Raster maps** section, and pick the file.
3. MapAround shows it on the map straight away.

<p class="shots"><img src="{{ '/guide/img/raster-add.webp' | relative_url }}" alt="Offline maps → Raster maps: what can be added" width="720" height="1195" loading="lazy"></p>

Big files are **read in place, not copied**, so a 2 GB map doesn't take another 2 GB. That also
means the file has to stay where it is: if you move or delete it, add it again.

Each map in **Raster maps** shows its size, its pixels or zoom levels, and its projection. Its
**⋮** button has **Show on map** and **Remove**. Remove takes it off the list; the file itself
stays on your phone.

## Showing it on the map

A raster map is the **background of the offline map**. Open **Map layers**, and under
**Background of the offline map** choose the raster map, the map's own colours, or satellite
imagery.

<p class="shots"><img src="{{ '/guide/img/raster-background.webp' | relative_url }}" alt="Map layers → Background of the offline map: map colours, satellite imagery or a raster map" width="720" height="1195" loading="lazy"></p>

- **Roads, trails & labels on top** draws the vector map's roads, paths, marked trails, contours
  and names over the raster map. A scanned map already has its own, so it starts with this off;
  switch it on to see your downloaded map's trails on the old sheet.
- Zoomed in past the scan's detail, the picture is enlarged and smoothed.

The 3D view and Android Auto use the same background.

## Where to get raster maps

- **National mapping agencies** sell or publish scanned topographic maps and orthophotos, often as
  GeoTIFF, some as `.rmap` files for TwoNav devices. Maps sold with copy protection may not open.
- **Your own scans**: georeference them in QGIS (its Georeferencer saves a GeoTIFF) or TwoNav's
  desktop software, and save them as GeoTIFF, MBTiles or `.rmap`.
- **MOBAC** saves an area of an online map as a SQLiteDB or MBTiles file. Check the map service's
  terms first: many don't allow bulk downloads.

## Space

A raster map takes the space of its file and nothing more, since it isn't copied. Scans at a
detailed scale are big: a whole country at 1:50,000 can take several gigabytes, so you may prefer
the sheets for the area you're going to.

## Privacy

Raster maps are read on your phone with the access you give when you pick the file. Nothing about
them is sent anywhere.

## Limits

- Android only for now.
- The picture is what it is: it doesn't know about paths, so search, routing and *What's here?*
  use your vector maps, not the raster map.
- An approximate placement (unknown projection) can be off by some metres; check it against the
  vector map with **Roads, trails & labels on top**.
