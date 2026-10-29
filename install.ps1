# Paper Translator installer for Windows (PowerShell 5.1 or later).
#
#   irm https://raw.githubusercontent.com/Shimmersss/paper-translator/main/install.ps1 | iex
#
# From mainland China, turn on the domestic mirrors:
#   $env:PAPER_TRANSLATOR_MIRROR='cn'; irm https://raw.githubusercontent.com/Shimmersss/paper-translator/main/install.ps1 | iex
#
# To pass options to `paper-translator setup`:
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/Shimmersss/paper-translator/main/install.ps1))) --install-tesseract
#
# It installs uv if needed, installs the `paper-translator` command from the latest
# release, then runs `paper-translator setup`, which installs a JDK if none is
# found, downloads the application and verifies its checksum.
#
# PAPER_TRANSLATOR_MIRROR: auto (default; mirrors only when GitHub is unreachable),
# cn (Aliyun PyPI, npmmirror Python builds, TUNA/NJU JDK; releases still from GitHub) or off.
$ErrorActionPreference = 'Stop'
$Repo = 'Shimmersss/paper-translator'
$PypiMirror = 'https://mirrors.aliyun.com/pypi/simple/'
$PythonMirror = 'https://registry.npmmirror.com/-/binary/python-build-standalone'
$Headers = @{ 'User-Agent' = 'paper-translator-installer' }
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Mirror = if ($env:PAPER_TRANSLATOR_MIRROR) { $env:PAPER_TRANSLATOR_MIRROR.ToLower() } else { 'auto' }
if ($Mirror -eq 'auto') {
    try {
        Invoke-WebRequest 'https://api.github.com/' -Method Head -TimeoutSec 5 -UseBasicParsing -Headers $Headers | Out-Null
        $Mirror = 'off'
    } catch {
        $Mirror = 'cn'
        Write-Host '==> GitHub is unreachable; using mainland China mirrors'
    }
}
# `paper-translator setup` reads this and remembers the choice.
$env:PAPER_TRANSLATOR_MIRROR = $Mirror
if ($Mirror -eq 'cn') {
    if (-not ($env:UV_DEFAULT_INDEX -or $env:UV_INDEX_URL -or $env:UV_INDEX -or $env:PIP_INDEX_URL)) {
        $env:UV_DEFAULT_INDEX = $PypiMirror
    }
    if (-not $env:UV_PYTHON_INSTALL_MIRROR) { $env:UV_PYTHON_INSTALL_MIRROR = $PythonMirror }
}

# uv's own installer downloads from GitHub. In mirror mode take uv.exe out of its PyPI
# wheel instead, checked against the hash the index publishes.
function Install-UvFromPypiMirror {
    $Tag = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'win_arm64' } else { 'win_amd64' }
    $Index = (Invoke-WebRequest "${PypiMirror}uv/" -UseBasicParsing -TimeoutSec 60).Content
    $Matches_ = [regex]::Matches($Index, 'href="([^"]*/uv-[0-9.]+-py3-none-' + $Tag + '\.whl)#sha256=([0-9a-f]{64})"')
    if ($Matches_.Count -eq 0) { return $false }
    $Last = $Matches_[$Matches_.Count - 1]
    $Path = $Last.Groups[1].Value
    $Hash = $Last.Groups[2].Value
    $Url = if ($Path -match '^https?://') { $Path } else { [Uri]::new([Uri]"${PypiMirror}uv/", $Path).AbsoluteUri }
    $Tmp = Join-Path ([IO.Path]::GetTempPath()) ("uv-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $Tmp | Out-Null
    try {
        $Zip = Join-Path $Tmp 'uv.zip'
        Invoke-WebRequest $Url -OutFile $Zip -UseBasicParsing -TimeoutSec 300
        if ((Get-FileHash $Zip -Algorithm SHA256).Hash.ToLower() -ne $Hash) { Write-Host 'uv wheel checksum mismatch'; return $false }
        Expand-Archive $Zip -DestinationPath (Join-Path $Tmp 'x')
        $Bin = Join-Path $env:USERPROFILE '.local\bin'
        New-Item -ItemType Directory -Force -Path $Bin | Out-Null
        foreach ($Name in 'uv.exe', 'uvx.exe') {
            $Found = Get-ChildItem (Join-Path $Tmp 'x') -Recurse -Filter $Name | Where-Object { $_.DirectoryName -like '*scripts' } | Select-Object -First 1
            if ($Found) { Copy-Item $Found.FullName (Join-Path $Bin $Name) -Force }
        }
        return (Test-Path (Join-Path $Bin 'uv.exe'))
    } finally {
        Remove-Item $Tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
    Write-Host '==> Installing uv'
    $Done = $false
    if ($Mirror -eq 'cn') {
        try { $Done = Install-UvFromPypiMirror } catch { $Done = $false }
        if (-not $Done) { Write-Host '==> Mirror install of uv failed; using the official installer' }
    }
    if (-not $Done) {
        powershell -NoProfile -ExecutionPolicy ByPass -Command "irm https://astral.sh/uv/install.ps1 | iex"
    }
    $env:Path = "$env:USERPROFILE\.local\bin;$env:Path"
}

function Get-LatestTag([string]$Url) {
    try { return (Invoke-RestMethod $Url -Headers $Headers -TimeoutSec 20).tag_name } catch { return $null }
}

# Candidate sources for the command, tried in order.
# Testing hook: PAPER_TRANSLATOR_REF installs the command from a branch, tag or commit.
$Specs = @()
if ($env:PAPER_TRANSLATOR_REF) {
    $Specs += "git+https://github.com/$Repo@$($env:PAPER_TRANSLATOR_REF)#subdirectory=cli"
} else {
    $Tag = Get-LatestTag "https://api.github.com/repos/$Repo/releases/latest"
    if ($Tag) { $Specs += "paper-translator @ https://github.com/$Repo/releases/download/$Tag/paper_translator-$($Tag.TrimStart('v'))-py3-none-any.whl" }
    if ($Specs.Count -eq 0) {
        Write-Host '==> No release found; installing the command from the main branch (needs git)'
        $Specs += "git+https://github.com/$Repo#subdirectory=cli"
    }
}

Write-Host '==> Installing the paper-translator command'
$Installed = $false
foreach ($Spec in $Specs) {
    uv tool install --force $Spec
    if ($LASTEXITCODE -eq 0) { $Installed = $true; break }
    Write-Host "==> Could not install from $Spec; trying the next source"
}
if (-not $Installed) { throw 'uv tool install failed' }
uv tool update-shell | Out-Null
$Bin = (uv tool dir --bin).Trim()
& (Join-Path $Bin 'paper-translator.exe') setup @args
exit $LASTEXITCODE
