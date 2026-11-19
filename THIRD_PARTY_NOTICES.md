# Third-party notices

PocketCompanion's own code is MIT licensed (see `LICENSE`). It is built on the work below.

## Bundled in the app

| Component | Version | License | Where |
|---|---|---|---|
| [Duix-Mobile SDK](https://github.com/duixcom/Duix-Mobile) (avatar rendering and lip sync) | 4.1.1, plus `DUIX.releaseAndWait()` | [duix.com Community License](duix-sdk/LICENSE.md) | `duix-sdk/` |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) (`llama-server`) | b12224, android-arm64 release, symbols stripped | MIT | `app/src/main/jniLibs/` |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (speech recognition and Kokoro TTS runtime) | 2.0.1, static-link ONNX Runtime AAR | Apache-2.0 | `app/libs/` |
| [ncnn](https://github.com/Tencent/ncnn) | 20231027 | BSD-3-Clause | `duix-sdk/`, `app/src/main/jniLibs/` |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | as shipped with Duix-Mobile | MIT | `duix-sdk/`, `app/src/main/jniLibs/` |
| [opencv-mobile](https://github.com/nihui/opencv-mobile) | 4.6.0 | Apache-2.0 | `duix-sdk/` |
| [libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) | as shipped with Duix-Mobile | IJG / BSD-3-Clause / zlib | `duix-sdk/` |
| [OkHttp](https://github.com/square/okhttp) | 4.10.0 | Apache-2.0 | Maven |
| AndroidX (core, appcompat, constraintlayout) | see `app/build.gradle` | Apache-2.0 | Maven |
| Komika Axis font, Apostrophic Laboratories | — | Freeware, redistributed unmodified ([license](app/src/main/assets/fonts/KomikaAxis-License.txt)) | `app/src/main/assets/fonts/` |
| Avatar previews | — | duix.com stock avatars, duix.com Community License | `app/src/main/assets/avatars/` |

## Downloaded by the app on first start

| Model | Source | License |
|---|---|---|
| Huihui Gemma 4 E2B it abliterated, Q4_K_M + vision projector Q8_0 | [mradermacher/Huihui-gemma-4-E2B-it-abliterated-GGUF](https://huggingface.co/mradermacher/Huihui-gemma-4-E2B-it-abliterated-GGUF), from [huihui-ai](https://huggingface.co/huihui-ai/Huihui-gemma-4-E2B-it-abliterated), based on [Google Gemma 4](https://huggingface.co/google/gemma-4-E2B-it) | Apache-2.0 ([Gemma 4 terms](https://ai.google.dev/gemma/docs/gemma_4_license)) |
| Kokoro-82M v1.0, multi-language (sherpa-onnx export) | [csukuangfj/kokoro-multi-lang-v1_0](https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0), from [hexgrad/Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) | Apache-2.0 |
| NVIDIA Nemotron Speech Streaming en 0.6B, 560 ms chunks, int8 (sherpa-onnx export) | [nvidia/nemotron-speech-streaming-en-0.6b](https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b), export from [sherpa-onnx asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models), mirrored unmodified on this repo's [`speech-model-v1`](https://github.com/SolvAI/PocketCompanion/releases/tag/speech-model-v1) release | Licensed by NVIDIA Corporation under the [NVIDIA Open Model License](https://www.nvidia.com/en-us/agreements/enterprise-software/nvidia-open-model-license/) |
| Avatar models (11 stock avatars) and the avatar base config | duix.com public downloads | duix.com Community License |

## duix.com Community License: what it asks for

- Keep a copy of the license with the duix.com materials (`duix-sdk/LICENSE.md`) and the attribution in `NOTICE`.
- Show "Powered by Duix.com" (the app shows it in Settings, the README and the website show it too).
- A product built on it with more than 1,000 monthly active users needs a separate license from duix.com.
