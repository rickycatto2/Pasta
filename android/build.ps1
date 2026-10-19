param(
    [switch]$SkipSign,
    [switch]$Clean
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw 'ANDROID_HOME is not set.' }
$buildTools = Join-Path $sdk 'build-tools\37.0.0'
$androidJar = Join-Path $sdk 'platforms\android-37.0\android.jar'
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { throw 'JAVA_HOME is not set.' }
if (-not (Test-Path $androidJar)) { throw "Android platform jar not found: $androidJar" }
if (-not (Test-Path (Join-Path $buildTools 'aapt2.exe'))) { throw "aapt2 not found under $buildTools" }

$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

$build = Join-Path $root 'build'
$compiled = Join-Path $build 'res.zip'
$gen = Join-Path $build 'gen'
$classes = Join-Path $build 'classes'
$dexDir = Join-Path $build 'dex'
$unsigned = Join-Path $build 'app-unsigned.apk'
$aligned = Join-Path $build 'app-aligned.apk'
$output = Join-Path $build 'Pasta.apk'

if ($Clean -and (Test-Path $build)) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force $build, $gen, $classes, $dexDir | Out-Null
if (Test-Path $compiled) { Remove-Item -Force $compiled }
if (Test-Path $unsigned) { Remove-Item -Force $unsigned }
if (Test-Path $aligned) { Remove-Item -Force $aligned }
if (Test-Path $output) { Remove-Item -Force $output }

# Optional local default server URL. Keep the real value in server-url.txt
# (ignored by git); the repository has only a generic placeholder.
# The generated resource is always written so the app has a default even on a
# clean clone that has no server-url.txt.
$serverUrlFile = Join-Path $root 'server-url.txt'
$serverLocal = Join-Path $root 'res\values\server_local.xml'
$serverUrl = 'https://pasta.example.com'
if (Test-Path $serverUrlFile) {
    $localUrl = (Get-Content -Raw $serverUrlFile).Trim()
    if ($localUrl) { $serverUrl = $localUrl }
}
$escaped = [System.Security.SecurityElement]::Escape($serverUrl)
$xml = "<?xml version=`"1.0`" encoding=`"utf-8`"?>`r`n<resources>`r`n    <string name=`"default_server_url`" translatable=`"false`">$escaped</string>`r`n</resources>`r`n"
Set-Content -Path $serverLocal -Value $xml -Encoding utf8

Write-Host '1/6  Compiling resources…'
& (Join-Path $buildTools 'aapt2.exe') compile --dir (Join-Path $root 'res') -o $compiled
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed ($LASTEXITCODE)" }

Write-Host '2/6  Linking resources and manifest…'
& (Join-Path $buildTools 'aapt2.exe') link `
    -o $unsigned `
    -I $androidJar `
    --manifest (Join-Path $root 'AndroidManifest.xml') `
    -R $compiled `
    --java $gen `
    --auto-add-overlay `
    --min-sdk-version 26 `
    --target-sdk-version 34 `
    --version-code 1 `
    --version-name '1.0' `
    -A (Join-Path $root 'assets')
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed ($LASTEXITCODE)" }

Write-Host '3/6  Compiling Java…'
$sources = @(Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem $gen -Recurse -Filter R.java | ForEach-Object { $_.FullName })
& (Join-Path $javaHome 'bin\javac.exe') `
    -encoding UTF-8 `
    --release 17 `
    -classpath "$androidJar;$gen" `
    -d $classes `
    $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host '4/6  Dexing…'
$classFiles = @(Get-ChildItem $classes -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& (Join-Path $buildTools 'd8.bat') `
    --release `
    --min-api 26 `
    --lib $androidJar `
    --output $dexDir `
    $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed ($LASTEXITCODE)" }

Write-Host '5/6  Packaging…'
& (Join-Path $javaHome 'bin\jar.exe') uf $unsigned -C $dexDir classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar update failed ($LASTEXITCODE)" }
& (Join-Path $buildTools 'zipalign.exe') -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed ($LASTEXITCODE)" }

if (-not $SkipSign) {
    Write-Host '6/6  Signing…'
    $keystore = Join-Path $root 'pasta-release.jks'
    $propertiesFile = Join-Path $root 'keystore.properties'
    $storePassword = $env:PASTA_KEYSTORE_PASSWORD
    $alias = 'pasta'
    if (Test-Path $propertiesFile) {
        foreach ($line in Get-Content $propertiesFile) {
            $trimmed = $line.Trim()
            if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
            $parts = $trimmed.Split('=', 2)
            if ($parts.Count -ne 2) { continue }
            switch ($parts[0].Trim()) {
                'storePassword' { $storePassword = $parts[1].Trim() }
                'keyAlias' { $alias = $parts[1].Trim() }
            }
        }
    }
    if (-not $storePassword) {
        throw 'Signing password missing. Create android/keystore.properties from keystore.properties.example, or set PASTA_KEYSTORE_PASSWORD.'
    }
    if (-not (Test-Path $keystore)) {
        & (Join-Path $javaHome 'bin\keytool.exe') -genkeypair `
            -v `
            -keystore $keystore `
            -alias $alias `
            -keyalg RSA `
            -keysize 2048 `
            -validity 10000 `
            -storepass $storePassword `
            -keypass $storePassword `
            -dname 'CN=Pasta, O=Pixelwood, C=US'
        if ($LASTEXITCODE -ne 0) { throw "keytool failed ($LASTEXITCODE)" }
    }
    & (Join-Path $buildTools 'apksigner.bat') sign `
        --ks $keystore `
        --ks-key-alias $alias `
        --ks-pass "pass:$storePassword" `
        --key-pass "pass:$storePassword" `
        --out $output `
        $aligned
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed ($LASTEXITCODE)" }
    & (Join-Path $buildTools 'apksigner.bat') verify --print-certs $output
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed ($LASTEXITCODE)" }
} else {
    Copy-Item $aligned $output
}

$downloads = Join-Path (Split-Path -Parent $root) 'public\downloads'
New-Item -ItemType Directory -Force $downloads | Out-Null
Copy-Item $output (Join-Path $downloads 'Pasta.apk') -Force
Copy-Item $output (Join-Path (Split-Path -Parent $root) 'Pasta.apk') -Force

$size = [math]::Round((Get-Item $output).Length / 1MB, 2)
Write-Host ''
Write-Host "Built Pasta.apk ($size MB)"
Write-Host "  $output"
Write-Host "  $(Join-Path $downloads 'Pasta.apk')"
