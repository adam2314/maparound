"""The countries for the web app's data view: webApp/src/wasmJsMain/resources/countries.json

    python tools/stats/make_countries.py ne_50m_admin_0_countries.geojson webApp/src/wasmJsMain/resources/countries.json

Input: Natural Earth's 1:50m "Admin 0 - Countries" (public domain), e.g. from
https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_50m_admin_0_countries.geojson
and, asked from Wikidata's query service (CC0) by each country's Wikidata id (Natural Earth's
WIKIDATAID), its area, capital and official languages, with their English and Hebrew names, and its
Wikipedia articles' titles (English, Hebrew). --no-wikidata leaves those out (offline).

    --flags webApp/src/wasmJsMain/resources/flags

also writes each country's flag (Wikidata's, from Wikimedia Commons, as Commons renders it: a PNG
160 pixels wide) as <ISO>.png, those under a free licence only, with LICENCES.md naming each one's
file, author and licence.

Output: {"<ISO 3166 alpha-3>": {
    "en": name, "he": Hebrew name, "g": GeoJSON geometry,
    "lp": [lon, lat] where its name goes on the map, "lz": the zoom it's named from (Natural Earth's),
    "km2": area, "cap": [[English, Hebrew], ...], "lang": [[English, Hebrew], ...],
    "wp": [English Wikipedia title, Hebrew Wikipedia title ("" for none)],
    "fl": [width, height] of its flag's picture (with --flags)   (each when known)
}, ...}, the outlines simplified to about 1.5 km and rounded to 0.01° (the countries show below
zoom 7), so Our World in Data's figures (by the same codes) colour them.
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request


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


# Natural Earth's names where a shorter or a fairer one reads better on a map: (English, Hebrew),
# None keeping its own.
NAMES = {
    "CHN": ("China", "סין"),
    "USA": ("United States", None),
    "CYN": ("Northern Cyprus", "צפון קפריסין"),
    "PSE": (None, "פלסטין"),  # Natural Earth has "ארץ ישראל", the land rather than the state
}

# Where Natural Earth's Wikidata id is the land rather than the country.
QIDS = {"PSE": "Q219060"}  # the State of Palestine (Natural Earth: Q23792, the region)

# Where Wikidata leaves a gap or has something odd, checked by hand.
FACTS = {
    # English's statement is deprecated on Wikidata (an executive order, 2025, not a law), and the
    # others apply to a state or territory; English is the language there.
    "USA": {"lang": [["English", "אנגלית"]]},
    "URY": {"lang": [["Spanish", "ספרדית"]]},  # none official in law
    "CHN": {"lang": [["Mandarin Chinese", "סינית מנדרינית"]]},  # Wikidata: "Putonghua"
    "ATG": {"cap": [["St. John's", "סנט ג'ונס"]], "lang": [["English", "אנגלית"]]},  # a capital without a name; Spanish
    "PSE": {"cap": [["Ramallah", "רמאללה"]]},  # the seat of its government
}

WIKIDATA = "https://query.wikidata.org/sparql"

QUERY = """
SELECT ?c ?kind ?v ?rank ?part ?en ?he ?speakers WHERE {
  VALUES ?c { %s }
  {
    BIND("area" AS ?kind)
    ?c p:P2046 ?s . ?s psn:P2046/wikibase:quantityAmount ?v ; wikibase:rank ?rank .
    OPTIONAL { ?s pq:P518 ?part }
  } UNION {
    BIND("cap" AS ?kind)
    ?c p:P36 ?s . ?s ps:P36 ?v ; wikibase:rank ?rank .
    FILTER NOT EXISTS { ?s pq:P582 ?end }
  } UNION {
    BIND("lang" AS ?kind)
    ?c p:P37 ?s . ?s ps:P37 ?v ; wikibase:rank ?rank .
    FILTER NOT EXISTS { ?s pq:P582 ?end }
    OPTIONAL { ?s pq:P518 ?part }
    OPTIONAL { ?v wdt:P1098 ?speakers }
  } UNION {
    BIND("flag" AS ?kind)
    ?c p:P41 ?s . ?s ps:P41 ?v ; wikibase:rank ?rank .
    FILTER NOT EXISTS { ?s pq:P582 ?end }
  } UNION {
    BIND("wp-en" AS ?kind) BIND(wikibase:NormalRank AS ?rank)
    ?v schema:about ?c ; schema:isPartOf <https://en.wikipedia.org/> ; schema:name ?en .
  } UNION {
    BIND("wp-he" AS ?kind) BIND(wikibase:NormalRank AS ?rank)
    ?v schema:about ?c ; schema:isPartOf <https://he.wikipedia.org/> ; schema:name ?he .
  }
  FILTER(?rank != wikibase:DeprecatedRank)
  OPTIONAL { ?v rdfs:label ?en FILTER(LANG(?en) = "en") }
  OPTIONAL { ?v rdfs:label ?he FILTER(LANG(?he) = "he") }
}
"""


def ask_wikidata(qids):
    """{qid: [(kind, value, preferred, part, en, he, speakers), ...]} from Wikidata's query service."""
    out = {}
    qids = sorted(set(qids))
    for i in range(0, len(qids), 60):
        batch = qids[i:i + 60]
        body = urllib.parse.urlencode({"query": QUERY % " ".join("wd:" + q for q in batch), "format": "json"}).encode()
        req = urllib.request.Request(WIKIDATA, data=body, headers={
            "User-Agent": "make_countries.py (Natural Earth + Wikidata country facts)",
            "Accept": "application/sparql-results+json",
        })
        for attempt in range(4):
            try:
                with urllib.request.urlopen(req, timeout=120) as r:
                    rows = json.load(r)["results"]["bindings"]
                break
            except Exception as e:  # busy: wait and try again
                if attempt == 3:
                    raise
                print(f"Wikidata: {e}, again", file=sys.stderr)
                time.sleep(10 * (attempt + 1))
        for b in rows:
            g = lambda k: b[k]["value"] if k in b else None
            qid = g("c").rsplit("/", 1)[-1]
            out.setdefault(qid, []).append((
                g("kind"), g("v"), g("rank").endswith("PreferredRank"), g("part"), g("en"), g("he"),
                float(g("speakers") or 0),
            ))
        time.sleep(1)
    return out


