"""Downloads, checksums, safe extraction and the release archive layout."""

from __future__ import annotations

import hashlib
import io
import os
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath
from typing import Iterable, List

USER_AGENT = "paper-translator-cli"


def download(url: str, target: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
    target.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(request, timeout=120) as response, open(target, "wb") as out:
        shutil.copyfileobj(response, out, length=1024 * 1024)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_sums(text: str) -> dict:
    """`sha256sum` output: '<hex>  <name>' per line (a leading '*' marks binary mode)."""
    sums = {}
    for line in text.splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) == 2:
            sums[parts[1].lstrip("*").strip()] = parts[0].lower()
    return sums


def _safe_member(name: str) -> PurePosixPath:
    path = PurePosixPath(name.replace("\\", "/"))
    if path.is_absolute() or ".." in path.parts or (path.parts and ":" in path.parts[0]):
        raise RuntimeError(f"压缩包包含不安全的路径: {name}")
    return path


def extract_archive(archive: Path, target: Path, strip_top_level: bool = False) -> None:
    """Extracts .zip/.tar.gz into target, refusing absolute paths, '..' and links that escape."""
    with tempfile.TemporaryDirectory(dir=target.parent if target.parent.exists() else None) as tmp:
        staging = Path(tmp) / "x"
        staging.mkdir()
        name = archive.name.lower()
        if name.endswith(".zip"):
            with zipfile.ZipFile(archive) as bundle:
                for member in bundle.infolist():
                    _safe_member(member.filename)
                bundle.extractall(staging)
                for member in bundle.infolist():
                    mode = (member.external_attr >> 16) & 0o777
                    if mode and not member.is_dir():
                        os.chmod(staging / member.filename, mode)
        else:
            with tarfile.open(archive) as bundle:
                for member in bundle.getmembers():
                    _safe_member(member.name)
                    if member.issym() or member.islnk():
                        link = PurePosixPath(member.name).parent / member.linkname
                        if member.linkname.startswith("/") or ".." in PurePosixPath(os.path.normpath(str(link))).parts:
                            raise RuntimeError(f"压缩包包含越界链接: {member.name}")
                    if member.isdev():
                        raise RuntimeError(f"压缩包包含设备文件: {member.name}")
                if hasattr(tarfile, "data_filter"):
                    bundle.extractall(staging, filter="data")
                else:
                    bundle.extractall(staging)
        source = staging
        if strip_top_level:
            entries = [entry for entry in staging.iterdir() if not entry.name.startswith(".")]
            if len(entries) == 1 and entries[0].is_dir():
                source = entries[0]
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            shutil.rmtree(target)
        shutil.move(str(source), str(target))


# ------------------------------------------------------------------ release layout

RELEASE_DOCS = (".env.example", "LICENSE", "README.md", "README.zh-CN.md", "THIRD_PARTY_NOTICES.md")


def stage_release(repo: Path, version: str, out_dir: Path) -> Path:
    """Copies built artifacts of a source checkout into paper-translator-<version>/."""
    jar = repo / "backend" / "target" / "paper-translator.jar"
    dist = repo / "frontend" / "dist" / "index.html"
    for required in (jar, dist):
        if not required.is_file():
            raise RuntimeError(f"缺少构建产物: {required}（先运行 mvn package 和 npm run build）")
    root = out_dir / f"paper-translator-{version}"
    if root.exists():
        shutil.rmtree(root)
    (root / "frontend").mkdir(parents=True)
    shutil.copy2(jar, root / "paper-translator.jar")
    shutil.copytree(repo / "frontend" / "dist", root / "frontend" / "dist")
    scripts = root / "scripts"
    scripts.mkdir()
    for script in sorted((repo / "backend" / "scripts").glob("*.py")):
        if not script.name.startswith("test_"):
            shutil.copy2(script, scripts / script.name)
    shutil.copytree(repo / "backend" / "scripts" / "tiktoken-cache", scripts / "tiktoken-cache")
    shutil.copytree(repo / "cli", root / "cli",
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "tests", "dist", ".venv", "uv.lock"))
    for doc in RELEASE_DOCS:
        if (repo / doc).is_file():
            shutil.copy2(repo / doc, root / doc)
    (root / "VERSION").write_text(version + "\n", encoding="utf-8")
    return root


def _members(root: Path) -> Iterable[Path]:
    return sorted(path for path in root.rglob("*"))


def write_archives(root: Path, out_dir: Path) -> List[Path]:
    """Writes .tar.gz and .zip of the staged directory plus SHA256SUMS.txt."""
    base = root.name
    tar_path = out_dir / f"{base}.tar.gz"
    zip_path = out_dir / f"{base}.zip"
    with tarfile.open(tar_path, "w:gz") as bundle:
        bundle.add(root, arcname=base)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as bundle:
        for path in _members(root):
            arcname = f"{base}/{path.relative_to(root).as_posix()}"
            if path.is_dir():
                continue
            info = zipfile.ZipInfo.from_file(path, arcname)
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(path, "rb") as handle:
                bundle.writestr(info, handle.read())
    sums = io.StringIO()
    for archive in (tar_path, zip_path):
        sums.write(f"{sha256(archive)}  {archive.name}\n")
    sums_path = out_dir / "SHA256SUMS.txt"
    sums_path.write_text(sums.getvalue(), encoding="utf-8")
    return [tar_path, zip_path, sums_path]


def main(argv=None) -> int:
    """python -m paper_translator_cli.archive <repo> <version> <out-dir>"""
    import sys
    args = list(sys.argv[1:] if argv is None else argv)
    if len(args) != 3:
        print("usage: python -m paper_translator_cli.archive <repo> <version> <out-dir>")
        return 2
    repo, version, out_dir = Path(args[0]).resolve(), args[1], Path(args[2]).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    root = stage_release(repo, version, out_dir)
    for path in write_archives(root, out_dir):
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
