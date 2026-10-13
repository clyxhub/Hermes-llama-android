# AGENTS.md — CALISTO local ai (repo `hermes-llama-android`, package `com.hermes.llama`)

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
| 2026-10-13 | **Live-server model list fixed**: `LocalServer` now holds a multi-model `catalogue` (`data class ModelCard`); it is built from `ModelRepository.all()` (local GGUFs + selected cloud models) and published by `ChatViewModel.refreshModels()` and rebuilt by `ServerService` on start. `GET /v1/models` returns the whole catalogue plus the loaded snapshot (deduped) instead of at most one entry. | user: "the live server is not loading any available models be it local or cloud" |
| 2026-10-13 | `ModelRepository.rootsFrom(context)` added so non-UI callers (the foreground `ServerService`) resolve the scan roots exactly like the ViewModel | keep the server catalogue and the model library roots identical |
| 2026-10-13 | `ProviderStore.providersById(context)` — the call in `ModelRepository.cloudEntries()` was missing its `Context` (surfaced by the first real compile: `ModelRepository.kt:128:52 No value passed for parameter 'context'`) | `providersById` is context-taking like every other `ProviderStore` call |
| 2026-10-13 | **General artifact pipeline** (14th requirement): any generative model — local (e.g. a diffusion image model) or cloud (video/music/whatever) — can hand the app a file of any type; it is materialised to `filesDir/generated`, shown as a card under the assistant bubble, and can be viewed, **tested/interacted with** (HTML → in-app `WebView`), shared, opened, and downloaded to public Downloads | user: "receive, view and download files of anykind from generative models"; "i need to be able to interact with the artifact system e.g. say its an html file and you want to test it" |
| 2026-10-13 | Artifact pipeline is dependency-free (hand-rolled `ArtifactStore` build/scan/download + `FileProvider`), no new Gradle deps | keeps the dependency set unchanged (consistent with the embedded-server decision) |
| 2026-10-13 | **mDNS for the live server** (15th requirement): a hand-rolled, dependency-free mDNS/DNS-SD responder (`MdnsResponder.kt`) advertises `<host>.local` (A record = LAN IPv4) + `_http._tcp.local` (SRV→port, TXT `path=/`) so LAN clients type `http://calisto-local.local:8080/v1` instead of an IP. Started only when the server is LAN-bound (needs a `WifiManager.MulticastLock` or Android drops multicast) | user: "setup mdns for live server … type something like 'calisto-local.ai'" |
| 2026-10-13 | mDNS suffix is **`.local`**, not the `.ai` in the user's example | `.local` is resolved natively by mDNS/DNS-SD clients; a custom `.ai` TLD would need separate public-DNS delegation to resolve |
| 2026-10-13 | App display name → **"CALISTO local ai"** (16th requirement); the package id stays `com.hermes.llama` (internal `Theme.HermesLlama` style kept too, to avoid manifest churn) | user: "change the name of this app to CALISTO local ai" |
| 2026-10-13 | mDNS is dependency-free (`MulticastSocket` + hand-written DNS wire format), no new Gradle deps | consistent with the embedded-server / artifact-pipeline decisions |
| 2026-10-13 | Reviewed an external AI's 4 suggestions against the actual code. **Adopted #1 — KV-cache quantization**: `cparams.type_k`/`type_v` were left at the library default (f16); the UI can now pick q8_0 (default) / q4_0 / off, threaded via `nativeSetKvQuant` → both context-creation sites (`nativeLoadModel`, `nativeGenerateWithMedia`); an automatic f16 fallback protects models that reject a quantized cache | roughly halves KV memory — the concrete long-context OOM mitigation that was genuinely missing |
| 2026-10-13 | **Rejected #2 as already-implemented**: media already reaches mtmd as a **file path** (`mtmd_helper_bitmap_init_from_file` — no JVM raw-frame buffering), and the projector already runs on **CPU** (`p.use_gpu = false`). No change | verified in `llama_jni.cpp` |
| 2026-10-13 | **Rejected #3** (per-connection KV slots + priority-LRU eviction): this app does NOT create a context per connection — one global session, one context, serialized by `LocalServer.inferLock` + `g_session.infer_mutex`. There are no per-thread KV slots to evict, so the premise is false here; building an eviction manager would be over-engineering | avoids work built on a wrong model of the architecture |
| 2026-10-13 | **Rejected #4 as already-satisfied**: mmap is the default (`load_mode = LLAMA_LOAD_MODE_AUTO` → `LLAMA_LOAD_MODE_MMAP`); the code never disables it. No change | verified in `llama-model.cpp` (`llama_model_default_params`) |
| 2026-10-13 | **17th requirement — hardware-fit warning** in the HuggingFace section: new `DeviceFit.kt` reads the real memory profile (`ActivityManager.MemoryInfo.totalMem`/`availMem` + `largeMemoryClass`) and grades a model (plus its paired mmproj) **GREEN** (just right, ≤45% of total RAM) / **YELLOW** (barely fits, ≤80%) / **RED** (too big, >80%), nudged one level worse when the file exceeds *currently free* RAM. **Advisory only — download is never gated**; a device-summary line plus a coloured dot + label render per file | user: "reads the user's phone hardware and checks if a model is too big … red / yellow / green … it shouldn't prevent download either way, it's just a warning system" |

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
| v5 defect batch #2 (13 fixes, incl. live-server model list) | DONE | 99c73d4 | `LocalServer` multi-model catalogue (local GGUFs + selected cloud models) so `GET /v1/models` is never empty; published from `ChatViewModel.refreshModels()` and rebuilt by `ServerService` on start; plus the `ProviderStore.providersById(context)` fix. Build 38404602086 green. |
| **14th requirement — general artifact pipeline** | DONE | 99c73d4 | new `Artifact.kt` (data model + `ArtifactStore` + `ArtifactScanner`) and `ArtifactViewer.kt` (card + interactive viewer: image/audio/video/text inline, HTML **testable in an in-app WebView**); `Msg.artifacts` + `ChatStore` persistence; `ChatViewModel` scans every reply (Markdown/bare URLs, base64 fields, data-URIs) and materialises files into `filesDir/generated`; `MainActivity` renders a card under each assistant bubble + hosts the viewer; `FileProvider` (`@xml/file_paths`) enables share/open; "Save" exports to public Downloads via MediaStore. Build 38404602086 green. |
| **15th requirement — mDNS for the live server** | DONE | (this flow) | `MdnsResponder.kt` (dependency-free mDNS/DNS-SD: A + PTR + SRV + TXT over `MulticastSocket` + `MulticastLock`); wired into `ServerService` (start on LAN bind, stop on stop/destroy; notification shows the mDNS URL); `ACCESS_WIFI_STATE` + `CHANGE_WIFI_MULTICAST_STATE` added to the manifest; Settings gains a "LAN access (mDNS)" switch + an editable "Server name" (prefs `mdns_lan` / `mdns_host`). LAN users type `http://calisto-local.local:8080/v1`. |
| **16th requirement — rename to "CALISTO local ai"** | DONE | (this flow) | `app_name` (strings.xml) + drawer title + Info heading + Settings About row + empty-chat copy + server notification title + artifact/web-search User-Agents all renamed. Package id stays `com.hermes.llama`. Folded into the SAME Rule-8 build as the 15th requirement. |
| KV-cache quantization (advice #1) + mDNS join fix | DONE | c189f11, e21a7ad | `nativeSetKvQuant` (0=f16 / 1=q8_0 / 2=q4_0) applied at both context-creation sites; `LlamaBridge.setKvQuant` + a Settings selector (default Q8_0) + an automatic f16 fallback if a model rejects it. Also fixed the `MdnsResponder.joinGroup` compile errors. Build 38409453542 green. |
| **17th requirement — hardware-fit warning (HuggingFace)** | DONE (pending build) | (this flow) | `DeviceFit.kt` grades each downloadable GGUF (+ its paired mmproj) GREEN / YELLOW / RED against the phone's real RAM, with a device-summary line and a coloured dot + label per file. Advisory only — downloads are never blocked. |

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

| 38391843182 | 9df32a6 | success | `:app:compileReleaseKotlin` clean (no errors). APK 72 MB. Unpacked libs: `libggml-vulkan.so` 20.5 MB (`memory budget disabled` marker count **1**), `libmtmd.so` 1.39 MB, `libhermes_llama.so` mtmd occurrences **19**. Installed `pm install -r -d` → Success; `topResumedActivity` = `.MainActivity`; no `FATAL`. App JNI log: `llama backend initialised (gpu_offload_supported=1)` + `system: CPU : NEON = 1 | ARM_FMA = 1 | REPACK = 1`. Live UI dump = PocketPal-style Chat screen: 'Open drawer' desc, 'Chat' title, 'No Models Available', 'Download a model to start chatting with Hermes Llama', 'Download Model', plus our 'Thinking' / 'Web search' toggles. |

| 38401007129 | 8c1231b | failure | `:app:compileReleaseKotlin` — a single error: `ModelRepository.kt:128:52 No value passed for parameter 'context'` (`ProviderStore.providersById()` called without a `Context`). Log recovered from the auto-pushed `ci-diagnostics` branch (`ci-logs/last-build.log`). Fix: `ProviderStore.providersById(context)`. |

| 38404602086 | 99c73d4 | success | `:app:compileReleaseKotlin` clean; APK built (the 14th-requirement artifact pipeline compiles). Validates the 13 defect fixes (incl. the live-server multi-model catalogue) + the artifact pipeline + the `providersById(context)` fix. This is the green baseline the mDNS (15th) + rename (16th) batch builds on. |

| 38407168408 | 227e922 | failure | `:app:compileReleaseKotlin` — a single error: `MdnsResponder.kt:108:38 Type mismatch: inferred type is InetAddress but SocketAddress! was expected`. Cause: modern `MulticastSocket` has no `joinGroup(InetAddress, NetworkInterface)`; the overload takes a `SocketAddress`. Fix: `joinGroup(InetSocketAddress(group, MDNS_PORT), nif)` (and the no-NIC fallback). Native CMake configure ran fine; the failure was Kotlin-only. Folded into the same build as the KV-quant work. |

| 38408362327 | c189f11 | failure | `:app:compileReleaseKotlin` — a single error: `MdnsResponder.kt:115:49 Type mismatch: inferred type is InetSocketAddress but InetAddress! was expected`. The 2-arg fix at :109 is correct, but the **1-arg** `joinGroup(SocketAddress)` overload does not exist on Android — only `joinGroup(InetAddress)` (deprecated) does. Fix: revert the fallback to `ms.joinGroup(group)`. Everything else in this batch (native `type_k`/`type_v` KV-quant C++, `LlamaBridge`, `ChatViewModel`, `SettingsScreen`) compiled clean — the C++ `buildCMakeRelease` task passed. |

| 38409453542 | e21a7ad | success | `:app:compileReleaseKotlin` clean; APK built and installed. Validates the mDNS (15th) + rename (16th) + KV-cache quantization (advice #1) batch, with the corrected `MulticastSocket.joinGroup` calls. Green baseline the 17th-requirement (hardware-fit warning) batch builds on. |

**v5 overhaul: DONE.** The single Rule-8 fix+restyle build (38391843182) is green and installed;
this docs-only tracker update is pushed with `[skip ci]` so it does not consume another build.
Still needs a GGUF present on-device to exercise a real load + projector auto-pair + MEMBUDGET.

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
14. **General artifact pipeline (added mid-flow):** receive / view / download a file of ANY kind from ANY generative
    model — local (e.g. a diffusion image model) or cloud (video / music / "whatever file type model") — and be able
    to **interact** with it (e.g. test an HTML file in an in-app WebView). Model-agnostic: any produced file flows
    through the same pipeline.
15. **mDNS for the live server (added mid-flow):** don't force LAN users to type an unmemorable IP — advertise a name via
    mDNS/DNS-SD so they can use e.g. `http://calisto-local.local:8080/v1`. (The user's example `.ai` becomes `.local`,
    the suffix mDNS actually resolves.)
16. **Rename the app to "CALISTO local ai" (added mid-flow).**
17. **Hardware-fit warning in the HuggingFace section (added mid-flow):** read the phone's real RAM/GPU and grade each
    downloadable model GREEN (just right) / YELLOW (barely fits) / RED (too big). It must NOT prevent any download —
    it is purely a warning so you know whether a model will fit your phone.

Implementation surface (current flow):
- New data layer: `ChatStore.kt` (multi-chat), `ModelRepository.kt` (local scan + mmproj pairing + cloud merge),
  `ProviderStore.kt` (unlimited keys + per-model selection), `VoiceIO.kt` (TTS/STT), `WebSearch.kt` (DuckDuckGo context injection),
  `Artifact.kt` + `ArtifactViewer.kt` (general generated-file pipeline: materialise → card → interactive viewer),
  `MdnsResponder.kt` (dependency-free mDNS/DNS-SD responder that advertises the LAN server by name).
- Rewritten: `ChatViewModel.kt` (UiState + GPU→CPU fallback + `/no_think` + trace stripping), `MainActivity.kt`
  (drawer host + `Section` enum + full chat screen).
- All five section screens exist: `ChatsScreen` / `HuggingFaceScreen` / `ProvidersScreen` (built this
  flow) plus `ModelsScreen` / `SettingsScreen` (rebuilt to PocketPal parity). `MainActivity.kt` hosts
  the shared drawer + `Section` enum, the PocketPal-style chat screen + empty "No Models Available"
  state, and the `shareText` / `copyUriToTemp` helpers used across screens.
- Structural gate: 23 `.kt` files, `BAD FILES: 0` (char-by-char tokenizer that skips comments/strings; brace+paren balance).

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
