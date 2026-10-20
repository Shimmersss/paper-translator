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
- **图片翻译**：视觉模型识别 PNG/JPG/GIF/BMP 中的文字，再把译文绘回图片，可下载纯中文或双语的 PNG / PDF。
- **队列与进度**：单 worker 依次处理，通过 SSE 实时推送进度。最近的任务在重启后保留，可从历史列表重新打开。

## 环境要求

| 工具 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17 或 21 | 构建与测试基于 Spring Boot 3.2 |
| Maven | 3.9+ | |
| Node.js | 18.17+ | 只在构建前端时需要 |
| [uv](https://docs.astral.sh/uv/) | 较新版本 | 运行锁定版本的 BabelDOC，并自动获取合适的 Python |
| Tesseract + `eng` 数据 | 4.1+ | 可选，仅用于没有文字层的 PDF 页面。`brew install tesseract` / `apt-get install tesseract-ocr tesseract-ocr-eng` |

另需一个兼容 OpenAI 接口的对话模型 API Key（OpenAI、DeepSeek、通义千问、本地 vLLM/Ollama 等）。图片翻译需要支持图像输入的模型，可以是 OpenAI 兼容接口或 Anthropic。

第一次翻译 PDF 时会下载 BabelDOC、它的 Python 依赖和版面模型，需要几分钟；之后的任务会直接开始。

## 快速开始

```bash
cp .env.example .env
```

编辑 `.env`，至少填写 `BABELDOC_OPENAI_API_KEY`；如果不用 OpenAI，再改 `BABELDOC_OPENAI_BASE_URL` 和 `BABELDOC_OPENAI_MODEL`。需要图片翻译时同时填写 `LLM_API_KEY`。

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

OCR 测试会调用真实的 Tesseract，需要先安装 `tesseract` 和 `eng` 识别数据。

## 配置

所有配置都是环境变量，也可以写成 `.env` 里的 `KEY=VALUE`。完整列表和默认值见 [`application.yml`](backend/src/main/resources/application.yml)。

| 变量 | 默认值 | 含义 |
| --- | --- | --- |
| `BABELDOC_OPENAI_BASE_URL` | `https://api.openai.com/v1` | PDF 翻译使用的对话接口地址 |
| `BABELDOC_OPENAI_API_KEY` | — | PDF 翻译必填 |
| `BABELDOC_OPENAI_MODEL` | `gpt-4o-mini` | PDF 翻译模型 |
| `LLM_API_URL` / `LLM_API_KEY` / `LLM_MODEL` | OpenAI / — / `gpt-4o-mini` | 图片翻译使用的视觉模型 |
| `LLM_VISION_MODEL` | — | 可选，单独指定图片翻译模型 |
| `LLM_PROTOCOL` | `auto` | `openai`、`claude` 或 `auto`（地址含 `anthropic` 时按 Anthropic 处理） |
| `SERVER_ADDRESS` / `SERVER_PORT` | `127.0.0.1` / `8080` | 监听地址 |
| `TRANSLATION_STORAGE_DIR` | `./data/translation-tasks` | 上传文件、结果和任务状态 |
| `TRANSLATION_MAX_HISTORY` | `20` | 保留的已结束任务数，超出后删除最旧的 |
| `TRANSLATION_MAX_QPS` / `TRANSLATION_STABLE_QPS` | `4` / `2` | 加速模式 / 稳定模式的模型并发数 |
| `BABELDOC_MAX_PAGES_PER_CHUNK` | `1` | 每次 BabelDOC 运行的页数，越大越快、越占内存 |
| `BABELDOC_RESOURCE_*` | 按 4 GB 调校 | 内存保护阈值（仅 Linux） |
| `BABELDOC_COMMAND` | `uv run --with babeldoc==0.6.2 --with bitstring==4.4.0 python` | 启动 Python 翻译脚本的命令 |

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
- [`frontend/src/components/TranslateWorkbench.vue`](frontend/src/components/TranslateWorkbench.vue)：全部界面。

## 许可

[AGPL-3.0](LICENSE)。BabelDOC 与 PyMuPDF 同为 AGPL-3.0，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。如果把修改后的版本作为网络服务提供，AGPL 要求你向使用者提供对应源码。
