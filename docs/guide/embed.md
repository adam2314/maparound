---
title: The map in your website · MapAround guide
lang: en
---

[עברית]({{ '/he/guide/embed' | relative_url }}) · [All guides]({{ '/guide/' | relative_url }})

# The map in your website

MapAround's web app can go on any web page as just a map: a hike's track in a blog post, a
meeting point on a club's page, the area of a trip. It's the same map as in the app, with its map
layers and the 3D view, and there's nothing to install or sign up for. Visitors move and zoom it,
and **Open in MapAround** (the top button) opens it in the full web app, where they can save the
track or plan their own route.

<iframe src="https://maparound.app/app/?embed&amp;layer=opentopomap#map=13/31.47/35.38"
        title="MapAround map of the Ein Gedi area" width="100%" height="420" style="border:0" loading="lazy"></iframe>

## The code

Put this where the map should be, with the place you want in the address:

```html
<iframe src="https://maparound.app/app/?embed#map=13/31.47/35.38"
        title="Map" width="100%" height="450" style="border:0" loading="lazy"></iframe>
```

`?embed` is what makes it the map alone. `#map=13/31.47/35.38` is where it opens: zoom, latitude,
longitude, as on openstreetmap.org (the web app's own address shows it as you move the map, so
you can copy it from there).

## A track or route

**From MapAround.** In **Library**, open a route's or track's ⋮ menu and choose **Embed in a
website**. The code is copied: paste it into your page. The line itself is in the code's
address, as in a shared link, so nothing is uploaded anywhere. Long tracks are thinned to 500
points with their shape kept, and the heights go along. The map fits the track, with its name,
length and climb at the top.

**A GPX file on a website.** Put its address after `gpx=`:

```html
<iframe src="https://maparound.app/app/?embed&gpx=https://example.org/hikes/wadi-qelt.gpx"
        title="Wadi Qelt" width="100%" height="450" style="border:0" loading="lazy"></iframe>
```

The visitor's browser reads the file, so the website holding it has to let other websites read
it (CORS, the `Access-Control-Allow-Origin` header). GitHub Pages and GitHub's raw files
(raw.githubusercontent.com) do; many websites don't, and then the map says it couldn't get the
file. The track takes the name inside the file, or else the file's name; its waypoints show as
places on the map. If the file's address has its own `?` or `&`, encode it first
(`encodeURIComponent` in JavaScript).

## A point

openstreetmap.org's pin, `mlat=` and `mlon=`:

```html
<iframe src="https://maparound.app/app/?embed&mlat=31.4655&mlon=35.3870#map=15/31.4655/35.3870"
        title="Where we meet" width="100%" height="450" style="border:0" loading="lazy"></iframe>
```

## Everything in the address

| In the address | What it does |
|---|---|
| `embed` | The map alone. Without it the page is the whole web app. |
| `#map=zoom/lat/lon` | Where the map opens. With a track, the map fits the track instead. |
| `mlat=…&mlon=…` | A pin. |
| `#track=…&n=…` | A route or track, as **Embed in a website** makes it. |
| `gpx=<address>` | A GPX file from a website that allows it. |
| `layer=<map>` | The map to show: `osmfr` (OpenStreetMap, the default), `opentopomap`, `cyclosm`, `hot`, `esri_topo`, `esri_street`, `esri_imagery` (satellite), `osmde`, and for their own country `swisstopo`, `basemap_at`, `kartverket`, `ign_plan`, `ign_ortho`, `usgs_topo`. |
| `trails=0` | Without the waymarked hiking trails drawn over the map (waymarkedtrails.org). |
| `lang=en` or `lang=he` | The buttons' language; otherwise the visitor's browser's. |

The options can go after `?` or after `#`, joined with `&`, for example
`…/app/?embed&layer=opentopomap&trails=0#map=13/31.47/35.38`. Visitors can still choose another map
in **Map layers**; that choice lasts until the page is left. **Open in MapAround** keeps the
view, the pin and the track, but not `layer`, `trails` or `lang`: the full app opens with the
visitor's own settings.

## What the visitor's browser does

The embedded map doesn't read or save anything of the visitor's: not their MapAround settings,
tracks or places, even if they use the web app on the same browser, and it doesn't ask for their
location. The browser downloads the app from GitHub (several megabytes the first time, then from
its cache), the map's tiles from that map's servers, and the GPX file from its website. A track in
the address after `#` is never sent to any server.

## Good to know

- **Map servers' terms.** The maps come from their own servers (OpenStreetMap France, OpenTopoMap,
  Esri, the national mapping agencies…), which are free for light use. A busy website should
  check their terms.
- **Leave the credits visible**: the map's sources are named at its bottom, and the maps'
  licences ask for that. Don't crop the frame.
- **On a phone** the map takes the touches inside it, so the page scrolls only outside it. A map
  shorter than the screen (the code's 450 pixels, or less) leaves room for that.
- **A sandboxed frame** (the `sandbox` attribute) needs at least `allow-scripts allow-same-origin
  allow-popups`, the last one for **Open in MapAround**. Without `sandbox`, nothing is needed.
- The browser needs to be recent enough for the web app: Chrome, Edge, Firefox or Safari from 2024
  or newer. An older one shows a message in the frame instead of the map.
