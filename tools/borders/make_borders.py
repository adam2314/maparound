"""Packs Natural Earth's country borders into app/src/main/assets/borders.bin.

    python tools/borders/make_borders.py ne_10m_admin_0_boundary_lines_land.geojson app/src/main/assets/borders.bin

Input: the 1:10m "Admin 0 - Boundary Lines" land borders (public domain), e.g. from
https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_10m_admin_0_boundary_lines_land.geojson

The app draws them where its offline maps have no borders: at the overview zooms (the world
overview map has none) and outside the installed country maps (map/BorderLines.kt).

Format (read by BorderLines.decode), all integers unsigned LEB128 varints unless noted:
    "MABL"  version (1 byte, = 1)  line count
    per line:  kind (1 byte: 0 border, 1 contested)  point count
               points as zigzag deltas of (lat, lon) in 1e-5 degrees, the first from (0, 0)
"""
import json
import sys

BORDER = {"International boundary (verify)"}
# Drawn as contested (dashed) lines. Lease limits (Guantánamo, Baikonur) and the internal
# "overlay limits" aren't borders between countries and are left out.
CONTESTED = {
    "Disputed (please verify)",
    "Indefinite (please verify)",
    "Line of control (please verify)",
    "Indeterminant frontier",
    "Unrecognized",
}
SCALE = 1e5


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
        for _ in range(2):  # extend the end, then (reversed) the start
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


def main(src, dst):
    with open(src, encoding="utf-8") as f:
        features = json.load(f)["features"]
    lines = []
    skipped = {}
    for feat in features:
        cls = feat["properties"].get("FEATURECLA")
        if cls in BORDER:
            kind = 0
        elif cls in CONTESTED:
            kind = 1
        else:
            skipped[cls] = skipped.get(cls, 0) + 1
            continue
        geom = feat["geometry"]
        parts = geom["coordinates"] if geom["type"] == "MultiLineString" else [geom["coordinates"]]
        for part in parts:
            pts = []
            for lon, lat, *_ in part:
                p = (round(lat * SCALE), round(lon * SCALE))
                if not pts or pts[-1] != p:
                    pts.append(p)
            if len(pts) >= 2:
                lines.append((kind, pts))
    # Natural Earth splits borders into thousands of short parts in no order: join the ones that
    # continue each other, so dashes run on and fewer lines are drawn.
    lines = [(k, pts) for k in (0, 1) for pts in chain([p for kk, p in lines if kk == k])]

    out = bytearray(b"MABL")
    out.append(1)
    varint(len(lines), out)
    for kind, pts in lines:
        out.append(kind)
        varint(len(pts), out)
        plat = plon = 0
        for lat, lon in pts:
            varint(zigzag(lat - plat), out)
            varint(zigzag(lon - plon), out)
            plat, plon = lat, lon
    with open(dst, "wb") as f:
        f.write(out)
    n = sum(len(p) for _, p in lines)
    contested = sum(1 for k, _ in lines if k == 1)
    print(f"{len(lines)} lines ({contested} contested), {n} points, {len(out)} bytes; left out: {skipped}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
