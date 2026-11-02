"""The manuscript (Markdown) as a Word document, with the figures placed above their legends, and optionally a PDF.

Two layouts: `review` (A4, line numbers, for co-authors' comments and tracked changes) and `preprint` (A4, no line
numbers). Pandoc comes from pypandoc-binary in a throwaway environment, so the project's lock file is untouched; the
PDF is LibreOffice's rendering of the .docx. Word counts per part are printed.

    uv run --with pypandoc-binary python manuscript_docx.py MANUSCRIPT.md --figures paper/figures \\
        --out review.docx [--layout review|preprint] [--pdf]
"""

import argparse
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

import pypandoc

import sys

sys.path.insert(0, str(Path(__file__).resolve().parent / "figures"))  # the figure scripts import each other by name
from render import soffice  # noqa: E402
from table1 import best_in_groups  # noqa: E402

TEXT_W = 17.0  # cm: A4 less 2 cm margins

# Each legend's figure: panels as (letter or None, path relative to --figures, width in cm); one row per list.
FIGURES = {
    "Figure 1": [[(None, "fig1/fig1.png", 14.0)]],  # the panels assembled (figures/fig1_compose.py); with its legend on one page
    "Figure 2": [[(None, "fig2/fig2a.png", TEXT_W)]],
    **{f"Supplementary Figure {k}": [[(None, f"supp/figS{k}/figS{k}.png", TEXT_W)]] for k in range(1, 5)},
}


# A new page as a property of a hairline paragraph ("page break before"), not a break character: a break on a page that is
# already full would itself spill over and leave a blank page.
PAGE_BREAK = ('```{=openxml}\n<w:p><w:pPr><w:pageBreakBefore/><w:spacing w:before="0" w:after="0" w:line="20" '
              'w:lineRule="exact"/></w:pPr></w:p>\n```')


def with_figures(md, figures):
    """Each figure on a page of its own: its images, then its legend, starting a new page; the section heading over
    the first figure moves onto that page, and whatever follows the last legend starts a new page too. Each table
    (its heading, the table and its note) starts a new page as well."""
    out, last_fig = [], None
    for block in re.split(r"\n\s*\n", md):
        m = re.match(r"\*\*((?:Supplementary )?Figure \d+) \|", block)
        if m and m.group(1) in FIGURES:
            if out and re.match(r"^#{1,6} ", out[-1]):  # a heading right above: break before it, not between
                out.insert(len(out) - 1, PAGE_BREAK)
            else:
                out.append(PAGE_BREAK)
            for row in FIGURES[m.group(1)]:
                parts = []
                for letter, path, width in row:
                    f = Path(figures) / path
                    if not f.exists():
                        raise SystemExit(f"missing {f}")
                    parts.append((f"**{letter}** " if letter else "") + f"![]({f}){{width={width}cm}}")
                out.append(" ".join(parts))
            # the legend in its own style (single-spaced), so the figure and its legend share a page
            out.append('::: {custom-style="Figure Legend"}\n' + block + "\n:::")
            last_fig = len(out)
            continue
        if re.match(r"^## (Supplementary )?Table \d+ \|", block):  # each table on a page of its own
            out.append(PAGE_BREAK)
        if last_fig is not None and len(out) == last_fig and block.strip():
            out.append(PAGE_BREAK)  # the first block after a figure's legend that is not another figure
        out.append(block)
    return "\n\n".join(out)


