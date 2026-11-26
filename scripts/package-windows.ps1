param(
    [ValidateSet('Installer', 'Portable')][string]$Format = 'Installer',
    [string]$WixBin
)
$ErrorActionPreference = 'Stop'
if (-not $IsWindows -or [Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw 'Build the Windows x64 edition on Windows x64 with PowerShell 7.'
}
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$version = (Select-String -LiteralPath (Join-Path $repo 'gradle.properties') -Pattern '^releaseVersion=(.+)$').Matches[0].Groups[1].Value
$pack = Join-Path $env:JAVA_HOME 'bin/jpackage.exe'
if (-not (Test-Path -LiteralPath $pack)) { throw 'Set JAVA_HOME to a JDK 17 installation with jpackage.' }
$stage = Join-Path $repo ('desktop/build/packaging/' + [guid]::NewGuid().ToString('N'))
$dist = Join-Path $repo 'dist'
New-Item -ItemType Directory -Force -Path $stage,$dist | Out-Null
Push-Location $repo
try {
    if ($Format -eq 'Installer') {
        if ($WixBin) { $env:PATH = (Resolve-Path -LiteralPath $WixBin).Path + ';' + $env:PATH }
        if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue) -or -not (Get-Command light.exe -ErrorAction SilentlyContinue)) {
            throw 'Pass -WixBin pointing to WiX 3 binaries, or choose -Format Portable.'
        }
    }
    & .\gradlew.bat :desktop:installDist
    if ($LASTEXITCODE) { throw 'Desktop build failed.' }
    $product = '循页'
    $image = Join-Path $stage $product
    $modules = 'java.base,java.desktop,java.sql,java.logging,java.management,java.naming,java.prefs,java.xml,java.net.http,java.security.jgss,jdk.crypto.ec,jdk.unsupported,jdk.charsets,jdk.localedata'
    & $pack --type app-image --dest $stage --name $product --app-version $version --vendor PageReprise --description '循页错题本' --input 'desktop/build/install/desktop/lib' --main-jar desktop.jar --main-class app.pagereprise.DesktopKt --icon 'desktop/package/bookmark.ico' --add-modules $modules --java-options '-Dfile.encoding=UTF-8'
    if ($LASTEXITCODE) { throw 'Windows application image failed.' }
    if ($Format -eq 'Portable') {
        $output = Join-Path $dist "PageReprise-$version-Windows-x64.zip"
        & tar.exe -a -cf $output -C $stage $product
        if ($LASTEXITCODE) { throw 'Creating the portable ZIP failed.' }
    } else {
        & $pack --type exe --dest $stage --name $product --app-version $version --vendor PageReprise --app-image $image --icon 'desktop/package/bookmark.ico' --win-per-user-install --install-dir 'PageRepriseDesktop' --win-shortcut --win-menu --win-menu-group $product --win-upgrade-uuid 'dcf346c4-c09b-43fd-9589-8525ce79174c'
        if ($LASTEXITCODE) { throw 'Windows installer build failed.' }
        $output = Join-Path $dist "PageReprise-$version-Windows-x64.exe"
        Copy-Item -LiteralPath (Join-Path $stage "$product-$version.exe") -Destination $output -Force
    }
    Get-FileHash -LiteralPath $output -Algorithm SHA256
} finally { Pop-Location }
