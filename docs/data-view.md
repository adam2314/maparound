# The data view

The **data view** turns the map into a way to explore places through their numbers: how many people
live there, how old they are, how rich, how high, how long they live. It's in the web app, under
**Layers → Data view**.

**The goal:** more data to explore the world. The first version covers every country and one
country in depth (Israel, down to each of its localities). This page describes what's there, where
the data comes from, how it's built, how to add more, and where it goes next.

## What it shows now

### Countries (zoomed out)

Every country is coloured by one figure, chosen in the panel's picker. The legend's colours run light
to dark in seven classes.

| Figure | Classes (breaks) | Source |
|---|---|---|
| Population | 1, 5, 20, 50, 100, 300 million | Our World in Data (UN World Population Prospects) |
| Population density | 10, 25, 50, 100, 200, 500 per km² | Our World in Data (UN WPP) |
| GDP per person | $2,000, 5,000, 10,000, 20,000, 35,000, 50,000 (PPP) | Our World in Data (World Bank WDI) |
| Life expectancy | 60, 65, 70, 75, 80, 83 years | Our World in Data (UN WPP) |
| Median age | 20, 25, 30, 35, 40, 45 years | Our World in Data (UN WPP) |

Each figure is the latest year with a value. Projections are left out: the population-density and
median-age charts run to 2100.

Tapping a country opens a card with its name (Hebrew or English) and every figure loaded so far,
each with its year.

### Israel's localities (zoomed in, from the app's zoom 7)

Each locality is a circle as big as its population: the radius grows with the square root, so a
circle's area follows its head count. The **Colour** picker chooses what the colour shows.

| Colour by | Classes |
|---|---|
| Size | <1,000 · 1,000–4,999 · 5,000–19,999 · 20,000–99,999 · 100,000+ people |
| Children (0–18) | <25% · 25–30% · 30–35% · 35–45% · 45%+ of residents |
| Young adults (19–45) | <30% · 30–35% · 35–40% · 40–45% · 45%+ |
| Aged 65 and over | <8% · 8–12% · 12–16% · 16–20% · 20%+ |
| Arab residents | <10% · 10–50% · 50–90% · 90%+ |
| Kind of place | Jewish town or city · Arab town · moshav · kibbutz · community settlement · moshava · Arab or Bedouin village · institution, other |
| Religion | Jewish · Arab · Bedouin · mixed |
| Founded | before 1948 · 1948–1966 · 1967–1989 · 1990 and after |
| Altitude | below sea level · 0–199 · 200–499 · 500–799 · 800 m and over |
| Socio-economic cluster | 1–2 · 3–4 · 5–6 · 7–8 · 9–10 |
| District | Jerusalem · Northern · Haifa · Central · Tel Aviv · Southern · Judea and Samaria |

A grey circle means that place has no data for the chosen figure.

Tapping a locality opens a card: its Hebrew and English names, kind, district, residents, the Arab
share (from 1%), founding year, altitude, socio-economic cluster, and a bar chart of the six age
groups. **Pin it** drops the map's pin there, so *What's here?*, Wikipedia, routing and sharing
work as for any place.

### In both levels

- **The legend filters.** Tapping a class hides those countries or places; tapping it again brings
  them back. The grey "No data" class can be hidden too.
- **The panel's button** jumps between the levels: to Israel at zoom 7, or out to the whole world.
- **Credits** are added to the map's credits line while the view is on.
- **Both languages.** The view works in English and in Hebrew, right to left.

## Where the data comes from