def table_widths(md):
    """Pipe tables at full width with columns in proportion to their longest entry (capped, so one long cell
    does not starve the rest, and a floor for the numbers): pandoc takes relative widths from the dashes of the header separator."""
    lines, out, i = md.split("\n"), [], 0
    while i < len(lines):
        if lines[i].startswith("|") and i + 1 < len(lines) and re.match(r"^\|(\s*:?-+:?\s*\|)+\s*$", lines[i + 1]):
            j = i + 2
            while j < len(lines) and lines[j].startswith("|"):
                j += 1
            rows = [[c.strip() for c in l.strip().strip("|").split("|")] for l in lines[i:j] if l is not lines[i + 1]]
            longest = lambda k, f: max((f(r[k]) for r in rows if k < len(r)), default=0)
            word = lambda c: max((len(w) for w in c.split()), default=0)
            # wide enough for its longest word (no break inside one), at least 12, at most 26 unless a word needs it
            width = [max(round(1.8 * longest(k, word)), min(max(12, longest(k, len)), 26)) for k in range(len(rows[0]))]
            out += [lines[i], "|" + "|".join("-" * w for w in width) + "|"] + lines[i + 2:j]
            i = j
        else:
            out.append(lines[i])
            i += 1
    return "\n".join(out)


def bold_best(md, name):
    """The best value of each score within each dataset in bold, as table1.py --bold-best does on the slide."""
    lines = md.split("\n")
    start = next(i for i, l in enumerate(lines) if l.startswith(f"## {name} |"))
    idx = []
    for i in range(start + 1, len(lines)):
        if lines[i].startswith("## ") or lines[i].startswith("---"):
            break
        if lines[i].startswith("|") and not re.match(r"^\|[-| :]+\|$", lines[i]):
            idx.append(i)
    rows = [[c.strip() for c in lines[i].strip().strip("|").split("|")] for i in idx]
    for r, c in best_in_groups(rows):
        rows[r][c] = f"**{rows[r][c]}**"
    for i, row in zip(idx, rows):
        lines[i] = "| " + " | ".join(row) + " |"
    return "\n".join(lines)


# The order Word requires for the children of w:pPr and w:rPr (ECMA-376); out of order, Word may call the file damaged.
PPR_ORDER = ["pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr", "suppressLineNumbers",
             "pBdr", "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct", "topLinePunct",
             "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind", "contextualSpacing",
             "mirrorIndents", "suppressOverlap", "jc", "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl",
             "divId", "cnfStyle", "rPr", "sectPr", "pPrChange"]
RPR_ORDER = ["rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline", "shadow",
             "emboss", "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing", "w", "kern",
             "position", "sz", "szCs", "highlight", "u", "effect", "bdr", "shd", "fitText", "vertAlign", "rtl", "cs", "em",
             "lang", "eastAsianLayout", "specVanish", "oMath"]


def restyle(styles, sid, ppr=None, rpr=None):
    """Sets paragraph (ppr) and run (rpr) properties of one style in styles.xml, replacing the same kinds of element."""
    m = re.search(r'<w:style [^>]*w:styleId="' + re.escape(sid) + r'".*?</w:style>', styles, re.S)
    assert m, f"reference.docx: no style {sid}"
    block = m.group(0)
    for props, tag in ((ppr, "pPr"), (rpr, "rPr")):
        if not props:
            continue
        kinds = set(re.findall(r"<w:(\w+)", props))
        inner = re.search(rf"<w:{tag}>(.*?)</w:{tag}>", block, re.S)
        element = r"<w:\w+[^>]*/>|<w:(\w+)[^>]*>.*?</w:\1>"
        kept = [e.group(0) for e in re.finditer(element, inner.group(1), re.S)
                if re.match(r"<w:(\w+)", e.group(0)).group(1) not in kinds] if inner else []
        order = PPR_ORDER if tag == "pPr" else RPR_ORDER
        rank = lambda e: order.index(re.match(r"<w:(\w+)", e).group(1)) if re.match(r"<w:(\w+)", e).group(1) in order else len(order)  # noqa: E731
        children = sorted(kept + [e.group(0) for e in re.finditer(element, props, re.S)], key=rank)
        if inner:
            block = block.replace(inner.group(0), f"<w:{tag}>{''.join(children)}</w:{tag}>")
        else:
            block = block.replace("</w:style>", f"<w:{tag}>{''.join(children)}</w:{tag}></w:style>")
    return styles.replace(m.group(0), block)


