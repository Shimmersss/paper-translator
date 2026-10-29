"""Platform detection and dependency checks/installs (JDK, uv, Tesseract, Node, Maven)."""

from __future__ import annotations

import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import List, Optional, Tuple

from . import mirrors, ui
from .archive import download, extract_archive

JAVA_FEATURE = 21
MIN_JAVA = 17
# Pinned interpreter: BabelDOC needs onnxruntime, which lags new Python releases.
BABELDOC_WITH = ["--python", "3.12", "--with", "babeldoc==0.6.2", "--with", "bitstring==4.4.0"]


def os_name() -> str:
    if sys.platform == "win32":
        return "windows"
    if sys.platform == "darwin":
        return "mac"
    return "linux"


def arch() -> str:
    machine = platform.machine().lower()
    return "aarch64" if machine in ("arm64", "aarch64") else "x64"


def which(name: str) -> Optional[str]:
    return shutil.which(name)


def run(command: List[str], check: bool = True, capture: bool = False, **kwargs) -> subprocess.CompletedProcess:
    if not capture:
        ui.detail("$ " + " ".join(command))
    return subprocess.run(command, check=check, text=True,
                          stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.STDOUT if capture else None, **kwargs)


# ------------------------------------------------------------------ Java

def java_major(java: str) -> Optional[int]:
    try:
        output = subprocess.run([java, "-version"], text=True, capture_output=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return None
    match = re.search(r'version "(\d+)(?:\.(\d+))?', output.stderr + output.stdout)
    if not match:
        return None
    major = int(match.group(1))
    return int(match.group(2)) if major == 1 and match.group(2) else major


def _java_candidates(jdk_dir: Path) -> List[Path]:
    exe = "java.exe" if os_name() == "windows" else "java"
    candidates: List[Path] = []
    if jdk_dir.exists():
        candidates += sorted(jdk_dir.glob(f"**/bin/{exe}"))
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / exe)
    if os_name() == "mac":
        for prefix in ("/opt/homebrew/opt", "/usr/local/opt"):
            for formula in ("openjdk@21", "openjdk@17", "openjdk"):
                candidates.append(Path(prefix) / formula / "bin" / "java")
    on_path = which("java")
    if on_path:
        candidates.append(Path(on_path))
    return candidates


def find_java(jdk_dir: Path) -> Optional[Tuple[str, int]]:
    """A usable java (17+), preferring the portable JDK, then JAVA_HOME, Homebrew and PATH."""
    seen = set()
    for candidate in _java_candidates(jdk_dir):
        path = str(candidate)
        if path in seen or not candidate.is_file():
            continue
        seen.add(path)
        major = java_major(path)
        if major and major >= MIN_JAVA:
            return path, major
    return None


def java_home(java: str) -> str:
    return str(Path(java).resolve().parent.parent)


def install_java(jdk_dir: Path, interactive: bool, use_mirror: bool = False) -> Optional[Tuple[str, int]]:
    """Package manager first (no admin needed for Homebrew/winget); otherwise a portable Temurin JDK.

    With the mainland mirror on, Homebrew bottles and winget packages still come from
    overseas, so macOS and Windows go straight to the portable JDK from TUNA/NJU.
    """
    system = os_name()
    if use_mirror and system in ("mac", "windows"):
        ui.step(f"从国内镜像下载便携版 Temurin JDK {JAVA_FEATURE} 到 {jdk_dir}（不需要管理员权限）")
        install_portable_jdk(jdk_dir, use_mirror=True)
        return find_java(jdk_dir)
    if system == "mac" and which("brew"):
        if not interactive or ui.confirm(f"用 Homebrew 安装 openjdk@{JAVA_FEATURE}？"):
            if run(["brew", "install", f"openjdk@{JAVA_FEATURE}"], check=False).returncode == 0:
                found = find_java(jdk_dir)
                if found:
                    return found
    if system == "windows" and which("winget"):
        if not interactive or ui.confirm(f"用 winget 安装 Temurin {JAVA_FEATURE}？"):
            result = run(["winget", "install", "--id", f"EclipseAdoptium.Temurin.{JAVA_FEATURE}.JDK", "-e",
                          "--accept-source-agreements", "--accept-package-agreements"], check=False)
            if result.returncode == 0:
                found = find_java(jdk_dir)
                if found:
                    return found
                ui.warn("winget 已安装 JDK，但当前终端还没刷新 PATH；先改用便携版")
    if system == "linux" and _can_sudo():
        manager = "apt-get" if which("apt-get") else "dnf" if which("dnf") else None
        package = f"openjdk-{JAVA_FEATURE}-jdk-headless" if manager == "apt-get" else f"java-{JAVA_FEATURE}-openjdk-headless"
        if manager and (not interactive or ui.confirm(f"用 {manager} 安装 {package}（需要 sudo）？")):
            if manager == "apt-get":
                run(["sudo", "-n", "apt-get", "update"], check=False)
            if run(["sudo", "-n", manager, "install", "-y", package], check=False).returncode == 0:
                found = find_java(jdk_dir)
                if found:
                    return found
    ui.step(f"下载便携版 Temurin JDK {JAVA_FEATURE} 到 {jdk_dir}（不需要管理员权限）")
    install_portable_jdk(jdk_dir, use_mirror)
    return find_java(jdk_dir)


