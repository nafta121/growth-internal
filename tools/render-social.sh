#!/usr/bin/env bash
# Re-render the link-preview card and the video poster from tools/social-card.html.
set -euo pipefail
cd "$(dirname "$0")/.."
CHROME=${CHROME:-google-chrome}
page="file://$PWD/tools/social-card.html"
shot() { "$CHROME" --headless=new --hide-scrollbars --virtual-time-budget=5000 \
  --force-device-scale-factor="$1" --window-size="$2" --screenshot="$3" "$4" 2>/dev/null; }
shot 1 1200,630 docs/og.png "$page"
tmp=$(mktemp --suffix=.png)
shot 1.6 1200,675 "$tmp" "$page?poster"   # 1920x1080, same as the video
ffmpeg -v error -y -i "$tmp" -q:v 3 docs/media/launch-poster.jpg
rm -f "$tmp"
echo "docs/og.png, docs/media/launch-poster.jpg"
