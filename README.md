# Nipper FM

Nipper FM is a native Android internet radio app. It finds stations through [Radio Browser](https://www.radio-browser.info/), plays streams directly from broadcasters, and shares one Media3 playback session across the phone, lock screen, home-screen widget, and Android Auto. Listening requires no account. The app inserts no ads.

## What you can do

- **Explore.** Search by station, city, callsign, or frequency where directory data supports it. Use voice search, browse genres, filter by country, or try **Surprise Me**. The country shortlist ranks countries by recent clicks across a sample of the most-played stations, with **More countries** opening the full list.
- **Save stations.** Add favorites to Collections, return to the last station, and preview a custom HLS, Icecast, or Shoutcast URL before saving it.
- **Find tracks again.** Recently On Air logs track titles when a broadcaster supplies them. Save individual tracks and copy track information.
- **Control playback.** Use Wi-Fi-only mode with a one-time mobile-data override, choose a buffer profile, switch stream sources, or set a sleep timer with a fade-out. The player distinguishes connection loss from a failed stream and shows retry actions.
- **Adjust sound and appearance.** Swipe through EQ presets, choose spatial audio or loudness enhancement where supported, and customize the theme and accent. Now Playing shows station artwork or the Nipper FM logo.
- **Listen in the car or from the launcher.** Manage up to six ordered Android Auto presets in Favorites. The home-screen widget offers playback controls and quick tuning.
- **Back up your setup.** Export favorites, collections, car presets, custom streams, and appearance as JSON. Preview an import before merging or replacing local data.

## Build and install

The project uses Kotlin, Compose Material 3, Media3, Room, DataStore, Retrofit, OkHttp, kotlinx.serialization, and Coil. It has one Android module, `:app`, with application ID `app.openradio`. The minimum Android version is API 26. The project targets API 36 and compiles with API 37.

Use the Gradle wrapper with JDK 17 or newer and install Android SDK platform 37. Android Studio can generate `local.properties` with the SDK location.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

With multiple devices attached, use `adb devices -l` and specify the serial with `adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`. You can also run the `app` configuration from Android Studio.

## Architecture

| Area | Responsibility |
|---|---|
| [Domain](app/src/main/java/app/openradio/domain) | Station data, ordering, playback status, retries, sleep timing, and audio presets |
| [Data](app/src/main/java/app/openradio/data) | Room-backed favorites, collections, recents and track history, DataStore preferences, and Radio Browser requests |
| [Playback](app/src/main/java/app/openradio/playback) | ExoPlayer, the Media3 library session, audio effects, and the phone's media controller |
| [UI](app/src/main/java/app/openradio/ui) | Explore, Favorites, Now Playing, Settings, and shared appearance and controls |

`RadioService` owns the player. The phone uses a MediaController, while Android Auto and system playback controls use the same session. Room stores the last-known stream URL for saved and recently played stations. Radio Browser requests use a descriptive User-Agent, prefer resolved stream URLs, and can fail over to another mirror.

## Verification

The latest debug build passed **75 unit tests** and lint with **0 errors and 10 warnings**. The APK installed on `emulator-5554` and the physical **CPH2791**.

Emulator checks covered discovery and country filtering, collections, backup export and import preview, custom-stream preview and save, track saving, Wi-Fi-only handoff and override, terminal stream errors and retry, manual alternate-feed switching, sleep controls and countdown, persisted playback settings, and widget tuning and playback controls. The v1 → v2 → v3 migration SQL preserved seeded data in a scratch SQLite database.

For Android Auto verification, start the head unit server from Android Auto developer settings on the phone and connect the Desktop Head Unit:

```bash
adb forward tcp:5277 tcp:5277
~/Android/Sdk/extras/google/auto/desktop-head-unit
```

Check that Presets and Recents open, selecting a preset works with the phone locked, next and previous follow preset order, and connecting alone keeps playback stopped. Repeat the flow in a real car when available.

## Platform and release notes

- Some broadcasters serve plain HTTP. The app accepts those direct stream URLs and uses HTTPS for Radio Browser.
- EQ and spatial audio depend on the device's audio effects. The loudness toggle applies a fixed makeup gain. The spectrum is hidden when live FFT capture is unavailable, and this build does not request microphone access.
- The release build uses R8. Signing and distribution setup are pending.
- Real-car validation, physical audio measurements, Room opening a historical database fixture, and full recovery testing are tracked in the local `ROADMAP.md`.
