"""Render a one-slide pptx to a PNG beside it with LibreOffice, at the slide's size in mm."""

import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path


def soffice():
    """LibreOffice's command line: $SOFFICE, then the PATH, then the macOS application."""
    for c in (os.environ.get("SOFFICE"), shutil.which("soffice"), "/Applications/LibreOffice.app/Contents/MacOS/soffice"):
        if c and Path(c).exists():
            return c
    return None


def render_png(pptx, width_mm, height_mm, dpi=400):
    exe = soffice()
    if exe is None:
        print("LibreOffice not found (set SOFFICE): PNG skipped")
        return None
    pptx = Path(pptx).resolve()
    size = {"PixelWidth": {"type": "long", "value": str(round(width_mm / 25.4 * dpi))},
            "PixelHeight": {"type": "long", "value": str(round(height_mm / 25.4 * dpi))}}
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run([exe, "--headless", "--convert-to", "png:impress_png_Export:" + json.dumps(size),
                        "--outdir", tmp, str(pptx)], check=True, capture_output=True)
        png = pptx.with_suffix(".png")
        shutil.move(str(Path(tmp) / png.name), png)
    return png
