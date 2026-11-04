# WORKLOG

## 2026-10-29 — Make translation completeness advisory

- Removed the delivery limit of three kept-source paragraphs / two percent, including the single-range and merged-chunk paths.
- Entirely untranslated pages and paragraphs without a known page now produce an advisory report instead of rejecting generated PDFs. Failed paragraphs retain their source text. Empty or truncated responses still use the existing retry and fallback path.
- Preserved paragraph counts, page warnings, chunk resumption, and failure handling for process errors, timeouts, and missing PDF output. Unknown page keys no longer discard the runner's entire paragraph report.
- Updated English and Chinese user documentation to describe best-effort delivery.
- Review: checked both delivery paths, runner completion events, cached-chunk aggregation, unknown-page statistics, and consistency of user-facing warnings. No project entry points or long-term agent rules changed.
- Validation: `cd backend && mvn -B -ntp test` — 80 tests passed. `cd backend/scripts && uv run --python 3.12 --with babeldoc==0.6.2 --with bitstring==4.4.0 python -m unittest test_babeldoc_runner test_translation_completion test_pdf_ocr` — 39 tests passed, including actual Tesseract OCR. `git diff --check` passed.
- Cleanup: removed rebuildable Maven outputs and Python bytecode after preserving test reports outside the checkout; existing shared dependency environments were reused.

## 2026-11-04 — Disable default thinking for DeepSeek Flash translation

- Added `deepseek-flash` to the existing provider thinking-switch adapter. Translation requests now explicitly send `thinking.type=disabled`, keeping the output budget for translated text and distinguishing the setting in the translation cache. Other providers retain their existing behavior.
- Regression coverage checks that MiMo and DeepSeek requests carry the disabled-thinking flag and cache parameter, while unrelated models receive no provider-specific switch. Existing completeness, fallback, and advisory-delivery behavior is preserved.
- Validation: `python3 -m unittest discover -s backend/scripts -p test_translation_completion.py`; `git diff --check`. Reviewed the request path and cache integration. No project entry points or long-term rules changed.
- This addresses default reasoning overhead; it does not prevent provider-side overload or address rotated-table PDF layout.

## 2026-11-04 — Repair PR installer CI revision selection

- Investigated the failing macOS pull-request run: the mirror installer passed GitHub's temporary merge SHA to uv, which could not resolve that revision from the repository. The push run for the same patch passed all platforms; translation tests were not the failing step.
- Centralized `PAPER_TRANSLATOR_REF` in the CI job environment: PR runs use the published head SHA, and push runs use `github.sha`. Standard and mirror installers now share that selection on shell and PowerShell paths; the clean shell environment explicitly forwards it.
- Validation: workflow diff review, actionlint, and the new GitHub Actions run. Application translation behavior is unchanged by this follow-up.
