# Changelog

All notable changes to PocketCompanion. Versions before 1.0.0 were private development builds
(package `ai.duix.companion`) and were not published.

## [1.0.0] - 2026-11-19

First public release, renamed PocketCompanion (package `io.github.solvai.pocketcompanion`).

### Added
- First start asks before downloading about 5 GB of models, recommends Wi-Fi, and checks free space.
- The speech recognition model now downloads by itself, from this repo's `speech-model-v1` release.
- Six English voices: Heart, Bella, Nicole and Sky (American), Emma and Isabella (British).
- Credits in Settings, including "Powered by Duix.com", with a link to the project.
- New app icon.
- Signed release APK.
- Demo videos with sound, recorded with `scripts/record_take.sh` and assembled with `scripts/assemble_demo.py`.

### Changed
- Photos are sent to the model at 512 px instead of 768 px. Gemma 4 describes them just as well and
  answers about twice as fast (first text after 10.4 s instead of 21 s on the test phone).
- The Duix SDK is trimmed to arm64 and the llama.cpp libraries are stripped of debug symbols, so the
  source tree is about 5x smaller. The APK is unchanged in behavior.

## [0.10.0] - 2026-11-12
### Added
- Editable instructions (system prompt) in Settings. `{name}` becomes the companion's name; saving starts a
  fresh conversation.

## [0.9.0] - 2026-11-12
### Changed
- Language model: Huihui Gemma 4 E2B abliterated (Q4_K_M) with its vision projector for photos.
  First reply text in about 1 s on the test phone, down from 7.5-10 s; photos about 10 s, down from 17 s.
- Speech recognition: NVIDIA Nemotron Speech Streaming 0.6B through sherpa-onnx. It transcribes while
  you hold the button and shows a live caption; the text is ready about 0.2 s after you let go.

## [0.8.0] - 2026-11-12
### Changed
- Tried a private roleplay finetune of LFM3-Omni 2B (never published).

## [0.7.0] - 2026-11-10
### Changed
- Latency pass: full sliding-window cache in llama-server (`--swa-full`) so the conversation is not
  re-read every turn, append-only chat history, and a short first spoken piece. Voice turns went from
  15-19 s to about 7 s until first audio.

## [0.6.0] - 2026-11-10
### Added
- Big, word-by-word "TikTok style" subtitles synced to the voice.
- Photo messages: attach a picture and she reacts to it.
### Changed
- Uncensored LFM3-Omni 2B model; chat moved to llama-server's chat endpoint.

## [0.5.0] - 2026-11-09
### Added
- Settings sheet: pick one of 11 avatars and one of 8 voices. The language model stays loaded when
  switching.

## [0.4.0] - 2026-11-09
### Added
- Kokoro voices through sherpa-onnx, replacing Google TTS.
- New full-screen UI: edge-to-edge avatar, one input pill, hold-to-talk button, fading captions.

## [0.3.0] - 2026-11-09
### Fixed
- No lip movement and crackling speech: the model's own speech output ran at about 0.06x realtime on
  the phone CPU. Speech now comes from a separate TTS, one sentence at a time; all lip frames render.

## [0.2.0] - 2026-11-09
### Added
- First build: Duix avatar, on-device LFM3-Omni 2B through llama-server, voice and text chat.