def best(rows):
    """The preferred statements when there are any, else all of them."""
    preferred = [r for r in rows if r[2]]
    return preferred or rows


def facts(rows):
    """
    Area (km²), capitals and official languages from a country's Wikidata rows: the capitals by
    name, the languages most spoken first (a country can have twenty); those of the whole country,
    else those of its parts (Belgium's are each a region's).
    """
    f = {}
    areas = best([r for r in rows if r[0] == "area" and not r[3]] or [r for r in rows if r[0] == "area"])
    if areas:
        # Several sources' figures: the largest (with its waters, which countries count).
        km2 = max(float(r[1]) for r in areas) / 1e6
        f["km2"] = round(km2, 2) if km2 < 10 else round(km2)
    for kind in ("cap", "lang"):
        names, speakers = {}, {}
        of_kind = [r for r in rows if r[0] == kind]
        for r in best([r for r in of_kind if not r[3]] or of_kind):
            if not r[4]:
                continue
            en = r[4][:-len(" language")] if r[4].endswith(" language") else r[4]  # "Kurdish language"
            names[r[1]] = [en, r[5] or en]
            speakers[r[1]] = max(speakers.get(r[1], 0), r[6])
        order = sorted(names, key=lambda v: (-speakers[v], names[v][0]) if kind == "lang" else names[v][0])
        if order:
            f[kind] = [names[v] for v in order]
    en = next((r[4] for r in rows if r[0] == "wp-en"), None)
    he = next((r[5] for r in rows if r[0] == "wp-he"), None)
    if en or he:
        f["wp"] = [en or "", he or ""]
    # The flag's file on Commons (kept aside: what --flags downloads).
    flags = sorted({r[1] for r in best([r for r in rows if r[0] == "flag"])})
    if flags:
        f["_flag"] = urllib.parse.unquote(flags[0].rsplit("/", 1)[-1])
    return f


COMMONS = "https://commons.wikimedia.org/w/api.php"
AGENT = "make_countries.py (Natural Earth + Wikidata country facts)"

# Licences a flag can be shipped under (with credit where they ask for it).
FREE = ("public domain", "pd", "cc0", "cc by", "cc-by")


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": AGENT})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except Exception as e:  # busy: wait and try again
            if attempt == 3:
                raise
            print(f"Commons: {e}, again", file=sys.stderr)
            time.sleep(5 * (attempt + 1))


def plain(html):
    """Commons' metadata as text (it comes as HTML)."""
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", html or "")).strip()


