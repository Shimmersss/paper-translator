"""Starting, stopping and inspecting the backend process, and login autostart."""

from __future__ import annotations

import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request
import webbrowser
from pathlib import Path
from typing import Optional

from . import ui
from .paths import Home, Layout
from .system import BABELDOC_WITH, find_java, os_name, uv_path

AUTOSTART_LABEL = "io.github.shimmersss.paper-translator"
READY_TIMEOUT_SECONDS = 120


def backend_command(home: Home, layout: Layout, java: str, port: int):
    """The java command line and environment shared by background and foreground starts."""
    uv = uv_path()
    if not uv:
        raise RuntimeError("找不到 uv，请先安装：https://docs.astral.sh/uv/")
    env = dict(os.environ)
    env.update({
        "SERVER_PORT": str(port),
        "TRANSLATION_STORAGE_DIR": str(home.storage),
        "SETTINGS_FILE": str(home.settings_file),
        "FRONTEND_DIST": layout.dist.as_uri() + "/",
        "BABELDOC_RUNNER_SCRIPT": str(layout.scripts / "babeldoc_runner.py"),
        "BABELDOC_COMMAND": " ".join([_quote(uv), "run", *BABELDOC_WITH, "python"]),
    })
    command = [java, "-jar", str(layout.jar),
               f"--spring.config.import=optional:file:{home.env_file.as_posix()}[.properties]"]
    return command, env


def _quote(value: str) -> str:
    return f'"{value}"' if any(ch.isspace() for ch in value) else value


def url(port: int) -> str:
    return f"http://127.0.0.1:{port}/"


def port_in_use(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.5)
        return probe.connect_ex(("127.0.0.1", port)) == 0


def http_ok(port: int, path: str = "api/settings") -> bool:
    try:
        with urllib.request.urlopen(url(port) + path, timeout=3) as response:
            return response.status == 200
    except Exception:
        return False


def read_pid(home: Home) -> Optional[dict]:
    try:
        return json.loads(home.pid_file.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def process_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    if os_name() == "windows":
        output = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True)
        return str(pid) in output.stdout
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def running(home: Home) -> Optional[dict]:
    info = read_pid(home)
    if info and process_alive(int(info.get("pid", 0))):
        return info
    return None


def start(home: Home, layout: Layout, port: int, open_browser: bool = True, foreground: bool = False) -> int:
    if running(home):
        info = running(home)
        ui.ok(f"已在运行：{url(int(info['port']))}")
        return 0
    if not layout.jar.is_file():
        ui.fail(f"缺少 {layout.jar}，请先运行 paper-translator setup")
        return 1
    if port_in_use(port):
        ui.fail(f"端口 {port} 已被占用。用 --port 换一个端口，或先停掉占用它的程序")
        return 1
    found = find_java(home.jdk_dir)
    if not found:
        ui.fail("找不到 JDK 17+，请先运行 paper-translator setup")
        return 1
    command, env = backend_command(home, layout, found[0], port)
    home.logs.mkdir(parents=True, exist_ok=True)
    home.storage.mkdir(parents=True, exist_ok=True)

    if foreground:
        # For launchd / systemd / Task Scheduler: they capture output and restart on failure.
        process = subprocess.Popen(command, cwd=str(home.path), env=env)
        _write_pid(home, process.pid, port)
        try:
            return process.wait()
        finally:
            home.pid_file.unlink(missing_ok=True)

    log = open(home.log_file, "ab")
    kwargs = {"cwd": str(home.path), "env": env, "stdout": log, "stderr": subprocess.STDOUT,
              "stdin": subprocess.DEVNULL}
    if os_name() == "windows":
        kwargs["creationflags"] = (subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
                                   | getattr(subprocess, "CREATE_NO_WINDOW", 0))
    else:
        kwargs["start_new_session"] = True
    process = subprocess.Popen(command, **kwargs)
    log.close()
    _write_pid(home, process.pid, port)

    ui.step(f"正在启动（日志：{home.log_file}）")
    deadline = time.time() + READY_TIMEOUT_SECONDS
    while time.time() < deadline:
        if process.poll() is not None:
            home.pid_file.unlink(missing_ok=True)
            ui.fail("服务启动失败，最近的日志：")
            print(tail(home.log_file, 30))
            return 1
        if http_ok(port):
            ui.ok(f"已启动：{url(port)}")
            if open_browser:
                webbrowser.open(url(port))
            return 0
        time.sleep(1)
    ui.warn(f"{READY_TIMEOUT_SECONDS} 秒内未就绪，进程仍在运行；用 paper-translator logs 查看日志")
    return 1


def _write_pid(home: Home, pid: int, port: int) -> None:
    home.pid_file.parent.mkdir(parents=True, exist_ok=True)
    home.pid_file.write_text(json.dumps({"pid": pid, "port": port, "started": int(time.time())}),
                             encoding="utf-8")


