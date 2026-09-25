# Interval Coach

A native, offline Android interval workout coach. Build a list of timed activities, then start a spoken session with a large countdown and notification controls.

## Build and test

Use JDK 17 and an installed Android SDK (API 37):

```sh
./gradlew testDebugUnitTest assembleDebug
./gradlew connectedDebugAndroidTest
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Structure

- `data/WorkoutStore.kt`: Room entities, ordered intervals, and workout operations.
- `session/SessionEngine.kt`: monotonic timing and deterministic cue rules.
- `session/SpeechCoach.kt`: offline Android TTS queue and transient ducking audio focus.
- `session/WorkoutService.kt`: active foreground session, recovery snapshot, wake lock, and notification actions.
- `MainActivity.kt`: Compose screens and Material You theme.

The service uses the documented `specialUse` foreground service type for user-started continuous interval timing and speech. This app reads no health sensors, so it does not meet the `health` type's prerequisites. The service's subtype is declared in the manifest. See [Android's foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) and [audio focus guidance](https://developer.android.com/media/optimize/audio-focus).

## Releases and updates

The app checks the public [GitHub releases page](https://github.com/LainsMain/IntervalCoach/releases) for the latest stable version. Workout data remains local and usable without a connection. When a newer APK exists, the app downloads it, checks GitHub's SHA-256 digest, confirms the package and version, and opens Android's installer. Android may ask the user to allow installs from Interval Coach first.

The v1 release APK is signed with the key stored outside this repository at `~/.config/intervalcoach/release.jks`; the matching settings are in `~/.config/intervalcoach/signing.properties`. Back up both securely. Future update APKs must use the same key and a larger `versionCode`. Set `INTERVAL_COACH_SIGNING_PROPERTIES` to use a different local settings file when building a release.
