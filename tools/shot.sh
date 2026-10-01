#!/usr/bin/env bash
# Screenshot a device display into a folder: tools/shot.sh <name> [display-id] [out-dir]
# Uses screencap on the device and adb pull (a redirect can mangle the PNG on Windows shells).
set -e
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
name="$1"; display="${2:-}"; out="${3:-${SHOTS:-shots}}"
mkdir -p "$out"
if [ -n "$display" ]; then "$ADB" ${SERIAL:+-s $SERIAL} shell screencap -d "$display" -p /sdcard/dwellplay-shot.png
else "$ADB" ${SERIAL:+-s $SERIAL} shell screencap -p /sdcard/dwellplay-shot.png; fi
"$ADB" ${SERIAL:+-s $SERIAL} pull /sdcard/dwellplay-shot.png "$out/$name.png" >/dev/null
echo "$out/$name.png"
