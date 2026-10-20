"""Add OCR text to selected pages without replacing their original artwork."""

import os
import shutil
import subprocess
from pathlib import Path

import numpy
import pymupdf


def prepare_pdf_text(input_path, output_path, pages, progress=None):
    """Return (input for BabelDOC, OCR pages, whether selected pages have text).

    Native text pages are untouched. Textless pages retain their rendered
    appearance plus an invisible OCR layer with a real, renderable font.
    Only selected pages are rendered, sequentially, to bound CPU and memory.
    """
    input_path, output_path = Path(input_path), Path(output_path)
    ocr_pages = []
    has_text = False
    with pymupdf.open(input_path) as document:
        selected = selected_page_indices(pages, len(document))
        for index, page_index in enumerate(selected):
            page = document[page_index]
            if any(char.isalnum() for char in page.get_text()):
                has_text = True
                continue
            if progress:
                progress(index, len(selected))
            text_pdf = ocr_text_layer(page, graphic_regions(page))
            with pymupdf.open(stream=text_pdf, filetype="pdf") as layer:
                if not any(char.isalnum() for char in layer[0].get_text()):
                    # Blank and illustration-only pages can pass through without
                    # asking the translator to translate an empty paragraph set.
                    continue
                # Path-only text and logos are otherwise discarded by BabelDOC's
                # vector reconstruction. Keep the original appearance as an
                # image, then put the searchable text on top. The source file
                # remains untouched. Native text pages never take this path.
                with pymupdf.open() as replacement:
                    background = replacement.new_page(
                        width=page.rect.width, height=page.rect.height)
                    background.insert_image(background.rect, pixmap=page.get_pixmap(
                        dpi=ocr_dpi(page), colorspace=pymupdf.csRGB, alpha=False))
                    background.show_pdf_page(background.rect, layer, 0)
                    document.delete_page(page_index)
                    document.insert_pdf(replacement, start_at=page_index)
            ocr_pages.append(page_index + 1)
            has_text = True
        if ocr_pages:
            output_path.parent.mkdir(parents=True, exist_ok=True)
            document.save(output_path, deflate=True)
            return output_path, ocr_pages, has_text
    return input_path, ocr_pages, has_text


def selected_page_indices(pages, page_count):
    selected = set()
    for item in pages.split(","):
        item = item.strip()
        if "-" in item:
            start, end = item.split("-", 1)
            start, end = int(start or 1), int(end or page_count)
        else:
            start = end = int(item)
        if start < 1 or end > page_count or start > end:
            raise ValueError(f"无效 PDF 页码范围: {item}")
        selected.update(range(start - 1, end))
    return sorted(selected)


def graphic_regions(page):
    """Embedded logos, icons and figures whose lettering must stay artwork.

    A raster covering most of the page is a scanned page, not a graphic, and
    its text is exactly what OCR should recognise.
    """
    page_area = abs(page.rect)
    regions = []
    for info in page.get_image_info():
        rect = pymupdf.Rect(info["bbox"]) & page.rect
        if not rect.is_empty and abs(rect) < page_area * 0.5:
            regions.append(rect)
    return regions


def ocr_text_layer(page, excluded_regions=()):
    command = shutil.which("tesseract")
    if not command:
        raise RuntimeError("此 PDF 缺少文字层，需安装 Tesseract 及英文识别数据后重试")
    # A4 at 300 DPI is about 26 MiB RGB. Do not keep pixmaps for other pages.
    dpi = ocr_dpi(page)
    pixmap = page.get_pixmap(dpi=dpi, colorspace=pymupdf.csRGB, alpha=False)
    env = os.environ.copy()
    env["OMP_THREAD_LIMIT"] = "1"
    result = subprocess.run(
        [command, "stdin", "stdout", "-l", "eng", "--dpi", str(dpi),
         "-c", "textonly_pdf=1", "pdf"],
        input=pixmap.tobytes("png"), capture_output=True, timeout=180, env=env,
    )
    if result.returncode:
        details = result.stderr.decode("utf-8", errors="replace")[-800:]
        raise RuntimeError(f"PDF 文字识别失败: {details}")
    if not result.stdout.startswith(b"%PDF-"):
        raise RuntimeError("PDF 文字识别未生成有效文字层")
    return renderable_text_layer(result.stdout, excluded_regions, pixmap)


def ocr_dpi(page):
    return min(300, max(1, int(6000 * 72 / max(page.rect.width, page.rect.height))))


# Font size comes from Tesseract word-box heights (about 0.86 em with
# Tesseract 5), corrected per page against the scanned ink: a prose line's ink
# spans ascender top to descender bottom, about 0.85 em in serif journal
# fonts, and Tesseract 4 boxes are about 9% shorter. Too small a size makes
# BabelDOC typeset the translation in tiny text and leave large blank areas.
OCR_INK_EM = 0.85
OCR_BOX_EM = 0.86


