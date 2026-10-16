# Agent rules

1. `docs/PRD.md` is the spec. If it and the code disagree, the PRD wins. If the PRD is unclear,
   **stop and ask**. Don't invent API fields.
2. Only the generated client talks to the server. Never hand-write models. Never call an endpoint that
   is not in `api/openapi.json`.
3. Work one phase at a time. For each phase: plan → implement → tests → CI green → commit(s) → phase
   note in `docs/CHANGELOG.md`. Small commits, imperative one-line messages, e.g.
   `feat(a0): theme generated from design tokens`. Add `Co-Authored-By: Codex <noreply@openai.com>` to
   commits Codex wrote.
4. **Parity**: same screens, same names, same flows, same error copy on both platforms. At the end of
   each phase, each agent writes a parity checklist (screens and states covered) into the phase note.
   The human compares the two.
5. Tests per phase: unit tests for every view model / state holder and the networking core, a
   MockWebServer / URLProtocol stub test for every API call used, and UI tests for the phase's main flow.
   A phase with failing or skipped tests is not done.
6. Secrets: none in the repo, ever. No analytics or third-party SDKs beyond §6 without asking.
7. Accessibility and dark mode are part of "done" in every phase, not a final polish pass.

## Branch delivery workflow

The owner merged the A0 foundation and requested this workflow on 2026-10-16:

- Start ongoing work from `dev`, commit there, and push to `origin/dev`.
- Finish the phase's implementation and validation before opening its final `dev` → `main` pull request.
- Keep `main` for reviewed merges. Preserve the owner's configured commit identity and the required Codex co-author trailer.


## Owner execution extension — 2026-10-16

The owner's latest complete-build instruction extends the original PRD: implement remaining phases on `dev`, commit and push tested progress there, and prepare the final `dev` → `main` PR after the batch. Intermediate merge gates are superseded; green CI remains mandatory before proceeding between phases. Phone AND tablet layouts are now in scope, including 320/360dp phones, common/large phones, 600/840dp tablets, portrait/landscape, light/dark and 2× font scale. Keep `docs/PRD.md` byte-identical to its owner source; record extensions here and in `docs/IMPLEMENTATION-PLAN.md`. Preserve Shaan's GitHub identity plus the Codex co-author trailer. Use the local Docker API for integration, contract/generated models only, no backend implementation edits or provider/attestation bypasses. Document precise backend-request issues for unavailable native capabilities; continue independent supported work.

Owner backend deferment: complete and test the full native flow using an explicit DEBUG-only, isolated in-memory demo. The backend will be supplied later. Never infer live success or invent production routes/models; missing native contract/provider configuration stays visibly unavailable outside demo.
