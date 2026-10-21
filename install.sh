#!/bin/sh
# Paper Translator installer for macOS and Linux.
#
#   curl -fsSL https://raw.githubusercontent.com/Shimmersss/paper-translator/main/install.sh | sh
#
# Arguments after `sh -s --` are passed to `paper-translator setup`, for example:
#   curl -fsSL .../install.sh | sh -s -- --non-interactive --install-tesseract
#
# It installs uv if needed, installs the `paper-translator` command from the latest
# GitHub release, then runs `paper-translator setup`, which installs a JDK if none is
# found, downloads the application and verifies its checksum.
set -eu

REPO="Shimmersss/paper-translator"
# Testing hook: install the command from a branch, tag or commit instead of the latest release.
REF="${PAPER_TRANSLATOR_REF:-}"

if ! command -v uv >/dev/null 2>&1; then
  echo "==> Installing uv"
  curl -LsSf https://astral.sh/uv/install.sh | sh
  PATH="$HOME/.local/bin:$HOME/.cargo/bin:$PATH"
  export PATH
fi

TAG=""
[ -n "$REF" ] || TAG=$(curl -fsSL -H "User-Agent: paper-translator-installer" \
  "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null \
  | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -n 1 || true)

if [ -n "$REF" ]; then
  SPEC="git+https://github.com/$REPO@$REF#subdirectory=cli"
elif [ -n "$TAG" ]; then
  VERSION=${TAG#v}
  SPEC="paper-translator @ https://github.com/$REPO/releases/download/$TAG/paper_translator-$VERSION-py3-none-any.whl"
else
  echo "==> No release yet; installing the command from the main branch (needs git)"
  SPEC="git+https://github.com/$REPO#subdirectory=cli"
fi

echo "==> Installing the paper-translator command"
uv tool install --force "$SPEC"
uv tool update-shell >/dev/null 2>&1 || true
BIN=$(uv tool dir --bin 2>/dev/null || echo "$HOME/.local/bin")

# Piped through `sh`, stdin is the script itself; read answers from the terminal instead.
if [ -t 1 ] && (exec </dev/tty) 2>/dev/null; then
  exec "$BIN/paper-translator" setup "$@" </dev/tty
fi
exec "$BIN/paper-translator" setup "$@"
