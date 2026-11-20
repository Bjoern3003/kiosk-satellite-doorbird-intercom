#!/usr/bin/env python3
"""Generate an original icon resembling DoorBird's flush-mounted metal door stations."""
from pathlib import Path
import random
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SIZE = 1024
OUT = Path(__file__).resolve().parents[1] / 'assets' / 'icon.png'

image = Image.new('RGBA', (SIZE, SIZE))
pixels = image.load()
for y in range(SIZE):
    for x in range(SIZE):
        t = y / (SIZE - 1)
        glow = max(0.0, 1.0 - (((x - 540) / 850) ** 2 + ((y - 250) / 750) ** 2))
        pixels[x, y] = (
            int(13 + 7 * t + 8 * glow),
            int(24 + 16 * t + 22 * glow),
            int(39 + 23 * t + 28 * glow),
            255,
        )
mask = Image.new('L', (SIZE, SIZE), 0)
ImageDraw.Draw(mask).rounded_rectangle((8, 8, 1016, 1016), radius=220, fill=255)
image.putalpha(mask)
background = image.copy()

shadow = Image.new('RGBA', (SIZE, SIZE), 0)
ImageDraw.Draw(shadow).rectangle((300, 34, 738, 1010), fill=(0, 0, 0, 190))
shadow = shadow.filter(ImageFilter.GaussianBlur(25))
image.alpha_composite(shadow)

panel = Image.new('RGBA', (SIZE, SIZE), 0)
panel_pixels = panel.load()
random.seed(17)
for y in range(28, 996):
    for x in range(306, 718):
        horizontal = x - 306
        vertical = (y - 28) / 968
        center_light = 24 * (1 - abs(horizontal / 412 - 0.45) * 1.6)
        brushed = random.choice((-3, -2, -1, 0, 0, 0, 1, 2, 3))
        shade = int(183 + center_light - 42 * vertical + brushed)
        panel_pixels[x, y] = (min(238, shade + 10), min(240, shade + 12), min(240, shade + 11), 255)
image.alpha_composite(panel)
draw = ImageDraw.Draw(image)
draw.rectangle((306, 28, 718, 996), outline='#E8EAE8', width=4)
draw.line((309, 31, 309, 992), fill='#FAFAF6', width=3)
draw.line((715, 31, 715, 992), fill='#858B89', width=3)


def font(size):
    for name in ('segoeui.ttf', 'arial.ttf'):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def centered(text, y, font_size, color):
    face = font(font_size)
    box = draw.textbbox((0, 0), text, font=face)
    draw.text((512 - (box[2] - box[0]) / 2, y), text, font=face, fill=color)


centered('DoorBird', 74, 34, '#505B55')

def camera_ellipse(bounds, color):
    center_x, center_y, scale = 512, 243, 0.78
    scaled = tuple(round(value) for value in (
        center_x + (bounds[0] - center_x) * scale,
        center_y + (bounds[1] - center_y) * scale,
        center_x + (bounds[2] - center_x) * scale,
        center_y + (bounds[3] - center_y) * scale,
    ))
    draw.ellipse(scaled, fill=color)


camera_ellipse((395, 126, 629, 360), '#A2A8A6')
camera_ellipse((401, 132, 623, 354), '#090D10')
camera_ellipse((414, 145, 610, 341), '#1D2529')
camera_ellipse((427, 158, 597, 328), '#05090D')
camera_ellipse((454, 185, 570, 301), '#192C3B')
camera_ellipse((468, 199, 556, 287), '#07111B')
camera_ellipse((478, 209, 546, 277), '#101D29')
camera_ellipse((490, 218, 522, 245), '#6A8798')
camera_ellipse((497, 222, 513, 237), '#D1E0E4')
camera_ellipse((529, 258, 539, 269), '#31546A')

for row in range(5):
    for column in range(3):
        x = 361 + column * 112
        y = 377 + row * 52
        draw.rounded_rectangle((x, y, x + 82, y + 34), radius=3, fill='#252B2C')
        draw.rectangle((x + 3, y + 3, x + 79, y + 31), outline='#0B1012', width=2)
        for dot in range(4):
            draw.line((x + 10, y + 9 + dot * 5, x + 72, y + 9 + dot * 5), fill='#0D1416', width=2)
        if row == 0 or (row == 2 and column != 1):
            draw.ellipse((x + 9, y + 10, x + 20, y + 21), outline='#64716D', width=2)

draw.rectangle((358, 653, 666, 803), fill='#7F8582')
draw.rectangle((363, 658, 661, 798), fill='#050A0C')
draw.line((368, 663, 656, 663), fill='#172226', width=3)

draw.rectangle((350, 851, 674, 932), fill='#676F6C')
draw.rectangle((355, 856, 669, 927), fill='#E9ECE7')
draw.rectangle((360, 861, 664, 922), outline='#B4BAB5', width=2)
centered('INTERCOM', 876, 27, '#525B56')

foreground = image.crop((270, 0, 754, 1024)).resize((388, 819), Image.Resampling.LANCZOS)
image = background.copy()
image.alpha_composite(foreground, (318, 102))
image = image.resize((256, 256), Image.Resampling.LANCZOS)
image.save(OUT, 'PNG', optimize=True)
print(f'Wrote {OUT} ({OUT.stat().st_size} bytes)')
