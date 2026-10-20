import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import pymupdf

from pdf_ocr import (
    graphic_regions,
    line_fill_boxes,
    ocr_text_layer,
    prepare_pdf_text,
    preserve_textless_pages,
    renderable_text_layer,
    selected_page_indices,
)


class PdfOcrTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def text_pdf(self):
        path = self.root / "native.pdf"
        with pymupdf.open() as document:
            for _ in range(2):
                document.new_page().insert_text((72, 72),
                    "The model uses electric fields to control the wall jet.")
            document.save(path)
        return path

    def test_native_text_requires_no_ocr_and_preserves_source(self):
        source = self.text_pdf()
        original = source.read_bytes()
        with patch("pdf_ocr.ocr_text_layer", side_effect=AssertionError("unexpected OCR")):
            prepared, pages, has_text = prepare_pdf_text(source, self.root / "out.pdf", "1-2")
        self.assertEqual(source, prepared)
        self.assertEqual([], pages)
        self.assertTrue(has_text)
        self.assertEqual(original, source.read_bytes())
        self.assertFalse((self.root / "out.pdf").exists())

    def test_real_ocr_adds_text_only_to_selected_image_page(self):
        self.assertIsNotNone(shutil.which("tesseract"), "Install Tesseract for OCR validation")
        native = self.text_pdf()
        source = self.root / "image.pdf"
        with pymupdf.open(native) as native_document, pymupdf.open() as document:
            for native_page in native_document:
                page = document.new_page(width=native_page.rect.width, height=native_page.rect.height)
                page.insert_image(page.rect, pixmap=native_page.get_pixmap(dpi=300))
            document.save(source)
        original = source.read_bytes()
        with pymupdf.open(source) as document:
            before = document[0].get_pixmap().samples
        prepared, pages, has_text = prepare_pdf_text(source, self.root / "out.pdf", "1")
        self.assertEqual([1], pages)
        self.assertTrue(has_text)
        self.assertEqual(original, source.read_bytes())
        with pymupdf.open(prepared) as document:
            self.assertIn("electric fields", document[0].get_text())
            self.assertEqual("", document[1].get_text())
            after = document[0].get_pixmap().samples
            # Resampling the original background may differ at antialiased
            # edges, but must retain the page's appearance.
            self.assertLess(sum(abs(a-b) for a,b in zip(before,after))/len(before), 1)
            self.assertTrue(all("GlyphLess" not in font[3]
                                for font in document[0].get_fonts(full=True)))

    def test_real_blank_page_has_no_translation_input(self):
        source = self.root / "blank.pdf"
        with pymupdf.open() as document:
            document.new_page()
            document.save(source)
        prepared, pages, has_text = prepare_pdf_text(source, self.root / "out.pdf", "1")
        self.assertEqual(source, prepared)
        self.assertEqual([], pages)
        self.assertFalse(has_text)

    def test_textless_input_reports_missing_ocr_dependency(self):
        source = self.root / "blank.pdf"
        with pymupdf.open() as document:
            document.new_page()
            document.save(source)
        with patch("pdf_ocr.shutil.which", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "Tesseract"):
                prepare_pdf_text(source, self.root / "out.pdf", "1")

    def test_line_masks_cover_text_rows_without_spanning_gaps(self):
        boxes = line_fill_boxes([
            (10, 100, 20, 110), (22, 101, 40, 110),  # first row
            (10, 80, 30, 90),                         # second row
        ], vertical_padding=0, horizontal_padding=0)
        self.assertEqual([(10, 100, 40, 110), (10, 80, 30, 90)], boxes)
        padded = line_fill_boxes([(10, 100, 20, 110)])
        self.assertEqual([(6.0, 97.5, 24.0, 112.5)], padded)
        self.assertEqual([], line_fill_boxes([(5, 5, 5, 9)]))

    def test_small_images_are_graphics_but_scanned_pages_are_not(self):
        with pymupdf.open() as document:
            page = document.new_page(width=200, height=200)
            pixmap = pymupdf.Pixmap(pymupdf.csRGB, pymupdf.IRect(0, 0, 4, 4), False)
            page.insert_image(pymupdf.Rect(10, 10, 50, 50), pixmap=pixmap)
            page.insert_image(pymupdf.Rect(0, 0, 200, 200), pixmap=pixmap, overlay=False)
            self.assertEqual([pymupdf.Rect(10, 10, 50, 50)], graphic_regions(page))

    def test_text_layer_font_size_matches_source_text(self):
        with pymupdf.open() as document:
            page = document.new_page(width=300, height=200)
            page.insert_text((20, 100), "Typical gyroscope journal", fontsize=10,
                             fontname="tiro")
            image = pymupdf.open()
            scan = image.new_page(width=300, height=200)
            scan.insert_image(scan.rect, pixmap=page.get_pixmap(dpi=300))
            image_page = image[0]
            self.assertIsNotNone(shutil.which("tesseract"))
            layer = ocr_text_layer(image_page)
        with pymupdf.open(stream=layer, filetype="pdf") as result:
            sizes = [span["size"] for block in result[0].get_text("dict")["blocks"]
                     for line in block.get("lines", []) for span in line["spans"]]
        # Source is 10 pt; the old formula produced about 6.5 pt.
        self.assertTrue(sizes)
        self.assertTrue(all(8.5 <= size <= 11.5 for size in sizes), sizes)

    def test_two_column_words_keep_reading_order(self):
        with pymupdf.open() as document:
            page = document.new_page(width=400, height=200)
            # Reading order: left column top to bottom, then right column.
            for point, text in (((20, 40), "leftone"), ((20, 60), "lefttwo"),
                                ((220, 40), "rightone"), ((220, 60), "righttwo")):
                page.insert_text(point, text, fontsize=10)
            ocr = document.tobytes()
        layer = renderable_text_layer(ocr)
        with pymupdf.open(stream=layer, filetype="pdf") as result:
            words = [word[4] for word in result[0].get_text("words", sort=False)]
        self.assertEqual(["leftone", "lefttwo", "rightone", "righttwo"], words)

    def test_ocr_words_inside_graphics_are_dropped(self):
        with pymupdf.open() as document:
            page = document.new_page(width=300, height=200)
            page.insert_text((20, 40), "ELSEVIER", fontsize=12)
            page.insert_text((20, 150), "Translated body text", fontsize=12)
            ocr = document.tobytes()
        layer = renderable_text_layer(ocr, [pymupdf.Rect(0, 0, 300, 60)])
        with pymupdf.open(stream=layer, filetype="pdf") as result:
            text = result[0].get_text()
        self.assertNotIn("ELSEVIER", text)
        self.assertIn("Translated body text", text)

    def test_ranges_are_bounded_and_deduplicated(self):
        self.assertEqual([0, 1, 2, 4], selected_page_indices("1-3,2,5", 5))
        for pages in ("0", "6", "3-2"):
            with self.assertRaises(ValueError):
                selected_page_indices(pages, 5)

    def test_textless_range_preserves_selected_pages_and_dimensions(self):
        source = self.root / "illustrations.pdf"
        with pymupdf.open() as document:
            for size in (300, 400, 500):
                document.new_page(width=size, height=size).draw_rect(
                    pymupdf.Rect(20, 20, 100, 100), color=(1, 0, 0))
            document.save(source)
        preserve_textless_pages(source, self.root / "results", "2-3")
        with pymupdf.open(source) as original:
            for mode in ("mono", "dual"):
                with pymupdf.open(self.root / f"results/passthrough.zh.{mode}.pdf") as result:
                    self.assertEqual(2, len(result))
                    for index in range(2):
                        self.assertEqual(original[index + 1].rect, result[index].rect)
                        self.assertEqual(original[index + 1].get_pixmap().samples,
                                         result[index].get_pixmap().samples)


if __name__ == "__main__":
    unittest.main()
