#!/bin/bash
# Map label fonts for the iOS app (glyph ranges in MapLibre's PBF format), so labels draw
# offline. From the openmaptiles font release (Noto Sans, OFL); only the ranges for Latin,
# Greek, Cyrillic, Hebrew, Arabic and symbols are kept (CJK is drawn with the system font).
# Usage: iosApp/fetch-fonts.sh [cache-dir]   (writes iosApp/Fonts)
set -euo pipefail
cd "$(dirname "$0")"
CACHE="${1:-../testdata}"
ZIP="$CACHE/openmaptiles-fonts-v2.0.zip"
mkdir -p "$CACHE"
[ -f "$ZIP" ] || curl -fsSL -o "$ZIP" https://github.com/openmaptiles/fonts/releases/download/v2.0/v2.0.zip
rm -rf Fonts
mkdir -p Fonts
for font in "Noto Sans Regular" "Noto Sans Bold" "Noto Sans Italic"; do
  unzip -q -o "$ZIP" "$font/*" -d Fonts
  for f in "Fonts/$font"/*.pbf; do
    start=$(basename "$f" .pbf | cut -d- -f1)
    if ! { [ "$start" -lt 8960 ] || { [ "$start" -ge 9472 ] && [ "$start" -lt 9984 ]; } || [ "$start" -ge 64256 ]; }; then
      rm "$f"
    fi
  done
done
du -sh Fonts
