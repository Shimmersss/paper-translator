"""Reject incomplete model responses before BabelDOC can cache or typeset them."""

import json
import logging
import re

LOG = logging.getLogger(__name__)
COMPLETION_CACHE_VERSION = "complete-v1"


class IncompleteTranslationError(RuntimeError):
    pass


def checked_content(response):
    if not response.choices:
        raise IncompleteTranslationError("模型没有返回翻译候选")
    choice = response.choices[0]
    if choice.finish_reason != "stop":
        raise IncompleteTranslationError(
            f"翻译输出未完整结束: finish_reason={choice.finish_reason}"
        )
    content = choice.message.content
    if not isinstance(content, str) or not content.strip():
        raise IncompleteTranslationError("模型返回了空译文")
    content = content.strip()
    # A provider can report stop even when it has cut the response mid-tag.
    opened = len(re.findall(r"<style\b[^>]*>", content))
    closed = len(re.findall(r"</style\s*>", content))
    if opened != closed or re.search(r"</?style\b[^>]*$", content):
        raise IncompleteTranslationError("译文样式标记被截断")
    structured = re.sub(r"^```(?:json)?\s*|\s*```$", "", content).strip()
    if re.match(r'^\s*(?:\[\s*\{|\[\s*\]|\{\s*")', structured):
        try:
            parsed = json.loads(structured)
        except ValueError as exc:
            raise IncompleteTranslationError("结构化译文被截断或格式无效") from exc
        if isinstance(parsed, list):
            if not parsed or any(
                isinstance(item, dict) and "output" in item
                and (not isinstance(item["output"], str) or not item["output"].strip())
                for item in parsed
            ):
                raise IncompleteTranslationError("结构化译文包含空段落")
    return content


def fail_closed(translator, call):
    """Mark the document incomplete when a paragraph finally fails.

    BabelDOC logs a failed paragraph and carries on, so without this a rejected
    model name, an invalid key or an exhausted rate-limit retry would still end
    in "finish" with the original text left in the PDF. Callers wrap the whole
    retry loop, so a rate limit that later succeeds does not trip it.
    """
    try:
        return call()
    except Exception:
        translator._paper_translator_incomplete_translation = True
        raise


def complete_translation(translator, text, rate_limit_params=None, *, plain=False):
    """Retry incomplete responses with bounded, increasing output budgets.

    Network/provider exceptions keep the upstream retry policy. No response is
    returned to BabelDOC's cache until completeness checks have passed.
    """
    if text is None:
        return None
    options = dict(translator.options) if translator.send_temperature else {}
    if not plain and translator.enable_json_mode_if_requested and (rate_limit_params or {}).get(
        "request_json_mode", False
    ):
        options["response_format"] = {"type": "json_object"}
    headers = {}
    if not plain and translator.send_dashscope_header:
        headers["X-DashScope-DataInspection"] = '{"input": "disable", "output": "disable"}'
    last_error = None
    for budget in (4096, 8192, 16384):
        response = translator.client.chat.completions.create(
            model=translator.model,
            **options,
            max_tokens=budget,
            messages=translator.prompt(text) if plain else [{"role": "user", "content": text}],
            extra_headers=headers,
            extra_body=translator.extra_body,
        )
        translator.update_token_count(response)
        try:
            return checked_content(response)
        except IncompleteTranslationError as exc:
            last_error = exc
            # Do not put document text or provider credentials in logs.
            LOG.warning("拒绝不完整译文: budget=%s, reason=%s", budget, exc)
    translator._paper_translator_incomplete_translation = True
    raise IncompleteTranslationError(f"翻译完整性检查失败，重试后仍无完整译文: {last_error}")
