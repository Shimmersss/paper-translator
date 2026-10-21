# 设置页与三端一键部署（待实施规格）

状态：规格草案，未实现。面向个人本机部署（macOS / Windows / Linux 桌面），不考虑原网站的 2 核 / 4 GB 服务器。

## 目标

1. 不改 `.env`、不重启，就能在网页上配置模型 API 和性能参数。
2. 性能默认按“现代个人电脑”给偏高的值，也可以选“最高性能”或手动设置。
3. 提供一个 CLI，三个平台都能一条命令装好依赖、配置并启动。

## 一、设置页

### 入口与权限

- 翻译页右上角加“设置”按钮，打开抽屉或对话框，不另做后台。
- 不加账号或认证。前提是默认只监听 `127.0.0.1`。
- 当 `SERVER_ADDRESS` 不是回环地址时，设置页只读，并提示“对外开放时请改 `.env` 并在前面加认证代理”，避免局域网内的人看到或修改 Key。

### 配置项

| 分组 | 字段 | 说明 |
| --- | --- | --- |
| PDF 翻译模型 | Base URL、API Key、模型名 | 任意 OpenAI 兼容接口；带“测试连接” |
| 图片翻译模型 | Base URL、API Key、模型名、视觉模型（可选）、协议 auto/openai/claude | 带“测试连接” |
| 性能 | 档位：自动 / 最高性能 / 手动 | 见下节 |
| 性能（手动） | 模型并发、每片页数、内存上限、单任务超时、队列长度 | 每项有校验范围和“恢复推荐值” |
| 存储 | 保留最近任务数 | 超出自动删除最旧的 |

- Key 只写不读：页面只显示末 4 位，留空表示不修改；日志、接口响应都不出现完整 Key。
- “测试连接”发一个极小请求，返回成功或去掉 Key 的简短错误；不保存配置。

### 存储与优先级

- 写入 `TRANSLATION_STORAGE_DIR` 同级的 `settings.json`，先写临时文件再原子替换，文件权限 0600（Windows 用默认 ACL）。
- 优先级：设置页 > `.env` / 环境变量 > 内置默认值。老用户只用 `.env` 的行为不变。
- `ProviderSettings` 改为读取“合并后的配置”；`BabelDocConfig`、`TranslationConfig` 中可调的字段同样从合并配置读取。

### 生效时机

- 保存后对下一个任务生效，不需要重启；正在运行的任务继续用旧值。
- 每片页数改变时，分片缓存键已包含相关参数，旧缓存不会被误用。界面提示“已完成的分片可能需要重翻”。

### 接口

- `GET /api/settings`：返回当前值（Key 打码）、各项来源（页面 / env / 默认）、探测到的硬件信息、三档推荐值。
- `PUT /api/settings`：保存；校验失败返回字段级错误。
- `POST /api/settings/test`：测试 PDF 或图片模型连接。
- 只接受来自回环地址的写请求（与只读规则一致）。

## 二、性能档位

### 能控制什么

只能控制这几项：模型并发、每片页数、内存保护阈值、超时、队列。CPU 只能通过并发间接影响，界面上要如实说明，不放“CPU 百分比”滑块。需要硬限制时，在文档里引导用 Docker `--cpus` 或 systemd `CPUQuota`。

### 硬件探测

- 核数：`Runtime.availableProcessors()`（容器内已感知 cgroup）。
- 内存：Linux 读 cgroup `memory.max`，没有限制时读 `/proc/meminfo`；macOS 和 Windows 用 `OperatingSystemMXBean.getTotalMemorySize()` / `getFreeMemorySize()`。
- 探测失败时按 8 GB / 4 核的保守值处理，并在设置页显示“未能识别硬件”。

### 三档的计算

BabelDOC 单进程峰值按约 2.8 GB 估算，系统预留 2 GB；仍然只运行一个 BabelDOC 进程，不开多进程。

