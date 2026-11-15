"""Finding and tapping things on screen by what they say (their text or TalkBack description), so
the steps read like what a person does and survive layout changes."""
import time


class NotFound(Exception):
    pass


class Ui:
    def __init__(self, device):
        self.d = device

    def find(self, label, n=0, nodes=None):
        """The n-th node whose text or description is [label]; else the n-th that contains it."""
        nodes = nodes if nodes is not None else self.d.nodes()
        want = label.lower()
        exact = [x for x in nodes if want in (x.text.lower(), x.desc.lower())]
        hits = exact or [x for x in nodes if want in x.label.lower()]
        return hits[n] if len(hits) > n else None

    def has(self, label):
        return self.find(label) is not None

    def wait(self, label, timeout=20, n=0):
        end = time.time() + timeout
        while True:
            node = self.find(label, n)
            if node:
                return node
            if time.time() > end:
                raise NotFound(f"nothing on screen says {label!r}")
            time.sleep(0.7)

    def wait_gone(self, label, timeout=60):
        end = time.time() + timeout
        while self.has(label):
            if time.time() > end:
                raise NotFound(f"{label!r} is still on screen")
            time.sleep(1)

    def tap(self, label, n=0, timeout=20):
        node = self.wait(label, timeout, n)
        self.d.tap(*node.centre)
        time.sleep(0.8)
        return node

    def tap_any(self, *labels, timeout=20):
        """The first of [labels] on screen (a button named one way or another)."""
        end = time.time() + timeout
        while True:
            nodes = self.d.nodes()
            for label in labels:
                node = self.find(label, nodes=nodes)
                if node:
                    self.d.tap(*node.centre)
                    time.sleep(0.8)
                    return node
            if time.time() > end:
                raise NotFound(f"none of {labels} on screen")
            time.sleep(0.7)

    def tap_near(self, label, anchor, timeout=20):
        """The [label] (a "Change" button…) on the same row as [anchor], of several on screen."""
        a = self.wait(anchor, timeout)
        ay = a.centre[1]
        nodes = [x for x in self.d.nodes() if label.lower() in (x.text.lower(), x.desc.lower())]
        if not nodes:
            raise NotFound(f"nothing on screen says {label!r}")
        node = min(nodes, key=lambda x: abs(x.centre[1] - ay))
        self.d.tap(*node.centre)
        time.sleep(0.8)
        return node

    def scroll_to(self, label, tries=8):
        """Scrolls the list down until [label] shows."""
        w, h = self.d.size()
        for _ in range(tries):
            node = self.find(label)
            if node:
                return node
            self.d.swipe(w // 2, int(h * 0.75), w // 2, int(h * 0.35), 400)
            time.sleep(0.8)
        raise NotFound(f"scrolled, but nothing says {label!r}")

    def bring(self, label, tries=10):
        """[label] onto the screen, scrolling down the list and, if it isn't there, back up."""
        w, h = self.d.size()
        for direction in (1, -1):
            for _ in range(tries):
                node = self.find(label)
                if node:
                    return node
                a, b = int(h * 0.7), int(h * 0.35)
                self.d.swipe(w // 2, a if direction > 0 else b, w // 2, b if direction > 0 else a, 500)
                time.sleep(0.8)
        raise NotFound(f"scrolled both ways, but nothing says {label!r}")

    def scroll_until_top(self, label, top, tries=8):
        """Scrolls until [label] is near y = [top] (pixels), in short checked steps (a fling overshoots)."""
        w, h = self.d.size()
        self.bring(label)
        for _ in range(tries):
            node = self.find(label)
            if node is None:
                node = self.bring(label)
            y = node.bounds[1]
            if abs(y - top) < 40:
                return node
            step = max(-500, min(500, y - top))
            start = int(h * 0.6)
            self.d.swipe(w // 2, start, w // 2, start - step, 600)
            time.sleep(1.2)
        return self.find(label)

    def rows(self, *labels):
        """The screen rows (full width) of the nodes that say [labels], and the values beside them."""
        nodes = self.d.nodes()
        w, _ = self.d.size()
        out = []
        for label in labels:
            for x in nodes:
                if label.lower() in x.label.lower():
                    _, y1, _, y2 = x.bounds
                    out.append((0, y1 - 6, w, y2 + 6))
        return out
