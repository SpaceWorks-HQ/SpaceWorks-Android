# Android release handoff

Prepared 16 October 2026. This procedure is not a claim of store submission. Phase implementation
and passing checks are recorded in `docs/CHANGELOG.md`. The owner requested phones/tablets and
deferred backend setup. The owner now requires all mobile work consolidated on `dev`, exact-source
checks and artifact/visual review, then a direct tested `dev` to `main` merge without a new pull request.

## Reproduce the app

Clone recursively and retain the reviewed `design/` commit. Install JDK 21, Python 3 and Android
SDK platform 36/build tools. `api/openapi.json` is the authoritative contract; do not replace it
with another checkout's schema. From the repository root:

```sh
./gradlew lint test assembleDebug assembleRelease bundleRelease
```

On Windows use `gradlew.bat`. SDK location belongs in the ignored `local.properties` or the build
environment. Gradle generates semantic colors, branding assets/motion values and the API client;
do not edit generated output. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.
The unsigned Release APK is under `app/build/outputs/apk/release/` and the unsigned bundle under
`app/build/outputs/bundle/release/`; these artifacts prove compilation/packaging, not Play
distribution readiness.

CI additionally validates every arm64/x86_64 native library's LOAD segments and runs the Android
SDK's `zipalign -c -P 16 4` on debug and release APKs. Evidence is in
`validation/native-packaging.json`. This verifies binary/ZIP alignment; a 16 KB runtime device
test remains separate. [Android page-size guidance](https://developer.android.com/guide/practices/page-sizes).

The `Android` GitHub Actions workflow runs build/lint/unit tests plus phone/tablet instrumented
tests on pushes/PRs. Manually dispatch the workflow on the final accepted commit for compact,
normal/large phone, tablet portrait/landscape and large tablet profiles. Preserve reports,
configuration, actual display dimensions, screenshots and APKs. Large text, dark mode and the
animation-disabled large-tablet profile extend the matrix. The display script fails if requested
dimensions were clamped; inspect actual evidence rather than claiming all phones were tested.
The full matrix retains every journey and uses these configurations. These are requested
profiles; the artifacts must confirm the actual app window, theme and inherited text scale.

| Profile | Portrait width / orientation | Text scale | Theme |
|---|---|---|---|
| compact | 320dp | 1× | Light |
| small-phone | 360dp | 1× | Light |
| common-phone | 393dp | 1× | Light |
| phone | approximately 411dp | 1× | Light |
| large | 480dp | 1.3× | Dark |
| small-tablet | 600dp | 1× | Light |
| tablet | 800dp | 1× | Light |
| wide-tablet | 840dp | 1× | Light |
| tablet-landscape | 1280dp landscape window | 2× | Dark |
| large-tablet | 1024dp | 2×, animations off | Light |

Dedicated journeys also exercise 3.2× component text and explicit dark/error states. App guide
tests cover expanded instructions, replay, scoped tip reset and actual Profile navigation.
`a8-observed-app-profile.json` and its Compose/full-OS images record observed appearance,
density, logical width, font scale and orientation. Preserve the workflow source SHA and all
per-profile reports rather than treating an adb configuration request as app-level proof.

Emulator UI/demo results do not prove Play Integrity, live FCM delivery, physical camera behavior
or real provider charges.

## Configure one deployment

`branding/brand.json` owns `app_name`, `android_application_id`, the icon, logo masters and theme
path. A null `server_url` allows server selection; a real HTTPS origin locks the deployment.
Support and privacy URLs must refer to the owner's live pages. See `docs/WHITE-LABEL.md` for
generator mechanics. Keep semantic color roles and supplied logo proportions/clear space; do not
recolor or animate a logo. Use bundled font assets only with the recorded embedding license.

Register the actual application ID in the owner's Play Console and enable Play App Signing.
Verify the final manifest, label, icon, versionCode/versionName, supported devices, HTTPS policy,
permissions and dependency manifest entries. The packaging gate reads the effective APK with
SDK `aapt2 dump badging`: no required camera/telephony feature may filter compatible devices,
any-camera must be explicitly optional, and small/normal/large/xlarge screens must be supported.
The app manifest also explicitly marks camera, autofocus and NFC optional. Camera tasks explain
missing hardware and retain manual scan entry.

Inspect transitive SDK permissions as well as the source manifest. The current merged Release
manifest includes INTERNET, CAMERA and POST_NOTIFICATIONS, plus SDK declarations for
USE_BIOMETRIC, USE_FINGERPRINT, NFC, ACCESS_NETWORK_STATE, WAKE_LOCK, c2dm.RECEIVE and
READ_BASIC_PHONE_STATE, and the app's signature-protected dynamic-receiver permission. Their
presence does not establish runtime collection or permission approval; review actual behavior
for the owner deployment. No AD_ID declaration was observed in this audited manifest. GLES 2
remains a required rendering capability.

Version values currently live in `app/build.gradle.kts`;
each uploaded build needs a fresh versionCode. Inject the owner's upload keystore and passwords
only in the private release environment. Never commit keys, credentials, Firebase configuration
or reviewer passwords. The checked-in application namespace is an internal code namespace;
the generated application ID controls deployment identity.

| Integration | Deployment input and verification |
|---|---|
| Play Integrity | Owner Cloud project number, linked Play app, real package and signing certificate identities; server app allowlist/proof contract. Exercise fresh challenge, opaque proof, refusal and sign-in on an installed owner test build. Debug emulator tests are not production integrity evidence. |
| Google | Registered Android package/certificate and WEB OAuth client ID for Credential Manager's server audience. The compiled SDK and an available web ID alone do not establish a supported native grant/social-MFA contract. Keep the build-tested dependency pins; do not bypass Kotlin metadata compatibility checks. |
| OIDC/Apple | Use the documented native/system browser callback and actual device-grant contract if enabled. Do not substitute a web cookie session or web-only audience. Provider buttons must match real deployment availability. |
| Firebase/FCM | Owner Firebase project/app matching the application ID, runtime Firebase initialization/config and server FCM credentials. Messaging auto-init is disabled in the manifest; do not register from demo/onboarding. Test permission denial, token change, logout deletion, foreground and cold-launch delivery. |
| App links | Owner publishes the actual Digital Asset Links file with the production app-signing SHA-256 certificate. App manifest and domain must agree; verify from outside the app and reauthorize the target before fetching protected data. A custom scheme is not verified App Links. |
| Camera/evidence | Camera remains optional hardware. Test first-use explanation, permission request/denial, manual entry, rotation, cancellation and explicitly confirmed photo upload. No background camera or scan-triggered mutation. |
| Payments | Server chooses vendor and supplies runtime public checkout input. Provider secrets stay on the server. Test configured sandbox success/cancel/failure, delayed webhook, process recreation and later status refresh; only the server can confirm paid status. |

The [backend dependency issue](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/issues/3)
tracks native configuration/attestation, staff authority, push v2/server link files, Razorpay
checkout fields and whole-account deletion. The owner deferred those services. Complete labelled
debug demo journeys are UI fixtures; they do not make a live provider configured or perform a
charge/deletion. A release must use supported generated operations and genuine authorization.
Account management has additional confirmed native limitations: current backend code refuses
optional authenticator enrollment from a device grant and provider linking from a native client.
Native login enrollment challenges remain supported. Account session/export list schemas omit
pagination parameters; later pages need a published contract. Do not impersonate a browser to
work around these gaps. Recovery-code replacement and supported MFA changes end old sessions;
a wrong management password must preserve a separately verified valid device session.

## Privacy and store content

Google Play Data safety answers must reflect actual production collection, sharing, security
and SDK behavior, including relevant third-party SDKs. The owner must verify the final binary,
dependency guidance and backend practice rather than infer “no collection” from the absence of
analytics. [Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469),
[Google SDK disclosure guidance](https://developers.google.com/android/guides/play-data-disclosure).

| Area to verify | Production question to resolve |
|---|---|
| Account/contact | Name, email, optional phone, account/membership identifiers: purpose, linkage, sharing and retention. |
| Device/security | Device grant, integrity and push identifiers: recipients, security use and deletion. |
| Camera/photos | Distinguish local QR recognition from uploaded evidence; verify access, retention and removal. |
| Activity/payments | Requests, loans, bookings, event attendance, payment metadata and retained accounting records; inspect each provider's processing. |
| Diagnostics | Verify transitive SDK defaults and server logging. App analytics was not authorized; SDK transmission still needs assessment. |

The account deletion flow and a functional, discoverable web deletion resource are release inputs
when the app falls within Play's account-creation requirement. A membership leave action, disabled
placeholder or debug success is not whole-account deletion. The deferred backend contract must
support the PRD's blockers, fix links, reauthentication, confirmation and explanation of anonymized
retained records. [Play account deletion guidance](https://support.google.com/googleplay/android-developer/answer/13327111).

Prepare the owner's real support/privacy/deletion URLs, name, short/full description, category,
content rating, phone/tablet screenshots, app-access instructions and reviewer account/makerspace.
Suggested short copy to adapt: “Tools, spaces and your makerspace activity, together.” Full copy:
“Find tools, request equipment, book a space and keep up with your makerspace. See loans, events,
tickets, payments and updates together in Space Works.” Describe only features working in the
submitted deployment. Put reviewer credentials in the private Play Console app-access section,
never this repository. Check current Play Console target/API and testing requirements at upload;
the app currently targets API 36 but this document does not assert future submission eligibility.

Concrete listing copy, screenshot capture order and private reviewer-access notes are prepared
in [STORE-LISTING.md](STORE-LISTING.md).

## Final acceptance and distribution

1. Record app/Design commits, schema hash, exact dependency versions, CI links and tested device
   profiles. Require all phase checks to pass without skipped required tests.
2. Inspect compact/large phone and tablet windows in light/dark, large text, landscape, split
   screen and animation-disabled settings. Verify TalkBack labels/order, keyboard/autofill,
   reachable controls, readable error states and static correctly sized logos.
3. Verify real backend sign-in/refresh, integrity, provider cancellation/revocation, push,
   physical camera, payment reconciliation, deletion and account/makerspace isolation on owner
   test devices. Keep these results separate from deterministic emulator fixture tests.
4. Configure release signing privately, build `./gradlew bundleRelease`, verify the signed AAB's
   package/certificate/version and keep it with the matching mapping file if minification is
   enabled. The expected bundle directory is `app/build/outputs/bundle/release/`.
5. Upload to the owner's Play Console internal/closed testing track, complete required console
   declarations and device checks, resolve tester feedback, then submit the accepted release.
   Do not treat an unsigned CI APK as an uploaded or publishable release.

Record distribution evidence after owner provisioning; do not put credentials in this ledger:

| Evidence | Value |
|---|---|
| Accepted app / Design commits | Record the exact tested `dev` SHA, pinned Design input and direct `dev` to `main` delivery in the final handoff, with matching CI artifacts |
| Full device-matrix CI run | The final handoff must link successful exact-source dev-push and ten-profile runs, observed profile evidence and actually reviewed full-OS images; fresh brand/motion validation remains required |
| Signed bundle / versionCode / certificate fingerprint | Owner signing environment required |
| Real-device provider test record | Owner backend/configuration required |
| Whole-account deletion and web resource verification | Updated owner contract/site required |
| Play test-track build / tester acceptance | Owner Play Console access required |

[PR #2](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/pull/2) records the historical A8 checkpoint. It does not substitute for final proof of later brand, SDK appearance, motion or accessibility corrections.
