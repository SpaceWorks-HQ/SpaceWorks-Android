# Space Works mobile: onboarding, authentication, and first use

Implementation specification, 16 October 2026. Maintained in the mobile repositories at the owner's request; the Design submodule supplies existing brand assets only. Applies to the native Android and iOS apps, including tablets and resizable windows.

## 1. Delivery and authority

The owner requested a complete, polished onboarding/authentication experience in the Space Works brand, including research and implementation in both apps. Implement this as a dedicated UX/onboarding checkpoint after the current A1 checkpoint passes; preserve the already frozen A1 verification while that work finishes. This document is an implementation input, not evidence that the apps implement it. Each platform must deliver the screens, state transitions, tests, and screenshot evidence below before declaring the UX checkpoint complete.

The original mobile PRD remains the scope owner: `SpaceWorks/docs/mobile/MOBILE-APP-PRD.md`. The owner has deferred backend work. Build complete, explicitly labelled debug/demo journeys for unavailable contracts; production must continue to use generated operations and fail safely where native authorization is unavailable. Do not create new routes, payload fields, access grants, successful payments, or deletion results to make screens appear complete.

Contract reference: the apps' `api/openapi.json`, SHA-256 `d5ec904eb2a52ca948ee38dae7840c8859a43e034a22f3b8dc478fd8851cbb9b`. Paths below include `/api/v1`, as do the generated operations. Configure the server origin without duplicating that prefix. A different schema in a local backend worktree is not a replacement contract.

Brand inputs: `design/tokens/tokens.json` is authoritative for color; `tokens/mobile-motion.json` is the shared machine-readable mobile motion contract generated into each app; `design/docs/ui-brand-spec.md` supplies the logo, spacing, motion provenance and voice; `design/docs/ux-yuyu-spec.md` and `design/docs/ux-screens-longtail.md` supply task-based layouts and states; `design/brand/space-works-brand-guidelines-v1.pdf` and its supplied page exports support visual verification. System fonts remain the mobile default until an app-embedding license for Gilroy is confirmed. The supplied WOFF files do not establish that license.

## 2. Shared visual language

Use a warm, quiet canvas, flat bordered panels, clear labels, and one primary action per task. Keep the brand recognizable through the exact masters, colors, spacing, and voice rather than decorative effects. Use native SF Symbols/Material icons consistently at a 24-unit drawing size, with labels for unfamiliar actions. Do not generate raster art or redraw the logo. Product illustrations may be simple native vector cards, tool silhouettes, calendar tiles, or a loan list, clearly illustrative rather than purported live activity.

### Color roles

Generate theme values from the JSON; this table records the current values for review and does not create a second token source. Some older prose in the brand document has stale dark colors.

| Role | Light | Dark | Application |
|---|---|---|---|
| `bg` | `#FFF4E8` | `#142C2A` | Whole canvas |
| `surface` | `#F6ECE0` | `#193935` | Quiet supporting regions |
| `panel` | `#FFFFFF` | `#1F433F` | Form and task cards |
| `ink` | `#142C2A` | `#FFF4E8` | Main copy |
| `muted` | `#5B6863` | `#C7C4BA` | Supporting copy |
| `line` | `#D5D0C6` | `#4D6D67` | Decorative dividers |
| `outline` | `#828A83` | `#93A198` | Input boundaries |
| `accent` / `on-accent` | `#006E63` / `#FFF4E8` | Same pair | Filled primary buttons |
| `accent-ink` | `#006E63` | `#ABC8BC` | Links and textual accents |
| `focus` | `#006E63` | `#DFFF8F` | Keyboard and accessibility focus |
| `danger` | `#B91C1C` | `#FCA5A5` | Error text and destructive actions |
| `success-soft` / `success-ink` | `#FBFFF2` / `#536D49` | `#2C5045` / `#DFFF8F` | Success callouts with a word/icon |

Dark `accent` on the dark canvas is not an accessible text pair; use `accent-ink` for links. Never use orange text on cream. Status always has a word or icon as well as color. Keep Light and Dark as the two explicit theme choices, matching the existing Design owner rule; persist the preference. Tenant branding must use the approved derived theme and preserve contrast, not overwrite semantic danger/success colors.

Use a 4-unit spacing grid: 4, 8, 12, 16, 20, 24, 32, 40, 48, 64. Inputs and buttons have radius 10; cards 16. Cards have a 1-unit border and no shadow. Use 48-unit minimum control/touch dimensions on both platforms (this also exceeds the iOS 44pt floor). Inline text links have a sufficiently padded hit region without making adjacent targets overlap. Body text defaults to the platform's readable body size, scales with accessibility settings, and is never shrunk to preserve a layout. Headings are semibold/bold system type, not all-caps marketing copy. Codes and IDs use the UI family with tabular digits, not a new monospace family.

### Logo application and correction

Use the supplied vector/master artwork with its intrinsic aspect ratio and its existing internal symbol/wordmark geometry. Artwork on cream is full color; on white use the one-colour ink master; on Ink or Teal use the reversed cream master. A logo is one accessibility element labelled “Space Works”; hide decorative duplicates from accessibility.

| Placement | Exact practical choice | Clear space outside artwork |
|---|---|---|
| Phone header, narrow task screen | Dedicated `symbol` master at height 48, intrinsic width about 51.8 | 12 on every side; header region at least 72 high |
| Phone welcome/sign-in, normal available height | `lockup-stacked` master at width 180, intrinsic height 64 | 16 on every side |
| Phone landscape/keyboard/large text | Switch to symbol header above; do not shrink the master | 12 on every side |
| Spacious tablet auth brand panel | Stacked master width 240–320, intrinsic height about 85–114 | One quarter of symbol height on every side, about 22–29 |
| Wide horizontal header, only if space permits | `lockup-horizontal` at height at least 48; supplied 360×58 master needs width about 298 at that height | At least 12 on every side |
| Installed app icon / OS launch icon | Supplied `app-icon` artwork, respecting each platform's icon masks and launch API | Use the supplied tile; do not create a replacement tile inside the sign-in form |

The stacked primary lockup minimum is 180 wide; the symbol minimum is 48 high. A horizontal lockup squeezed into 180×64 is only about 29 high and fails the symbol minimum. Fix the current iOS compact auth wordmark choice accordingly. Android auth should use the dedicated symbol/lockup, not the rounded app-icon tile. The masters are outlined; they do not require a licensed font to render.

