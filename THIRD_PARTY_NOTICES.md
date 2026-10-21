# Third-party notices

Paper Translator is licensed under the GNU Affero General Public License v3.0
(see [LICENSE](LICENSE)). It is built on the following projects; each keeps its
own license.

## Runtime (invoked as a separate process)

| Project | Version | License | Used for |
| --- | --- | --- | --- |
| [BabelDOC](https://github.com/funstory-ai/BabelDOC) | 0.6.2 | AGPL-3.0 | Layout-preserving PDF translation |
| [PyMuPDF](https://pymupdf.readthedocs.io/) | via BabelDOC | AGPL-3.0 (or commercial) | PDF parsing, rendering and OCR page preparation |
| [bitstring](https://github.com/scott-griffiths/bitstring) | 4.4.0 | MIT | Pinned for BabelDOC compatibility |
| [Tesseract OCR](https://github.com/tesseract-ocr/tesseract) + `eng` data | 4.1.1+ | Apache-2.0 | OCR for pages without a text layer (optional) |
| [uv](https://github.com/astral-sh/uv) | — | MIT / Apache-2.0 | Provisions the pinned Python environment |

## Installed by the `paper-translator` CLI when missing

| Project | License | Notes |
| --- | --- | --- |
| [Eclipse Temurin](https://adoptium.net/) JDK 21 | GPL-2.0 with Classpath Exception | Via Homebrew / winget / apt / dnf, or downloaded from the Adoptium API and checked against its SHA-256 |
| [uv](https://github.com/astral-sh/uv) | MIT / Apache-2.0 | Installed by `install.sh` / `install.ps1` from astral.sh |
| [Hatchling](https://github.com/pypa/hatch) | MIT | Build backend for the CLI package (build time only) |

## Bundled data

| Item | Location | License | Notes |
| --- | --- | --- | --- |
| [tiktoken](https://github.com/openai/tiktoken) `o200k_base` vocabulary | `backend/scripts/tiktoken-cache/` | MIT | Shipped and SHA-256 checked so jobs never download it at runtime |

## Backend (Java)

| Project | Version | License |
| --- | --- | --- |
| [Spring Boot](https://spring.io/projects/spring-boot) | 3.2.5 | Apache-2.0 |
| [Apache PDFBox](https://pdfbox.apache.org/) | 3.0.3 | Apache-2.0 |
| [Jackson](https://github.com/FasterXML/jackson) | via Spring Boot | Apache-2.0 |

## Frontend

| Project | Version | License |
| --- | --- | --- |
| [Vue](https://vuejs.org/) | 3.5.30 | MIT |
| [Naive UI](https://www.naiveui.com/) | 2.44.1 | MIT |
| [PDF.js](https://mozilla.github.io/pdf.js/) (`pdfjs-dist`) | 4.10.38 | Apache-2.0 |
| [xicons / Ionicons 5](https://github.com/07akioni/xicons) (`@vicons/ionicons5`) | 0.12.0 | MIT |
| [Vite](https://vitejs.dev/) | 5.4.21 | MIT |

## External services

Translation text and images are sent to the model endpoints you configure
(on the settings page or as `BABELDOC_OPENAI_BASE_URL`, `LLM_API_URL`). Their
terms of service and data policies apply; documents are sent nowhere else.

The installer and CLI download software from GitHub (releases of this
repository), astral.sh (uv), api.adoptium.net (a JDK, only when none is
installed) and, on first use, the Python package index and BabelDOC's model
hosts. No usage data is collected.
