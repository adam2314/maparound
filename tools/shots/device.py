"""The device the shots are taken on, over adb: a phone (`Phone`) or the Android emulator
(`Emulator`). Both do the same things; only how they get there differs (where the position comes
from, how the device starts)."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

SDK = Path(os.environ.get("ANDROID_HOME") or Path.home() / ".mapAround-toolchain" / "sdk")
EXE = ".exe" if os.name == "nt" else ""
ADB = str(SDK / "platform-tools" / f"adb{EXE}")
EMULATOR = str(SDK / "emulator" / f"emulator{EXE}")
PACKAGE = "app.maparound"


@dataclass
class Node:
    """Something on screen with a text or a description (what TalkBack reads)."""
    text: str
    desc: str
    bounds: tuple  # x1, y1, x2, y2
    clickable: bool

    @property
    def label(self):
        return (self.text + " " + self.desc).strip()

    @property
    def centre(self):
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


class Device:
    def __init__(self, serial):
        self.serial = serial

    # ---- adb ----------------------------------------------------------------------------------

    def adb(self, *args, binary=False, check=True, timeout=120):
        cmd = [ADB] + (["-s", self.serial] if self.serial else []) + [str(a) for a in args]
        r = subprocess.run(cmd, capture_output=True, timeout=timeout)
        if check and r.returncode != 0:
            raise RuntimeError(f"{' '.join(cmd)}: {r.stderr.decode('utf-8', 'replace').strip()}")
        return r.stdout if binary else r.stdout.decode("utf-8", "replace")

    def shell(self, *args, check=True):
        return self.adb("shell", *args, check=check)

    # ---- the device -----------------------------------------------------------------------------

    def prepare(self):
        """Ready to take shots: awake and unlocked, the status bar in demo mode, and a screen no
        taller than 9:16 (Google Play's and the site's shape): a taller one draws at 1080×1920."""
        self.require_unlocked()
        # The screen stays on while the shots run (10 minutes; the device's own setting back after).
        self._timeout = self.shell("settings", "get", "system", "screen_off_timeout").strip()
        self.shell("settings", "put", "system", "screen_off_timeout", "600000")
        w, h = self.size()
        if h * 9 > w * 16:
            self.shell("wm", "size", f"{w}x{w * 16 // 9}")
            self._resized = True
            time.sleep(2)
        self.demo_bar(True)

    def finish(self):
        """Back as it was: the screen size, the status bar, the position."""
        if getattr(self, "_resized", False):
            self.shell("wm", "size", "reset")
            self._resized = False
        if getattr(self, "_timeout", "").isdigit():
            self.shell("settings", "put", "system", "screen_off_timeout", self._timeout)
        self.demo_bar(False)
        self.clear_location()

    def size(self):
        m = re.findall(r"(\d+)x(\d+)", self.shell("wm", "size"))
        w, h = map(int, m[-1])  # an override comes last
        return w, h

    def bars(self):
        """Heights of the status bar and the navigation (gesture) bar, pixels."""
        out = self.shell("dumpsys", "window")
        _, h = self.size()

        def frame(kind):
            m = re.search(rf"type={kind} frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", out)
            return tuple(map(int, m.groups())) if m else None

        status, nav = frame("statusBars"), frame("navigationBars")
        top = status[3] if status else 96
        bottom = h - nav[1] if nav and nav[1] > h // 2 else 48
        return top, bottom

    def require_unlocked(self):
        power = self.shell("dumpsys", "power")
        if "mWakefulness=Awake" not in power:
            self.shell("input", "keyevent", "KEYCODE_WAKEUP")
            time.sleep(1)
        window = self.shell("dumpsys", "window")
        if re.search(r"isKeyguardShowing=true|mDreamingLockscreen=true", window):
            raise SystemExit("The device is locked: unlock it (and keep the screen on while the shots run).")

    def demo_bar(self, on):
        """The status bar in demo mode: 14:30, full battery and signal, no notifications."""
        self.shell("settings", "put", "global", "sysui_demo_allowed", "1", check=False)

        def demo(*args):
            self.shell("am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", *args, check=False)

        if not on:
            demo("exit")
            return
        demo("enter")
        demo("clock", "-e", "hhmm", "1430")
        demo("battery", "-e", "level", "100", "-e", "plugged", "false")
        demo("network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true")
        demo("network", "-e", "mobile", "show", "-e", "datatype", "none", "-e", "level", "4", "-e", "fully", "true")
        demo("notifications", "-e", "visible", "false")
        demo("status", "-e", "location", "hide", "-e", "alarm", "hide", "-e", "volume", "hide", "-e", "bluetooth", "hide",
             "-e", "mute", "hide", "-e", "speakerphone", "hide", "-e", "sync", "hide", "-e", "zen", "hide")

    # ---- the app --------------------------------------------------------------------------------

    def installed(self):
        return f"package:{PACKAGE}" in self.shell("pm", "list", "packages", PACKAGE)

    def install(self, apk):
        self.adb("install", "-r", apk, timeout=600)

    def app_language(self, lang):
        """The app's language (Android's per-app language; None: the device's own again)."""
        self.shell("cmd", "locale", "set-app-locales", PACKAGE, "--locales", lang or "''", check=False)
        self.stop()

    def grant(self):
        """What the shots need without asking: location, notifications."""
        for p in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS"):
            self.shell("pm", "grant", PACKAGE, f"android.permission.{p}", check=False)

    def start(self):
        self.shell("monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")

    def stop(self):
        self.shell("am", "force-stop", PACKAGE)

    def open(self, url):
        """A link the app opens: geo:lat,lon?z=15, maparound://party/…"""
        self.shell("am", "start", "-a", "android.intent.action.VIEW", "-d", f"'{url}'", "-p", PACKAGE)

    # ---- the screen -----------------------------------------------------------------------------

    def tap(self, x, y):
        self.shell("input", "tap", x, y)

    def swipe(self, x1, y1, x2, y2, ms=300):
        self.shell("input", "swipe", x1, y1, x2, y2, ms)

    def touchscreen(self):
        """The touchscreen's input device and its coordinate range (for two-finger gestures)."""
        if getattr(self, "_touch", None):
            return self._touch
        out = self.shell("getevent", "-p")
        touch = []
        for block in re.split(r"\nadd device \d+: ", "\n" + out):
            m = re.match(r"/dev/input/event(\d+)", block)
            mx = re.search(r"0035\s*:\s*value -?\d+, min -?\d+, max (\d+)", block)  # ABS_MT_POSITION_X
            my = re.search(r"0036\s*:\s*value -?\d+, min -?\d+, max (\d+)", block)  # ABS_MT_POSITION_Y
            if m and mx and my:
                touch.append((int(m.group(1)), int(mx.group(1)), int(my.group(1))))
        if not touch:
            raise RuntimeError("no touchscreen found")
        # The first display's (the emulator lists one per possible display).
        n, max_x, max_y = min(touch)
        self._touch = (f"/dev/input/event{n}", max_x, max_y)
        return self._touch

    def two_fingers(self, cx, cy, half, dx=0, dy=0, spread=0, steps=14):
        """Two fingers [half]·2 apart beside (cx, cy), moving together by (dx, dy) and apart by
        [spread]: a tilt (dy), a pan, or a pinch (spread)."""
        dev, max_x, max_y = self.touchscreen()
        w, h = self.size()
        sx = lambda v: int(v * max_x / w)
        sy = lambda v: int(v * max_y / h)
        lines = []
        ev = lambda t, c, v: lines.append(f"sendevent {dev} {t} {c} {v}")
        for i in range(steps + 1):
            d = half + spread * i // steps
            for slot, x in ((0, cx - d), (1, cx + d)):
                ev(3, 47, slot)
                if i == 0:
                    ev(3, 57, 100 + slot)
                    ev(3, 58, 50)
                ev(3, 53, sx(x + dx * i // steps))
                ev(3, 54, sy(cy + dy * i // steps))
            if i == 0:
                ev(1, 330, 1)
            ev(0, 0, 0)
        for slot in (0, 1):
            ev(3, 47, slot)
            ev(3, 57, 4294967295)
        ev(1, 330, 0)
        ev(0, 0, 0)
        script = "\n".join(lines) + "\n"
        self.adb("shell", f"cat > /data/local/tmp/maparound-gesture.sh <<'EOF'\n{script}EOF")
        self.shell("sh", "/data/local/tmp/maparound-gesture.sh")

    def back(self):
        self.shell("input", "keyevent", "KEYCODE_BACK")

    def type(self, text):
        self.shell("input", "text", text.replace(" ", "%s"))

    def clear_field(self, chars=40):
        """Empties the text field being edited."""
        self.shell("input", "keyevent", "KEYCODE_MOVE_END")
        self.shell("input", "keyevent", *(["KEYCODE_DEL"] * chars))

    def screenshot(self):
        """The screen as PNG bytes."""
        return self.adb("exec-out", "screencap", "-p", binary=True)

    def nodes(self):
        """What's on screen, by uiautomator."""
        for _ in range(3):
            out = self.shell("uiautomator", "dump", "/sdcard/maparound-ui.xml", check=False)
            if "dumped" in out:
                break
            time.sleep(1)
        xml = self.adb("exec-out", "cat", "/sdcard/maparound-ui.xml")
        root = ET.fromstring(xml[xml.index("<"):])
        found = []
        for n in root.iter("node"):
            text, desc = n.get("text") or "", n.get("content-desc") or ""
            if not (text or desc):
                continue
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            found.append(Node(text, desc, (x1, y1, x2, y2), n.get("clickable") == "true"))
        return found

    # ---- where the device is ----------------------------------------------------------------------

    def set_location(self, lat, lon, accuracy=5):
        raise NotImplementedError

    def clear_location(self):
        pass

    # Whether the GPS screen shows a real position (blurred in the shots).
    real_gps = False


class Phone(Device):
    """A phone over USB or Wi-Fi debugging. Its position is a test location provider the shell
    sets (the real one is back after); the GPS screen's satellites are the real ones, so its
    position rows are blurred."""
    real_gps = True
    PROVIDERS = ("gps", "network")

    def set_location(self, lat, lon, accuracy=5):
        if not getattr(self, "_mocking", False):
            self.shell("appops", "set", "com.android.shell", "android:mock_location", "allow")
            for p in self.PROVIDERS:
                self.shell("cmd", "location", "providers", "add-test-provider", p, "--supportsAltitude", "--supportsSpeed",
                           "--supportsBearing", "--accuracy", "1", check=False)
                self.shell("cmd", "location", "providers", "set-test-provider-enabled", p, "true")
            self._mocking = True
        for p in self.PROVIDERS:
            self.shell("cmd", "location", "providers", "set-test-provider-location", p,
                       "--location", f"{lat},{lon}", "--accuracy", accuracy)

    def clear_location(self):
        for p in self.PROVIDERS:
            self.shell("cmd", "location", "providers", "remove-test-provider", p, check=False)
        self.shell("appops", "set", "com.android.shell", "android:mock_location", "default", check=False)
        self._mocking = False


class Emulator(Device):
    """The Android emulator (the maparound-shots AVD unless told otherwise), started if it isn't
    running. Its position is the emulator's own GPS."""

    def __init__(self, serial=None, avd="maparound-shots"):
        super().__init__(serial)
        self.avd = avd

    def prepare(self):
        if not self.serial:
            self.serial = self.running() or self.boot()
        # Root, for two-finger gestures (sendevent); the emulator's default images allow it.
        subprocess.run([ADB, "-s", self.serial, "root"], capture_output=True)
        subprocess.run([ADB, "-s", self.serial, "wait-for-device"], capture_output=True)
        super().prepare()

    def running(self):
        out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
        return next((line.split()[0] for line in out.splitlines() if line.startswith("emulator-") and "\tdevice" in line), None)

    def boot(self):
        print(f"starting the emulator {self.avd}…")
        env = dict(os.environ)
        env.setdefault("ANDROID_AVD_HOME", str(Path.home() / ".mapAround-toolchain" / "avd"))
        env.setdefault("ANDROID_SDK_ROOT", str(SDK))
        subprocess.Popen([EMULATOR, "-avd", self.avd, "-no-snapshot-save", "-no-boot-anim"], env=env,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(180):
            time.sleep(2)
            serial = self.running()
            if serial and subprocess.run([ADB, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                                         capture_output=True, text=True).stdout.strip() == "1":
                time.sleep(5)
                return serial
        raise SystemExit("The emulator didn't start.")

    def set_location(self, lat, lon, accuracy=5):
        self.adb("emu", "geo", "fix", lon, lat)


def connect(kind, serial=None, avd=None):
    """The device to use: `phone` (the only one connected, or --serial) or `emulator`."""
    if kind == "emulator":
        return Emulator(serial, avd or "maparound-shots")
    if not serial:
        out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
        phones = [line.split()[0] for line in out.splitlines()[1:] if "\tdevice" in line and not line.startswith("emulator-")]
        if len(phones) != 1:
            raise SystemExit(f"{len(phones)} phones connected: pick one with --serial ({', '.join(phones) or 'none'}).")
        serial = phones[0]
    return Phone(serial)
