# Testing

## Unit tests (seconds, no device)

```powershell
.\gradlew.bat :core:test
```

45 test cases in `core/src/test`:

- `SafetyTest`: the car-safety contract, including YouTube never becoming audio, hidden playback never restoring the picture, and a drive always ending in the Resume prompt. New regressions cover automatic next-title loading and external media play while Resume is pending: native audio is rechecked against the policy, and YouTube remains stopped.
- `LinksTest`: every YouTube link shape the apps share, stream detection, subscription services refused, resume rules, search.
- `ClientsTest`: the Jellyfin and YouTube clients against canned responses shaped like Jellyfin 12.1 and YouTube's APIs (sign-in, expired token fails closed, direct play, server conversion, search asks only for embeddable videos).

## Dark Cinema verification (1 October 2026)

The refinement is being checked with Android emulators and simulated displays. These checks do not repeat the real-phone/Desktop Head Unit session from 30 September, and the actual Curvv has never been tested. Historical results below belong to that earlier build; record final results for this revision separately.

- Phone portrait and landscape: four destinations, Settings reachable from Home, native and YouTube players, expanded/empty queues, long titles and larger system fonts.
- Panoramic, standard landscape and portrait car windows: useful video area, scrollable control surfaces, 76dp targets and 24sp labels. An unusually narrow dock scrolls instead of shrinking its targets.
- Scroll the metadata or queue during YouTube playback: the official stage must stay visible. Change layout or enter/leave full screen while paused: current position, playlist index and paused intent must survive remounting.
- Drive and park with native audio, including a title ending while Resume is pending: automatic next must leave the picture gated. An external play action may resume eligible sound, but must not restore the picture or start YouTube.
- Open a dialog before driving: restrictions must remove its separate window. After parking, both the pending dialog and the Resume requirement remain recoverable.

## On an emulator

```powershell
powershell -File tools/build-install.ps1 -Serial emulator-5554     # build and install the debug app
bash tools/demo-data.sh emulator-5554                               # test videos, a YouTube link, a stream, Jellyfin's demo server
bash tools/shot.sh home                                             # screenshot into ./shots
```

Two emulator quirks that look like app bugs and aren't:

- Files pushed with `adb push` stay "pending" in the media library, and apps can't see pending files. `demo-data.sh` asks for a rescan after pushing.
- With `-no-snapshot-save` the emulator forgets everything at shutdown. Run `demo-data.sh` again.

### Test commands (debug builds only)

`DebugReceiver` lets adb drive the app, so nothing depends on blind taps. Every command logs under the tag `DwellDebug`.

```bash
R="-n com.thealgothrim.dwellplay.debug/com.thealgothrim.dwellplay.DebugReceiver"
adb shell am broadcast $R -a add --es text "https://youtu.be/VIDEO_ID"
adb shell am broadcast $R -a play --es first phone        # or youtube, stream, jellyfin, or --es id <video id>
adb shell am broadcast $R -a drive --ez on true           # the car moves
adb shell am broadcast $R -a drive --ez on false          # parked again
adb shell am broadcast $R -a resume                       # the "Resume video" tap
adb shell am broadcast $R -a convert                      # Jellyfin: ask the server to convert
adb shell am broadcast $R -a state                        # log what's playing and the safety state
adb logcat -s DwellDebug
```

`play` starts a session but doesn't open the player screen. YouTube refuses to play unseen (that's the rule working), so tap the mini player to open it.

Testing on someone's own phone: phone videos come only from Movies › DwellPlay, and a debug build can be narrowed further to named files so nothing personal ever reaches a screen or a screenshot:

```bash
adb shell am broadcast $R -a only --es files "'a.mp4|b.mp4'"   # only these files
adb shell am broadcast $R -a only --es files "''"               # back to the whole DwellPlay folder
```

Frame timing on a phone driving Android Auto: find DwellPlay's display with `adb shell dumpsys activity activities`, scroll it with `adb shell input -d <display> swipe ...`, then read `adb shell dumpsys gfxinfo com.thealgothrim.dwellplay.debug` (reset it first with `reset`). For the video itself, `adb shell dumpsys SurfaceFlinger --list` gives the SurfaceView layer and `--latency '<layer name>'` its present times. Measure release builds: debug Compose is several times slower on the UI thread.

### The car screen without a car

An emulator can show a second display at the Curvv's panel size, and DwellPlay can be told to treat it as Android Auto's:

