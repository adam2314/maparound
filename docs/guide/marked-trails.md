---
title: Marked trails · MapAround guide
lang: en
---

[עברית]({{ '/he/guide/marked-trails' | relative_url }}) · [All guides]({{ '/guide/' | relative_url }})

# Marked trails

Marked trails are the waymarked hiking routes you follow on the ground by their paint marks: the
red-white-red of the Alps, the colour-marked trails of Israel, long-distance paths and local
loops. MapAround's **Topo** style draws them in their own colours, with their names, side by side
where several routes share a path.

## Where they come from

From [OpenStreetMap](https://www.openstreetmap.org), where volunteers map each marked route with
its colour and symbol. MapAround uses the hiking and walking routes, not cycling or riding routes.

There are three ways to have them on the map:

1. **Built into MapAround's own maps.** The maps in **Download maps** already carry the marked
   trails, and the cliffs and ridges too. On these maps the **⋮** menu says **Marked trails: in
   this map**, and there's nothing more to download.
2. **Downloaded for another map**, for the basic maps from mapsforge.org or a `.map` file you
   imported (such as OpenAndroMaps). See below.
3. **Online**, as an overlay from [Waymarked Trails](https://waymarkedtrails.org). It shows the
   same OpenStreetMap routes over any map, online maps included. The Waymarked Trails website is
   also a good place to look at a route before a trip: where it goes, how long it is, how much it
   climbs.

In **Map layers → Overlays**, **Marked hiking trails** switches them on and off. They're drawn
offline in the Topo style where your maps have them, and as the online overlay on other maps.

## Downloading trail data for a map

For a map that doesn't have them (Android):

1. On the **Offline maps** screen, tap **⋮** beside the map → **Marked trails**.
2. The dialog shows how many areas of 1° × 1° the map needs. Tap **Download**.

The download also brings the cliffs, arêtes and ridges, which the mapsforge.org maps leave out.
It comes from the [Overpass API](https://overpass-api.de), a public service that answers queries
on OpenStreetMap's data, one area at a time. Busy hiking regions like the Alps can take several
minutes, and up to about 50 MB per area while downloading. The progress shows in the **Marked
trails** section, and the map shows **trails ✓** when it's done. With **Download over Wi‑Fi only**
on, it waits for Wi‑Fi.

The data doesn't update by itself. To get routes added since, delete it and download it again.

## Space

On the phone the trails are kept about 10 times smaller than the download: roughly 0.5–8 MB per
area. The **Marked trails** section shows the total, with **Delete all trail data**. After that,
marked trails show only online, as an overlay (on maps without them built in).

## Privacy

The download sends the area you ask for to the Overpass servers. The online overlay asks
waymarkedtrails.org for the map tiles you're looking at, which tells it roughly where you are
looking. Trails built into a map, or downloaded, send nothing.

## Limits

- Only routes that someone has mapped in OpenStreetMap show up. In some places the marks on the
  ground are newer, older or missing; trust the marks you see.
- A path without a marked route is still on the map, as a path; it just isn't coloured as a trail.
- The trail download is on Android for now. On iPhone and in the web app, use MapAround's own
  maps (they have the trails built in) or the online overlay.
- One download covers up to 120 areas; for a bigger map, the app says the area is too large.

## Licence

Trail data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) (ODbL). The
online overlay is made by [Waymarked Trails](https://waymarkedtrails.org) from the same data.
