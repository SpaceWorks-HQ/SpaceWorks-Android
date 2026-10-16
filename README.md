# Space Works Android

Native Kotlin/Compose app implementing phase A0 of [the PRD](docs/PRD.md). Later phases require the preceding phase to be merged.

## Build

Install Java 21, Python 3 and Android SDK 36. Clone with `--recurse-submodules`, set `ANDROID_HOME` or untracked `local.properties`, then run:

```sh
./gradlew lint test assembleDebug
./gradlew connectedDebugAndroidTest
```

The second command needs an Android emulator/device. CI runs the first on Ubuntu and the Compose UI flow on an API 35 emulator. Open the repository in Android Studio and run `app` for server setup. HTTPS is required except debug-only localhost/10.0.2.2. No staging server or credentials are bundled.

Gradle regenerates native semantic colors, icon and the A0 API client before compilation. See [contract provenance](api/README.md), [white-label steps](docs/WHITE-LABEL.md), and [phase/parity notes](docs/CHANGELOG.md). The app currently connects to a server and shows placeholders; signing in and actual inventory arrive in later phases.