def ink_mask(pixmap):
    if pixmap is None:
        return None
    samples = numpy.frombuffer(pixmap.samples, dtype=numpy.uint8)
    pixels = samples.reshape(pixmap.height, pixmap.stride)[:, :pixmap.width * pixmap.n]
    return pixels.reshape(pixmap.height, pixmap.width, pixmap.n).min(axis=2) < 128


def word_ink_extent(mask, scale, box):
    """Vertical ink extent (top, bottom) in points of the word in box, or None."""
    x0, y0, x1, y1 = box
    height = y1 - y0
    rows = mask.shape[0]
    top = max(0, int((y0 - 0.4 * height) * scale))
    bottom = min(rows, int((y1 + 0.4 * height) * scale) + 1)
    left = max(0, int(x0 * scale))
    right = min(mask.shape[1], int(x1 * scale) + 1)
    if top >= bottom or left >= right:
        return None
    inked = numpy.flatnonzero(mask[top:bottom, left:right].any(axis=1)) + top
    inside = inked[(inked >= y0 * scale) & (inked <= y1 * scale)]
    if not len(inside):
        return None
    gap = max(2, int(0.12 * height * scale))
    first, last = int(inside.min()), int(inside.max())
    for row in inked[inked < first][::-1]:
        if first - row > gap:
            break
        first = int(row)
    for row in inked[inked > last]:
        if row - last > gap:
            break
        last = int(row)
    return first / scale, (last + 1) / scale


def word_ink_span(mask, scale, box, top, bottom, size):
    """Horizontal ink extent (left, right) in points of the word in box.

    Tesseract 4 reports word boxes about 12% narrower than the ink, which
    squeezes the substitute glyphs; masks then miss word endings. Gaps inside
    a word are well below the inter-word space (about 0.25 em).
    """
    x0, _, x1, _ = box
    first_row, last_row = max(0, int(top * scale)), min(mask.shape[0], int(bottom * scale) + 1)
    left = max(0, int((x0 - 0.6 * size) * scale))
    right = min(mask.shape[1], int((x1 + 0.6 * size) * scale) + 1)
    if first_row >= last_row or left >= right:
        return x0, x1
    inked = numpy.flatnonzero(mask[first_row:last_row, left:right].any(axis=0)) + left
    inside = inked[(inked >= x0 * scale) & (inked <= x1 * scale)]
    if not len(inside):
        return x0, x1
    gap = max(2, int(0.15 * size * scale))
    first, last = int(inside.min()), int(inside.max())
    for column in inked[inked < first][::-1]:
        if first - column > gap:
            break
        first = int(column)
    for column in inked[inked > last]:
        if column - last > gap:
            break
        last = int(column)
    return first / scale, (last + 1) / scale


def ocr_lines(words):
    """Group OCR words whose boxes overlap vertically into lines."""
    lines = []
    for word in sorted(words, key=lambda item: ((item[1] + item[3]) / 2, item[0])):
        for line in lines:
            ly0, ly1 = line["y0"], line["y1"]
            overlap = min(ly1, word[3]) - max(ly0, word[1])
            if overlap > 0.5 * min(ly1 - ly0, word[3] - word[1]):
                line["words"].append(word)
                line["y0"], line["y1"] = min(ly0, word[1]), max(ly1, word[3])
                break
        else:
            lines.append({"words": [word], "y0": word[1], "y1": word[3]})
    return [line["words"] for line in lines]


