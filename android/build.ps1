# Builds the DSH Mobile APK without Gradle.
# Requires: JDK 11+, Android SDK platform 33 + build-tools 33.0.2 + platform-tools.
# All SDK components are cached in <workspace>\.android-build\sdk.

$ErrorActionPreference = 'Stop'

$here       = $PSScriptRoot
$project    = Split-Path $here -Parent
$workspace  = Split-Path $project -Parent
$sdk        = Join-Path $workspace '.android-build\sdk'
$androidJar = Join-Path $sdk 'platforms\android-33\android.jar'
$buildTools = Join-Path $sdk 'build-tools\33.0.2'

$jdk = 'D:\New Folder'
if (-not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) {
    $jdk = Split-Path (Split-Path (Get-Command javac).Source -Parent) -Parent
}
$javac      = Join-Path $jdk 'bin\javac.exe'
$javaExe    = Join-Path $jdk 'bin\java.exe'
$keytool    = Join-Path $jdk 'bin\keytool.exe'
$jar        = Join-Path $jdk 'bin\jar.exe'
$aapt2      = Join-Path $buildTools 'aapt2.exe'
$d8Jar      = Join-Path $buildTools 'lib\d8.jar'
$zipalign   = Join-Path $buildTools 'zipalign.exe'
$apksigner  = Join-Path $buildTools 'apksigner.bat'

foreach ($p in @($androidJar, $aapt2, $d8Jar, $javaExe, $zipalign, $apksigner, $javac, $keytool, $jar)) {
    if (-not (Test-Path $p)) { throw "Missing build dependency: $p" }
}

$manifest = Join-Path $here 'AndroidManifest.xml'
$res      = Join-Path $here 'res'
$java     = Join-Path $here 'java'
$libs     = Join-Path $here 'libs\core-3.5.3.jar'
$build    = Join-Path $here 'build'
$gen      = Join-Path $build 'gen'
$classes  = Join-Path $build 'classes'
$dex      = Join-Path $build 'dex'

Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $gen, $classes, $dex | Out-Null

Write-Host '[1/7] aapt2 compile resources'
$resZip = Join-Path $build 'resources.zip'
& $aapt2 compile --dir $res -o $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed ($LASTEXITCODE)" }

Write-Host '[2/7] aapt2 link + R.java'
$unsigned = Join-Path $build 'app-unsigned.apk'
& $aapt2 link -o $unsigned -I $androidJar --manifest $manifest --java $gen `
    --min-sdk-version 23 --target-sdk-version 33 `
    --version-code 1 --version-name 1.0.0 $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed ($LASTEXITCODE)" }

Write-Host '[3/7] javac'
$sources = @(Get-ChildItem (Join-Path $java 'com\dsh\mobile') -Filter *.java | ForEach-Object FullName)
$sources += @(Get-ChildItem $gen -Recurse -Filter *.java | ForEach-Object FullName)
& $javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options `
    -bootclasspath $androidJar -classpath $libs -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host '[4/7] d8 dex'
$classesJar = Join-Path $build 'classes.jar'
& $jar cf $classesJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar classes failed ($LASTEXITCODE)" }
& $javaExe -cp $d8Jar com.android.tools.r8.D8 --lib $androidJar --min-api 23 --output $dex $classesJar $libs
if ($LASTEXITCODE -ne 0) { throw "d8 failed ($LASTEXITCODE)" }

Write-Host '[5/7] package and align'
$withDex = Join-Path $build 'app-with-dex.apk'
Copy-Item $unsigned $withDex -Force
& $jar uf $withDex -C $dex classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar failed ($LASTEXITCODE)" }
$aligned = Join-Path $build 'app-aligned.apk'
& $zipalign -f -p 4 $withDex $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed ($LASTEXITCODE)" }

Write-Host '[6/7] signing key'
$stateDir = Join-Path $project '.state'
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
$keystore = Join-Path $stateDir 'dsh-mobile.keystore'
$passFile = Join-Path $stateDir 'dsh-mobile.keystore.pass'
if (-not (Test-Path $keystore)) {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $pass = [Convert]::ToBase64String($bytes).Replace('+','A').Replace('/','B').Replace('=','')
    Set-Content -Path $passFile -Value $pass -NoNewline -Encoding ascii
    & $keytool -genkeypair -keystore $keystore -storepass $pass -keypass $pass `
        -alias dshmobile -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=DSH Mobile, OU=Personal, O=DSH, L=Local, ST=Local, C=CN'
    if ($LASTEXITCODE -ne 0) { throw "keytool failed ($LASTEXITCODE)" }
}
$pass = (Get-Content $passFile -Raw).Trim()

Write-Host '[7/7] apksigner'
$out = Join-Path $project 'dsh-mobile.apk'
& $apksigner sign --ks $keystore --ks-key-alias dshmobile `
    --ks-pass "pass:$pass" --key-pass "pass:$pass" --out $out $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed ($LASTEXITCODE)" }
& $apksigner verify --verbose $out
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed ($LASTEXITCODE)" }

$size = [math]::Round((Get-Item $out).Length / 1MB, 2)
Write-Host "OK: $out ($size MB)" -ForegroundColor Green
