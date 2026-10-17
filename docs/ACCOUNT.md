# Account and privacy flows

Open **Account and security** from Profile, or **Account access** when no makerspace membership
exists. Global password, two-step status, verified phone, connected providers and device grants
remain available without inventing a makerspace. Tenant sessions, membership and exports require
the actual selected makerspace and its bootstrap self-service/export capabilities.

Manual password changes use the generated change-password operation and a fresh identity read.
A separately verified native session is retained; the first-login temporary-password gate remains
separate and requires fresh sign-in. Current password, new password, confirmation and OTP input are
transient and cleared on disposal/background. A wrong management password does not sign out a
separately valid identity. Provider listing/unlink and phone link/confirm/unlink use published
operations. Native provider linking and authenticated MFA enrollment remain unavailable under the
current backend; their explicitly labelled Debug previews never invoke providers or fabricate a
native grant. Login-challenge MFA enrollment remains supported separately.

Session IDs are opaque and use the server's current marker. Global native grants are UUIDs and
have no current marker, so revocation probes identity rather than guessing which grant is current.
Confirmed security revocation clears native credentials/private screens locally without issuing an
extra logout. Replacement recovery codes appear only after that clear, outside the signed-in shell,
with explicit saved acknowledgement. Codes are never persisted, logged, copied or included in
validation screenshots. Real secret/document screens use the platform secure-window flag.

Renewal cancellation retains the current membership term. Confirmed leave synchronously removes
the makerspace from cached identity and selection before another identity request; offline or stale
responses cannot restore it. A generic403 refresh does not fabricate a leave. Export creation and
status use generated operations; download consumes a fresh same-origin one-use public grant with
no bearer or makerspace header. Streaming ZIP data is bounded to25 MiB and cancelled through its
underlying Call. Native private document preview/save/share has explicit temporary-file ownership.
The published sessions/export envelopes have next links but no page query parameter; additional
rows are honestly deferred to the web portal rather than repeating page1 or inventing page2.

Each controller owns its account UI and callbacks. The encrypted store and refresh lock are shared
per deployment: token rotation retains installation identity, whereas login/clear publishes a
non-secret installation epoch. Other controllers immediately invalidate cached private identity
without touching replacement credentials or shared selection. Every generated account request is
bound to its original installation, including the gap before first send and multi-call exports.
Account-owned phone resend deadlines survive makerspace changes; server429 deadlines remain
visible and enforced. Account forms defer incoming navigation until closed. Generated services
retain their installation when created, so a queued or retained mutation cannot first-send with
a replacement account. Logout and notification removal explicitly retain the old installation
after private identity clears. An anonymous window never borrows another window's grant.
Notification delivery uses an opaque controller lease, and SDK acquisition/deletion share one
serialized queue; late cleanup cannot publish over a newer owner.

Privacy and support links come from validated optional `branding/brand.json` values. Missing
configuration displays an honest unavailable state; no legal document or contact success is
invented. Links normalize HTTPS/IDNA and open externally without credentials.

Whole-account deletion has no production route in the supplied contract. Debug alone offers
**Explore account deletion preview**: all planned blockers, actual demo makerspace IDs, supported
fix destinations, fresh proof, exact typed DELETE, proof expiry, a newly discovered obligation and
local preview reset. The preview uses `DemoPass!123` or code123456. It never deletes a real account,
invokes an SDK or sends a deletion request. Staff fix navigation appears only for the actual
selected staff preview; unsupported responsibilities point to makerspace support. The ordinary
account security preview password is `sample`; manual password preview uses `DemoPass!123`.

Live server/provider/account-deletion verification remains deferred to the owner backend and the
native-contract requests in issue3. The English account catalog prepares localization; it does not
claim translated languages or completed store submission.
