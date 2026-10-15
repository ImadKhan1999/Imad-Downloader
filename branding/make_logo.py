"""Renders the Hyper Downloader logo into every size the apps, the extension and the docs need.

Run from the repo root:  python branding/make_logo.py
The mark is a lightning bolt that ends in a download arrow, with speed lines, on a
violet -> blue -> cyan gradient. Also writes the vector versions (branding/logo.svg and the
Android notification/monochrome icon) from the same points, so every copy matches.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
S = 1024  # master size; everything is drawn at 4x and scaled down for smooth edges
SS = 4

STOPS = [(0.0, (0x7C, 0x3A, 0xED)), (0.55, (0x25, 0x63, 0xEB)), (1.0, (0x06, 0xB6, 0xD4))]  # violet, blue, cyan
DX = 26  # shifts the bolt right so bolt + speed lines sit centred
BOLT = [(560, 140), (340, 510), (480, 510), (400, 690), (300, 690), (512, 890),
        (724, 690), (600, 690), (700, 440), (560, 440), (680, 140)]
BOLT = [(x + DX, y) for x, y in BOLT]
SPEED = [(290, 250, 420), (370, 190, 380), (450, 240, 340)]  # (y, x0, x1), 28 px thick


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(n=256):
    img = Image.new("RGB", (n, n))
    px = img.load()
    for y in range(n):
        for x in range(n):
            t = (x * 0.35 + y * 0.65) / (n - 1)
            for (a, ca), (b, cb) in zip(STOPS, STOPS[1:]):
                if t <= b:
                    px[x, y] = lerp(ca, cb, (t - a) / (b - a))
                    break
    return img


def glyph(draw, size, color=(255, 255, 255, 255), lines=True):
    u = size / 1024
    if lines:
        for y, x0, x1 in SPEED:
            draw.rounded_rectangle([x0 * u, (y - 14) * u, x1 * u, (y + 14) * u], radius=14 * u, fill=color[:3] + (175,))
    draw.polygon([(x * u, y * u) for x, y in BOLT], fill=color)


def rounded_mask(big, radius=0.22):
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * radius), fill=255)
    return mask


def master():
    big = S * SS
    mask = rounded_mask(big)
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    img.paste(gradient().resize((big, big), Image.BICUBIC), (0, 0), mask)
    glow = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ImageDraw.Draw(glow).polygon([(x * big / 1024, y * big / 1024) for x, y in BOLT], fill=(255, 255, 255, 140))
    glow = glow.filter(ImageFilter.GaussianBlur(80))
    img = Image.alpha_composite(img, Image.composite(glow, Image.new("RGBA", (big, big), (0, 0, 0, 0)), mask))
    glyph(ImageDraw.Draw(img, "RGBA"), big)
    return img.resize((S, S), Image.LANCZOS)


def foreground():
    """Android adaptive icon foreground: glyph only, inside the 66% safe zone."""
    big = S * SS
    inner = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    glyph(ImageDraw.Draw(inner, "RGBA"), big)
    scaled = inner.resize((int(big * 0.62), int(big * 0.62)), Image.LANCZOS)
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    off = (big - scaled.width) // 2
    img.paste(scaled, (off, off), scaled)
    return img.resize((S, S), Image.LANCZOS)


def font(size, bold=True):
    for name in (["segoeuib.ttf", "seguisb.ttf"] if bold else ["segoeui.ttf"]):
        p = Path("C:/Windows/Fonts") / name
        if p.exists():
            return ImageFont.truetype(str(p), size)
    return ImageFont.load_default()


def banner(logo):
    """Wide image for the README and store listings: logo + name + tagline."""
    w, h = 1600, 500
    bg = gradient().resize((w, h), Image.BICUBIC).convert("RGBA")
    img = Image.alpha_composite(bg, Image.new("RGBA", (w, h), (0x0B, 0x0C, 0x24, 200)))
    mark = logo.resize((300, 300), Image.LANCZOS)
    img.paste(mark, (110, 100), mark)
    d = ImageDraw.Draw(img)
    d.text((470, 135), "Hyper Downloader", font=font(120), fill=(255, 255, 255, 255))
    d.text((476, 290), "Fast downloads, videos in any quality.", font=font(48, bold=False), fill=(0xC7, 0xD2, 0xFE, 255))
    d.text((476, 355), "Windows  \u2022  Android  \u2022  Chrome, Edge, Firefox", font=font(40, bold=False), fill=(0x67, 0xE8, 0xF9, 255))
    return img


def svg():
    pts = " ".join(f"{x},{y}" for x, y in BOLT)
    lines = "\n".join(
        f'  <rect x="{x0}" y="{y - 14}" width="{x1 - x0}" height="28" rx="14" fill="#fff" fill-opacity="0.69"/>' for y, x0, x1 in SPEED)
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="0.5" y2="1">
      <stop offset="0" stop-color="#7C3AED"/><stop offset="0.55" stop-color="#2563EB"/><stop offset="1" stop-color="#06B6D4"/>
    </linearGradient>
  </defs>
  <rect width="1024" height="1024" rx="225" fill="url(#g)"/>
{lines}
  <polygon points="{pts}" fill="#fff"/>
</svg>
"""


def android_vector():
    """24dp notification / monochrome icon: the bolt alone, fitted to the 24x24 box."""
    xs = [x for x, _ in BOLT]
    ys = [y for _, y in BOLT]
    scale = 21 / (max(ys) - min(ys))
    ox = 12 - (min(xs) + max(xs)) / 2 * scale
    oy = 12 - (min(ys) + max(ys)) / 2 * scale
    path = "M" + " L".join(f"{x * scale + ox:.2f},{y * scale + oy:.2f}" for x, y in BOLT) + " Z"
    return f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="{path}" />
</vector>
"""


def save(img, path, size):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path)


def main():
    logo = master()
    save(logo, ROOT / "branding/logo-1024.png", 1024)
    save(logo, ROOT / "branding/logo-512.png", 512)
    banner(logo).convert("RGB").save(ROOT / "branding/banner.png")
    (ROOT / "branding/logo.svg").write_text(svg(), encoding="utf-8")

    res = ROOT / "desktop/src/main/resources/icons"
    save(logo, res / "app.png", 256)
    save(logo, res / "tray.png", 32)
    logo.save(res / "app.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])

    for s in (16, 32, 48, 128):
        save(logo, ROOT / f"extension/icons/icon{s}.png", s)

    android = ROOT / "androidApp/src/main/res"
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for d, s in densities.items():
        save(logo, android / f"mipmap-{d}/ic_launcher.png", s)
        save(logo, android / f"mipmap-{d}/ic_launcher_round.png", s)
    fg = foreground()
    for d, s in densities.items():
        save(fg, android / f"mipmap-{d}/ic_launcher_foreground.png", int(s * 108 / 48))
    save(logo, ROOT / "androidApp/src/main/ic_launcher-playstore.png", 512)
    save(logo, android / "drawable-nodpi/logo.png", 512)
    (android / "drawable/ic_stat_download.xml").write_text(android_vector(), encoding="utf-8")
    print("logo assets written")


if __name__ == "__main__":
    main()
