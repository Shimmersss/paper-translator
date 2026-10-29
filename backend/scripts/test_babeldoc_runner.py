import hashlib
import os
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from tokenizer_cache import O200K_CACHE_KEY, O200K_SHA256, configure_tokenizer_cache

from reference_layout import (
    is_numbered_reference,
    is_reference_paragraph,
    reference_number,
    reference_section_flags,
    reference_split_points,
)


class ReferenceLayoutTest(unittest.TestCase):
    def test_recognizes_supported_reference_numbers(self):
        self.assertEqual(1, reference_number("[1] Author, 2024. Title."))
        self.assertEqual(23, reference_number("(23) Author, 2024. Title."))
        self.assertEqual(7, reference_number("7. Author, 2024. Title."))

    def test_requires_bibliographic_signal(self):
        self.assertTrue(is_numbered_reference("[1] Author et al. Journal, 2024."))
        self.assertTrue(is_numbered_reference("2. https://example.com/paper"))
        self.assertFalse(is_numbered_reference("1. 安装依赖"))
        self.assertFalse(is_numbered_reference("普通正文 2024"))

    def test_preserves_reference_headings_and_entries(self):
        self.assertTrue(is_reference_paragraph("References"))
        self.assertTrue(is_reference_paragraph("BIBLIOGRAPHY"))
        self.assertTrue(is_reference_paragraph("Literature Cited"))
        self.assertFalse(is_reference_paragraph("Reference"))
        self.assertTrue(
            is_reference_paragraph(
                "Smith, J. (2022). Reliable systems. Journal of Testing, 4(2), 1-9."
            )
        )
        self.assertTrue(is_reference_paragraph("[8] Doe et al. Proceedings, 2023."))

    def test_does_not_skip_normal_academic_prose(self):
        self.assertFalse(
            is_reference_paragraph(
                "Smith et al. (2022) showed that the proposed method improves accuracy."
            )
        )
        self.assertFalse(is_reference_paragraph("1. Install the package in 2024."))

    def test_preserves_entire_reference_section_until_appendix(self):
        self.assertEqual(
            [False, True, True, True, False, False],
            reference_section_flags(
                [
                    "Conclusion",
                    "References",
                    (
                        "Vaswani, A., Shazeer, N., Parmar, N. (2017). "
                        "Attention is all you need."
                    ),
                    "A source without machine-readable publication metadata.",
                    "Appendix A",
                    "Additional experiments should still be translated.",
                ]
            ),
        )
        self.assertEqual(
            [False, False, False],
            reference_section_flags(["Reference", "BPA", "3.4 Risk predictions"]),
        )

    def test_continues_reference_section_across_page_chunks(self):
        self.assertEqual(
            [True, True, False, False],
            reference_section_flags(
                [
                    "A source without machine-readable publication metadata.",
                    "Another weak reference entry.",
                    "Supplementary Material",
                    "Additional experiments should still be translated.",
                ],
                in_reference_section=True,
            ),
        )

    def test_titled_appendix_headings_end_reference_section(self):
        weak_entry = "A source without machine-readable publication metadata."
        body = "The governing equations are derived below."
        for heading in (
            "Appendix A. Derivation of the governing equations",
            "APPENDIX B: PROOF OF THEOREM 1",
            "Appendix A Supplementary data",
            "Appendix A. Supplementary data Supplementary data to this article "
            "can be found online at https://doi.org/10.1016/j.example.2025.1",
            "Appendices",
            "7. Appendix",
            "Supporting Information",
            "Methods",
            "Acknowledgements",
            "GraspGen-X: Supplementary Material",
        ):
            with self.subTest(heading=heading):
                self.assertEqual(
                    [True, True, False, False],
                    reference_section_flags(
                        ["References", weak_entry, heading, body]
                    ),
                )

    def test_lettered_appendix_headings_end_reference_section(self):
        # RT-2 ("A. Contributions") and Mamba ("A" above "Discussion: Selection
        # Mechanism") appendices were kept in English as if they were references.
        weak_entry = "A source without machine-readable publication metadata."
        body = "The governing equations are derived below."
        for headings in (
            ["A. Contributions"],
            ["A Discussion: Selection Mechanism"],
            ["A.1 Training Details"],
            ["A", "Discussion: Selection Mechanism"],
            ["A.", "Data Mixture Details"],
        ):
            with self.subTest(headings=headings):
                flags = reference_section_flags(["References", weak_entry, *headings, body])
                self.assertEqual([True, True], flags[:2])
                self.assertFalse(any(flags[2:]))

    def test_letter_a_inside_references_keeps_reference_section(self):
        for lines in (
            ["A. Radford, J. W. Kim, C. Hallacy, et al. Learning transferable models."],
            ["A. Radford. Improving language understanding by generative pre-training."],
            ["A. Herzog, et al. Do as I can, not as I say"],
            ["A survey of robot learning, 2020."],
            ["B. Datasets"],
            # References extracted one word per line.
            ["and", "A.", "Zisserman,"],
            ["Mish:", "A", "self"],
        ):
            with self.subTest(lines=lines):
                self.assertTrue(all(reference_section_flags(["References", *lines])))

    def test_appendix_mentions_in_entries_or_prose_keep_reference_section(self):
        self.assertEqual(
            [True, True, True],
            reference_section_flags(
                [
                    "References",
                    "Appendix is cited by an entry without metadata",
                    "Methods Appl. Mech. Eng. 200 (2011) 1-10",
                ]
            ),
        )
        for entry in (
            "[12] Smith: Supplementary Material",
            "J. Smith: Supporting Information",
        ):
            with self.subTest(entry=entry):
                self.assertEqual(
                    [True, True], reference_section_flags(["References", entry])
                )

    def test_only_splits_sequential_bibliographic_entries(self):
        self.assertEqual(
            [0, 2],
            reference_split_points(
                ["[1] Author, 2023.", "continued", "[2] Author, 2024."]
            ),
        )
        self.assertEqual([], reference_split_points(["1. 安装依赖", "2. 启动服务"]))
        self.assertEqual([], reference_split_points(["[1] Author, 2023.", "[3] Other, 2024."]))


