# Staff tools

The native staff interface covers pending request quantities and accept/reject review, QR-assisted
issue and return, direct handout, booking approval/refusal and event attendance confirmation.
Scanning identifies equipment or a person; it never performs a physical action by itself.

The current published native session contract does not establish `native_staff` authority.
Production staff navigation and generated operations therefore remain closed. A membership role or
action list alone cannot grant access. The owner-deferred backend requirement is tracked in
[issue3](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/issues/3). Live inventory, evidence
upload and attendance success are not claimed.

## Explore the flow

In the Debug app choose **Explore demo → Demo scenarios → Staff → Sign in**, choose a makerspace,
then open **Profile → Staff tools**. This explicit fixture has a separate local staff workspace.
The ordinary Member fixture has no staff entry. No API, camera, storage or payment SDK is invoked
by sample codes and sample photos.

**Preview scenario** offers normal, empty, refused, offline, rate-limited, partial assignment and
kit-checklist states. Changing it discards the current local review, photo and scan results. The
fixture permission refusal can be recovered by selecting Normal. These controls are Debug only.

For an issue, review the actual request quantities, confirm the borrower, identify the container
and required individual equipment, review any kit counts, select and upload evidence, then confirm
the reviewed action. A return requires explicit outcomes, a remark and evidence. Counts start at
zero or pending; required kit UUIDs each require a deliberate count, including an explicit zero.
Assigning a box and issuing a request are separate server operations. If only assignment succeeds,
the app requires a fresh status read and explicit continuation instead of replaying the handover.

## Boundaries

- Every generated staff operation captures the selected makerspace and checks native authority and
  the established membership action before and after transport. Server permission checks remain
  authoritative. Cross-maker IDs and malformed individual/kit metadata fail closed.
- Active request, handover, attendance and booking reviews retain incoming navigation intent;
  notifications cannot replace their selected makerspace or automatically confirm an action.
- Photo picker, camera permission and QR callbacks carry transient tickets for their original
  account, makerspace and exact review. Late callbacks cannot attach a photo or resolve a code for
  a newer handover, even when its visible ID is identical.
- Production photos are orientation-corrected, downsampled and re-encoded as bounded JPEGs with
  source metadata removed. Only the private random camera output is deleted and its URI grants
  revoked. The user's gallery original remains untouched.
- A generated presign is uploaded through the isolated storage transport: missing method means
  multipart POST with all fields before the file; explicit PUT sends raw bytes with the returned
  safe headers. App credentials, cookies, redirects and automatic retries are excluded. Evidence
  is usable only after upload succeeds; changing the photo invalidates its previous evidence.
- Logout and makerspace/account replacement clear private staff state synchronously. Real secret
  views use screenshot protection. Debug screenshots contain synthetic fixtures only.

## Validation

Each of the24 generated operations has a contract test. State tests cover authority changes,
conflicts, exact quantities, partial outcomes, photo/QR ownership and kit requirements. Debug
fixture tests exercise each preview state. Native journeys cover full-app staff/member gating,
reviewed accept/issue/return/direct handout, explicit attendance, approval, partial assignment and
kit counts at phone/tablet sizes, including large dark text. Current phase results belong in the
changelog after the integrated build and device gate; authored journeys are not device proof.

Camera hardware/permission behavior, signed native staff grants and live storage remain part of
owner configuration and real-device/backend acceptance. The app does not substitute a browser
session or invent an authority field to bypass those gates.

The API26/27 orientation fallback uses the explicit AndroidX ExifInterface1.4.2 dependency,
matching the existing resolved CameraX graph and the
[official stable release](https://developer.android.com/jetpack/androidx/releases/exifinterface).
