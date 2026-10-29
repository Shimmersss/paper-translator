"""Mainland China download mirrors.

GitHub, PyPI and python.org downloads are slow or unreliable from mainland China.
In "cn" mode the CLI fetches Python packages from the Aliyun PyPI mirror, Python
interpreters from npmmirror and JDKs from TUNA/NJU. Releases still come from
GitHub. Every file is checked against its published SHA-256, and each step falls
back to the original source if the mirror does not have the file.

The mode comes from PAPER_TRANSLATOR_MIRROR, then config.json ("mirror"), then
"auto", which uses mirrors only when the GitHub API cannot be reached.
"""

from __future__ import annotations

import os
import urllib.request
from typing import Dict, List, Optional

MODES = ("auto", "cn", "off")
ENV_VAR = "PAPER_TRANSLATOR_MIRROR"

PYPI_INDEX = "https://mirrors.aliyun.com/pypi/simple/"
PYTHON_INSTALL_MIRROR = "https://registry.npmmirror.com/-/binary/python-build-standalone"
JDK_MIRRORS = ("https://mirrors.tuna.tsinghua.edu.cn/Adoptium", "https://mirror.nju.edu.cn/adoptium")

# uv settings a user may already have pointed somewhere else; never override those.
_UV_INDEX_VARS = ("UV_DEFAULT_INDEX", "UV_INDEX_URL", "UV_INDEX", "PIP_INDEX_URL")


def requested(config: Optional[dict] = None) -> str:
    value = (os.environ.get(ENV_VAR) or (config or {}).get("mirror") or "auto").strip().lower()
    return value if value in MODES else "auto"


def github_reachable(timeout: float = 5.0) -> bool:
    request = urllib.request.Request("https://api.github.com/", method="HEAD",
                                     headers={"User-Agent": "paper-translator-cli"})
    try:
        with urllib.request.urlopen(request, timeout=timeout):
            return True
    except Exception:
        return False


def resolve(mode: str) -> str:
    """Turns "auto" into "cn" or "off" by probing GitHub; other modes pass through."""
    if mode == "auto":
        return "off" if github_reachable() else "cn"
    return mode


def enabled(config: Optional[dict] = None) -> bool:
    """For commands that should not probe the network (start, doctor): auto counts as off."""
    return requested(config) == "cn"


def uv_env(active: bool, environ: Optional[Dict[str, str]] = None) -> Dict[str, str]:
    """Environment for uv: Aliyun PyPI and npmmirror Python builds, unless already configured."""
    if not active:
        return {}
    environ = os.environ if environ is None else environ
    env: Dict[str, str] = {}
    if not any(environ.get(name) for name in _UV_INDEX_VARS):
        env["UV_DEFAULT_INDEX"] = PYPI_INDEX
    if not environ.get("UV_PYTHON_INSTALL_MIRROR"):
        env["UV_PYTHON_INSTALL_MIRROR"] = PYTHON_INSTALL_MIRROR
    return env


def jdk_urls(feature: int, arch: str, system: str, name: str) -> List[str]:
    """Mirror URLs for an Adoptium package, in the mirrors' <feature>/jdk/<arch>/<os>/ layout."""
    return [f"{base}/{feature}/jdk/{arch}/{system}/{name}" for base in JDK_MIRRORS]

