# Space Works Android

Native Kotlin/Compose app for Android 8+ (API 26), targeting API 36, for phones and tablets. [The PRD](docs/PRD.md) defines the flows; the owner's extensions in [AGENTS.md](AGENTS.md) add tablets, shared onboarding/auth UX and completion of the remaining phases on `dev`. [CHANGELOG.md](docs/CHANGELOG.md) records actual phase implementation, tests and parity. Only recorded passing phases count as complete.

## Development workflow

Use `dev` for ongoing changes and push commits to `origin/dev`. After implementation and CI validation finish, open the final pull request from `dev` to `main`. The A0 foundation is already merged into `main`.

## Build

Install Java 21, Python 3 and Android SDK 36. Clone with `--recurse-submodules`, set `ANDROID_HOME` or untracked `local.properties`, then run:

```sh
git submodule update --init --recursive
./gradlew lint test assembleDebug assembleRelease bundleRelease
./gradlew connectedDebugAndroidTest
```

Use `gradlew.bat` on Windows. Instrumented tests need an emulator/device. CI builds both variants, runs lint/unit tests, validates native-library and APK 16 KB alignment, and exercises phone/tablet journeys. Manually dispatch the workflow for all configured device profiles. Inspect actual display sizes, configuration and full-screen screenshots; the matrix does not represent every Android device. See [RELEASE-READINESS.md](docs/RELEASE-READINESS.md) for evidence and the separate 16 KB runtime-device requirement.

Open this repository in Android Studio and run `app`. Choose a server on launch or lock one HTTPS origin in `branding/brand.json`. Debug builds allow documented local Docker origins and an explicitly labelled, isolated in-memory demo. Demo authentication, notifications and checkout previews do not establish a live session, push delivery or payment. Release builds omit demo implementations and entry points. [LOCAL-DOCKER-VALIDATION.md](docs/LOCAL-DOCKER-VALIDATION.md) separates local API evidence from fixtures.

The backend and owner provisioning are deferred. Unsupported native contracts remain unavailable; see [backend dependencies](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/issues/3). Real integrity, providers, push, checkout, account deletion and store distribution require the deployment inputs and verification in the release guide. Debug APKs and unsigned release outputs need the owner's signing setup for distribution.

Gradle regenerates native semantic colors and branding from the pinned Design submodule, motion from local `tokens/mobile-motion.json`, and the API client from the [contract](api/README.md). Generated sources stay out of Git. Never replace the pinned schema with a local server's schema or hand-write API payload models. See [WHITE-LABEL.md](docs/WHITE-LABEL.md) for configuration. Space Works uses static logo masters, native system text and licensed Unbounded accent text. Gilroy is not embedded without app-embedding permission. Never commit signing material, provider secrets or test credentials.
