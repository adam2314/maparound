"""Packs Natural Earth's world layers into app/src/main/assets/world.bin.

    python tools/world/make_world.py <folder with the GeoJSON files> app/src/main/assets/world.bin

Inputs (public domain, e.g. from https://github.com/nvkelso/natural-earth-vector/tree/master/geojson):
    ne_10m_land, ne_10m_minor_islands         land
    ne_10m_lakes                              lakes and reservoirs
    ne_10m_rivers_lake_centerlines            rivers (the lake centre lines are left out)
    ne_10m_populated_places_simple            cities and towns
    ne_50m_admin_0_countries                  country names (their label points)
    ne_10m_geography_marine_polys             ocean, sea and bay names

The app draws them where its offline maps have nothing: at the overview zooms and outside the
installed country maps (map/WorldData.kt).

Format (read by WorldData.decode). Integers are unsigned LEB128 varints, coordinates zigzag varints
in 1e-4 degrees; each ring or line starts from (0, 0) and goes on in deltas:
    "MAWD"  version (1 byte, = 1)
    land:   count, then per polygon: min zoom (byte), ring count, rings (point count, points)
    lakes:  the same
    rivers: count, then per line: min zoom (byte), name, point count, points
    labels: count, then per label: kind (byte), min zoom (byte), name, population, lat, lon
Strings are a varint byte length and UTF-8. Rings are closed implicitly (the last point isn't
repeated). Label kinds: 0 country, 1 capital, 2 city, 3 town, 4 ocean, 5 sea or bay, 6 lake.
"""
import heapq
import json
import math
import os
import sys

SCALE = 1e4


def load(folder, name):
    with open(os.path.join(folder, name + ".geojson"), encoding="utf-8") as f:
        return json.load(f)["features"]


def polygons(geom):
    if geom is None:
        return []
    if geom["type"] == "Polygon":
        return [geom["coordinates"]]
    if geom["type"] == "MultiPolygon":
        return geom["coordinates"]
    return []


def lines(geom):
    if geom is None:
        return []
    if geom["type"] == "LineString":
        return [geom["coordinates"]]
    if geom["type"] == "MultiLineString":
        return geom["coordinates"]
    return []


def quantise(coords, closed):
    pts = []
    for lon, lat, *_ in coords:
        p = (round(lat * SCALE), round(lon * SCALE))
        if not pts or pts[-1] != p:
            pts.append(p)
    if closed and len(pts) > 1 and pts[0] == pts[-1]:
        pts.pop()
    return pts


def min_zoom(props, *keys, default=0.0):
    for k in keys:
        v = props.get(k)
        if v is not None:
            return max(0, min(20, round(float(v))))
    return round(default)


def name_of(props, *keys):
    for k in keys:
        v = props.get(k)
        if v and str(v).strip():
            return str(v).strip()
    return ""


# ---- labels inside polygons (Mapbox's polylabel) ------------------------------------------------

def _seg_dist_sq(px, py, ax, ay, bx, by):
    x, y, dx, dy = ax, ay, bx - ax, by - ay
    if dx or dy:
        t = ((px - x) * dx + (py - y) * dy) / (dx * dx + dy * dy)
        if t > 1:
            x, y = bx, by
        elif t > 0:
            x, y = x + dx * t, y + dy * t
    dx, dy = px - x, py - y
    return dx * dx + dy * dy


def _point_to_polygon(x, y, poly):
    inside = False
    best = math.inf
    for ring in poly:
        n = len(ring)
        j = n - 1
        for i in range(n):
            ax, ay = ring[i][0], ring[i][1]
            bx, by = ring[j][0], ring[j][1]
            if (ay > y) != (by > y) and x < (bx - ax) * (y - ay) / (by - ay) + ax:
                inside = not inside
            best = min(best, _seg_dist_sq(x, y, ax, ay, bx, by))
            j = i
    return (1 if inside else -1) * math.sqrt(best)


