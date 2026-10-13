# AGENTS.md — Hermes Llama (`com.hermes.llama`)

**READ THIS FILE FIRST, EVERY SESSION. Never code from memory of the plan.**
Master spec source: `/sdcard/Download/Android-llama-cpp-app-plan.md` (v4). Full spec embedded below.

---

## HARD RULES
1. Read this file (including the embedded spec) BEFORE writing any code. Never code from memory.
2. Every commit message references the stage/section it implements.
3. NO stage skipping or detours without explicit user instruction.
4. Any work outside plan order goes in DEVIATIONS — visible, always.
5. Update STAGE-TRACKER after every commit.
6. **This file is LIVING.** Any decision change, new constraint, better approach, or user
   override → update AGENTS.md in the same commit. Stale = drift.
7. Every decision change is logged in DECISIONS LOG with date + reason.
8. **BUILD BUDGET (user constraint):** the user has a limited number of GitHub Actions
   build chances. Do NOT do stage-by-stage APK build+test cycles. Implement all agreed
   work in ONE flow, then build once. Batch everything into a single push/build.
   Local dry-run/compile checks first where possible; never spend a CI build to test a
   trivial increment.

---

## DECISIONS LOG
| Date | Decision | Reason |
|---|---|---|
| 2026-10-13 | 4B GPU-vs-CPU benchmark skipped | user instruction ("skip 2") |
| 2026-10-13 | AGENTS.md must be updated whenever decisions change/improve | user instruction |
| 2026-10-13 | No more stage-by-stage builds; implement remaining stages in ONE flow, single CI build | user has limited GH Actions build budget |
| 2026-10-13 | Spec must live INSIDE AGENTS.md (not just referenced) | user wants plan always in view |
| 2026-10-13 | Implement stages b(rest)/c/d/e/h in ONE flow; single CI build | user build-budget constraint |
| 2026-10-13 | Auto-tuner: topology heuristic + first-measured tok/s, NOT the full multi-reload benchmark | N model reloads on 1–3 GB GGUFs cost RAM/time; adjacent thread counts differ marginally |
| 2026-10-13 | Chain-of-thought delimiters stored as `\u003C…\u003E` unicode escapes in source | literal tags are silently stripped by the tooling pipeline |
| 2026-10-13 | Embedded server: hand-rolled `HttpURLConnection`/`ServerSocket`, no 3rd-party dep | keeps the dependency set unchanged (no new Gradle deps) |
| 2026-10-13 | **v5 UX overhaul**: adopt PocketAI's UI/UX as the reference style ("copy style and everything… verbatim"); differentiate via extra features + a different name | user instruction |
| 2026-10-13 | Navigation = multi-section drawer (Chat / Chats / Models / HuggingFace / Providers / Settings); HuggingFace is its OWN section, not a sub-tab | user: "huggingface page should be in a different section" |
| 2026-10-13 | Unlimited API keys per provider + per-model select/unselect; selected provider models surface in the Models page alongside local GGUFs | user: "unlimited api keys… per model select" |
| 2026-10-13 | Every local GGUF is auto-paired to its mmproj and loaded from the Models page (no repeated file picker) | user: "model has no pair option for mproj"; "all on-device models… selectable to load" |
| 2026-10-13 | Thinking OFF ⇒ append `/no_think` to the final user turn AND strip any emitted reasoning trace from the reply | user: "model is thinking anyways regardless if thinking mode is on or off" |
| 2026-10-13 | HuggingFace search state is hoisted OUT of the composable so it survives navigating away | user: "searches doesn't erase" (search content was being cleared) |
| 2026-10-13 | Multi-chat persistence moved to `ChatStore` (prefs key `chats_v2`) replacing the single-history blob | proper chat history (pin/rename/delete/share) |

---

