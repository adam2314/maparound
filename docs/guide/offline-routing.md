---
title: Offline routing · MapAround guide
lang: en
---

[עברית]({{ '/he/guide/offline-routing' | relative_url }}) · [All guides]({{ '/guide/' | relative_url }})

# Offline routing

**Plan a route** on the map calculates a route along paths and roads between the points you tap.
With routing data on your phone, it does that on the phone itself, with no connection: planning a
detour on the trail, or a new route when navigation sees you've left the planned one. Offline
routing is on Android for now; iPhone and the web app will get it in a later version.

## How it works

MapAround has [BRouter](https://brouter.de) built in, an open-source route planner for
OpenStreetMap that works offline and takes climbs into account. It reads BRouter's routing data: OpenStreetMap's paths and roads,
with their surfaces, grades and access rules, prepared in blocks of 5° × 5° (files named like
`E5_N45.rd5`, for 5–10° E and 45–50° N). One block can cover several small countries, or part of
a big one.

The planner's profiles:

- **Hike** follows footpaths and mountain trails;
- **Bike** prefers cycleways and quiet roads;
- **Car** follows roads;
- **Line** draws straight lines to measure, and needs no routing data.

Under the route, the planner says how it was made: **Routed on this phone (BRouter)**, or
**Routed online by brouter.de**.

## Getting the data

1. On the **Offline maps** screen, tap **⋮** beside a map → **Offline routing**.
2. The dialog lists the blocks that map needs, with their sizes and the total.
3. Tap **Download**.

The map then shows **routing ✓**, and the blocks appear in the **Offline routing** section, each
with its size and a bin to delete it. With **Download over Wi‑Fi only** on, they wait for Wi‑Fi.

The blocks don't update by themselves. For newer paths, delete a block and download it again.

## Online and offline

- When every point of a route is inside downloaded blocks, the route is calculated on the phone.
- Otherwise it's calculated online by [brouter.de](https://brouter.de)'s server, the same engine
  with the server's own copy of the data. If an offline route runs off the downloaded area, the planner asks the server
  when there's a connection.
- With no data and no connection, there's no route; **Line** still works.

## Space

A block takes from about 2 MB (mostly sea or desert) to about 300 MB (dense parts of Europe). The
dialog shows the exact sizes before you download. Blocks that are only sea don't exist and aren't
listed.

## Privacy

An offline route never leaves your phone. An online route sends its points to brouter.de, and
while navigating, a new route calculated online sends your position too. Download the routing
data to keep it all on the phone.

## Limits

- A route is as good as the map data. OpenStreetMap may miss a path, or show one that's
  overgrown or closed. Look at the route on the map before you rely on it, especially **Hike** in
  the mountains, which can choose demanding trails.
- Access rules (private roads, seasonal closures) follow what's mapped, which isn't always what's
  signposted.
- Android only for now.

## Licence

Routing data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) (ODbL),
prepared by the BRouter project. BRouter is open source under the MIT licence.
