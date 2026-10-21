"""Terminal output and prompts."""

from __future__ import annotations

import getpass
import os
import sys

# Windows consoles and CI logs may default to a legacy code page; never crash on output.
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

_COLOR = sys.stdout.isatty() and os.environ.get("NO_COLOR") is None and os.name != "nt"
INTERACTIVE = sys.stdin.isatty()


def _paint(code: str, text: str) -> str:
    return f"\033[{code}m{text}\033[0m" if _COLOR else text


def step(message: str) -> None:
    print(_paint("1", "==> ") + message, flush=True)


def detail(message: str) -> None:
    print(_paint("2", "    " + message), flush=True)


def ok(message: str) -> None:
    print(_paint("32", "  ✓ ") + message, flush=True)


def warn(message: str) -> None:
    print(_paint("33", "  ! ") + message, flush=True)


def fail(message: str) -> None:
    print(_paint("31", "  ✗ ") + message, file=sys.stderr, flush=True)


def confirm(question: str, default: bool = True) -> bool:
    if not INTERACTIVE:
        return default
    suffix = " [Y/n] " if default else " [y/N] "
    answer = input(question + suffix).strip().lower()
    return default if not answer else answer in ("y", "yes", "是")


def ask(question: str, default: str = "", secret: bool = False) -> str:
    if not INTERACTIVE:
        return default
    shown = f" [{default}]" if default and not secret else ""
    reader = getpass.getpass if secret else input
    answer = reader(f"{question}{shown}: ").strip()
    return answer or default
