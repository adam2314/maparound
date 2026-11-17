"""Packs the world's time zones' outlines into timezones.bin for the data view's time zones.

    python tools/timezones/make_timezones.py combined-with-oceans.json webApp/src/wasmJsMain/resources/timezones.bin

Input: timezone-boundary-builder's "timezones-with-oceans" GeoJSON
(https://github.com/evansiroky/timezone-boundary-builder/releases, data © OpenStreetMap
contributors, ODbL): each feature an IANA zone ("Asia/Jerusalem", "Etc/GMT-2" over the sea).
Plain Python, no libraries.

Outlines are simplified (Douglas–Peucker) to about 2 km; rings smaller than that are left out (a
polygon whose outer ring is, with its holes).

Format (read by TimeZones.decode), as regions.bin (all integers unsigned LEB128 varints unless noted):
    "MATZ"  version (1 byte, = 1)  zone count
    per zone:  IANA name (byte length, UTF-8)  polygon count
               per polygon:  ring count (the outer ring, then its holes)
                   per ring:  point count, points as zigzag deltas of (lat, lon) in 1e-3 degrees,
                              the first from (0, 0)
"""
import json
import sys

TOLERANCE = 0.02  # degrees, about 2 km
SCALE = 1e3


def varint(n, out):
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def zigzag(n):
    return (n << 1) ^ (n >> 63)


def simplify(pts, tol):
    """Douglas–Peucker on (lon, lat) points (a closed ring: its first and last are the same)."""
    if len(pts) < 4:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        if b - a < 2:
            continue
        ax, ay = pts[a]
        bx, by = pts[b]
        dx, dy = bx - ax, by - ay
        length = (dx * dx + dy * dy) ** 0.5
        worst, at = -1.0, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            if length == 0:
                d = ((px - ax) ** 2 + (py - ay) ** 2) ** 0.5
            else:
                d = abs(dx * (ay - py) - (ax - px) * dy) / length
            if d > worst:
                worst, at = d, i
        if worst > tol:
            keep[at] = True
            stack.append((a, at))
            stack.append((at, b))
    return [p for p, k in zip(pts, keep) if k]


def polygons_of(geometry):
    polys = geometry["coordinates"] if geometry["type"] == "MultiPolygon" else [geometry["coordinates"]]
    out = []
    for poly in polys:
        rings = []
        for ring in poly:
            # A closed ring split at its far point, so both halves have ends to keep.
            far = max(range(len(ring)), key=lambda i: (ring[i][0] - ring[0][0]) ** 2 + (ring[i][1] - ring[0][1]) ** 2)
            simple = simplify(ring[:far + 1], TOLERANCE)[:-1] + simplify(ring[far:], TOLERANCE)
            pts = []
            for lon, lat in simple:
                q = (round(lat * SCALE), round(lon * SCALE))
                if not pts or pts[-1] != q:
                    pts.append(q)
            if len(pts) > 1 and pts[0] == pts[-1]:
                pts.pop()
            # Rings smaller than the tolerance (islets, slivers) are left out.
            lats = [p[0] for p in pts]
            lons = [p[1] for p in pts]
            big = len(pts) >= 3 and (max(lats) - min(lats) > TOLERANCE * SCALE or max(lons) - min(lons) > TOLERANCE * SCALE)
            if big:
                rings.append(pts)
            elif not rings:
                break  # the outer ring: the polygon goes, holes and all
        if rings:
            out.append(rings)
    return out


def main(src, dst):
    features = json.load(open(src, encoding="utf-8"))["features"]
    out = bytearray(b"MATZ")
    out.append(1)
    packed = []
    for f in features:
        name = f["properties"]["tzid"]
        polygons = polygons_of(f["geometry"])
        if polygons:
            packed.append((name, polygons))
    packed.sort()
    varint(len(packed), out)
    points = 0
    for name, polygons in packed:
        b = name.encode("utf-8")
        varint(len(b), out)
        out += b
        varint(len(polygons), out)
        for rings in polygons:
            varint(len(rings), out)
            for pts in rings:
                varint(len(pts), out)
                plat = plon = 0
                for lat, lon in pts:
                    varint(zigzag(lat - plat), out)
                    varint(zigzag(lon - plon), out)
                    plat, plon = lat, lon
                points += len(pts)
    open(dst, "wb").write(out)
    print(f"{len(packed)} zones, {points} points, {len(out)} bytes -> {dst}")


if __name__ == "__main__":
    main(*sys.argv[1:3])