def _can_sudo() -> bool:
    if hasattr(os, "geteuid") and os.geteuid() == 0:
        return which("apt-get") is not None or which("dnf") is not None
    return which("sudo") is not None and subprocess.run(
        ["sudo", "-n", "true"], capture_output=True).returncode == 0


def install_portable_jdk(jdk_dir: Path, use_mirror: bool = False) -> None:
    system = {"mac": "mac", "linux": "linux", "windows": "windows"}[os_name()]
    api = (f"https://api.adoptium.net/v3/assets/latest/{JAVA_FEATURE}/hotspot"
           f"?os={system}&architecture={arch()}&image_type=jdk&vendor=eclipse")
    with tempfile.TemporaryDirectory() as tmp:
        listing = Path(tmp) / "assets.json"
        download(api, listing)
        assets = json.loads(listing.read_text(encoding="utf-8"))
        if not assets:
            raise RuntimeError(f"Adoptium 没有 {system}/{arch()} 的 JDK {JAVA_FEATURE}")
        package = assets[0]["binary"]["package"]
        archive = Path(tmp) / package["name"]
        # The API is small and reachable from China; the binary itself lives on GitHub.
        urls = mirrors.jdk_urls(JAVA_FEATURE, arch(), system, package["name"]) if use_mirror else []
        urls.append(package["link"])
        for index, url in enumerate(urls):
            try:
                download(url, archive)
            except OSError as error:
                if index == len(urls) - 1:
                    raise
                ui.detail(f"{url} 不可用（{error}），换下一个下载源")
                continue
            if hashlib.sha256(archive.read_bytes()).hexdigest() == package["checksum"]:
                break
            if index == len(urls) - 1:
                raise RuntimeError("JDK 下载校验失败")
            ui.detail(f"{url} 的文件校验不一致，换下一个下载源")
        if jdk_dir.exists():
            shutil.rmtree(jdk_dir)
        extract_archive(archive, jdk_dir, strip_top_level=True)
    if os_name() != "windows":
        for java in jdk_dir.glob("**/bin/java"):
            java.chmod(0o755)


# ------------------------------------------------------------------ other tools

def uv_path() -> Optional[str]:
    return which("uv")


def tesseract_status() -> Tuple[bool, bool]:
    """(installed, has English data)."""
    tesseract = which("tesseract")
    if not tesseract:
        return False, False
    try:
        output = subprocess.run([tesseract, "--list-langs"], text=True, capture_output=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return True, False
    langs = (output.stdout + output.stderr).split()
    return True, "eng" in langs


def tesseract_install_command() -> List[str]:
    return {
        "mac": ["brew", "install", "tesseract"],
        "windows": ["winget", "install", "--id", "UB-Mannheim.TesseractOCR", "-e",
                    "--accept-source-agreements", "--accept-package-agreements"],
        "linux": ["sudo", "apt-get", "install", "-y", "tesseract-ocr", "tesseract-ocr-eng"],
    }[os_name()]


def java_install_hint() -> str:
    return {
        "mac": f"brew install openjdk@{JAVA_FEATURE}",
        "windows": f"winget install --id EclipseAdoptium.Temurin.{JAVA_FEATURE}.JDK -e",
        "linux": f"sudo apt-get install openjdk-{JAVA_FEATURE}-jdk-headless",
    }[os_name()] + "（或重新运行 paper-translator setup 下载便携版）"


def npm() -> Optional[str]:
    return which("npm.cmd" if os_name() == "windows" else "npm") or which("npm")


def maven() -> Optional[str]:
    return which("mvn.cmd" if os_name() == "windows" else "mvn") or which("mvn")
