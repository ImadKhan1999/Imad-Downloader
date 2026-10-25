"""Renders the iDownloader icon (ImadLabs HexaCore style) into every size the apps, the extension
and the docs need.

Run from the repo root:  python branding/make_logo.py
The icon is the downloader icon from Imad's ImadLabs brand kit (github.com/ImadKhan1999/Graphics,
apps/hyper-downloader/...-master-icon.svg): an isometric cube (sky, cyan and cobalt facets) with
two download chevrons on top and the Cyber Mint "L" pathway, on the Deep Void tile. It is drawn
here from the same coordinates so every size, the adaptive foreground and the notification icon
match. The banner also uses the ImadLabs logo from branding/imadlabs/.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
S = 1024
SS = 4  # drawn at 4x and scaled down for smooth edges

BG = (0x07, 0x0B, 0x14)        # Deep Void
BORDER = (0x1E, 0x29, 0x3B)
TOP = (0x38, 0xBD, 0xF8)       # Sky Diamond facet
LEFT = (0x02, 0x84, 0xC7)      # Deep Cyan-Blue facet
RIGHT = (0x00, 0x66, 0xFF)     # Electric Cobalt facet
WHITE = (0xFF, 0xFF, 0xFF)
DOT = (0x00, 0xFF, 0xC2)       # Quantum Dot
MINT = (0x00, 0xF5, 0xA0)      # Cyber Mint "L"

# Brand-kit geometry (200x232 box), placed with translate(180,160) scale(3.2) on a 1024 tile.
FACETS = [
    ([(100, 20), (180, 66), (100, 112), (20, 66)], TOP),
    ([(20, 74), (100, 120), (100, 212), (20, 166)], LEFT),
    ([(100, 120), (180, 74), (180, 166), (100, 212)], RIGHT),
]
STROKES = [  # (points, colour, width)
    ([(80, 55), (100, 75), (120, 55)], WHITE, 6),
    ([(80, 75), (100, 95), (120, 75)], DOT, 6),
    ([(100, 120), (100, 182), (152, 152)], MINT, 14),
]


def draw_mark(img, ox, oy, scale):
    d = ImageDraw.Draw(img, "RGBA")
    def p(x, y):
        return (ox + x * scale, oy + y * scale)
    for pts, col in FACETS:
        d.polygon([p(*q) for q in pts], fill=col)
    for pts, col, w in STROKES:
        ww = w * scale
        d.line([p(*q) for q in pts], fill=col, width=int(ww), joint="curve")
        for q in (pts[0], pts[-1]):  # round caps
            x, y = p(*q)
            d.ellipse([x - ww / 2, y - ww / 2, x + ww / 2, y + ww / 2], fill=col)


def master():
    big = S * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, big - 1, big - 1], radius=224 * SS, fill=BG)
    d.rounded_rectangle([12 * SS, 12 * SS, (1012) * SS, (1012) * SS], radius=214 * SS, outline=BORDER, width=4 * SS)
    draw_mark(img, 180 * SS, 160 * SS, 3.2 * SS)
    return img.resize((S, S), Image.LANCZOS)


def foreground():
    """Android adaptive icon foreground: the cube alone inside the 66% safe zone."""
    big = S * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    scale = 2.2 * SS  # the 200x232 mark becomes ~440x510 of 1024
    draw_mark(img, (big - 200 * scale) / 2, (big - 212 * scale) / 2 - 10 * scale, scale)
    return img.resize((S, S), Image.LANCZOS)


def font(size, weight="bold"):
    names = {"bold": ["segoeuib.ttf"], "regular": ["segoeui.ttf"], "light": ["segoeuil.ttf", "segoeui.ttf"]}[weight]
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
    gd = ImageDraw.Draw(glow)
    gd.ellipse([260 - 300, 250 - 300, 260 + 300, 250 + 300], fill=(0x00, 0x66, 0xFF, 70))
    img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(120)))
    mark = logo.resize((300, 300), Image.LANCZOS)
    img.paste(mark, (110, 100), mark)
    d = ImageDraw.Draw(img)
    d.text((470, 120), "iDownloader", font=font(124), fill=(0xF8, 0xFA, 0xFC, 255))
    d.text((476, 285), "Fast downloads, videos and playlists in any quality.", font=font(42, "regular"), fill=(0x94, 0xA3, 0xB8, 255))
    d.text((476, 345), "Windows  \u2022  Android  \u2022  Chrome, Edge, Firefox", font=font(36, "regular"), fill=MINT + (255,))
    brand = ROOT / "branding/imadlabs/imadlabs-logo-horizontal-transparent.png"
    if brand.exists():
        b = Image.open(brand).convert("RGBA")
        b = b.crop(b.getbbox())
        b = b.resize((int(b.width * 70 / b.height), 70), Image.LANCZOS)
        x, y = w - b.width - 60, h - b.height - 40
        d.text((x - 52, y + 18), "by", font=font(28, "regular"), fill=(0x64, 0x74, 0x8B, 255))
        img.paste(b, (x, y), b)
    return img


def svg():
    facets = "\n".join(
        f'    <polygon points="{" ".join(f"{x},{y}" for x, y in pts)}" fill="#{c[0]:02X}{c[1]:02X}{c[2]:02X}"/>' for pts, c in FACETS)
    strokes = "\n".join(
        f'    <path d="M {" L ".join(f"{x} {y}" for x, y in pts)}" stroke="#{c[0]:02X}{c[1]:02X}{c[2]:02X}" stroke-width="{w}" '
        f'stroke-linecap="round" stroke-linejoin="round" fill="none"/>' for pts, c, w in STROKES)
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
  <rect width="1024" height="1024" rx="224" fill="#070B14"/>
  <rect x="12" y="12" width="1000" height="1000" rx="214" fill="none" stroke="#1E293B" stroke-width="4"/>
  <g transform="translate(180, 160) scale(3.2)">
{facets}
{strokes}
  </g>
</svg>
"""


def android_vector():
    """24dp notification / monochrome icon: cube outline with the download chevron, in white."""
    s = 24 / 232
    ox = (24 - 200 * s) / 2
    def pt(x, y):
        return f"{ox + x * s:.2f},{y * s + 0.5:.2f}"
    cube = "M" + " L".join(pt(x, y) for x, y in [(100, 20), (180, 66), (180, 166), (100, 212), (20, 166), (20, 66)]) + " Z"
    hole = "M" + " L".join(pt(x, y) for x, y in [(100, 40), (34, 78), (34, 158), (100, 196), (166, 158), (166, 78)]) + " Z"
    chevron = "M" + " L".join(pt(x, y) for x, y in [(68, 92), (100, 124), (132, 92), (132, 112), (100, 144), (68, 112)]) + " Z"
    return f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{cube} {hole}" />
    <path android:fillColor="#FFFFFFFF" android:pathData="{chevron}" />
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
