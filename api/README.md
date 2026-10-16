# API contract provenance

`openapi.json` is an unchanged, owner-supplied OpenAPI 3.0.3 snapshot from `SpaceWorks-HQ/SpaceWorks`, `frontend/openapi-schema.json`, source commit `e7c2803a4a57870e11dc4faf9adc40ee54401632` (2026-10-16). SHA-256: `d5ec904eb2a52ca948ee38dae7840c8859a43e034a22f3b8dc478fd8851cbb9b` (4,063,303 bytes; 635 paths).

`./gradlew :app:openApiGenerate` runs OpenAPI Generator 7.26.0 (`kotlin`, `jvm-retrofit2`, `kotlinx_serialization`, coroutines). `scripts/contract.py` mechanically selects implemented operation paths and their unchanged transitive schema references, avoiding unused API surface. The full authoritative snapshot remains committed. Expand the operation allowlist as each validated phase needs it. All client code lives in ignored build outputs; no handwritten request/response models.

Schema server examples are overridden with the validated deployment root. Paths already contain `/api/v1/`. Never append a second API prefix. Updates come from the owner; request missing endpoints using a `backend-request` issue.

Native generation compatibility overrides, type mappings, scoped headers and streamed-body cancellation are documented in [GENERATED-CLIENT.md](../docs/GENERATED-CLIENT.md). The raw snapshot above remains unchanged.
