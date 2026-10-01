#!/usr/bin/env bash
# Puts a debug build and demo content on an emulator, so screens have something to show:
#   tools/demo-data.sh [adb-serial]        (default: emulator-5554)
# It installs the personal debug APK (build it first with tools/build-install.ps1), generates three
# test videos with ffmpeg, adds a YouTube link and a test stream, and signs in to Jellyfin's public
# demo server. Emulators only: it pushes files and grants permissions over adb.
set -e
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
SERIAL="${1:-emulator-5554}"
# Windows-style path when run from Git Bash (adb.exe and ffmpeg.exe need it), plain path elsewhere.
ROOT="$(cd "$(dirname "$0")/.." && { pwd -W 2>/dev/null || pwd; })"
PKG=com.thealgothrim.dwellplay.debug
R="-n $PKG/com.thealgothrim.dwellplay.DebugReceiver"
adb() { "$ADB" -s "$SERIAL" "$@"; }

MEDIA="$ROOT/build/demo-media"
mkdir -p "$MEDIA"
make() { # name, lavfi video source, tone Hz, seconds
  [ -f "$MEDIA/$1" ] || ffmpeg -loglevel error -y -f lavfi -i "$2" -f lavfi -i "sine=frequency=$3:sample_rate=48000" \
    -t "$4" -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -shortest "$MEDIA/$1"
}
make "DwellPlay test pattern.mp4" "testsrc2=size=1280x720:rate=30" 330 75
make "Colour bars 1080p.mp4" "smptehdbars=size=1920x1080:rate=30" 523 45
make "Cinemascope sample 21x9.mp4" "mandelbrot=size=1280x544:rate=25" 220 40

adb install -r "$ROOT/app/build/outputs/apk/personal/debug/app-personal-debug.apk" | tail -1
adb shell "mkdir -p '/sdcard/Movies/Music Videos'"
for f in "$MEDIA"/*.mp4; do
  name="$(basename "$f")"
  adb push "$f" "/sdcard/Movies/Music Videos/$name" >/dev/null
  # adb-pushed files stay "pending" in MediaStore until scanned, and apps can't see pending files.
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///storage/emulated/0/Movies/Music%20Videos/${name// /%20}" >/dev/null
done
adb shell pm grant $PKG android.permission.READ_MEDIA_VIDEO

# A clean status bar for screenshots.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1030 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null

adb shell am start -n $PKG/com.thealgothrim.dwellplay.MainActivity >/dev/null
sleep 5
adb shell am broadcast $R -a add --es text "https://youtu.be/dQw4w9WgXcQ" >/dev/null
adb shell am broadcast $R -a add --es text "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" --es name "'Mux test stream'" >/dev/null
adb shell am broadcast $R -a jellyfin --es url https://demo.jellyfin.org/stable --es user demo --es password "''" >/dev/null
sleep 6
adb logcat -d -s DwellDebug:I | grep -E "add:|jellyfin:" | sed 's/^.*DwellDebug: //'
echo "Demo data is on $SERIAL."
