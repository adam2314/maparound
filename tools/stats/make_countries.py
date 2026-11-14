"""The countries' outlines for the web app's data view: webApp/src/wasmJsMain/resources/countries.json

    python tools/stats/make_countries.py ne_50m_admin_0_countries.geojson webApp/src/wasmJsMain/resources/countries.json

Input: Natural Earth's 1:50m "Admin 0 - Countries" (public domain), e.g. from
https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_50m_admin_0_countries.geojson

Output: {"<ISO 3166 alpha-3>": {"en": name, "he": Hebrew name, "g": GeoJSON geometry}, ...}, the
outlines simplified to about 1.5 km and rounded to 0.01° (the countries show below zoom 7), so Our World in Data's figures (by the same codes)
colour them.
"""
import json
import sys


def code(p):
    for k in ("ISO_A3_EH", "ISO_A3", "ADM0_A3"):
        v = p.get(k) or ""
        if len(v) == 3 and v != "-99":
            return v
    return None


TOLERANCE = 0.015  # degrees (about 1.5 km): enough for the zooms the countries show at


def simplify(pts, tol):
    """Douglas-Peucker: the points that keep the line within tol of its shape."""
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        (ax, ay), (bx, by) = pts[a], pts[b]
        dx, dy = bx - ax, by - ay
        n = (dx * dx + dy * dy) ** 0.5
        best, at = 0.0, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            d = abs(dy * px - dx * py + bx * ay - by * ax) / n if n else ((px - ax) ** 2 + (py - ay) ** 2) ** 0.5
            if d > best:
                best, at = d, i
        if best > tol:
            keep[at] = True
            stack += [(a, at), (at, b)]
    return [p for p, k in zip(pts, keep) if k]


def ring(coords):
    pts = [[lon, lat] for lon, lat, *_ in coords]
    if len(pts) > 1 and pts[0] == pts[-1]:
        pts.pop()
    if len(pts) < 3:
        return None
    # Simplified in two halves, so the ring's ends stay.
    half = len(pts) // 2
    pts = simplify(pts[:half + 1], TOLERANCE)[:-1] + simplify(pts[half:] + [pts[0]], TOLERANCE)[:-1]
    out = []
    for lon, lat in pts:
        pt = [round(lon, 2), round(lat, 2)]
        if not out or out[-1] != pt:
            out.append(pt)
    if len(out) < 3:
        return None
    return out + [out[0]]


def geometry(g):
    polys = [g["coordinates"]] if g["type"] == "Polygon" else g["coordinates"]
    out = []
    for poly in polys:
        rings = [r for r in (ring(r) for r in poly) if r]
        if rings:
            out.append(rings)
    if not out:
        return None
    return {"type": "Polygon", "coordinates": out[0]} if len(out) == 1 else {"type": "MultiPolygon", "coordinates": out}


def main(src, dst):
    with open(src, encoding="utf-8") as f:
        features = json.load(f)["features"]
    out = {}
    for feat in features:
        p = feat["properties"]
        iso = code(p)
        g = geometry(feat["geometry"]) if feat.get("geometry") else None
        if not iso or not g:
            continue
        if iso in out:  # parts listed apart: one shape
            a = out[iso]["g"]
            polys = (a["coordinates"] if a["type"] == "MultiPolygon" else [a["coordinates"]]) + \
                (g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]])
            out[iso]["g"] = {"type": "MultiPolygon", "coordinates": polys}
            continue
        out[iso] = {"en": p.get("NAME_EN") or p.get("NAME") or iso, "he": p.get("NAME_HE") or "", "g": g}
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(out)} countries")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
