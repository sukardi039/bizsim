#!/usr/bin/env python3
"""Render docs/USER_GUIDE.md to a printable PDF with no external toolchain.

There is no pandoc/wkhtmltopdf/Chromium in this environment, so this is a small markdown subset
renderer on top of fpdf2: headings, paragraphs, bullets, numbered lists, tables, fenced code, rules,
bold/italic/inline code. It handles exactly the constructs the guide uses, and refuses nothing — an
unrecognised line simply renders as a paragraph.

    python scripts/md_to_pdf.py docs/USER_GUIDE.md out.pdf
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from fpdf import FPDF
from fpdf.fonts import FontFace

FONTS = Path("/usr/share/fonts/truetype")
SANS = FONTS / "dejavu/DejaVuSans.ttf"
SANS_BOLD = FONTS / "dejavu/DejaVuSans-Bold.ttf"
# DejaVu ships no oblique face here; Liberation Sans Italic is metrically close and present.
SANS_ITALIC = FONTS / "liberation/LiberationSans-Italic.ttf"
MONO = FONTS / "dejavu/DejaVuSansMono.ttf"

INK = (28, 32, 38)
MUTED = (96, 104, 116)
ACCENT = (24, 92, 168)
RULE = (206, 212, 220)
HEAD_FILL = (240, 244, 249)


# The guide quotes the product's own Chinese labels ("其中：存货变动"), so the renderer needs a CJK
# face. WenQuanYi ships with the image and, failing that, sits beside the backend's PDF font.
CJK_CANDIDATES = [
    Path("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"),
    Path(__file__).resolve().parent.parent / "backend/src/main/resources/fonts/wqy-microhei.ttc",
]


def cjk_font() -> Path | None:
    for candidate in CJK_CANDIDATES:
        if candidate.exists():
            return candidate
    return None


def release_line() -> str:
    """The cover states the build it documents, read from the repo rather than typed by hand: a
    hard-coded version drifts the moment the next release ships."""
    repo = Path(__file__).resolve().parent.parent
    version = "?"
    try:
        for line in (repo / "backend/src/main/resources/application.yml").read_text().splitlines():
            if line.strip().startswith("version:"):
                version = line.split(":", 1)[1].strip()
                break
    except OSError:
        pass
    migrations = len(list((repo / "backend/src/main/resources/db/migration").glob("V*.sql")))
    today = __import__("datetime").date.today().strftime("%-d %B %Y")
    return f"Version {version}  ·  schema V{migrations}  ·  {today}"


def clean(text: str) -> str:
    """Text goes out as written: the CJK fallback renders the labels, ASCII needs nothing."""
    return text


class Guide(FPDF):
    def __init__(self) -> None:
        super().__init__(format="A4")
        self.set_margins(18, 20, 18)
        self.set_auto_page_break(True, margin=18)
        self.add_font("sans", "", str(SANS))
        self.add_font("sans", "B", str(SANS_BOLD))
        self.add_font("sans", "I", str(SANS_ITALIC))
        self.add_font("mono", "", str(MONO))
        cjk = cjk_font()
        if cjk is not None:
            # Every body face falls back to it per glyph, so a Chinese label inside an English
            # sentence renders instead of becoming a box (or being quietly edited out). All three
            # styles are registered because the fallback lookup is per (family, style): a bold run of
            # Chinese with only a regular face installed loses its glyphs silently.
            for style in ("", "B", "I"):
                self.add_font("cjk", style, str(cjk))
            self.set_fallback_fonts(["cjk"])
        self.title_text = "bizsim — user guide"

    def header(self) -> None:
        if self.page_no() == 1:
            return
        self.set_font("sans", "", 8)
        self.set_text_color(*MUTED)
        self.cell(0, 6, clean(self.title_text), align="R", new_x="LMARGIN", new_y="NEXT")
        self.set_draw_color(*RULE)
        self.line(self.l_margin, self.get_y(), self.w - self.r_margin, self.get_y())
        self.ln(4)

    def footer(self) -> None:
        self.set_y(-14)
        self.set_font("sans", "", 8)
        self.set_text_color(*MUTED)
        self.cell(0, 6, f"{self.page_no()}", align="C")


def inline(pdf: Guide, text: str, size: float, indent: float = 0) -> None:
    """One wrapped paragraph with **bold**, *italic* and `code` runs."""
    text = clean(text)
    pdf.set_font("sans", "", size)
    pdf.set_text_color(*INK)
    if indent:
        pdf.set_x(pdf.l_margin + indent)
    parts = re.split(r"(\*\*[^*]+\*\*|`[^`]+`|\*[^*]+\*)", text)
    with pdf.local_context(fill_opacity=1):
        for part in parts:
            if not part:
                continue
            if part.startswith("**") and part.endswith("**"):
                pdf.set_font("sans", "B", size)
                pdf.write(6, part[2:-2])
                pdf.set_font("sans", "", size)
            elif part.startswith("`") and part.endswith("`"):
                pdf.set_font("mono", "", size - 0.6)
                pdf.write(6, part[1:-1])
                pdf.set_font("sans", "", size)
            elif part.startswith("*") and part.endswith("*") and len(part) > 2:
                pdf.set_font("sans", "I", size)
                pdf.write(6, part[1:-1])
                pdf.set_font("sans", "", size)
            else:
                pdf.write(6, part)
    pdf.ln(6)


def heading(pdf: Guide, level: int, text: str) -> None:
    text = clean(text)
    if level == 1:
        pdf.ln(2)
        pdf.set_font("sans", "B", 20)
        pdf.set_text_color(*INK)
        pdf.multi_cell(0, 9, text, new_x="LMARGIN", new_y="NEXT")
        pdf.set_draw_color(*ACCENT)
        pdf.set_line_width(0.8)
        pdf.line(pdf.l_margin, pdf.get_y() + 1, pdf.l_margin + 28, pdf.get_y() + 1)
        pdf.set_line_width(0.2)
        pdf.ln(5)
        return
    sizes = {2: 14.5, 3: 12, 4: 10.5}
    pdf.ln(3 if level > 2 else 5)
    pdf.set_font("sans", "B", sizes.get(level, 10.5))
    pdf.set_text_color(*(ACCENT if level == 2 else INK))
    pdf.multi_cell(0, 7, text, new_x="LMARGIN", new_y="NEXT")
    pdf.ln(2)


def table(pdf: Guide, rows: list[list[str]]) -> None:
    header, body = rows[0], rows[1:]
    columns = len(header)
    widths = []
    for index in range(columns):
        longest = max(
            (len(clean(row[index])) for row in rows if index < len(row)), default=1
        )
        widths.append(max(9, min(70, longest * 1.05)))
    total = sum(widths) or 1
    usable = pdf.w - pdf.l_margin - pdf.r_margin
    widths = [width / total * usable for width in widths]

    pdf.set_draw_color(*RULE)
    pdf.set_line_width(0.2)
    headings = FontFace(emphasis="BOLD", color=INK, fill_color=HEAD_FILL)
    body_face = FontFace(color=(52, 58, 66))
    with pdf.table(
        col_widths=tuple(widths),
        text_align="LEFT",
        borders_layout="HORIZONTAL_LINES",
        line_height=5,
        padding=1.8,
        first_row_as_headings=True,
        headings_style=headings,
    ) as layout:
        for row in rows:
            cells = layout.row()
            for c_index in range(columns):
                value = clean(row[c_index]) if c_index < len(row) else ""
                value = re.sub(r"\*\*([^*]+)\*\*", r"\1", value)
                value = re.sub(r"`([^`]+)`", r"\1", value)
                cells.cell(value, style=body_face)
    pdf.ln(4)


def code_block(pdf: Guide, lines: list[str]) -> None:
    pdf.ln(1)
    pdf.set_font("mono", "", 8.5)
    pdf.set_text_color(40, 46, 54)
    pdf.set_fill_color(246, 248, 250)
    pdf.set_x(pdf.l_margin + 2)
    pdf.multi_cell(
        pdf.w - pdf.l_margin - pdf.r_margin - 2,
        4.8,
        clean("\n".join(lines)),
        fill=True,
        new_x="LMARGIN",
        new_y="NEXT",
        padding=2,
    )
    pdf.ln(3)


def render(markdown: str, out: Path) -> None:
    pdf = Guide()
    pdf.set_title("bizsim — user guide")
    pdf.set_author("bizsim")

    # Cover: start low enough that the block sits near the optical centre, not in the top corner.
    pdf.add_page()
    pdf.ln(72)
    pdf.set_font("sans", "B", 30)
    pdf.set_text_color(*INK)
    pdf.cell(0, 14, "bizsim", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("sans", "", 16)
    pdf.set_text_color(*ACCENT)
    pdf.cell(0, 10, "User guide", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(6)
    pdf.set_font("sans", "", 10.5)
    pdf.set_text_color(*MUTED)
    pdf.multi_cell(
        0,
        6,
        "Signing in, what each role sees, and what to click in every section: templates, companies,\n"
        "packages, people, sessions and the debrief.",
    )
    pdf.ln(10)
    pdf.set_draw_color(*ACCENT)
    pdf.set_line_width(0.6)
    pdf.line(pdf.l_margin, pdf.get_y(), pdf.l_margin + 60, pdf.get_y())
    pdf.set_line_width(0.2)
    pdf.ln(8)
    pdf.set_font("sans", "", 9)
    pdf.set_text_color(*MUTED)
    pdf.multi_cell(
        0,
        5.6,
        "Two jobs, two people: a BUILDER prepares templates, companies and packages; a FACILITATOR\n"
        "starts sessions from a published package and runs the debrief. An ADMIN does both.",
    )
    pdf.ln(6)
    pdf.set_font("sans", "", 9)
    pdf.set_text_color(*MUTED)
    pdf.multi_cell(
        0,
        5.6,
        "A BUILDER cannot run a session; a FACILITATOR cannot edit what they run. The server enforces "
        "it, so the sections you can see are the sections your role owns.",
    )

    # Contents: fills the cover usefully instead of leaving a third of the page blank.
    sections = [line[3:].strip() for line in markdown.splitlines() if line.startswith("## ")]
    pdf.ln(16)
    pdf.set_font("sans", "B", 9)
    pdf.set_text_color(*ACCENT)
    pdf.cell(0, 6, "CONTENTS", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(1)
    half = (len(sections) + 1) // 2
    column_width = (pdf.w - pdf.l_margin - pdf.r_margin) / 2
    top = pdf.get_y()
    for index, title in enumerate(sections):
        column = 0 if index < half else 1
        row = index if column == 0 else index - half
        pdf.set_xy(pdf.l_margin + column * column_width, top + row * 5.6)
        pdf.set_font("sans", "", 8.8)
        pdf.set_text_color(*(INK if column == 0 else (52, 58, 66)))
        pdf.cell(column_width - 2, 5.6, clean(title), new_x="LEFT", new_y="TOP")
    pdf.set_y(top + half * 5.6 + 4)

    # Imprint, pinned to the bottom of the cover.
    pdf.set_y(-38)
    pdf.set_draw_color(*RULE)
    pdf.line(pdf.l_margin, pdf.get_y(), pdf.w - pdf.r_margin, pdf.get_y())
    pdf.ln(3)
    pdf.set_font("sans", "", 8.5)
    pdf.set_text_color(*MUTED)
    pdf.cell(0, 5, "bizsim · user guide · docs/USER_GUIDE.md", new_x="LMARGIN", new_y="NEXT")
    pdf.cell(0, 5, release_line(),
             new_x="LMARGIN", new_y="NEXT")

    pdf.add_page()
    lines = markdown.splitlines()
    i = 0
    paragraph: list[str] = []

    def flush() -> None:
        nonlocal paragraph
        if paragraph:
            inline(pdf, " ".join(paragraph).strip(), 9.6)
            paragraph = []

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if stripped.startswith("```"):
            flush()
            i += 1
            block = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                block.append(lines[i])
                i += 1
            code_block(pdf, block)
            i += 1
            continue

        if stripped.startswith("|"):
            flush()
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                raw = lines[i].strip().strip("|")
                if not re.fullmatch(r"[-: |]+", raw.replace(" ", "") or "-"):
                    rows.append([cell.strip() for cell in raw.split("|")])
                i += 1
            if rows:
                table(pdf, rows)
            continue

        if re.fullmatch(r"-{3,}", stripped):
            flush()
            pdf.ln(2)
            pdf.set_draw_color(*RULE)
            pdf.line(pdf.l_margin, pdf.get_y(), pdf.w - pdf.r_margin, pdf.get_y())
            pdf.ln(4)
            i += 1
            continue

        match = re.match(r"^(#{1,4})\s+(.*)$", stripped)
        if match:
            flush()
            heading(pdf, len(match.group(1)), match.group(2))
            i += 1
            continue

        match = re.match(r"^[-*]\s+(.*)$", stripped)
        if match:
            flush()
            pdf.set_font("sans", "", 9.6)
            pdf.set_text_color(*ACCENT)
            pdf.set_x(pdf.l_margin + 1)
            pdf.cell(4, 6, "•")
            inline(pdf, match.group(1), 9.6, indent=5)
            i += 1
            continue

        match = re.match(r"^(\d+)\.\s+(.*)$", stripped)
        if match:
            flush()
            pdf.set_font("sans", "B", 9.6)
            pdf.set_text_color(*ACCENT)
            pdf.set_x(pdf.l_margin + 1)
            pdf.cell(5, 6, match.group(1) + ".")
            inline(pdf, match.group(2), 9.6, indent=6)
            i += 1
            continue

        if not stripped:
            flush()
            i += 1
            continue

        paragraph.append(stripped)
        i += 1

    flush()
    pdf.output(str(out))


def main() -> None:
    source = Path(sys.argv[1] if len(sys.argv) > 1 else "docs/USER_GUIDE.md")
    target = Path(sys.argv[2] if len(sys.argv) > 2 else "user-guide.pdf")
    render(source.read_text(encoding="utf-8"), target)
    print(f"{target} — {target.stat().st_size} bytes")


if __name__ == "__main__":
    main()
