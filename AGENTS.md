# AGENTS.md — Hermes Llama (working rules — READ FIRST EVERY SESSION)

> Master spec source of truth: `/sdcard/Download/Android-llama-cpp-app-plan.md` (v4)

## Hard rules
1. Read this file + STAGE-TRACKER + master spec BEFORE any coding. Never code from memory.
2. Every commit message references the stage/section it implements.
3. NO stage skipping or detours without explicit user instruction.
4. Any work outside plan order goes in DEVIATIONS below — visible, always.
5. Update STAGE-TRACKER after every commit.
6. **This file is LIVING.** Whenever a decision changes, a new constraint appears, a
   better approach is found, or the user overrides something — update AGENTS.md in the
   same commit. Stale AGENTS.md = drift. The file must always reflect current truth.
7. Record decision changes under DECISIONS LOG below with date + reason, so reversals are
   traceable instead of silent.

## DECISIONS LOG
| Date | Decision | Reason |
|---|---|---|
| 2026-10-13 | 4B GPU-vs-CPU benchmark skipped | user instruction ("skip 2") |
| 2026-10-13 | AGENTS.md must be updated whenever decisions change/improve | user instruction |

## Staged plan (from v4 §9)
- [ ] a. llama.cpp CPU only — working chat UI, CI green
- [ ] b. Vulkan offload — auto ngl + auto-tuner
- [ ] c. mmproj capability pipeline — modality-agnostic projector + dynamic input UI
- [ ] d. HTTP server — OpenAI + native endpoints, foreground service
- [ ] e. Thinking mode + PresetEngine — auto-presets, reasoning UI
- [ ] f. Audio modality
- [ ] g. QNN NPU flavor (optional)
- [ ] h. Model sources A/B/C — local import, HF portal, BYOK

## STAGE-TRACKER
| Stage | Status | Commits | Notes/Deviations |
|---|---|---|---|
| a CPU + UI + CI | DONE | eff947b..a822f6c chain | |
| b Vulkan + auto-tuner | PARTIAL | 1e6386b, a9a4fba | MANUAL toggle only — auto-tuner + per-(device,model) config persistence NOT built. Missing: auto-ngl, benchmarking, config persistence. |
| c mmproj pipeline | NOT STARTED | | user skipped 4B benchmark |
| d HTTP server | NOT STARTED | | |
| e Thinking + PresetEngine | NOT STARTED | | |
| f Audio | NOT STARTED | | |
| g QNN | NOT STARTED | | optional |
| h Model sources | NOT STARTED | | |

## DEVIATIONS (work outside plan order)
- Auto-load last model + tok/s readout (`a9a4fba`) — not in plan stages, quick win
- Chat history persistence + clear button (`ef550f6`) — not in plan stages, quick win