def reference_docx(path, layout):
    """Pandoc's reference document: A4, 2 cm margins, Times New Roman 12 pt, double-spaced justified body text, black
    bold headings and, for review, line numbers."""
    subprocess.run([pypandoc.get_pandoc_path(), "-o", str(path), "--print-default-data-file", "reference.docx"],
                   check=True)
    with zipfile.ZipFile(path) as z:
        files = {n: z.read(n) for n in z.namelist()}
    doc = files["word/document.xml"].decode()
    numbering = '<w:lnNumType w:countBy="1" w:restart="continuous" w:distance="283"/>' if layout == "review" else ""
    sect = ('<w:sectPr><w:footnotePr><w:numRestart w:val="eachSect" /></w:footnotePr>'
            '<w:pgSz w:w="11906" w:h="16838"/>'
            '<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="567" w:footer="567" w:gutter="0"/>'
            f'{numbering}</w:sectPr>')
    doc, n = re.subn(r"<w:sectPr>.*?</w:sectPr>", sect, doc, flags=re.S)
    assert n == 1, "reference.docx: expected one sectPr"
    files["word/document.xml"] = doc.encode()
    styles = files["word/styles.xml"].decode()
    # Times New Roman 12 pt throughout (as bioRxiv manuscripts commonly are), black headings.
    times = '<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="Times New Roman" w:cs="Times New Roman" />'
    styles = re.sub(r'<w:rFonts w:asciiTheme="minorHAnsi"[^>]*/>', times, styles, count=1)
    styles = re.sub(r'(<w:rPrDefault>.*?)<w:sz w:val="\d+" />\s*<w:szCs w:val="\d+" />',
                    r'\1<w:sz w:val="24" /><w:szCs w:val="24" />', styles, count=1, flags=re.S)
    styles = re.sub(r'<w:rFonts w:asciiTheme="majorHAnsi"[^>]*/>', times, styles)
    styles = re.sub(r'<w:color w:val="[0-9A-Fa-f]{6}"( w:themeColor="[^"]*")?[^/]*/>', '<w:color w:val="000000" />', styles)
    # Body text double-spaced and justified; tight lists ("Compact") double-spaced too. Headings, the title and code
    # (based on Normal) stay single-spaced.
    styles = restyle(styles, "BodyText", ppr='<w:spacing w:before="0" w:after="120" w:line="480" w:lineRule="auto" />'
                                             '<w:jc w:val="both" />')
    styles = restyle(styles, "Compact", ppr='<w:spacing w:before="0" w:after="0" w:line="480" w:lineRule="auto" />')
    # Headings: bold, and room above and below them (the body under them is double-spaced).
    for sid, half_points, before, after in (("Title", 32, 0, 240), ("Heading2", 28, 360, 240), ("Heading3", 26, 240, 240),
                                            ("Heading4", 24, 240, 240)):
        styles = restyle(styles, sid, ppr=f'<w:spacing w:before="{before}" w:after="{after}" w:line="240" w:lineRule="auto" />',
                         rpr=f'<w:b /><w:sz w:val="{half_points}" /><w:szCs w:val="{half_points}" />')
    # Code (pandoc's "Verbatim Char") at the body's size, in its own face.
    styles = restyle(styles, "VerbatimChar", rpr='<w:sz w:val="24" /><w:szCs w:val="24" />')
    # Figure legends single-spaced (still justified), so each figure shares its page with its legend.
    styles = styles.replace("</w:styles>",
                            '<w:style w:type="paragraph" w:customStyle="1" w:styleId="FigureLegend"><w:name w:val="Figure Legend"/>'
                            '<w:basedOn w:val="BodyText"/><w:pPr><w:spacing w:before="0" w:after="120" w:line="240" '
                            'w:lineRule="auto"/><w:jc w:val="both"/></w:pPr></w:style></w:styles>')
    # Table cells get their own style: single-spaced, left-aligned, 10 pt, so tables fit (pandoc makes them "Compact",
    # like tight lists; rows_unsplit() moves them to this style).
    styles = styles.replace("</w:styles>",
                            '<w:style w:type="paragraph" w:customStyle="1" w:styleId="TableText"><w:name w:val="Table Text"/>'
                            '<w:basedOn w:val="Compact"/><w:pPr><w:spacing w:before="0" w:after="0" w:line="240" '
                            'w:lineRule="auto"/><w:jc w:val="left"/></w:pPr><w:rPr><w:sz w:val="20"/><w:szCs w:val="20"/>'
                            "</w:rPr></w:style></w:styles>")
    files["word/styles.xml"] = styles.encode()
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for n, b in files.items():
            z.writestr(n, b)