def write_flags(out, files, folder, width=160):
    """Each country's flag ([files]: its Commons file by ISO code) as folder/<ISO>.png, and LICENCES.md."""
    os.makedirs(folder, exist_ok=True)
    info = {}
    names = sorted(set(files.values()))
    for i in range(0, len(names), 40):
        batch = names[i:i + 40]
        q = urllib.parse.urlencode({
            "action": "query", "format": "json", "prop": "imageinfo", "iiprop": "url|extmetadata",
            "iiurlwidth": str(width), "iiextmetadatafilter": "LicenseShortName|Artist",
            "titles": "|".join("File:" + n for n in batch),
        })
        data = json.loads(fetch(COMMONS + "?" + q))
        back = {n["to"]: n["from"] for n in data["query"].get("normalized", [])}
        for page in data["query"]["pages"].values():
            ii = (page.get("imageinfo") or [None])[0]
            if not ii:
                continue
            meta = ii.get("extmetadata", {})
            info[back.get(page["title"], page["title"])[len("File:"):]] = {
                "thumb": ii.get("thumburl"), "w": ii.get("thumbwidth"), "h": ii.get("thumbheight"),
                "page": ii.get("descriptionurl"),
                "licence": plain(meta.get("LicenseShortName", {}).get("value")),
                "author": plain(meta.get("Artist", {}).get("value")),
            }
        time.sleep(1)
    rows, left_out = [], []
    for iso in sorted(files):
        f = info.get(files[iso])
        if not f or not f["thumb"]:
            left_out.append(f"{iso} (no picture)")
            continue
        if not f["licence"].lower().startswith(FREE):
            left_out.append(f"{iso} ({f['licence'] or 'no licence'})")
            continue
        with open(os.path.join(folder, iso + ".png"), "wb") as fh:
            fh.write(fetch(f["thumb"]))
        out[iso]["fl"] = [f["w"], f["h"]]
        rows.append(f"| {iso} | [{files[iso]}]({f['page']}) | {f['author'] or '—'} | {f['licence']} |")
        time.sleep(0.2)
    with open(os.path.join(folder, "LICENCES.md"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(
            "# The countries' flags\n\n"
            "From Wikimedia Commons (each country's flag on Wikidata), as Commons renders it, "
            f"{width} pixels wide; written by tools/stats/make_countries.py.\n\n"
            "| Country | File | Author | Licence |\n|---|---|---|---|\n" + "\n".join(rows) + "\n"
        )
    print(f"{len(rows)} flags; left out: {', '.join(left_out) or 'none'}")


def main(src, dst, wikidata=True, flags=None):
    with open(src, encoding="utf-8") as f:
        features = json.load(f)["features"]
    out = {}
    main_part = {}  # the part a country is named by: Natural Earth's most important (lowest MIN_LABEL)
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
        else:
            out[iso] = {"en": p.get("NAME_EN") or p.get("NAME") or iso, "he": p.get("NAME_HE") or "", "g": g}
        if iso not in main_part or (p.get("MIN_LABEL") or 99) < (main_part[iso].get("MIN_LABEL") or 99):
            main_part[iso] = p
    for iso, p in main_part.items():
        o = out[iso]
        o["en"], o["he"] = p.get("NAME_EN") or p.get("NAME") or iso, p.get("NAME_HE") or ""
        en, he = NAMES.get(iso, (None, None))
        o["en"], o["he"] = en or o["en"], he or o["he"]
        if p.get("LABEL_X") is not None and p.get("LABEL_Y") is not None:
            o["lp"] = [round(p["LABEL_X"], 2), round(p["LABEL_Y"], 2)]
            o["lz"] = round(float(p.get("MIN_LABEL") or 5), 1)
    if wikidata:
        qids = {iso: QIDS.get(iso) or p["WIKIDATAID"] for iso, p in main_part.items() if QIDS.get(iso) or p.get("WIKIDATAID")}
        answers = ask_wikidata(qids.values())
        for iso, qid in qids.items():
            out[iso].update(facts(answers.get(qid, [])))
    for iso, f in FACTS.items():
        if iso in out:
            out[iso].update(f)
    files = {iso: o.pop("_flag") for iso, o in out.items() if "_flag" in o}
    if flags:
        write_flags(out, files, flags)
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    known = lambda k: sum(1 for o in out.values() if k in o)
    print(f"{len(out)} countries; area {known('km2')}, capital {known('cap')}, languages {known('lang')}, Wikipedia {known('wp')}")


if __name__ == "__main__":
    argv = sys.argv[1:]
    flags = argv[argv.index("--flags") + 1] if "--flags" in argv else None
    args = [a for i, a in enumerate(argv) if not a.startswith("--") and (i == 0 or argv[i - 1] != "--flags")]
    main(args[0], args[1], wikidata="--no-wikidata" not in argv, flags=flags)
