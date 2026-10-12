# Hermes Llama

**Pocket AI, but better — lots more features.**

An Android app built on [llama.cpp](https://github.com/ggml-org/llama.cpp) that runs GGUF LLMs
locally on your phone, with a streaming chat UI. This repo is the ground-up rebuild.

> **Stage (a) — current status:** CPU-only vertical slice. Load a `.gguf` model, chat with
> token-by-token streaming. No GPU, no vision, no server yet. Those land in stages (b)–(h).

---

## Why this repo exists

The earlier prototype drove llama.cpp through a **Termux shell**. That works for CPU, but on a
non-rooted device (e.g. Samsung S21+, Adreno 660) the Android **SELinux `untrusted_app` domain
denies access to `/dev/dri/*`**, so GPU offload is impossible from a shell. A properly built APK
uses sanctioned APIs and is the only realistic path to **Vulkan** acceleration.

So: this is a real Android app, built by the Android toolchain, installed like a normal app.

---

## Architecture (stage a)

```
MainActivity (Jetpack Compose)
      │  load .gguf via SAF  →  materialise() into filesDir
      ▼
ChatViewModel ──► PresetEngine        (family detection + chat template)
      │        └► ModelCapabilities   (stub; filled from GGUF metadata in stage c)
      ▼
LlamaBridge (Kotlin object, System.loadLibrary("hermes_llama"))
      ▼
llama_jni.cpp  ── LlamaSession { model, ctx, vocab, infer_mutex, cancel flag }
      ▼
llama.cpp  (third_party/llama.cpp, git submodule) → libllama / libggml / libcommon
```

### Deliberate design choices
- **One global `LlamaSession` + one `infer_mutex`.** This is not laziness — it is the seed of the
  embedded OpenAI-compatible server's inference queue (stage d). Serialising inference now means
  no rework later.
- **`n_gpu_layers` is accepted but forced to 0** in stage (a). The parameter exists so the Vulkan
  auto-tuner (stage b) is a pure behaviour change, not an interface change.
- **`ModelCapabilities` is a stub on purpose.** Stage (c) populates it from **mmproj GGUF metadata**
  (`clip.projector_type`, encoder arch, tensor shapes) — *not* file extensions. mmproj is
  modality-agnostic: the same projector machinery can carry vision, audio, or video.
- **`PresetEngine` currently keys off the file name only.** Stage (e) reads real GGUF metadata
  (chat template, quantisation, trained context length) and drives the auto-tuner from that.

---

## Requirements

| | |
|---|---|
| Android | 9.0+ (API 28), `arm64-v8a` only in stage (a) |
| JDK | 17 |
| AGP / Kotlin | 8.5.2 / 1.9.24 |
| Gradle | 8.7 (wrapper) |
| NDK | r26+ (installed automatically by the CI `setup-android` action) |

---

## Building

### Locally (needs Android SDK + NDK)

```bash
git clone --recurse-submodules https://github.com/<you>/hermes-llama-android.git
cd hermes-llama-android
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

If you cloned without submodules:

```bash
git submodule update --init --recursive
```

### Via GitHub Actions (recommended, no local SDK needed)

The workflow `.github/workflows/build.yml` builds on `ubuntu-latest`, which has the Android SDK
and NDK preinstalled.

1. Push to `main` (or run **Actions → Build APK → Run workflow**).
2. Download the `hermes-llama-release-apk` artifact when the job goes green.

The release build is signed with the **debug key** for CI convenience. This is fine for testing;
it is not suitable for the Play Store. Swap in a real keystore before any public release.

---

## Running

1. Install the APK on your `arm64-v8a` device.
2. Tap **+** and pick a `.gguf` file (the app copies it into its private `filesDir`).
3. Wait for the status card to show the detected preset and native system info.
4. Type a message and send. Tokens stream in as they are generated; **stop** cancels mid-stream.

---

## Roadmap

| Stage | Scope |
|---|---|
| **(a)** | CPU load + streaming chat, green CI build ← *you are here* |
| (b) | Vulkan GPU offload + auto-tuner (`-DGGML_VULKAN=ON`) |
| (c) | mmproj multimodality, capabilities from GGUF metadata |
| (d) | Embedded OpenAI-compatible server + inference queue |
| (e) | Real GGUF metadata → presets, quant, context |
| (f) | Reasoning / thinking-mode handling |
| (g) | Model sources: local / in-app Hugging Face portal / BYOK cloud |
| (h) | Capability-driven UI, polish, performance |

---

## Repo layout

```
.
├── .github/workflows/build.yml    # CI: SDK+NDK → assembleRelease → APK artifact
├── app/
│   ├── build.gradle.kts           # module config, CMake args, ABI filter
│   └── src/main/
│       ├── cpp/
│       │   ├── CMakeLists.txt     # wires llama.cpp submodule
│       │   └── llama_jni.cpp      # JNI bridge (LlamaSession)
│       ├── java/com/hermes/llama/ # Kotlin: bridge, VM, UI, presets, capabilities
│       └── res/                   # strings, theme
├── third_party/llama.cpp          # git submodule (pinned)
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## Licence

llama.cpp is MIT-licensed and pulled in as a submodule. App code: TBD.
