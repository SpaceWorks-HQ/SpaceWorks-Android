# Security policy

## Reporting

Use GitHub's private **Report a vulnerability** entry in this repository's
[Security tab](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/security).
The [private reporting form](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/security/advisories/new)
is usable only when the owner has enabled private vulnerability reporting. If it
is unavailable, open an issue asking only for a private reporting channel; do not
include the vulnerability details or sensitive evidence publicly. No reporting
email, response deadline or bounty is asserted here.

Include the exact commit/build, Android version/device, a minimal reproduction,
expected versus actual behavior and the affected trust boundary. Redact tokens,
passwords, verification/recovery codes, private document URLs, account data and
signing/provider configuration. Do not access other people's accounts or run
destructive checks against a live makerspace. Use local fixtures or an explicitly
authorized test deployment. Never commit a discovered secret; notify the owner
privately so it can be revoked at its actual issuer.

## Scope and support

`dev` contains the current reviewed work; `main` receives the owner-authorized
delivery. There is no declared production support window or published security
release SLA. Report issues against the exact source, including the generated
client, native storage, request ownership, links, document handling and build
supply chain. A dependency alert or package checksum check is not a complete
security assessment.

The backend, production attestation/provider setup, signing and store deployment
are separate owner gates. Labelled Debug fixtures do not establish a live session,
settled charge, deletion or successful provider setup. Preserve honest unavailable
states when the generated native contract or deployment configuration is absent.

## Maintainer handling

Keep reports and reproductions private until the owner agrees on disclosure.
Confirm affected versions and ownership boundaries, add meaningful regression
coverage and validate the exact corrected source before delivery. Do not disable
TLS, attestation, authorization or secure-window protections to make a test pass.

Use the existing owner-authorized delivery workflow and preserve phase commits.
Store credentials, signing material and incident artifacts outside Git. Review
the current GitHub protection/reporting/scanning settings and the
[release checklist](docs/RELEASE-READINESS.md); this policy does not enable those
settings by itself.
