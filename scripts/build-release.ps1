param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$SigningDirectory = (Join-Path $env:USERPROFILE '.android-signing\blocker'),
    [switch]$InitializeSigning,
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$key = Join-Path $SigningDirectory 'blocker-release.jks'
$credentials = Join-Path $SigningDirectory 'credentials.xml'
if (!$JavaHome -or !$AndroidHome) { throw 'Set JAVA_HOME and ANDROID_HOME first.' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidHome
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
$signer = Join-Path $AndroidHome 'build-tools\36.0.0\apksigner.bat'
$aapt = Join-Path $AndroidHome 'build-tools\36.0.0\aapt.exe'
if ($InitializeSigning) {
    if ((Test-Path -LiteralPath $key) -or (Test-Path -LiteralPath $credentials)) {
        throw 'Signing identity already exists; never overwrite a release key.'
    }
    New-Item -ItemType Directory -Path $SigningDirectory -Force | Out-Null
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $SigningDirectory -AclObject $acl
    $bytes = New-Object byte[] 48
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $secure = ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
    $credential = New-Object System.Management.Automation.PSCredential('blocker-release', $secure)
    $credential | Export-Clixml -LiteralPath $credentials
    $env:BLOCKER_KEY_PASSWORD = $credential.GetNetworkCredential().Password
    try {
        & $keytool -genkeypair -keystore $key -storetype JKS -alias blocker-release -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 -dname 'CN=STC214 Blocker Release' -storepass:env BLOCKER_KEY_PASSWORD -keypass:env BLOCKER_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw "keytool exit $LASTEXITCODE" }
    } finally { $env:BLOCKER_KEY_PASSWORD = $null }
}
if (!(Test-Path -LiteralPath $key)) { throw "Missing release key: $key" }
$credential = Import-Clixml -LiteralPath $credentials
$env:ORG_GRADLE_PROJECT_releaseStoreFile = $key
$env:ORG_GRADLE_PROJECT_releaseKeyAlias = $credential.UserName
$env:ORG_GRADLE_PROJECT_releaseStorePassword = $credential.GetNetworkCredential().Password
$env:ORG_GRADLE_PROJECT_releaseKeyPassword = $env:ORG_GRADLE_PROJECT_releaseStorePassword
$dist = Join-Path $repo 'dist'
New-Item -ItemType Directory -Path $dist -Force | Out-Null
Push-Location $repo
try {
    if (!$VerifyOnly) {
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat :app-compose:assembleFossRelease --console=plain --no-configuration-cache 2>&1 | Tee-Object -FilePath (Join-Path $dist 'build.log')
        $code = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($code -ne 0) { throw "Release build exit $code" }
    }
    $output = Join-Path $repo 'app-compose\build\outputs\apk\foss\release'
    $metadata = Get-Content -LiteralPath (Join-Path $output 'output-metadata.json') -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne 'com.stc214.blocker' -or $metadata.elements.Count -ne 1) {
        throw 'Unexpected release identity or output count.'
    }
    $element = $metadata.elements[0]
    $apk = Join-Path $dist "blocker-$($element.versionName)-foss-release.apk"
    Copy-Item -LiteralPath (Join-Path $output $element.outputFile) -Destination $apk -Force
    $signature = & $signer verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $certificate = Join-Path $dist 'release-certificate.der'
    & $keytool -exportcert -keystore $key -storepass:env ORG_GRADLE_PROJECT_releaseStorePassword -alias $credential.UserName -file $certificate
    if ($LASTEXITCODE -ne 0) { throw 'Certificate export failed.' }
    $certificateHash = (Get-FileHash -LiteralPath $certificate -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($signature -notcontains "Signer #1 certificate SHA-256 digest: $certificateHash") {
        throw 'APK signer does not match the independent release key.'
    }
    $signature | Set-Content -LiteralPath (Join-Path $dist 'signature.txt')
    $badging = & $aapt dump badging $apk
    if ($LASTEXITCODE -ne 0 -or ($badging -match 'application-debuggable')) { throw 'Invalid or debuggable release.' }
    $badging | Set-Content -LiteralPath (Join-Path $dist 'badging.txt')
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($apk))" | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Encoding ascii
    Write-Output "RELEASE_APK=$apk"
    Write-Output "SHA256=$hash"
    Write-Output "SIGNING_DIRECTORY=$SigningDirectory"
} finally {
    Pop-Location
    $env:ORG_GRADLE_PROJECT_releaseStoreFile = $null
    $env:ORG_GRADLE_PROJECT_releaseKeyAlias = $null
    $env:ORG_GRADLE_PROJECT_releaseStorePassword = $null
    $env:ORG_GRADLE_PROJECT_releaseKeyPassword = $null
}
