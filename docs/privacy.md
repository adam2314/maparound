# MapAround privacy policy

*Effective 12 November 2026*

MapAround is an offline map app for Android and iPhone. This policy explains what information the
app uses, where it goes, and what you control.

**In short:** MapAround has no accounts, no ads, no analytics and no tracking. The developer
doesn't run a server and doesn't receive any of your data. Your maps, tracks, places and settings
stay on your phone. The app contacts other services only for the online features you use, as
listed below.

## What stays on your phone

- **Your location** is used on the phone for the map, navigation, track recording, the compass
  (true north), the map tools and the Speed and Satellites screens. The exceptions are online
  routing and party sharing, below.
- **Tracks, routes, places, downloaded maps, terrain and settings** are stored in the app's own
  storage. Uninstalling the app deletes them.
- **Files you open** (GPX files, map files, raster maps, map styles) are read with the access you
  give when you pick them, and aren't sent anywhere.
- **The camera** is only used to scan a party invite QR code. No pictures are kept or sent.
- **Voice prompts** come from your phone's own text-to-speech engine.
- **The web app** (adam2314.github.io/maparound/app) keeps your routes, places, settings and the
  maps you add in your browser's storage for that site, on your device. Clearing the site's data
  in the browser deletes them. GitHub, which hosts the site, receives your IP address when the page
  loads (see GitHub's privacy statement); after that, the app only asks GitHub for MapAround's list
  of maps (on the Offline maps screen), and your browser downloads from GitHub the maps you
  choose.

## What the app sends, and to whom

The app makes these requests only when you use the feature. Each service receives your device's IP
address with the request, as with any internet connection, and handles it under its own privacy
policy.

| Feature | Sent to | What is sent |
|---|---|---|
| Online maps (when you choose an online map, satellite background or 3D view) | The map tile service you choose: OpenStreetMap France or Germany, OpenTopoMap, CyclOSM, Humanitarian, Esri, swisstopo, basemap.at, Kartverket, IGN, USGS, waymarkedtrails.org (trail overlay), or a tile service you added yourself. The web app's 3D view also loads elevation tiles from Amazon Web Services, and Esri imagery for satellite | Which map tiles to show, which reveals the area you're looking at |
| Offline map, terrain and routing downloads | GitHub (MapAround's maps and their list, github.com, raw.githubusercontent.com and GitHub's download servers), download.mapsforge.org (the older maps, also offered), Amazon Web Services (terrain tiles, also fetched by themselves when line of sight, the viewshed or the 3D view need them), brouter.de | Which areas to download |
| Marked trails and cliffs download | Overpass API servers (overpass-api.de and others) | The area to download |
| Online search | photon.komoot.io, or nominatim.openstreetmap.org as a fallback | Your search text and the centre of the map. Offline search sends nothing. |
| What's here? (when you open it from a pin) | wikipedia.org (the Wikipedia in your language, and the English one) | The pin's coordinates, to find articles about places nearby. The places the map shows around it are read on your device. Articles you open load in your browser. |
| A place you tapped that has its own Wikipedia article (its picture, the Wikipedia button, What's here? for it) | wikidata.org, wikipedia.org, upload.wikimedia.org | Which place it is (its Wikidata id, its article's name), to find its article in your language and its picture; the picture itself |
| Photos around here (in What's here?) | commons.wikimedia.org, upload.wikimedia.org | The pin's coordinates, to find photos taken nearby; which photos to show; when you open one, which one, for its author and licence |
| Online routing (when routing data for the area isn't downloaded) | brouter.de | The route's points. While navigating, if a new route is calculated online, your current position too. |
| Opening a shortened map link someone shared with you | The link's own service (for example Google) | The link, to find the place it points to |
| Party location sharing | The other members of your party, through public relay servers (broker.emqx.io, test.mosquitto.org, broker.hivemq.com) and, if you pair one, Meshtastic LoRa radios | Your name, icon and colour, your position, speed, heading and battery level, SOS alerts, the meeting point and messages. All of it is end-to-end encrypted (AES-256-GCM) with the party's key, so the relays and radios can't read it. They do see that encrypted messages pass, when, and from which IP address. |
| iPhone only, if the app's built-in label fonts are missing | demotiles.maplibre.org | Which font characters to load |

### Party sharing

- Only people who have the party's invite link or QR code can read the party's positions. Share
  it only with your group. Someone with the link can also watch the party in the web app; a
  watcher sends nothing, so it doesn't appear in the members list.
- An invite link opens a page on this website (hosted by GitHub Pages), which opens MapAround,
  or the web app to watch. The party's code is in the part of the link after `#`, which browsers
  keep on the device and never send to the website, so neither the developer nor GitHub can see
  it. The web app takes it out of its address once it has read it, and keeps it in the browser's
  storage for that site while you watch.
- **Watching in the web app** connects to the same relay servers, which see your IP address and
  which party topic you listen to, as for members; it sends nothing to the party.
- **Share my location** off hides your position from the others, and **Party on** off stops
  sending and receiving.
- Relay servers keep the latest encrypted position, name, SOS and meeting point of each member, so
  people who join later see the group straight away. Leaving the party removes yours from the
  relays.

## Permissions

- **Location (precise and approximate)** is used for the features above, also while recording a
  track, navigating or sharing in a party with the screen off. A notification shows the whole time.
  The app doesn't ask for background location access.
- **Notifications** are used for recording, navigation, party alerts and download progress.
- **Bluetooth (scan and connect)** is only used to find and talk to your Meshtastic radio.
- **Camera** is only used for scanning a party QR code.
- **Android Auto** lets the app show its map on a car screen. Nothing is sent to the car maker or
  to Google beyond what Android Auto itself handles.

## Children

MapAround isn't directed at children and doesn't knowingly collect information from anyone.

## Your choices

- Use offline maps, routing data and search to keep all map use on your phone.
- Delete tracks, places, maps and downloads in the app at any time, or uninstall the app to delete
  everything.
- Leave a party to remove your last position from the relay servers.

## Changes

If this policy changes, the new version will be posted at this address with a new effective date.

## Contact

Questions about this policy: open an issue at
[github.com/adam2314/maparound/issues](https://github.com/adam2314/maparound/issues).