def polylabel(poly, precision=0.05):
    """The point inside the polygon farthest from its edges (lon, lat)."""
    outer = poly[0]
    xs = [p[0] for p in outer]
    ys = [p[1] for p in outer]
    minx, miny, maxx, maxy = min(xs), min(ys), max(xs), max(ys)
    size = min(maxx - minx, maxy - miny)
    if size == 0:
        return outer[0][0], outer[0][1]
    h = size / 2
    queue = []

    def push(x, y, h):
        d = _point_to_polygon(x, y, poly)
        heapq.heappush(queue, (-(d + h * math.sqrt(2)), d, x, y, h))

    x = minx
    while x < maxx:
        y = miny
        while y < maxy:
            push(x + h, y + h, h)
            y += size
        x += size
    cx, cy = sum(xs) / len(xs), sum(ys) / len(ys)
    best = (_point_to_polygon(cx, cy, poly), cx, cy)
    while queue:
        _, d, x, y, h = heapq.heappop(queue)
        if d > best[0]:
            best = (d, x, y)
        if d + h * math.sqrt(2) - best[0] <= precision:
            continue
        h /= 2
        for dx in (-h, h):
            for dy in (-h, h):
                push(x + dx, y + dy, h)
    return best[1], best[2]


# ---- joining line parts --------------------------------------------------------------------------

def chain(parts):
    """Joins polylines that share end points into longer ones (either direction)."""
    at = {}
    for i, p in enumerate(parts):
        at.setdefault(p[0], []).append(i)
        at.setdefault(p[-1], []).append(i)
    used = [False] * len(parts)
    out = []
    for i in range(len(parts)):
        if used[i]:
            continue
        used[i] = True
        line = list(parts[i])
        for _ in range(2):
            while True:
                nxt = next((j for j in at[line[-1]] if not used[j]), None)
                if nxt is None:
                    break
                used[nxt] = True
                p = parts[nxt]
                line.extend(p[1:] if p[0] == line[-1] else reversed(p[:-1]))
            line.reverse()
        out.append(line)
    return out


# ---- writing -------------------------------------------------------------------------------------

class Out:
    def __init__(self):
        self.b = bytearray()

    def byte(self, v):
        self.b.append(v & 0xFF)

    def varint(self, n):
        while True:
            c = n & 0x7F
            n >>= 7
            if n:
                self.b.append(c | 0x80)
            else:
                self.b.append(c)
                return

    def zigzag(self, n):
        self.varint((n << 1) ^ (n >> 63))

    def text(self, s):
        data = s.encode("utf-8")
        self.varint(len(data))
        self.b.extend(data)

    def points(self, pts):
        self.varint(len(pts))
        plat = plon = 0
        for lat, lon in pts:
            self.zigzag(lat - plat)
            self.zigzag(lon - plon)
            plat, plon = lat, lon


def polygon_layer(out, polys):
    out.varint(len(polys))
    for mz, rings in polys:
        out.byte(mz)
        out.varint(len(rings))
        for r in rings:
            out.points(r)


