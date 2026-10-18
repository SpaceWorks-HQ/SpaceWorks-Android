# Native backend contract (backend 1a–1d)

`api/openapi.json` is pinned to the mobile-backend snapshot SHA-256
`962e6a4b5b6927db0214c11e4b3b5ff688f660dccb8e674a7403b996cb1d8460` (760 paths). The backend's
`docs/mobile/MOBILE-APP-PRD.md` (v2) and `docs/mobile-integration.md` describe the rules the schema
cannot express. `docs/PRD.md` stays the byte-identical v1 owner copy; where the two differ, the
v2 text is read as the owner's update of the "planned (1a–1d)" items. Every call goes through the
generated client; `scripts/contract.py` lists each operation the app uses.

## What the app now does against the real contract

| Area | Behavior | Generated operations |
|---|---|---|
| Attestation | Debug emulator → `{"type":"development"}` (open-object arm). Every other build → Play Integrity Standard, `requestHash = base64url(SHA-256(challenge))`, sent as `{"type":"play_integrity","token":…}`. Without a configured Cloud project number, device sign-in is visibly unavailable. | attestation-challenge |
| Sign-in methods | Rendered from `PublicConfig.native.providers` when present (password, phone, Google, OIDC); legacy flags otherwise. Apple is iOS-only (its native audience binds to the iOS app ID). | config |
| Minimum version | Below `native.min_app_version.android` (dotted numeric compare) → non-dismissable "Update required" with Google Play and Try again. A stored session is kept and resumes after the update. | config |
| Password + MFA | Unchanged device login; enrollment-at-login, verify and recovery codes. | device login, mfa enroll/confirm/verify |
| Phone OTP | Member-only. Start with `delivery=device`, the attestation challenge and `client_platform=android`; confirm with the same challenge, a fresh proof and the six-digit code. Handles device tokens, MFA challenge and rejects completions without a device grant. 30 s local resend, server `Retry-After` honored. | phone login start/confirm |
| Google | Credential Manager with the server nonce; `serverClientId` = `native.providers.google.android_client_id`. | social nonce, social google |
| OIDC | Published `oidc:{slug}` providers: system browser, authorization code + PKCE S256 against the issuer's discovery document, redirect `{applicationId}:/oidc`, ID token sent to the attested device route. | social nonce, social oidc |
| Staff authority | Staff tools only when `session_authority == "native_staff"` **and** the selected membership has the action; `X-Makerspace-Id` is always the selected membership. | auth me |
| Sign out everywhere | Push cleanup, then `POST /auth/logout-all`; local session is cleared only after the server confirms. | logout-all |
| Account deletion | Preview with every blocker (server detail kept, unknown codes shown as-is, in-app fix links, organization fixes only to the deployment's own host), restore disclosure, reauthentication (password ± authenticator, phone OTP, Google with provider second factor), typed `DELETE`, fresh 409/403 blockers, `recent_reauth_required` → verify again, `deletion_retry` → refetch, proof expiry. Success wipes the local session. | account deletion GET/POST, reauth GET/POST, reauth phone start/confirm, reauth social start/complete/factor |
| Payments | `vendor`-discriminated intents: Stripe PaymentSheet or Razorpay Checkout (`amount_minor` passed unchanged). Each is validated before presentation; settlement only from the refetched payment list. | payments list, mobile-intent, documents |
| Push v2 + links | v2 `link` and verified App Links resolve the server link contract (`/m/{slug}/member[/tab]`, event tickets, events, bookings, items); custom-domain push forms use the hint's makerspace; unsupported links open Inbox. The App Link filter claims only those member paths. | push devices create/delete, inbox, preferences |
| Member usage | Home "Your activity": loans (borrowed/returned/out now + monthly), machine hours and top machines, bookings, events attended, paid/outstanding per currency. Absent sections stay hidden; series use the server's declared x field and first measure, with every value written out. Cleared on sign-out and makerspace switch. | my-usage |
| Staff dashboard | "Today at a glance" above the staff request tabs: server counters, handover `queue_tiles` (handover scope shows no analytics), outstanding per currency and report widgets with a single headline number. | admin dashboard |
| SMS/WhatsApp consent | Profile → "Text and WhatsApp messages", shown when the bootstrap's `messaging` reports a channel. One switch per channel with an On/Off word; opt-in needs a verified phone and an available, unsuppressed channel; opt-out is always allowed; refusals (409/423/503) show the server reason and refetch. | me/messaging GET, consent POST |
| Account restore | Sign-in → "Restore a deleted account" (when phone sign-in is published): phone login start with a device challenge, then code + new password through `restore/phone` with attestation. Never returns a session; the person signs in afresh. | phone login start, account restore phone |

## Owner inputs still required (not in the repository)

| Input | Where it goes |
|---|---|
| Play Integrity Cloud project **number** | `branding/brand.json` → `play_integrity_cloud_project_number`, or `-Pspaceworks.playIntegrityProject=` in the release environment. The server needs the Play Integrity service account and the app registration with the Play App Signing SHA-256 signers. |
| Firebase `google-services.json` | Ignored `app/google-services.json` for the exact application ID (the Google Services plugin applies only when present). Server needs the FCM service account. |
| Google OAuth audience | The value the server publishes as **"Google android client id"** is what Credential Manager sends as `serverClientId`; Google requires a **Web application** OAuth client there. Also register an Android OAuth client for the package + signing SHA-1 in the same Cloud project. |
| OIDC redirect URI | Register `{applicationId}:/oidc` (e.g. `org.spaceworks.app:/oidc`) as an allowed redirect for each enabled public OIDC client with PKCE. |
| Release signing | `SPACEWORKS_UPLOAD_KEYSTORE`, `SPACEWORKS_UPLOAD_KEYSTORE_PASSWORD`, `SPACEWORKS_UPLOAD_KEY_ALIAS`, `SPACEWORKS_UPLOAD_KEY_PASSWORD` environment variables; `-Pspaceworks.versionCode=` / `-Pspaceworks.versionName=` per upload. Keep `app/build/outputs/mapping/release/mapping.txt` with each upload. |
| App Links | A fixed HTTPS `server_url` in `brand.json`, and the server's `/.well-known/assetlinks.json` publishing the production signing SHA-256. |
| Payment providers | Stripe/Razorpay credentials per makerspace on the server; the app receives only runtime public values. |

## Backend contract issues found (documented, not worked around)

1. `DeviceAttestationPayload.type` is generated as a single-value enum (`play_integrity`); App Attest and development proofs must use the open-object arm. The v2 PRD acknowledges this.
2. `SocialLoginResponse` has no MFA-challenge arm although the v2 PRD says social sign-in can return one. Backend code marks provider sign-in as MFA-satisfied and always returns device tokens; the app treats social as tokens-only.
3. `POST /auth/mfa` still refuses device grants ("Enroll through browser sign-in"), so optional authenticator enrollment stays unavailable in the app (login-time enrollment works).
4. `POST /auth/social/providers` still accepts only `client_platform: web`; native provider linking stays unavailable.
5. Account sessions and exports list operations still declare no `page` query parameter, although responses are paginated.
6. `mobile-integration.md` calls the Google values "native OAuth client IDs"; on Android the published value must be the Web client used as `serverClientId` (see owner inputs).
7. There is no published native OIDC redirect-URI convention; the app uses `{applicationId}:/oidc`.
8. Every `Dashboard` counter declares `default: 0`, so the generated client cannot distinguish an omitted (unavailable) counter from zero, contrary to the v2 PRD. The app shows only non-zero counters.
9. `NullEnum` (`enum: [null]`, the null arm of report-chart `partition`) makes OpenAPI Generator 7.26 emit an empty enum; it is mapped to `JsonElement`.
10. Several optional URI fields are `oneOf[uri, maxLength 0]` inline unions that OpenAPI Generator 7.26 cannot serialize; they are mapped to `String` without changing the wire contract (see `GENERATED-CLIENT.md`).

## Not yet built

Email and social **account restore** stay on the web (the email landing page is browser-only; native social restore needs a fresh provider proof flow not yet built). The staff analytics report browser (`reports/catalog`, `analytics/{key}`) and the new ticket/calendar download routes are not implemented. Live integration against a deployed backend and real-device provider round trips (Play Integrity, FCM, Google/OIDC, Stripe/Razorpay, SMS) remain unverified.