def stop(home: Home) -> int:
    info = running(home)
    if not info:
        home.pid_file.unlink(missing_ok=True)
        ui.ok("没有在运行")
        return 0
    pid = int(info["pid"])
    if os_name() == "windows":
        # /T also ends the BabelDOC child processes.
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
    else:
        # The backend shuts down cleanly on SIGTERM; its process group holds BabelDOC.
        try:
            os.killpg(pid, signal.SIGTERM)
        except (ProcessLookupError, PermissionError):
            os.kill(pid, signal.SIGTERM)
        for _ in range(30):
            if not process_alive(pid):
                break
            time.sleep(0.5)
        else:
            try:
                os.killpg(pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
    for _ in range(20):
        if not process_alive(pid):
            break
        time.sleep(0.25)
    home.pid_file.unlink(missing_ok=True)
    ui.ok("已停止")
    return 0


def tail(path: Path, lines: int) -> str:
    if not path.is_file():
        return "(还没有日志)"
    with open(path, "rb") as handle:
        handle.seek(0, os.SEEK_END)
        size = handle.tell()
        block = min(size, max(4096, lines * 400))
        handle.seek(size - block)
        data = handle.read().decode("utf-8", errors="replace")
    return "\n".join(data.splitlines()[-lines:])


def follow(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.touch(exist_ok=True)
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        handle.seek(0, os.SEEK_END)
        try:
            while True:
                line = handle.readline()
                if line:
                    sys.stdout.write(line)
                    sys.stdout.flush()
                else:
                    time.sleep(0.5)
        except KeyboardInterrupt:
            pass


# ------------------------------------------------------------------ autostart

def cli_executable() -> str:
    found = shutil.which("paper-translator")
    return found or str(Path(sys.argv[0]).resolve())


def autostart_files(home: Home) -> dict:
    """What `autostart enable` writes, per platform (also used by tests)."""
    exe = cli_executable()
    system = os_name()
    if system == "mac":
        plist = Path.home() / "Library" / "LaunchAgents" / f"{AUTOSTART_LABEL}.plist"
        content = f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>{AUTOSTART_LABEL}</string>
  <key>ProgramArguments</key>
  <array><string>{exe}</string><string>start</string><string>--foreground</string><string>--no-browser</string></array>
  <key>EnvironmentVariables</key>
  <dict><key>PAPER_TRANSLATOR_HOME</key><string>{home.path}</string>
  <key>PATH</key><string>{os.environ.get('PATH', '')}</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>StandardOutPath</key><string>{home.log_file}</string>
  <key>StandardErrorPath</key><string>{home.log_file}</string>
</dict>
</plist>
"""
        return {"path": plist, "content": content}
    if system == "linux":
        unit = Path.home() / ".config" / "systemd" / "user" / "paper-translator.service"
        content = f"""[Unit]
Description=Paper Translator
After=network-online.target

[Service]
Environment=PAPER_TRANSLATOR_HOME={home.path}
Environment=PATH={os.environ.get('PATH', '')}
ExecStart="{exe}" start --foreground --no-browser
Restart=on-failure

[Install]
WantedBy=default.target
"""
        return {"path": unit, "content": content}
    return {"path": None, "content": f'"{exe}" start --no-browser'}


def autostart(home: Home, action: str) -> int:
    system = os_name()
    spec = autostart_files(home)
    if action == "enable":
        if system == "windows":
            result = subprocess.run(["schtasks", "/Create", "/TN", "PaperTranslator", "/SC", "ONLOGON",
                                     "/TR", spec["content"], "/F"], capture_output=True, text=True)
            ok = result.returncode == 0
        else:
            spec["path"].parent.mkdir(parents=True, exist_ok=True)
            spec["path"].write_text(spec["content"], encoding="utf-8")
            if system == "mac":
                uid = str(os.getuid())
                subprocess.run(["launchctl", "bootout", f"gui/{uid}", str(spec["path"])], capture_output=True)
                ok = subprocess.run(["launchctl", "bootstrap", f"gui/{uid}", str(spec["path"])],
                                    capture_output=True).returncode == 0
            else:
                subprocess.run(["systemctl", "--user", "daemon-reload"], capture_output=True)
                ok = subprocess.run(["systemctl", "--user", "enable", "paper-translator"],
                                    capture_output=True).returncode == 0
        (ui.ok("已设置登录后自动启动") if ok else ui.fail("设置自动启动失败"))
        return 0 if ok else 1
    if action == "disable":
        if system == "windows":
            subprocess.run(["schtasks", "/Delete", "/TN", "PaperTranslator", "/F"], capture_output=True)
        elif system == "mac":
            subprocess.run(["launchctl", "bootout", f"gui/{os.getuid()}", str(spec["path"])], capture_output=True)
            spec["path"].unlink(missing_ok=True)
        else:
            subprocess.run(["systemctl", "--user", "disable", "paper-translator"], capture_output=True)
            spec["path"].unlink(missing_ok=True)
            subprocess.run(["systemctl", "--user", "daemon-reload"], capture_output=True)
        ui.ok("已取消自动启动")
        return 0
    enabled = (subprocess.run(["schtasks", "/Query", "/TN", "PaperTranslator"], capture_output=True).returncode == 0
               if system == "windows" else spec["path"].is_file())
    print("已开启" if enabled else "未开启")
    return 0
