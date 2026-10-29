"""Reject incomplete model responses before BabelDOC can cache or typeset them."""

import json
import logging
import re
import threading
from contextlib import contextmanager

LOG = logging.getLogger(__name__)
COMPLETION_CACHE_VERSION = "complete-v1"
# MiMo v2.6 models reason by default. Runaway reasoning can use the whole output
# budget on every retry, and translation does not need it.
THINKING_DISABLED_MODEL_PREFIXES = ("mimo-",)


def disable_thinking(translator):
    """Turn off provider reasoning for models known to accept the switch."""
    if str(translator.model).lower().startswith(THINKING_DISABLED_MODEL_PREFIXES):
        translator.extra_body["thinking"] = {"type": "disabled"}
        translator.add_cache_impact_parameters("thinking", "disabled")


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


class ParagraphOutcomes:
    """Which paragraphs reached the PDF translated and which kept their source text.

    BabelDOC logs a failed paragraph and carries on with the original text, so
    the runner reports these counts as advisory warnings. Failed paragraphs
    do not block PDF delivery. Only paragraph identities and page numbers are kept.
    """

    def __init__(self):
        self._local = threading.local()
        self._lock = threading.Lock()
        self._page_of = {}
        self._translated = {}
        self._failed = {}
        self.unattributed = 0
        self.last_reason = ""

    def map_document(self, pages):
        """pages: iterable of (1-based page number, paragraphs on that page)."""
        with self._lock:
            for page_number, paragraphs in pages:
                for paragraph in paragraphs:
                    self._page_of[id(paragraph)] = page_number

    @contextmanager
    def attempt(self, paragraph):
        """Attribute model failures inside this block to one paragraph."""
        previous = getattr(self._local, "paragraph", None)
        self._local.paragraph = id(paragraph)
        try:
            yield
        finally:
            self._local.paragraph = previous

    def record_translated(self, paragraph):
        with self._lock:
            key = id(paragraph)
            self._translated[key] = self._page_of.get(key)
            self._failed.pop(key, None)

    def record_failure(self, exc):
        key = getattr(self._local, "paragraph", None)
        with self._lock:
            # Exception text from the provider or our checks; never document text.
            self.last_reason = f"{type(exc).__name__}: {exc}"[:300]
            if key is None:
                self.unattributed += 1
            elif key not in self._translated:
                self._failed[key] = self._page_of.get(key)

    def summary(self):
        with self._lock:
            failed_pages, translated_pages = {}, set(self._translated.values())
            for page in self._failed.values():
                failed_pages[page] = failed_pages.get(page, 0) + 1
            return {
                "translated": len(self._translated),
                "failed": len(self._failed),
                "failedPages": {str(page): count for page, count in sorted(
                    failed_pages.items(), key=lambda item: (item[0] is None, item[0] or 0))},
                "untranslatedPages": sorted(
                    page for page in failed_pages if page is not None and page not in translated_pages),
                "unattributed": self.unattributed + failed_pages.get(None, 0),
                "reason": self.last_reason,
            }

    def report(self):
        """Report unsuccessful paragraphs without rejecting a generated PDF."""
        summary = self.summary()
        if summary["failed"] or summary["unattributed"]:
            LOG.warning(
                "部分段落翻译失败，已保留原文并继续交付: failed=%s, pages=%s, unattributed=%s",
                summary["failed"], summary["failedPages"], summary["unattributed"],
            )
        return summary


def fail_closed(outcomes, call):
    """Record a paragraph whose final translation attempt failed, then re-raise.

    Callers wrap the whole retry loop, so a rate limit that later succeeds is not
    recorded. BabelDOC catches the re-raised error and keeps the source text.
    """
    try:
        return call()
    except Exception as exc:
        outcomes.record_failure(exc)
        raise


def complete_translation(translator, text, rate_limit_params=None, *, plain=False):
    """Retry incomplete responses with bounded, increasing output budgets.

    Network/provider exceptions keep the upstream retry policy. No response is
    returned to BabelDOC's cache until completeness checks have passed. This
    does not record failures: a failed JSON batch falls back to per-paragraph
    translation, so only the final paragraph path is wrapped in fail_closed.
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
    raise IncompleteTranslationError(f"翻译完整性检查失败，重试后仍无完整译文: {last_error}")
