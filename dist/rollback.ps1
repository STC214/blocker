param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$patch = Join-Path $PSScriptRoot 'source.patch'
Push-Location $repo
try {
    & git apply --reverse --check --whitespace=nowarn $patch
    if ($LASTEXITCODE -ne 0) { throw 'Source differs from this release; rollback stopped without changes.' }
    Write-Output 'ROLLBACK_SOURCE_CHECK=PASS'
    if ($Apply) {
        & git apply --reverse --whitespace=nowarn $patch
        if ($LASTEXITCODE -ne 0) { throw 'Source rollback failed.' }
        Write-Output 'ROLLBACK_SOURCE_APPLIED=PASS'
    }
    Write-Output 'No installed apps, app settings, signing keys or system IFW rules were changed.'
} finally { Pop-Location }
