---
title: Parties · MapAround guide
lang: en
---

[עברית]({{ '/he/guide/party' | relative_url }}) · [All guides]({{ '/guide/' | relative_url }})

# Parties on the map

A **party** shares your group's positions live on the map, with SOS alerts, a meeting point and
short messages. It works in the Android app and in the [web app]({{ '/app/' | relative_url }}),
online and off-grid over LoRa radios. This guide covers being in several parties at once and
what each one shows on the map. How the messages travel, and running a server of your own, is in
[the party's server]({{ '/guide/party-server' | relative_url }}).

## Starting and joining

On the **Party** screen, **Start a party** gives it a name and shows its invite: a QR code and a
link. Whoever opens the invite joins. Share it only with your group: anyone with it can see the
party.

## Several parties at once

You can be in up to **4 parties** at once: say your hiking club and your family. Joining one no
longer leaves the one you're in.

- **The row at the top of the Party screen** has a chip for each party, in its colour. The screen
  shows the party you pick; an SOS in another one shows on its chip. **Another party** starts or
  joins one more. Opening the invite of a party you're in already just shows it.
- **Each party has its own:**
  - your id in it (nothing links your ids in two parties);
  - **Party on** (sending and receiving) and **Share my location**, so you can share in one and
    stay hidden in another;
  - its server, its members, its messages and its meeting point.
- **The same for all of them:** your name and look, the LoRa radio, the online switch and the
  gateway. One connection to a server carries every party on it. A radio's airtime is shared too,
  so each extra party on the radio leaves a little less for the others.
- **An SOS goes to every party that's on**, and to the one shown: help may be in any of them. The
  SOS dialog lists them. Cancelling it cancels it everywhere.
- **Leaving** (on the party's own card) tells its members and leaves your other parties as they
  are.

## On the map

Open **Map layers → Overlays**. Under **Party** (all parties on or off), each party has its own
rows:

- **Its name**, with its colour dot and how many members it has: its members on the map. Tap the
  dot to pick another colour.
- **Trails**: where each member went in the last 30 minutes, a line in their colour that ends at
  their marker.
- **Meeting point and marks**: the party's meeting point, and the points, lines and areas its
  members marked.

With several parties on the map, each party's markers have its colour round them, and its name
follows theirs: "Dana · Hike".

**Connections** lists the ways the parties connect: the public servers, a party's own server, the
radio. Each shows how it's doing, how many members came through it, and when it last heard one.
Switching one off hides what came only through it: a member then sits at their newest position
from a connection that's on. A hidden connection still receives, sends and relays. Under the
radio, **Meshtastic nodes** shows or hides the plain radios it hears (trackers, other people's
radios).

**An SOS always shows**, whatever is switched off.

## Tapping on the map

- **A member**: how far and which way, how long ago, the connections they came through, their
  speed, height and battery, with **History** (their way so far, replayed), **Route here** and
  **Share**. With several parties, which party they're in.
- **The meeting point** and **a mark**: its name and note, a line's length, who changed it last.
  **Route here** goes to its point nearest you.
- **Meet here**: long-press the map, then **Meet here**. With several parties on, it asks which
  party's meeting point to set.

## Notifications

An SOS, a message and a new meeting point arrive as notifications; with several parties, the
party's name follows the title. On Android, the party's own notification while sharing lists
every party that's on, with each one's members, and the car screen counts the members of all of
them.

In the web app, the parties run while the page is open.

## Privacy

Your position goes only to the parties you share it in, encrypted with each party's key. The
servers and radios in between see that encrypted messages pass, not what's in them. See the
[privacy policy]({{ '/privacy' | relative_url }}).
