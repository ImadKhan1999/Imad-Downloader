"""Renders the Imad Downloader logo into every size the apps and the extension need.

Run from the repo root:  python branding/make_logo.py
The same shapes are drawn as vectors in branding/logo.svg and the Android adaptive icon XML.
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
S = 1024  # master size; everything is drawn at 4x and scaled down for smooth edges
SS = 4

TOP = (0x1E, 0x88, 0xE5)     # #1E88E5
BOTTOM = (0x0D, 0x47, 0xA1)  # #0D47A1
ACCENT = (0x00, 0xE6, 0x76)  # #00E676 progress green


def gradient(size, top, bottom):
    img = Image.new("RGB", (size, size), top)
    px = img.load()
    for y in range(size):
        t = y / (size - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(size):
            px[x, y] = c
    return img


def glyph(draw, size, color=(255, 255, 255, 255), ring=True):
    """Down arrow into a tray, with a progress ring around it."""
    u = size / 1024
    if ring:
        box = [212 * u, 180 * u, 812 * u, 780 * u]
        draw.arc(box, start=0, end=360, fill=(110, 165, 230, 255), width=int(54 * u))
        draw.arc(box, start=-90, end=170, fill=ACCENT + (255,), width=int(54 * u))
    # shaft
    draw.rounded_rectangle([462 * u, 300 * u, 562 * u, 560 * u], radius=24 * u, fill=color)
    # head
    draw.polygon([(352 * u, 520 * u), (672 * u, 520 * u), (512 * u, 680 * u)], fill=color)
    # tray
    draw.rounded_rectangle([300 * u, 820 * u, 724 * u, 876 * u], radius=28 * u, fill=color)


def master():
    big = S * SS
    bg = gradient(256, TOP, BOTTOM).resize((big, big), Image.BICUBIC).convert("RGBA")
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.22), fill=255)
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    img.paste(bg, (0, 0), mask)
    glyph(ImageDraw.Draw(img, "RGBA"), big)
    return img.resize((S, S), Image.LANCZOS)


def foreground():
    """Android adaptive icon foreground: glyph only, inside the 66% safe zone."""
    big = S * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    inner = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    glyph(ImageDraw.Draw(inner), big)
    scaled = inner.resize((int(big * 0.62), int(big * 0.62)), Image.LANCZOS)
    off = (big - scaled.width) // 2
    img.paste(scaled, (off, off), scaled)
    return img.resize((S, S), Image.LANCZOS)


def save(img, path, size):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path)


def main():
    logo = master()
    save(logo, ROOT / "branding/logo-1024.png", 1024)
    save(logo, ROOT / "branding/logo-512.png", 512)

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
    print("logo assets written")


if __name__ == "__main__":
    main()