def word_counts(md):
    """Words per part, prose only (no tables, headings or code), as a journal counts them."""
    parts, name = {}, None
    for line in md.splitlines():
        h = re.match(r"^(#{2,3}) (.+)", line)
        if h:
            title = h.group(2).split(" |")[0]
            if h.group(1) == "##":
                name = title
            continue
        if not line.strip() or line.lstrip().startswith(("|", "`", "!")) or name is None:
            continue
        parts[name] = parts.get(name, 0) + len(re.findall(r"[A-Za-z0-9][\w'’–\-.]*", line))
    return parts


def table_drift(md, figures):
    """Names of the tables whose slide source (paper/figures/.../*.md) no longer matches the manuscript's copy."""
    sources = {"Table 1": Path(figures) / "table1" / "table1.md",
               "Supplementary Table 1": Path(figures) / "supp" / "tableS1" / "tableS1.md"}
    def section(text, name):
        start = text.find(f"## {name} |")
        if start < 0:
            return None
        end = min(i for i in (text.find("\n## ", start + 1), text.find("\n---", start + 1), len(text)) if i > 0)
        return " ".join(text[start:end].split())
    drift = []
    for name, src in sources.items():
        if src.exists() and section(md, name) != section(src.read_text(encoding="utf-8"), name):
            drift.append(f"{name} ({src})")
    return drift


def layout_lint(md):
    """Lines that would turn into the wrong element: a heading that swallowed text, or a paragraph that pandoc
    reads as a numbered list because it starts with "(1)" or "1." after a blank line or a heading."""
    out, lines = [], md.split("\n")
    for i, line in enumerate(lines, 1):
        if re.match(r"^#{1,6} ", line) and len(line) > 100:
            out.append(f"line {i}: a heading of {len(line)} characters (text merged into it?)")
        after_break = i > 1 and (not lines[i - 2].strip() or re.match(r"^#{1,6} ", lines[i - 2]))
        if after_break and re.match(r"^\(?\d+[).] ", line) \
                and not re.match(r"^\d+\. [A-Z]", line):
            out.append(f"line {i}: starts like a numbered list: {line[:40]!r}")
    return out


