#!/usr/bin/env python3
# Builds the CJK fonts the Qt frontend downloads for the Japanese, Korean and Chinese (Simplified) OSD and Big Picture
# Mode: NotoSans{JP,KR,SC}-Regular.zip, published in the runtime-resources release (see docs/releasing.md).
#
# Google Fonts only ships these as variable fonts, whose default instance is Thin, and that is what FreeType renders
# when given the file as it is. This takes the Regular (wght 400) instance as a static font instead.
#
# Requires fontTools: pip install fonttools
# Usage: make-cjk-fonts.py <output directory>

import hashlib
import io
import os
import sys
import urllib.parse
import urllib.request
import zipfile

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

# google/fonts at a fixed commit, so the fonts can be rebuilt as they were published.
GOOGLE_FONTS_COMMIT = "e7db6ca603fd4862010383ff17ffd6cc4ffac1c0"
GOOGLE_FONTS_URL = "https://raw.githubusercontent.com/google/fonts/" + GOOGLE_FONTS_COMMIT + "/ofl/{}/{}"

FONTS = [
  # (google/fonts directory, variable font, output name)
  ("notosansjp", "NotoSansJP[wght].ttf", "NotoSansJP-Regular"),
  ("notosanskr", "NotoSansKR[wght].ttf", "NotoSansKR-Regular"),
  ("notosanssc", "NotoSansSC[wght].ttf", "NotoSansSC-Regular"),
]


def download(directory, filename):
  url = GOOGLE_FONTS_URL.format(directory, urllib.parse.quote(filename))
  print(f"Downloading {url}")
  with urllib.request.urlopen(url) as response:
    return response.read()


def make_static_regular(variable_font_data):
  font = TTFont(io.BytesIO(variable_font_data))
  static_font = instancer.instantiateVariableFont(font, {"wght": 400}, updateFontNames=True)
  out = io.BytesIO()
  static_font.save(out)
  return out.getvalue()


def main():
  if len(sys.argv) != 2:
    print(f"Usage: {sys.argv[0]} <output directory>")
    sys.exit(1)

  out_dir = sys.argv[1]
  os.makedirs(out_dir, exist_ok=True)

  for directory, variable_name, name in FONTS:
    static_data = make_static_regular(download(directory, variable_name))
    licence = download(directory, "OFL.txt")

    zip_path = os.path.join(out_dir, name + ".zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
      zf.writestr(name + ".ttf", static_data)
      zf.writestr("OFL.txt", licence)

    with open(zip_path, "rb") as f:
      digest = hashlib.sha256(f.read()).hexdigest()
    print(f"Wrote {zip_path}: {name}.ttf {len(static_data)} bytes, zip sha256 {digest}")


if __name__ == "__main__":
  main()
