#!/bin/bash
# Map symbols for the iOS app: the SVG symbols topo.xml uses, from the mapsforge-themes
# library (LGPL-3.0), as PNGs 20 points high at 3x (Symbols/symbols_peak.png).
# Needs rsvg-convert (brew install librsvg). Usage: iosApp/fetch-symbols.sh [cache-dir]
set -euo pipefail
cd "$(dirname "$0")"
CACHE="${1:-../testdata}"
JAR="$CACHE/mapsforge-themes-0.25.0.jar"
mkdir -p "$CACHE"
[ -f "$JAR" ] || curl -fsSL -o "$JAR" https://repo1.maven.org/maven2/org/mapsforge/mapsforge-themes/0.25.0/mapsforge-themes-0.25.0.jar
command -v rsvg-convert >/dev/null || brew install librsvg
rm -rf Symbols svg-tmp
mkdir -p Symbols svg-tmp
unzip -q -o "$JAR" 'assets/symbols/*' 'assets/patterns/*' -d svg-tmp
grep -o 'src="jar:[^"]*\.svg"' ../app/src/main/assets/themes/topo.xml | sed 's/^src="jar://; s/"$//' | sort -u |
  while read -r f; do
    out="Symbols/$(echo "${f%.svg}" | tr '/' '_').png"
    rsvg-convert -h 60 "svg-tmp/assets/$f" -o "$out" || echo "could not convert $f"
  done
rm -rf svg-tmp
echo "$(ls Symbols | wc -l) symbols"
