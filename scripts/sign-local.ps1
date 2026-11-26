param(
    [Parameter(Mandatory = $true)][string]$UnsignedApk,
    [string]$Output = "dist/PageReprise-0.1.0.apk"
)
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'This helper uses Windows DPAPI. On other systems use your own release signing key.' }
$inputFile = (Resolve-Path -LiteralPath $UnsignedApk).Path
if (-not $env:ANDROID_HOME -or -not $env:JAVA_HOME) { throw 'Set ANDROID_HOME and JAVA_HOME (JDK 17) first.' }
$build = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0'
$keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
$align = Join-Path $build 'zipalign.exe'
$sign = Join-Path $build 'apksigner.bat'
foreach ($tool in @($keytool,$align,$sign)) { if (-not (Test-Path -LiteralPath $tool)) { throw "Missing tool: $tool" } }
$keys = Join-Path $env:LOCALAPPDATA 'PageReprise/signing'
New-Item -ItemType Directory -Force -Path $keys | Out-Null
$store = Join-Path $keys 'page-reprise.p12'
$protected = Join-Path $keys 'pass.dpapi'
$outputFile = [IO.Path]::GetFullPath($Output)
if ($outputFile -eq $inputFile) { throw 'Output cannot overwrite the input.' }
New-Item -ItemType Directory -Force -Path (Split-Path $outputFile) | Out-Null
$aligned = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString() + '.apk')
try {
    if ((Test-Path -LiteralPath $store) -ne (Test-Path -LiteralPath $protected)) { throw 'Incomplete local signing credentials; restore the original key before continuing.' }
    if (-not (Test-Path -LiteralPath $store)) {
        $env:REPRISE_LOCAL_PASS = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(36))
        $env:REPRISE_LOCAL_PASS | ConvertTo-SecureString -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $protected
        & $keytool -genkeypair -storetype PKCS12 -keystore $store -alias reprise -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=PageReprise private distribution' -storepass:env REPRISE_LOCAL_PASS
        if ($LASTEXITCODE -ne 0) { throw 'Key generation failed' }
    } else {
        $secure = (Get-Content -Raw -LiteralPath $protected).Trim() | ConvertTo-SecureString
        $env:REPRISE_LOCAL_PASS = [Net.NetworkCredential]::new('local', $secure).Password
    }
    & $align -f -P 16 4 $inputFile $aligned
    if ($LASTEXITCODE -ne 0) { throw 'Alignment failed' }
    & $sign sign --ks $store --ks-key-alias reprise --ks-pass env:REPRISE_LOCAL_PASS --out $outputFile $aligned
    if ($LASTEXITCODE -ne 0) { throw 'Signing failed' }
    & $sign verify --verbose --print-certs $outputFile
    if ($LASTEXITCODE -ne 0) { throw 'Verification failed' }
    Get-FileHash -LiteralPath $outputFile -Algorithm SHA256
} finally {
    [Environment]::SetEnvironmentVariable('REPRISE_LOCAL_PASS', $null, 'Process')
    if (Test-Path -LiteralPath $aligned) { Remove-Item -LiteralPath $aligned }
}