| Data | Publisher | Licence | How it's read | Size, first use |
|---|---|---|---|---|
| Country outlines | [Natural Earth](https://www.naturalearthdata.com), 1:50m Admin 0 | public domain | bundled: `webApp/.../resources/countries.json` | 0.9 MB (about 0.25 MB compressed) |
| Country figures | [Our World in Data](https://ourworldindata.org) grapher CSVs | CC BY 4.0, and each source's own terms | downloaded per figure, when chosen | 0.3–2.2 MB each |
| Localities of Israel | Central Bureau of Statistics (הלמ"ס), *localities-in-israel* on [data.gov.il](https://data.gov.il) | marked open | CKAN API: the dataset's newest table | about 1.5 MB |
| Residents by age group | Population and Immigration Authority, *residents_in_israel_by_communities_and_age_groups* | **none stated on the portal** | CKAN API, newest table (updated monthly) | about 0.3 MB |
| Socio-economic clusters (2019) | *social_economic_cluster* on data.gov.il | marked open | CKAN API | small |

All the online sources allow a web page to read them directly (CORS `*`), so the browser fetches
them itself; no server is involved.

**Left out, and why:**
- **The World Bank's own API:** its terms allow non-commercial use only and forbid derivative works,
  which doesn't fit a GPL app, and it didn't answer when tested. Its development indicators reach
  us through Our World in Data instead.
- **The CIA World Factbook:** discontinued in February 2026. The CIA's announcement doesn't say
  where the data now lives. Its last edition is public domain, but frozen.

**Before relying on the age data publicly**, check the Population Authority's terms: the portal
lists no licence for it.

## How it's built

All the logic is shared Kotlin; only the web app switches it on.

| Part | Where | What it does |
|---|---|---|
| Israel's grid | `shared/.../stats/Itm.kt` | The CBS gives each locality's place as one number, 12 digits: east and north in metres on the Israeli Transverse Mercator. This turns it into latitude and longitude (inverse transverse Mercator on GRS80; the datum's few metres' offset is left out). |
| Localities | `shared/.../stats/Localities.kt` | Reads the three data.gov.il tables and joins them by locality code. Field names are matched by their start (the CBS puts the year in some). Places without people or coordinates are left out. |
| Country figures | `shared/.../stats/CountryStats.kt` | Reads an Our World in Data CSV and keeps the latest year with a value per ISO 3166 code, up to this year. |
| Classes and layers | `shared/.../stats/StatsLayers.kt` | Each figure's classes, colours and legend labels, and the GeoJSON for the map: countries with a `color` per feature, localities with `color` and a radius `r`. |
| Loading and cache | `shared/.../stats/StatsData.kt` | Downloads each source the first time it's needed and keeps it for a week in the app's files (`/data/stats` on the web); offline, it uses the kept copy however old. |
| Map layers | `MapStyle.kt` | Two GeoJSON sources: a fill for the countries (to MapLibre's zoom 7) and circles for the localities (from 6). Their ids start with `tap-`, so a tap reports the feature's `tap` key (`data:c:ISR`, `data:l:3000`). |
| Screen | `ui/DataViewUi.kt`, `ui/MapScreen.kt` | The panel (pickers, legend, level button), the country and locality cards, and the Layers switch. |
| Texts | `i18n/StatsTexts.kt` | English and Hebrew. |
| Outlines file | `tools/stats/make_countries.py` | Builds `countries.json` from Natural Earth: ISO code, English and Hebrew names, the outline simplified to about 1.5 km. |

The view appears only where the app provides `LocalStats` (the web app, in `WebApp.kt` and
`Main.kt`). Android and iPhone would need only that: a `StatsData` with a cache folder and the
outlines file from their assets.

Tests: `shared/src/commonTest/.../stats/StatsTest.kt` covers the grid conversion, the tables, the
CSV, the classes and the layers.

## Adding data

### Another figure for the countries

1. Find the chart on Our World in Data. Its CSV is
   `https://ourworldindata.org/grapher/<slug>.csv?v=1&csvType=full&useColumnShortNames=true`.
   Check that the value is the first column after `year`; the parser takes that column.
2. Add an entry to `CountryIndicator` with the slug and six breaks.
3. Add its name and its value format to `StatsTexts`, in English and Hebrew.
4. Add the source to the credits if it isn't Our World in Data's own.

### Another way to colour the localities

Add an entry to `LocalityIndicator` with its `classes` and its `classOf`, then its name in
`StatsTexts`. If it needs a new field, read it in `IsraelLocalities.parse`, match the field name by
its start, and add it to `Locality`.

### Another country's places

Write an adapter like `IsraelLocalities`: read the publisher's table, turn its coordinates into
latitude and longitude, and produce `Locality`-like records. Countries publish different figures,
so the classes may need to be per country.

### Regions (states, districts)

Country outlines are bundled; region outlines are too many to bundle. Load them per country when
zoomed in: [geoBoundaries](https://www.geoboundaries.org) has ADM1 and ADM2 for every country, under
CC BY 4.0 (credit required), callable from the browser.

## Next: more data to explore the world

Ordered roughly by value for the effort.

**1. Make what's there go further.**
- **Time:** a year slider for the countries. Our World in Data's CSVs already hold every year, so it
  costs no new download.
- **More country figures:** for example fertility, urban share, CO₂ per person, internet use,
  literacy, forest cover, from Our World in Data (check each chart's source licence).
- **Find and compare:** a searchable list of places sorted by the chosen figure, and two places side
  by side.
- **Share a view:** the figure and level in the page's address, as the map's `#map=` is already.

**2. Places everywhere, not only in Israel.** [Wikidata](https://www.wikidata.org) (CC0: no
conditions) has population, elevation, founding date and area for cities and towns worldwide,
with names in many languages. Query the places in view through its SPARQL service, and cache them
by area. Its limits apply: 60-second queries, 5 at a time per address, a User-Agent required.

**3. Regions of every country.** geoBoundaries outlines, with regional figures where they're open:
national statistics offices, and Eurostat for Europe (its terms and API to check: its site blocked
the research request).

**4. Israel in more depth.** Still on data.gov.il, with each dataset's licence checked:
- municipal indices (education, budgets and more, about 1.4 million rows, so filter them before
  loading)
- the CBS's statistical areas, for neighbourhoods within cities
- election results by locality

**5. Data that fits a hiking and off-road app.** Protected areas, climate normals (temperature and
rain by month), daylight. Licences vary here: the main world protected-areas database restricts
commercial use, for example. Check each one before using it.

**6. Android and iPhone.** The code is shared; provide `StatsData` on each.

### Principles for adding data

- **Licences:** open and compatible with a GPL app that's redistributed. Credit every source on the
  map, and check each dataset's own licence, not only the portal's.
- **Access:** readable from a browser (CORS), or built into a small file by a tools script.
- **Size and caching:** download data only when first used, keep it, and show it offline.
- **Languages:** names and labels in English and Hebrew, and Hebrew reads right to left.
- **Colours:** palettes that colour-blind users can tell apart, with grey for "no data".
- **Privacy:** no tracking. Requests go straight to the publishers, carrying nothing about the user.
