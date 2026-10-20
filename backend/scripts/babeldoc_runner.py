#!/usr/bin/env python3
import argparse
import asyncio
import copy
import json
import os
import re
import statistics
from pathlib import Path

from tokenizer_cache import configure_tokenizer_cache
from translation_completion import COMPLETION_CACHE_VERSION, complete_translation
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential
import openai
from pdf_ocr import line_fill_boxes, prepare_pdf_text, preserve_textless_pages

tokenizer_cache_override = os.environ.get("TIKTOKEN_CACHE_DIR")

from babeldoc.docvision.doclayout import DocLayoutModel
from babeldoc.format.pdf import high_level
from babeldoc.format.pdf.document_il import Box, PdfParagraph, PdfRectangle
from babeldoc.format.pdf.document_il.midend.il_translator import ILTranslator
from babeldoc.format.pdf.document_il.midend.paragraph_finder import (
    ParagraphFinder,
    generate_base58_id,
)
from babeldoc.format.pdf.document_il.midend.typesetting import Typesetting
from babeldoc.format.pdf.document_il.utils.style_helper import WHITE
from babeldoc.format.pdf.translation_config import (
    TranslationConfig,
    WatermarkOutputMode,
)
from babeldoc.translator.translator import OpenAITranslator, set_translate_rate_limiter

from reference_layout import (
    is_numbered_reference,
    is_reference_paragraph,
    reference_section_flags,
    reference_split_points,
)

# BabelDOC assigns its own runtime cache directory while importing. Restore an
# operator override or select the packaged cache after those imports complete.
configure_tokenizer_cache(tokenizer_cache_override)

COMMON_ENGLISH_WORDS = {
    "the",
    "and",
    "for",
    "with",
    "that",
    "this",
    "from",
    "between",
    "system",
    "systems",
    "model",
    "models",
    "data",
    "method",
    "methods",
    "results",
    "study",
    "studies",
    "reaction",
    "reactions",
    "chemical",
    "using",
    "used",
    "were",
    "was",
    "are",
    "is",
    "as",
    "in",
    "of",
    "to",
    "by",
    "on",
    "an",
    "be",
    "we",
    "it",
    "can",
}


def emit(event):
    print(json.dumps(event, ensure_ascii=False), flush=True)


def line_text(composition):
    line = getattr(composition, "pdf_line", None)
    if not line:
        return ""
    return "".join(char.char_unicode or "" for char in line.pdf_character).strip()


def split_merged_reference_paragraphs(paragraph_finder, paragraphs):
    """Split BabelDOC paragraphs when source reference entries have been merged."""
    index = 0
    while index < len(paragraphs):
        paragraph = paragraphs[index]
        compositions = paragraph.pdf_paragraph_composition or []
        split_points = reference_split_points(
            [line_text(composition) for composition in compositions]
        )
        if not split_points:
            index += 1
            continue

        if split_points[0] != 0:
            split_points.insert(0, 0)
        split_points.append(len(compositions))
        replacement = []
        for start, end in zip(split_points, split_points[1:]):
            if start == end:
                continue
            item = PdfParagraph(
                box=Box(0, 0, 0, 0),
                pdf_paragraph_composition=compositions[start:end],
                unicode="",
                debug_id=generate_base58_id(),
                layout_label=paragraph.layout_label,
                layout_id=paragraph.layout_id,
            )
            paragraph_finder.update_paragraph_data(item)
            replacement.append(item)
        paragraphs[index : index + 1] = replacement
        index += len(replacement)


