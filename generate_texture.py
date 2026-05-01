#!/usr/bin/env python3
"""
Generates a simple 16x16 Remote Speaker block texture.
Requires: pip install Pillow
Run from the project root: python generate_texture.py
"""
import os
from PIL import Image, ImageDraw # type: ignore

SIZE = 16
img = Image.new("RGBA", (SIZE, SIZE), (80, 55, 30, 255))   # dark wood base
draw = ImageDraw.Draw(img)

# Speaker grille — horizontal slots
for y in range(2, SIZE - 2, 2):
    for x in range(3, SIZE - 3):
        draw.point((x, y), fill=(20, 12, 5, 255))

# Highlight edge grain
for x in range(SIZE):
    draw.point((x, 0), fill=(100, 72, 40, 255))
    draw.point((x, SIZE - 1), fill=(60, 40, 18, 255))

# Iron-ingot centre marker
draw.rectangle([(6, 6), (9, 9)], fill=(185, 185, 195, 255))
draw.rectangle([(7, 7), (8, 8)], fill=(220, 220, 230, 255))

out = os.path.join("src", "main", "resources", "assets", "remote_speaker",
                   "textures", "block", "remote_speaker.png")
os.makedirs(os.path.dirname(out), exist_ok=True)
img.save(out)
print(f"Texture written to {out}")
