# Paper Translator installer for Windows (PowerShell 5.1 or later).
#
#   irm https://raw.githubusercontent.com/Shimmersss/paper-translator/main/install.ps1 | iex
#
# To pass options to `paper-translator setup`:
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/Shimmersss/paper-translator/main/install.ps1))) --install-tesseract
#
# It installs uv if needed, installs the `paper-translator` command from the latest
# GitHub release, then runs `paper-translator setup`, which installs a JDK if none is
# found, downloads the application and verifies its checksum.
$ErrorActionPreference = 'Stop'
$Repo = 'Shimmersss/paper-translator'

if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
    Write-Host '==> Installing uv'
    powershell -NoProfile -ExecutionPolicy ByPass -Command "irm https://astral.sh/uv/install.ps1 | iex"
    $env:Path = "$env:USERPROFILE\.local\bin;$env:Path"
}

# Testing hook: install the command from a branch, tag or commit instead of the latest release.
$Ref = $env:PAPER_TRANSLATOR_REF
$Release = $null
if (-not $Ref) { try {
    $Release = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest" `
        -Headers @{ 'User-Agent' = 'paper-translator-installer' }
} catch {
    $Release = $null
} }

if ($Ref) {
    $Spec = "git+https://github.com/$Repo@$Ref#subdirectory=cli"
} elseif ($Release) {
    $Version = $Release.tag_name.TrimStart('v')
    $Spec = "paper-translator @ https://github.com/$Repo/releases/download/$($Release.tag_name)/paper_translator-$Version-py3-none-any.whl"
} else {
    Write-Host '==> No release yet; installing the command from the main branch (needs git)'
    $Spec = "git+https://github.com/$Repo#subdirectory=cli"
}

Write-Host '==> Installing the paper-translator command'
uv tool install --force $Spec
if ($LASTEXITCODE -ne 0) { throw 'uv tool install failed' }
uv tool update-shell | Out-Null
$Bin = (uv tool dir --bin).Trim()
& (Join-Path $Bin 'paper-translator.exe') setup @args
exit $LASTEXITCODE
