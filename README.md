<p align="center">
  <img src="branding/nipper-fm-dog-logo.png" alt="Nipper FM logo" width="280">
</p>

# Nipper FM

Nipper FM is a native Android internet radio app. It finds stations through [Radio Browser](https://www.radio-browser.info/), plays streams directly from broadcasters, and shares one Media3 playback session across the phone, lock screen, home-screen widget, and Android Auto. Listening requires no account. The app inserts no ads.

## What you can do

- **Explore.** Search by station, city, callsign, or frequency where directory data supports it. Use voice search, browse genres, filter by country, or try **Surprise Me**. On launch, Explore preselects the country you're in, using approximate location or the mobile network country. The country shortlist ranks countries by recent clicks across a sample of the most-played stations, with **More countries** opening the full list.
- **Save stations.** Search and sort favorites by name, country, or recently played. Add favorites to Collections, return to the last station, and preview a custom HLS, Icecast, or Shoutcast URL before saving it.
- **See what's on air.** When a broadcaster sends ICY or ID3 metadata, Now Playing, the mini player, the notification, and Android Auto show the artist and song. Cover art comes from the station's `StreamUrl` when it sends one. Otherwise Nipper FM searches the iTunes catalog by artist and title. Turn off **Look up album artwork** in **Playback & Audio** to stop those lookups.
- **Find tracks again.** Recently On Air logs track titles when a broadcaster supplies them. Save individual tracks and copy track information.
- **Control playback.** Use Wi-Fi-only mode with a one-time mobile-data override, choose a buffer profile, switch stream sources, or adjust each station's volume trim. On seekable live streams, rewind 30 seconds or to the start of a shorter window, then use **Go live** to catch up. Settings shows monthly mobile and Wi-Fi streaming totals, with the hourly estimate below **This month**, and can export a playback-error log. Enable headphone or Bluetooth resumption in **Playback & Audio** to resume after reconnecting an audio device.
- **Set a sleep timer.** Choose a duration on the horizontal slider or stop after the current track when track titles are available. Use **Turn off sleep timer** to cancel an active timer. The lock-screen moon indicates the timer's state and opens setup without changing it. If Android blocks the direct launch, tap the **Open sleep timer setup** notification when notifications are enabled.
- **Schedule listening.** Create, edit, or delete recurring station routines in the **Scheduled Listening** section of Settings. Wake-up routines start quietly, ramp up the volume, and use the device's alarm sound if the stream fails.
- **Adjust sound and appearance.** Swipe through global EQ presets or assign a preset to one station. Choose spatial audio and loudness enhancement where supported, and customize the theme and accent. Tablet and landscape displays can show browsing and Now Playing side by side.
- **Listen in the car or from the launcher.** Manage up to six ordered Android Auto presets in Favorites. In the car, presets and playlists show as station-logo tiles, and each Collection appears under **Playlists**. In Settings → **Android Auto**, choose what the steering-wheel Play button starts: the last station, the presets, all favorites, or one Collection. Next and previous then move through that list. Pin station shortcuts from Now Playing or set up to three presets independently for each resizable home-screen widget. Use the Quick Settings tile to return to the last station.
- **About Nipper FM.** Open the About page from Settings to see the app logo and an introduction.
- **Back up your setup.** Export favorites, collections, car presets, custom streams, and appearance as JSON. Preview an import before merging or replacing local data.

## Install

<p align="center">
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/GChavez0210/Nipper-FM">
    <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="80">
  </a>
</p>

[Obtainium](https://github.com/ImranR98/Obtainium) installs the latest release from GitHub and checks for updates. Open the link above on your Android device with Obtainium installed, then tap **Add**.

> [!NOTE]
> To install without Obtainium, open the [latest release](https://github.com/GChavez0210/Nipper-FM/releases/latest), download the `.apk` file under **Assets**, and open it on your device. Android may ask you to allow installs from your browser or file manager. Earlier versions are on the [releases page](https://github.com/GChavez0210/Nipper-FM/releases).

## Build and install

The project uses Kotlin, Compose Material 3, Media3, Room, DataStore, Retrofit, OkHttp, kotlinx.serialization, and Coil. It has one Android module, `:app`, with application ID `app.nipperfm`. The minimum Android version is API 26. The project targets API 36 and compiles with API 37.

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

## Platform and release notes

- Some broadcasters serve plain HTTP. The app accepts those direct stream URLs and uses HTTPS for Radio Browser.
- EQ and spatial audio depend on the device's audio effects. The loudness toggle applies a fixed makeup gain. The spectrum is hidden when live FFT capture is unavailable, and this build does not request microphone access.
- Publishing a GitHub release runs `.github/workflows/release.yml`, which builds a signed APK and attaches it to the release. The release tag must match `versionName` (for example `v0.5.0`). Add these repository secrets first: `NIPPERFM_KEYSTORE_BASE64` (`base64 -w0 your.jks`), `NIPPERFM_STORE_PASSWORD`, `NIPPERFM_KEY_ALIAS`, and `NIPPERFM_KEY_PASSWORD`.
- Build the R8 release APK and app bundle with `./gradlew :app:assembleRelease :app:bundleRelease`. Set `NIPPERFM_KEYSTORE`, `NIPPERFM_STORE_PASSWORD`, `NIPPERFM_KEY_ALIAS`, and `NIPPERFM_KEY_PASSWORD` in the build environment to sign both artifacts with your distribution key. Omit all four variables for an unsigned source build. [F-Droid inclusion requirements](https://f-droid.org/docs/Inclusion_Policy/)
- Scheduled listening uses Android's Alarms & reminders access. Wake-up fallback also requires notifications so it can show a Stop action. Routines skip when another station is playing or Android Auto is connected.
- Sideloaded builds appear in Android Auto only after enabling **Unknown sources** in Android Auto's developer settings.
- CI runs unit tests and lint on every push. Hardware and in-car verification is tracked in the local `ROADMAP.md`.

## Support Nipper FM

<p align="center">
  <a href="https://buymeacoffee.com/gchavez0210">
    <img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me a Coffee" width="220">
  </a>
</p>

Your support helps fund ongoing development and testing.
