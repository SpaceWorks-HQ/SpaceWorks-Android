# Contributing and maintenance

This is a native Kotlin/Jetpack Compose application. Read [AGENTS.md](AGENTS.md)
and the unchanged [PRD](docs/PRD.md) before changing behavior. The owner currently
authorizes the existing [PR #4](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/pull/4)
for final delivery: work on `dev`, preserve tested phase commits and `dev`, and
merge only the reviewed head after its required gates pass. Do not create other
pull requests or feature branches for this batch.

## Local checks

Use JDK 21, Python 3 and Android SDK platform 36/build tools. Clone recursively,
switch to `dev`, and retain the pinned Design submodule. Keep SDK paths in ignored
`local.properties` or `ANDROID_HOME`; never commit local credentials.

```sh
git submodule update --init --recursive
./gradlew lint test assembleDebug assembleRelease bundleRelease assembleDebugAndroidTest
python3 -m unittest discover -s scripts/tests -p 'test_native_packaging.py' -v
python3 scripts/check_native_packaging.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

Use `gradlew.bat` and `python` on Windows. Run `connectedDebugAndroidTest` with a
ready emulator/device for native journeys. The manually dispatched Android
workflow runs every case across the ten profiles in
[release readiness](docs/RELEASE-READINESS.md). Do not skip failing cases or infer
actual layout quality from compilation or a screenshot listing.

## Change boundaries

- Use only the generated API client and authoritative schema. Do not invent
  endpoints, models, production successes or backend behavior.
- Generated API, theme, typography and copy output belongs under build directories;
  change the reviewed inputs/generators instead. Keep `docs/PRD.md`,
  `api/openapi.json` and the Design gitlink unchanged unless explicitly authorized.
- Retain source-generation, unit/transport, lint, native packaging and relevant
  instrumented checks. Review original full-OS captures for affected phone/tablet,
  theme, orientation and text-scale states. Keep secrets and recovery codes out
  of logs, issues and evidence.
- Preserve the configured Shaan commit identity and required Codex attribution
  for Codex-authored commits. Record concrete behavior, tests and remaining owner
  gates; do not claim live backend/provider or store acceptance from demo tests.
- Dependency/toolchain changes require a separate reviewed phase and a resolved
  graph/provenance comparison; see [dependency maintenance](docs/DEPENDENCIES.md).
  Do not automatically update SDKs, generate update PRs or trust new checksum
  metadata without review.

The backend is deferred. Debug's labelled in-memory demo is isolated; Release
does not acquire production authority from it. Signing, legal/privacy inputs,
physical-device/provider verification and store distribution remain owner gates.

Report vulnerabilities through the [security policy](SECURITY.md), not a public
issue containing credentials or private account data.