## STAGE-TRACKER
| Stage | Status | Commits | Notes |
|---|---|---|---|
| process: AGENTS.md | DONE | 7febb49, f0fe0df | living tracker; full v4 spec embedded |
| a CPU + UI + CI | DONE | eff947b..a822f6c chain | |
| b Vulkan + auto-tuner | DONE | 1e6386b + this flow | Vulkan dispatcher/memory patches; `AutoTuner.kt` per-(device,model) config + persistence |
| c mmproj capability pipeline | DONE | this flow | `llama_jni.cpp` mtmd pipeline; projector auto-pair (`*mmproj*.gguf`); `ModelCapabilities.ofLoadedModel()`; capability-gated composer |
| d HTTP server | DONE | this flow | `LocalServer.kt` (OpenAI + native endpoints, SSE streaming) + `ServerService.kt` foreground service |
| e Thinking mode + PresetEngine | DONE | this flow | `PresetEngine` GGUF-metadata refine + named-template fallback; collapsible reasoning-trace UI |
| f Audio modality | PARTIAL | this flow | served generically whenever the loaded mmproj declares audio (`nativeSupportsAudio`); no dedicated audio preset/UI copy yet |
| g QNN NPU flavor | SKIPPED (optional) | | Qualcomm-only, licensing risk |
| h Model sources A/B/C | DONE | this flow | A local import (SAF); B `HuggingFaceClient.kt` (search / file list / resumable download); C `CloudEngine.kt` + `CloudProviders` (12 BYOK providers) |
| quick wins (off-plan) | DONE | a9a4fba, ef550f6 | auto-load model, tok/s, chat history, clear button |
| v5 UI/UX overhaul (PocketAI-parity) | IN PROGRESS | (uncommitted) | multi-section drawer; new data layer `ChatStore`/`ModelRepository`/`ProviderStore`/`VoiceIO`/`WebSearch`; rewritten `ChatViewModel`+`MainActivity`; pending: `ChatsScreen`/`ModelsScreen`/`HuggingFaceScreen`/`ProvidersScreen`/`SettingsScreen`, GPU-crash fix, single CI build |

## DEVIATIONS (work outside plan order)
- Auto-load last model + tok/s readout (`a9a4fba`) — not a plan stage
- Chat history persistence + clear button (`ef550f6`) — not a plan stage
- Auto-tuner uses a topology heuristic + persists the first measured tok/s instead of the
  §2 "benchmark candidate configs on a tiny prompt" loop (avoids N model reloads). Spec deviation, logged.
- Thinking delimiters are written to source as `\u003C…\u003E` unicode escapes rather than raw
  angle brackets, because literal tags are stripped by the tooling pipeline (caused an earlier empty-literal bug).
- `LocalServer` implements `/props` and `/tokenize` plus the OpenAI surface, but not
  `/detokenize` or `/v1/embeddings` yet (declared in §5) — deferred.
- **v5 (PocketAI-parity UX):** the whole UI/UX is being reworked to match PocketAI's look-and-feel
  per user instruction, differentiation ONLY via extra features + app name. Supersedes the stage-(a)
  single-screen UI; tracked as the "v5 UI/UX overhaul" row above.
- Storage layer: chats / models / provider-keys still use `SharedPreferences` JSON (not Room, as
  §1/§13 propose). Rationale: zero new Gradle deps; revisit if/when the schema outgrows JSON.

---

## BUILD RECORD
| Run | SHA | Result | Verification |
|---|---|---|---|
| 38381977970 | 994d98c | success | libs unpacked: `libggml-vulkan.so` 20.5 MB, `libmtmd.so` 1.39 MB, `libllama.so`, `libllama-common.so`, `libhermes_llama.so`; `strings libggml-vulkan.so \| grep -c 'memory budget disabled'` = **1**; mtmd symbols in `libhermes_llama.so` = **19**; installed `pm install -r -d`; `topResumedActivity` = `.MainActivity`, no `FATAL`. |

Single-flow build per Rule 8 — the **one** CI build for stages b(complete)/c/d/e/h.

Not yet exercised on-device (recommended next manual test): actual model load +
projector auto-pair + a real generation (needs a SAF file pick, UI-driven).

| 38389045212 | a0c15e1 | failure | `:app:compileReleaseKotlin` — 8 errors, all `MainActivity.kt` 198/201/207/317–321: "Type mismatch: inferred type is () -> Unit but MutableInteractionSource was expected". Cause: a trailing content lambda was passed to Material3 `DropdownMenuItem`, which has **no** trailing slot. Fix: use the `text =` / `leadingIcon =` slots. |

**v5 overhaul build #2: IN PROGRESS.** The follow-up (single allowed) build folds in (a) the
DropdownMenuItem fix and (b) a full PocketPal-parity restyle of `MainActivity.kt` /
`ModelsScreen.kt` / `SettingsScreen.kt`, keeping every differentiator feature. Per Rule 8 this
restyle rides the same fix build. (Gap the user flagged 2026-10-13: AGENTS.md was not being kept
current *during* the rework — Rule 6 violation, corrected here.)

---

