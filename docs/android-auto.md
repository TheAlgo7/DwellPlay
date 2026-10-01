# Getting DwellPlay onto a car screen

Checked against Google's documentation and on Android Auto itself on 30 September 2026.

## The three routes

### 1. Android Auto today: the parked-app door (the `personal` build)

Android Auto can already run an ordinary Android activity on the car's screen while the car is parked. Google's page ["Add support for Android Auto to your parked app"](https://developer.android.com/training/cars/parked/auto) says it works on phones running Android 15 or newer, that the activity needs the `android.intent.category.CAR_LAUNCHER` category, and that games are the only parked category Android Auto accepts "at this time".

So the `personal` build declares:

- `android:appCategory="game"` (set in `app/build.gradle`)
- `CAR_LAUNCHER` on the main activity (`app/src/personal/AndroidManifest.xml`)
- and, just as important, **no** Android Auto media declaration (see "What the head unit taught us" below)

Android Auto then lists DwellPlay in the car's app grid, shows it full screen as a normal app while parked, and takes the screen away when the car moves. The parked-only rule is enforced by Android Auto itself from the car's own driving status, which is a stronger guarantee than anything an app could check.

Be clear about what this is: a video app wearing the games label, because the video label isn't accepted on Android Auto yet. That is fine for your own car. It is not something Play's review would pass, so:

- Upload this build to the **internal testing track only**, with yourself as the tester. Internal testing has no review. Never promote it to closed, open or production.
- Your Play developer account carries every other app you publish. If this ever feels risky, skip Play for DwellPlay and wait for route 2.

### 2. Android Auto, officially: video apps (the `store` build)

In May 2026 Google announced that [Android Auto is getting video apps](https://android-developers.googleblog.com/2026/05/android-for-cars-unifying-platforms-premium-experiences.html): full HD at 60fps while parked, an audio-only mode when the car shifts out of park, phones on Android 17 or newer, rolling out to compatible cars "later this year". The [launch list](https://9to5google.com/2026/05/12/android-auto-video-apps-gemini-updates/) names Tata along with BMW, Ford, Hyundai, Kia, Mahindra, Mercedes-Benz, Renault, Skoda, Volvo and Genesis. Google's note to developers: "If your video app is already adaptive, making it available for parked use cases in cars requires minimal effort."

DwellPlay is already that adaptive video app. When the category opens:

1. Ship the `store` build (`android:appCategory="video"`).
2. Move the `CAR_LAUNCHER` intent filter from `app/src/personal/` into the main manifest, if Google's page still asks for it.
3. Check how Android Auto then treats an app that has both a media list and a parked screen (today it shows only one of them), and drop the `personal` flavor.

Until then Google takes early-access interest through the form linked from that blog post. It costs nothing to fill in.

This route needs the phone on Android 17, so it also waits for the phone maker's update.

### 3. Android Automotive OS (cars with Google built-in): the `store` build

Video apps are a supported category there today ([Build video apps](https://developer.android.com/training/cars/parked/video)). The same APK installs on the car. The system hides the app when driving restrictions are on, and on cars that support it, keeps the sound going in its own Drive View. Verified on the Android 15 car emulator (see [testing.md](testing.md)). The Curvv is not one of these cars; this route is for the wider product.

## What the head unit taught us

Tested on the Desktop Head Unit 2.0 with the Curvv preset, a Galaxy S24 Ultra on Android 16, Android Auto 17.7, and a debug build installed over USB.

**It works.** Parked: DwellPlay opens full screen from the car's app grid and plays phone videos and YouTube. `restrict all` (driving): Android Auto removes it from the screen and returns to its dashboard. A phone video carries on as sound, and Android Auto's dashboard media card shows it with pause and previous. YouTube stops completely. `restrict none` (parked) and reopening: "Parked. Ready when you are." with Resume video, and it continues where it stopped. Typing works too: the phone's keyboard appears on the car screen.

Four things no documentation mentions:

1. **One entry per app, and the driving one wins.** With the Android Auto media declaration (`com.google.android.gms.car.application` with `<uses name="media"/>`) in the manifest, Android Auto found the parked screen and then dropped it. Its log says so in plain words:

   ```text
   GH.AvailableAppFinder: Skipping ProjectionApp(...MainActivity..., isParkedOnly=true, isImmersiveApp=true,
   isNativeApp=true) due to a conflicting driving optimized app
   ```

   That is why the declaration lives in `app/src/store/AndroidManifest.xml` only. The personal build gives up the media list you could browse while driving, and keeps the video screen. Sound while driving still works, through the dashboard card.
2. **A parked app's display is 1.31 times denser than the head unit's.** Android Auto gave the app a 1920x720 display at 262 dpi, while its own screens use the head unit's 200. That zoom makes phone-sized buttons car-sized, but DwellPlay's car layout is car-sized already, so everything came out a third too big. `DwellTheme` divides the density back (`ANDROID_AUTO_ZOOM`), so the app lays out at the scale Android Auto uses for itself.
3. **Android Auto draws its own buttons in two corners.** For a few seconds after the app opens, a back arrow sits top-left and an "Exit" button top-right, on top of the app. Nothing of DwellPlay's that can be tapped goes in those corners on Android Auto (`LocalHostCorners`).
4. **Reopening while something plays comes with an action.** Android Auto restarts the activity with `MEDIA_SHOW_PLAYBACK_VIEW`; DwellPlay then goes straight to its player, which is waiting on Resume.

To read that log yourself: `adb logcat | grep -E "GH.AvailableAppFinder|CAR.VALIDATOR"`.

Still not proven: the real car. The head unit simulator accepts a USB-installed build once "Unknown sources" is on; a real car, as with Overworld Maps, wants the app to come from Play.

## Into the Curvv, step by step

Same path Overworld Maps took, because Android Auto in a real car only trusts apps that came from Play.

1. **Create the app** in Play Console: name DwellPlay, package `com.thealgothrim.dwellplay`.
2. **Internal testing** > create a release > upload `Work\Ongoing Apps Assets\DwellPlay\Builds\DwellPlay 0.1.0 (personal).aab`. It is signed with the upload key in `...\DwellPlay\Keys` (password in that folder's README). Let Play App Signing create the app-signing key.
3. Add your tester list, open the opt-in link on the phone, install from Play.
4. On the phone, open DwellPlay once: allow video access, share a few YouTube videos to it, connect a server if you have one.
5. In the car, parked, with Android Auto connected: open the app grid and tap DwellPlay.

To rebuild the bundle after a change: bump `versionCode` in `app/build.gradle`, then

```powershell
.\gradlew.bat :app:bundlePersonalRelease
```

The bundle lands in `app\build\outputs\bundle\personalRelease\`.

## Testing with the Desktop Head Unit

The DHU stands in for the car. It needs a real phone.

1. Phone: Android Auto settings > tap the version until developer mode turns on > developer settings > enable **Unknown sources** (so a build installed over USB is allowed on the DHU). Then the three-dot menu on the main Android Auto settings page > **Start head unit server**.
2. PC:

   ```powershell
   adb forward tcp:5277 tcp:5277
   powershell -File tools/build-install.ps1 -Serial <phone serial>
   python tools/dhu/dhu_console.py
   ```

   That starts the DHU with the Curvv preset (`tools/dhu/tata_curvv.ini`: 1920x1080 cropped to 1920x720, 200 dpi, right-hand drive, with the driving sensors switched on).
3. Drive it by appending lines to `C:\tmp\dhu-cmd.txt`. Coordinates are in the full 1920x1080 frame; the car's visible screen is the band from y=180 to y=900.

   ```text
   tap 1862 843             Android Auto's corner button: app, then dashboard, then app grid
   screenshot C:/tmp/dwellplay-parked.png
   restrict all             the car is driving: DwellPlay must leave the screen
   restrict none            parked again: DwellPlay must wait on "Resume video"
   quit
   ```

   Check four things: DwellPlay appears in the app grid; a YouTube video stops dead when `restrict all` arrives; a phone video carries on as sound and shows in Android Auto's media card; after `restrict none`, reopening DwellPlay shows the Resume prompt and no moving picture.

After installing a build whose manifest changed, reconnect the DHU (`quit`, then start it again): Android Auto builds its app list when the head unit connects.

Screenshots of the head unit can show your own videos and, behind other apps, your location. Keep them out of the repo unless you have looked at them.
