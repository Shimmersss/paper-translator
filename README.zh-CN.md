# 论文翻译 · Paper Translator

[English](README.md)

一个可自行部署的论文翻译网页工具：上传英文 PDF，选择页面范围，得到保留原排版的纯中文 PDF 和逐页对照的双语 PDF，可在浏览器里预览后下载。也能借助视觉模型翻译海报、插图、截图等图片。

它以 [BabelDOC](https://github.com/funstory-ai/BabelDOC) 为翻译引擎，补上了长期运行的服务所需的部分：持久化任务队列、分片翻译与失败续跑、小内存服务器的内存保护、扫描页 OCR，以及“译文不完整就不交付”的检查。项目从个人网站中拆分出来，此前一直在 2 核 / 4 GB 的服务器上翻译真实论文；开源版去掉了账号与计费。

## 功能

- **保留版式的 PDF 翻译**：BabelDOC 在原位置重建译文，输出纯中文版和双语对照版，支持逐页预览，也可以导出纯文本。
- **长论文**：按小分片翻译并缓存在磁盘上。失败或服务重启后，已完成的分片直接复用，从中断处继续；之后缩小页面范围重试，也不会丢弃已经翻好的页面。
- **内存保护**：在 Linux 上监测 cgroup 内存、可用内存和 Swap。压力过高时先停止 BabelDOC，等内存恢复后降低并发重试，必要时逐页翻译。
- **不交付残缺译文**：逐段检查译文。空结果或被截断的输出会加大输出额度重试，仍有段落未译完时不会生成 PDF。
- **扫描页**：没有文字层的页面先用 Tesseract OCR 识别。出版社标志、封面和插图保持原样，隐藏文字的字号按原图笔画实测，避免译文字号过小。
- **参考文献与附录**：参考文献保持原文；位于其后的附录、补充材料和致谢仍会翻译。
- **图片翻译**：视觉模型识别 PNG/JPG/GIF/BMP 中的文字，再把译文绘回图片。模型给出的文字框只是近似位置，覆盖前会先对齐到原文笔画；遮盖颜色跟随背景（含渐变和彩色标签），原文不会露出。可下载纯中文或双语的 PNG / PDF。
- **队列与进度**：单 worker 依次处理，通过 SSE 实时推送进度。最近的任务在重启后保留，可从历史列表重新打开。

## 安装

### 一条命令安装（macOS、Linux、Windows）

macOS / Linux：

```bash
curl -fsSL https://raw.githubusercontent.com/Shimmersss/paper-translator/main/install.sh | sh
```

Windows（PowerShell）：

```powershell
irm https://raw.githubusercontent.com/Shimmersss/paper-translator/main/install.ps1 | iex
```

安装脚本会装好 [uv](https://docs.astral.sh/uv/)，从最新的 GitHub Release 安装 `paper-translator` 命令，然后运行 `paper-translator setup`。setup 会：

- 下载发布包，并用 `SHA256SUMS.txt` 校验；
- 使用已有的 JDK 17+，没有时安装 JDK 21（macOS 用 Homebrew，Windows 用 winget，Linux 在可用 sudo 时用 apt/dnf，否则把便携版 Temurin 下载到安装目录，不需要管理员权限）；
- 询问是否安装 Tesseract（扫描版 PDF 需要）；
- 询问模型接口（可以跳过，之后在设置页填写）；
- 提前下载 BabelDOC 和版面模型，首次翻译不用再等。

不需要 Node.js 和 Maven。所有文件放在 `~/.paper-translator`（Windows 为 `%LOCALAPPDATA%\paper-translator`），可用 `PAPER_TRANSLATOR_HOME` 改到别处。

```bash
paper-translator start
```

服务在后台启动，并自动打开 <http://127.0.0.1:8080>。

### 命令

| 命令 | 作用 |
| --- | --- |
| `paper-translator setup` | 安装依赖、配置并预下载模型。`--from-source` 改为构建源码目录；`--archive 文件` 离线安装 |
| `paper-translator start` / `stop` / `status` | 后台启动、停止、查看状态（`start --port 9000`、`--no-browser`） |
| `paper-translator update` | 安装最新版本，保留 `.env`、`settings.json` 和数据 |
| `paper-translator doctor` | 逐项检查 JDK、uv、Tesseract、端口和模型连接，并给出修复命令 |
| `paper-translator logs` | 查看后端日志（`-f` 持续跟踪） |
| `paper-translator autostart enable` / `disable` | 登录后自动启动（launchd、计划任务或 systemd 用户服务） |
| `paper-translator uninstall` | 删除程序，并询问是否保留数据和配置 |

另需一个兼容 OpenAI 接口的对话模型 API Key（OpenAI、DeepSeek、通义千问、本地 vLLM/Ollama 等）。图片翻译需要支持图像输入的模型，可以是 OpenAI 兼容接口或 Anthropic。

## 设置页

点页面右上角的“设置”：

- **模型**：PDF 翻译和图片翻译各自的接口地址、API Key 和模型名，都带“测试连接”。Key 只写不读：页面只显示末 4 位，输入框留空表示不修改。
- **性能**：自动、最高性能或手动。推荐值按检测到的核数和内存计算：

  | 内存 | 自动：每批页数 / 并发 | 最高性能：每批页数 / 并发 |
  | --- | --- | --- |
  | < 6 GB | 1 / 2 | 1 / 4 |
  | 6–12 GB | 3 / 4 | 5 / 6 |
  | 12–24 GB | 5 / 6 | 10 / 8 |
  | ≥ 24 GB | 10 / 8 | 20 / 12 |

  并发不超过核数的两倍。BabelDOC 始终只运行一个进程，内存保护和自动降级在所有档位都保留。“服务商并发上限”用于限流严格的服务商；遇到 HTTP 429 时，进度文字里会出现提示。
- **存储**：保留多少个已结束的任务。

修改后对下一个任务生效，不需要重启。设置保存在任务目录旁的 `settings.json`（仅本人可读写），优先于 `.env`，`.env` 又优先于内置默认值。只在 `.env` 里设置性能参数的老用户，在设置页选择档位之前行为不变。

设置页只接受来自运行服务的这台电脑的修改；服务监听 `127.0.0.1` 以外的地址时，设置页变为只读。

## 手动安装

用于开发，或不想使用安装脚本时。

| 工具 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17 或 21 | 构建与测试基于 Spring Boot 3.2 |
| Maven | 3.9+ | |
| Node.js | 18.17+ | 只在构建前端时需要 |
| [uv](https://docs.astral.sh/uv/) | 较新版本 | 运行锁定版本的 BabelDOC，并自动获取合适的 Python |
| Tesseract + `eng` 数据 | 4.1+ | 可选，仅用于没有文字层的 PDF 页面。`brew install tesseract` / `apt-get install tesseract-ocr tesseract-ocr-eng` |

`paper-translator setup --from-source` 会检查这些依赖并替你构建源码目录。也可以手动操作：

```bash
cp .env.example .env
```

编辑 `.env`（也可以不填，改用设置页）。

```bash
cd frontend && npm ci && npm run build
```

```bash
cd backend && mvn package -DskipTests
```

```bash
cd backend && java -jar target/paper-translator.jar
```

打开 <http://127.0.0.1:8080>。请在 `backend/` 目录下启动后端：`../.env`、`scripts/` 和 `../frontend/dist/` 都按这个目录解析。

第一次翻译 PDF 时会下载 BabelDOC、它的 Python 依赖和版面模型，需要几分钟（setup 已预下载时除外）。

### 开发

在 `backend/` 下运行 `mvn spring-boot:run`，前端用热更新：

```bash
cd frontend && npm run dev
```

Vite 运行在 <http://localhost:5173>，并把 `/api` 代理到 `127.0.0.1:8080`。

### 测试

```bash
cd backend && mvn test
```

```bash
cd backend/scripts && uv run --with babeldoc==0.6.2 --with bitstring==4.4.0 python -m unittest test_babeldoc_runner test_pdf_ocr test_translation_completion
```

```bash
cd cli && uv run --no-project python -m unittest discover -s tests
```

OCR 测试会调用真实的 Tesseract，需要先安装 `tesseract` 和 `eng` 识别数据。每次推送和 PR，GitHub Actions 都会在 Ubuntu、macOS 和 Windows 上运行以上测试，并对 CLI 做一次安装、启动、停止的冒烟测试。

### 发布新版本

1. 在 `backend/pom.xml`、`frontend/package.json`、`cli/pyproject.toml` 和 `cli/paper_translator_cli/__init__.py` 中设置相同的版本号。
2. 打标签并推送：`git tag v0.2.0 && git push origin v0.2.0`。
3. 发布工作流会核对版本号、运行测试，并发布 `paper-translator-<版本>.tar.gz`、`.zip`、CLI wheel 和 `SHA256SUMS.txt`。安装脚本和 `paper-translator update` 从这里获取新版本。

## 配置

环境变量（或 `.env` 里的 `KEY=VALUE`）提供默认值，设置页保存的值会覆盖它们。完整列表见 [`application.yml`](backend/src/main/resources/application.yml)。

| 变量 | 默认值 | 含义 |
| --- | --- | --- |
| `BABELDOC_OPENAI_BASE_URL` | `https://api.openai.com/v1` | PDF 翻译使用的对话接口地址 |
| `BABELDOC_OPENAI_API_KEY` | — | PDF 翻译必填 |
| `BABELDOC_OPENAI_MODEL` | `gpt-4o-mini` | PDF 翻译模型 |
| `LLM_API_URL` / `LLM_API_KEY` / `LLM_MODEL` | OpenAI / — / `gpt-4o-mini` | 图片翻译使用的视觉模型 |
| `LLM_VISION_MODEL` | — | 可选，单独指定图片翻译模型 |
| `LLM_PROTOCOL` | `auto` | `openai`、`claude` 或 `auto`（地址含 `anthropic` 时按 Anthropic 处理） |
| `SERVER_ADDRESS` / `SERVER_PORT` | `127.0.0.1` / `8080` | 监听地址 |
| `TRANSLATION_STORAGE_DIR` | `./data/translation-tasks` | 上传文件、结果和任务状态；`settings.json` 放在它旁边 |
| `SETTINGS_FILE` | — | 单独指定 `settings.json` 的路径 |
| `TRANSLATION_MAX_HISTORY` | `20` | 保留的已结束任务数，超出后删除最旧的 |
| `TRANSLATION_MAX_QPS` / `TRANSLATION_STABLE_QPS` / `BABELDOC_MAX_PAGES_PER_CHUNK` / `BABELDOC_RESOURCE_*` | — | 不用设置页、直接固定性能参数（按 4 GB 服务器调校）。只要设置了其中任一项，在设置页选择档位之前不启用档位 |
| `BABELDOC_COMMAND` | `uv run --with babeldoc==0.6.2 --with bitstring==4.4.0 python` | 启动 Python 翻译脚本的命令；路径含空格时请加引号 |

## 安全

本项目没有账号体系：能访问端口的人都可以上传文件、消耗你的模型额度。因此默认只监听 `127.0.0.1`。如需对外开放，请先在前面加一层需要认证的反向代理（HTTP Basic Auth、SSO 代理、VPN 等），再设置 `SERVER_ADDRESS=0.0.0.0`。

上传的文件和结果保存在 `TRANSLATION_STORAGE_DIR`，只会发送给你配置的模型接口。

## 工作原理

```
浏览器 ──/api/translate──▶ Spring Boot ──▶ 任务队列（单 worker，状态写入 task.json）
                                │
             PDF ──────────────┼──▶ uv run … babeldoc_runner.py（逐分片运行并监测内存）
                                │       ├─ pdf_ocr.py            无文字层页面走 Tesseract
                                │       ├─ reference_layout.py   参考文献保持原文
                                │       └─ translation_completion.py  拒收不完整译文
                                │
             图片 ─────────────┴──▶ 视觉模型 ──▶ 绘回译文 ──▶ PNG + PDF
```

- [`backend/src/main/java/com/papertranslator/translate/`](backend/src/main/java/com/papertranslator/translate/)：HTTP 接口、队列、分片续跑、内存保护和图片翻译。
- [`backend/scripts/`](backend/scripts/)：BabelDOC 启动脚本及补丁。
- [`backend/src/main/java/com/papertranslator/settings/`](backend/src/main/java/com/papertranslator/settings/)：合并设置页、`.env` 与默认值，计算性能档位。
- [`frontend/src/components/`](frontend/src/components/)：界面，包括翻译页和设置抽屉。
- [`cli/`](cli/)：`paper-translator` 命令；[`install.sh`](install.sh) 与 [`install.ps1`](install.ps1) 负责引导安装。

## 许可

[AGPL-3.0](LICENSE)。BabelDOC 与 PyMuPDF 同为 AGPL-3.0，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。如果把修改后的版本作为网络服务提供，AGPL 要求你向使用者提供对应源码。