## v5 UX OVERHAUL — user requirements (source of truth for the current flow)
User msg (paraphrased, high-expectation, verbatim intent):
1. GPU ON crashes the app — must not crash; degrade safely.
2. Model has no explicit mmproj pair option.
3. Model "thinks" regardless of thinking mode on/off.
4. HuggingFace must be its OWN section; search content must persist (not be erased); needs a real download section.
5. All on-device models must appear in an in-app Models page WITH their mproj, selectable to load (no repeated file picker).
6. Unlimited API keys per provider; per-model select/unselect; all selected provider models surface alongside local models.
7. Proper chat interface: file upload, internet search, TTS/STT voice, copy/edit/retry/delete/read-aloud per message,
   chat history (pin/rename/delete/share), new-chat button, share-conversation script.
8. **Reference style = PocketPal AI (`com.pocketpalai`) — "copy style, design, placement and UI/UX".**
   Differentiation = extra features PocketPal lacks + a different app name. (Captured on-device via
   `uiautomator dump`: Chat = hamburger + left "Chat" title + centered "No Models Available" empty
   state + full-width composer with an icon row beneath; drawer = full-width icon+label items
   Chat/Pals/Models/Benchmark/Settings/App Info; Models = "Available to Download" banner + ＋ FAB +
   name-left/size-right cards; Settings = labelled section headers + label/subtitle/switch rows.)

Implementation surface (current flow):
- New data layer: `ChatStore.kt` (multi-chat), `ModelRepository.kt` (local scan + mmproj pairing + cloud merge),
  `ProviderStore.kt` (unlimited keys + per-model selection), `VoiceIO.kt` (TTS/STT), `WebSearch.kt` (DuckDuckGo context injection).
- Rewritten: `ChatViewModel.kt` (UiState + GPU→CPU fallback + `/no_think` + trace stripping), `MainActivity.kt`
  (drawer host + `Section` enum + full chat screen).
- All five section screens exist: `ChatsScreen` / `HuggingFaceScreen` / `ProvidersScreen` (built this
  flow) plus `ModelsScreen` / `SettingsScreen` (rebuilt to PocketPal parity). `MainActivity.kt` hosts
  the shared drawer + `Section` enum, the PocketPal-style chat screen + empty "No Models Available"
  state, and the `shareText` / `copyUriToTemp` helpers used across screens.
- Structural gate: 20 `.kt` files, `BAD FILES: 0` (comment/string-stripped brace+paren balance).

---

# EMBEDDED MASTER SPEC — Android llama.cpp Multimodal Chat App (v4)

## 0. One-line summary
An Android app that runs GGUF LLMs + GGUF mmproj projectors on-device using every
accelerator it can reach (GPU via Vulkan, CPU with tuned SIMD/threads, NPU only where
genuinely supported), provides a modern multimodal chat UI with thinking mode and
auto-presets, exposes the loaded model over an OpenAI-compatible HTTP server for other
apps, and can source models three ways: local files, an in-app HuggingFace portal, or BYOK
cloud providers.

## 1. Architecture overview (layers)
- UI Layer (Compose): Chat screen · Model manager · Settings · Server panel; input composer generated from declared model capabilities
- Domain Layer: ChatRepository · ModelRepository · PresetEngine · CapabilityRegistry · ThinkingModeController
- Inference Abstraction: `LocalEngine → JNI → llama.cpp`  |  `CloudEngine → HTTP → provider APIs (BYOK)`
- Native Layer (JNI/NDK): llama.cpp core · ggml backends (CPU/Vulkan/QNN) · mtmd (multimodal) · projector pipeline · KV cache mgmt
- Server Layer: OpenAI-compatible + llama.cpp native endpoints; binds to loaded LocalEngine, serializes requests
- Storage Layer: Room (chats, models, providers, presets) · SAF · app files

Key design rule: UI/domain never know local-vs-cloud, and never hardcode a modality. Both
come from the `CapabilityRegistry`.

## 2. Accelerator strategy (GPU / NPU / CPU)
| Backend | Coverage | Status | Notes |
|---|---|---|---|
| CPU | 100% of devices | Primary, always available | NEON, i8mm, dotprod; thread count = perf cores; batch tuning |
| Vulkan | ~all modern Android (2016+) | Primary GPU path | `-ngl` layer offload; the real reason "CPU-only" apps are fast |
| OpenCL / Adreno | Qualcomm | Optional secondary | Legacy path; Vulkan generally preferred |
| QNN / Hexagon NPU | Qualcomm only | Experimental, optional flavor | Needs pre-quantized (e.g. Q4_0) + model-specific conversion; SDK licensing to check |
| NNAPI | — | Not used | Deprecated/frozen, no benefit |

Reality checks:
- MediaTek / Exynos / Tensor NPUs have no usable llama.cpp backend. No promises there.
- "Uses every accelerator" = auto-detect, rank, fall back gracefully — not NPU on every phone.

