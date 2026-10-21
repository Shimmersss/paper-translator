"""Where things live: the install home, the application files and saved choices."""

from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


def home_dir() -> Path:
    """Install home: app files, portable JDK, .env and data. Override with PAPER_TRANSLATOR_HOME."""
    configured = os.environ.get("PAPER_TRANSLATOR_HOME")
    if configured:
        return Path(configured).expanduser().resolve()
    if sys.platform == "win32":
        base = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
        return Path(base) / "paper-translator"
    return Path.home() / ".paper-translator"


@dataclass(frozen=True)
class Layout:
    """Application files, either a source checkout or an unpacked release."""

    root: Path
    mode: str  # "source" or "release"

    @property
    def jar(self) -> Path:
        if self.mode == "source":
            return self.root / "backend" / "target" / "paper-translator.jar"
        return self.root / "paper-translator.jar"

    @property
    def dist(self) -> Path:
        return self.root / "frontend" / "dist"

    @property
    def scripts(self) -> Path:
        return self.root / ("backend/scripts" if self.mode == "source" else "scripts")

    @property
    def env_example(self) -> Path:
        return self.root / ".env.example"

    @property
    def cli_dir(self) -> Path:
        return self.root / "cli"

    def version(self) -> str:
        if self.mode == "release":
            marker = self.root / "VERSION"
            return marker.read_text(encoding="utf-8").strip() if marker.is_file() else "unknown"
        pom = self.root / "backend" / "pom.xml"
        if pom.is_file():
            match = re.search(r"<artifactId>paper-translator</artifactId>\s*<version>([^<]+)</version>",
                              pom.read_text(encoding="utf-8"))
            if match:
                return match.group(1).strip()
        return "unknown"

    @staticmethod
    def detect(root: Path) -> Optional["Layout"]:
        root = root.resolve()
        if (root / "backend" / "pom.xml").is_file():
            return Layout(root, "source")
        if (root / "paper-translator.jar").is_file() or (root / "VERSION").is_file():
            return Layout(root, "release")
        return None


@dataclass
class Home:
    """The install home and the choices saved by `setup`."""

    path: Path

    @property
    def config_file(self) -> Path:
        return self.path / "config.json"

    @property
    def env_file(self) -> Path:
        return self.path / ".env"

    @property
    def data(self) -> Path:
        return self.path / "data"

    @property
    def storage(self) -> Path:
        return self.data / "translation-tasks"

    @property
    def settings_file(self) -> Path:
        return self.data / "settings.json"

    @property
    def logs(self) -> Path:
        return self.data / "logs"

    @property
    def log_file(self) -> Path:
        return self.logs / "backend.log"

    @property
    def pid_file(self) -> Path:
        return self.data / "paper-translator.pid"

    @property
    def app_dir(self) -> Path:
        return self.path / "app"

    @property
    def jdk_dir(self) -> Path:
        return self.path / "jdk"

    def load(self) -> dict:
        try:
            return json.loads(self.config_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}

    def save(self, config: dict) -> None:
        self.path.mkdir(parents=True, exist_ok=True)
        self.config_file.write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def layout(self) -> Optional[Layout]:
        configured = self.load().get("app")
        if configured:
            return Layout.detect(Path(configured))
        return Layout.detect(self.app_dir) if self.app_dir.exists() else None

    def port(self) -> int:
        return int(self.load().get("port", 8080))


def update_env_file(path: Path, updates: dict) -> None:
    """Sets KEY=value lines, keeping comments and other keys; the file is owner-only."""
    lines = path.read_text(encoding="utf-8").splitlines() if path.is_file() else []
    remaining = dict(updates)
    for index, line in enumerate(lines):
        key = line.split("=", 1)[0].strip()
        if not line.lstrip().startswith("#") and "=" in line and key in remaining:
            lines[index] = f"{key}={remaining.pop(key)}"
    lines.extend(f"{key}={value}" for key, value in remaining.items())
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    restrict_to_owner(path)


def read_env_file(path: Path) -> dict:
    values = {}
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def restrict_to_owner(path: Path) -> None:
    if os.name == "posix":
        os.chmod(path, 0o600)