| 内存（可用于本服务） | 自动：每片页数 / 并发 | 最高性能：每片页数 / 并发 |
| --- | --- | --- |
| < 6 GB | 1 / 2 | 1 / 4 |
| 6–12 GB | 3 / 4 | 5 / 6 |
| 12–24 GB | 5 / 6 | 10 / 8 |
| ≥ 24 GB | 10 / 8 | 20 / 12 |

- 并发另受核数约束：不超过核数 × 2。
- 内存保护阈值按“可用内存 − 预留”自动算出；最高性能档放宽 Swap 上限。
- 三档都保留现有的内存保护和自动降级（降并发、再降为逐页），所以激进的设置也不会把电脑拖死。
- 模型服务商限流往往比本机先到瓶颈：设置页单独提供“服务商并发上限”，遇到 429 / RateLimit 错误时提示调低。
- 翻译页去掉“稳定 / 加速”单选，由档位统一决定。接口里的 `qps` 参数保留兼容，未传时按档位取值。

## 三、一键部署 CLI

### 形式

单个跨平台命令 `paper-translator`，用 Python 编写，由 `uv` 运行（项目已依赖 uv，三平台都有官方安装方式）。仓库根目录另放两个引导脚本：

- `install.sh`（macOS / Linux）：`curl -fsSL …/install.sh | sh`
- `install.ps1`（Windows）：`irm …/install.ps1 | iex`

引导脚本只负责装好 `uv` 并拉取或更新仓库，然后调用 CLI。

### 命令

| 命令 | 作用 |
| --- | --- |
| `paper-translator setup` | 检查并安装依赖，交互式填写 API（可跳过，之后在设置页填），构建前后端 |
| `paper-translator start` / `stop` / `status` | 后台启动或停止服务、查看状态，启动后自动打开浏览器 |
| `paper-translator update` | 拉取新版本、重新构建，保留 `.env`、`settings.json` 和数据目录 |
| `paper-translator doctor` | 逐项检查 JDK、Node、uv、Tesseract、端口、模型连通性，给出修复命令 |
| `paper-translator logs` | 查看后端日志 |
| `paper-translator uninstall` | 删除程序，询问是否保留数据和配置 |

### 依赖处理

| 依赖 | macOS | Windows | Linux |
| --- | --- | --- | --- |
| JDK 17 / 21 | Homebrew `openjdk@21` | winget `EclipseAdoptium.Temurin.21.JDK` | apt / dnf，失败时下载 Temurin 便携版到程序目录 |
| Node.js | 仅源码构建时需要 | 同左 | 同左 |
| uv | 官方安装脚本 | 官方 PowerShell 脚本 | 官方安装脚本 |
| Tesseract（可选） | `brew install tesseract` | winget `UB-Mannheim.TesseractOCR` | `apt install tesseract-ocr tesseract-ocr-eng` |

- 优先使用系统包管理器；没有权限或失败时，退回把 JDK 下载到程序目录（不需要管理员权限）。
- 发布版附带预构建产物（后端 JAR + 前端 dist，放在 GitHub Releases），普通用户不需要 Node 和 Maven；只有 `--from-source` 才在本机构建。
- 预热：`setup` 末尾执行一次 `uv run --with babeldoc==0.6.2 …`，把 BabelDOC 和版面模型提前下载好，避免首次翻译等待数分钟。
- 关于 JDK 版本：测试需要 17 或 21（JDK 26 下 Mockito 无法运行），运行时 17+ 都可以；CLI 默认安装 21。

### 后台运行

- 默认不注册系统服务：`start` 用 PID 文件管理后台进程。
- 可选 `--autostart`：macOS 用 launchd，Windows 用计划任务，Linux 用 systemd `--user`。
- 日志写在数据目录的 `logs/`。

### Docker（可选路线）

同时提供 `docker compose up -d` 的方案：镜像内含 JRE、uv、预装的 BabelDOC 和 Tesseract。适合不想在本机装依赖的用户。端口映射默认 `127.0.0.1:8080`。注意：目前开发机没有 Docker 守护进程，发布前需要在有 Docker 的环境里实测。

## 四、GitHub Actions 自动构建与发布

