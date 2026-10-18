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

The owner's latest delivery instruction, updated 2026-10-18, authorizes the existing PR #4 delivery workflow:

- Consolidate ongoing mobile work on `dev`, retain phase commits, and push tested progress to `origin/dev`; do not create other pull requests or feature branches.
- Complete exact-source local/CI checks, the required full device matrix and actual screenshot review, then merge only the reviewed head of existing [PR #4](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/pull/4) into `main`. Keep `dev` and preserve phase commits.
- Preserve the owner's configured commit identity, the required Codex co-author trailer and all immutable inputs. A failed or skipped required gate cannot establish acceptance.


## Owner execution extension — 2026-10-16

The owner's complete-build extension adds remaining phases on `dev` with tested progress committed and pushed there. The latest delivery instruction authorizes only existing PR #4: consolidate and validate `dev`, then merge its reviewed head into `main`, retaining `dev` and phase commits. Do not create other pull requests or feature branches. Intermediate merge waits are superseded; green CI remains mandatory for phase acceptance. Phone AND tablet layouts are now in scope, including 320/360dp phones, common/large phones, 600/840dp tablets, portrait/landscape, light/dark and 2× font scale. Keep `docs/PRD.md` byte-identical to its owner source; record extensions here and in `docs/IMPLEMENTATION-PLAN.md`. Preserve Shaan's GitHub identity plus the Codex co-author trailer. Use the local Docker API for integration, contract/generated models only, no backend implementation edits or provider/attestation bypasses. Document precise backend-request issues for unavailable native capabilities; continue independent supported work.

Owner backend deferment: complete and test the full native flow using an explicit DEBUG-only, isolated in-memory demo. The backend will be supplied later. Never infer live success or invent production routes/models; missing native contract/provider configuration stays visibly unavailable outside demo.

Owner UX extension: implement the shared `docs/mobile-auth-ux-spec.md`, first-use introduction/replay, branded native auth and full-app usage guidance. Keep master logos static, generate motion from `tokens/mobile-motion.json`, respect system reduced motion and native autofill, and exercise all sign-in methods in explicitly labelled DEBUG previews when the production native contract is unavailable. Authenticated account/security management remains A7. Green CI, scalable phone/tablet layouts and screenshot review apply to this checkpoint as to every phase.

Owner repository boundary correction: keep app-specific UX specifications, motion JSON and implementation artifacts inside this mobile repository. The Design submodule stays pinned to the original brand baseline `fd28f825086a2cac7fa297881f1860b9d332db69`; do not push app-specific additions to the Design repository. Shared brand masters/tokens are consumed unchanged.
