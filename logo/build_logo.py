"""Produce the logo source images from the research table block preview.

Two sets come out of this: the faithful inventory-icon preview (`research_table_block_*`) and the
same table with this mod's green status lamp and the inkwell/quill props (`research_table_logo_*`).

The master render is 2048x2048; smaller sizes are downsampled from it in premultiplied alpha so
the transparent background does not darken the silhouette.

    python logo/build_logo.py
"""

import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render_block_preview as rp  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
MASTER = 2048
MARGIN = 0.06

# name prefix, green status lamp, inkwell and quill
SETS = (
    ("research_table_block", False, False),
    ("research_table_logo", True, True),
)


def downsample(image: Image.Image, size: int) -> Image.Image:
    if size == image.width:
        return image
    pixels = np.asarray(image, dtype=np.float32) / 255.0
    alpha = pixels[..., 3:4]
    premultiplied = np.concatenate([pixels[..., :3] * alpha, alpha], axis=-1)
    resized = np.stack([
        np.asarray(
            Image.fromarray(premultiplied[..., channel], mode="F").resize(
                (size, size), Image.LANCZOS
            ),
            dtype=np.float32,
        )
        for channel in range(4)
    ], axis=-1)
    out_alpha = np.clip(resized[..., 3:4], 0.0, 1.0)
    rgb = np.where(out_alpha > 1e-6, resized[..., :3] / np.maximum(out_alpha, 1e-6), 0.0)
    out = np.concatenate([np.clip(rgb, 0.0, 1.0), out_alpha], axis=-1)
    return Image.fromarray((out * 255.0).round().astype(np.uint8), mode="RGBA")


def main() -> None:
    for prefix, led, inkwell in SETS:
        master = rp.render(225.0, 30.0, MASTER, 2, MARGIN, led=led, inkwell=inkwell)
        for size in (MASTER, 1024, 512, 128):
            path = os.path.join(HERE, f"{prefix}_{size}.png")
            downsample(master, size).save(path)
            print("wrote", path)

        alternative = rp.render(135.0, 30.0, 1024, 2, MARGIN, led=led, inkwell=inkwell)
        path = os.path.join(HERE, f"{prefix}_1024_alt.png")
        alternative.save(path)
        print("wrote", path)


if __name__ == "__main__":
    main()