def install_reference_layout_patches(force_reference_section=False):
    """Keep references untranslated and preserve BabelDOC compatibility fixes."""
    if getattr(ParagraphFinder, "_paper_translator_reference_layout_patch", False):
        return

    original_process = ParagraphFinder.process_independent_paragraphs
    original_layout = Typesetting._layout_typesetting_units
    original_pre_translate = ILTranslator.pre_translate_paragraph

    def process_independent_paragraphs(self, paragraphs, median_width):
        split_merged_reference_paragraphs(self, paragraphs)
        return original_process(self, paragraphs, median_width)

    def pre_translate_paragraph(
        self, paragraph, tracker, page_font_map, xobj_font_map
    ):
        # Returning no translation input leaves the original PDF line/character
        # compositions untouched. Typesetting can then pass them through at their
        # original coordinates instead of rebuilding the bibliography as CJK text.
        if not hasattr(self, "_paper_translator_reference_paragraph_ids"):
            document = getattr(self, "docs", None)
            document_paragraphs = (
                [
                    item
                    for page in document.page
                    for item in page.pdf_paragraph
                ]
                if document
                else []
            )
            flags = reference_section_flags(
                [item.unicode or "" for item in document_paragraphs],
                in_reference_section=force_reference_section,
            )
            self._paper_translator_reference_paragraph_ids = {
                id(item)
                for item, preserve in zip(document_paragraphs, flags)
                if preserve
            }
        if (
            id(paragraph) in self._paper_translator_reference_paragraph_ids
            or is_reference_paragraph(paragraph.unicode or "")
        ):
            tracker.set_pdf_unicode(paragraph.unicode)
            return None, None
        return original_pre_translate(
            self, paragraph, tracker, page_font_map, xobj_font_map
        )

    def layout_typesetting_units(
        self, typesetting_units, box, scale, line_skip, paragraph, use_english_line_break=True
    ):
        if not is_numbered_reference(paragraph.unicode or "") or not typesetting_units:
            return original_layout(
                self, typesetting_units, box, scale, line_skip, paragraph, use_english_line_break
            )

        font_sizes = [unit.font_size for unit in typesetting_units if unit.font_size]
        if not font_sizes:
            return original_layout(
                self, typesetting_units, box, scale, line_skip, paragraph, use_english_line_break
            )
        hanging_width = statistics.mode(font_sizes) * scale * 2
        if box.x + hanging_width >= box.x2:
            return original_layout(
                self, typesetting_units, box, scale, line_skip, paragraph, use_english_line_break
            )

        hanging_box = copy.deepcopy(box)
        hanging_box.x += hanging_width
        laid_out, all_fit = original_layout(
            self,
            typesetting_units,
            hanging_box,
            scale,
            line_skip,
            paragraph,
            use_english_line_break,
        )
        if not laid_out:
            return laid_out, all_fit

        first_line_y = max(unit.box.y for unit in laid_out)
        laid_out = [
            unit.relocate(unit.box.x - hanging_width, unit.box.y, 1)
            if abs(unit.box.y - first_line_y) < 0.1
            else unit
            for unit in laid_out
        ]
        return laid_out, all_fit

    ParagraphFinder.process_independent_paragraphs = process_independent_paragraphs
    Typesetting._layout_typesetting_units = layout_typesetting_units
    ILTranslator.pre_translate_paragraph = pre_translate_paragraph
    ParagraphFinder._paper_translator_reference_layout_patch = True


def paragraph_characters(paragraph):
    for composition in paragraph.pdf_paragraph_composition or []:
        if composition.pdf_line:
            yield from composition.pdf_line.pdf_character
        elif composition.pdf_formula:
            yield from composition.pdf_formula.pdf_character
        elif composition.pdf_same_style_characters:
            yield from composition.pdf_same_style_characters.pdf_character
        elif composition.pdf_character:
            yield composition.pdf_character


def ocr_text_fill_background(self, page):
    """Mask only the OCR text lines on the rendered page background.

    BabelDOC expands each mask to the detected layout box. For journal
    headers that box spans the publisher logo and cover image, which then
    disappear from the translated page.
    """
    for paragraph in page.pdf_paragraph:
        boxes = []
        for char in paragraph_characters(paragraph):
            box = char.visual_bbox.box if char.visual_bbox and char.visual_bbox.box else char.box
            if box is not None:
                boxes.append((box.x, box.y, box.x2, box.y2))
        for x0, y0, x1, y1 in line_fill_boxes(boxes):
            page.pdf_rectangle.append(
                PdfRectangle(
                    box=Box(x0, y0, x1, y1),
                    fill_background=True,
                    graphic_state=WHITE,
                    debug_info=False,
                    xobj_id=paragraph.xobj_id,
                )
            )


def install_ocr_fill_patch():
    ParagraphFinder.add_text_fill_background = ocr_text_fill_background


def repair_pdf_font_text(text):
    if not text:
        return text
    if len(re.findall(r"[A-Za-z]", text)) < 20:
        return text

    candidate = "".join(repair_pdf_font_char(char) for char in text)
    original_score = english_score(text)
    candidate_score = english_score(candidate)
    if (
        candidate_score["common_ratio"] >= 0.06
        and candidate_score["vowel_ratio"] > original_score["vowel_ratio"] + 0.08
        and candidate_score["score"] > original_score["score"] + 0.18
    ):
        return candidate
    return text


def repair_pdf_font_char(char):
    if "a" <= char <= "y":
        return chr(ord(char) + 1)
    if "A" <= char <= "V":
        return chr(ord(char) + 1)
    if char in {"W", "X", "Z"}:
        return "a"
    if "0" <= char <= "8":
        return chr(ord(char) + 1)
    return {
        "/": "0",
        "+": ",",
        "-": ".",
        ":": "A",
        ";": "A",
    }.get(char, char)


