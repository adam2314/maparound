"""Takes MapAround's screenshots again (SHOTS.md) and puts them where they go.

    python tools/shots/shots.py                      # all of them, on the phone that's connected
    python tools/shots/shots.py --device emulator    # on the emulator (started if need be)
    python tools/shots/shots.py --only map,gps       # some
    python tools/shots/shots.py --lang he --only offline-list   # the Hebrew guides'
    python tools/shots/shots.py --list               # what there is
    python tools/shots/shots.py --export-only        # again from the raw shots kept last time

The app must be installed (--install builds and installs the release first). The raw screenshots
are kept in build/shots/<lang>/; the finished ones go to docs/ (see SHOTS.md).
"""
import argparse
import subprocess
import sys
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import export  # noqa: E402 (checks for Pillow)
from device import connect  # noqa: E402
from plan import LABELS, SHOTS  # noqa: E402
from session import GRADLE, REPO, Session  # noqa: E402
from PIL import Image  # noqa: E402


def order(ids):
    """The shots with those they need first (a shot leaves the app ready for the next)."""
    out = []

    def add(i):
        for n in SHOTS[i].needs:
            add(n)
        if i not in out:
            out.append(i)

    for i in ids:
        add(i)
    return out


def finish(shot, img, lang, bars):
    done = []
    if lang == "en" and shot.play:
        done.append(export.play(img, shot.caption, shot.play, shot.id, bars))
    if lang == "en" and shot.site:
        done.append(export.site(img, shot.id))
    if shot.guide:
        done.append(export.guide(img, shot.id, lang, bars))
    return done


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--device", choices=["phone", "emulator"], default="phone")
    p.add_argument("--serial", help="which phone (adb devices), or which emulator")
    p.add_argument("--avd", help="the emulator's AVD (maparound-shots)")
    p.add_argument("--only", help="shot ids, comma-separated")
    p.add_argument("--lang", choices=["en", "he"], default="en")
    p.add_argument("--install", action="store_true", help="build and install the release first")
    p.add_argument("--export-only", action="store_true", help="only make the finished pictures from the raw ones")
    p.add_argument("--list", action="store_true")
    a = p.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")  # the Hebrew labels in messages, on any console

    if a.list:
        for s in SHOTS.values():
            where = ", ".join(x for x in (f"Play {s.play}" if s.play else "", "site" if s.site else "", "guide" if s.guide else "") if x)
            print(f"{s.id:18} {where:24} {s.caption or ''}")
        return

    wanted = [x.strip() for x in a.only.split(",")] if a.only else list(SHOTS)
    unknown = [x for x in wanted if x not in SHOTS]
    if unknown:
        sys.exit(f"no such shot: {', '.join(unknown)} (--list)")
    raw = REPO / "build" / "shots" / a.lang

    if a.export_only:
        bars = (96, 48)
        for i in wanted:
            f = raw / f"{i}.png"
            if f.exists():
                for d in finish(SHOTS[i], Image.open(f).convert("RGB"), a.lang, bars):
                    print(" ", d.relative_to(REPO))
        return

    device = connect(a.device, a.serial, a.avd)
    if a.install:
        subprocess.run([GRADLE, ":app:assembleRelease", "-q"], cwd=REPO, check=True)
        device.prepare()
        device.install(REPO / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk")
    if not device.installed():
        sys.exit("MapAround isn't installed on the device (--install).")
    device.prepare()
    device.grant()
    device.app_language(a.lang)  # whatever the device's own language
    labels = LABELS[a.lang]
    session = Session(device, labels, a.lang, raw)
    failed = []
    try:
        bars = device.bars()
        session.fresh()
        session.leave_party(labels)  # no members of an earlier party on the maps
        for i in order(wanted):
            shot = SHOTS[i]
            print(f"{i}…")
            try:
                img, blur = shot.take(session, labels)
            except Exception as e:
                traceback.print_exc()
                failed.append(i)
                print(f"  failed: {e}")
                continue
            export.blur(img, blur)
            raw.mkdir(parents=True, exist_ok=True)
            img.save(raw / f"{i}.png")
            if i in wanted:
                for d in finish(shot, img, a.lang, bars):
                    print(" ", d.relative_to(REPO))
    finally:
        if session.sim:  # the device out of the party, then the members gone
            try:
                session.leave_party(labels)
            except Exception:
                pass
        session.party_stop()
        device.app_language(None)
        device.finish()
    if failed:
        sys.exit(f"failed: {', '.join(failed)} (the raw shots taken are in {raw.relative_to(REPO)})")


if __name__ == "__main__":
    main()