Auto-tuner: on model load, benchmark candidate configs (threads, ngl, batch size) on a tiny
prompt, pick the fastest that fits RAM. Persist the winning config per (device, model) pair.

Pocket AI note: its "no GPU access" message is almost certainly misreporting — it uses
Vulkan offload. Speed = Q4 quant + small model + flash attention + KV-cache quant + mmap'd
weights (fast load = page-in, not compute). No hidden CPU trick. Forced pure-CPU on a 3B Q4
mid-range chip ≈ 5–12 tok/s.

## 3. Multimodality — capability-driven, not vision-only
mmproj is a projector into the LLM embedding space, not a "vision file". Whatever modality
it was trained for, it handles.

Pipeline (one, generic): `input bytes → modality preprocessor → modality encoder → projection → embed splice → LLM`

Capability detection: read mmproj GGUF metadata (`clip.projector_type`, encoder arch, tensor
shapes, modality tags) → infer modality set. One `CapabilityRegistry` entry per loaded model:
```kotlin
data class ModelCapabilities(
    val text: Boolean,
    val vision: Boolean,
    val audio: Boolean,
    val video: Boolean,        // frame sampling over vision
    val reasoning: Boolean,    // supports thinking mode
    val embeddings: Boolean,   // can serve /v1/embeddings
    val tools: Boolean,
)
```
Consequences:
- Input composer is GENERATED from capabilities — image button only if vision, mic only if audio.
- New upstream projector types are picked up by capability routing; no app update needed.
- Upstream reality: vision mature; audio experimental (Qwen2-Audio, Ultravox, Voxtral); video = frame extraction through vision.

