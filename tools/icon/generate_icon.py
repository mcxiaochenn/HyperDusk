"""使用 Pillow 生成 HyperDusk 图标；直接运行即可重建并自检所有资源。"""

from pathlib import Path
from hashlib import sha256
from math import ceil, floor
from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "android/app/src/main/res"
DOCS = ROOT / "docs/icon"
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
STOPS = [(0, "#0B1026"), (0.45, "#1E1B4B"), (0.72, "#4A2E5C"), (1, "#C0714F")]
# 所有几何参数以 108dp 画布为基准，PNG 与 SVG 共用。
MOON = (52, 50, 23)
CUTOUT = (61, 41, 20)
MOON_COLORS = ("#FFF6EA", "#FFD9B0")
STAR = (77, 31, 12, 3.2)
STAR_COLOR = "#FFF1DC"
GLOW = (54, 104, 46)
GLOW_COLOR = "#F2B27A"
GLOW_ALPHA = 0.42


def rgb(value):
    return tuple(bytes.fromhex(value.lstrip("#")))


def gradient(size, stops):
    image = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        t = y / (size - 1)
        for (a, ca), (b, cb) in zip(stops, stops[1:]):
            if a <= t <= b:
                f = (t - a) / (b - a)
                color = tuple(round(x + (z - x) * f) for x, z in zip(rgb(ca), rgb(cb)))
                draw.line((0, y, size, y), fill=(*color, 255))
                break
    return image


def circle_mask(size, circle):
    x, y, radius = circle
    scale = size / 108
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse(
        tuple(v * scale for v in (x-radius, y-radius, x+radius, y+radius)), fill=255)
    return mask


def star_boxes():
    x, y, length, width = STAR
    return [(x-length/2, y-width/2, x+length/2, y+width/2),
            (x-width/2, y-length/2, x+width/2, y+length/2)]


def horizon_glow(size):
    x, y, radius = GLOW
    scale = size / 108
    alpha = Image.new("L", (size, size), 0)
    pixels = alpha.load()
    # 只遍历辉光与画布相交的区域；距离在线性半径上衰减。
    for py in range(max(0, floor((y-radius)*scale)), min(size, ceil((y+radius)*scale))):
        for px in range(max(0, floor((x-radius)*scale)), min(size, ceil((x+radius)*scale))):
            distance = ((px/scale-x)**2 + (py/scale-y)**2)**0.5
            pixels[px, py] = round(255 * GLOW_ALPHA * max(0, 1-distance/radius))
    image = Image.new("RGBA", (size, size), GLOW_COLOR)
    image.putalpha(alpha)
    return image


def render(size, layer):
    """以 4× 超采样渲染 108dp 画布，再用 LANCZOS 降采样。"""
    if layer not in ("background", "foreground", "monochrome"):
        raise ValueError(layer)
    high = size * 4
    if layer == "background":
        image = Image.alpha_composite(gradient(high, STOPS), horizon_glow(high))
    else:
        moon = ImageChops.subtract(circle_mask(high, MOON), circle_mask(high, CUTOUT))
        star = Image.new("L", (high, high), 0)
        draw = ImageDraw.Draw(star)
        scale = high / 108
        for box in star_boxes():
            draw.rounded_rectangle(tuple(v * scale for v in box),
                                   radius=STAR[3] * scale / 2, fill=255)
        if layer == "monochrome":
            image = Image.new("RGBA", (high, high), "white")
            image.putalpha(ImageChops.lighter(moon, star))
        else:
            top, bottom = (MOON[1]-MOON[2])/108, (MOON[1]+MOON[2])/108
            image = gradient(high, [(0, MOON_COLORS[0]), (top, MOON_COLORS[0]),
                                    (bottom, MOON_COLORS[1]), (1, MOON_COLORS[1])])
            image.putalpha(moon)
            sparkle = Image.new("RGBA", (high, high), STAR_COLOR)
            sparkle.putalpha(star)
            image = Image.alpha_composite(image, sparkle)
    image = image.resize((size, size), Image.Resampling.LANCZOS)
    if layer == "monochrome":
        white = Image.new("RGBA", image.size, "white")
        white.putalpha(image.getchannel("A"))
        image = white
    return image