def rows_unsplit(docx):
    """Every table row kept on one page (w:cantSplit), and each table's header row repeated on a new page: a row
    split at a page break leaves a fragment that reads as an extra, almost empty row."""
    with zipfile.ZipFile(docx) as z:
        files = {n: z.read(n) for n in z.namelist()}
    doc = files["word/document.xml"].decode()
    doc = re.sub(r"<w:trPr>(?!.*?<w:cantSplit)", "<w:trPr><w:cantSplit/>", doc)
    doc = re.sub(r"<w:tr>(?!\s*<w:trPr>)", "<w:tr><w:trPr><w:cantSplit/></w:trPr>", doc)
    rule = lambda side, eighths: f'<w:{side} w:val="single" w:sz="{eighths}" w:space="0" w:color="000000"/>'  # noqa: E731

    def table_text(m):
        t = m.group(0).replace('<w:pStyle w:val="Compact" />', '<w:pStyle w:val="TableText" />')
        t = re.sub(r'(<w:rStyle w:val="VerbatimChar" />)', r'\1<w:sz w:val="20" /><w:szCs w:val="20" />', t)
        # Horizontal rules only: thick above the header and at the bottom, thin between rows ...
        borders = ("<w:tblBorders>" + rule("top", 12) + rule("bottom", 12) + rule("insideH", 4)
                   + '<w:left w:val="nil"/><w:right w:val="nil"/><w:insideV w:val="nil"/></w:tblBorders>')
        # (in the schema's order: tblBorders before tblLayout and tblLook, or Word and LibreOffice ignore it)
        t = re.sub(r"(<w:tblLayout|<w:tblLook|</w:tblPr>)", lambda m: borders + m.group(1), t, count=1)
        # ... and thick under the header row (the table's first row).
        first = re.search(r"<w:tr>.*?</w:tr>", t, re.S)
        if first:
            under = "<w:tcBorders>" + rule("bottom", 12) + "</w:tcBorders>"
            row = re.sub(r"<w:tc>(?!\s*<w:tcPr>)", "<w:tc><w:tcPr>" + under + "</w:tcPr>", first.group(0))
            # tcBorders after the cell's width (tcW), as the schema orders them
            row = re.sub(r"(<w:tcPr>(?:<w:tcW[^>]*/>)?)(?!<w:tcBorders>)", lambda m: m.group(1) + under, row)
            t = t.replace(first.group(0), row, 1)
        return t
    doc = re.sub(r"<w:tbl>.*?</w:tbl>", table_text, doc, flags=re.S)
    # Paragraphs holding a figure: single-spaced (auto spacing multiplies the line height, image included), centred.
    def figure_paragraph(m):
        para = m.group(0)
        if "<w:drawing>" not in para:
            return para
        props = '<w:spacing w:before="0" w:after="120" w:line="240" w:lineRule="auto" /><w:jc w:val="center" />'
        if "<w:pPr>" in para:  # after the paragraph's style, as the schema orders them
            return re.sub(r"<w:pPr>(<w:pStyle[^>]*/>)?", lambda m: "<w:pPr>" + (m.group(1) or "") + props, para, count=1)
        return para.replace("<w:p>", "<w:p><w:pPr>" + props + "</w:pPr>", 1)
    doc = re.sub(r"<w:p>(?:(?!</w:p>).)*</w:p>", figure_paragraph, doc, flags=re.S)
    # the first row of each table is its header: repeat it at the top of each page the table spans
    doc = re.sub(r"(<w:tbl>.*?<w:tr><w:trPr>(?:<w:cantSplit/>)?)", r"\1<w:tblHeader/>", doc, flags=re.S)  # after cantSplit
    files["word/document.xml"] = doc.encode()
    with zipfile.ZipFile(docx, "w", zipfile.ZIP_DEFLATED) as z:
        for n, b in files.items():
            z.writestr(n, b)
    return doc.count("<w:cantSplit/>")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("manuscript")
    ap.add_argument("--figures", required=True, help="the paper/figures folder")
    ap.add_argument("--out", required=True)
    ap.add_argument("--layout", choices=("review", "preprint"), default="review")
    ap.add_argument("--pdf", action="store_true", help="also write a PDF beside the .docx (LibreOffice)")
    a = ap.parse_args()
    md = Path(a.manuscript).read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory() as tmp:
        ref = Path(tmp) / "reference.docx"
        reference_docx(ref, a.layout)
        pypandoc.convert_text(table_widths(bold_best(with_figures(md, a.figures), "Supplementary Table 1")), "docx", format="markdown", outputfile=a.out,
                              extra_args=[f"--reference-doc={ref}"])
    rows_unsplit(a.out)
    print(a.out)
    for w in layout_lint(md):
        print(f"  WARNING: {w}")
    for t in table_drift(md, a.figures):
        print(f"  WARNING: {t} differs from the manuscript's table: update the source and re-render the slide")
    for name, n in word_counts(md).items():
        print(f"  {name:45s} {n:6d} words")
    if a.pdf:
        out = Path(a.out).resolve()
        subprocess.run([soffice(), "--headless", "--convert-to", "pdf", "--outdir", str(out.parent), str(out)],
                       check=True, capture_output=True)
        print(out.with_suffix(".pdf"))


if __name__ == "__main__":
    main()
