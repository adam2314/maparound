# MapAround privacy policy

*Effective 20 November 2026*

MapAround is an offline map app for Android and iPhone. This policy explains what information the
app uses, where it goes, and what you control.

**In short:** MapAround has no accounts, no ads, no analytics and no tracking. The developer
doesn't run a server and the app sends the developer none of your data. Your maps, tracks, places
and settings stay on your phone. The app contacts other services only for the online features you
use, as listed below. If you join the Android beta, the developer sees the email address of the
Google account you join with (see [Testing the Android beta](#testing-the-android-beta)).

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
- **The web app** (maparound.app/app) keeps your routes, places, settings and the
  maps you add in your browser's storage for that site, on your device. Clearing the site's data
  in the browser deletes them. GitHub, which hosts the site, receives your IP address when the page
  loads (see GitHub's privacy statement); after that, the app only asks GitHub for MapAround's list
  of maps (on the Offline maps screen), and your browser downloads from GitHub the maps you
  choose.
- **The map embedded in other websites** (the web app with `?embed`, in a frame on someone's
  page) keeps nothing: it doesn't read or save your settings, routes or places, and doesn't ask for
  your location. It loads the app from GitHub, the map tiles of the map it shows, and a GPX file
  the page names from that file's website.

## What the app sends, and to whom

The app makes these requests only when you use the feature. Each service receives your device's IP
address with the request, as with any internet connection, and handles it under its own privacy
policy.

**Settings → Sources** lists these services in one place. You can point most of them at a server
of your own; that server then gets the requests instead, and **Check** sends it one test request.
You can turn some off altogether: online search, online routing, Wikipedia and photos, the
vector map online, and the web app's GPS traces, rain radar, weather, weather maps, street photos, buses and cameras. Where a country has its own national map (Switzerland, Austria, Norway, France
and the US), the automatic online map uses it there, so that country's mapping agency gets the
tile requests for that area.

| Feature | Sent to | What is sent |
|---|---|---|
| Online maps (when you choose an online map, satellite background or 3D view) | The map tile service you choose: OpenStreetMap France or Germany, OpenTopoMap, CyclOSM, Humanitarian, Esri, swisstopo, basemap.at, Kartverket, IGN, USGS, waymarkedtrails.org (trail overlay), or a tile service you added yourself. The 3D view also loads elevation tiles from Amazon Web Services (the web app always; the apps for ground whose elevation hasn't been downloaded, such as distant mountains), and Esri imagery for satellite | Which map tiles to show, which reveals the area you're looking at |
| The vector map online, where no map is downloaded (Android; it can be switched off in Map layers) | GitHub's download servers (MapAround's maps) and download.mapsforge.org (the basic maps); the lists of maps from raw.githubusercontent.com and download.mapsforge.org | Which parts of which map to read, which reveals roughly the area you're looking at |
| Offline map, terrain and routing downloads | GitHub (MapAround's maps and their list, github.com, raw.githubusercontent.com and GitHub's download servers), download.mapsforge.org (the older maps, also offered), Amazon Web Services (terrain tiles, also fetched by themselves when line of sight, the viewshed or the 3D view need them, or the map's contour lines and hill shading where your offline maps are; or the elevation source you set instead), brouter.de | Which areas to download |
| Marked trails and cliffs download | Overpass API servers (overpass-api.de and others) | The area to download |
| Online search | photon.komoot.io, or nominatim.openstreetmap.org as a fallback | Your search text and the centre of the map. Offline search sends nothing. |
| What's here? (when you open it from a pin) | wikipedia.org (the Wikipedia in your language, and the English one) | The pin's coordinates, to find articles about places nearby. The places the map shows around it are read on your device. Articles you open load in your browser. |
| A place you tapped that has its own Wikipedia article (its picture, the Wikipedia button, What's here? for it) | wikidata.org, wikipedia.org, upload.wikimedia.org | Which place it is (its Wikidata id, its article's name), to find its article in your language and its picture; the picture itself |
| Photos around here (in What's here?) | commons.wikimedia.org, upload.wikimedia.org | The pin's coordinates, to find photos taken nearby; which photos to show; when you open one, which one, for its author and licence |
| Online routing (when routing data for the area isn't downloaded) | brouter.de | The route's points. While navigating, if a new route is calculated online, your current position too. |
| The web app's data view (when you turn it on and pick a layer) | ourworldindata.org (the countries' figures), data.gov.il (Israel's localities), gps.tile.openstreetmap.org (GPS traces), api.rainviewer.com and tilecache.rainviewer.com (rain radar), api.open-meteo.com (weather), raw.githubusercontent.com (weather maps: MapAround's pictures of NOAA's forecast, or the server you set), api.panoramax.xyz and the Panoramax server keeping a photo (street photos), open-bus-stride-api.hasadna.org.il (buses), overpass-api.de or overpass.private.coffee (cameras, or the Overpass server you set), each only when you show that layer | Which figures to download; for the GPS traces, the radar and the street photos, which map tiles to show; for the weather, points over the area you're looking at (and a point you tap, for its forecast); for the weather maps, nothing about where you are (everyone gets the same pictures of the whole world, though GitHub sees the request); for the buses and the cameras, the area you're looking at. All of these reveal roughly where you're looking. The time zones are built in and send nothing. |
| Opening a shortened map link someone shared with you | The link's own service (for example Google) | The link, to find the place it points to |
| Party location sharing | The other members of your party, through public relay servers (broker.emqx.io, test.mosquitto.org, broker.hivemq.com) or the party's own server if it has one (its invite says which) and, if you pair one, Meshtastic LoRa radios | Your name, icon and colour, your position, speed, heading and battery level, SOS alerts, the meeting point and messages. All of it is end-to-end encrypted (AES-256-GCM) with the party's key, so the relays and radios can't read it. They do see that encrypted messages pass, when, and from which IP address. |
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
  sending and receiving. In several parties, each has its own.
- **Several parties at once:** you have a different member id in each, made at random, so neither
  the servers nor the members can link you in one party to you in another (only your name and
  look, which are the same in all). An SOS goes to every party that's on.
- Relay servers keep the latest encrypted position, name and SOS of each member, and the party's
  marks and meeting point, so people who join later see the group straight away. Leaving the party
  removes yours from the relays.
- **On a party's own server,** the app also sends, for that party, your member id and a secret it
  made when you joined, so that only your phone can send as you. A
  [MapAround party server]({{ '/guide/party-server' | relative_url }}) also keeps the party's
  messages for a while (30 days by default), still encrypted, for members who connect later. Its
  admin can see members' ids (also of those on a LoRa radio, whose messages a member's phone
  passes on) and when they were last heard, and can remove members.
- **Members' history**: the positions of the others that your device receives are kept on it
  while the party runs, so you can replay where each one went and draw their trails. They stay on
  your device, aren't sent anywhere, and are deleted when you leave that party.

## Testing the Android beta

- **The beta is the same app**, under this same policy: it collects nothing more.
- **Joining the testers' group** ([groups.google.com/g/maparound](https://groups.google.com/g/maparound))
  is done with your Google account. The developer, as the group's owner, sees the email address
  you join with; other testers don't. It's used only to let you into the beta and, rarely, to tell
  testers about a new version. Google Groups is Google's service, under Google's privacy policy.
- **Becoming a tester on Google Play** is between you and Google. Google Play shows the developer
  how many people test, and, if your phone shares usage and diagnostics with Google, crash and
  performance reports (the phone model, the Android version, where the app failed), not who you
  are. Feedback you send privately through Google Play's page for the app reaches the developer.
- **Reports on GitHub are public.** Don't include party invite links (anyone with one can read the
  party), track links (they carry the route), screenshots that show where you live, or your email
  address. For anything personal, use Google Play's private feedback.
- **Leaving**: leave the group and the test (the tester page on Google Play). The developer no
  longer sees your address once you've left the group.

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
- In **Settings → Sources**, turn off the online services you don't want, or use servers of your
  own.
- Delete tracks, places, maps and downloads in the app at any time, or uninstall the app to delete
  everything.
- Leave a party to remove your last position from the relay servers.

## Changes

If this policy changes, the new version will be posted at this address with a new effective date.

## Contact

Questions about this policy: open an issue at
[github.com/adam2314/maparound/issues](https://github.com/adam2314/maparound/issues). Issues are
public; testers can also write privately through Google Play's feedback for the app.
