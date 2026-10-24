#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0
#
# Renders GooseStation's icons from scripts/branding/goosestation.png (1024x1024, transparent).
# Needs Pillow (pip install pillow). Run from anywhere:
#   python scripts/branding/generate-icons.py

import io
import struct
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
MASTER = Image.open(Path(__file__).resolve().parent / "goosestation.png").convert("RGBA")


def render(size: int) -> bytes:
    data = io.BytesIO()
    MASTER.resize((size, size), Image.LANCZOS).save(data, "PNG", optimize=True)
    return data.getvalue()


def write_png(path: str, size: int) -> None:
    # Re-encode through Pillow so the files are plain, optimised RGBA PNGs.
    image = Image.open(io.BytesIO(render(size))).convert("RGBA")
    out = ROOT / path
    image.save(out, optimize=True)
    print(f"{path} ({size}x{size})")


def write_ico(path: str, sizes: list[int]) -> None:
    # PNG-compressed ICO entries; supported by Windows Vista and later.
    images = [render(size) for size in sizes]
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
        data = render(size)
        body += icon_type + struct.pack(">I", len(data) + 8) + data
    (ROOT / path).write_bytes(b"icns" + struct.pack(">I", len(body) + 8) + body)
    print(f"{path} (icns)")


def main() -> None:
    write_png("data/resources/images/goosestation.png", 512)
    write_png("src/duckstation-qt/resources/icons/goosestation.png", 256)
    write_png("src/duckstation-qt/resources/icons/goosestation_128.png", 128)
    write_png("src/duckstation-qt/resources/icons/goosestation_64.png", 64)
    write_png("scripts/io.github.ilyalissoboi.GooseStation.png", 512)
    write_ico("src/duckstation-qt/duckstation-qt.ico", [16, 24, 32, 48, 64, 128, 256])
    write_icns("src/duckstation-qt/GooseStation.icns")
    write_icns("src/updater/Updater.icns")


if __name__ == "__main__":
    main()