The master and every containing view that visually affects it stay static and fully opaque. No transform, scale, rotation, translation, fill override, filter, opacity fade, mask reveal, shadow, spring bounce, stretching, or panel recolor. Animate sibling content only. This includes transitions: do not put the logo inside an animated/fading page container. The OS-controlled splash behavior is platform-owned; do not add a second animated logo splash in app code.

## 3. Entry flow and persistence

The state graph is: OS launch → resolve local settings/session → optional first-run introduction → server selection/health check → configured sign-in → required authentication gates → makerspace selection → Home. A valid returning session bypasses the introduction and sign-in. A pending protected deep link is retained as an opaque intent and resolved only after authentication, current membership, and fresh API authorization. Never show protected content for a frame while restoring credentials.

Persist introduction completion/skip per installation, theme choice, validated server origin, and last valid makerspace selection under the session's account/server scope. Logout does not repeat the product introduction. Help → “Show introduction” replays it. Do not persist passwords, OTPs, MFA setup secrets, recovery codes, provider ID tokens, or pending secret reset links in ordinary preferences, state restoration, logs, screenshots, or analytics. Secure token storage follows the PRD. Account/server changes clear all account-scoped navigation, cached data, carts, QR codes, and pending operations before loading another account. Do not offer multiple stored accounts unless secure multi-account storage exists; “Use another account” can sign out the current account and start fresh.

### Launch and introduction

Keep OS launch artwork static. Restore state immediately; do not impose a minimum splash duration or wait for an animation. If restoration needs visible time, show a quiet content loading state below the static brand header. Keep “Change server” available after a recoverable server failure. First run does not request notifications, camera, location, photo-library access, or payment setup.

The optional introduction has three manually advanced pages, with visible “Skip introduction” throughout, Back after page one, and no automatic paging. Show a readable “1 of 3” equivalent to assistive technology; page dots alone are insufficient. Swiping is optional, never the only navigation. Keep controls in stable positions while copy wraps.

| Page | Heading | Supporting copy | Illustration | Primary action |
|---|---|---|---|---|
| 1 | Make room. | Find tools, book a space, and see what's next at your makerspace. | Quiet vector tool/shelf cards | Next |
| 2 | Borrow with confidence. | Request what you need and keep an eye on your loans and due dates. | Illustrative request and due-date cards | Next |
| 3 | One place for your making. | Keep bookings, event tickets, payments, and updates together. | Simple calendar, ticket, and inbox tiles | Get started |

Use a citron or orange accent in one illustrative region per page; keep text on tested ink/neutral pairs. Illustrative cards must not show a fabricated user name, unread count, transaction, loan, or membership. Start useful content without onboarding if the owner build disables the introduction. Store permission explanations are contextual later in the journey. This follows Apple's recommendation for short, optional onboarding and contextual guidance: [Apple onboarding](https://developer.apple.com/design/human-interface-guidelines/onboarding).

## 4. Screen and state specification

Every task screen uses the same static brand header, a heading, one short explanation, a bounded form/task card, its primary action, and secondary navigation. Provider buttons appear before password fields when available; show “or sign in with a password” only when both methods are actually available. No Member/Staff role selector: server authority determines access after sign-in.

