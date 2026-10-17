# Space Works Android

Native Kotlin/Compose app for Android 8+ (API 26), targeting API 36, for phones and tablets. [The PRD](docs/PRD.md) defines the flows; the owner's extensions in [AGENTS.md](AGENTS.md) add tablets, shared onboarding/auth UX and completion of the remaining phases on `dev`. [CHANGELOG.md](docs/CHANGELOG.md) records actual phase implementation, tests and parity. Only recorded passing phases count as complete.

## Development workflow

Consolidate ongoing mobile changes on `dev` and push tested commits to `origin/dev`. Do not create feature branches or pull requests. After exact-source CI, the full device matrix and actual screenshot review pass, merge tested `dev` directly to `main`.

## Build

Install Java 21, Python 3 and Android SDK 36. Clone with `--recurse-submodules`, set `ANDROID_HOME` or untracked `local.properties`, then run:

```sh
git submodule update --init --recursive
./gradlew lint test assembleDebug assembleRelease bundleRelease
./gradlew connectedDebugAndroidTest
```

Use `gradlew.bat` on Windows. Instrumented tests need an emulator/device. CI builds both variants, runs lint/unit tests, validates native-library and APK 16 KB alignment, and exercises phone/tablet journeys. Manually dispatch the workflow for the full ten-profile matrix: compact through large phones, 600/800/840/1024dp tablets, and tablet landscape. The tests record the app’s actual logical width, appearance, text scale and window orientation. Inspect actual display sizes, configuration and full-screen screenshots; the matrix does not represent every Android device. See [RELEASE-READINESS.md](docs/RELEASE-READINESS.md) for evidence and the separate 16 KB runtime-device requirement.

Open this repository in Android Studio and run `app`. Choose a server on launch or lock one HTTPS origin in `branding/brand.json`. Debug builds allow documented local Docker origins and an explicitly labelled, isolated in-memory demo. Demo authentication, notifications and checkout previews do not establish a live session, push delivery or payment. Release builds omit demo implementations and entry points. [LOCAL-DOCKER-VALIDATION.md](docs/LOCAL-DOCKER-VALIDATION.md) separates local API evidence from fixtures.

The backend and owner provisioning are deferred. Unsupported native contracts remain unavailable; see [backend dependencies](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/issues/3). Real integrity, providers, push, checkout, account deletion and store distribution require the deployment inputs and verification in the release guide. The Debug APK can be installed for development and the isolated preview. Play distribution uses an owner-signed release build; CI release outputs are unsigned.

Gradle regenerates native semantic colors and branding from the pinned Design submodule, motion from local `tokens/mobile-motion.json`, and the API client from the [contract](api/README.md). Generated sources stay out of Git. Never replace the pinned schema with a local server's schema or hand-write API payload models. See [WHITE-LABEL.md](docs/WHITE-LABEL.md) for configuration. Space Works uses static logo masters, supplied Gilroy native text and one short Unbounded accent per layout; font provenance and license metadata are retained in `docs/licenses/`. Never commit signing material, provider secrets or test credentials.

## Use and review the app

In Debug, choose **Explore demo** to use isolated member and staff journeys without a backend.
Open **Profile → App guide** for task instructions, introduction replay and helpful-tip reset.
The first-use Home tip is dismissible and scoped to the actual account and makerspace. Staff
guidance appears only in an authorized staff context. See [USER-GUIDE.md](docs/USER-GUIDE.md).
Prepared store copy, screenshot selection and private reviewer-access notes are in
[STORE-LISTING.md](docs/STORE-LISTING.md).

Record the final tested commit, successful dev-push/full-matrix runs and reviewed artifacts in the release handoff before merging to `main`. [PR #2](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/pull/2) is historical A8 evidence, not final proof of later changes.
Keep simulator/demo checks separate from live provider and physical-device acceptance; the
[release handoff](docs/RELEASE-READINESS.md) records those deployment inputs.
