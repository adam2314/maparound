"""The shots (SHOTS.md), each a few steps on the device. A step taps what the screen says (in the
run's language: LABELS), so the steps read like the clicks a person makes.

Each shot returns the picture and, if any, the rows to blur. Where it goes: `play` (its number on
Google Play, with `caption`), `site` (docs/assets/<id>.webp), `guide` (docs/guide/img/<id>.webp).
"""
import time
from dataclasses import dataclass, field

# ---- places -----------------------------------------------------------------------------------------

ESTANY_PRIMER = (42.63729, 1.49026)  # you, at the lowest Tristaina lake (Andorra)
TRISTAINA = (42.64450, 1.49150)  # the map's middle over the lakes

# The route the planner shows (and navigation follows): from the Orri trailhead past the lakes to
# Estany de Més Amunt, then up towards Port de l'Albeille.
ORRI = (42.63350, 1.49850)
ROUTE = [ORRI, (42.64300, 1.48900), (42.64780, 1.50200)]

# You, in the party (a made-up name: these pictures are published).
ME = "Yael"

# ---- what the screen says, by language ------------------------------------------------------------------

LABELS = {
    "en": {
        "map": "Map", "library": "Library", "party": "Party", "offline": "Offline", "sensors": "Sensors",
        "close": "Close", "layers": "Map layers", "3d": "3D view of this area", "close3d": "Close 3D view",
        "satellites": "Satellites", "position": "Position", "legend": "Legend", "join": "Join", "start": "Start",
        "more": "More", "satellite": "Satellite imagery", "map_colours": "Map colours", "server": "Server", "terrain": "Terrain",
        "routing": "Offline routing", "trails": "Marked trails", "your_name": "Your name", "leave": "Leave", "zoom_out": "Zoom out", "km": "km", "options": "Options for", "source": "Source", "change": "Change",
        "routing_data": "Routing data", "raster": "Raster maps", "background": "BACKGROUND OF THE OFFLINE MAP",
        "marked_dirt_road": "Dirt road marked red", "leave_title": "Leave the party?", "joined": "joined the party",
    },
    "he": {
        "map": "מפה", "library": "ספרייה", "party": "קבוצה", "offline": "אופליין", "sensors": "חיישנים",
        "close": "סגירה", "layers": "שכבות מפה", "3d": "תצוגת תלת־ממד של האזור", "close3d": "סגירת תצוגת התלת־ממד",
        "satellites": "לוויינים", "position": "מיקום", "legend": "מקרא", "join": "הצטרפות", "start": "התחלה",
        "more": "עוד", "satellite": "צילומי לוויין", "map_colours": "צבעי המפה", "server": "שרת", "terrain": "תבליט",
        "routing": "ניתוב אופליין", "trails": "שבילים מסומנים", "your_name": "השם שלך", "leave": "יציאה", "zoom_out": "התרחקות", "km": "ק״מ", "options": "אפשרויות עבור", "source": "מקור", "change": "שינוי",
        "routing_data": "נתוני ניתוב", "raster": "מפות רסטר", "background": "רקע מפת האופליין",
        "marked_dirt_road": "דרך עפר מסומנת באדום", "leave_title": "לצאת מהקבוצה?", "joined": "הצטרפות לקבוצה",
    },
}


@dataclass
class Shot:
    id: str
    take: object  # (session, labels) -> (image, rects to blur)
    play: int = None
    caption: str = None
    site: bool = False
    guide: bool = False
    needs: list = field(default_factory=list)  # shots to take first (they leave the app set up)


SHOTS = {}


def shot(id, **kw):
    def register(fn):
        SHOTS[id] = Shot(id, fn, **kw)
        return fn
    return register


def brouter(points, profile="hiking-mountain"):
    """A BRouter-Web link: the app opens it in the route planner."""
    lonlats = ";".join(f"{lon:.5f},{lat:.5f}" for lat, lon in points)
    lat, lon = points[0]
    return f"https://brouter.de/brouter-web/#map=14/{lat:.5f}/{lon:.5f}&lonlats={lonlats}&profile={profile}"