class TokenizerCacheTest(unittest.TestCase):
    def test_packaged_o200k_cache_is_complete_and_selected(self):
        previous = os.environ.pop("TIKTOKEN_CACHE_DIR", None)
        try:
            cache_dir = configure_tokenizer_cache()
            cache_file = cache_dir / O200K_CACHE_KEY
            self.assertEqual(O200K_SHA256, hashlib.sha256(cache_file.read_bytes()).hexdigest())
            self.assertEqual(str(cache_dir), os.environ["TIKTOKEN_CACHE_DIR"])
        finally:
            if previous is None:
                os.environ.pop("TIKTOKEN_CACHE_DIR", None)
            else:
                os.environ["TIKTOKEN_CACHE_DIR"] = previous

    def test_explicit_cache_directory_is_preserved(self):
        previous = os.environ.get("TIKTOKEN_CACHE_DIR")
        try:
            with tempfile.TemporaryDirectory() as configured:
                self.assertEqual(Path(configured), configure_tokenizer_cache(configured))
                self.assertEqual(configured, os.environ["TIKTOKEN_CACHE_DIR"])
        finally:
            if previous is None:
                os.environ.pop("TIKTOKEN_CACHE_DIR", None)
            else:
                os.environ["TIKTOKEN_CACHE_DIR"] = previous


class RunnerDeliveryTest(unittest.IsolatedAsyncioTestCase):
    async def test_completion_emits_finish_for_untranslated_or_unattributed_paragraphs(self):
        import babeldoc_runner as runner
        from translation_completion import ParagraphOutcomes

        for known_page in (True, False):
            with self.subTest(known_page=known_page):
                outcomes, paragraph = ParagraphOutcomes(), object()
                if known_page:
                    outcomes.map_document([(3, [paragraph])])
                with outcomes.attempt(paragraph):
                    outcomes.record_failure(RuntimeError("provider unavailable"))

                async def events(config):
                    yield {"type": "finish"}

                emit = Mock()
                args = SimpleNamespace(input="input.pdf", output="output", pages="3",
                                       model="test", base_url="http://unused", api_key="test",
                                       qps=1, font_family="auto", force_reference_section=False)
                with ExitStack() as stack:
                    for name, replacement in {
                        "prepare_pdf_text": Mock(return_value=(Path("input.pdf"), [], True)),
                        "install_reference_layout_patches": Mock(),
                        "install_paragraph_tracking": Mock(),
                        "set_translate_rate_limiter": Mock(),
                        "RepairingOpenAITranslator": Mock(return_value=SimpleNamespace(outcomes=outcomes)),
                        "TranslationConfig": Mock(return_value=SimpleNamespace()),
                        "emit": emit,
                    }.items():
                        stack.enter_context(patch.object(runner, name, replacement))
                    stack.enter_context(patch.object(runner.high_level, "init"))
                    stack.enter_context(patch.object(runner.high_level, "async_translate", events))
                    stack.enter_context(patch.object(runner.DocLayoutModel, "load_onnx",
                                                    return_value=SimpleNamespace()))
                    await runner.run(args)
                self.assertEqual("paragraphs", emit.call_args_list[-2].args[0]["type"])
                self.assertEqual(1, emit.call_args_list[-2].args[0]["failed"])
                self.assertEqual({"type": "finish"}, emit.call_args_list[-1].args[0])


if __name__ == "__main__":
    unittest.main()
