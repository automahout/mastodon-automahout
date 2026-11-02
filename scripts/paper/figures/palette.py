"""Colours shared by the image figures (Fig. 1b, Fig. 2a), so one kind of mark has one colour everywhere.

Bright colours that read on a grayscale image; tp cyan and fp magenta as in the ELEPHANT GUI. The ground truth is a
solid green line: white on a bright nucleus could not be seen.
"""

from pptx.dml.color import RGBColor

GROUND_TRUTH = RGBColor(0x2E, 0xE6, 0x5A)  # ground truth (green), on images
GROUND_TRUTH_TREE = RGBColor(0x1B, 0x9E, 0x4B)  # ground truth (darker green), on a white background
FOUND = RGBColor(0x00, 0xE5, 0xFF)  # reference found, result matched (cyan: tp)
MISSED = RGBColor(0xFF, 0x9A, 0x8A)  # missed (salmon pink: fn); orange merged with yellow, red was dim on dark images
PREDICTION = RGBColor(0xFF, 0xE6, 0x00)  # prediction (yellow)
MERGED = RGBColor(0xFF, 0xE6, 0x00)  # merged detection (yellow)
FALSE = RGBColor(0xFF, 0x2B, 0xD6)  # false hit, false detection (magenta: fp)

THIN, THICK = 0.75, 1.5  # pt: ordinary marks, and the errors a reader should find