| Screen/state | Copy and controls | Transition and guard |
|---|---|---|
| Server selection | “Connect to your makerspace”; “Enter the server address provided by your makerspace.” Label “Server address”; primary “Connect”; supporting current white-label default if supplied | Parse/validate the PRD URL rules, run the generated health/config flow, and save only a successful normalized origin. Permit only the PRD's explicit debug exception to HTTPS. Do not silently switch origins or reuse another origin's credentials. |
| Server loading/failure | “Connecting…”; then “Couldn't connect to this server. Check the address and try again.” Actions “Try again”, “Edit address” | Keep entered address and keyboard position; failures do not become a healthy saved server. Config failure is a retry state, not guessed provider availability. |
| Sign in | “Welcome back.”; “Sign in to your makerspace account.” Labels “Email or username”, “Password”; “Forgot password?” on password-label row; primary “Sign in”; footer “New here?” → “Create account” only when self-registration is allowed | Password method respects config. Username may be a username; do not force email syntax. Show chosen server name/origin and “Change server”. No secret body/error response is printed in UI. |
| Sign-in submitting | “Signing in…” in the same button bounds, accessible progress announcement | Disable duplicate submissions and conflicting provider/account/server actions while the atomic native grant is being obtained/stored. A cancel/back action must have safe cleanup; stale callbacks cannot navigate or write credentials. |
| No available sign-in method | “Sign-in isn't available here”; “Ask your makerspace for help, or check the server address.” Actions “Change server”, “Try again” | Do not render pretend enabled providers or a browser cookie workaround. |
| Create account | “Create your account”; “Use the email you want to use with your makerspace.” Labels “Name”, “Email”, “Password”, “Confirm password”; optional “Phone number (optional)” under disclosure; primary “Create account”; secondary “Already have an account? Sign in” | Generated `MemberSignUp`. Do not ask users for role, makerspace admin access, or website. `website` remains the schema's empty default/honeypot. Success is acknowledgement, not an authenticated session. |
| Signup acknowledgement | “Account created”; “Sign in to continue. You may need to confirm your email.” Primary “Go to sign in” | Preserve email as a non-secret suggestion, clear passwords, then use normal native login. Do not turn the acknowledgement into access/refresh tokens. Do not promise email delivery if it is disabled. |
| Confirm email | “Confirm your email”; “Enter the six-digit code sent to your email.” Label “Verification code”; primary “Confirm email”; “Send another code”; “Use another account” | Only in an authenticated/restricted verification gate supported by server state. Show actual account email if known, not guessed. Re-fetch account after success; a restricted session does not unlock normal tabs. Handle disabled email delivery with help copy rather than a fake send. |
| Forgot password: request | “Reset password”; “Enter your account email to request a six-digit recovery code.” Label “Email”; primary “Request recovery code”; “Back to sign in” | Generated forgot-password. Acknowledgement is uniform regardless of account existence. |
| Forgot password: confirm | “Enter your recovery code”; “If an account matches this email, a recovery code will arrive. Delivery may take a few minutes.” Fields “Six-digit code”, “New password”, “Confirm password”; primary “Update password”; “Request another code”; “Change email” | Six digits plus new-password checks. Resend uses the same generated request. All reset-confirm server rejections use the identical message below, regardless of whether OTP, account, or policy caused rejection. |
| Reset rejection | “Invalid or expired verification code.” | Do not reveal server password-policy details conditionally after a valid OTP. Local checks independent of server validity may still explain minimum length/mismatch. Preserve email/new-password editing state in memory; never log submitted code/password. |
| Reset success | “Password updated”; “Your password has been updated. You can now sign in.” Primary “Go to sign in” | Clear code and passwords, return to native sign-in. Reset response does not establish a session. |
| Recovery unavailable | “Password recovery unavailable”; “Contact your makerspace staff to reset your password.” Primary “Back to sign in” | Only a known `recovery_unavailable` response enters this state; a transient failure stays retryable. |
| Temporary-password gate | “Choose a new password”; “Replace your temporary password to continue.” Fields “Current password”, “New password”, “Confirm password”; primary “Update password”; secondary “Sign out” | Block app tabs until generated change-password succeeds and refreshed `/auth/me` confirms the gate is cleared. Response is detail only; do not invent tokens. Never prefill the old password from saved plaintext. |
| MFA enrollment | “Add extra security”; “Add this account to your authenticator app, then enter its six-digit code.” Server-provided setup QR; “Can't scan the code?” reveals the setup key with deliberate copy action; primary “Verify and continue” | Enrollment challenge is not an authenticated session. QR/setup key are secrets, shown only while this screen is active. No automatic external URL launch from QR content. Manual key is essential for single-device use. |
| MFA verification | “Check your authenticator”; “Enter the six-digit code from your authenticator app.” Label “Authentication code”; primary “Verify”; secondary “Use a recovery code”; “Back to sign in” | Submit only for the active unexpired challenge; prevent overlapping verification/enrollment tasks. Do not request SMS or label authenticator verification as an email code. |
| MFA recovery entry | “Use a recovery code”; “Enter one of the recovery codes you saved when you set up extra security.” Label “Recovery code”; primary “Verify”; secondary “Use authenticator instead” | Accept the actual server format up to the schema's 64-character maximum; do not restrict this field to six digits or silently strip punctuation. Same active challenge; code use is server-authoritative. |
| MFA challenge expired | “Sign in again”; “Your verification session expired. Sign in to request a new one.” Primary “Back to sign in” | Clear challenge/setup secret/code/recovery list and pending SDK results; do not retry it via the access-token refresher. A background/resume recomputes expiry from server timing. |
| MFA recovery codes | “Save your recovery codes”; “Keep these somewhere safe. You can use each code once if you lose access to your authenticator.” Deliberate copy/export if implemented securely; checkbox “I've saved my recovery codes”; primary “Continue” | Display only real returned codes, not a fabricated list. Require acknowledgement. Do not silently store in preferences or copy without action. Explain exported files contain account access secrets. Clear view state on exit. |
| Security settings | “Sign-in and security”; show actual password/MFA/linked-provider status; actions “Change password”, “Set up extra security”, “Get new recovery codes”, “Turn off extra security”, and supported link/unlink methods | These are distinct authenticated server flows, not local switches. Reauthentication uses the exact supported method; password-only protected operations cannot accept an invented social proof. Explain that regenerating codes replaces the old set; confirm before disable/unlink. A server refusal to remove the last sign-in method stays a refusal. Phone verification/linking is not automatically native phone login availability. |
| Select makerspace: several | “Choose your makerspace”; cards with name and permitted membership label; primary on selected row “Continue”; optional search when list is long | Use eligible memberships from current account response; organization-only authority does not confer mobile staff access. Scope all subsequent requests to the chosen space. |
| Select makerspace: one | Load that eligible space directly | Do not make returning users reconfirm a single choice; offer Profile → “Switch makerspace” if multiple become available. |
| No eligible makerspace | “No makerspace access yet”; “Ask your makerspace to add or reactivate your membership.” Actions “Refresh access”, “Use another account”, “Sign out” | An authenticated account without authorized membership must not see another account's cache or an invented join success. Offer an actual supported invitation/join action only when the real flow exists. |
| Session revoked/expired | “Sign in again”; “Your session ended. Sign in to continue.” Primary “Sign in” | Wipe the invalid local session and protected caches. Preserve server preference; discard protected pending mutations. Reauthorize pending links after sign-in. Do not call a network timeout a revoked session. |
| Superadmin refusal | “Use the web console”; “This account needs to use the web console.” Primary “Back to sign in” | Show the PRD refusal only on the server refusal/known authority condition, not guessed role strings. Open only a configured trusted console URL if a secondary web action is supplied. |
| Minimum-version gate | “Update to continue”; “This version of the app needs an update to connect.” Primary “Update app” | Only from authoritative version configuration when available; trusted store URL, no arbitrary server-provided executable. Present fully in demo; do not invent a production config field. |
| Sign out | Confirmation title “Sign out?”; “You'll need to sign in again to use this account.” Primary destructive “Sign out”, secondary “Cancel” | Revoke via supported generated flow when possible; always clear local access on explicit sign-out, including offline. Stop device/token association attempts. Clear provider credential state and caches. Do not claim all other devices were signed out. |
| Use another account/server | Explain that continuing signs out this account; “Continue”, “Cancel” | Complete the same local cleanup before returning to sign-in/server. Do not save the old password or silently select the prior Google account. |

Do not add terms/privacy acceptance checkboxes unless the configured legal flow requires them. Display configured legal links as ordinary accessible links; do not invent legal promises or placeholder URLs. Display “Space Works” in prose, and use a configured product/makerspace name only where white-label configuration explicitly supplies it.

## 5. Fields, focus, keyboards, and submission

Use real labelled native fields, not a drawing of a text field. Labels remain visible after entry; examples are placeholders, not labels. Helpers and errors belong beneath their field and are programmatically associated. On submit, announce the first invalid field and focus/scroll it into view without repeatedly stealing focus while typing. Validate required fields on submit and invalid touched fields on blur; do not show a page of errors on first appearance. Preserve correct entries after a failure. Errors have a word/icon and color, never color alone.