# ---- Google Play and the main page ------------------------------------------------------------------------

@shot("map", play=1, caption="Offline topo maps with marked trails and contours", site=True)
def map_(s, L):
    s.fresh()
    s.me(*ESTANY_PRIMER)
    s.at(*TRISTAINA, 14)
    s.settle(8)
    return s.capture("map"), []


@shot("route", play=2, caption="Plan hikes with climb, time and elevation profile", site=True)
def route(s, L):
    s.fresh()
    s.me(*ESTANY_PRIMER)
    s.send(brouter(ROUTE))
    s.ui.wait(L["km"], timeout=60)  # the route's summary
    # The route in the half of the map above the planner.
    s.at(42.63430, 1.49750, 14)
    s.settle(6)
    return s.capture("route"), []


@shot("navigate", play=4, caption="Turn-by-turn navigation with voice prompts", site=True, needs=["route"])
def navigate(s, L):
    s.me(*ORRI)
    s.ui.tap(L["start"])
    s.settle(3)
    # A little way up, between the lakes (off the ski runs at the start).
    for p in [(42.63520, 1.49640), (42.63640, 1.49420), (42.63780, 1.49180)]:
        s.me(*p)
        time.sleep(2)
    s.settle(8)
    return s.capture("navigate"), []


@shot("3d-topo", play=5, caption="Explore the terrain in 3D", site=True)
def three_d(s, L):
    s.fresh()
    s.me(*ESTANY_PRIMER)
    s.at(42.64450, 1.49200, 14)  # the lakes
    s.settle(4)
    s.ui.tap(L["3d"])  # opens looking straight down at the map's middle
    s.settle(12)
    w, h = s.d.size()
    s.d.two_fingers(w // 2 - 40, int(h * 0.52), 150, dy=-260)  # tilted, looking north to Tristaina
    s.settle(5)
    s.ui.tap(L["zoom_out"])  # the camera higher and further back: the whole cirque
    s.settle(15)
    img = s.capture("3d-topo")
    s.ui.tap(L["close3d"])
    return img, []


@shot("satellite", play=6, caption="Satellite imagery under your trails", site=True)
def satellite(s, L):
    s.fresh()
    s.me(*ESTANY_PRIMER)
    s.at(*TRISTAINA, 15)
    s.ui.tap(L["layers"])
    s.ui.scroll_to(L["satellite"])
    s.ui.tap(L["satellite"])
    s.close_sheet()
    s.settle(15)
    img = s.capture("satellite")
    # The background back as it was.
    s.ui.tap(L["layers"])
    s.ui.scroll_to(L["map_colours"])
    s.ui.tap(L["map_colours"])
    s.close_sheet()
    return img, []


@shot("gps", play=7, caption="GPS, compass, barometer and inclinometer, from your phone's sensors", site=True)
def gps(s, L):
    s.fresh()
    s.tab(L["sensors"])
    s.ui.tap(L["satellites"])
    s.settle(15)  # satellites coming into view
    img = s.capture("gps")
    blur = []
    if s.d.real_gps:  # a real receiver: its position isn't published
        node = s.ui.find(L["position"])
        if node:
            w, _ = s.d.size()
            _, y1, _, y2 = node.bounds
            blur.append((0, y1 - 8, w, y2 + (y2 - y1) * 2 + 8))  # the position and the row under it
    return img, blur


# ---- the guides --------------------------------------------------------------------------------------

# Andorra: a 4×4 road marked red (GRP La volta a Andorra), for the marked-trails guide.
MARKED_4X4 = (42.44510, 1.55590)


@shot("offline-list", guide=True)
def offline_list(s, L):
    s.fresh()
    s.tab(L["offline"])
    s.settle(2)
    return s.capture("offline-list"), []


@shot("offline-menu", guide=True, needs=["offline-list"])
def offline_menu(s, L):
    s.ui.tap(L["options"])
    s.settle(1)
    return s.capture("offline-menu"), []


@shot("trails-dirt-road", guide=True)
def trails_dirt_road(s, L):
    s.fresh()
    s.at(*MARKED_4X4, 15)
    s.settle(10)
    return s.capture("trails-dirt-road"), []


@shot("trails-legend", guide=True)
def trails_legend(s, L):
    s.fresh()
    s.ui.tap(L["layers"])
    s.ui.tap(L["legend"])
    s.settle(2)
    _, h = s.d.size()
    s.ui.scroll_until_top(L["marked_dirt_road"], int(h * 0.7))
    s.settle(2)
    return s.capture("trails-legend"), []


@shot("elevation-map", guide=True)
def elevation_map(s, L):
    s.fresh()
    s.at(42.64600, 1.48900, 15)
    s.settle(10)
    return s.capture("elevation-map"), []


@shot("elevation-source", guide=True)
def elevation_source(s, L):
    s.fresh()
    s.tab(L["offline"])
    s.ui.scroll_to(L["source"])
    s.ui.tap_near(L["change"], L["source"])
    s.settle(2)
    return s.capture("elevation-source"), []


@shot("routing-offline", guide=True)
def routing_offline(s, L):
    img, blur = route(s, L)  # the planner, routed on the phone
    return img, blur


@shot("routing-data", guide=True)
def routing_data(s, L):
    s.fresh()
    s.tab(L["offline"])
    s.ui.scroll_to(L["routing_data"])
    _, h = s.d.size()
    s.ui.scroll_until_top(L["routing"], int(h * 0.18))
    s.settle(1)
    return s.capture("routing-data"), []


@shot("raster-add", guide=True)
def raster_add(s, L):
    s.fresh()
    s.tab(L["offline"])
    _, h = s.d.size()
    s.ui.scroll_until_top(L["raster"], int(h * 0.18))
    s.settle(1)
    return s.capture("raster-add"), []


@shot("raster-background", guide=True)
def raster_background(s, L):
    s.fresh()
    s.ui.tap(L["layers"])
    s.ui.scroll_to(L["background"])
    _, h = s.d.size()
    s.ui.scroll_until_top(L["background"], int(h * 0.35))
    s.settle(1)
    img = s.capture("raster-background")
    s.close_sheet()
    return img, []


# ---- the party (last: the device is in it until the run ends) ---------------------------------------

@shot("party-join", site=True)
def party_join(s, L):
    s.fresh()
    s.me(*ESTANY_PRIMER)
    s.leave_party(L)  # out of one an earlier run left it in
    s.party_start()
    s.send(s.invite)
    s.ui.tap(L["your_name"], timeout=20)
    s.d.clear_field()
    s.d.type(ME)
    s.d.back()  # the keyboard away
    time.sleep(1)
    img = s.capture("party-join")
    s.ui.tap(L["join"])
    s.settle(4)
    return img, []


@shot("party-map", play=3, caption="See your group live on the map, even off-grid", site=True, needs=["party-join"])
def party_map(s, L):
    s.tab(L["map"])
    s.at(42.63900, 1.49080, 14)  # everyone and the meeting point
    s.settle(10)
    return s.capture("party-map"), []


@shot("party-list", play=8, caption="End-to-end encrypted group sharing with SOS", site=True, needs=["party-join"])
def party_list(s, L):
    s.tab(L["party"])
    s.ui.wait_gone(L["joined"], timeout=30)  # the "… joined the party" messages
    # Down to the SOS button, the connections and the members.
    _, h = s.d.size()
    s.ui.scroll_until_top("SOS", int(h * 0.21))
    s.settle(2)
    return s.capture("party-list"), []


@shot("party-server", guide=True, needs=["party-join"])
def party_server(s, L):
    s.tab(L["party"])  # in the party: its connections, the server among them
    s.ui.scroll_to(L["server"])
    s.ui.tap_near(L["change"], L["server"])
    s.settle(2)
    return s.capture("party-server"), []
