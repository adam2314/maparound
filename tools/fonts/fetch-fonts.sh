#!/bin/bash
# Map label fonts for the Android app (glyph ranges in MapLibre's PBF format), so labels draw
# offline: app/src/main/assets/fonts/<font>/<range>.pbf. The same set as the iOS app
# (iosApp/fetch-fonts.sh): Noto Sans (OFL) from the openmaptiles font release, only the ranges for
# Latin, Greek, Cyrillic, Hebrew, Arabic, other scripts below U+2300 and symbols (CJK is drawn
# with the system font).
# Usage: tools/fonts/fetch-fonts.sh [cache-dir]
set -euo pipefail
cd "$(dirname "$0")/../.."
CACHE="${1:-testdata}"
ZIP="$CACHE/openmaptiles-fonts-v2.0.zip"
OUT=app/src/main/assets/fonts
mkdir -p "$CACHE"
[ -f "$ZIP" ] || curl -fsSL -o "$ZIP" https://github.com/openmaptiles/fonts/releases/download/v2.0/v2.0.zip
rm -rf "$OUT"
mkdir -p "$OUT"
for font in "Noto Sans Regular" "Noto Sans Bold" "Noto Sans Italic"; do
  unzip -q -o "$ZIP" "$font/*" -d "$OUT"
  for f in "$OUT/$font"/*.pbf; do
    start=$(basename "$f" .pbf | cut -d- -f1)
    if ! { [ "$start" -lt 8960 ] || { [ "$start" -ge 9472 ] && [ "$start" -lt 9984 ]; } || [ "$start" -ge 64256 ]; }; then
      rm "$f"
    fi
  done
done
du -sh "$OUT"
