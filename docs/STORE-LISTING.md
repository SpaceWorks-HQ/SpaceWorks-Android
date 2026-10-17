# Google Play listing draft

Prepared 17 October 2026 for owner review and later deployment. The backend and store
provisioning remain deferred. Use this copy only for features verified in the submitted build.

## Product fields

| Field | Proposed value | Count / limit |
|---|---|---|
| App name | Space Works | 11 / 30 |
| Short description | Your makerspace: tools, bookings, events and updates, together. | 63 / 80 |
| App type | App | — |
| Category | Productivity, subject to owner deployment classification | — |

Google's current name/description limits are documented in
[Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152).
The full-description limit is 4,000 characters; the draft below is within it.

## Full description

Plain text, 1388 characters:

```text
Make more time for making. Space Works brings your makerspace's tools, spaces and activity together.

Find what you need
Browse inventory, search and filter equipment, then review the items and quantities before sending a request. Follow updates from your makerspace and check your active loans and due dates. Request an eligible renewal when you need more time.

Plan your next visit
Explore bookable spaces, review available slots and follow your booking's current status. Discover events, register and see whether your place is confirmed, awaiting approval or waitlisted. Open a valid event ticket when it is available.

Keep up to date
Read makerspace updates in Inbox. Choose optional notification and communication preferences, and keep important safety messages available.

Stay on top of your account
Where enabled, review charges, check payment status and open receipts or credit notes. Manage account security and signed-in devices, and review the membership controls your makerspace makes available.

Space Works adapts to phones and tablets, with light and dark appearance and an app guide you can revisit at any time.

You need a compatible makerspace server and an account. Member features require the relevant makerspace membership. Available tools, services and actions depend on your makerspace. Internet access is required for current availability and protected actions.
```

This is the base member listing. Do not add unsupported native provider, offline-cache,
whole-account deletion or live staff claims. Add the following staff paragraph only after the
owner's native authority contract and the submitted deployment actually enable those controls:

```text
Authorized staff can review requests and approvals, check items before handover or return, and confirm attendance. Scanning finds a record; staff review and confirm each action.
```

## Screenshot capture plan

| Order | Screen / message | Capture conditions |
|---|---|---|
| 1 | Home — your makerspace at a glance | Synthetic reviewer member; real supported deployment; no personal notices |
| 2 | Inventory — find what you need | Search/filter and an actual available item; no invented availability |
| 3 | Review a request | Real supported request review; avoid submitting a mutation just to obtain a screenshot |
| 4 | Bookings or events | Use the module actually enabled; show its genuine pending/confirmed state |
| 5 | App guide and Profile | Show readable navigation/help; exclude secrets and private account identifiers |
| 6 | Tablet navigation | Repeat Home/Inventory on a tablet, showing the actual adaptive layout |

Use accepted CI full-OS screenshots to select composition and verify safe areas. Their Debug
demo banners identify fixture evidence; they are not production-store screenshots. Capture the
final submitted build against the owner's supported backend with a synthetic reviewer account.
Retain original dimensions and current console device slots; never stretch a phone image into
a tablet image. Keep recovery codes, verification codes and QR payloads out of store assets;
do not weaken secure-window protections to capture them. Optional staff images must show a
verified authorized staff deployment. The final release handoff links the exact tested commit, successful CI runs and actually reviewed layout-proof artifacts.

## Reviewer access and review notes

The in-app **Explore demo** is a Debug-only, in-memory preview. Its sample credentials do not
authenticate the signed Release build. The owner-deferred backend must provide a stable real
reviewer account and makerspace before submission. Store credentials only in the private console
app-access/review fields, never in this repository.

| Private console input | Owner-provisioned value |
|---|---|
| Server | Working HTTPS origin matching this build's deployment configuration |
| Member account | Working username/email and password entered privately |
| Membership | Active synthetic reviewer makerspace with representative safe records |
| Additional security | Working review access for the actual deployed MFA/provider policy |
| Optional staff account | Separate authorized reviewer only if staff is included in the submitted release |
| Review contact | Owner-managed name, email and phone entered privately |

Review-note draft (replace bracketed non-secret deployment details in the private console):

```text
Space Works connects members to their makerspace.
Server: [owner HTTPS origin; the build may already lock this origin]
Sign in with the reviewer credentials provided in the private app-access fields.
Select [reviewer makerspace] if prompted.
Use Home, Inventory, Activity, Inbox and Profile to explore the enabled member flows.
Profile > App guide explains the app and can replay the introduction.
Notifications and camera are optional and requested in their relevant tasks.
Use [owner-approved sandbox payment procedure] only if payments are enabled for review.
[Add separate authorized staff steps only when the submitted deployment supports them.]
```

## Deployment fields before submission

Use real owner HTTPS `support_url` and `privacy_url` in `branding/brand.json` and the console.
They are currently unset; no contact address or legal policy is fabricated. Complete the real
Data safety/App privacy answers, deletion resource, content/age declarations, copyright and
owner signing details against the submitted binary and backend. The release handoff lists the
live backend/provider and distribution checks still required. This document prepares copy and
review steps; no store upload, closed test or TestFlight distribution is claimed.
