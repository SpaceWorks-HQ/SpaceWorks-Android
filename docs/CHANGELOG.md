# Phase notes

## A0 Base — implementation under validation (2026-10-16)

Scaffolded Kotlin/Compose Android app with min SDK 26 and compile/target 36, generated semantic light/dark theme, `brand.json` identity/name/icon/server configuration, pinned official design submodule, owner contract snapshot and a build-time generated Retrofit/kotlinx.serialization client. Networking includes deployment validation, auth/makerspace headers, no cookies or browser headers, no redirect credential forwarding, structured errors/Retry-After, safe next-page URL resolution, and serialized refresh rotation with atomic in-memory pair replacement. No signed-in session can be created in A0; Keystore persistence/real sign-in belong to A1.

The connection screen checks the generated health operation before persisting a server, then opens Home with the sign-in placeholder and five empty tabs. Staff tools remain absent. A branded fixed server cannot be edited. All controls use Material touch targets, semantic labels and scalable platform typography; a Light/Dark toggle exercises both token schemes.

Parity checklist:
- [x] “Connect to your Space Works server”, “Server URL”, “Connect”; loading, validation and server-error states.
- [x] Health success enters Home; “Sign in” / “Sign-in will be available in the next phase.”
- [x] Home, Inventory, Activity, Inbox, Profile; “Nothing here yet.”; Staff hidden.
- [x] Shared API error details and special-code copy, HTTPS-only production, debug localhost/10.0.2.2 exception.
- [x] Light/dark semantic theme, font scaling, platform font pending Gilroy licence clearance.
- [ ] Validation results and phase acceptance recorded after all CI checks pass.

Owner inputs remaining for later phases: staging server/test accounts, support/privacy URLs, app-store/signing/Firebase/Integrity setup, Gilroy app-embedding permission. No A1 work starts before A0 is merged.
