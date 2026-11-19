"""Renders the app icon (same design as res/drawable/ic_launcher_*.xml) to PNGs for the website and stores.

Usage: python3 scripts/make_brand_assets.py docs/assets [phone-screenshot.png]
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

PINK = (255, 107, 154)
PURPLE = (107, 78, 255)
SMILE = (255, 92, 141)


def gradient(size, start=PINK, end=PURPLE):
    """Diagonal gradient, top-left to bottom-right, like the vector background."""
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x + y) / (w + h - 2)
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(start, end))
    return img


def draw_bubble(draw, s, ox=0.0, oy=0.0):
    """The foreground glyph from the 108-unit vector, scaled by s and offset by (ox, oy) pixels."""
    def p(x, y):
        return (ox + x * s, oy + y * s)

    draw.rounded_rectangle([p(30, 32), p(78, 70)], radius=14 * s, fill="white")
    draw.polygon([p(50, 69), p(36, 80), p(40, 64)], fill="white")
    r = 3.4 * s
    for cx in (45, 63):
        x, y = p(cx, 48.5)
        draw.ellipse([x - r, y - r, x + r, y + r], fill=PURPLE)
    # Smile: quadratic curve from (45.5,57) through control (54,64.5) to (62.5,57), stroke 4.
    pts = []
    for i in range(41):
        t = i / 40
        x = (1 - t) ** 2 * 45.5 + 2 * (1 - t) * t * 54 + t ** 2 * 62.5
        y = (1 - t) ** 2 * 57 + 2 * (1 - t) * t * 64.5 + t ** 2 * 57
        pts.append(p(x, y))
    draw.line(pts, fill=SMILE, width=max(1, round(4 * s)), joint="curve")
    for end in (pts[0], pts[-1]):
        rr = 2 * s
        draw.ellipse([end[0] - rr, end[1] - rr, end[0] + rr, end[1] + rr], fill=SMILE)


def icon(size, rounded=True):
    scale = 4  # supersample for smooth edges
    big = size * scale
    img = gradient((big, big))
    draw = ImageDraw.Draw(img)
    # Crop the 108-unit canvas to its central 76 units, roughly what a launcher mask shows.
    view = 76
    s = big / view
    off = -(108 - view) / 2 * s
    draw_bubble(draw, s, off, off)
    if rounded:
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=big * 0.23, fill=255)
        out = Image.new("RGBA", (big, big))
        out.paste(img, (0, 0), mask)
        img = out
    return img.resize((size, size), Image.LANCZOS)


FONT = "/usr/share/fonts/truetype/ubuntu/UbuntuSans[wdth,wght].ttf"


def font(size, weight="Bold"):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_name(weight)
    except (OSError, ValueError):
        pass
    return f


def og_image(screenshot=None):
    """1200x630 social preview: name, tagline and, if given, a phone screenshot on the right."""
    w, h = 1200, 630
    img = Image.new("RGB", (w, h), (14, 12, 20))
    glow = gradient((w, h), (60, 22, 52), (28, 20, 70))
    img = Image.blend(img, glow, 0.9)
    draw = ImageDraw.Draw(img)
    logo = icon(120)
    img.paste(logo, (72, 96), logo)
    draw.text((72, 250), "PocketCompanion", font=font(76), fill=(244, 241, 250))
    draw.text((72, 344), "Offline AI avatar companion for Android", font=font(36, "Medium"), fill=(214, 206, 232))
    chips = ("100% on-device", "Voice, text & photos", "Free & open source")
    size = 26
    while size > 16:  # shrink until the row ends before the phone
        f = font(size, "SemiBold")
        if 72 + sum(draw.textlength(c, font=f) + size * 1.4 + 14 for c in chips) < 820:
            break
        size -= 1
    y, x = 430, 72
    for chip in chips:
        tw = draw.textlength(chip, font=f)
        pad = size * 0.7
        draw.rounded_rectangle([x, y, x + tw + 2 * pad, y + size * 1.9], radius=size, outline=(255, 255, 255), width=2)
        draw.text((x + pad, y + size * 0.33), chip, font=f, fill=(244, 241, 250))
        x += tw + 2 * pad + 14
    if screenshot:
        shot = Image.open(screenshot).convert("RGB")
        sh = 560
        sw = round(shot.width * sh / shot.height)
        shot = shot.resize((sw, sh), Image.LANCZOS)
        frame = Image.new("RGB", (sw + 16, sh + 16), (5, 4, 9))
        mask = Image.new("L", frame.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, frame.width - 1, frame.height - 1], radius=44, fill=255)
        inner = Image.new("L", shot.size, 0)
        ImageDraw.Draw(inner).rounded_rectangle([0, 0, sw - 1, sh - 1], radius=36, fill=255)
        frame.paste(shot, (8, 8), inner)
        img.paste(frame, (w - frame.width - 70, (h - frame.height) // 2), mask)
    return img


def main(out_dir, screenshot=None):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    icon(512).save(out / "icon-512.png")
    icon(192).save(out / "icon-192.png")
    icon(180, rounded=False).convert("RGB").save(out / "apple-touch-icon.png")
    icon(64).save(out / "favicon.png")
    og_image(screenshot).save(out / "og-image.png", optimize=True)
    print("wrote", sorted(p.name for p in out.iterdir()))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "docs/assets", sys.argv[2] if len(sys.argv) > 2 else None)
