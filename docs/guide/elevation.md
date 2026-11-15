---
title: Elevation data · MapAround guide
lang: en
---

[עברית]({{ '/he/guide/elevation' | relative_url }}) · [All guides]({{ '/guide/' | relative_url }})

# Elevation data

Elevation data is a grid of ground heights. MapAround draws the map's **contour lines** and **hill
shading** from it on your phone, and uses it for **line of sight**, the **viewshed**, the heights
in the map tools and the route planner's **Line** mode, and the **3D view**. It works offline once
it's on your phone.

## What it is

The heights come in tiles of 1° latitude by 1° longitude (about 110 × 80 km in Europe), named by
their south-west corner: `N47E009.hgt` covers 47–48° N, 9–10° E. It's the `.hgt` format of NASA's
SRTM survey: at 1 arc-second, one height about every 30 m.

<p class="shots"><img src="{{ '/guide/img/elevation-map.webp' | relative_url }}" alt="Contour lines and hill shading over a mountain lake" width="720" height="1195" loading="lazy"></p>

## Where it comes from

By default, from [Terrain Tiles on AWS Open Data](https://registry.opendata.aws/terrain-tiles/), a
free worldwide dataset made by Mapzen from many public sources: SRTM, GMTED, ETOPO1 and national
surveys. Credit: © Mapzen and its sources (the
[full list](https://github.com/tilezen/joerd/blob/master/docs/attribution.md)). The map's credits
line says *terrain: Mapzen, AWS Terrain Tiles*.

## Getting it

You rarely need to think about it:

- **On the map**, once you zoom in (zoom 10 and closer) where you have offline maps, the tiles
  under the screen download by themselves, with their progress on the map.
- **Line of sight, the viewshed and the 3D view** fetch the few tiles their area is missing, show
  the progress and carry on.
- **A whole map's area at once**, before a trip: on the **Offline maps** screen, **⋮** beside a map
  → **Contours & hill shading**. Choose **High detail (30 m)** or **Standard (90 m)**, nine times
  smaller with less detail.

With **Download over Wi‑Fi only** on, nothing downloads over mobile data.

The **Terrain** section of **Offline maps** shows how many tiles you have and the space they take,
with **Delete all terrain data**.

## Space

| Detail | Per tile | Notes |
|---|---|---|
| High (30 m) | about 26 MB | Sharpest contours and line of sight |
| Standard (90 m) | about 3 MB | Fine for hill shading and overviews |

A small country takes a handful of tiles; the Alps take dozens. The app checks the free space
before it starts.

## In the web app

The web app fetches the same tiles through the browser and keeps them at standard detail (every
third sample, about 90 m), in the browser's storage for the site. Its 3D view reads separate
online image tiles of the relief.

## Using another source

Someone in your group may run a server with better heights for your area, or you may have
converted a newer model yourself. In the **Terrain** section, **Source → Change** takes the
address of the 1° files as a template:

<p class="shots"><img src="{{ '/guide/img/elevation-source.webp' | relative_url }}" alt="Offline maps → Terrain → Source: the address of the elevation tiles" width="720" height="1195" loading="lazy"></p>

- `{folder}` becomes the latitude part of the name, such as `N47`;
- `{name}` becomes the tile's name, such as `N47E009`;
- an address ending in `.gz` means gzipped files (`.hgt.gz`), otherwise plain `.hgt`.

The default is:

```
https://elevation-tiles-prod.s3.amazonaws.com/skadi/{folder}/{name}.hgt.gz
```

The files must be SRTM-style `.hgt`: 3601 × 3601 heights (1 arc-second) or 1201 × 1201 (3
arc-seconds). A tile the server doesn't have (sea, say) is skipped. **Use the default** in the same
dialog goes back to Terrain Tiles.

Tiles you already have stay as they are: delete them (**Delete all terrain data**) to fetch them
again from the new source. In the web app the server must allow cross-origin requests (CORS), or
the browser won't read it.

**The web app's 3D view** has its own template, for image tiles with `{z}/{x}/{y}`, and their
encoding: **Terrarium** (the default,
`https://elevation-tiles-prod.s3.amazonaws.com/terrarium/{z}/{x}/{y}.png`) or **Mapbox
Terrain-RGB**. It applies the next time the 3D view opens.

### An example: Copernicus GLO-30

The [Copernicus DEM GLO-30](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM)
is a newer worldwide model at 30 m, sharper than SRTM in many mountain areas. To use it, convert
its tiles to `.hgt` (3601 × 3601, for example with GDAL's SRTMHGT format), name them like
`N47E009.hgt`, and put them on a web server of your own. MapAround doesn't host it.

Keep in mind:

- It's a **surface** model: it measures the top of forests and roofs, not the ground under them.
  Contours in woodland come out higher and bumpier.
- Its licence asks for a credit line that begins *"Produced using Copernicus WorldDEM-30 © DLR
  e.V."*; see the dataset's page for the full text.

## Privacy

The source sees your IP address and which tiles you download, which tells it roughly where you're
looking. Everything else happens on your phone.

## Limits

- Heights are smoothed over 30 or 90 m, so summits come out lower than their real height and
  narrow gorges shallower.
- The map's own downloads cover the areas of your offline maps; elsewhere, use **⋮ → Contours &
  hill shading** or the 3D view.
- Contour lines are drawn by the Topo style and imported styles.