| Input | Native behavior | Rules |
|---|---|---|
| Username/email login | No capitalization/autocorrect; username/password-manager semantics; Next → password | Username is not always an email. Trim surrounding identifier whitespace only if consistent with the generated gateway/server normalization. Never trim password. |
| Signup/recovery email | Email keyboard, email content type, no capitalization/autocorrect | Email syntax, not account-existence probing. Signup max254 per schema. |
| Name | Name autofill, words keyboard | Required signup, max200; do not reject non-Latin names or require two words. |
| Current/login password | Password autofill, secure entry, no autocorrect/capitalization | Preserve exact spaces and Unicode; allow paste and password managers. Return key submits only when all local required data is valid. |
| New/confirm password | New-password autofill/generation semantics for both fields | Match frontend's local minimum8 and confirmation equality. That minimum is an existing UI policy, not an invented schema constraint or guarantee of server acceptance. Signup max128. Keep any additional server policy server-authoritative. No arbitrary mandatory character classes or misleading strength meter. |
| Email/SMS reset verification code | Numeric keyboard plus one-time-code semantics | One real six-character field styled as desired, not six separately focusable traps; paste full code. Normalize ASCII digit input consistently; explicit verify action. Never submit repeatedly just because six characters were entered. |
| Authenticator code | Numeric keyboard, six-digit local validation | Keep distinct from SMS/email autofill expectations; explicit verify. |
| MFA recovery code | Text keyboard, no autocorrect/capitalization | Allow full server code format; one field; no numeric-only filter. |
| Server origin | URL keyboard, no autocorrect/capitalization | Show validation inline; don't send credentials until origin is validated. |

Show/hide password is a real 48-unit target inside the field boundary, labelled “Show password” / “Hide password” with announced state. Toggling preserves value, focus, selection, autofill association, and keyboard; it does not submit or clear the field. Do not toggle all password fields together unexpectedly. Error state must not replace the visibility button or overlap entered text. Text remains legible with large font settings. Keep the submit button above the keyboard or reachable by scrolling; no fixed bottom overlay covering final fields.