def english_score(text):
    letters = re.findall(r"[A-Za-z]", text)
    if not letters:
        return {"score": 0.0, "vowel_ratio": 0.0, "common_ratio": 0.0}
    vowels = sum(1 for char in letters if char.lower() in "aeiou")
    words = [word for word in re.split(r"[^A-Za-z]+", text.lower()) if len(word) >= 2]
    common = sum(1 for word in words if word in COMMON_ENGLISH_WORDS)
    vowel_ratio = vowels / len(letters)
    common_ratio = common / len(words) if words else 0.0
    return {
        "score": common_ratio * 3 + min(vowel_ratio, 0.45),
        "vowel_ratio": vowel_ratio,
        "common_ratio": common_ratio,
    }


class RepairingOpenAITranslator(OpenAITranslator):
    name = "openai-fontfix"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.add_cache_impact_parameters("pdf_font_text_repair", "shift-v1")
        self.add_cache_impact_parameters("translation_completeness", COMPLETION_CACHE_VERSION)

    @retry(retry=retry_if_exception_type(openai.RateLimitError),
           stop=stop_after_attempt(100), wait=wait_exponential(multiplier=1, min=1, max=15))
    def do_translate(self, text, rate_limit_params=None):
        return complete_translation(self, repair_pdf_font_text(text), rate_limit_params, plain=True)

    @retry(retry=retry_if_exception_type(openai.RateLimitError),
           stop=stop_after_attempt(100), wait=wait_exponential(multiplier=1, min=1, max=15))
    def do_llm_translate(self, text, rate_limit_params=None):
        return complete_translation(self, repair_pdf_font_text(text), rate_limit_params)


async def run(args):
    prepared_input, ocr_pages, has_text = prepare_pdf_text(
        args.input, Path(args.output).parent / "ocr-input.pdf", args.pages,
        progress=lambda current, total: emit({
            "type": "progress", "stage": "OCR 文字识别", "overallProgress": 0,
            "stageCurrent": current, "stageTotal": total,
        }),
    )
    if not has_text:
        preserve_textless_pages(args.input, args.output, args.pages)
        emit({"type": "progress", "stage": "保留无文字页面", "overallProgress": 100,
              "stageCurrent": 1, "stageTotal": 1})
        emit({"type": "finish"})
        return
    install_reference_layout_patches(args.force_reference_section)
    if ocr_pages:
        install_ocr_fill_patch()
    high_level.init()
    translator = RepairingOpenAITranslator(
        lang_in="en",
        lang_out="zh",
        model=args.model,
        base_url=args.base_url,
        api_key=args.api_key,
    )
    set_translate_rate_limiter(args.qps)
    doc_layout_model = DocLayoutModel.load_onnx()
    config = TranslationConfig(
        translator=translator,
        input_file=str(prepared_input),
        lang_in="en",
        lang_out="zh",
        doc_layout_model=doc_layout_model,
        pages=args.pages,
        output_dir=args.output,
        qps=args.qps,
        pool_max_workers=args.qps,
        term_pool_max_workers=args.qps,
        auto_extract_glossary=False,
        ocr_workaround=bool(ocr_pages),
        primary_font_family=None if args.font_family == "auto" else args.font_family,
        only_include_translated_page=True,
        watermark_output_mode=WatermarkOutputMode.NoWatermark,
        report_interval=0.2,
    )
    getattr(doc_layout_model, "init_font_mapper", lambda _config: None)(config)
    async for event in high_level.async_translate(config):
        event_type = event.get("type")
        if event_type in {"progress_start", "progress_update", "progress_end"}:
            emit(
                {
                    "type": "progress",
                    "stage": event.get("stage", ""),
                    "overallProgress": round(float(event.get("overall_progress", 0)), 1),
                    "stageCurrent": event.get("stage_current", 0),
                    "stageTotal": event.get("stage_total", 0),
                }
            )
        elif event_type == "error":
            emit({"type": "error", "message": str(event.get("error", "BabelDOC 翻译失败"))})
            raise RuntimeError(event.get("error", "BabelDOC 翻译失败"))
        elif event_type == "finish":
            if getattr(translator, "_paper_translator_incomplete_translation", False):
                raise RuntimeError("部分段落翻译不完整，已阻止交付缺字 PDF，请重试")
            emit({"type": "finish"})
            break


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--pages", required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--qps", type=int, required=True)
    parser.add_argument("--font-family", default="auto")
    parser.add_argument("--force-reference-section", action="store_true")
    args = parser.parse_args()
    args.api_key = os.environ["BABELDOC_OPENAI_API_KEY"]
    asyncio.run(run(args))
    # ONNX/CoreML may retain native worker threads after BabelDOC finishes.
    # The PDFs and the final JSON event are already flushed at this point.
    os._exit(0)


if __name__ == "__main__":
    main()
