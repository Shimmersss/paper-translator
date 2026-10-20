# Paper Translator

[中文](README.zh-CN.md)

A self-hosted web app that translates academic PDFs into Simplified Chinese while keeping the original layout. Upload a paper, pick a page range, and get a translated-only PDF and a side-by-side bilingual PDF you can preview in the browser and download. Images (posters, figures, screenshots) can be translated too, using a vision model.

It wraps [BabelDOC](https://github.com/funstory-ai/BabelDOC) with what a long-running service needs: a persistent task queue, chunked processing that resumes after failures, memory protection on small servers, OCR for scanned pages, and checks that refuse to deliver a PDF with missing translations. It was extracted from a personal site where it has been translating real papers on a 2 vCPU / 4 GB server; accounts and billing were removed for this release.

The interface is in Chinese.

## Features

- **Layout-preserving PDF translation.** BabelDOC rebuilds each page with the translation in place. You get `translated.pdf` and `bilingual.pdf`, a page-by-page preview, and a plain-text export.
- **Long papers.** Pages are translated in small chunks that are cached on disk. If a run fails or the server restarts, finished chunks are reused and the job continues where it stopped. Narrowing the page range later does not throw away completed pages.
- **Memory protection.** On Linux the service watches cgroup memory, available RAM and swap. Under pressure it stops BabelDOC, waits for memory to recover and retries at lower concurrency, falling back to one page at a time if needed.
- **Complete or nothing.** Every paragraph's translation is checked. A truncated or empty answer is retried with a larger output budget, and a PDF with untranslated paragraphs is never delivered.
- **Scanned pages.** Pages without a text layer go through Tesseract OCR first. Logos, covers and figures are kept, and the hidden text is sized from the actual glyphs so the translation is not tiny.
- **References and appendices.** The bibliography is left in its original language, while appendices, supplementary material and acknowledgements that follow it are still translated.
- **Image translation.** A vision model reads the text in a PNG/JPG/GIF/BMP, and the translation is drawn back over the image. You can download it as a translated-only or bilingual PNG or PDF.
- **Queue and progress.** One worker processes tasks in order, with live progress over Server-Sent Events. Recent tasks survive restarts and can be reopened from the history list.

## Requirements

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 17 or 21 | The build and tests use Spring Boot 3.2. |
| Maven | 3.9+ | |
| Node.js | 18.17+ | Only needed to build the UI. |
| [uv](https://docs.astral.sh/uv/) | recent | Runs the pinned BabelDOC and fetches a suitable Python automatically. |
| Tesseract + `eng` data | 4.1+ | Optional; only for PDFs whose pages have no text layer. `brew install tesseract` / `apt-get install tesseract-ocr tesseract-ocr-eng` |

You also need an API key for an OpenAI-compatible chat model (OpenAI, DeepSeek, Qwen, a local vLLM/Ollama server, …). Image translation needs a vision-capable model, either OpenAI-compatible or Anthropic.

The first PDF job downloads BabelDOC, its Python dependencies and its layout model, which takes a few minutes. Later jobs start immediately.

## Getting started

```bash
cp .env.example .env
```

Edit `.env` and set at least `BABELDOC_OPENAI_API_KEY`, plus `BABELDOC_OPENAI_BASE_URL` and `BABELDOC_OPENAI_MODEL` if you are not using OpenAI. Set `LLM_API_KEY` too if you want image translation.

```bash
cd frontend && npm ci && npm run build
```

```bash
cd backend && mvn package -DskipTests
```

```bash
cd backend && java -jar target/paper-translator.jar
```

Open <http://127.0.0.1:8080>. Start the backend from inside `backend/`: it resolves `../.env`, `scripts/` and `../frontend/dist/` relative to that directory.

### Development

Run the backend with `mvn spring-boot:run` in `backend/`, and the UI with hot reload:

```bash
cd frontend && npm run dev
```

Vite serves on <http://localhost:5173> and proxies `/api` to `127.0.0.1:8080`.

### Tests

```bash
cd backend && mvn test
```

```bash
cd backend/scripts && uv run --with babeldoc==0.6.2 --with bitstring==4.4.0 python -m unittest test_babeldoc_runner test_pdf_ocr test_translation_completion
```

The OCR tests run real Tesseract, so they need `tesseract` and its `eng` data installed.

## Configuration

All settings are environment variables (or `KEY=VALUE` lines in `.env`). The full list with defaults is in [`application.yml`](backend/src/main/resources/application.yml).

| Variable | Default | Meaning |
| --- | --- | --- |
| `BABELDOC_OPENAI_BASE_URL` | `https://api.openai.com/v1` | Chat completions endpoint used for PDF translation |
| `BABELDOC_OPENAI_API_KEY` | — | Required for PDF translation |
| `BABELDOC_OPENAI_MODEL` | `gpt-4o-mini` | Model name for PDF translation |
| `LLM_API_URL` / `LLM_API_KEY` / `LLM_MODEL` | OpenAI / — / `gpt-4o-mini` | Vision model for image translation |
| `LLM_VISION_MODEL` | — | Optional separate model for images |
| `LLM_PROTOCOL` | `auto` | `openai`, `claude`, or `auto` (Anthropic when the URL contains `anthropic`) |
| `SERVER_ADDRESS` / `SERVER_PORT` | `127.0.0.1` / `8080` | Listen address |
| `TRANSLATION_STORAGE_DIR` | `./data/translation-tasks` | Uploaded files, results and task state |
| `TRANSLATION_MAX_HISTORY` | `20` | Finished tasks to keep; older ones are deleted |
| `TRANSLATION_MAX_QPS` / `TRANSLATION_STABLE_QPS` | `4` / `2` | Concurrent model requests in fast and stable mode |
| `BABELDOC_MAX_PAGES_PER_CHUNK` | `1` | Pages per BabelDOC run. Larger chunks are faster but use more memory. |
| `BABELDOC_RESOURCE_*` | tuned for 4 GB | Memory guard thresholds (Linux only) |
| `BABELDOC_COMMAND` | `uv run --with babeldoc==0.6.2 --with bitstring==4.4.0 python` | How the Python runner is started |

## Security

There are no user accounts: anyone who can reach the port can upload files and spend your model quota. The server therefore listens on `127.0.0.1` by default. To expose it, put it behind a reverse proxy that requires authentication (HTTP basic auth, an SSO proxy, a VPN) before setting `SERVER_ADDRESS=0.0.0.0`.

Uploaded files and results are stored under `TRANSLATION_STORAGE_DIR` and are sent only to the model endpoints you configure.

## How it works

```
browser ──/api/translate──▶ Spring Boot ──▶ task queue (1 worker, state in task.json)
                                │
             PDF ──────────────┼──▶ uv run … babeldoc_runner.py  (per chunk, watched for memory)
                                │       ├─ pdf_ocr.py            Tesseract for textless pages
                                │       ├─ reference_layout.py   keep the bibliography untranslated
                                │       └─ translation_completion.py  reject incomplete answers
                                │
             image ────────────┴──▶ vision model ──▶ redraw text ──▶ PNG + PDF
```

- [`backend/src/main/java/com/papertranslator/translate/`](backend/src/main/java/com/papertranslator/translate/) holds the HTTP API, queue, chunking and resume logic, memory guard and image translation.
- [`backend/scripts/`](backend/scripts/) holds the BabelDOC runner and its patches.
- [`frontend/src/components/TranslateWorkbench.vue`](frontend/src/components/TranslateWorkbench.vue) is the whole UI.

## License

[AGPL-3.0](LICENSE). BabelDOC and PyMuPDF are AGPL-3.0 as well; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). If you run a modified version as a network service, the AGPL requires you to offer its source to its users.
