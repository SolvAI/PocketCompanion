"""Adds sound to a phone screen recording of PocketCompanion, for the demo videos.

`adb shell screenrecord` records no audio. Debug builds can save every spoken sentence instead
(`--ez recordAudio true`), as `<epoch ms>-<who>.pcm` files, 16 kHz mono 16-bit. This script lays
those files on a timeline and muxes them with the video. `scripts/record_take.sh` records a take.

The clocks are aligned on the first subtitle: subtitles start in the same callback as the
companion's audio, so the first video frame showing the subtitle highlight color is the moment
the first companion sentence started.

Editing (all times are seconds of the raw recording):
  --start/--end         trim
  --cut A-B             remove a stretch, e.g. waiting for an avatar to load (repeatable)
  --fast A-B:F          play a silent stretch F times faster with a "fast-forward" badge (repeatable)
  --photo PATH@T        show the photo that was sent, as a picture-in-picture, from T on

Usage:
  python3 scripts/assemble_demo.py --video raw.mp4 --audio-dir demo/ --out voice.mp4 \
      [--start 0.5] [--end 31] [--cut 18-28] [--fast 9.5-21:4] [--photo pic.jpg@1.6] \
      [--width 720] [--ffmpeg ffmpeg]
"""
import argparse
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

RATE = 16_000
HIGHLIGHT = np.array([0xE0, 0x24, 0x4A])  # TikTokSubtitleView's spoken-word color
BAND = (0.52, 0.80)  # subtitles sit at 66% of the height
PROBE_WIDTH = 180
FPS = 30
FONT = "/usr/share/fonts/truetype/ubuntu/UbuntuSans[wdth,wght].ttf"


def probe_size(ffmpeg, video):
    out = subprocess.run([ffmpeg, "-hide_banner", "-i", str(video)], capture_output=True, text=True).stderr
    for token in out.replace(",", " ").split():
        if "x" in token and token.split("x")[0].isdigit() and token.split("x")[1].isdigit():
            w, h = map(int, token.split("x"))
            if w > 100 and h > 100:
                return w, h
    sys.exit("could not read the video size")


def first_subtitle_time(ffmpeg, video, size):
    w, h = size
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


def build_track(audio_dir, anchor_video_s, duration_s, delay_s, anchor_clip=0):
    clips = sorted(Path(audio_dir).glob("*.pcm"), key=lambda p: int(p.name.split("-")[0]))
    companion = [c for c in clips if c.stem.endswith("companion")]
    if not companion:
        sys.exit("no companion audio in " + str(audio_dir))
    # The first visible subtitle belongs to companion clip number `anchor_clip` (a voice sample
    # played while the settings sheet covers the subtitles does not count).
    anchor_ms = int(companion[anchor_clip].name.split("-")[0])
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


def font(size, weight="Bold"):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_name(weight)
    except (OSError, ValueError):
        pass
    return f


def badge_png(text, width, path):
    """A dark pill with a fast-forward icon and white text, for sped-up stretches."""
    f = font(round(width * 0.036))
    pad = round(width * 0.025)
    box = ImageDraw.Draw(Image.new("RGBA", (1, 1))).textbbox((0, 0), text, font=f)
    th = box[3] - box[1]
    icon_w = round(th * 1.5)
    w, h = box[2] - box[0] + icon_w + pad * 2.6, th + pad * 1.4
    img = Image.new("RGBA", (int(w), int(h)), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=h / 2, fill=(20, 16, 28, 215))
    x0, cy, tri = pad, h / 2, icon_w / 2
    for k in range(2):  # two triangles, like a fast-forward button
        x = x0 + k * tri
        d.polygon([(x, cy - th / 2), (x + tri, cy), (x, cy + th / 2)], fill=(255, 92, 141, 255))
    d.text((pad * 1.6 + icon_w - box[0], pad * 0.7 - box[1]), text, font=f, fill=(255, 255, 255, 255))
    img.save(path)


def photo_png(src, width, path):
    """The sent photo with rounded corners and a white border, about 42% of the video width."""
    pw = round(width * 0.42)
    img = Image.open(src).convert("RGB")
    img = img.resize((pw, round(img.height * pw / img.width)), Image.LANCZOS)
    b = max(3, round(width * 0.008))
    out = Image.new("RGBA", (img.width + 2 * b, img.height + 2 * b), (0, 0, 0, 0))
    r = round(width * 0.03)
    ImageDraw.Draw(out).rounded_rectangle([0, 0, out.width - 1, out.height - 1], radius=r + b, fill="white")
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.width - 1, img.height - 1], radius=r, fill=255)
    out.paste(img, (b, b), mask)
    out.save(path)


def parse_range(text):
    a, b = text.split("-")
    return float(a), float(b)


