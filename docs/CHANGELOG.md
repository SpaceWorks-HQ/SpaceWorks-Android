# Phase notes

## A0 Base — foundation (2026-10-16)

Scaffolded Kotlin/Compose Android app with min SDK 26 and compile/target 36, generated semantic light/dark theme, `brand.json` identity/name/icon/server configuration, pinned official design submodule, owner contract snapshot and a build-time generated Retrofit/kotlinx.serialization client. Networking includes deployment validation, auth/makerspace headers, no cookies or browser headers, no redirect credential forwarding, structured errors/Retry-After, safe next-page URL resolution, and serialized refresh rotation with atomic in-memory pair replacement. No signed-in session can be created in A0; Keystore persistence/real sign-in belong to A1.

The connection screen checks the generated health operation before persisting a server, then opens Home with the sign-in placeholder and five empty tabs. Staff tools remain absent. A branded fixed server cannot be edited. All controls use Material touch targets, semantic labels and scalable platform typography; a Light/Dark toggle exercises both token schemes.

Parity checklist:
- [x] “Connect to your Space Works server”, “Server URL”, “Connect”; loading, validation and server-error states.
- [x] Health success enters Home; “Sign in” / “Sign-in will be available in the next phase.”
- [x] Home, Inventory, Activity, Inbox, Profile; “Nothing here yet.”; Staff hidden.
- [x] Shared API error details and special-code copy, HTTPS-only production, debug localhost/10.0.2.2 exception.
- [x] Light/dark semantic theme, font scaling, platform font pending Gilroy licence clearance.
- [x] Unit coverage for all connection state and networking behavior, brand inputs/token samples and control text contrast; Compose UI coverage for main flow and large-font validation.

Validation: local `./gradlew lint test assembleDebug assembleDebugAndroidTest` passes with Java 21. The debug and release variants each run 15 unit tests (30 executions, zero failures/errors/skips). [Ubuntu CI run 38908791188](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/actions/runs/38908791188) passed lint, unit tests, debug assembly and both Compose UI tests on API 35. Final revision checks are visible on [PR #1](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/pull/1/checks). The large-font UI test preserves the device density and doubles font scale.

The concurrent-auth fake-server test starts 12 generated health calls, holds a five-request stale-token wave and verifies exactly one generated refresh call followed by the new bearer token. Additional tests cover a disconnected rotation with no replay, cancelled caller completing atomic rotation, framework/field errors, Retry-After cooldown and same-origin pagination. Refresh uses an independent dispatcher to avoid starving behind waiting authenticated calls. Redirects and implicit connection retries are disabled, and raw transport/decoder text is never shown to users.

Owner inputs remaining for later phases: staging server/test accounts, support/privacy URLs, app-store/signing/Firebase/Integrity setup, Gilroy app-embedding permission. No A1 work starts before A0 is merged.

## Development branch workflow (2026-10-16)

The owner made the repository public and merged A0 through PR #1. `dev` starts from the merged `main` commit `8f8126bfe79bae686801c9ad565c722446bfa4fe`. Ongoing work is committed and pushed on `dev`; its final pull request targets `main` after validation. The workflow is recorded in `AGENTS.md` and the README.

After attribution was updated to the owner's GitHub identity, [push CI](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/actions/runs/38912584191) and [PR CI](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/actions/runs/38912591048) both passed on `7238889823937c0d577df22a5165b7711eae90ea`. The merged application source is unchanged by this documentation update.
