$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$config = Get-Content -Raw -LiteralPath (Join-Path $repo 'gradle.properties') | ConvertFrom-StringData
$dist = Join-Path $repo 'dist'
$version = $config.releaseVersion
$keyDir = Join-Path $env:LOCALAPPDATA 'PageReprise/signing'
$pass = (Get-Content -Raw -LiteralPath (Join-Path $keyDir 'pass.dpapi')).Trim() | ConvertTo-SecureString
$cert = [Security.Cryptography.X509Certificates.X509Certificate2]::new((Join-Path $keyDir 'page-reprise.p12'),
    $pass, [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
function Get-Asset([string]$Name) {
    $file = Get-Item -LiteralPath (Join-Path $dist $Name)
    @{ name = $file.Name; size = $file.Length; sha256 = (Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant() }
}
$rsa = [Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPrivateKey($cert)
try {
    $pinned = [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo())
    $keySource = Get-Content -Raw -LiteralPath (Join-Path $repo 'core/src/main/kotlin/app/pagereprise/core/Release.kt')
    if (!$keySource.Contains('SIGNING_KEY = "' + $pinned + '"')) { throw 'Restore the original release key.' }
    $android = Get-Asset "PageReprise-$version.apk"
    $windows = Get-Asset "PageReprise-$version-Windows-x64.exe"
    $payload = @{ product = 'app.pagereprise'; format = 1; version = $version; androidCode = [int]$config.androidVersionCode
        android = $android; windows = $windows } | ConvertTo-Json -Depth 4 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($payload)
    $sha = [Security.Cryptography.HashAlgorithmName]::SHA256
    $padding = [Security.Cryptography.RSASignaturePadding]::Pkcs1
    $signature = $rsa.SignData($bytes, $sha, $padding)
    if (!$rsa.VerifyData($bytes, $signature, $sha, $padding)) { throw 'Manifest signature verification failed.' }
    $envelope = @{ payload = [Convert]::ToBase64String($bytes); signature = [Convert]::ToBase64String($signature) }
    $path = Join-Path $dist 'PageReprise-update.json'
    [IO.File]::WriteAllText($path, ($envelope | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
    Get-FileHash -LiteralPath $path -Algorithm SHA256
} finally {
    $rsa.Dispose()
    $cert.Dispose()
    $pass.Dispose()
}
