"""Finding, downloading and installing GitHub Releases."""

from __future__ import annotations

import json
import re
import shutil
import tempfile
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional, Tuple

from . import ui
from .archive import USER_AGENT, download, extract_archive, parse_sums, sha256
from .paths import Layout
from .system import os_name

REPOSITORY = "Shimmersss/paper-translator"


def version_tuple(version: str) -> Tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", version.lstrip("v"))[:3])


def latest_release() -> Optional[dict]:
    request = urllib.request.Request(f"https://api.github.com/repos/{REPOSITORY}/releases/latest",
                                     headers={"User-Agent": USER_AGENT, "Accept": "application/vnd.github+json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def _asset(release: dict, name: str) -> Optional[str]:
    for asset in release.get("assets", []):
        if asset.get("name") == name:
            return asset.get("browser_download_url")
    return None


def install_release(app_dir: Path, release: Optional[dict] = None, archive: Optional[Path] = None) -> Layout:
    """Installs a release (from GitHub, or a local archive) into app_dir, replacing it only on success."""
    with tempfile.TemporaryDirectory() as tmp:
        if archive is None:
            release = release or latest_release()
            if not release:
                raise RuntimeError("仓库还没有发布版本，请改用 --from-source 从源码安装")
            version = release["tag_name"].lstrip("v")
            suffix = "zip" if os_name() == "windows" else "tar.gz"
            name = f"paper-translator-{version}.{suffix}"
            archive_url = _asset(release, name)
            sums_url = _asset(release, "SHA256SUMS.txt")
            if not archive_url or not sums_url:
                raise RuntimeError(f"版本 {release['tag_name']} 缺少 {name} 或 SHA256SUMS.txt")
            archive = Path(tmp) / name
            ui.step(f"下载 {name}")
            download(archive_url, archive)
            sums_file = Path(tmp) / "SHA256SUMS.txt"
            download(sums_url, sums_file)
            expected = parse_sums(sums_file.read_text(encoding="utf-8")).get(name)
            if not expected or expected != sha256(archive):
                raise RuntimeError("下载的压缩包校验失败，已放弃安装")
            ui.ok("校验通过")
        staging = app_dir.parent / (app_dir.name + ".new")
        extract_archive(archive, staging, strip_top_level=True)
        layout = Layout.detect(staging)
        if not layout or layout.mode != "release" or not layout.jar.is_file():
            shutil.rmtree(staging, ignore_errors=True)
            raise RuntimeError("压缩包内容不是 Paper Translator 发布包")
        previous = app_dir.parent / (app_dir.name + ".old")
        if previous.exists():
            shutil.rmtree(previous)
        if app_dir.exists():
            app_dir.rename(previous)
        staging.rename(app_dir)
        shutil.rmtree(previous, ignore_errors=True)
    return Layout(app_dir, "release")
