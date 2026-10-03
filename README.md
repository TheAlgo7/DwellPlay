# DwellPlay

Your parked-screen media hub: videos on your phone, YouTube, your own Jellyfin server and stream links, on the car's big screen whenever you're parked. When you drive, the picture goes away and your own videos carry on as sound.

One Android app for three screens: the phone, the car screen through Android Auto, and cars that run Android Automotive OS (Google built-in).

## Dark Cinema

The 1 October 2026 refinement uses near-black graphite layers, restrained dark translucent controls, neutral transport buttons and small Algothrim crimson accents. Artwork remains the card; cached artwork adds a quiet atmosphere around the player controls. The direction is recorded in [DESIGN.md](DESIGN.md).

Home, Library, Search and Sources are the four primary destinations. Settings is in the phone Home header and the car rail footer; compact car windows also expose it in Home. Navigation becomes an inset dock when a rail would leave too little room.

The player recomposes for the window: panoramic video and controls sit side by side, while phone portrait and narrower car windows stack them. The active video stage stays visible while metadata and the expandable queue scroll. YouTube's controls remain outside its official player; layout changes preserve its current position and play/pause intent.

## Screenshots and verification

The screenshots below are historical captures from 30 September 2026, before the Dark Cinema refinement. The Android Auto images came from a real phone connected to the Desktop Head Unit, not the actual Curvv. Checks for the redesigned build use emulators and simulated displays; see [docs/testing.md](docs/testing.md) for the verification steps and results. The redesign still needs a fresh phone/DHU test, and the actual Curvv remains unproven.

![DwellPlay on Android Auto: YouTube's own player at full height, DwellPlay's controls in a rail beside it](docs/readme/android-auto-youtube.png)

Above: Android Auto on 30 September 2026 (the Desktop Head Unit set to a Tata Curvv's 1920x720 screen, driven by a real phone). Below: after a drive, the picture waits for a tap. The back arrow and Exit button in the corners are Android Auto's own.

![DwellPlay on Android Auto after a drive: "Parked. Ready when you are." with a Resume video button](docs/readme/android-auto-resume.png)

Home on the car screen, and a film from a Jellyfin server in the player:

![Home on the car screen](docs/readme/car-home.png)

![A film from a Jellyfin server in the car player](docs/readme/car-player.png)

On the phone: home, the player, what it shows while the car moves, and the prompt after you park.

![Four phone screens](docs/readme/phone.png)

On Android Automotive OS the car itself takes over while driving (its Drive View keeps the sound going), and DwellPlay waits for a tap once you've parked.

![Android Automotive OS: Drive View while driving, the resume prompt after parking](docs/readme/automotive-drive-park.png)

## What it plays

| Source | How |
|---|---|
| Videos on the phone | Read from the phone's own library. Through Android Auto the app runs on the phone, so these play on the car screen with no copying. |
| A folder or USB drive | Picked with the system file picker. Only that folder is read. |
| YouTube | Only in YouTube's official embedded player. Share a video or playlist from the YouTube app to DwellPlay, or paste a link. Search works once a free YouTube Data API key is added in Settings. |
| Jellyfin | Sign in to your own server. It sends the original file when the car can play it and converts it on the fly when it can't. |
| Stream links | HLS (.m3u8), DASH (.mpd) or a video file link you own. |

Netflix, Prime Video and other subscription services play only in their own apps. DwellPlay does not try to play them.

## The safety rules

These are the product, not an add-on. They live in one small state machine ([core/.../policy](core/src/main/kotlin/com/thealgothrim/dwellplay/core/policy)) with a unit test for every rule.

- Video shows only while the car's own system says it is parked. There is no gear or speed shortcut in the app.
- When the car moves, YouTube stops completely. Its rules forbid audio-only or background play, and the code makes that impossible by type, not by convention.
- Your own videos (phone, folder, Jellyfin, links) can carry on as sound while you drive, if you leave that setting on and the car allows it.
- After a drive the picture never restarts by itself. You tap "Resume video".
- Automatic queue advancement and play from external media controls retain that Resume requirement. Eligible native sound can continue; YouTube stays stopped until a deliberate video action.

## How it reaches a car

| Screen | Build | Status |
|---|---|---|
| Android Auto (for example a Tata Curvv) | `personal` | Uses the parked-game route documented on 30 September 2026. The pre-refinement build worked on the Desktop Head Unit with a real phone (Android 16, Android Auto 17.7): parked video, native sound while driving, and explicit Resume after parking. The redesigned build needs a new DHU test; the actual Curvv has not been tested. For personal use through Play's internal testing track only. |
| Android Auto, officially | `store` | Google announced video apps for Android Auto in May 2026 (Tata is on the launch list, phones need Android 17). When it opens, the `store` build's honest `video` declaration is what ships. |
| Android Automotive OS | `store` | The pre-refinement build was checked on the Android 15 car emulator with platform driving restrictions on 30 September 2026. Re-check the redesigned build using the steps in testing.md. |

Details and the step-by-step for a real car are in [docs/android-auto.md](docs/android-auto.md).

## Build and try it

Needs JDK 21 and the Android SDK (platform 36).

```powershell
# unit tests for the safety rules, link parsing and the Jellyfin and YouTube clients
.\gradlew.bat :core:test

# build and install on a connected phone or emulator
powershell -File tools/build-install.ps1 -Launch                  # personal build
powershell -File tools/build-install.ps1 -Flavor store -Launch    # store build

# put demo content on an emulator (test videos, a YouTube link, Jellyfin's public demo server)
bash tools/demo-data.sh emulator-5554
```

Debug builds install next to the real app as "DwellPlay debug" and accept test commands over adb (pretend the car is driving, add a link, play something). See [docs/testing.md](docs/testing.md).

The core suite now contains 45 test cases, including post-drive queue advancement and external-play regressions. Run it for the current result; an emulator layout check does not establish real-car compatibility.

## Layout

```text
core/    plain Kotlin: the media model, the safety state machine, link parsing,
         the Jellyfin and YouTube clients. No Android, so its tests run in seconds.
app/     the Android app: Compose screens, the Media3 player service, the YouTube embed,
         phone and folder sources, car detection.
docs/    architecture, the Android Auto route, testing.
tools/   build and install, demo data, screenshots, the Desktop Head Unit preset.
```

## Not built yet

Plex and DLNA sources, a phone-to-car bridge for Android Automotive OS (not needed through Android Auto), DRM streams a rights holder would have to authorise, and search by voice. The reasoning for each is in [docs/architecture.md](docs/architecture.md).
