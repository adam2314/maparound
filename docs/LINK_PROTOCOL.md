# MapAround Link v1: the party protocol

**Status:** draft, 2026-11-20. Not implemented yet. It replaces the party format in
`shared/.../party/Protocol.kt`, which this document calls **legacy** (packets starting `0x4D 0xA1`).

## Goals

- **Base: live positions, as compact as possible.** Sender id, sequence number, position,
  accuracy, battery and heading, in about 32 bytes (legacy: 54).
- **Modes (Saver, Normal, Live)** decide how often positions go out, online and on the radio.
  By default the app switches between them by itself (Auto), and any member can switch the
  whole party to Live.
  A smart sender (dead reckoning) only sends when the others' prediction of where you are
  would be wrong, and each position carries the way taken since the previous one.
- **Several connections at once:** any number of MQTT servers and LoRa radios. **Each
  connection is a stream, and each stream is its own map layer** that can be turned on and off.
- **Several parties at once,** each on whichever of those connections it uses.
- **Marks:** points, lines, areas and circles that everyone in the party can add, edit and
  delete live, and that converge on every phone.
- **Relaying** between connections (radio and internet, two servers, two meshes), by members
  and by boxes that don't hold the key.
- **Each transport does what it can.** Online carries everything. LoRa carries less, and less
  often, but still the same packets.

## Contents

