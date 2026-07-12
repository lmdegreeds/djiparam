# Build DjiParam.apk (gradle-free): aapt2 link (manifest + res) -> javac -> d8 -> add dex -> zipalign -> sign.
#
# Configuration via environment variables (all optional, sensible defaults shown):
#   ANDROID_SDK_ROOT     Android SDK location      (default: %LOCALAPPDATA%\Android\Sdk)
#   ANDROID_BUILD_TOOLS  build-tools version       (default: 35.0.0)
#   ANDROID_PLATFORM     compile platform          (default: android-34)
#   JAVA_HOME            JDK 11+ location          (default: current JAVA_HOME / PATH javac)
#   DJIPARAM_KEYSTORE    signing keystore          (default: .\debug.keystore in the repo root)
#   DJIPARAM_KS_PASS     keystore + key password   (default: android)
#   DJIPARAM_KS_ALIAS    key alias                 (default: androiddebugkey)
$ErrorActionPreference = "Continue"

function Env($name, $default) {
    $v = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrEmpty($v)) { $default } else { $v }
}

$SDK  = Env "ANDROID_SDK_ROOT" "$env:LOCALAPPDATA\Android\Sdk"
$BT   = "$SDK\build-tools\$(Env 'ANDROID_BUILD_TOOLS' '35.0.0')"
$AJAR = "$SDK\platforms\$(Env 'ANDROID_PLATFORM' 'android-34')\android.jar"

$PROJ  = $PSScriptRoot
$BUILD = Join-Path $PROJ "build"
$KS    = Env "DJIPARAM_KEYSTORE" (Join-Path $PROJ "debug.keystore")
$KSPW  = Env "DJIPARAM_KS_PASS"  "android"
$ALIAS = Env "DJIPARAM_KS_ALIAS" "androiddebugkey"

# resolve javac: prefer JAVA_HOME, else whatever is on PATH
$JAVAC = if ($env:JAVA_HOME) { "$env:JAVA_HOME\bin\javac.exe" } else { "javac" }

function Die($m){ Write-Host "BUILD FAILED: $m" -ForegroundColor Red; exit 1 }

if (-not (Test-Path $AJAR))  { Die "android.jar not found at $AJAR (set ANDROID_SDK_ROOT / ANDROID_PLATFORM)" }
if (-not (Test-Path $BT))    { Die "build-tools not found at $BT (set ANDROID_BUILD_TOOLS)" }
if (-not (Test-Path $KS))    { Die "keystore not found at $KS — create a debug keystore or set DJIPARAM_KEYSTORE (see README)" }

New-Item -ItemType Directory -Force $BUILD | Out-Null
if (Test-Path "$BUILD\classes") { Remove-Item -Recurse -Force "$BUILD\classes" }
New-Item -ItemType Directory -Force "$BUILD\classes" | Out-Null

Write-Host "== aapt2 compile (res) =="
& "$BT\aapt2.exe" compile --dir "$PROJ\res" -o "$BUILD\res.zip"
if ($LASTEXITCODE -ne 0) { Die "aapt2 compile" }

Write-Host "== aapt2 link (manifest + res + assets) =="
$assetArgs = @()
if (Test-Path "$PROJ\assets") { $assetArgs = @("-A", "$PROJ\assets") }
& "$BT\aapt2.exe" link -I $AJAR --manifest "$PROJ\AndroidManifest.xml" "$BUILD\res.zip" @assetArgs `
  -o "$BUILD\app.unsigned.apk" --min-sdk-version 24 --target-sdk-version 30
if ($LASTEXITCODE -ne 0) { Die "aapt2 link" }

Write-Host "== javac =="
$srcs = Get-ChildItem -Recurse "$PROJ\src" -Filter *.java | ForEach-Object { $_.FullName }
& $JAVAC --release 11 -classpath $AJAR -d "$BUILD\classes" $srcs 2>&1 |
  Where-Object { $_ -notmatch 'bootstrap class path|obsolete|To suppress|warning: \[options\]|warnings?$|deprecat' }
if ($LASTEXITCODE -ne 0) { Die "javac" }

Write-Host "== d8 =="
$classes = Get-ChildItem -Recurse "$BUILD\classes" -Filter *.class | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --release --min-api 24 --lib $AJAR --output $BUILD $classes
if ($LASTEXITCODE -ne 0) { Die "d8" }

Write-Host "== add classes.dex =="
Push-Location $BUILD
& "$BT\aapt.exe" add "app.unsigned.apk" "classes.dex" | Out-Null
Pop-Location
if ($LASTEXITCODE -ne 0) { Die "aapt add" }

Write-Host "== zipalign + apksigner =="
& "$BT\zipalign.exe" -f -p 4 "$BUILD\app.unsigned.apk" "$BUILD\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { Die "zipalign" }
& "$BT\apksigner.bat" sign --ks $KS --ks-pass "pass:$KSPW" --key-pass "pass:$KSPW" --ks-key-alias $ALIAS --out "$BUILD\DjiParam.apk" "$BUILD\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { Die "apksigner" }

$sz = [math]::Round((Get-Item "$BUILD\DjiParam.apk").Length/1KB,0)
Write-Host ("RESULT: " + (Join-Path $BUILD "DjiParam.apk") + "  ($sz KB)") -ForegroundColor Green
