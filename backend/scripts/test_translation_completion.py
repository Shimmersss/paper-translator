import unittest
from types import SimpleNamespace as NS
from unittest.mock import Mock

from translation_completion import (
    IncompleteTranslationError, checked_content, complete_translation,
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
        self.assertFalse(getattr(engine, "_paper_translator_incomplete_translation", False))
        self.assertEqual(3, engine.update_token_count.call_count)

    def test_empty_responses_with_stop_are_retried_and_fail_closed(self):
        engine = translator(response(""), response(""), response(""))
        with self.assertRaises(IncompleteTranslationError):
            complete_translation(engine, "source")
        self.assertTrue(engine._paper_translator_incomplete_translation)
        self.assertEqual(3, engine.client.chat.completions.create.call_count)

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
