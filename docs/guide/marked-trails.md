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

On a path or a road, a marked trail is a line of waymark dots in its colour. On a dirt road (drawn
as a double line), the road's edges take the colour instead: one colour on both edges, two colours
one on each, a third and a fourth on a second line outside the edges. The edges keep their dashes,
so you still see how passable the road is: solid for any car, dashed for high vehicles, dash-dot
for 4×4 only.

<p class="shots"><img src="{{ '/guide/img/trails-dirt-road.webp' | relative_url }}" alt="A 4×4 road marked red: its edges in the trail's colour, dash-dot; paths beside it" width="720" height="1195" loading="lazy"><img src="{{ '/guide/img/trails-legend.webp' | relative_url }}" alt="The legend's marked trails, down to the marked dirt road" width="720" height="1195" loading="lazy"></p>

## Where they come from

From [OpenStreetMap](https://www.openstreetmap.org), where volunteers map each marked route with
its colour and symbol. MapAround uses the hiking and walking routes, not cycling or riding routes.

There are two ways to have them on the map:

1. **Built into MapAround's own maps.** The maps in **Download maps** already carry the marked
   trails, and the cliffs and ridges too; there's nothing more to download. On these maps the
   Offline maps screen shows **trails ✓**.
2. **Online**, as an overlay from [Waymarked Trails](https://waymarkedtrails.org), on every other
   map: online maps, the basic maps from mapsforge.org, a `.map` file you imported. It shows the
   same OpenStreetMap routes. The Waymarked Trails website is also a good place to look at a route
   before a trip: where it goes, how long it is, how much it climbs.

In **Map layers → Overlays**, **Marked hiking trails** switches them on and off. They're drawn
offline in the Topo styles where your maps have them, and as the online overlay on other maps.

(Android used to download trails by area for other maps, from the Overpass API. MapAround's maps
have them built in now, so that download is gone, and its data is deleted from the phone.)

## Privacy

The online overlay asks waymarkedtrails.org for the map tiles you're looking at, which tells it
roughly where you are looking. Trails built into a map send nothing.

## Limits

- Only routes that someone has mapped in OpenStreetMap show up. In some places the marks on the
  ground are newer, older or missing; trust the marks you see.
- A path without a marked route is still on the map, as a path; it just isn't coloured as a trail.
- Offline, only MapAround's maps have them; on other maps they need a connection (the overlay).

## Licence

Trail data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) (ODbL). The
online overlay is made by [Waymarked Trails](https://waymarkedtrails.org) from the same data.