def svg():
    stops = "".join(f'<stop offset="{p*100:g}%" stop-color="{c}"/>' for p, c in STOPS)
    mx, my, mr = MOON
    cx, cy, cr = CUTOUT
    gx, gy, gr = GLOW
    capsules = "".join(
        f'<rect x="{x:g}" y="{y:g}" width="{right-x:g}" height="{bottom-y:g}" rx="{STAR[3]/2:g}"/>'
        for x, y, right, bottom in star_boxes())
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="1024" height="1024">
<defs>
<linearGradient id="dusk" x1="0" y1="0" x2="0" y2="1">{stops}</linearGradient>
<radialGradient id="horizon" gradientUnits="userSpaceOnUse" cx="{gx}" cy="{gy}" r="{gr}">
<stop stop-color="{GLOW_COLOR}" stop-opacity="{GLOW_ALPHA}"/><stop offset="100%" stop-color="{GLOW_COLOR}" stop-opacity="0"/>
</radialGradient>
<linearGradient id="moon" gradientUnits="userSpaceOnUse" x1="0" y1="{my-mr}" x2="0" y2="{my+mr}">
<stop stop-color="{MOON_COLORS[0]}"/><stop offset="100%" stop-color="{MOON_COLORS[1]}"/>
</linearGradient>
<mask id="crescent" maskUnits="userSpaceOnUse" x="0" y="0" width="108" height="108" style="mask-type:luminance">
<circle cx="{mx}" cy="{my}" r="{mr}" fill="white"/><circle cx="{cx}" cy="{cy}" r="{cr}" fill="black"/>
</mask>
</defs>
<rect width="108" height="108" fill="url(#dusk)"/>
<circle cx="{gx}" cy="{gy}" r="{gr}" fill="url(#horizon)"/>
<rect width="108" height="108" fill="url(#moon)" mask="url(#crescent)"/>
<g fill="{STAR_COLOR}">{capsules}</g>
</svg>
'''


def main():
    outputs = []

    def save(image, path, layer):
        path.parent.mkdir(parents=True, exist_ok=True)
        image.save(path)
        outputs.append((path, image.size[0], layer))

    for density, size in DENSITIES.items():
        for layer in ("background", "foreground", "monochrome"):
            save(render(size, layer), RES / f"mipmap-{density}/ic_launcher_{layer}.png", layer)
    xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
'''
    adaptive = RES / "mipmap-anydpi-v26"
    adaptive.mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (adaptive / name).write_text(xml, encoding="utf-8")
    save(Image.alpha_composite(render(1024, "background"), render(1024, "foreground")),
         DOCS / "hyperdusk-icon-1024.png", "brand")
    preview = Image.alpha_composite(render(512, "background"), render(512, "foreground"))
    circle = Image.new("L", (2048, 2048), 0)
    ImageDraw.Draw(circle).ellipse((0, 0, 2047, 2047), fill=255)
    circle = circle.resize((512, 512), Image.Resampling.LANCZOS)
    # LANCZOS 会在圆边内外产生轻微振铃（圆外可能残留 1-3 的 alpha），
    # 这里把远低于/远高于阈值的值夹回 0/255，保证圆形裁剪是干净的硬边。
    circle = circle.point(lambda v: 0 if v < 16 else (255 if v > 239 else v))
    preview.putalpha(circle)
    save(preview, DOCS / "hyperdusk-icon-round-preview.png", "round")
    (DOCS / "hyperdusk-icon.svg").write_text(svg(), encoding="utf-8")
    for density in DENSITIES:
        (RES / f"mipmap-{density}/ic_launcher.png").unlink(missing_ok=True)
    (RES / "drawable/ic_launcher_foreground.xml").unlink(missing_ok=True)

    for path, size, layer in outputs:
        with Image.open(path) as image:
            image.load()
            assert image.size == (size, size) and image.mode == "RGBA", path
            alpha = image.getchannel("A")
            if layer in ("background", "brand"):
                assert alpha.getextrema() == (255, 255), path
            elif layer in ("foreground", "monochrome"):
                box = alpha.getbbox()
                assert box and min(box[:2]) >= floor(size / 6), (path, box)
                assert max(box[2:]) <= ceil(size * 5 / 6), (path, box)
                if layer == "monochrome":
                    assert all(image.getchannel(c).getextrema() == (255, 255) for c in "RGB"), path
            else:
                # 逐像素验证圆内实心、圆外透明；允许边缘两像素抗锯齿。
                for y in range(size):
                    for x in range(size):
                        distance = ((x + 0.5 - size/2)**2 + (y + 0.5 - size/2)**2)**0.5
                        if distance < size/2 - 2:
                            assert alpha.getpixel((x, y)) == 255
                        elif distance > size/2 + 2:
                            assert alpha.getpixel((x, y)) == 0
            print(f"{path.relative_to(ROOT).as_posix()}: {size}×{size} {image.mode} OK")
    print("全部 PNG 尺寸、透明度、安全区和圆形裁剪自检通过。")
    # 输出摘要便于两次运行比较幂等性。
    digest = sha256()
    for path in sorted([p for p, _, _ in outputs] + list(adaptive.glob("ic_launcher*.xml"))
                       + [DOCS / "hyperdusk-icon.svg"]):
        digest.update(path.read_bytes())
    print(f"资源 SHA256: {digest.hexdigest()}")


if __name__ == "__main__":
    main()
