"""What the shots' steps share: the app on the device, the map moved somewhere, a position, the
party's other members, the screen captured."""
import io
import os
import subprocess
import threading
import time
from pathlib import Path

from PIL import Image

from ui import Ui

REPO = Path(__file__).resolve().parents[2]
GRADLE = str(REPO / ("gradlew.bat" if os.name == "nt" else "gradlew"))


class Session:
    def __init__(self, device, labels, lang="en", raw_dir=None):
        self.d = device
        self.ui = Ui(device)
        self.L = labels  # what the screen says, in the run's language (plan.LABELS)
        self.lang = lang
        self.raw_dir = Path(raw_dir) if raw_dir else None
        self.sim = None
        self.invite = None

    # ---- the app --------------------------------------------------------------------------------

    def tab(self, label):
        """One of the bottom bar's tabs: Map, Library, Party, Offline, Sensors."""
        self.ui.tap(label)
        time.sleep(1.5)

    def fresh(self):
        """The app started anew on the map, nothing open."""
        self.d.stop()
        self.d.start()
        time.sleep(4)
        for _ in range(3):  # whatever a first start or an earlier shot left open
            if self.ui.has(self.L["map"]) and self.ui.has(self.L["sensors"]):
                break
            self.d.back()
            time.sleep(1)
        self.tab(self.L["map"])

    def close_sheet(self):
        """A bottom sheet (the layers…) closed: its close strip, or Back when it fills the screen."""
        for _ in range(4):
            nodes = self.d.nodes()
            if not any("Drag handle" in n.desc for n in nodes):
                return
            close = next((n for n in nodes if n.desc.startswith(("Close sheet", "סגירת"))), None)
            if close:
                self.d.tap(*close.centre)
            else:
                self.d.back()
            time.sleep(1.2)

    def leave_party(self, labels):
        """Out of any party (one an earlier run left the device in shows members on every map)."""
        self.tab(labels["party"])
        if self.ui.has(labels["leave"]):
            self.ui.tap(labels["leave"])
            self.ui.wait(labels["leave_title"])
            self.ui.tap(labels["leave"])
            time.sleep(2)

    def send(self, text):
        """Text shared to the app, as another app would: a geo: or map link, a route, an invite."""
        quoted = "'" + text.replace("'", "'\\''") + "'"
        self.d.shell("am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain",
                     "--es", "android.intent.extra.TEXT", quoted, "-p", "app.maparound")
        time.sleep(3)

    def at(self, lat, lon, zoom):
        """The map moved to a place and zoom (the pin a geo: link leaves is closed again)."""
        self.send(f"geo:{lat},{lon}?z={zoom}")
        try:
            self.ui.tap_any(self.L["close"], timeout=6)
        except Exception:
            pass
        time.sleep(1)

    def me(self, lat, lon):
        """Where the device says it is."""
        self.d.set_location(lat, lon)
        time.sleep(1)

    def settle(self, seconds=6):
        """Time for the map's tiles, terrain and names."""
        time.sleep(seconds)

    # ---- the party's other members (tools/party-sim) -----------------------------------------------

    def party_start(self, plan=REPO / "tools" / "shots" / "party.json"):
        """Starts the members; returns the invite link once they're all online."""
        if self.sim:
            return self.invite
        cmd = [GRADLE, "-q", "--console=plain", "-p", "tools/party-sim", "run", f"--args={plan.relative_to(REPO).as_posix()}"]
        env = dict(os.environ)
        jdk = Path.home() / ".mapAround-toolchain" / "jdk17"  # the project's own, where JAVA_HOME isn't set
        if "JAVA_HOME" not in env and jdk.is_dir():
            env["JAVA_HOME"] = str(jdk)
        self.sim = subprocess.Popen(cmd, cwd=REPO, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
        ready = threading.Event()

        def read():
            for line in self.sim.stdout:
                line = line.strip()
                if line.startswith("INVITE "):
                    self.invite = line[7:]
                elif line == "READY":
                    ready.set()
                print("  party:", line)

        threading.Thread(target=read, daemon=True).start()
        if not ready.wait(300):
            raise SystemExit("The party's members didn't get online.")
        return self.invite

    def party_stop(self):
        if self.sim and self.sim.poll() is None:
            try:
                self.sim.stdin.write("leave\n")
                self.sim.stdin.flush()
                self.sim.wait(30)
            except Exception:
                self.sim.kill()
        self.sim = None

    # ---- the picture --------------------------------------------------------------------------

    def capture(self, name):
        """The screen now ([name] for the record; shots.py keeps it once anything private is blurred)."""
        return Image.open(io.BytesIO(self.d.screenshot())).convert("RGB")