def main(folder, dst):
    stats = {}

    # Land: the continents and big islands, and the minor islands.
    land = []
    for name in ("ne_10m_land", "ne_10m_minor_islands"):
        for f in load(folder, name):
            mz = min_zoom(f["properties"], "min_zoom")
            for poly in polygons(f["geometry"]):
                rings = [r for r in (quantise(r, True) for r in poly) if len(r) >= 3]
                if rings and len(rings[0]) >= 3:
                    land.append((mz, rings))
    stats["land"] = (len(land), sum(len(r) for _, rs in land for r in rs))

    # Lakes, and a label point for the named ones.
    lakes = []
    labels = []
    for f in load(folder, "ne_10m_lakes"):
        p = f["properties"]
        mz = min_zoom(p, "min_zoom")
        polys = polygons(f["geometry"])
        for poly in polys:
            rings = [r for r in (quantise(r, True) for r in poly) if len(r) >= 3]
            if rings and len(rings[0]) >= 3:
                lakes.append((mz, rings))
        nm = name_of(p, "name_en", "name")
        if nm and polys:
            biggest = max(polys, key=lambda poly: len(poly[0]))
            lon, lat = polylabel(biggest, 0.01)
            labels.append((6, min_zoom(p, "min_label", "min_zoom", default=mz + 1), nm, 0, lat, lon))
    stats["lakes"] = (len(lakes), sum(len(r) for _, rs in lakes for r in rs))

    # Rivers: joined into long lines per river; the centre lines through lakes are left out.
    groups = {}
    for f in load(folder, "ne_10m_rivers_lake_centerlines"):
        p = f["properties"]
        if p.get("featurecla") != "River":
            continue
        key = (min_zoom(p, "min_zoom"), name_of(p, "name_en", "name"))
        for part in lines(f["geometry"]):
            q = quantise(part, False)
            if len(q) >= 2:
                groups.setdefault(key, []).append(q)
    rivers = [(mz, nm, line) for (mz, nm), parts in groups.items() for line in chain(parts)]
    stats["rivers"] = (len(rivers), sum(len(l) for _, _, l in rivers))

    # Cities and towns.
    for f in load(folder, "ne_10m_populated_places_simple"):
        p = f["properties"]
        cls = p.get("featurecla") or ""
        if "capital" not in cls.lower() and cls.lower() != "populated place":
            continue
        pop = int(p.get("pop_max") or 0)
        kind = 1 if cls.startswith("Admin-0 capital") else 2 if pop >= 100_000 else 3
        lon, lat = f["geometry"]["coordinates"][:2]
        labels.append((kind, min_zoom(p, "min_zoom"), name_of(p, "name", "nameascii"), max(pop, 0), lat, lon))

    # Countries, at their label points.
    for f in load(folder, "ne_50m_admin_0_countries"):
        p = f["properties"]
        nm = name_of(p, "NAME_EN", "NAME")
        if not nm or p.get("LABEL_X") is None:
            continue
        labels.append((0, min_zoom(p, "MIN_LABEL", default=3), nm, int(p.get("POP_EST") or 0), p["LABEL_Y"], p["LABEL_X"]))

    # Oceans, seas, gulfs and bays.
    marine_kinds = {"ocean": 4, "sea": 5, "gulf": 5, "bay": 5, "strait": 5, "sound": 5, "channel": 5, "fjord": 5, "inlet": 5, "lagoon": 5}
    for f in load(folder, "ne_10m_geography_marine_polys"):
        p = f["properties"]
        kind = marine_kinds.get((p.get("featurecla") or "").lower())
        nm = name_of(p, "name_en", "name")
        polys = polygons(f["geometry"])
        if kind is None or not nm or not polys:
            continue
        nm = nm.title() if nm.isupper() else nm
        biggest = max(polys, key=lambda poly: len(poly[0]))
        lon, lat = polylabel(biggest, 0.05)
        labels.append((kind, min_zoom(p, "min_label", default=(p.get("scalerank") or 3) + 1), nm, 0, lat, lon))
    stats["labels"] = len(labels)

    out = Out()
    out.b.extend(b"MAWD")
    out.byte(1)
    polygon_layer(out, land)
    polygon_layer(out, lakes)
    out.varint(len(rivers))
    for mz, nm, line in rivers:
        out.byte(mz)
        out.text(nm)
        out.points(line)
    out.varint(len(labels))
    for kind, mz, nm, pop, lat, lon in labels:
        out.byte(kind)
        out.byte(mz)
        out.text(nm)
        out.varint(pop)
        out.zigzag(round(lat * SCALE))
        out.zigzag(round(lon * SCALE))
    with open(dst, "wb") as f:
        f.write(out.b)
    print(f"{stats}, {len(out.b)} bytes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