Android uses Compose `ContentType.Username` (combine `EmailAddress` when appropriate), `Password` and `NewPassword` semantics. iOS uses native `.username`, `.password`, `.newPassword`, `.emailAddress`, `.oneTimeCode` where appropriate and an implementation that preserves text/focus during secure-entry changes. Verify these behaviors with a real password manager/device; semantic unit tests alone cannot prove OS autofill. See [Android autofill](https://developer.android.com/develop/ui/compose/text/autofill) and [Apple Password AutoFill](https://developer.apple.com/documentation/security/enabling-password-autofill-on-a-text-input-view).

One submission owns one visible operation. Show busy state in the original control bounds and prevent duplicate side effects. Give cancellation neutral treatment (“Sign-in cancelled” only if explanation is useful); do not show an error alert for dismissing an OS provider sheet. Lifecycle recreation must not launch duplicate providers, issue duplicate payments, or apply stale callbacks to a different account/server. Provider success is not app sign-in success until the server grant is validated and safely stored.

## 6. Provider availability and native SDK behavior

Provider order is configured Google, configured Apple on its supported platform, configured enterprise OIDC, then phone if actually supported; don't show empty slots. Only advertise a provider when both its SDK/setup inputs and its generated native exchange are ready. A configured web provider alone does not establish a valid native session. Show a quiet “More sign-in options” disclosure if needed rather than five competing primary actions. Password remains available if allowed by configuration; missing optional password flag matches the frontend's enabled default, but failed config loading is not the same as an absent flag.

| Provider | Implementation and UX requirement | Production limit in the pinned contract |
|---|---|---|
| Google Android | Credential Manager with official Google assets/button wording. Use Web OAuth client ID as `serverClientId`; Android package/SHA setup is separately required. A tap opens the native chooser, not a custom account list. Obtain fresh server nonce and challenge, get ID token, then generated exchange. | Existing `web_client_id` may be the correct SDK audience. Native attestation envelope and native/MFA response completeness still need validation; missing native refresh/grant must not be accepted as an authenticated app session. |
| Google iOS | Real configured Google SDK/system browser flow, correct iOS client configuration and server audience; official button asset; same generated exchange authority | Do not substitute the Android/web client ID for required iOS setup. Unsupported setup is a clear unavailable/demo branch, never fake credential entry. |
| Apple | System `SignInWithAppleButton`/AuthenticationServices, approved white or white-outline style, height48, radius10, full accessible title. Surrounding brand panel can use Space Works colors. Bind and validate nonce through the generated gateway. | No hand-drawn Apple glyph or teal Apple-branded button. Apple response cancellation is neutral. Account linking/MFA still requires server-native readiness. |
| Enterprise OIDC | Button “Continue with {configured provider name}”; trusted configured issuer; OS browser authentication session, fresh state/nonce and PKCE when the actual flow requires it; verified redirect and callback ownership | Pinned browser authorize/callback models do not specify native refresh/device-grant delivery. Do not turn browser cookies or a web access token into native access. Unavailable until a real compatible generated exchange exists. |
| Phone | “Continue with phone”; country-aware phone entry, explicit send, code screen with change-number/resend/cancel. Explain “We'll send a verification code.” | `PhoneStart`/`PhoneConfirm` exist but native device proof/grant contract is incomplete. Full flow is demo-previewable; no production phone-session shortcut. No invented SMS expiry fields. |

Provider assets retain official styling, including Google multicolor glyph. Do not recolor provider marks to teal or replace them with ordinary letters. See [Google branding](https://developers.google.com/identity/branding-guidelines) and [Apple sign-in guidance](https://developer.apple.com/design/human-interface-guidelines/sign-in-with-apple/).

### Android Google SDK checkpoint

Verified 16 October 2026 against official release notes: `androidx.credentials:credentials:1.6.0`, `androidx.credentials:credentials-play-services-auth:1.6.0`, and `com.google.android.libraries.identity.googleid:googleid:1.2.1`. Use matching stable Credentials artifacts, not the alpha version shown in some current guide examples. Check the app's build constraints when adding them; version verification does not replace compiling and exercising the integration. Sources: [AndroidX releases](https://developer.android.com/jetpack/androidx/releases/credentials), [Google ID releases](https://developers.google.com/identity/android-credential-manager/releases).

Manual button flow: `CredentialManager.create(context)` → `GetSignInWithGoogleOption.Builder(serverClientId = WEB_CLIENT_ID).setNonce(serverNonce).build()` → `GetCredentialRequest.Builder().addCredentialOption(option).build()` → `getCredential` with the current foreground Activity context. The current official guide recommends a `MutableContextWrapper` around that Activity for reconstruction handling. The [Google option builder](https://developers.google.com/identity/android-credential-manager/android/reference/com/google/android/libraries/identity/googleid/GetSignInWithGoogleOption.Builder) defines the server audience and nonce. Accept only a `CustomCredential` with `GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL`, parse with `GoogleIdTokenCredential.createFrom(data)`, then pass `.idToken` to the existing generated gateway; see [Google credential reference](https://developers.google.com/identity/android-credential-manager/android/reference/com/google/android/libraries/identity/googleid/GoogleIdTokenCredential). Use the server nonce required by this app's contract instead of creating an unrelated nonce locally. Never log the token.

An optional returning-account bottom sheet uses `GetGoogleIdOption` with authorized-account filtering first; `NoCredentialException` may fall back to all accounts. Do not auto-open a provider chooser on cold launch or fight the user's explicit account switch. Clear credential state on explicit sign-out with `ClearCredentialStateRequest`; that clears app selection state rather than revoking the user's Google permissions. SDK cancellation, parsing failures, unavailable Play services, and a rejected native grant need separate tested outcomes. [Official implementation guide](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation).

## 7. Generated authentication operations and exact shapes

Use the generated operation/model from the pinned contract; names differ between Swift/Kotlin generators but the OpenAPI operation IDs below are authoritative. Extra confirmation fields exist only in UI state and must not be transmitted.

| Operation ID (path relative to `/api/v1`) | Exact request | Result and UI implication |
|---|---|---|
| `api_v1_auth_member_sign_up_create` (`POST /auth/member-sign-up`) | `MemberSignUp`: required `display_name`, `email`, `password`; optional `phone` default empty max32, `website` default empty | `MemberVerificationAck(detail)`, not tokens. Native login follows acknowledgement. |
| `api_v1_auth_forgot_password_create` (`POST /auth/forgot-password`) | `ForgotPasswordRequest(email)` | `PasswordResetAcknowledgement(detail)`; uniform acknowledgement, then confirm screen. |
| `api_v1_auth_reset_password_create` (`POST /auth/reset-password`) | `ResetPasswordConfirmRequest` union: `OtpResetPasswordConfirm(email, code, new_password)` with code pattern `^\d{6}$`, or `LegacyResetPasswordConfirm(uid, token, new_password)` | `PasswordUpdated(detail)`; return to native sign-in. Never send both union branches. Legacy link support uses only a validated supported link; query values are secrets and never an auto-submit. |
| `api_v1_auth_email_verification_confirm_create` (`POST /auth/email-verification/confirm`) | Authenticated `EmailVerificationConfirm(code)`, max6; UI uses six digits | Confirm then reload current account/gates. |
| `api_v1_auth_email_verification_resend_create` (`POST /auth/email-verification/resend`) | Authenticated operation, no invented JSON body | Acknowledgement and resend cooldown. |
| `api_v1_auth_change_password_create` (`POST /auth/change-password`) | `ChangePassword(current_password, new_password)` | Detail only; re-fetch account, preserve actual gate until server clears it. |
| `api_v1_auth_mfa_enroll_create` (`POST /auth/mfa/enroll`) | `MFAChallenge(challenge)` | Setup uses server secret/URI; not app access. |
| `api_v1_auth_mfa_confirm_create` (`POST /auth/mfa/confirm`) and `api_v1_auth_mfa_verify_create` (`POST /auth/mfa/verify`) | `MFAVerify(challenge, code)`; challenge20–200, code6–64 | Enrollment confirmation and existing-factor verification are distinct transitions. Validate native completion refresh/device grant before publishing session. |
| `api_v1_auth_mfa_recovery_create` (`POST /auth/mfa/recovery`) | `MFAPassword(password)` | Regenerate codes in authenticated Security settings, not a guessed login-recovery route. Current challenge recovery entry uses verify with code according to the existing frontend flow. |
| `api_v1_auth_mfa_create` (`POST /auth/mfa`) / `api_v1_auth_mfa_disable_create` (`POST /auth/mfa/disable`) | `MFAPassword(password)` | Authenticated MFA setup/disable according to server authority. Disable requires a clear security confirmation; never claim it succeeded on a local toggle. |
| `api_v1_auth_social_nonce_create` (`POST /auth/social/nonce`) | `SocialNonce(provider, surface, delivery, client_platform)` with optional `challenge` | Use actual enum values from generated models; native social platform is `ios`/`android`, not device platform enum `apple`. |
| `api_v1_auth_social_google_create`, `api_v1_auth_social_apple_create`, `api_v1_auth_social_oidc_create` | `SocialLogin(id_token, nonce, surface, delivery, client_platform)` plus optional `apple_name`, `challenge`, unconstrained `attestation` | ID token must be server validated. Optional refresh/device grant and absent MFA union in `SocialLoginResponse` need safe handling, not assumptions. |

Frontend evidence: `features/auth/PasswordRecoveryFlow.tsx`, `features/auth/passwordRecovery.ts`, `features/auth/NewPasswordFields.tsx`, `features/auth/MfaFlow.tsx`, `features/members/MemberAuthPanel.tsx`, and `features/staff/ChangePasswordGate.tsx`. Password recovery uses a local60-second resend delay and the exact uniform rejection above. Signup visibility follows configured `self_registration`/`member_accounts`; restricted verification remains a gate. Read frontend flows for parity; do not copy browser authentication storage or cookie transport into mobile.

Backend-deferred boundaries remain explicit: native attestation envelope, native phone/OIDC exchange and complete social MFA grant delivery, native/min-version config shape, push server association/link files, staff authority where not represented, vendor server checkout inputs, and account deletion blockers/reauth/execute. A beautiful screen never grants authority these contracts do not provide.

## 8. Error, offline, and cooldown behavior

Use actionable inline language. Never display raw JSON, stack traces, authorization values, server HTML, SDK exception class names, or bare numeric codes. A retry belongs beside the failed task. Preserve the current safe screen and useful inputs; do not reset the entire flow for every failure.

| Condition | Message | Action/state |
|---|---|---|
| Login credentials rejected | “The email, username, or password didn't match. Try again.” | Keep identifier; retain password only in current in-memory form so user can correct it. No account-existence distinction. |
| Missing required field | “Enter your email or username.” / “Enter your password.” | Associate with field; first invalid field gets focus on submit. |
| New password local checks | “Use at least 8 characters.” / “Passwords don't match.” | Independent of account/OTP validity; server can still reject per policy. |
| MFA invalid code | “That code didn't work. Check it and try again.” | Keep active challenge if still valid; clear only submitted code as appropriate. Expired challenge uses its dedicated restart screen. |
| Reset confirmation rejected | “Invalid or expired verification code.” | Always identical for every server rejection; no conditional password-policy detail. |
| Network unavailable during mutation/auth | “You're offline. Connect to the internet and try again.” | Stop busy state; explicit retry after reconnect. Do not queue signup, login, OTP, checkout, staff actions, deletion, or password changes silently. |
| Server temporarily unavailable | “This service isn't available right now. Try again shortly.” | Keep task; respect Retry-After if supplied. A503 is not invalid credentials. |
| Rate limited | “Please wait before trying again.”; supporting “Try again in {remaining}s.” | Disable affected action until allowed; no per-second screen-reader announcements. |
| Permission denied403 | “You don't have access to this action.” | Refetch authority if needed and return to safe context. Do not offer a retry loop that bypasses role checks. |
| Resource removed404 | “This item is no longer available.” | Return to refreshed list; clear old QR/detail and dependent action. |
| Conflict409 | Task-specific “This changed while you were working. Refresh and try again.” | Reload real resource before resubmitting; scanning uses “Scan again”. No fake rollback. |
| Native provider unavailable | “This sign-in option isn't available on this device.” | Offer another configured method or help/change server. Provider cancel is neutral. |
| Secure storage failure | “Couldn't save your sign-in securely. Try again.” | Do not publish an in-memory authenticated session that will lose its refresh/grant; clean up partial state. |

Retry-After parsing and timing belong to the existing networking core: support the actual header format, measure countdown against elapsed time, recompute after backgrounding, and never shorten a server wait. Use the greater of server wait and any documented local resend delay. The frontend's 60-second password-recovery resend delay is a UI rule, not a claimed server OTP expiry. Editing email, backing up a screen, rotating, retrying another button, or changing tabs must not bypass a server cooldown for the same server/operation. Do not refresh bearer tokens for an unauthenticated MFA challenge error. Busy/error/cooldown states are distinct.

Offline after prior authentication can show only data that the app really caches, clearly labelled “Offline · Last updated {time}”. A network error alone does not wipe valid tokens or assert revocation. Missing cache shows “Connect to load your makerspace”; never show a demo fixture as cached production data. Protected transactions require an online server. On resume/reconnect, refresh authorized membership and data before enabling transactions. Explicit offline logout still clears local protected state.

## 9. First-use guidance across the app

Keep the five main destinations Home, Inventory, Activity, Inbox, Profile. Staff tools are an explicit authorized destination/action, not an alternate sign-in identity. The first successful sign-in ends on the selected makerspace's Home. Show a single dismissible “Start here” card with two relevant actions, “Browse inventory” and “View bookings”; if a module is unavailable, choose another enabled action rather than presenting a dead CTA. Do not gate app use on a tutorial checklist.

Context tips are brief, dismissible, shown once per account + makerspace + feature version, and replayable from Help. No more than one educational callout per screen; operational warnings can coexist when necessary. Dismissing a tip does not dismiss a failure or consent requirement. See [Apple onboarding](https://developer.apple.com/design/human-interface-guidelines/onboarding) for task-time guidance.

| Phase/flow | Context explanation and real action | Essential states and safety |
|---|---|---|
| A2 Home/read | “Your makerspace, at a glance.” Show real upcoming bookings/events and due loans; action opens the actual detail | Loading skeleton with stable layout; truly empty next-step card; failure with retry; partial section failure does not erase other successful sections. |
| A2 Inventory/detail | “Find what you need, then request it from your makerspace.” Search/filter and actual item detail | Distinguish “No items yet” from “No items match these filters” → “Clear filters”. Availability is server-authoritative. No raw IDs as primary titles. |
| A2 Loans/card/events/bookings/payments read | Use plain status words, dates with locale/timezone, real charges/amount/currency, card/ticket QR only when valid | Stale/unauthorized QR is removed; unavailable card explains “Ask the front desk for help.” Never show an expired QR as usable. |
| A3 Hardware request/cart | “Add the items you need. Staff will confirm your request.” Review items/quantities, then “Submit request” | Validation/availability changes remain editable; show actual submitted result, not a fabricated handover. Empty cart → “Browse inventory”. |
| A3 Renew loan | Explain the actual loan/date and action “Request renewal” or “Renew loan” according to contract result | Pending/approved/rejected meanings distinct; do not silently replace the due date before server success. |
| A3 Booking | Choose resource → date/time → review → “Confirm booking” | Clearly state makerspace timezone, duration, conflicts, cancellation policy when supplied. No arbitrary timezone conversion of selected slot. Failure preserves draft and refreshes availability. |
| A3 Event registration/ticket | Detail → actual registration choice → review cost/eligibility → register → real ticket | Sold out, waitlisted, payment pending, cancelled and already registered remain distinct. Cancel has an explicit confirmation. QR is not proof of payment or permission to check in without server verification. |
| A3 Profile/preferences | Group Profile, Notifications, Security, Membership, Devices; clear “Save changes” where required | Dirty edits have discard confirmation; failures keep edits; toggles don't claim success until saved. Updating phone/email follows real verification, not a local checkmark. |
| A4 Inbox/push | Inbox works without notification permission. First relevant prompt: “Get updates from your makerspace”; “Turn on notifications for requests, bookings, and important updates.” Actions “Enable notifications”, “Not now” | Use real OS permission request only after Enable. Denied → quiet settings row, no repeated launch prompt. Push is a hint; authenticated refetch authorizes navigation. Unknown versions/malformed/external links fall back to Inbox. Missing/deleted target shows a safe unavailable state. |
| A5 Payment | Payments list/detail → review real amount/currency/charge; “Continue to payment” opens configured SDK only with server-issued checkout input | SDK completion → “Checking payment…” and generated server refresh/poll. Only server-confirmed paid status shows a receipt. Cancel returns to review; pending/failed/expired may retry only through a fresh authorized checkout. Receipt/document uses a trusted actual document response and safe viewer/share action. No SDK launch in demo and no charge from preview data. |
| A6 Staff queues | Pending requests and booking approvals show requester, actual items/time, status, detail → explicit “Accept”/“Reject” or “Approve”/“Decline” with required reason | Refresh on conflict; do not remove a row or claim approval before success. Authority loss exits staff actions safely. Rejection has a deliberate confirmation and meaningful reason when required. |
| A6 Staff scan | “Scan to find a member or ticket.” First entry explains camera purpose before OS request; manual lookup/entry remains available | Camera permission denied, no hardware, busy, unreadable code, revoked authority, offline, duplicate scan, invalid/expired QR all have clear states. Resolve person/ticket first, explicit “Confirm check-in” second. Never execute a server action merely on camera detection. |
| A6 Staff handover/return/direct handout | Container/member → items → required photo/notes → review → explicit confirm → receipt/result; direct handout follows the actual borrower/item selection contract | Gate each step by real data; show partial success precisely; unresolved charge/waiver is a real informational/blocking state per response. Do not invent successful issue/return or Undo without an actual reverse operation. Camera closes when leaving the scan screen. |
| A7 Devices/security | “Devices signed in to your account”; recognizable device/session description, current-device marker; per-device “Sign out device” confirmation | Actual server list/revoke; current-device revoke clears local state. No false “all devices” promise. MFA disable/regenerate are reauthenticated server actions. |
| A7 Membership | Differentiate “Cancel renewal” (current term remains) and “Leave makerspace” (ends membership as server indicates); explain consequences and confirm | Preserve history where the real app does. Leaving a makerspace is not deleting an account. Revoked membership removes that scope and reselects safely. |
| A7 Account deletion | “Delete account”; eligibility/blockers → real fix links → required reauthentication → explicit final confirmation | Production unavailable until deletion contract exists. Show “Account deletion isn't available in this app yet. Contact your makerspace for help.” without claiming a deletion request was sent. Demo may exercise labelled simulated blockers/confirmation, but no production success or local logout masquerading as deletion. |
| A8 Help/release | Help can replay introduction, explain permissions, show app version/server and configured privacy/support links | Strings are localization-ready, no UI implementation/phase jargon. Reviewer/demo mode is obvious and separate from production. Store privacy descriptions must match actual SDK behavior. |

Request camera only on Scan/Take photo; use native photo picker for an explicit attach action rather than broad library permission. Explain why a denied permission matters at that task and offer manual or settings fallback. Do not prevent ordinary inventory use because notifications or camera are denied. Recheck permission when each operation begins. [Android contextual permissions](https://developer.android.com/training/permissions/requesting).

## 10. Adaptive layouts and accessibility

Measure available window size, not device model or orientation alone. A tablet in split view behaves like the available compact width. Preserve entered data and current step across resize/rotation; don't repeat a provider launch, resend, SDK payment, or mutation. Use native navigation/back behavior. On tablets, popovers/sheets are anchored correctly and never display a phone form stretched across the screen.

| Available width | Layout |
|---|---|
| Under600 logical units | One column, gutter16–24 as space permits, form width fills available space up to460; symbol or compact stacked header; scrollable content. Intro illustration can collapse in short height. |
| 600–959 | Centered single bounded task column, max460, gutter24–32; optional quiet surrounding surface. App content may use a navigation rail/sidebar and real list/detail when both panes remain useful. |
| At least960 with adequate height and ordinary text size | Optional two-column auth: form max460, gap40–48, brand panel roughly320–400; balanced content area max1040. Put static stacked logo and one short value statement in the panel. All actual fields/actions stay in one reading column. |
| Any width with large accessibility text, large display zoom, or short keyboard-obscured height | Collapse brand panel and nonessential illustrations; one scrollable column; no clipped fixed-height cards. Preserve controls and text rather than scaling the page down. |

No screen depends on landscape; no orientation lock to solve clipping. Auth may use a non-fullscreen tablet task card, but its entire form/error content must remain scrollable with hardware and software keyboards. Full-app master/detail selection survives navigation and resizing; a phone opens detail as a destination. Follow [Android window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes) and [Apple adaptive layout](https://developer.apple.com/design/human-interface-guidelines/layout).

Acceptance floor: VoiceOver/TalkBack labels and logical reading order; real headings; announced progress/errors without repeated countdown chatter; accessible selected-page/tab/checkbox states; 48-unit targets; keyboard traversal and visible focus; native text scaling; at least4.5:1 text contrast and meaningful non-text boundary/focus contrast. Screen-reader focus moves to the new heading on navigation, first invalid field on submit, and back to the invoking control after a dismissed sheet. Decorative illustration and duplicate logos are excluded. Do not mark an entire form as a single accessibility element. Dialogs trap focus appropriately; Back/escape dismisses safe dialogs and never confirms a destructive action. QR screens provide textual context and an available equivalent action, without announcing the secret QR payload. Sources: [Android accessible components](https://developer.android.com/develop/ui/compose/accessibility/api-defaults), [Android semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics), [Apple accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility).

## 11. Motion specification

Use the existing brand timings generated from `tokens/mobile-motion.json`; do not hand-copy divergent constants into the two apps. Animate only a change that helps someone understand progress or hierarchy. Native interaction/state updates happen immediately; never delay a result or disable input until a decorative animation finishes. Respect the OS Reduce Motion/animator-duration setting from first render, including live changes. The shared reduced-motion contract sets custom opacity duration and blocking delay to zero and disables spatial transitions/decorative repeats. Avoid spring overshoot, parallax, shimmer loops, bouncing, confetti, auto-advancing carousels, and repeating card float effects. Native indeterminate progress remains available with an accessible label.

| Transition | Standard behavior | Reduced motion |
|---|---|---|
| Launch | Static OS artwork; immediate state resolution | Same; no second splash |
| Intro page change | Content/illustration cards may crossfade and shift at most12 units over200ms; stationary independent logo/header and controls | Instant content replacement; no custom opacity animation or spatial movement |
| First task content appearance | One brief non-logo content fade, at most160–200ms; no stagger delaying controls | Immediate display |
| Color feedback | 120ms | Immediate |
| Inline disclosure/expanded helper | 200ms | Immediate layout update; retain focus |
| Task sheet/modal | Enter240ms, exit160ms, cubic-bezier(.32,.72,0,1), native platform presentation where appropriate | Native reduced-motion presentation or immediate custom state change |
| Form error/success | Stable inline region, optional120ms opacity | Immediate; announce once, no shake |
| Main navigation | Native platform transition, no custom flourish on every tap | Honor native setting |

Keep the stationary logo outside every animated subtree. Do not fade a parent panel that contains it. Native navigation transitions should not carry a custom floating logo between screens; use a stable shared header or render the correct static destination header. Motion decisions combine the brand specification with [Apple motion guidance](https://developer.apple.com/design/human-interface-guidelines/motion), [Apple Reduce Motion criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/reduced-motion-evaluation-criteria), and [Material motion principles](https://m3.material.io/styles/motion/overview/how-it-works). The numeric durations come from the Space Works brand, not a claim that the latest Material system mandates them.

## 12. Debug/demo and preview requirements

Demo mode is authorized to demonstrate complete flows while the owner supplies backend work later. Label entry and all authenticated demo screens with a persistent “Demo mode” indicator and offer “Exit demo”. Use an isolated demo gateway/store with deterministic, synthetic accounts/data; never insert demo fixtures into production repositories/caches/token storage. Show “Simulated” on payment/deletion/staff success summaries. Demo mode is not a way to establish a production native grant.

Provide a debug scenario picker for: first-run/returning; password success/rejection; MFA enrollment, invalid/expired verification, recovery and codes acknowledgement; signup/email verification; recovery request/confirm/success/unavailable; temporary password; zero/one/multiple memberships; revoked session; configured/unavailable/cancelled provider; offline/cooldown; minimum version; empty/error app sections; permission denied; invalid scan; payment pending/failure/server-confirmed simulation; deletion blockers/unavailable. Scenario names are developer controls, not end-user copy. Keep preview/demos unreachable in ordinary release unless an explicitly configured reviewer build calls for them.

Demo must never request a live native provider token, register a real push device to a sample account, invoke payment SDK checkout, modify real staff inventory/check-in, or call deletion. Real camera permission/preview may be exercised only by a deliberately chosen hardware test, clearly separate from a simulated scan result. Never hardcode live secrets, user credentials, production provider tokens, or executable checkout inputs into demo data. Release builds must compile without debug fixtures reachable from normal navigation.

## 13. Required implementation evidence

Each agent should map its actual screens/actions to this specification and note any contract-dependent production branch in its delivery notes. A linked Markdown file alone does not finish the work. Follow the PRD's per-phase tests/commits; this UX checkpoint requires focused state tests, actual platform build checks, and rendered evidence. Do not generate hundreds of snapshots that only mirror implementation.

### Functional acceptance journeys

1. Fresh launch is usable without waiting for an animation. Skip and completion both persist; Help replay works without wiping account settings. No permission dialog appears during introduction.
2. Server bad URL/network/config failures remain editable and retryable; a failed candidate cannot replace the last validated origin or receive another origin's credentials.
3. Username and email/password login work with autofill/paste. Show/hide preserves selection/focus. Duplicate submit and stale callback cannot create two sessions. Secure-storage failure does not expose a signed-in shell.
4. Config controls actual methods/signup; no configured methods has a useful recovery state. Native provider cancel is neutral. Google manual chooser parses real credential type and clears selection state on sign-out; missing native grant does not enter Home.
5. Signup sends only model fields, receives acknowledgement, clears password, and returns to normal native login. Email verification/restricted session cannot unlock main tabs before refreshed authority.
6. Forgot/reset uses the real generated operations; local8-character/match checks, full OTP paste, resend60s plus longer server wait, same reset-rejection text for every server rejection, success back to sign-in, known unavailable branch.
7. MFA enrollment exposes a real/manual setup key, verifies once, shows real returned recovery codes, requires acknowledgement. Recovery accepts non-numeric codes. Expiry/back/account change clears challenge secrets and rejects stale results.
8. Temporary-password gate blocks tabs until generated change-password plus account refresh clears it. Password rejection remains correctable; sign-out remains possible.
9. Zero/one/multiple membership cases and revoked membership do not leak the previous space/account cache. Switching after a pending link reauthorizes the target.
10. Auth network failure does not become token revocation. Offline cached views are labelled; offline explicit logout wipes data. Server429/Retry-After survives resize/background and cannot be bypassed by editing or navigation.
11. First-use tips dismiss/replay, remain scoped correctly, and never block ordinary actions. Empty/filter-empty/error/partial-load states have meaningful next actions.
12. Camera and notifications are requested only after a task action; denial has manual/Inbox fallback. Camera releases on exit/background as appropriate. Push malformed/external/stale targets cannot route around auth/membership.
13. Payment SDK completion never displays paid before server confirmation. Demo never launches checkout. Staff scan detection never performs check-in without confirmation. Production deletion remains unavailable until its actual contract exists.
14. Explicit logout/account/server switch clears pending SDK callbacks, cache, cart, QR and secure state; no old account content flashes on the next sign-in.
15. Release configuration has no reachable demo gateway/secret fixture path. All displayed production successes are backed by generated server state.

### Rendered and accessibility evidence

Capture a small purposeful matrix on each platform: smallest supported phone portrait with keyboard; representative modern phone; large phone/landscape; tablet portrait and landscape; tablet split/narrow window. Include light and dark, largest supported accessibility text, and reduced motion. At minimum render introduction, sign-in with real provider styling, field error, MFA/recovery, password reset, membership picker, Home, and one contextual permission/denial state across that matrix. Verify no clipping, stretched logo, logo motion, hidden CTA, duplicate focus stop, or inaccessible dark link. Record tested device/window dimensions and settings rather than claiming universal coverage from one screenshot.

Manually exercise TalkBack/VoiceOver through sign-in, OTP paste, show/hide, errors, modal cancel, and makerspace selection; hardware keyboard traversal on a tablet; and real native autofill/provider UI on an appropriately configured device. CI should compile debug/release SDK paths and run meaningful coordinator/state tests. macOS/Xcode is required for iOS build and simulator evidence; Windows source lint is not a substitute. Native payment, push, camera, and attestation need configured physical-device smoke checks before store release. Document unavailable environment evidence honestly rather than marking it passed.
