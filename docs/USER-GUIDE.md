# Using Space Works

## Start

Use your makerspace's HTTPS server address and your own account. A branded deployment may fix the
server automatically. Complete any required password update or two-step sign-in, save newly issued
recovery codes, and choose the actual makerspace you want to use. The app keeps makerspace data and
permissions separate. With no memberships, **Account access** still opens supported global security
and device controls.

The Debug app also offers **Explore demo** without a server. Open **Demo scenarios**, choose
**Member** or **Staff**, press **Sign in**, and choose a makerspace. Scenario selection fills the
sample credentials; it does not submit them automatically. **Exit demo** resets this local preview.
A persistent Demo mode banner identifies it. Preview provider/phone sign-in, checkout, evidence and
deletion operate on synthetic data and do not send messages, charge money or change a real account.

## Find your way

Phones use Home, Inventory, Activity, Inbox and Profile tabs. Wider windows use a navigation rail
with the same destinations. Home summarizes membership, dues, loans, bookings, registrations and
notices. **Start here** offers inventory and bookings shortcuts; dismissing it affects only that
account and makerspace. **Profile → App guide** contains the task reference, introduction replay,
helpful-tip reset and safe version/server information.

| Task | Flow |
|---|---|
| Request equipment | Inventory → search/category → item and borrowing terms → quantity/cart → review → Submit request. Staff confirmation is a later state; a submitted request is not a completed handover. |
| Manage a loan | Activity → loan → review due date and eligibility → Confirm renewal. A refusal preserves the loan's actual server state. |
| Book a space | Home → Book a space → space → time range → Check availability → required answers → review and submit. Follow the returned approval/booking status. |
| Join an event | Home → Explore events → event/ticket → required form → Confirm registration. Registered, pending approval and waitlisted are distinct; only a valid registered ticket exposes its QR. |
| Read updates | Inbox → All/Unread → update. Notifications are optional; Inbox works after permission denial. Opening a link freshly checks account, makerspace and destination. |
| Pay or view a document | Home dues or Profile → Payments → actual charge → review → configured checkout. Only refreshed server status can confirm payment. Open issued documents to preview, save or share an authorized file. |
| Update account | Profile → Account and security. Global devices/security/verified phone differ from makerspace sessions, membership and exports. Cancel renewal retains the term; Leave ends that makerspace membership. |
| Get help | Profile → App guide or Account and security → privacy/support. Missing deployment links are identified as unconfigured. |

Keep each form's review visible until its confirmation completes. Back/cancel preserves a safe
editable state or asks before discarding changes. Unknown, unavailable and refused server states
are shown explicitly; refreshing does not turn them into successful operations.

## Staff preview

Only the explicit Staff demo or a future verified native staff deployment exposes **Profile →
Staff tools**. Select a request and review accepted quantities. Issue/return/direct handout then
identify the actual borrower/container/equipment, resolve any kit counts, prepare and upload JPEG
evidence, and explicitly confirm the reviewed action. A scan identifies an item/person; it never
records the physical action by itself. Event check-in also requires a separate confirmation.
If container assignment succeeds but issue fails, refresh the recorded state before continuing.
See [STAFF.md](STAFF.md) for the partial-operation boundary and sample scenarios.

## Exercise the preview

- Sign-in scenarios cover two-step enrollment/verification, expired proof, unverified email,
  no membership, temporary password, offline/cooldown and superadmin refusal. Verification code:
  `123456`; the enrollment recovery-code sample is `DEMO-001-ABCD`.
- **Preview sign-in options** covers Google, Apple, SSO and phone cancellation/refusal/verification
  without opening provider SDKs or sending SMS.
- **Profile → Choose a demo state** covers populated, empty, offline, access refused, unavailable
  dues and revoked/not-issued cards. Return to Populated to recover the local member fixture.
- Staff tools have their own preview scenario menu, including partial assignment and kit review.
- Account security's sample password is `sample`. Manual-password and deletion previews use
  `DemoPass!123`; deletion also accepts the displayed `123456` sample code. Whole-account deletion
  is explicitly a Debug-only preview while its production contract is deferred.

Live integrity, provider configuration, push delivery, payments, physical evidence and whole-account
removal need the owner's backend/provisioning and verification recorded in
[RELEASE-READINESS.md](RELEASE-READINESS.md). Demo results and emulator checks are recorded separately
from live-provider or store-distribution evidence.