```bash
adb shell settings put global overlay_display_devices "1920x720/262,should_show_system_decorations"
adb shell cmd display get-displays                        # note the new display's id, for example 2
adb shell am broadcast $R -a car --ez on true
adb shell am start --display 2 -f 0x18000000 -n com.thealgothrim.dwellplay.debug/com.thealgothrim.dwellplay.MainActivity
adb shell input -d 2 tap 80 155                           # touch that display
adb shell dumpsys SurfaceFlinger --display-id             # the long id screencap needs
bash tools/shot.sh car-home <long id>
adb shell settings put global overlay_display_devices "''"   # remove it
```

Use **262 dpi** for this simulated Android Auto app display: it matches the parked-app density measured on the DHU. `DwellTheme` divides it by 1.31, yielding the Curvv preset's 200dpi layout scale. A 200dpi overlay would apply that correction twice. The actual DHU preset remains 200 dpi. Additional shapes can use the same simulated 262dpi density; an ordinary phone or AAOS display does not use this correction.

## Android Automotive OS

The car emulator image is `system-images;android-35-ext15;android-automotive;x86_64`, in an AVD named `dwellplay_aaos` (1920x1080).

```powershell
emulator -avd dwellplay_aaos -gpu host -no-window -no-audio -port 5556
powershell -File tools/build-install.ps1 -Flavor store -Serial emulator-5556 -Launch
```

The car's driver is Android user 10, not 0. Permissions and files have to go to that user:

```bash
adb -s emulator-5556 root
adb -s emulator-5556 shell pm grant --user 10 com.thealgothrim.dwellplay.debug android.permission.READ_MEDIA_VIDEO
adb -s emulator-5556 push video.mp4 "/data/media/10/Movies/video.mp4"
adb -s emulator-5556 shell chown -R media_rw:media_rw /data/media/10/Movies
adb -s emulator-5556 shell am broadcast --user 10 -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///storage/emulated/10/Movies/video.mp4
```

Simulate driving through the vehicle hardware layer, which is what real driving restrictions come from:

```bash
# drive
adb -s emulator-5556 shell cmd car_service inject-vhal-event 0x11400400 8       # gear: drive
adb -s emulator-5556 shell cmd car_service inject-vhal-event 0x11200402 false   # parking brake off
adb -s emulator-5556 shell cmd car_service inject-vhal-event 0x11600207 30      # speed, m/s
# park
adb -s emulator-5556 shell cmd car_service inject-vhal-event 0x11600207 0
adb -s emulator-5556 shell cmd car_service inject-vhal-event 0x11400400 4       # gear: park
adb -s emulator-5556 shell cmd car_service inject-vhal-event 0x11200402 true
```

What should happen, and was observed on the pre-refinement build on 30 September 2026:

1. Parked: the video plays in the theater and rail layout.
2. Driving: the system covers DwellPlay. On this image it shows its Drive View with DwellPlay's title, position and pause button while the sound carries on; on images without audio-while-driving it shows "You can't use this feature while driving" and playback pauses.
3. Parked again, "Exit Drive View": DwellPlay is back on "Parked. Ready when you are." with a Resume video button. No moving picture until it's tapped.

The car emulator's network can be flaky (DNS drops). Use a local video for this test.

## Android Auto

Needs a real phone. The Desktop Head Unit steps are in [android-auto.md](android-auto.md).

The earlier phone/DHU test is historical evidence of the projection route. Repeat it after the refinement; emulator overlays cannot verify Android Auto's app grid, host corner buttons, keyboard or driving takeover.

## Before a release

- `.\gradlew.bat :core:test` passes.
- The release build still plays a phone video and a YouTube video (the release is shrunk with R8; `app/proguard-rules.pro` keeps the YouTube bridge).
- On the car emulator: no path from driving to moving video, and YouTube stops completely.
- Native auto-next and media play while waiting on Resume leave the picture gated; YouTube remains unloaded until an explicit video action.
- YouTube remains visible when controls scroll; a layout remount preserves current position and pause state.
- Four-destination navigation, Settings, queue and Resume actions remain usable on narrow windows and with larger fonts.
- Controls are at least 76dp in the car layout; text is 24sp or larger.
- A server sign-in that no longer works asks to sign in again and plays nothing.

## README screenshots

Take them with `tools/shot.sh` using the names listed at the top of `tools/readme_images.py`, then run it on that folder.
