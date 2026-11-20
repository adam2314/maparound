---
title: Party server · MapAround guide
lang: en
---

[עברית]({{ '/he/guide/party-server' | relative_url }}) · [All guides]({{ '/guide/' | relative_url }})

# The party's server

A **party** shares your group's positions live on the map, with SOS alerts, a meeting point and
short messages. It works in the Android app and in the [web app]({{ '/app/' | relative_url }}).
This guide explains how the messages travel, what the servers in between can see, and how to run
your own server, either [MapAround's party server](#maparound-party-server) or any MQTT server.

## How messages travel

Everything a party sends is **end-to-end encrypted** (AES-256-GCM) with the party's secret, which
is in the invite link and QR code. Whatever carries the messages only carries ciphertext.

The **Connections** card on the **Party** screen shows the ways a party connects:

- **Online:** through an MQTT server on the internet, over secure WebSockets
  ([MQTT](https://mqtt.org) is a small, widely used messaging protocol).
- **LoRa radio:** off-grid, through a [Meshtastic](https://meshtastic.org) radio paired with your
  phone by Bluetooth. No phone signal needed; radios reach each other over kilometres.
- **Gateway:** a phone with both passes messages between radio and online, so off-grid and online
  members see each other.

## The public servers

Unless you choose your own, a party uses free public servers, tried in this order:
broker.emqx.io, test.mosquitto.org and broker.hivemq.com. Messages go to topics like
`maparound/link1/<party tag>/<member id>/<kind>`. Each member's latest position, name and SOS, and
the party's marks and meeting point, are kept on the server (*retained*), so whoever joins sees
everyone at once. Leaving the party removes yours.

**What a server can see:**
- the members' IP addresses;
- when and how often each one sends, and the size and kind of each message (position, message,
  mark…);
- the party's tag and random member ids.

Not the party's name, nor any name, position or message. It can't change messages without the apps
noticing, but it could fail to pass them on.

Public servers are fine for trying a party out. They promise nothing: they can be slow, full or
down, and they may drop what they keep. **For a real group, use your own server.**

## Using your own server

1. On the **Party** screen, in the **Connections** card, tap **Server → Change**.
2. Enter a secure WebSocket address, such as `wss://party.example.org/mqtt`.
3. Share the invite: the link and the QR code carry the server, so everyone who joins with them
   uses the same one, the app and the web app alike.

<p class="shots"><img src="{{ '/guide/img/party-server.webp' | relative_url }}" alt="The party's server: a wss:// address, or back to the public servers" width="720" height="1195" loading="lazy"></p>

Changing the server in a running party moves you alone: share the invite again, and have everyone
open it. New parties you start use the server you chose last. **Use the public servers** in the same
dialog goes back to them.

Everyone in the party needs **MapAround 0.5.4 or newer**: parties now use MapAround's Link v1
protocol, which older versions don't speak.

## MapAround party server

[maparound-server](https://github.com/adam2314/maparound-server) is a free, open-source server
made for MapAround's parties. It's MQTT too, plus what a group's organiser needs:

- **Verified ids.** Each phone proves its id with a secret it makes when it joins the party, so
  nobody can send as someone else over the internet (the radio is another matter:
  [below](#with-lora-radios)).
- **Who joins.** A party on it is *open* (anyone with the invite) or *by approval* (you approve each
  new member on its admin page).
- **Ban and mute.** A banned member's messages and position go nowhere. A muted one can't send
  messages or marks, but their position and SOS still go through.
- **A message log.** Messages are kept (30 days by default) and handed to members as they connect,
  so whoever joins late, or was out of signal, sees what was said.
- **An admin page,** at `https://<your server>/admin`.

It never needs the party's key: messages stay end-to-end encrypted, and so does its log.

Your id is shown on the Party screen, under the server: that's how the admin page lists you. Tell
the organiser your id if they approve members.

### With LoRa radios

Members on the radio reach the server through a gateway: a member's phone that has both
([below](#off-grid-lora-radios)). The server takes what a gateway passes on for them too.

- **Someone only ever on the radio** shows on the admin page with their id and *no token*. In a
  party by approval, they wait for approval like anyone new. Their id is on their Party screen
  too.
- **When a gateway connects,** it puts only the last 30 minutes of the server's log onto the
  radio, so old messages don't flood the air.
- **A ban or a mute** applies to what goes through the server. On the radio itself nothing can
  stop anyone: members in radio range still hear a banned member directly. To shut someone out
  of the radio too, start a new party and share its invite with the others.
- **What comes over the radio can't be verified** like what phones send directly: anyone in the
  party can make a radio message under another member's id. The verified ids are about the
  internet side.

### Deploying it

You need a small server that's always on (1 CPU and 1 GB of memory are plenty), with a domain name
pointing at it, and ports 80 and 443 open. The server comes with
[Caddy](https://caddyserver.com), which gets and renews its HTTPS certificate by itself.

1. **Point a domain at it:** an `A` record such as `party.example.org` with the server's IP address.
2. **Install Docker** on it ([docs.docker.com/engine/install](https://docs.docker.com/engine/install/)).
3. **Get MapAround's party server and set it up:**

   ```sh
   git clone https://github.com/adam2314/maparound-server.git
   cd maparound-server
   cp .env.example .env
   nano .env        # your domain, and a password for the admin page
   ```

4. **Start it:** `docker compose up -d`. It starts again by itself after a reboot.
5. **Sign in** at `https://party.example.org/admin` with the password from `.env`. Choose there
   whether new parties are open or by approval.
6. **In MapAround,** set the party's server to `wss://party.example.org/mqtt`, and share the
   invite.

To update it later: `git pull && docker compose up -d --build`. The parties, members and log are
kept across updates. Running it without Docker, or behind nginx, is in its
[README](https://github.com/adam2314/maparound-server#readme).

## Any other MQTT server

Any MQTT server can carry a party, without the extras above, as long as it:

- speaks MQTT 3.1.1 over **secure WebSockets** (`wss://`), with a certificate phones and browsers
  trust (Let's Encrypt works; a self-signed one doesn't);
- lets clients in with the user name **`maparound`** (and any password), or with none: MapAround
  sends that name, with its ids, to a party's own server;
- keeps **retained messages**;
- is reachable from the internet, from phones and from browsers.

**[Mosquitto](https://mosquitto.org)** on any small Linux server with a domain name does it. Party
traffic is tiny: a position update is about 32 bytes.

1. Install Mosquitto and get a certificate for the domain (for example with certbot).
2. Copy the certificate and key to `/etc/mosquitto/certs/`, readable by the `mosquitto` user (and
   again after each renewal: certbot's `--deploy-hook` can do it).
3. Add a file `/etc/mosquitto/conf.d/maparound.conf`:

   ```
   listener 8084
   protocol websockets
   certfile /etc/mosquitto/certs/fullchain.pem
   keyfile /etc/mosquitto/certs/privkey.pem
   allow_anonymous true
   acl_file /etc/mosquitto/maparound.acl
   ```

4. The ACL limits clients to MapAround's topics. The first line is for clients without a name, the
   last two for MapAround's (with no password file, Mosquitto doesn't check the password),
   `/etc/mosquitto/maparound.acl`:

   ```
   topic readwrite maparound/link1/#
   user maparound
   topic readwrite maparound/link1/#
   ```

5. Open port 8084 in the firewall and restart Mosquitto. With `persistence true` (the default in
   Debian and Ubuntu's package), the kept positions and marks survive a restart.

The address for MapAround is then `wss://mqtt.example.org:8084/mqtt`. Try it in the web app first:
the **Online** connection should say **Connected** with your server's name.

**Hosted MQTT services**, such as EMQX Cloud or HiveMQ Cloud, have free plans, but they ask for
their own user names and passwords, which MapAround doesn't send. Check their terms and settings
before you rely on one.

## Off-grid: LoRa radios

Where there's no signal, no server helps. Give the group [Meshtastic](https://meshtastic.org)
radios (small LoRa boards), all on the same Meshtastic region and channel, and pair each one under
**Connections → LoRa radio → Pair**. Keep **Gateway** on for at least one phone that has mobile
data, so radio-only and online members see each other. Radio messages are encrypted with the
party's key too, and don't depend on the server you choose.

## Privacy

Only people with the invite can read the party, so share it only with your group. The server's
address is in the invite link, after the `#`, which browsers keep on the device. See the
[privacy policy]({{ '/privacy' | relative_url }}) for what each service receives.
