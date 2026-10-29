#!/bin/sh
# Paper Translator installer for macOS and Linux.
#
#   curl -fsSL https://raw.githubusercontent.com/Shimmersss/paper-translator/main/install.sh | sh
#
# From mainland China, turn on the domestic mirrors:
#   curl -fsSL https://raw.githubusercontent.com/Shimmersss/paper-translator/main/install.sh | PAPER_TRANSLATOR_MIRROR=cn sh
#
# Arguments after `sh -s --` are passed to `paper-translator setup`, for example:
#   curl -fsSL .../install.sh | sh -s -- --non-interactive --install-tesseract
#
# It installs uv if needed, installs the `paper-translator` command from the latest
# release, then runs `paper-translator setup`, which installs a JDK if none is
# found, downloads the application and verifies its checksum.
#
# PAPER_TRANSLATOR_MIRROR: auto (default; mirrors only when GitHub is unreachable),
# cn (Aliyun PyPI, npmmirror Python builds, TUNA/NJU JDK; releases still from GitHub) or off.
set -eu

REPO="Shimmersss/paper-translator"
PYPI_MIRROR="https://mirrors.aliyun.com/pypi/simple/"
PYTHON_MIRROR="https://registry.npmmirror.com/-/binary/python-build-standalone"
# Testing hook: install the command from a branch, tag or commit instead of the latest release.
REF="${PAPER_TRANSLATOR_REF:-}"

MIRROR="${PAPER_TRANSLATOR_MIRROR:-auto}"
if [ "$MIRROR" = auto ]; then
  if curl -fsS -o /dev/null --max-time 5 https://api.github.com/ 2>/dev/null; then
    MIRROR=off
  else
    MIRROR=cn
    echo "==> GitHub is unreachable; using mainland China mirrors"
  fi
fi
# `paper-translator setup` reads this and remembers the choice.
PAPER_TRANSLATOR_MIRROR="$MIRROR"
export PAPER_TRANSLATOR_MIRROR

if [ "$MIRROR" = cn ]; then
  if [ -z "${UV_DEFAULT_INDEX:-}${UV_INDEX_URL:-}${UV_INDEX:-}${PIP_INDEX_URL:-}" ]; then
    UV_DEFAULT_INDEX="$PYPI_MIRROR"
    export UV_DEFAULT_INDEX
  fi
  UV_PYTHON_INSTALL_MIRROR="${UV_PYTHON_INSTALL_MIRROR:-$PYTHON_MIRROR}"
  export UV_PYTHON_INSTALL_MIRROR
fi

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# uv's own installer downloads from GitHub. In mirror mode take the uv binary out of
# its PyPI wheel instead, checked against the hash the index publishes.
install_uv_from_pypi_mirror() {
  case "$(uname -s)/$(uname -m)" in
    Darwin/arm64) tag='macosx_[0-9_]*_arm64' ;;
    Darwin/x86_64) tag='macosx_[0-9_]*_x86_64' ;;
    Linux/x86_64|Linux/amd64)
      if ldd --version 2>&1 | grep -qi musl; then tag='musllinux_1_1_x86_64'
      else tag='manylinux_2_17_x86_64[a-z0-9_.]*'; fi ;;
    Linux/aarch64|Linux/arm64) tag='manylinux_2_17_aarch64[a-z0-9_.]*' ;;
    *) return 1 ;;
  esac
  tmp=$(mktemp -d)
  href=$(curl -fsSL --max-time 60 "${PYPI_MIRROR}uv/" \
    | grep -oE "href=\"[^\"]*/uv-[0-9.]+-py3-none-${tag}\\.whl#sha256=[0-9a-f]{64}\"" \
    | tail -n 1 | sed 's/^href="//; s/"$//') || { rm -rf "$tmp"; return 1; }
  [ -n "$href" ] || { rm -rf "$tmp"; return 1; }
  hash=${href##*#sha256=}
  path=${href%%#*}
  case "$path" in
    http*) url="$path" ;;
    *) url="${PYPI_MIRROR}uv/$path" ;;
  esac
  curl -fsSL --max-time 300 "$url" -o "$tmp/uv.whl" || { rm -rf "$tmp"; return 1; }
  [ "$(sha256_of "$tmp/uv.whl")" = "$hash" ] || { echo "uv wheel checksum mismatch" >&2; rm -rf "$tmp"; return 1; }
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$tmp/uv.whl" -d "$tmp/x"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -m zipfile -e "$tmp/uv.whl" "$tmp/x"
  elif [ "$(uname -s)" = Darwin ]; then
    mkdir -p "$tmp/x" && tar -xf "$tmp/uv.whl" -C "$tmp/x"
  else
    rm -rf "$tmp"; return 1
  fi
  mkdir -p "$HOME/.local/bin"
  for name in uv uvx; do
    found=$(find "$tmp/x" -path "*/scripts/$name" -type f | head -n 1)
    [ -n "$found" ] && cp "$found" "$HOME/.local/bin/$name" && chmod 755 "$HOME/.local/bin/$name"
  done
  rm -rf "$tmp"
  [ -x "$HOME/.local/bin/uv" ]
}

if ! command -v uv >/dev/null 2>&1; then
  echo "==> Installing uv"
  if [ "$MIRROR" = cn ] && install_uv_from_pypi_mirror; then
    :
  else
    [ "$MIRROR" = cn ] && echo "==> Mirror install of uv failed; using the official installer"
    curl -LsSf https://astral.sh/uv/install.sh | sh
  fi
  PATH="$HOME/.local/bin:$HOME/.cargo/bin:$PATH"
  export PATH
fi

latest_tag() {
  # $1: API URL. Prints the tag of the latest release, or nothing.
  curl -fsSL --max-time 20 -H "User-Agent: paper-translator-installer" "$1" 2>/dev/null \
    | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -n 1 || true
}

# Candidate sources for the command, tried in order.
SPECS=""
add_spec() { SPECS="$SPECS
$1"; }
if [ -n "$REF" ]; then
  add_spec "git+https://github.com/$REPO@$REF#subdirectory=cli"
else
  TAG=$(latest_tag "https://api.github.com/repos/$REPO/releases/latest")
  [ -n "$TAG" ] && add_spec "paper-translator @ https://github.com/$REPO/releases/download/$TAG/paper_translator-${TAG#v}-py3-none-any.whl"
  if [ -z "$SPECS" ]; then
    echo "==> No release found; installing the command from the main branch (needs git)"
    add_spec "git+https://github.com/$REPO#subdirectory=cli"
  fi
fi

echo "==> Installing the paper-translator command"
INSTALLED=""
OLD_IFS=$IFS
IFS='
'
for SPEC in $SPECS; do
  [ -n "$SPEC" ] || continue
  if uv tool install --force "$SPEC"; then INSTALLED=1; break; fi
  echo "==> Could not install from $SPEC; trying the next source"
done
IFS=$OLD_IFS
[ -n "$INSTALLED" ] || { echo "Failed to install the paper-translator command" >&2; exit 1; }
uv tool update-shell >/dev/null 2>&1 || true
BIN=$(uv tool dir --bin 2>/dev/null || echo "$HOME/.local/bin")

# Piped through `sh`, stdin is the script itself; read answers from the terminal instead.
if [ -t 1 ] && (exec </dev/tty) 2>/dev/null; then
  exec "$BIN/paper-translator" setup "$@" </dev/tty
fi
exec "$BIN/paper-translator" setup "$@"