## 4. Model sources — three paths
A. Local import: SAF/file picker; auto-detect and pair its mmproj; validate GGUF header + magic + arch; store reference or copy into app storage (user's choice).
B. In-app HuggingFace portal: NOT a redirect. Native in-app browser over the HF API. Search all HF models, open repo, list files/variants/quants with sizes. Download model + mmproj inside the app: progress, resume, integrity check. Optional HF token for gated repos. Storage manager + cleanup.
C. BYOK cloud providers: ship preset provider configs with correct base URLs + auth scheme (OpenAI, Anthropic, Google Gemini, Groq, Mistral, xAI, OpenRouter, DeepSeek, Together, Fireworks, Cerebras, Cohere, …). Flow: pick provider → paste API key → app fetches provider model list → search + select. Fallback: providers w/o list endpoint use a static curated list. Cloud models implement the SAME engine interface as local ones, so streaming, samplers, thinking mode, and the server all work uniformly (server fronts local models by default; cloud proxying is a toggle).

## 5. Embedded HTTP server
- OpenAI-compatible: `/v1/chat/completions` (SSE + non-streaming), `/v1/completions`, `/v1/models`, `/v1/embeddings` (if supported)
- llama.cpp native: `/completion`, `/tokenize`, `/detokenize`, `/health`, `/props` (context size, chat template, modalities, sampler defaults so clients auto-configure)
- Multimodal over the wire: OpenAI `image_url` parts + base64 audio parts
- Binding: default `127.0.0.1:8080`; opt-in `0.0.0.0` for LAN. LAN off by default.
- Auth: optional bearer token; LAN mode warns/requires a key
- Lifecycle: foreground service, survives UI backgrounding. One model → one server → many clients
- Concurrency: single inference queue protecting the KV cache; optional second context slot (costs RAM)
- CORS configurable for browser clients

## 6. Chat UI & features
- Streaming responses, stop generation, regenerate, edit-and-resend
- Chat history, folders, branches (edit from any message)
- Markdown + code blocks with syntax highlighting + copy
- System prompt per chat; persona/character support
- Full sampler panel: temperature, top-p, top-k, min-p, typical-p, repeat/frequency/presence penalties, seed, mirostat, DRY, XTC
- Context length, context shift strategy, stop strings, batch size, threads, ngl
- Multi-chat, export/import (JSON), search across chats
- Attachments: images/audio/documents per capabilities; doc attach with RAG-lite chunking
- Token stats: prompt tok, gen tok, tok/s, context usage bar
- Thinking mode: toggle + collapsible reasoning block; auto-on for reasoning models; per-model default; separate render styling for the trace

## 7. Instant defaults on model load (PresetEngine)
1. Read GGUF metadata: arch, param count, quant type, context length, chat template, vocab, projector attached?
2. Detect family (Llama / Qwen / Gemma / Phi / Mistral / DeepSeek / …)
3. Apply a preset: context size, threads, batch, sampler defaults, chat template, reasoning toggle, GPU layer offload, stop strings
4. Run the auto-tuner (§2) to finalize perf settings
5. Persist overrides per model so user tweaks stick

## 8. Build & CI (GitHub Actions)
- NDK + CMake; primary ABI `arm64-v8a` (optional `x86_64` for emulator)
- Build llama.cpp + ggml backends; Vulkan headers + ShaderGen/glslc step
- QNN as a separate optional flavor (Qualcomm SDK licensing must be checked before shipping)
- Pipeline: cache NDK/CMake deps → build native → assemble APK → sign → artifact
- Highest-risk area. Multi-hour pipeline, expect debugging.

## 9. Staging plan (build order)
| # | Stage | Deliverable |
|---|---|---|
| a | llama.cpp CPU only | Working chat UI, GH Actions green |
| b | Vulkan offload | Auto ngl + auto-tuner |
| c | mmproj capability pipeline | Modality-agnostic projector + dynamic input UI |
| d | HTTP server | OpenAI + native endpoints, foreground service |
| e | Thinking mode + PresetEngine | Auto-presets, reasoning UI |
| f | Audio modality | Audio projector support |
| g | QNN NPU flavor | Optional Qualcomm-only build |
| h | Model sources A/B/C | Local import, HF portal, BYOK |

Note (user override 2026-10-13): stages c–h are to be implemented in ONE flow with a single
CI build, not stage-by-stage.

## 10. Known risks / open questions
- GH Actions native build complexity (biggest risk)
- QNN SDK licensing for redistribution
- Audio modality maturity upstream
- HF API rate limits / gated repo handling
- RAM ceiling on low-end devices → model-size gating + warnings
- Thermal throttling on sustained generation
- Provider API drift (BYOK) → keep provider configs data-driven/updatable

## 11. Reference product framing
Positioning: "Pocket AI, but better, with a lot more features."

| Area | Pocket AI (reference) | This app (target) |
|---|---|---|
| Local GGUF models | Yes | Yes |
| mmproj / multimodality | Image-focused | Capability-driven: any modality the projector declares |
| Accelerators | Reports CPU-only (actually Vulkan) | Honest auto-detect GPU/CPU/NPU + auto-tuner + per-device persisted config |
| In-app model browser | Yes | Yes + quant/variant listing, resume, integrity check, storage mgmt |
| Cloud models | Limited/none | Full BYOK multi-provider with preset configs + model list fetch |
| Embedded server | No | Yes — OpenAI-compatible + native endpoints |
| Thinking mode | Basic/none | Full toggle + collapsible trace + per-model default + auto-on |
| Sampler controls | Limited | Full panel (temp, top-p/k, min-p, typical, penalties, seed, mirostat, DRY, XTC) |
| Auto-defaults on load | Basic | PresetEngine: family detection + perf auto-tune |
| Chat mgmt | Basic | Folders, branches, search, export/import |
| Token stats | Minimal | tok/s, prompt/gen tokens, context usage bar |

- UX baseline: mirror Pocket AI's model-manager + in-app-browser flows, then add extras
- Perf baseline: match or beat its tok/s on same device + model (Vulkan offload)
- Do NOT copy its misleading "no GPU access" messaging — report the actual active backend

---

## DEVICE FACTS (verified)
- SoC SM8350 / Adreno 660 (Qualcomm); UMA; fp16; no matrix cores; `ro.hardware.vulkan=adreno`
- Android 15 / One UI 7; fingerprint `samsung/t2quew/t2q:15/AP3A.240905.015.A2/G996U1UESIHYL1:user/release-keys`
- Not rooted (no `run-as`), so prefs verified behaviorally, not by reading files
- Local models: `.../Hermes/models/qwen 3.5-2b claude/Qwen3.5-2B-Claude-4.6-Q4_K_M.gguf` (1.27 GB) + `...-mmproj-Q8_0.gguf` (365 MB); `.../Qwen3.5-4b-Uncensored-NonReasoning/Qwen3.5-4b-Uncensored-NonReasoning.Q4_K_M.gguf`
- Repo: `/sdcard/Download/hermes-llama-android`, remote `git@github.com:clyxhub/Hermes-llama-android.git`, branch `main`
- Vulkan fix: `HERMES-PATCH(vulkan-device-dispatcher)` + `HERMES-PATCH(vulkan-device-memory)` in `app/src/main/cpp/CMakeLists.txt`; `HERMES-PATCH(cpu-only-devices)` in `llama_jni.cpp`; verify via `strings libggml-vulkan.so | grep -c 'memory budget disabled'` == 1
- Build install: fresh CI keystore each run → `pm uninstall com.hermes.llama; pm install -r /data/local/tmp/<apk>`
