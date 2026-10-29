"""paper-translator: install, configure, start, stop and update Paper Translator."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path
from typing import List, Optional

from . import __version__, mirrors, service, system, ui
from .paths import Home, Layout, home_dir, read_env_file, restrict_to_owner, update_env_file
from .releases import install_release, latest_release, version_tuple


# ------------------------------------------------------------------ setup

def cmd_setup(args, home: Home) -> int:
    interactive = ui.INTERACTIVE and not args.non_interactive
    home.path.mkdir(parents=True, exist_ok=True)
    config = home.load()
    use_mirror = choose_mirror(args, config)
    home.save(config)

    ui.step("应用文件")
    if args.from_source:
        source = Path(args.source_dir or Path.cwd()).resolve()
        layout = Layout.detect(source)
        if not layout or layout.mode != "source":
            ui.fail(f"{source} 不是 paper-translator 源码目录（缺少 backend/pom.xml），用 --source-dir 指定")
            return 1
        ui.ok(f"使用源码目录 {layout.root}")
    elif args.archive:
        layout = install_release(home.app_dir, archive=Path(args.archive).resolve())
        ui.ok(f"已从 {args.archive} 安装 {layout.version()}")
    else:
        layout = home.layout()
        if layout and layout.mode == "release" and layout.jar.is_file():
            ui.ok(f"已安装 {layout.version()}（升级用 paper-translator update）")
        else:
            layout = install_release(home.app_dir)
            ui.ok(f"已安装 {layout.version()}")
    config.update({"app": str(layout.root), "mode": layout.mode})
    if args.port:
        config["port"] = args.port
    home.save(config)

    ui.step("Java")
    java = system.find_java(home.jdk_dir)
    if java:
        ui.ok(f"JDK {java[1]}：{java[0]}")
    else:
        if interactive and not ui.confirm("没有找到 JDK 17+，现在安装？"):
            ui.fail("需要 JDK 17 或更高版本：" + system.java_install_hint())
            return 1
        java = system.install_java(home.jdk_dir, interactive, use_mirror)
        if not java:
            ui.fail("JDK 安装失败：" + system.java_install_hint())
            return 1
        ui.ok(f"JDK {java[1]}：{java[0]}")

    ui.step("uv")
    if system.uv_path():
        ui.ok(system.uv_path())
    else:
        ui.fail("找不到 uv：https://docs.astral.sh/uv/getting-started/installation/")
        return 1

    ui.step("Tesseract（可选，用于扫描版 PDF）")
    installed, english = system.tesseract_status()
    if installed and english:
        ui.ok("已安装，含英文识别数据")
    elif args.install_tesseract or (interactive and ui.confirm("没有找到 Tesseract，现在安装？", default=False)):
        command = system.tesseract_install_command()
        if system.run(command, check=False).returncode != 0:
            ui.warn("安装失败，可稍后手动运行：" + " ".join(command))
    else:
        ui.warn("未安装；扫描版 PDF 需要它：" + " ".join(system.tesseract_install_command()))

    if layout.mode == "source":
        ui.step("构建（源码模式）")
        if build_source(layout, java[0], use_mirror) != 0:
            return 1

    ui.step("配置文件")
    prepare_env(home, layout, interactive)

    if not args.skip_warmup:
        ui.step("预下载 BabelDOC 和版面模型（首次约需几分钟）")
        result = system.run([system.uv_path(), "run", *system.BABELDOC_WITH, "babeldoc", "--warmup"], check=False,
                            env=dict(os.environ, **mirrors.uv_env(use_mirror)))
        (ui.ok("完成") if result.returncode == 0 else ui.warn("预下载失败，首次翻译时会再试"))

    print()
    ui.ok("安装完成。启动：paper-translator start")
    return 0


def choose_mirror(args, config: dict) -> bool:
    """Settles the download source for this run and remembers it in config.json."""
    mode = getattr(args, "mirror", None) or mirrors.requested(config)
    if mode == "auto":
        ui.step("检测下载源")
        mode = mirrors.resolve(mode)
        (ui.ok("GitHub 可以访问，使用官方下载源") if mode == "off"
         else ui.ok("GitHub 无法访问，改用国内镜像（阿里云 PyPI、npmmirror、清华/南大 JDK）"))
    elif mode == "cn":
        ui.ok("使用国内镜像下载（阿里云 PyPI、npmmirror、清华/南大 JDK）")
    config["mirror"] = mode
    return mode == "cn"


def build_source(layout: Layout, java: str, use_mirror: bool = False) -> int:
    mvn, npm = system.maven(), system.npm()
    missing = [name for name, path in (("Maven", mvn), ("Node.js / npm", npm)) if not path]
    if missing:
        ui.fail("源码构建需要 " + "、".join(missing) + "；发布版安装不需要它们")
        return 1
    env = dict(os.environ, JAVA_HOME=system.java_home(java))
    if use_mirror:
        ui.detail("源码构建仍从 Maven Central 和 npm 官方源下载依赖；需要时请自行配置 Maven/npm 镜像")
    if system.run([mvn, "-q", "-B", "-DskipTests", "package"], check=False,
                  cwd=str(layout.root / "backend"), env=env).returncode != 0:
        ui.fail("后端构建失败")
        return 1
    frontend = str(layout.root / "frontend")
    if system.run([npm, "ci", "--no-audit", "--no-fund"], check=False, cwd=frontend).returncode != 0 \
            or system.run([npm, "run", "build"], check=False, cwd=frontend).returncode != 0:
        ui.fail("前端构建失败")
        return 1
    ui.ok("构建完成")
    return 0


def prepare_env(home: Home, layout: Layout, interactive: bool) -> None:
    if not home.env_file.is_file():
        if layout.env_example.is_file():
            shutil.copy2(layout.env_example, home.env_file)
        else:
            home.env_file.write_text("", encoding="utf-8")
        restrict_to_owner(home.env_file)
        ui.ok(f"已创建 {home.env_file}")
    else:
        restrict_to_owner(home.env_file)
        ui.ok(f"保留现有 {home.env_file}")
    current = read_env_file(home.env_file)
    if not interactive or current.get("BABELDOC_OPENAI_API_KEY"):
        if not current.get("BABELDOC_OPENAI_API_KEY"):
            ui.warn("还没有填写翻译模型的 API Key，可以启动后在网页的“设置”里填写")
        return
    if not ui.confirm("现在填写 PDF 翻译模型的接口（也可以稍后在网页的“设置”里填）？"):
        return
    base_url = ui.ask("接口地址（OpenAI 兼容）", current.get("BABELDOC_OPENAI_BASE_URL", "https://api.openai.com/v1"))
    api_key = ui.ask("API Key", secret=True)
    model = ui.ask("模型名", current.get("BABELDOC_OPENAI_MODEL", "gpt-4o-mini"))
    updates = {"BABELDOC_OPENAI_BASE_URL": base_url, "BABELDOC_OPENAI_MODEL": model}
    if api_key:
        updates["BABELDOC_OPENAI_API_KEY"] = api_key
    update_env_file(home.env_file, updates)
    ui.ok("已写入 .env")


# ------------------------------------------------------------------ service commands

def require_layout(home: Home) -> Optional[Layout]:
    layout = home.layout()
    if not layout:
        ui.fail("还没有安装，请先运行 paper-translator setup")
    return layout


def cmd_start(args, home: Home) -> int:
    layout = require_layout(home)
    if not layout:
        return 1
    port = args.port or home.port()
    return service.start(home, layout, port, open_browser=not args.no_browser, foreground=args.foreground)


def cmd_stop(args, home: Home) -> int:
    return service.stop(home)


def cmd_status(args, home: Home) -> int:
    layout = home.layout()
    info = service.running(home)
    print(f"版本      {layout.version() if layout else '未安装'}（CLI {__version__}）")
    print(f"程序目录  {layout.root if layout else '-'}（{layout.mode if layout else '-'}）")
    print(f"数据目录  {home.data}")
    if info:
        port = int(info["port"])
        state = "运行中" if service.http_ok(port) else "进程存在，但网页暂时无响应"
        print(f"状态      {state}  PID {info['pid']}  {service.url(port)}")
        return 0
    print("状态      未运行")
    return 3


def cmd_logs(args, home: Home) -> int:
    if args.follow:
        print(service.tail(home.log_file, args.lines))
        service.follow(home.log_file)
    else:
        print(service.tail(home.log_file, args.lines))
    return 0


def cmd_autostart(args, home: Home) -> int:
    return service.autostart(home, args.action)


# ------------------------------------------------------------------ doctor

def cmd_doctor(args, home: Home) -> int:
    problems = 0

    def good(message):
        ui.ok(message)

    def bad(message, fix=None):
        nonlocal problems
        problems += 1
        ui.fail(message + (f"\n      修复：{fix}" if fix else ""))

    layout = home.layout()
    if not layout:
        bad("还没有安装", "paper-translator setup")
        return 1
    good(f"程序 {layout.version()}（{layout.mode}）：{layout.root}")
    ui.ok("下载源：" + ("国内镜像" if mirrors.enabled(home.load()) else "官方（GitHub / PyPI）")
          + "（切换：paper-translator setup --mirror cn|off|auto）")
    for label, path, fix in (("后端", layout.jar, "paper-translator setup"),
                             ("前端", layout.dist / "index.html", "paper-translator setup"),
                             ("BabelDOC 脚本", layout.scripts / "babeldoc_runner.py", "paper-translator update")):
        (good(f"{label}：{path}") if path.is_file() else bad(f"缺少{label}：{path}", fix))
    java = system.find_java(home.jdk_dir)
    (good(f"JDK {java[1]}：{java[0]}") if java else bad("找不到 JDK 17+", system.java_install_hint()))
    (good(f"uv：{system.uv_path()}") if system.uv_path()
     else bad("找不到 uv", "https://docs.astral.sh/uv/getting-started/installation/"))
    if layout.mode == "source":
        (good("Maven") if system.maven() else bad("找不到 Maven（源码模式需要）"))
        (good("Node.js / npm") if system.npm() else bad("找不到 npm（源码模式需要）"))
    installed, english = system.tesseract_status()
    if installed and english:
        good("Tesseract（含英文数据）")
    else:
        ui.warn("Tesseract 未安装或缺少英文数据；只影响扫描版 PDF：" + " ".join(system.tesseract_install_command()))
    if home.env_file.is_file():
        good(f".env：{home.env_file}")
    else:
        ui.warn(f"没有 {home.env_file}；可以只用网页里的设置")

    info = service.running(home)
    port = int(info["port"]) if info else home.port()
    if info and service.http_ok(port):
        good(f"服务运行中：{service.url(port)}")
        for kind, label in (("pdf", "PDF 翻译模型"), ("image", "图片翻译模型")):
            result = _test_connection(port, kind)
            message = result.get("message", "")
            if result.get("ok"):
                good(f"{label}：{message}")
            elif message.startswith("未填写"):
                # Not configured yet is a choice, not a fault; the page can fill it in later.
                ui.warn(f"{label}：{message}（在网页“设置”里填写）")
            elif kind == "pdf":
                bad(f"{label}：{message}", "在网页“设置”里检查接口、Key 和模型名")
            else:
                ui.warn(f"{label}：{message}")
    elif service.port_in_use(port):
        bad(f"端口 {port} 被其他程序占用", f"paper-translator start --port {port + 1}")
    else:
        ui.warn("服务未运行，启动后可检测模型连接：paper-translator start")
    print()
    (ui.ok("没有发现问题") if problems == 0 else ui.fail(f"发现 {problems} 个问题"))
    return 0 if problems == 0 else 1


def _test_connection(port: int, kind: str) -> dict:
    request = urllib.request.Request(service.url(port) + "api/settings/test", method="POST",
                                     data=json.dumps({"kind": kind}).encode("utf-8"),
                                     headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode("utf-8")).get("data", {})
    except Exception as error:
        return {"ok": False, "message": f"无法检测：{error}"}


# ------------------------------------------------------------------ update / uninstall

def cmd_update(args, home: Home) -> int:
    layout = require_layout(home)
    if not layout:
        return 1
    was_running = service.running(home) is not None
    config = home.load()
    use_mirror = choose_mirror(args, config)
    home.save(config)
    if layout.mode == "source":
        ui.step(f"更新源码 {layout.root}")
        if system.run(["git", "-C", str(layout.root), "pull", "--ff-only"], check=False).returncode != 0:
            ui.fail("git pull 失败，请手动处理后重试")
            return 1
        java = system.find_java(home.jdk_dir)
        if not java or build_source(layout, java[0], use_mirror) != 0:
            return 1
    else:
        release = latest_release()
        if not release:
            ui.fail("仓库还没有发布版本")
            return 1
        latest = release["tag_name"].lstrip("v")
        if not args.force and version_tuple(latest) <= version_tuple(layout.version()):
            ui.ok(f"已是最新版本 {layout.version()}")
            return 0
        if was_running:
            service.stop(home)
        install_release(home.app_dir, release)
        ui.ok(f"已更新到 {latest}（.env、settings.json 和数据目录保留不变）")
        reinstall_cli(home.app_dir / "cli", use_mirror)
    if was_running:
        service.stop(home)
        return service.start(home, home.layout(), home.port(), open_browser=False)
    return 0


def reinstall_cli(cli_dir: Path, use_mirror: bool = False) -> None:
    uv = system.uv_path()
    if uv and cli_dir.is_dir():
        system.run([uv, "tool", "install", "--force", str(cli_dir)], check=False,
                   env=dict(os.environ, **mirrors.uv_env(use_mirror)))


def cmd_uninstall(args, home: Home) -> int:
    layout = home.layout()
    if not args.yes and not ui.confirm(f"卸载 Paper Translator（{home.path}）？", default=False):
        return 1
    service.stop(home)
    service.autostart(home, "disable")
    keep = args.keep_data or (not args.purge and ui.confirm("保留翻译数据、.env 和 settings.json？"))
    if layout and layout.mode == "release":
        shutil.rmtree(layout.root, ignore_errors=True)
    shutil.rmtree(home.jdk_dir, ignore_errors=True)
    home.config_file.unlink(missing_ok=True)
    if not keep:
        shutil.rmtree(home.data, ignore_errors=True)
        home.env_file.unlink(missing_ok=True)
        try:
            home.path.rmdir()
        except OSError:
            pass
    ui.ok("已卸载" + ("，数据保留在 " + str(home.path) if keep else ""))
    if layout and layout.mode == "source":
        ui.detail(f"源码目录 {layout.root} 未删除")
    ui.detail("最后移除命令本身：uv tool uninstall paper-translator")
    return 0


# ------------------------------------------------------------------ entry point

def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(prog="paper-translator", description="安装、启动和更新 Paper Translator")
    root.add_argument("--version", action="version", version=f"paper-translator {__version__}")
    commands = root.add_subparsers(dest="command", required=True)

    setup = commands.add_parser("setup", help="检查并安装依赖、配置 API、构建并预下载模型")
    setup.add_argument("--non-interactive", action="store_true", help="不提问，全部使用默认值")
    setup.add_argument("--from-source", action="store_true", help="使用源码目录并在本机构建")
    setup.add_argument("--source-dir", help="源码目录（默认当前目录）")
    setup.add_argument("--archive", help="从本地发布包安装（离线安装）")
    setup.add_argument("--port", type=int, help="服务端口（默认 8080）")
    setup.add_argument("--install-tesseract", action="store_true", help="同时安装 Tesseract")
    setup.add_argument("--skip-warmup", action="store_true", help="跳过预下载 BabelDOC 与模型")
    setup.add_argument("--mirror", choices=mirrors.MODES, help="下载源：auto 自动检测（默认），cn 国内镜像，off 官方源")

    start = commands.add_parser("start", help="后台启动服务并打开浏览器")
    start.add_argument("--port", type=int)
    start.add_argument("--no-browser", action="store_true")
    start.add_argument("--foreground", action="store_true", help="前台运行（供自动启动使用）")

    commands.add_parser("stop", help="停止服务")
    commands.add_parser("status", help="查看状态")
    logs = commands.add_parser("logs", help="查看后端日志")
    logs.add_argument("-n", "--lines", type=int, default=200)
    logs.add_argument("-f", "--follow", action="store_true")
    commands.add_parser("doctor", help="逐项检查环境并给出修复命令")
    update = commands.add_parser("update", help="更新到最新版本，保留配置和数据")
    update.add_argument("--force", action="store_true", help="即使版本相同也重新安装")
    update.add_argument("--mirror", choices=mirrors.MODES, help="下载源：auto 自动检测（默认），cn 国内镜像，off 官方源")
    uninstall = commands.add_parser("uninstall", help="删除程序")
    uninstall.add_argument("-y", "--yes", action="store_true")
    uninstall.add_argument("--keep-data", action="store_true")
    uninstall.add_argument("--purge", action="store_true", help="同时删除数据和配置")
    auto = commands.add_parser("autostart", help="登录后自动启动")
    auto.add_argument("action", choices=["enable", "disable", "status"])
    return root


HANDLERS = {
    "setup": cmd_setup, "start": cmd_start, "stop": cmd_stop, "status": cmd_status, "logs": cmd_logs,
    "doctor": cmd_doctor, "update": cmd_update, "uninstall": cmd_uninstall, "autostart": cmd_autostart,
}


def main(argv: Optional[List[str]] = None) -> int:
    args = parser().parse_args(argv)
    home = Home(home_dir())
    try:
        return HANDLERS[args.command](args, home)
    except KeyboardInterrupt:
        print()
        return 130
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        ui.fail(str(error))
        return 1