在仓库 `.github/workflows/` 下放两个工作流。公开仓库使用 Actions 免费。

### `ci.yml`：每次推送和 PR

- 触发：推送到任意分支、所有 Pull Request。
- 矩阵：`ubuntu-latest`、`macos-latest`、`windows-latest`。
- 步骤：
  1. `actions/setup-java`（Temurin 21，启用 Maven 缓存），在 `backend/` 跑 `mvn -B test`。
  2. `actions/setup-node`（Node 20，启用 npm 缓存），在 `frontend/` 跑 `npm ci && npm run build`。
  3. `astral-sh/setup-uv`，跑 `backend/scripts` 的 Python 测试；Linux 先 `apt install tesseract-ocr`，macOS 用 `brew install tesseract`，Windows 先跳过 OCR 测试（或用 choco 安装后再跑）。
  4. CLI 冒烟：`paper-translator setup --non-interactive --from-source`，然后 `start`、`status`、`stop`、`doctor`。
- 不需要任何密钥；真实模型调用不放在 CI 里。

### `release.yml`：打版本标签时发布

- 触发：推送 `v*` 标签（例如 `git tag v0.2.0 && git push --tags`）。
- 构建一次（ubuntu 即可，jar 和前端产物与平台无关），产出：

| 产物 | 内容 |
| --- | --- |
| `paper-translator-<版本>.zip` | `paper-translator.jar`、`frontend/dist/`、`scripts/`（含 tiktoken 词表）、`.env.example`、LICENSE、README |
| `paper-translator-<版本>.tar.gz` | 同上，给 macOS / Linux |
| `SHA256SUMS.txt` | 上面两个包的校验和 |

- 用 `softprops/action-gh-release` 创建 GitHub Release，附上产物，并根据提交记录自动生成更新说明。
- 可选：同一工作流构建并推送 Docker 镜像到 GitHub Container Registry（`ghcr.io/shimmersss/paper-translator:<版本>` 与 `latest`），对应 Docker 路线。

### 与 CLI 的衔接

- `install.sh` / `install.ps1` 和 `paper-translator update` 通过 GitHub API 查最新 Release，下载对应压缩包，校验 `SHA256SUMS.txt` 后解压，保留 `.env`、`settings.json` 和数据目录。
- 用户本机只需要 JDK 和 uv（CLI 会自动装），不需要 Node 和 Maven。
- `--from-source` 仍支持本机构建，供开发者使用。

### 发版流程

1. 更新版本号（`backend/pom.xml`、`frontend/package.json`）和更新说明。
2. 打标签并推送：`git tag v0.2.0`，`git push origin v0.2.0`。
3. Actions 自动构建、测试、发布；Release 页面出现下载包后，一键安装脚本即可拿到新版本。

## 五、测试与验收

- 单元测试：配置合并与优先级、Key 打码、只读判断、三档计算（各内存区间与核数上限）、硬件探测失败时的回退。
- 接口测试：设置读写、字段校验、测试连接（模拟成功和失败）。
- CLI：三平台在 CI（GitHub Actions 的 macos / windows / ubuntu runner）跑 `setup --non-interactive`、`start`、`status`、`stop`、`doctor`。
- 真实验收：每个平台至少翻译一份多页 PDF 和一张图片，并检查改设置后下一个任务确实生效。
- 文档：更新中英文 README 的“快速开始”为一键安装，保留手动安装章节。

## 六、建议的实施顺序

1. 配置合并层与 `settings.json`（后端，不涉及界面）。
2. 硬件探测与三档计算，补齐单元测试。
3. 设置页前端与接口。
4. GitHub Actions：先 `ci.yml`（三平台测试），再 `release.yml`（打标签自动发布）。
5. CLI（先 macOS / Linux，再 Windows），从 Releases 下载预构建产物。
6. Docker 方案（镜像由 `release.yml` 一并发布）。

## 待确认

1. CLI 名称是否就用 `paper-translator`。
2. 是否需要第一版就做 Docker，还是放到后续。
