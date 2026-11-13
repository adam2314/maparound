"""Packs the outlines of the regions MapAround's offline maps are made for into app/src/main/assets/regions.bin.

    python tools/regions/make_regions.py index-v1.json regions.txt app/src/main/assets/regions.bin

Inputs: Geofabrik's index of download regions with their outlines
(https://download.geofabrik.de/index-v1.json; the outlines are Geofabrik's, from OpenStreetMap data)
and the list of regions MapAround builds (regions.txt in github.com/adam2314/my-pipeline: the same
paths as Geofabrik's and the mapsforge.org maps'). Needs shapely (pip install shapely).

The map screen tells from them which map is made for what it shows, and offers it where no
installed map reaches (map/MapRegions.kt). Outlines are simplified to about a kilometre.

Format (read by MapRegions.decode), all integers unsigned LEB128 varints unless noted:
    "MARG"  version (1 byte, = 1)  region count
    per region:  path (byte length, UTF-8, e.g. "europe/germany/bayern")  ring count
                 per ring (outer rings and holes alike: inside is inside an odd number of them):
                     point count, points as zigzag deltas of (lat, lon) in 1e-3 degrees, the first from (0, 0)
"""
import json
import sys

from shapely.geometry import shape

TOLERANCE = 0.01  # degrees, about a kilometre
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


def rings_of(geometry):
    """Every ring (outer and holes) of the simplified outline, as (lat, lon) in 1e-3 degrees."""
    g = shape(geometry).simplify(TOLERANCE, preserve_topology=True)
    polys = [g] if g.geom_type == "Polygon" else [p for p in getattr(g, "geoms", []) if p.geom_type == "Polygon"]
    out = []
    for p in polys:
        for ring in [p.exterior, *p.interiors]:
            pts = []
            for lon, lat in ring.coords:
                q = (round(lat * SCALE), round(lon * SCALE))
                if not pts or pts[-1] != q:
                    pts.append(q)
            if len(pts) > 1 and pts[0] == pts[-1]:
                pts.pop()
            if len(pts) >= 3:
                out.append(pts)
    return out


def main(index_path, regions_path, dst):
    regions = [l.strip() for l in open(regions_path, encoding="utf-8") if l.strip() and not l.startswith("#")]
    by_path = {}
    for f in json.load(open(index_path, encoding="utf-8"))["features"]:
        url = f["properties"].get("urls", {}).get("pbf", "")
        by_path[url.replace("https://download.geofabrik.de/", "").replace("-latest.osm.pbf", "")] = f
    out = bytearray(b"MARG")
    out.append(1)
    packed = []
    for r in regions:
        f = by_path.get(r)
        if not f or not f.get("geometry"):
            print("no outline:", r, file=sys.stderr)
            continue
        rings = rings_of(f["geometry"])
        if rings:
            packed.append((r, rings))
    varint(len(packed), out)
    points = 0
    for path, rings in packed:
        name = path.encode("utf-8")
        varint(len(name), out)
        out += name
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
    print(f"{len(packed)} regions, {points} points, {len(out)} bytes -> {dst}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
