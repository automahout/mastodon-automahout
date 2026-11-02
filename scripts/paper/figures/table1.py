"""A table of the manuscript as an editable PowerPoint table (full page width, 174 mm), with a PNG of it beside.

The rows are read from the markdown table under "## <name>" in the manuscript (Table 1 by default), so the
manuscript stays the one source of the table's text. Every cell has a black border. The first column and the header
are bold; --merge-first merges equal neighbouring cells of the first column, --bold-best bolds each group's best
score; with --two-tone the
second and third columns are a person's (grey) and Automahout's (blue), as in Fig. 1a. A paragraph after the table,
before the next rule, is set as a note under it.

    uv run python figures/table1.py <manuscript_draft.md> <out.pptx> [--table "Table 2"] [--cols 30,20,...] [--two-tone] [--no-png]
"""

import argparse
import re

from lxml import etree
from pptx.oxml.ns import qn

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.util import Mm, Pt

from render import render_png

FONT = "Arial"
INK = RGBColor(0x26, 0x26, 0x26)
PERSON = RGBColor(0x6E, 0x6E, 0x6E)  # as in Fig. 1a
AGENT = RGBColor(0x00, 0x72, 0xB2)
RULE = RGBColor(0xBF, 0xBF, 0xBF)
W = 174
COLS = (34, 52, 86)  # mm


def grid(cell, colour="262626", width_pt=0.5):
    """Draw all four borders of a table cell, in the text's colour."""
    tc_pr = cell._tc.get_or_add_tcPr()
    for tag in ("a:lnL", "a:lnR", "a:lnT", "a:lnB"):
        for old in tc_pr.findall(qn(tag)):
            tc_pr.remove(old)
        ln = etree.SubElement(tc_pr, qn(tag), w=str(int(width_pt * 12700)), cap="flat", cmpd="sng", algn="ctr")
        fill = etree.SubElement(ln, qn("a:solidFill"))
        etree.SubElement(fill, qn("a:srgbClr"), val=colour)
        etree.SubElement(ln, qn("a:prstDash"), val="solid")


def number(v):
    try:
        return float(v)
    except ValueError:
        return None


def best_in_groups(rows):
    """(row, column) of the best value of each numeric column within each group of rows that share the first
    column's value (the highest: every score here is better higher). Ties are all best."""
    best = set()
    groups = {}
    for i, r in enumerate(rows[1:], 1):
        groups.setdefault(r[0], []).append(i)
    for members in groups.values():
        if len(members) < 2:
            continue
        for j in range(1, len(rows[0])):
            vals = [(number(rows[i][j]), i) for i in members]
            vals = [(v, i) for v, i in vals if v is not None]
            if len(vals) < 2:
                continue
            top = max(v for v, _ in vals)
            best |= {(i, j) for v, i in vals if v == top}
    return best


def read_table(md, name="Table 1"):
    text = open(md, encoding="utf-8").read()
    start = text.index("## " + name + " |")
    title = text[start:text.index("\n", start)].lstrip("# ").strip()
    rows, note = [], []
    for line in text[start:].split("\n")[1:]:
        if line.startswith("---") or line.startswith("## "):
            break
        if line.startswith("|"):
            if not re.match(r"^\|[-| ]+\|$", line):
                rows.append([c.strip().replace("`", "") for c in line.strip("|").split("|")])
        elif rows and line.strip():
            note.append(line.strip())
    return title, rows, " ".join(note)


def build(md, out, name="Table 1", cols=COLS, two_tone=True, merge_first=False, bold_best=False):
    title, rows, note = read_table(md, name)
    row_h = 8.6  # LibreOffice grows two-line rows past 7 mm
    # The note's height from its length: about 1.1 characters per mm of width at 5.5 pt, 2.6 mm a line.
    note_h = 2 + 2.6 * -(-len(note) // int((W - 2) * 1.1)) if note else 0
    H = 9 + row_h * len(rows) + (note_h + 2 if note else 2)
    prs = Presentation()
    prs.slide_width, prs.slide_height = Mm(W), Mm(H)
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    tb = slide.shapes.add_textbox(Mm(1), Mm(1), Mm(W - 2), Mm(6))
    tf = tb.text_frame
    tf.word_wrap = True
    r = tf.paragraphs[0].add_run()
    head, _, rest = title.partition("|")
    r.text, r.font.size, r.font.bold, r.font.name, r.font.color.rgb = head.strip() + " | ", Pt(7), True, FONT, INK
    r = tf.paragraphs[0].add_run()
    r.text, r.font.size, r.font.name, r.font.color.rgb = rest.strip(), Pt(7), FONT, INK
    ncol = len(rows[0])
    table = slide.shapes.add_table(len(rows), ncol, Mm(1), Mm(8), Mm(W - 2), Mm(row_h * len(rows))).table
    for j, w in enumerate(cols):
        table.columns[j].width = Mm(w)
    best = best_in_groups(rows) if bold_best else set()
    for i, row in enumerate(rows):
        for j, value in enumerate(row):
            c = table.cell(i, j)
            grid(c)
            if merge_first and j == 0 and i > 1 and value == rows[i - 1][0]:
                continue  # merged into the first row of its group below
            c.fill.background()
            tf = c.text_frame
            tf.word_wrap = True
            tf.margin_left = tf.margin_right = Mm(1.0)
            tf.margin_top = tf.margin_bottom = Mm(0.6)
            p = tf.paragraphs[0]
            p.alignment = PP_ALIGN.LEFT
            run = p.add_run()
            run.text = value
            run.font.name, run.font.size = FONT, Pt(6)
            run.font.bold = i == 0 or j == 0 or (i, j) in best
            run.font.color.rgb = INK if (i == 0 or j == 0 or not two_tone) else (PERSON if j == 1 else AGENT)
    if merge_first:  # one cell per dataset in the first column
        i = 1
        while i < len(rows):
            k = i
            while k + 1 < len(rows) and rows[k + 1][0] == rows[i][0]:
                k += 1
            if k > i:
                table.cell(i, 0).merge(table.cell(k, 0))
            i = k + 1
    if note:
        nb = slide.shapes.add_textbox(Mm(1), Mm(8 + row_h * len(rows) + 1.5), Mm(W - 2), Mm(note_h))
        nb.text_frame.word_wrap = True
        r = nb.text_frame.paragraphs[0].add_run()
        r.text, r.font.size, r.font.name, r.font.color.rgb = note, Pt(5.5), FONT, INK
    prs.save(out)
    return W, H


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("manuscript")
    ap.add_argument("out")
    ap.add_argument("--table", default="Table 1")
    ap.add_argument("--cols", help="column widths in mm, comma-separated (they should add up to 172)")
    ap.add_argument("--two-tone", action="store_true", help="a person's column grey, Automahout's blue (Table 1)")
    ap.add_argument("--merge-first", action="store_true", help="merge consecutive equal cells of the first column")
    ap.add_argument("--bold-best", action="store_true", help="bold the best value of each score within each group")
    ap.add_argument("--no-png", action="store_true")
    a = ap.parse_args()
    cols = [float(c) for c in a.cols.split(",")] if a.cols else COLS
    w, h = build(a.manuscript, a.out, a.table, cols, a.two_tone or a.table == "Table 1", a.merge_first, a.bold_best)
    print(a.out)
    if not a.no_png:
        print(render_png(a.out, w, h))
