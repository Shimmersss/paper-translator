# WORKLOG

## 2026-10-29 — Make translation completeness advisory

- Removed the delivery limit of three kept-source paragraphs / two percent, including the single-range and merged-chunk paths.
- Entirely untranslated pages and paragraphs without a known page now produce an advisory report instead of rejecting generated PDFs. Failed paragraphs retain their source text. Empty or truncated responses still use the existing retry and fallback path.
- Preserved paragraph counts, page warnings, chunk resumption, and failure handling for process errors, timeouts, and missing PDF output. Unknown page keys no longer discard the runner's entire paragraph report.
- Updated English and Chinese user documentation to describe best-effort delivery.
- Review: checked both delivery paths, runner completion events, cached-chunk aggregation, unknown-page statistics, and consistency of user-facing warnings. No project entry points or long-term agent rules changed.
- Validation: `cd backend && mvn -B -ntp test` — 80 tests passed. `cd backend/scripts && uv run --python 3.12 --with babeldoc==0.6.2 --with bitstring==4.4.0 python -m unittest test_babeldoc_runner test_translation_completion test_pdf_ocr` — 39 tests passed, including actual Tesseract OCR. `git diff --check` passed.
- Cleanup: removed rebuildable Maven outputs and Python bytecode after preserving test reports outside the checkout; existing shared dependency environments were reused.
