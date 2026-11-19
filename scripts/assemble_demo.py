"""Adds sound to a phone screen recording of PocketCompanion, for the demo videos.

`adb shell screenrecord` records no audio. Debug builds can save every spoken sentence instead
(`--ez recordAudio true`), as `<epoch ms>-<who>.pcm` files, 16 kHz mono 16-bit. This script lays
those files on a timeline and muxes them with the video.

The clocks are aligned on the first subtitle: subtitles start in the same callback as the
companion's audio, so the first video frame showing the subtitle highlight color is the moment
the first companion sentence started.

Usage:
  python3 scripts/assemble_demo.py --video raw.mp4 --audio-dir demo/ --out voice.mp4 \
      [--start 0.5] [--end 31] [--width 720] [--ffmpeg ffmpeg]
"""
import argparse
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

RATE = 16_000
HIGHLIGHT = np.array([0xE0, 0x24, 0x4A])  # TikTokSubtitleView's spoken-word color
BAND = (0.52, 0.80)  # subtitles sit at 66% of the height
PROBE_WIDTH = 180
FPS = 30


def probe_size(ffmpeg, video):
    out = subprocess.run([ffmpeg, "-hide_banner", "-i", str(video)], capture_output=True, text=True).stderr
    for token in out.replace(",", " ").split():
        if "x" in token and token.split("x")[0].isdigit() and token.split("x")[1].isdigit():
            w, h = map(int, token.split("x"))
            if w > 100 and h > 100:
                return w, h
    sys.exit("could not read the video size")


def first_subtitle_time(ffmpeg, video):
    w, h = probe_size(ffmpeg, video)
    pw = PROBE_WIDTH
    ph_full = round(h * pw / w)
    top, bottom = round(ph_full * BAND[0]), round(ph_full * BAND[1])
    band_h = bottom - top
    cmd = [ffmpeg, "-hide_banner", "-loglevel", "error", "-i", str(video),
           "-vf", f"fps={FPS},scale={pw}:{ph_full},crop={pw}:{band_h}:0:{top}",
           "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    frames = np.frombuffer(raw, np.uint8).reshape(-1, band_h, pw, 3).astype(np.int16)
    counts = (np.abs(frames - HIGHLIGHT).max(axis=3) < 40).sum(axis=(1, 2))
    hits = np.nonzero(counts >= 12)[0]
    if len(hits) == 0:
        sys.exit("no subtitle found in the video")
    return hits[0] / FPS, len(frames) / FPS


def build_track(audio_dir, anchor_video_s, duration_s, delay_s):
    clips = sorted(Path(audio_dir).glob("*.pcm"), key=lambda p: int(p.name.split("-")[0]))
    companion = [c for c in clips if c.stem.endswith("companion")]
    if not companion:
        sys.exit("no companion audio in " + str(audio_dir))
    anchor_ms = int(companion[0].name.split("-")[0])
    track = np.zeros(int((duration_s + 1) * RATE), np.int32)
    for clip in clips:
        at_ms = int(clip.name.split("-")[0])
        start = (at_ms - anchor_ms) / 1000 + anchor_video_s
        if clip.stem.endswith("companion"):
            start += delay_s
        pcm = np.frombuffer(clip.read_bytes(), "<i2").astype(np.int32)
        i = int(start * RATE)
        if i < 0:
            pcm, i = pcm[-i:], 0
        end = min(len(track), i + len(pcm))
        track[i:end] += pcm[: end - i]
        print(f"  {clip.name}: at {start:6.2f} s, {len(pcm) / RATE:.2f} s long")
    return np.clip(track, -32768, 32767).astype("<i2")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--audio-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--start", type=float, default=0.0)
    ap.add_argument("--end", type=float, default=None)
    ap.add_argument("--width", type=int, default=720)
    ap.add_argument("--delay", type=float, default=0.0, help="extra seconds to delay companion audio")
    ap.add_argument("--ffmpeg", default="ffmpeg")
    args = ap.parse_args()

    anchor, duration = first_subtitle_time(args.ffmpeg, args.video)
    print(f"first subtitle at {anchor:.2f} s of {duration:.1f} s")
    track = build_track(args.audio_dir, anchor, duration, args.delay)
    wav_path = Path(args.out).with_suffix(".wav")
    with wave.open(str(wav_path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.writeframes(track.tobytes())

    trim = ["-ss", str(args.start)] + (["-to", str(args.end)] if args.end else [])
    cmd = [args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
           "-i", args.video, "-i", str(wav_path), *trim,
           "-map", "0:v", "-map", "1:a",
           "-vf", f"fps={FPS},scale={args.width}:-2",
           "-c:v", "libx264", "-preset", "slow", "-crf", "26", "-pix_fmt", "yuv420p",
           "-c:a", "aac", "-b:a", "96k", "-ar", "48000", "-af", "loudnorm=I=-16:TP=-1.5",
           "-movflags", "+faststart", "-shortest", args.out]
    subprocess.run(cmd, check=True)
    wav_path.unlink()
    print("wrote", args.out)


if __name__ == "__main__":
    main()
