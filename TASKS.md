# IntervalCoach implementation

- [x] Inspect repository, tooling, and current Android guidance.
- [x] Create Android project, Material You theme, Room model and repository.
- [x] Implement monotonic session engine and deterministic cue tests.
- [x] Implement TTS coach, audio focus, foreground service, notification, and recovery.
- [x] Build Compose home, editor, interval sheet, and active workout screens.
- [x] Test Room ordering and disk reopen; build and run on Android emulator.
- [x] Review UI in light/dark themes and large text; validate service and notification controls; fix issues.

Physical phone checks still needed for audible cues, audio ducking with another app, lock-screen behavior, and drag feel. The available USB device is a Quest 2; phone UI checks used the API 36.1 emulator.

# Public v1 release and in-app updates

- [x] Add GitHub latest-release check and version comparison.
- [x] Download and verify release APK, then open Android's install flow.
- [x] Add focused update UI and test parsing/version decisions.
- [ ] Build and sign the v1 release APK; verify it installs.
- [ ] Initialize Git, publish public GitHub repository, tag v1.0.0, upload APK.
- [ ] Validate published release endpoint and in-app check.
