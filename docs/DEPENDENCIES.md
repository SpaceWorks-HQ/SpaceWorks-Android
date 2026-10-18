# Dependency and build provenance

This inventory describes the declarations in `build.gradle.kts`,
`app/build.gradle.kts` and the Gradle wrapper. It is not an SBOM, a complete list of
transitive licenses, a vulnerability clearance or a grant to redistribute the app
and supplied assets. Preserve the currently tested versions until a reviewed
maintenance phase validates a replacement.

## Tools and repositories

| Input | Declared version/source |
|---|---|
| Gradle | 8.13 binary distribution through the checked-in wrapper |
| Android Gradle Plugin | 8.13.0 |
| Kotlin Android / Compose / serialization plugins | 2.2.21 |
| OpenAPI Generator | 7.26.0; generated Kotlin Retrofit/serialization client |
| Google Services plugin | 4.5.0; applied only when private configuration exists |
| Build environment | JDK 21, Python 3, Android SDK 36; JVM target 17 |
| Dependency repositories | Google Maven and Maven Central; plugin portal for plugins |

The wrapper's `distributionSha256Sum` is
`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`, verified against
the [official 8.13 binary ZIP checksum](https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256)
and [Gradle checksum reference](https://gradle.org/release-checksums/). The wrapper
JAR is unchanged; CI's Gradle setup action also validates wrapper JARs.

## Direct SDK and test inputs

| Group | Exact declarations |
|---|---|
| Compose | BOM 2025.10.01; Material 3, Material icons core, UI test JUnit4/test manifest |
| AndroidX | Activity 1.11.0; Navigation 2.9.5; Lifecycle 2.9.4; Credentials and Play Services adapter 1.6.0 |
| Google identity / integrity | GoogleID 1.1.1; Play Integrity 1.6.0 |
| Firebase | BOM 34.4.0; Messaging |
| Camera / images / scans | CameraX core/camera2/lifecycle/view 1.5.3; ExifInterface 1.4.2; ML Kit barcode scanning 17.3.0 |
| Kotlin libraries | Coroutines Android/test 1.10.2; serialization JSON 1.9.0 |
| HTTP / serialization | Retrofit and serialization converter 2.11.0; OkHttp / MockWebServer 4.12.0 |
| Payments | Stripe Android 22.8.1; Razorpay Checkout 1.6.41; standard-core strictly 1.7.20 |
| Tests | JUnit 4.13.2; AndroidX test extension JUnit 1.3.0; Compose test modules use the same BOM |

The generator consumes this repository's authoritative OpenAPI input and reviewed
operation filter/templates. Providers retain their supported native behavior;
adding an SDK does not supply backend authority or deployment configuration.

Review the resolved POM/module metadata and licenses at the actual artifact
sources, not just this table: [Google Maven](https://maven.google.com/),
[Maven Central](https://central.sonatype.com/),
[AndroidX](https://android.googlesource.com/platform/frameworks/support/),
[Kotlin libraries](https://github.com/Kotlin),
[Square libraries](https://github.com/square),
[Firebase Android](https://github.com/firebase/firebase-android-sdk),
[Stripe Android](https://github.com/stripe/stripe-android), and
[Razorpay Android integration](https://razorpay.com/docs/payments/payment-gateway/android-integration/standard/).
Their licenses and applicable provider terms remain independent of the app's code
and brand assets. No root application-code license is declared in this repository;
do not infer an MIT/Apache grant from an individual dependency or the Design repo.

## Fonts and assets

Gilroy uses the owner-supplied four faces and explicit native weight mapping; retain
[its conversion and license distinction](licenses/Gilroy-native-fonts.md). Separate
owner confirmation of app-embedding/distribution rights remains a release input;
the old free Light/ExtraBold EULA does not license those four supplied faces.
Unbounded is limited to short accent copy. Its existing copyright and full OFL
text are copied unchanged into `app/src/main/assets/licenses/OFL-Unbounded.txt`;
the packaging gate requires those exact source bytes in each APK. See
[font provenance](licenses/FONT-PROVENANCE.md) and [the full OFL](licenses/OFL-Unbounded.txt).
Logo masters stay pinned to Design, and README screenshot provenance stays bound
to its recorded tested app commit. Do not rewrite third-party notices or invent
rights for replacement assets.

## Immutable CI action inputs

These are the original major refs resolved through each upstream repository's Git
API on 18 October 2026, peeling annotated tags to commits. No action major was
upgraded. The workflow pins the full commits; review upstream changes deliberately
before replacing them.

| Action | Original ref | Pinned upstream commit |
|---|---|---|
| actions/checkout | v4 | [11d5960a326750d5838078e36cf38b85af677262](https://github.com/actions/checkout/commit/11d5960a326750d5838078e36cf38b85af677262) |
| actions/setup-java | v4 | [cf277c60eb25467037889841efdb72551f06f6c3](https://github.com/actions/setup-java/commit/cf277c60eb25467037889841efdb72551f06f6c3) |
| android-actions/setup-android | v3 | [9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407](https://github.com/android-actions/setup-android/commit/9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407) |
| gradle/actions/setup-gradle | v4 (v4.4.3) | [ed408507eac070d1f99cc633dbcf757c94c7933a](https://github.com/gradle/actions/commit/ed408507eac070d1f99cc633dbcf757c94c7933a) |
| actions/upload-artifact | v4 | [ea165f8d65b6e75b540449e92b4886f43607fa02](https://github.com/actions/upload-artifact/commit/ea165f8d65b6e75b540449e92b4886f43607fa02) |
| reactivecircus/android-emulator-runner | v2 | [a421e43855164a8197daf9d8d40fe71c6996bb0d](https://github.com/ReactiveCircus/android-emulator-runner/commit/a421e43855164a8197daf9d8d40fe71c6996bb0d) |

## Maintenance and remaining reproducibility work

Direct versions/BOMs are fixed, but the project does not yet claim a fully locked,
independently checksum-reviewed transitive graph or byte-identical APKs across
different host environments. A finite follow-up must record the current resolved
Debug/Release/test graph, review exact metadata/artifact provenance, then enable
supported Gradle dependency locking and strict verification without silently
changing that graph. Do not treat generated bootstrap checksums as independent
trust evidence or bypass a checksum failure.

For an approved update, record the original and proposed graphs, upstream release
and license information, changed native libraries/permissions, and meaningful
tests. Re-run generator/catalog checks, lint, both unit variants, package/font
checks and the appropriate exact-source native/visual matrix. Preserve signed
release owner checks separately. Use phase commits on `dev` and the existing
authorized PR; do not add automatic dependency-update PRs under this delivery policy.
