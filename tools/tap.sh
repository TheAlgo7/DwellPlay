#!/usr/bin/env bash
# Taps the on-screen element whose text or label contains the given words (emulators only):
#   tools/tap.sh "Resume video" [display-id]
# Reads the screen with uiautomator, so it never taps blind. Prints what it tapped, or lists what
# is on screen when nothing matches.
set -e
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
want="$1"; display="${2:-}"
adb() { "$ADB" ${SERIAL:+-s $SERIAL} "$@"; }
adb shell uiautomator dump ${display:+--display-id $display} /sdcard/dwellplay-ui.xml >/dev/null 2>&1
# One node per line (the dump is a single line of XML).
nodes="$(adb shell cat /sdcard/dwellplay-ui.xml | sed 's/></>\n</g')"
match="$(printf '%s\n' "$nodes" | grep -iE "(text|content-desc)=\"[^\"]*${want}[^\"]*\"" | head -1)"
if [ -z "$match" ]; then
  echo "Nothing on screen matches \"$want\". Visible:" >&2
  printf '%s\n' "$nodes" | grep -oE "(text|content-desc)=\"[^\"]+\"" | sort -u | head -40 >&2
  exit 1
fi
bounds="$(printf '%s' "$match" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+')"
set -- $bounds
x=$(( ($1 + $3) / 2 )); y=$(( ($2 + $4) / 2 ))
adb shell input ${display:+-d $display} tap $x $y
echo "tapped \"$want\" at $x,$y"
