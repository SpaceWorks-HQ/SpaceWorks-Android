# Local Docker integration evidence

The owner selected local Docker as the integration environment on 2026-10-16.
The owner subsequently directed: complete the apps according to the flow and supply the app backend
later. Backend connectivity is therefore deferred; it does not gate UI, local flow, SDK adapter or
device-layout work. Debug preview fixtures must be visibly labeled and isolated from real sessions.
The existing development stack was restarted without deleting volumes, changing backend source,
or replacing its database. Its API is published on port 8000; the web flow is on port 5000.

| Client | Debug origin |
|---|---|
| Windows host | http://localhost:8000 |
| Android emulator | http://10.0.2.2:8000 |
| iOS Simulator on a Mac | A loopback proxy to this Docker host is required |

GitHub-hosted macOS runners cannot reach this Windows machine's localhost. Their XCTest/UI tests
use deterministic contract fixtures. Those results do not establish live Docker integration.
Release builds must continue to require HTTPS.

## Observed API results

| Probe | Result | Meaning |
|---|---|---|
| GET /api/v1/health/ | 200, status=ok | Local API is reachable |
| GET /api/v1/config | 200 | Public configuration is reachable |
| POST /api/v1/auth/device/attestation-challenge | 503, attestation_unavailable | Native session integration is blocked by local server configuration |

The challenge probe used platform=android, app_id=org.spaceworks.app and environment=development,
as documented by the supplied contract. The container has DEBUG=True and no
DEVICE_ATTESTATION_APPS environment entry. The public environment reference lists that setting
with an empty default but does not specify the allowlist format. Do not bypass attestation or
describe sign-in, refresh, authenticated reads/writes or logout as live-verified until the
owner supplies the development configuration and the challenge succeeds.

The running stack uses the existing design-revamp worktree. Its schema file differs from the
owner-supplied mobile snapshot. This is not authority to silently replace api/openapi.json:
the apps remain pinned to the supplied contract, and any observed response mismatch must be
reported before updating the snapshot.

## Required remaining evidence

After configuration: native member/manager login, MFA if enabled, me/membership filtering,
refresh rotation and revoked-session handling, all implemented generated read/write operations,
logout and restoration after process restart. Use isolated development fixtures, preserve
existing records, revoke created device grants, and keep credentials/tokens out of logs and Git.

Production SDK attestation, APNs/FCM delivery, social OAuth, payment-provider completion,
real-camera staff evidence and signed release builds need their actual provider/device setup.
They are separate from deterministic UI/state tests.