1. [Conventions](#1-conventions)
2. [Envelope](#2-envelope)
3. [Messages](#3-messages)
4. [Marks](#4-marks)
5. [Catching up on the radio](#5-catching-up-on-the-radio)
6. [Transports](#6-transports)
7. [Connections, layers and parties](#7-connections-layers-and-parties)
8. [Relaying](#8-relaying)
9. [Sizes](#9-sizes)
10. [Security and privacy](#10-security-and-privacy)
11. [The legacy format](#11-the-legacy-format)
12. [Not in v1](#12-not-in-v1)
13. [Open questions](#13-open-questions)

## 1. Conventions

- Integers are big-endian.
- **varint**: unsigned LEB128, as in Protocol Buffers. **zz-varint**: zigzag, then varint.
- **string**: a `u8` length, then UTF-8, cut on a character boundary to the field's limit.
- **Trailing bytes** a receiver doesn't understand are ignored. New fields are only ever
  appended, so older apps keep reading newer packets.
- **Grid coordinates.** Every position and every mark vertex sits on one grid of 24-bit
  integers:

  ```
  lat_g = round(lat / 90 × 2^23)    clamped to −2^23 … 2^23−1   (i24)
  lon_g = round(lon / 180 × 2^23)   wrapped into −2^23 … 2^23−1 (i24)
  ```

  One step is 1.07e-5° of latitude (1.19 m) and 2.15e-5° of longitude (2.39 m at the equator,
  2.0 m at 32°N, 1.7 m at 45°N). That is finer than a phone's GPS. A **vertex** is `lat_g`
  then `lon_g`, 6 bytes. A **delta** between two vertices is two zz-varints in grid steps, with
  Δlon wrapped into the same range so a line can cross the antimeridian.

## 2. Envelope

Every packet on every transport is the same bytes. Relays forward them unchanged, except for
the hop count.

| Bytes  | Field     | Meaning |
|--------|-----------|---------|
| 0      | marker    | `0xA2`: Link v1. Legacy packets start `0x4D`, so the two can't be mistaken. |
| 1      | type·hops | bits 0–3: message type ([§3](#3-messages)); bits 4–5: hops (app-level relays so far, 0–3); bits 6–7: 0 |
| 2–3    | tag       | the party's tag: the first 2 bytes of HMAC-SHA256(secret, `"MapAround link tag v1"`) |
| 4–7    | sender    | random u32 per member per party, never 0 (the same id the legacy format uses) |
| 8–11   | time      | when the packet was first sent: u32 Unix seconds |
| 12–13  | seq       | u16: +1 for every packet the sender sends in this party, kept across restarts, wraps around |
| 14…    | sealed    | AES-256-GCM of the body, with an 8-byte authentication tag at the end |

- **Key:** HMAC-SHA256(secret, `"MapAround link key v1"`). The `secret` is the invite's
  32 bytes, as today. The legacy format uses the secret itself, so the two formats never share
  a key.
- **Nonce:** bytes 4–13 followed by `00 00` (12 bytes).
- **AAD:** bytes 0–13, with the hop bits (byte 1, bits 4–5) set to 0, so a relay can raise the
  hop count without breaking authentication.
- **The plaintext is just the body.** The type is in the header, and the send time is the
  header's `time`.

The envelope costs 22 bytes per packet: 14 of header and 8 of authentication tag.
`(sender, time, seq)` is the packet's identity, used to drop duplicates.

**Receiving** a packet:

1. Check that byte 0 is `0xA2`, that the packet is at least 22 bytes, and that bits 6–7 of
   byte 1 are 0. Find the parties with this tag; with 2-byte tags, more than one is possible.
2. Drop it if `time` is more than 10 minutes in the future. Note that the sender's clock is
   wrong, so the screen can say so.
3. Try each matching party's key. If none opens it, drop it.
4. Note which connection it came over. Then drop it if it's my own packet (an echo), or if
   `(sender, time, seq)` has already been seen on any connection. Keep the last 20 000 seen,
   as `PartyEngine` does now. A duplicate still counts as heard on its connection, for that
   connection's layer and status ([§7](#7-connections-layers-and-parties)).
5. Handle it by type. Ignore types this app doesn't know, but relay them anyway
   ([§8](#8-relaying)).

A party's packets are accepted from any connection; the party sends only on the connections
it uses ([§7](#7-connections-layers-and-parties)).

## 3. Messages

| Type | Name   | Priority | Body |
|------|--------|----------|------|
| 1    | POS    | normal   | position |
| 2    | HELLO  | normal   | name, look, what this app can do, summary of the marks |
| 3    | SOS    | urgent   | raise or cancel an alert |
| 4    | TEXT   | normal   | a chat message |
| 5    | LEAVE  | normal   | empty |
| 6    | MARK   | normal   | a change to a mark ([§4](#4-marks)) |
| 7    | DIGEST | bulk     | the marks I have ([§5](#5-catching-up-on-the-radio)) |
| 8    | WANT   | bulk     | marks I'm missing ([§5](#5-catching-up-on-the-radio)) |
| 9    | ACK    | urgent   | packets I received |
| 10   | LIVE   | urgent   | the whole party goes Live until a given time |
| 0, 11–15 | –  |          | reserved |

The priority comes from the type, so a relay that can't read the body can still put SOS first.

### POS: position

```
flags   u8   bit 0 ACC, 1 BAT, 2 HDG, 3 SPD, 4 ALT, 6 MODE, 7 TRAIL   (which optional fields follow)
             bit 5 SOS (an alert is active)
vertex  6    lat_g, lon_g
ACC     u8   accuracy in metres, 0–254; 255 means 255 m or worse
BAT     u8   bits 0–6 battery %, bit 7 charging
HDG     u8   heading in degrees × 256/360 (steps of 1.4°)
SPD     u8   km/h, 0–254; 255 means faster
ALT     i16  metres
MODE    u8   bits 0–1 mode (0 Normal, 1 Saver, 2 Live), bit 2 AUTO, bits 3–7 0;
             only while it differs from the mode in my last HELLO on this connection
TRAIL        the way since my previous position on this connection (see Modes below):
             n varint, then n points going back in time, each:
               Δlat zz-varint, Δlon zz-varint   grid steps from the point after it
               Δt varint                        seconds before the point after it
```

The optional fields come in bit order, and only when their bit is set. A packet with
accuracy, battery and heading is **32 bytes**. Adding speed and altitude makes 35. The bare
minimum is 29. A trail adds 3–5 bytes per point; a typical one adds 13–24 bytes.

The base fields map like this: id → `sender`, seq → `seq`, lat/lon → `vertex`, plus `ACC`,
`BAT` and `HDG`. The header's `time` is when the position was taken. A position with accuracy
worse than 20 m is a sign of life (it still updates "last heard" and the battery) but isn't
drawn on the map, as now. Receivers treat a member as driving when SPD is 15 km/h or more;
there's no flag for it.

### Modes: when to send a position

**Auto picks the mode by itself** ([below](#auto-mode)), for each party and each connection.
A member can also pin one mode. The mode travels in HELLO and POS, so the others know how
often to expect them. The same rules run on every connection, online and radio, each with its
own numbers.

| Mode             | Connection | Error  | Min gap | Max silence | Trail tolerance |
|------------------|------------|--------|---------|-------------|-----------------|
| Saver            | online     | 50 m   | 30 s    | 5 min       | 25 m |
|                  | LoRa       | 150 m  | 3 min   | 30 min      | 25 m |
| Normal           | online     | 15 m   | 5 s     | 60 s        | 10 m |
|                  | LoRa       | 50 m   | 45 s    | 10 min      | 10 m |
| Live             | online     | 3 m    | 1 s     | 15 s        | 3 m |
|                  | LoRa       | 25 m   | 30 s    | 5 min       | 3 m |

Nearby connections use the online numbers.

**Dead reckoning.** The sender and every receiver predict the member's position the same way,
from the last POS: straight on along HDG at SPD, for at most 2 minutes after that POS's time.

```
d    = SPD / 3.6 × min(now − time, 120)            metres
lat' = lat + d × cos(HDG) / 111 320
lon' = lon + d × sin(HDG) / (111 320 × cos(lat))
```

- Nothing is predicted when the POS has no HDG or SPD, or SPD is under 2 km/h. A member who is
  moving therefore always sends both.
- **Receivers draw the marker at the predicted position,** so it moves smoothly between
  packets. After 2 minutes it stops there, drawn as an estimate.
- **The sender predicts from the values it actually sent,** rounded as they are in the packet,
  so its prediction matches the receivers'.
- **The sender sends a new POS on a connection when any of these happens:**
  1. Its real position is further from the prediction than the mode's **error**. The error
     used is never smaller than the GPS accuracy, so jitter doesn't trigger a send.
  2. It stopped moving, or started.
  3. The mode's **max silence** has passed (a heartbeat).
  4. An SOS was raised. Until it's cancelled, positions follow Live's numbers, and go every
     30 s on LoRa.
  5. A good fix came back after a time without one. It goes out at once: nothing was sent
     meanwhile, so the min gap has already passed.
  6. Auto switched to a livelier mode.
- **It never sends sooner than the mode's min gap** after its previous POS on that connection.
  On LoRa it also stays within the radio's budget ([§6](#6-transports)).
- **A heartbeat goes out even without a good fix.** It carries the poor fix, or, with no
  location at all, the last vertex again with ACC 255. Either way it's only a sign of life
  with the battery level, and doesn't move the marker.

A car at a steady speed on a straight road sends rarely, and every bend sends a packet. The
others' view stays within the mode's error, as long as packets arrive.

**Trail.**

- **Why:** rare packets would draw a member's way as straight lines between them, so each POS
  also carries the way taken since the previous POS on that connection.
- **How it's made:** the route is simplified (Douglas–Peucker) to the mode's trail tolerance.
  If it doesn't fit, it's simplified further until the whole packet fits: at most 80 bytes on
  LoRa and 512 online.
- **Where it goes:** receivers add the trail to the member's history (their trail layer). It
  doesn't move the marker.

**Stale members.** A member not heard on any connection for 3 times their mode's max silence
is shown as stale.

### Auto mode

**Auto is the default.** It picks a mode for each connection from what the phone already
knows, and it is entirely local: only the chosen mode goes on the wire. A member can pin
Saver, Normal or Live instead. A pinned mode stays until the member changes it, except that an
SOS still switches to Live.

**Live**, when the battery is above 30% or charging, and any of these holds:

- An SOS is active in the party, whoever raised it. The helpers and the person in trouble then
  see each other move live.
- I'm moving within 300 m of another member, or within 1 km of the meeting point: meeting up,
  or driving in a convoy.
- I'm moving while charging, e.g. on the car's charger.

**Saver**, when nothing calls for Live, and any of these holds:

- The battery is at 20% or below and not charging.
- I've been still for 10 minutes.
- Nobody else in the party has been heard on that connection for 30 minutes.

**Normal** otherwise.

**Party Live** ([LIVE](#live-the-whole-party-goes-live)) puts every connection in Live until it
ends. It overrides Auto and pinned modes alike. The only exception is a member whose battery is
at 20% or below and not charging: they stay in Normal, and their positions show it. A busy
mesh still steps it down on that radio (below).

**A busy mesh moves Auto one mode down on that radio.** Meshtastic reports the mesh's channel
utilisation; above 25% counts as busy, the level where Meshtastic itself starts holding back.

**Switching:**

- **To a livelier mode:** at once, and a POS goes out right away, within the new mode's min
  gap, so the others get the new pace.
- **To a calmer mode:** only once the reason has been gone for 5 minutes, so the mode doesn't
  flap.
- **Telling the others:** the mode goes in the next POS on each connection (the MODE field),
  and in every HELLO.

### HELLO: who I am

```
caps    u8   bit 0 MARKS (speaks MARK, DIGEST, WANT), 1 RADIO (has a LoRa radio now),
             2 GATEWAY (relaying now), 3 NODE (a node number follows),
             bits 4–5 mode: 0 Normal, 1 Saver, 2 Live, 3 reserved; bit 6 AUTO;
             bit 7 LIVE (the party is Live: the newest LIVE I know follows)
icon    u8   member icon (PartyIcon code)
colour  3    RGB
node    u32  my Meshtastic node number, if NODE
marks        if MARKS: count varint, then the hash u32 when count > 0 (§5)
live         if LIVE: until u32, time u32, by u32 (the LIVE's end, when it was sent, who sent it)
name    string, at most 24 bytes
```

When to send it is unchanged from the current `sendHello`: on start (retained online), then
every 10 minutes online and every 30 minutes on the radio, and as a reply to a newcomer at
most every 2 minutes.

### SOS, ACK

- **SOS:** `active u8` (1 raises the alert, 0 cancels it), then `text` (string, at most
  80 bytes). Positions sent during the alert have the SOS flag set and go every 30 s on the
  radio.
- **ACK:** one or more references to packets I received, each `sender u32, time u32, seq u16`
  (10 bytes).
  - Every member who receives an SOS acknowledges it once. On the radio, the ACK waits a
    random 0–10 s first.
  - The person in trouble sees who got the alert.
  - On the radio, the SOS is repeated every 60 s until an ACK arrives, then every 10 minutes
    while the alert stays on.
  - Online, members may also acknowledge TEXT, to show "seen by …". On the radio they don't.

### TEXT, LEAVE

- **TEXT:** `text`, a string of at most 170 bytes, so that it fits a LoRa packet.
- **LEAVE:** an empty body. Online, the sender also clears its retained topics
  ([§6](#6-transports)).

### LIVE: the whole party goes Live

```
until   u32  Unix seconds when party Live ends; 0 ends it now
```

- **Anyone in the party can switch everyone to Live** for 15 minutes, 1 hour or 4 hours, at
  most 12 hours. Anyone can stop it early. It ends by itself, so a forgotten switch doesn't
  drain everyone's battery. Receivers cut a longer `until` to 12 hours after the packet's
  time.
- **The newest LIVE wins:** the later header `time`, then the higher sender id.
- **Every phone shows who started it and until when,** e.g. "Dana switched the party to Live
  until 14:30", with a Stop button.
- **Each member's mode switches to Live** on every connection until then ([Auto
  mode](#auto-mode)). Their positions say so (MODE), which shows whoever started it who
  followed.
- **A member who joins later still finds out:**
  - online, from the retained `live` topic ([§6](#6-transports));
  - on the radio, from the HELLOs of members who know about it (the LIVE field).
- The packet is 26 bytes.

## 4. Marks

A **mark** is a shared object on the map: a point, a line, an area or a circle. Anyone in the
party can add, edit and delete any mark. Every phone ends up with the same marks, whatever
order the changes arrive in and however late.

### Identity

`id = creator u32, counter varint`

- `creator` is the creating member's sender id.
- `counter` starts at 1, counts per member per party, and is kept across restarts.
- No two members can make the same id, so marks are created without asking anyone.
- **The meeting point is the mark with id (0, 0):** a point, one per party. Setting it is an
  edit and clearing it is a delete. There is no separate MEET message.

### State: four registers

| Register | Holds |
|----------|-------|
| GEOM  | the shape and where it is |
| STYLE | icon, colour, line and fill |
| TEXT  | name and note |
| ALIVE | deleted or not (default: alive, version 0) |

- **Each register has its own version and editor** (the member who last wrote it), and is
  merged on its own: the higher version wins. With equal versions, the higher editor id wins,
  then the larger encoded bytes, so every phone picks the same one.
- **A mark's last editor** is the editor of its newest register, and its creator is in its id.
  Its card shows both, e.g. "added by Lior, moved by Dana 5 min ago".
- Because the registers merge separately, one person can move a point while another renames
  it, and both changes stay.
- A mark is shown when its GEOM is known and ALIVE says alive.
- **Versions come from one clock per party, kept across restarts.** Writing a register gives
  it the version `max(now in Unix seconds, highest version seen in this party + 1)`.
  - An edit therefore always beats everything its writer had seen.
  - Edits made at the same moment by people who hadn't seen each other's are ordered by time.
  - A version more than 10 minutes in the future doesn't move the clock.
- **Deleted marks (tombstones) are kept for the life of the party,** about 12 bytes each.
  Someone who was away can't bring a deleted mark back by sending their old copy.

### MARK body

```
id      creator u32, counter varint
groups  u8   bit 0 GEOM, 1 STYLE, 2 TEXT, 3 ALIVE   (which registers follow, in bit order)
             bits 4–7: future registers. They come after these, so older apps stop before them.
then, for each register present:
  meta  varint   zz(back) << 1 | BY
                 version = header time − back   (back is 0 for an edit made now)
  by    u32      the editor's sender id, only when BY is 1;
                 when BY is 0, the editor is this packet's sender
  the register's payload
```

A live edit costs one `meta` byte per register, since the sender is the editor. Marks sent in
full, to catch someone up or by a gateway, set BY for registers written by someone else:
4 more bytes each.

**GEOM**

```
kind    u8   bits 0–2: 0 point, 1 line, 2 area, 3 circle (4–7 reserved)
             bit 3 PART: this is one part of a shape split over several packets
             bits 4–7: 0
point:       vertex
circle:      centre vertex, radius varint (metres)
line, area:  part u8 (bits 0–3 index, bits 4–7 count − 1), only with PART
             n varint, first vertex, then n − 1 deltas
```

- An area's last vertex isn't repeated.
- A line has 2 or more vertices and an area 3 or more, up to 1000 vertices and 16 parts.
- **Each part repeats the previous part's last vertex as its first,** so the parts join.
- **A drawing is simplified once, by the phone that drew it** (Douglas–Peucker, with a
  tolerance of about one screen pixel at the zoom it was drawn at). Every member receives that
  same geometry.
- **No transport gets a simplified copy of its own.** Two shapes with one version would never
  converge.
- A typical hand-drawn line takes 2 to 3.6 bytes per vertex.

**STYLE**

```
flags   u8   bit 0 ICON: icon u8 follows
             bit 1 COLOUR: RGB, 3 bytes, follows (absent: the icon's colour)
             bits 2–3 width: normal, thin, thick, extra thick
             bits 4–5 line: solid, dashed, dotted, (reserved)
             bits 6–7 fill of an area or circle: none, light, medium, strong
```

Icon codes:

| Code   | Icon |
|--------|------|
| 0      | pin |
| 1–8    | favourite, camp, water, view, parking, fuel, food, danger (the saved places' categories, so a shared place keeps its category) |
| 9      | flag (the meeting point) |
| 10     | label: the name alone, no pin |
| 11–255 | reserved |

**TEXT:** `name` (string, at most 40 bytes), then `note` (string, at most 120 bytes).

**ALIVE:** `u8`: 1 alive, 0 deleted. Deleting sets 0. Undoing a delete sets 1 again, with a
newer version.

### Sending changes

- **A new mark** carries all its registers, with one version.
- **An edit** carries only the registers that changed: a move sends GEOM, a rename sends TEXT.
- **"Live" means each change is sent the moment it's made:** a point dropped or moved, a
  stroke finished, a name changed. Online that's within a second. On the radio it's the next
  free slot, ahead of positions ([§6](#6-transports)). Lines aren't sent while they're still
  being drawn.
- **A shape too big for one packet** is split into parts, each its own MARK packet:
  - This only happens on the radio, for long lines and areas. One LoRa packet carries about
    55 to 80 vertices.
  - Every part has the same version, and part 0 also carries the other registers.
  - A receiver applies the shape once it has every part of that version.
  - It keeps an incomplete shape for 10 minutes, and asks for the missing parts with WANT.
- **Sharing a saved place or a track** into the party makes a mark. A track is thinned to
  1000 vertices or fewer first.

## 5. Catching up on the radio

Online, the broker keeps every mark's latest state (retained, [§6](#6-transports)), so
someone who joins gets them all at once. On the radio nothing keeps state, so members compare
what they have and fill the gaps. When nothing has changed, this costs only the 5-byte summary
in each HELLO.

### Fingerprints and summary

- **A mark's fingerprint, `fp`,** is FNV-1a-32 over its id bytes, then over each register in
  order: the register's version (u32), its editor (u32) and its payload bytes. A register the
  phone doesn't have counts as version 0, editor 0, with no payload. `fp16` is the low 16 bits.
- **The summary** is `count` and FNV-1a-32 over all the marks' `fp`, sorted ascending, each
  written as a big-endian u32. Deleted marks count too. The summary rides in every HELLO.

### DIGEST

```
summary  count varint, hash u32
page     u8   bits 0–3 index, bits 4–7 pages − 1
fp16 …   my marks whose fp mod pages = index, at most 80 per page (a 188-byte packet)
```

### WANT

A list of entries, one after another:

- `0x01, fp16`: send me the marks with this fingerprint.
- `0x02, id, u16 bitmap`: send me these missing parts of this mark's GEOM.

### The exchange

1. **I hear a HELLO or DIGEST whose summary differs from mine.** I wait a random 5–30 s, then
   send my DIGEST pages. I skip that if, meanwhile, someone else sent a DIGEST with the same
   summary as mine.
2. **I receive a DIGEST page:**
   - For each `fp16` on the page that I don't have, I ask with WANT.
   - For each of my marks in that page's range that isn't listed, I send it in full.
3. **I hold a mark that someone asked for in a WANT, or that a DIGEST lacked.** I wait a random
   1–8 s, then send it in full. I skip it if I hear someone else send that version first.
4. **Edits and the sides converge after a round or two.** Two different versions of one mark
   each show up as "missing" on the other side, so both get sent and merged.

**DIGEST and WANT stay on the connection they were sent on.** Relays never forward them. A
gateway answers them itself, from what it knows from all its connections.

## 6. Transports

A **connection** is one MQTT server or one LoRa radio. A device can have several of each open
at once ([§7](#7-connections-layers-and-parties)). The packets are the same on all of them.

|                      | Online (MQTT)          | LoRa (Meshtastic)               | Nearby (later: BLE, Wi-Fi Direct) |
|----------------------|------------------------|---------------------------------|------------------|
| Largest packet       | 4096 bytes             | 200 bytes                       | 200 bytes (so it can be relayed to LoRa unchanged) |
| Positions            | by mode (Normal: 5–60 s) | by mode (Normal: 45 s to 10 min) | as online |
| Marks                | full state, retained   | changed registers; large shapes in parts | as LoRa |
| Someone joining gets | everything retained    | DIGEST and WANT                 | DIGEST and WANT |
| ACK for TEXT         | yes                    | no                              | yes |

### Online: MQTT topics

- **Each MQTT server is a connection of its own.**
  - Several can be open at once.
  - The public brokers count as one connection: the first one that answers, as now
    (`PartyRelays`).
- **A party's invite lists its servers.** `s=` may now appear several times. With none, the
  party uses the public servers. Members connect to all the listed servers.

```
maparound/link1/<tag, 4 hex>/<sender, 8 hex>/pos      retained
                                            /hello    retained
                                            /sos      retained
                                            /text
                                            /ack
                                            /leave    (also clears the retained ones above)
                                            /mark     marks from the radio, posted by blind relays (§8)
maparound/link1/<tag>/o/<creator, 8 hex>-<counter>   retained: the mark's full state
maparound/link1/<tag>/live                            retained: the newest LIVE
```

The `live` topic follows the rule for a mark's topic. A member who sees an older LIVE retained
there than the newest it knows publishes the newer one, after a random 1–5 s, unless someone
else publishes it meanwhile.

- **Each mark has one retained topic,** `o/…`, holding one MARK packet with every register.
  - Online, a packet can hold every register, so a shape never needs splitting.
  - Subscribers get each new publish live, so this topic is also how edits travel online.
  - It is written by members only.
- **Members write a mark's topic in two cases:**
  - after they change the mark;
  - when they see a retained state older than what they have. They wait a random 1–5 s
    first, and skip it if someone else publishes a newer one meanwhile.

### LoRa: Meshtastic

- **Each radio is a connection of its own.** Several can be connected at once, over Bluetooth
  or USB. For example, one on the public LongFast mesh and one on a club's own channel or
  preset.
- **Port and channel:** Meshtastic's PRIVATE_APP port (256), broadcast on the radio's primary
  channel with hop_limit 3, as now.
- **Size:** a Link packet is at most 200 bytes. Meshtastic's limit is 233; the rest is margin
  for its own wrapper.
- **Airtime budget per radio, shared by every party that uses it:**
  - a token bucket: on average 1 packet every 20 s, bursts of up to 3;
  - SOS and ACK go outside the budget, with SOS at most every 30 s;
  - the budget has to stay within the region's duty-cycle limit (EU_868: 10%).
- **Queue order:**
  1. urgent: SOS, ACK, LIVE;
  2. normal: LEAVE, HELLO, TEXT, MARK, then POS;
  3. bulk: DIGEST, WANT, and marks sent to answer them.

  Only the newest queued position per member per party is kept.
- **How often positions are sent** follows each member's mode
  ([§3](#modes-when-to-send-a-position)). This replaces `SendPolicy`.

## 7. Connections, layers and parties

### Connections

**A connection is one MQTT server or one LoRa radio.** A device can have any number of each
open at once. For example:

- the public servers;
- a club's own server;
- a radio on the public LongFast mesh;
- a second radio on a private channel.

In code, `Via` (online, LoRa, nearby) becomes a connection id.

### Each connection is its own layer

**Each connection is a stream, and each stream is a layer on the map** that turns on and off by
itself. None of this is on the wire; it's how the apps show what arrives.

- **What a connection's layer holds:** the party members' positions and trails that came over
  it. A radio's layer also holds the plain Meshtastic nodes it hears (trackers, other people's
  radios).
- **What it shows about the connection:** whether it's connected, when it last heard
  something, and how many members and nodes came through it.
- **A member heard over several connections is still one marker.** It sits at the newest
  position from any visible connection, and the member's card lists the connections they came
  through. Hiding a connection hides whatever came only through it.
- **Marks follow their connections too.** A mark is shown while at least one connection that
  brought it is visible, or when it was made on this phone. Each party's marks, meeting point
  included, can also be hidden all together.
- **Hiding is not disconnecting:** a hidden connection still receives, sends and relays.

### Parties on connections

**Each party stands alone.** It has its own key, tag, sender id, seq, mark counter and version
clock. Nothing on the wire links your ids in one party to your ids in another.

**Each party uses some of the connections:**

- **Online:** the servers in its invite ([§6](#6-transports)).
- **Radio:** the radios the user turns on for that party.
- **A party is accepted from any connection,** but sends only on the ones it uses.

**Parties share connections:**

- **One MQTT connection** carries a subscription for each party on that server.
- **One radio** carries every party that uses it. Packets are told apart by their tag. When
  two parties share a 2-byte tag, both keys are tried.
- **Airtime:** each radio has its own budget. Positions go to each party on it at its own
  pace, taking turns.
- **Limit:** up to 8 parties at once is the suggested maximum. How many radios can be
  connected depends on the platform's Bluetooth.

## 8. Relaying

**Three kinds of relay:**

- **Mesh:** Meshtastic radios repeat packets to each other (hop_limit 3). This is invisible to
  MapAround and needs nothing from it.
- **Gateway:** a member's phone with two or more connections: radio and online, two servers, or
  two radios on different meshes. It holds the key, and relays each party's packets between
  the connections that party uses.
- **Blind relay:** something without the key that forwards by the header alone. Examples: a box
  at base camp with a radio and internet, a hilltop node with an uplink, a phone left on in the
  car. It can't read anything, answer WANT, or split a packet that's too big.

**Rules for every relay:**

- Forward the bytes unchanged, except hops + 1. Never forward a packet whose hops is already 3.
- Forward each `(tag, sender, time, seq)` at most once per connection, and never back to the
  connection it came from.
- **Two radios on the same mesh** (same channel and preset) hear the same packets. Relay only
  between radios on different meshes, and send your own packets on just one of them.
- **Onto LoRa:**
  - only packets of 200 bytes or less;
  - positions at most once a minute per member (SOS every 30 s);
  - drop POS and HELLO older than 2 minutes (newer ones replace them anyway);
  - deliver everything else however late it is.
- **Before sending onto LoRa,** wait a random 0–3 s, and cancel if the same packet is heard on
  the radio meanwhile (another relay was quicker).
- **Online,** publish to the topic the header's type names, retained as in
  [§6](#6-transports). A MARK goes to `<sender>/mark`; the first member who merges it
  republishes the mark's full state.
- **Never relay DIGEST or WANT.**

**Which parties a blind relay carries:**

- the tags it heard on the radio in the last hour, for which it subscribes online;
- or a fixed list it was set up with.

**What a gateway does beyond that:**

- It sends marks it learned online onto the radio, in its own MARK packets, in parts if
  needed.
- It publishes the full state of marks it learned on the radio.
- It answers DIGEST and WANT on each of its connections.

## 9. Sizes

| Packet                                 | Legacy | Link v1 |
|----------------------------------------|--------|---------|
| Position (accuracy, battery, heading)  | 54     | 32      |
| Position with speed and altitude       | 54     | 35      |
| Position with a trail of 4–6 points    | –      | 45–56   |
| Hello (5-letter name, radio node)      | 54     | 42 (with the marks' summary) |
| New point with a name and an icon      | –      | 47      |
| Move a mark                            | –      | 36      |
| Delete a mark                          | –      | 30      |
| ACK                                    | –      | 32      |
| LIVE (the whole party goes Live)       | –      | 26      |
| Airtime of a position on LongFast      | ≈ 740 ms | ≈ 575 ms (with a trail: 700–780 ms) |

The airtime figures assume SF11, 250 kHz, coding rate 4/5, with Meshtastic's 16-byte header and
about 7 bytes of its data wrapper.

## 10. Security and privacy

- **The trust model is the same as legacy: the invite is the key.**
  - Everyone who has it reads everything.
  - They can send as any member, and edit or delete any mark.
  - Signing each member's packets (Ed25519: 64 bytes each) doesn't fit the radio's budget, so
    v1 doesn't.
  - Removing someone means starting a new party.
- **What brokers and relays see:**
  - the party's tag (2 bytes, not a secret), sender ids, send times, sequence numbers, types,
    hops and sizes;
  - not positions, names, marks or messages.

  The type is now visible on the radio as well. Online it already was, in the topic. That is
  what lets a relay put an SOS first.
- **A nonce never repeats.**
  - `(sender, time, seq)` would need the same sender to send the same `seq` in the same second.
  - `seq` only comes back round after 65 536 packets, which takes days.
  - A phone restored from a backup sends at a later time than when the backup was made.
  - Sender id 0 is never used.
- **An 8-byte tag is enough for this.** Forging a packet takes about 2^64 tries. LoRaWAN and
  BLE use 4 bytes. Android's `javax` GCM refuses tags shorter than 12 bytes, so all platforms
  use the shared Kotlin `Gcm`, which iOS and the web already do.
- **Replaying a packet changes nothing.** Duplicates are dropped. An old position is older than
  the newest one, and a mark version that was already merged changes nothing.
- **Clocks:** packets dated more than 10 minutes ahead are dropped, and the sender's clock is
  flagged as wrong.

## 11. The legacy format

- **Not supported.** Apps with Link v1 neither read nor send it, and don't subscribe to
  `maparound/v1/…`. The legacy code goes.
- **Invites don't change,** so existing parties carry on once everyone has updated. Members
  still on an old version don't see the others, and aren't seen by them.

## 12. Not in v1

- Lines visible to others while they're still being drawn.
- Read-only invites, which need signatures.
- Removing a member without starting a new party.
- Folders or sub-layers within a party's marks.
- Photos and attachments.
- One-to-one messages.
- A Meshtastic port number of MapAround's own, instead of PRIVATE_APP.
- Finer precision than the 1.2–2.4 m grid, which is a later issue.

## 13. Open questions

None at the moment. Settled on 2026-11-20:

- Hiding a connection hides the marks that came only through it, and marks carry their last
  editor.
- Anyone can edit or delete any mark.
- No legacy support.
- The LoRa budget is 1 packet every 20 s on average per radio.
- The modes' numbers. Auto switches between the modes by default.
- A good fix after a time without one is sent at once.
- Auto's rules and thresholds, as in [§3](#auto-mode).
- Any member can switch the whole party to Live (LIVE).
