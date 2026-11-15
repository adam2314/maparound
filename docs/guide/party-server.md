---
title: Party server · MapAround guide
lang: en
---

[עברית]({{ '/he/guide/party-server' | relative_url }}) · [All guides]({{ '/guide/' | relative_url }})

# The party's server

A **party** shares your group's positions live on the map, with SOS alerts, a meeting point and
short messages. It works in the Android app and in the [web app]({{ '/app/' | relative_url }}).
This guide explains how the messages travel, what the servers in between can see, and how to use
your own server.

## How messages travel

Everything a party sends is **end-to-end encrypted** (AES-256-GCM) with the party's secret, which
is in the invite link and QR code. Whatever carries the messages only carries ciphertext.

The **Connections** card on the **Party** screen shows the ways a party connects:

- **Online**: through an MQTT server on the internet ([MQTT](https://mqtt.org) is a small,
  widely used messaging protocol).
- **LoRa radio**: off-grid, through a [Meshtastic](https://meshtastic.org) radio paired with your
  phone by Bluetooth. No phone signal needed; radios reach each other over kilometres.
- **Gateway**: a phone with both passes messages between radio and online, so off-grid and online
  members see each other.

## The public servers

Unless you choose your own, a party uses free public servers, tried in this order:
broker.emqx.io, test.mosquitto.org and broker.hivemq.com. The apps connect over MQTT with TLS, the
web app over secure WebSockets. Messages go to topics like
`maparound/v1/<party tag>/<sender>/<kind>`. Each member's latest position, name, SOS and the
meeting point are kept on the server (*retained*), so whoever joins sees everyone at once.
Leaving the party removes yours.

**What a server can see:** the IP addresses of the members, when and how often each one sends,
the size of the messages, the party's tag and random member ids. Not the party's name, nor any
name, position or message. It can't change messages without the apps noticing, but it could fail
to pass them on.

Public servers are fine for trying a party out. They promise nothing: they can be slow, full or
down, and they may drop what they keep. **For a real group, use your own server.**

## Using your own server

1. On the **Party** screen, in the **Connections** card, tap **Server → Change**.
2. Enter a secure WebSocket address, such as `wss://mqtt.example.org:8084/mqtt`.
3. Share the invite: the link and the QR code carry the server, so everyone who joins with them
   uses the same one, the app and the web app alike.

<p class="shots"><img src="{{ '/guide/img/party-server.webp' | relative_url }}" alt="The party's server: a wss:// address, or back to the public servers" width="720" height="1195" loading="lazy"></p>

Changing the server in a running party moves you alone: share the invite again, and have everyone
open it. New parties you start use the server you chose last. **Use the public servers** in the same
dialog goes back to them.

Everyone in the party needs **MapAround 0.5.1 or newer**. Older versions don't read the server in
the invite and stay on the public servers, where they won't meet the others.

The server must:

- speak MQTT 3.1.1 over **secure WebSockets** (`wss://`), with a certificate phones and browsers
  trust (Let's Encrypt works; a self-signed one doesn't);
- accept clients **without a user name or password**;
- keep **retained messages**;
- be reachable from the internet, from phones and from browsers.

## Running one (technical)

[Mosquitto](https://mosquitto.org), the open-source MQTT server, on any small Linux server with a
domain name does it. Party traffic is tiny: a position update is about 54 bytes.

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

4. The ACL limits clients without a user name to MapAround's topics,
   `/etc/mosquitto/maparound.acl`:

   ```
   topic readwrite maparound/v1/#
   ```

5. Open port 8084 in the firewall and restart Mosquitto. With `persistence true` (the default in
   Debian and Ubuntu's package), the retained positions survive a restart.

The address for MapAround is then `wss://mqtt.example.org:8084/mqtt`. Try it in the web app first:
the **Online** connection should say **Connected** with your server's name.

[EMQX](https://www.emqx.io), another open-source MQTT server, has a secure WebSocket listener at
port 8084, path `/mqtt`, out of the box.

**Hosted services**, such as EMQX Cloud or HiveMQ Cloud, have free plans, but they usually require
a user name and password, which MapAround doesn't send. Check their current terms and settings
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
