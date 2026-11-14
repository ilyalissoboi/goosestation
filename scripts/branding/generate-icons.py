#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0
#
# Renders the desktop builds' icons. Needs Pillow (pip install pillow). Run from anywhere:
#   python scripts/branding/generate-icons.py
#
# The app icon is the Android launcher icon: the design system's goose mark (docs/design-system/logos/goose-mark.svg)
# on the dark `surface` tile, placed as generate-android-icon.py places it on the adaptive icon's canvas and cropped
# to the middle 72 dp a launcher shows, under a rounded square. The placement, the tile's colour and the SVG reader
# come from that script, so the two can't drift apart.
#
# The hero illustration (the setup wizard, the about box, the Big Picture UI, the README) is still the full goose,
# scripts/branding/goosestation.png (1024x1024, transparent).

import importlib.util
import io
import struct
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]

_spec = importlib.util.spec_from_file_location("generate_android_icon", HERE / "generate-android-icon.py")
android = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(android)

# The adaptive icon's canvas, and the square at its middle that launchers show.
CANVAS = 108.0
VISIBLE = 72.0

# The tile's corner radius as a share of its side: goose-app-icon.svg's (rx 14 of 64).
CORNER = 14 / 64

# macOS icons leave a margin around the tile: Apple's grid puts an 824 px tile on the 1024 px canvas.
MAC_TILE = 824 / 1024

MASTER_SIZE = 1024
# Pillow doesn't antialias polygons, so the master is drawn this many times larger and scaled down.
SUPERSAMPLE = 4


def app_icon(tile: float = 1.0) -> Image.Image:
    """The app icon at MASTER_SIZE, its tile [tile] of the canvas across and centred."""
    size = MASTER_SIZE * SUPERSAMPLE
    side = size * tile
    origin = (size - side) / 2
    per_dp = side / VISIBLE
    inset = (CANVAS - VISIBLE) / 2

    def place(x: float, y: float) -> tuple[float, float]:
        # The mark's grid to the adaptive canvas, as the foreground layer's group does, then to the tile.
        cx, cy = x * android.SCALE + android.DX, y * android.SCALE + android.DY
        return (origin + (cx - inset) * per_dp, origin + (cy - inset) * per_dp)

    art = Image.new("RGBA", (size, size), "#" + android.BACKGROUND[3:])
    draw = ImageDraw.Draw(art)
    for fill, points in android.shapes(android.LOGOS / "goose-mark.svg"):
        draw.polygon([place(x, y) for x, y in points], fill=fill)

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (origin, origin, origin + side - 1, origin + side - 1), radius=side * CORNER, fill=255
    )
    icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    icon.paste(art, (0, 0), mask)
    return icon.resize((MASTER_SIZE, MASTER_SIZE), Image.LANCZOS)


HERO = Image.open(HERE / "goosestation.png").convert("RGBA")
ICON = app_icon()
MAC_ICON = app_icon(MAC_TILE)


def render(master: Image.Image, size: int) -> bytes:
    data = io.BytesIO()
    master.resize((size, size), Image.LANCZOS).save(data, "PNG", optimize=True)
    return data.getvalue()


def write_png(path: str, master: Image.Image, size: int) -> None:
    (ROOT / path).write_bytes(render(master, size))
    print(f"{path} ({size}x{size})")


def write_ico(path: str, sizes: list[int]) -> None:
    # PNG-compressed ICO entries; supported by Windows Vista and later.
    images = [render(ICON, size) for size in sizes]
    header = struct.pack("<HHH", 0, 1, len(images))
    offset = len(header) + 16 * len(images)
    entries = b""
    for size, data in zip(sizes, images):
        dim = 0 if size >= 256 else size
        entries += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    (ROOT / path).write_bytes(header + entries + b"".join(images))
    print(f"{path} ({', '.join(map(str, sizes))})")


def write_icns(path: str) -> None:
    # PNG-based icns types, readable by macOS 10.7 and later.
    types = [
        (b"icp4", 16),
        (b"icp5", 32),
        (b"ic11", 32),
        (b"icp6", 64),
        (b"ic12", 64),
        (b"ic07", 128),
        (b"ic08", 256),
        (b"ic13", 256),
        (b"ic09", 512),
        (b"ic14", 512),
        (b"ic10", 1024),
    ]
    body = b""
    for icon_type, size in types:
        data = render(MAC_ICON, size)
        body += icon_type + struct.pack(">I", len(data) + 8) + data
    (ROOT / path).write_bytes(b"icns" + struct.pack(">I", len(body) + 8) + body)
    print(f"{path} (icns)")


def main() -> None:
    # The hero illustration.
    write_png("data/resources/images/goosestation.png", HERO, 512)
    write_png("src/duckstation-qt/resources/icons/goosestation_hero.png", HERO, 256)
    # The app icon: the windows' icon, the Linux packages' icon, the executable's and the bundles'.
    write_png("src/duckstation-qt/resources/icons/goosestation.png", ICON, 256)
    write_png("src/duckstation-qt/resources/icons/goosestation_128.png", ICON, 128)
    write_png("src/duckstation-qt/resources/icons/goosestation_64.png", ICON, 64)
    write_png("scripts/io.github.ilyalissoboi.GooseStation.png", ICON, 512)
    write_ico("src/duckstation-qt/duckstation-qt.ico", [16, 24, 32, 48, 64, 128, 256])
    write_icns("src/duckstation-qt/GooseStation.icns")
    write_icns("src/updater/Updater.icns")


if __name__ == "__main__":
    main()