def segments(start, end, cuts, fasts):
    """Kept stretches of the raw video as (from, to, speed)."""
    marks = sorted([(a, b, None) for a, b in cuts] + [(a, b, f) for a, b, f in fasts])
    out, t = [], start
    for a, b, f in marks:
        if b <= t or a >= end:
            continue
        if a > t:
            out.append((t, a, 1.0))
        if f is not None:
            out.append((max(a, t), min(b, end), f))
        t = max(t, min(b, end))
    if t < end:
        out.append((t, end, 1.0))
    return out


def output_time(raw_t, segs):
    t = 0.0
    for a, b, f in segs:
        if raw_t <= a:
            return t
        if raw_t < b:
            return t + (raw_t - a) / f
        t += (b - a) / f
    return t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--audio-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--start", type=float, default=0.0)
    ap.add_argument("--end", type=float, default=None)
    ap.add_argument("--cut", action="append", default=[], help="A-B, seconds of the raw video to remove")
    ap.add_argument("--fast", action="append", default=[], help="A-B:F, play this stretch F times faster")
    ap.add_argument("--fast-label", default="{f:g}× while she thinks")
    ap.add_argument("--photo", default=None, help="PATH@T, show this photo from raw time T")
    ap.add_argument("--width", type=int, default=720)
    ap.add_argument("--delay", type=float, default=0.0, help="extra seconds to delay companion audio")
    ap.add_argument("--anchor-clip", type=int, default=0,
                    help="which companion clip (0-based) the first visible subtitle belongs to")
    ap.add_argument("--ffmpeg", default="ffmpeg")
    args = ap.parse_args()

    size = probe_size(args.ffmpeg, args.video)
    anchor, duration = first_subtitle_time(args.ffmpeg, args.video, size)
    print(f"first subtitle at {anchor:.2f} s of {duration:.1f} s")
    track = build_track(args.audio_dir, anchor, duration, args.delay, args.anchor_clip)

    cuts = [parse_range(c) for c in args.cut]
    fasts = [(*parse_range(x.split(":")[0]), float(x.split(":")[1])) for x in args.fast]
    segs = segments(args.start, min(args.end or duration, duration), cuts, fasts)
    print("segments:", ", ".join(f"{a:.2f}-{b:.2f}" + (f" x{f:g}" if f != 1 else "") for a, b, f in segs))

    width = args.width
    height = round(size[1] * width / size[0] / 2) * 2
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = Path(tmp) / "track.wav"
        with wave.open(str(wav_path), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(RATE)
            wav.writeframes(track.tobytes())

        inputs = ["-i", args.video, "-i", str(wav_path)]
        parts = []
        for i, (a, b, f) in enumerate(segs):
            parts.append(f"[0:v]trim=start={a}:end={b},setpts=(PTS-STARTPTS)/{f},fps={FPS}[v{i}]")
            parts.append(f"[1:a]atrim=start={a}:end={a + (b - a) / f},asetpts=PTS-STARTPTS[a{i}]")
        joined = "".join(f"[v{i}][a{i}]" for i in range(len(segs)))
        parts.append(f"{joined}concat=n={len(segs)}:v=1:a=1[vc][ac]")
        parts.append(f"[vc]scale={width}:{height}[v]")
        label, n_inputs = "v", 2

        if args.photo:
            src, at = args.photo.rsplit("@", 1)
            png = Path(tmp) / "photo.png"
            photo_png(src, width, png)
            inputs += ["-loop", "1", "-i", str(png)]
            t = output_time(float(at), segs)
            parts.append(f"[{label}][{n_inputs}:v]overlay=x=W*0.05:y=H*0.075:enable='gte(t,{t:.2f})':shortest=1[vp]")
            label, n_inputs = "vp", n_inputs + 1

        for k, (a, b, f) in enumerate(fasts):
            png = Path(tmp) / f"badge{k}.png"
            badge_png(args.fast_label.format(f=f), width, png)
            inputs += ["-loop", "1", "-i", str(png)]
            t0, t1 = output_time(a, segs), output_time(b, segs)
            parts.append(f"[{label}][{n_inputs}:v]overlay=x=(W-w)/2:y=H*0.63:"
                         f"enable='between(t,{t0:.2f},{t1:.2f})':shortest=1[vf{k}]")
            label, n_inputs = f"vf{k}", n_inputs + 1

        parts.append("[ac]loudnorm=I=-16:TP=-1.5,aresample=48000[a]")
        cmd = [args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", *inputs,
               "-filter_complex", ";".join(parts), "-map", f"[{label}]", "-map", "[a]",
               "-c:v", "libx264", "-preset", "slow", "-crf", "26", "-pix_fmt", "yuv420p",
               "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", args.out]
        subprocess.run(cmd, check=True)
    print("wrote", args.out)


if __name__ == "__main__":
    main()