def renderable_text_layer(ocr_pdf, excluded_regions=(), pixmap=None):
    # Tesseract's GlyphLessFont contains no visible glyphs. BabelDOC copies
    # protected numbers/citations as formulas, producing black rectangles when
    # that hidden font is re-rendered. Use actual Helvetica glyphs at the OCR
    # word positions so those preserved fragments can be drawn correctly.
    font = pymupdf.Font("helv")
    mask = ink_mask(pixmap)
    with pymupdf.open(stream=ocr_pdf, filetype="pdf") as source, pymupdf.open() as result:
        original = source[0]
        page = result.new_page(width=original.rect.width, height=original.rect.height)
        scale = pixmap.height / original.rect.height if pixmap is not None else 0
        words = []
        for x0, y0, x1, y1, text, *_ in original.get_text("words"):
            # Lettering inside logos, journal covers and figure images would
            # otherwise join nearby paragraphs; the white OCR mask then erases
            # the artwork and the translator rewrites brand names.
            center = pymupdf.Point((x0 + x1) / 2, (y0 + y1) / 2)
            if any(center in region for region in excluded_regions):
                continue
            if y1 > y0 and x1 > x0:
                words.append((x0, y0, x1, y1, text))
        lines = []
        for line in ocr_lines(words):
            heights = sorted(word[3] - word[1] for word in line)
            box_size = heights[len(heights) // 2] / OCR_BOX_EM
            extents = [] if mask is None else [
                extent for extent in
                (word_ink_extent(mask, scale, word[:4]) for word in line) if extent]
            ink = (min(e[0] for e in extents), max(e[1] for e in extents)) if extents else None
            lines.append((line, box_size, ink))
        # Corrections are per page, never per line or word: BabelDOC splits
        # paragraphs where font size, line pitch or word spacing turn
        # irregular, and per-line ink measurements jitter (lines without
        # descenders measure short). Box geometry is regular within a font, so
        # scaling it by the page's median ink ratio keeps paragraphs intact
        # while removing the Tesseract-version bias (Tesseract 4 boxes are
        # about 9% shorter and 12% narrower than Tesseract 5).
        ratios = sorted((ink[1] - ink[0]) / OCR_INK_EM / box_size
                        for line, box_size, ink in lines if ink and len(line) >= 3)
        factor = min(1.4, max(0.9, ratios[len(ratios) // 2])) if ratios else 1.0
        widths = []
        for line, box_size, ink in lines:
            if not ink:
                continue
            for word in line:
                if len(word[4]) >= 3:
                    left, right = word_ink_span(mask, scale, word[:4], ink[0], ink[1],
                                                box_size * factor)
                    widths.append((right - left) / (word[2] - word[0]))
        widths.sort()
        width_factor = min(1.3, max(1.0, widths[len(widths) // 2])) if widths else 1.0
        # Emit words in Tesseract's reading order. BabelDOC assembles
        # paragraphs from content-stream order; emitting by visual row would
        # interleave the two columns and shred every paragraph.
        placements = {}
        for line, box_size, _ in lines:
            size = round(box_size * factor, 2)
            line = sorted(line)
            for index, word in enumerate(line):
                x0, x1 = word[0], word[0] + (word[2] - word[0]) * width_factor
                if index + 1 < len(line):
                    x1 = min(x1, line[index + 1][0] - 0.1 * size)
                placements[id(word)] = size, x1
        for word in words:
            x0, y0, _, y1, text = word
            size, x1 = placements[id(word)]
            width = font.text_length(text, fontsize=size)
            if width <= 0 or x1 <= x0:
                continue
            origin = pymupdf.Point(x0, y1 - 0.2 * size)
            page.insert_text(origin, text, fontname="helv", fontsize=size,
                             render_mode=3,
                             morph=(origin, pymupdf.Matrix((x1 - x0) / width, 1)))
        return result.tobytes(deflate=True)


def preserve_textless_pages(input_path, output_dir, pages):
    """Keep genuinely empty/illustration-only ranges instead of failing parsing."""
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    with pymupdf.open(input_path) as source, pymupdf.open() as result:
        for index in selected_page_indices(pages, len(source)):
            result.insert_pdf(source, from_page=index, to_page=index)
        result.save(output_dir / "passthrough.zh.mono.pdf", deflate=True)
        result.save(output_dir / "passthrough.zh.dual.pdf", deflate=True)


def line_fill_boxes(char_boxes, vertical_padding=0.25, horizontal_padding=0.4):
    """Merge OCR character boxes into padded per-line rectangles.

    Boxes are (x0, y0, x1, y1) with x0 < x1 and y0 < y1. BabelDOC's OCR mode
    otherwise paints the paragraph's whole layout box white, which erases
    logos and rules sitting beside the text inside the same layout region.
    Padding is relative to the row height: glyph boxes of the substitute font
    are tighter than the scanned ink, so descenders and the last glyph of a
    line would otherwise show through beneath the translation.
    """
    rows = []
    for x0, y0, x1, y1 in sorted(
        (box for box in char_boxes if box[2] > box[0] and box[3] > box[1]),
        key=lambda box: (-(box[1] + box[3]) / 2, box[0]),
    ):
        for row in rows:
            overlap = min(row[3], y1) - max(row[1], y0)
            if overlap > 0.5 * min(row[3] - row[1], y1 - y0):
                row[:] = [min(row[0], x0), min(row[1], y0),
                          max(row[2], x1), max(row[3], y1)]
                break
        else:
            rows.append([x0, y0, x1, y1])
    return [
        (x0 - (y1 - y0) * horizontal_padding, y0 - (y1 - y0) * vertical_padding,
         x1 + (y1 - y0) * horizontal_padding, y1 + (y1 - y0) * vertical_padding)
        for x0, y0, x1, y1 in rows
    ]
