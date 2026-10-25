"""Builds every iDownloader icon size from Imad's ImadLabs brand kit.

Run from the repo root:  python branding/make_logo.py
Source art: github.com/ImadKhan1999/Graphics, apps/downloader/ (cloud with the Cyber Mint download
arrow and the ImadLabs quantum dot), copied into branding/imadlabs/downloader/. The kit's 1024 px
icon and its Android adaptive layers are scaled here for Windows, Android, the extension and the
docs; the notification / monochrome icon is a vector of the same cloud and arrow.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
KIT = ROOT / "branding/imadlabs/downloader"
BG = (0x07, 0x0B, 0x14)
MINT = (0x00, 0xF5, 0xA0)


def font(size, weight="bold"):
    names = {"bold": ["segoeuib.ttf"], "regular": ["segoeui.ttf"]}[weight]
    for name in names:
        f = Path("C:/Windows/Fonts") / name
        if f.exists():
            return ImageFont.truetype(str(f), size)
    return ImageFont.load_default()


def banner(logo):
    """Wide image for the README and store pages: icon, name, tagline and the ImadLabs logo."""
    w, h = 1600, 500
    img = Image.new("RGBA", (w, h), BG + (255,))
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([260 - 300, 250 - 300, 260 + 300, 250 + 300], fill=(0x00, 0x66, 0xFF, 70))
    img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(120)))
    mark = logo.resize((300, 300), Image.LANCZOS)
    img.paste(mark, (110, 100), mark)
    d = ImageDraw.Draw(img)
    d.text((470, 120), "iDownloader", font=font(124), fill=(0xF8, 0xFA, 0xFC, 255))
    d.text((476, 285), "Fast downloads, videos and playlists in any quality.", font=font(42, "regular"), fill=(0x94, 0xA3, 0xB8, 255))
    d.text((476, 345), "YouTube  \u2022  TikTok  \u2022  Instagram  \u2022  Facebook  \u2022  X  \u2022  1000+ sites", font=font(34, "regular"), fill=MINT + (255,))
    brand = ROOT / "branding/imadlabs/imadlabs-logo-horizontal-transparent.png"
    if brand.exists():
        b = Image.open(brand).convert("RGBA")
        b = b.crop(b.getbbox())
        b = b.resize((int(b.width * 70 / b.height), 70), Image.LANCZOS)
        x, y = w - b.width - 60, h - b.height - 40
        d.text((x - 52, y + 18), "by", font=font(28, "regular"), fill=(0x64, 0x74, 0x8B, 255))
        img.paste(b, (x, y), b)
    return img


def android_vector():
    """24dp notification / monochrome icon: the kit's cloud outline with the download arrow."""
    s = 24 / 1024
    def n(v):
        return f"{v * s:.2f}"
    # Cloud from the kit's master SVG (arcs scaled to 24 dp), arrow as a cut-out stroke.
    cloud = (f"M{n(340)},{n(640)} A{n(110)},{n(110)} 0 0 1 {n(330)},{n(420)} A{n(150)},{n(150)} 0 0 1 {n(610)},{n(360)} "
             f"A{n(130)},{n(130)} 0 0 1 {n(700)},{n(640)} Z")
    return f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="{n(56)}" android:strokeLineJoin="round" android:pathData="{cloud}" />
    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="{n(78)}" android:strokeLineCap="round" android:strokeLineJoin="round"
        android:pathData="M{n(512)},{n(470)} L{n(512)},{n(790)} M{n(400)},{n(680)} L{n(512)},{n(792)} L{n(624)},{n(680)}" />
</vector>
"""


def save(img, path, size):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path)


def main():
    logo = Image.open(KIT / "downloader-1024x1024.png").convert("RGBA")
    fg = Image.open(KIT / "adaptive-foreground.png").convert("RGBA")
    bgl = Image.open(KIT / "adaptive-background.png").convert("RGBA")

    save(logo, ROOT / "branding/logo-1024.png", 1024)
    save(logo, ROOT / "branding/logo-512.png", 512)
    banner(logo).convert("RGB").save(ROOT / "branding/banner.png")
    (ROOT / "branding/logo.svg").write_text((KIT / "downloader-master-icon.svg").read_text(encoding="utf-8"), encoding="utf-8")

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
        save(fg, android / f"mipmap-{d}/ic_launcher_foreground.png", int(s * 108 / 48))
        save(bgl, android / f"mipmap-{d}/ic_launcher_background.png", int(s * 108 / 48))
    save(logo, ROOT / "androidApp/src/main/ic_launcher-playstore.png", 512)
    save(logo, android / "drawable-nodpi/logo.png", 512)
    (android / "drawable/ic_stat_download.xml").write_text(android_vector(), encoding="utf-8")
    print("logo assets written")


if __name__ == "__main__":
    main()
