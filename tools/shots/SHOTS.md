# MapAround's pictures

Every screenshot the website, the guides and the Google Play listing use: where each one goes,
what it shows and how it's set up. [`shots.py`](shots.py) takes them again (see the
[README](README.md)); this list is what it follows. Retake them after changes to what they show:
the map style, the bars and buttons, the screens below.

Names are the shots' ids: `python tools/shots/shots.py --only route,navigate`.

## Where they go

| Where | Files | Size | How it looks |
|---|---|---|---|
| Google Play: phone screenshots | `docs/store/screenshots/NN-<id>.png` | 1080×1920 PNG, no alpha | Framed: a caption on the brand green (#1B6B4A) above the screen, its corners rounded; the status and gesture bars cut off |
| Website: the main page | `docs/assets/<id>.webp` | 720×1280 WebP | The whole screen with its status bar (in demo mode: 14:30, full battery), in the page's own phone frame |
| Website: the party invite page (group tracking) | `docs/assets/<id>.webp` | 720×1280 WebP | As on the main page |
| Website: the guides | `docs/guide/img/<id>.webp`, Hebrew ones in `docs/he/guide/img/` | 720 px wide WebP | The screen, or the part that matters, without the status bar; the Hebrew guides' with the app in Hebrew |
| Google Play: feature graphic and icon | `docs/store/feature-graphic.png`, `icon-512.png` | 1024×500, 512×512 | Not screenshots: made by `store_assets.py` from a Topo render and the icon |

Google Play takes 2 to 8 phone screenshots, 9:16 or no longer than 2:1, at least 1080 px wide for
the promoted places. A phone taller than that (most are now) is cut to 9:16 by the script.

## Google Play and the main page

The same eight, in this order on Google Play; the main page shows them in pairs.

| # | id | Caption (Google Play) | On screen | Main page |
|---|---|---|---|---|
| 1 | `map` | Offline topo maps with marked trails and contours | The Topo map of the Tristaina lakes, Andorra (42.640, 1.495), zoom 15: contours, hill shading, marked trails in their colours, peaks and huts; you at Estany Primer | Hero |
| 2 | `route` | Plan hikes with climb, time and elevation profile | The route planner: a hike from Estany Primer up to the Tristaina ridge (about 3.7 km, 575 m up), its time and the elevation profile open | Plan and navigate (left) |
| 3 | `party-map` | See your group live on the map, even off-grid | A party on the map: three hikers (Noa, Lena, Marc), a 4×4 (Tom), the meeting point "Lunch at the dam" and the planned route | Travel as a group (left) |
| 4 | `navigate` | Turn-by-turn navigation with voice prompts | Navigating that route: the next turn and its distance, the distance and time to go | Plan and navigate (right) |
| 5 | `3d-topo` | Explore the terrain in 3D | The 3D view over the Tristaina cirque, tilted, the Topo map on the relief | Maps your way (left) |
| 6 | `satellite` | Satellite imagery under your trails | The same lakes over satellite imagery, the trails, contours and names on top | Maps your way (right) |
| 7 | `gps` | GPS, compass, barometer and inclinometer, from your phone's sensors | Sensors → Satellites: the sky view, signal strength, fix and accuracy. On a phone the position rows are blurred | Tools for the field (was the inclinometer) |
| 8 | `party-list` | End-to-end encrypted group sharing with SOS | The Party screen: SOS, the connections (online, LoRa) and the members with distance and battery | Travel as a group (right) |

## The party invite page (group tracking)

`docs/party.html` is what an invite link opens. Below the invite card, what joining gets you:

| id | On screen |
|---|---|
| `party-map` | As above (the same file) |
| `party-join` | The app opened from an invite link: "Join "Pyrenees trip"?" with your name to show, before joining |

## The guides

One or two per guide, beside the step they show. The Hebrew guides get the same shots with the app
in Hebrew (`--lang he`).

| Guide | id | On screen |
|---|---|---|
| Offline maps | `offline-list` | Offline maps: the map on the device, its size and what it has (terrain ✓, routing ✓, trails ✓), and the raster maps below |
| | `offline-menu` | A map's **⋮** menu: Contours & hill shading, Offline routing, Marked trails (each ✓ when there), Delete map |
| Marked trails | `trails-dirt-road` | Andorra, a 4×4 road marked red (GRP La volta a Andorra, 42.445, 1.556), zoom 15: the road's edges in the trail's colour, dash-dot; an unmarked 4×4 road and paths beside it |
| | `trails-legend` | The legend's Marked trails section, down to the marked dirt road |
| Elevation | `elevation-map` | Contours and hill shading close up over Estany de Més Amunt, with elevation labels |
| | `elevation-source` | Offline maps → Terrain → Source → Change: the elevation source's address |
| Offline routing | `routing-offline` | The route planner with the hike above, "Routed on this phone (BRouter)" |
| | `routing-data` | Offline maps → Offline routing: the routing block downloaded |
| Raster maps | `raster-add` | Offline maps → Raster maps: what can be added, and the button |
| | `raster-background` | Map layers → Background of the offline map: map colours, satellite imagery, a raster map |
| Party server | `party-server` | Party → Server → Change: the server's address and back to the public ones |

## Setting them up

- **The place.** Andorra, everywhere: the map from mapsforge.org with its contours & hill shading
  (high detail), offline routing and the marked trails downloaded for it (the downloaded trails
  know the dirt roads they run on). The script moves the map with a `geo:` link and gives the
  device a position (on a phone a test location provider, removed after).
- **The party.** Four members played by the script (`tools/party-sim`, the app's own party code on
  the computer), joined by the phone through an invite link; each has a name, an icon, a colour, a
  position and a battery level; one sets the meeting point.
- **The status bar.** Demo mode: 14:30, full battery and signal, no notifications.
- **The language.** English, or Hebrew for the Hebrew guides.
- **Never** your real position: on a phone the script fakes it (and blurs the GPS screen's
  coordinates, which come from the real receiver).
