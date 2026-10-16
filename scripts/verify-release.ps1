param([switch]$SummarizeOnly, [switch]$RepeatTests)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$dist = Join-Path $repo 'dist'
New-Item -ItemType Directory -Path $dist -Force | Out-Null
Push-Location $repo
try {
    if (!$SummarizeOnly) {
        $ErrorActionPreference = 'Continue'
        $tasks = @('testFossDebugUnitTest')
        if ($RepeatTests) { $tasks += '--rerun' }
        $tasks += @(':app-compose:lintFossRelease', ':app-compose:spotlessCheck', ':core:common:spotlessCheck', ':core:ifw-api:spotlessCheck', ':core:provider:spotlessCheck', ':feature:settings:impl:spotlessCheck')
        & .\gradlew.bat @tasks '-Proborazzi.test.verify=false' --max-workers=4 --continue --console=plain --no-configuration-cache 2>&1 | Tee-Object -FilePath (Join-Path $dist 'regression.log')
        $code = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        "EXIT_CODE=$code" | Add-Content -LiteralPath (Join-Path $dist 'regression.log')
    }
    $results = @(Get-ChildItem -LiteralPath $repo -Recurse -Filter 'TEST-*.xml' | Where-Object { $_.FullName -match '\\build\\test-results\\testFossDebugUnitTest\\' } | ForEach-Object {
        [xml]$xml = Get-Content -LiteralPath $_.FullName -Raw
        [pscustomobject]@{
            suite = $xml.testsuite.name
            tests = [int]$xml.testsuite.tests
            failures = [int]$xml.testsuite.failures
            errors = [int]$xml.testsuite.errors
            skipped = [int]$xml.testsuite.skipped
            report = $_.FullName.Substring($repo.Length + 1)
        }
    })
    $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $dist 'test-results.json') -Encoding UTF8
    $totals = [pscustomobject]@{
        suites = $results.Count
        tests = ($results | Measure-Object tests -Sum).Sum
        failures = ($results | Measure-Object failures -Sum).Sum
        errors = ($results | Measure-Object errors -Sum).Sum
        skipped = ($results | Measure-Object skipped -Sum).Sum
        screenshotBaselineVerification = 'Excluded on Windows; requires Linux'
    }
    $totals | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $dist 'test-summary.json') -Encoding UTF8
    $totals | Format-List
    if (!$SummarizeOnly -and $code -ne 0) { throw "Regression exit $code; inspect dist/regression.log" }
} finally { Pop-Location }
