# Taking the screenshots again

`shots.py` takes the pictures [SHOTS.md](SHOTS.md) lists, on a phone or the emulator, and puts the
finished ones where they go: Google Play's framed screenshots in `docs/store/screenshots/`, the
website's in `docs/assets/`, the guides' in `docs/guide/img/` (Hebrew: `docs/he/guide/img/`).

```sh
python -m pip install pillow                          # once
python tools/shots/shots.py --list                    # the shots
python tools/shots/shots.py                           # all of them, on the connected phone
python tools/shots/shots.py --device emulator         # on the emulator (maparound-shots)
python tools/shots/shots.py --only map,satellite      # some (and those they need first)
python tools/shots/shots.py --lang he --only offline-list,offline-menu   # the Hebrew guides'
python tools/shots/shots.py --install                 # build and install the release first
python tools/shots/shots.py --export-only             # frame the raw ones kept last time again
```

Then look at them (`git diff --stat docs/`, and the pictures), and commit what's better.

## The parts

| File | What it does |
|---|---|
| `shots.py` | The command: picks the device, runs the shots in order, exports |
| `device.py` | The device over adb. `Phone`: its position from a test location provider; `Emulator`: started if it isn't running, its position from the emulator's GPS. Both: demo status bar, a 9:16 screen, screenshots, what's on screen |
| `ui.py` | Finding and tapping things by what they say (text or TalkBack description) |
| `session.py` | What the steps share: the app started on the map, the map moved somewhere, a position, the party's other members, a capture |
| `plan.py` | The shots, each a few steps, and the labels they tap in English and Hebrew |
| `export.py` | Google Play's frame (caption on the brand green), the site's 720×1280 WebP, the guides' 720 px WebP |
| `party.json` | The party's other members: names, icons, colours, places, batteries, the meeting point |
| `../party-sim/` | Plays those members: the app's own party code on the computer, over the public MQTT brokers |

To add a shot: a function in `plan.py` with `@shot("id", …)` saying where it goes, and a line in
SHOTS.md. To change a caption or where a member stands: `plan.py`, `party.json`.

## What the device needs

- MapAround installed (`--install`), started once, its first-start questions answered, the map's
  layers on **Offline vector maps** with the **Topo** style.
- Downloaded on it, from Offline maps: **Andorra**, and from its **⋮** menu Contours & hill shading
  (high detail), Offline routing and Marked trails.
- A phone: USB or Wi‑Fi debugging on, **unlocked**, the screen set to stay on a while (the shots
  take a few minutes). While they run, its position is made up (Andorra) and its screen drawn at
  1080×1920; both are put back at the end, also when a shot fails.
- The emulator: the `maparound-shots` AVD in `~/.mapAround-toolchain/avd` (a "default" system
  image: the script runs adb as root there, for the two-finger tilt of the 3D view). Its GPS screen
  shows satellites in view but none in use: the GPS shot is better taken on a phone
  (`--only gps`).
- For the party shots, the shared code built once: `./gradlew :shared:compileDebugKotlinAndroid`
  (`party-sim` uses it), and the internet.

## Privacy

Nothing real gets published: the position is made up; on a phone the GPS screen's satellites are
real, so its position rows are blurred (in the raw shots kept in `build/shots/` too); the status
bar is in demo mode, without notifications. Look at every picture before committing it.
