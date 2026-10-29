import unittest
from types import SimpleNamespace as NS
from unittest.mock import Mock

from translation_completion import (
    IncompleteTranslationError, ParagraphOutcomes, checked_content, complete_translation,
    disable_thinking, fail_closed,
)



def response(text, reason="stop"):
    return NS(choices=[NS(message=NS(content=text), finish_reason=reason)])


def translator(*responses):
    create = Mock(side_effect=responses)
    return NS(
        client=NS(chat=NS(completions=NS(create=create))), model="test",
        options={"temperature": 0}, send_temperature=True,
        enable_json_mode_if_requested=True, send_dashscope_header=False,
        extra_body={"reasoning_effort": "minimal"}, update_token_count=Mock(),
        prompt=lambda text: [{"role": "user", "content": text}],
        add_cache_impact_parameters=Mock(),
    )


class TranslationCompletionTest(unittest.TestCase):
    def test_rejects_empty_and_truncated_provider_responses(self):
        for item in (response(""), response(None), response("半段译文", "length"),
                     response("", "content_filter"), NS(choices=[]),
                     response("<style id='33'>正文</style"),
                     response('[{"id":0,"output":"半段'),
                     response('[{"id":0,"output":""}]')):
            with self.subTest(item=item), self.assertRaises(IncompleteTranslationError):
                checked_content(item)

    def test_accepts_complete_text_and_structured_output(self):
        for text in ("完整译文", "{v1}", "[1] 引用及说明", '<style id="1">正文</style>',
                     '```json\n[{"id":0,"output":"完整译文"}]\n```'):
            self.assertEqual(text, checked_content(response(text)))

    def test_retries_with_larger_budget_before_returning_to_cache(self):
        engine = translator(response("", "length"), response("半段", "length"), response("完整译文"))
        self.assertEqual("完整译文", complete_translation(engine, "source"))
        self.assertEqual([4096, 8192, 16384], [c.kwargs["max_tokens"]
                         for c in engine.client.chat.completions.create.call_args_list])
        self.assertEqual(3, engine.update_token_count.call_count)

    def test_empty_responses_with_stop_are_retried_and_recorded(self):
        engine, outcomes, para = translator(response(""), response(""), response("")), ParagraphOutcomes(), object()
        outcomes.map_document([(5, [para])])
        with outcomes.attempt(para), self.assertRaises(IncompleteTranslationError):
            fail_closed(outcomes, lambda: complete_translation(engine, "source", plain=True))
        summary = outcomes.summary()
        self.assertEqual((0, 1, {"5": 1}, [5]), (summary["translated"], summary["failed"],
                         summary["failedPages"], summary["untranslatedPages"]))
        self.assertIn("IncompleteTranslationError", summary["reason"])
        self.assertEqual(3, engine.client.chat.completions.create.call_count)

    def test_failed_batch_is_not_recorded_before_paragraph_fallback(self):
        # 2026-10-24: one empty "output" in a JSON batch failed whole pages even
        # though BabelDOC re-translated each paragraph successfully afterwards.
        engine = translator(*[response('[{"id":0,"output":""}]')] * 3, response("译文"))
        outcomes, para = ParagraphOutcomes(), object()
        outcomes.map_document([(1, [para])])
        with self.assertRaises(IncompleteTranslationError):
            complete_translation(engine, "batch", {"request_json_mode": True})
        with outcomes.attempt(para):
            self.assertEqual("译文", fail_closed(
                outcomes, lambda: complete_translation(engine, "paragraph", plain=True)))
        outcomes.record_translated(para)
        self.assertEqual((1, 0), (outcomes.summary()["translated"], outcomes.summary()["failed"]))

    def test_few_failed_paragraphs_are_reported_per_page(self):
        outcomes = ParagraphOutcomes()
        page3, page4 = [object() for _ in range(3)], [object(), object()]
        outcomes.map_document([(3, page3), (4, page4)])
        for para in page3[:2] + page4:
            outcomes.record_translated(para)
        with outcomes.attempt(page3[2]):
            outcomes.record_failure(RuntimeError("Error code: 400 - Unsupported model"))
        summary = outcomes.summary()
        self.assertEqual((4, 1, {"3": 1}, [], 0), (summary["translated"], summary["failed"],
                         summary["failedPages"], summary["untranslatedPages"], summary["unattributed"]))
        self.assertIn("Unsupported model", summary["reason"])

    def test_failures_outside_a_paragraph_are_unattributed(self):
        outcomes = ParagraphOutcomes()
        outcomes.record_failure(RuntimeError("boom"))
        self.assertEqual(1, outcomes.summary()["unattributed"])

    def test_attempt_restores_previous_paragraph(self):
        outcomes, outer, inner = ParagraphOutcomes(), object(), object()
        outcomes.map_document([(2, [outer, inner])])
        with outcomes.attempt(outer):
            with outcomes.attempt(inner):
                pass
            outcomes.record_failure(RuntimeError("late"))
        self.assertEqual({"2": 1}, outcomes.summary()["failedPages"])

    def test_disables_thinking_only_for_mimo_models(self):
        mimo = translator()
        mimo.model, mimo.extra_body = "mimo-v2.6-pro", {}
        disable_thinking(mimo)
        self.assertEqual({"thinking": {"type": "disabled"}}, mimo.extra_body)
        mimo.add_cache_impact_parameters.assert_called_once_with("thinking", "disabled")
        other = translator()
        other.model, other.extra_body = "gpt-4o-mini", {}
        disable_thinking(other)
        self.assertEqual({}, other.extra_body)
        other.add_cache_impact_parameters.assert_not_called()

    def test_preserves_provider_options_and_plain_prompt(self):
        engine = translator(response("译文"), response("译文"))
        complete_translation(engine, "source", {"request_json_mode": True})
        complete_translation(engine, "source", plain=True)
        calls = engine.client.chat.completions.create.call_args_list
        self.assertEqual({"type": "json_object"}, calls[0].kwargs["response_format"])
        self.assertNotIn("response_format", calls[1].kwargs)
        self.assertEqual(engine.extra_body, calls[0].kwargs["extra_body"])
        self.assertEqual(0, calls[0].kwargs["temperature"])

    def test_capability_probe_does_not_make_an_api_call(self):
        engine = translator()
        self.assertIsNone(complete_translation(engine, None))
        engine.client.chat.completions.create.assert_not_called()


if __name__ == "__main__":
    unittest.main()
