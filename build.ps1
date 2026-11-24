param(
    [switch]$Debug,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'

$root = $PSScriptRoot
$tools = Join-Path $root '.tools'
New-Item -ItemType Directory -Force -Path $tools | Out-Null

function Get-Download([string]$Url, [string]$OutFile) {
    # curl.exe (bundled with Windows 10+) retries on dropped connections; fall back to Invoke-WebRequest.
    if (Test-Path $OutFile) { Remove-Item -Force $OutFile }
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        & $curl.Source -fL --retry 5 --retry-all-errors --retry-delay 3 -o $OutFile $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed ($LASTEXITCODE): $Url" }
    } else {
        Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing
    }
}

# 1. JDK 17
$jdkDir = Join-Path $tools 'jdk17'
if (-not (Test-Path (Join-Path $jdkDir 'bin\java.exe'))) {
    Write-Host 'Downloading JDK 17...'
    $zip = Join-Path $tools 'jdk17.zip'
    Get-Download 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' $zip
    $tmp = Join-Path $tools 'jdk-tmp'
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $inner = @(Get-ChildItem -Path $tmp -Directory)
    if ($inner.Count -ne 1) { throw 'Unexpected JDK archive layout' }
    if (Test-Path $jdkDir) { Remove-Item -Recurse -Force $jdkDir }
    Move-Item -Path $inner[0].FullName -Destination $jdkDir
    Remove-Item -Recurse -Force $tmp
    Remove-Item -Force $zip
}
$env:JAVA_HOME = $jdkDir
$env:Path = (Join-Path $jdkDir 'bin') + ';' + $env:Path

# 2. Android SDK command-line tools, licenses, packages
$sdkRoot = Join-Path $tools 'android-sdk'
$sdkmanager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
    Write-Host 'Downloading Android command-line tools...'
    $zip = Join-Path $tools 'cmdline-tools.zip'
    Get-Download 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $zip
    $tmp = Join-Path $tools 'cmdline-tmp'
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $latest = Join-Path $sdkRoot 'cmdline-tools\latest'
    New-Item -ItemType Directory -Force -Path (Split-Path $latest -Parent) | Out-Null
    if (Test-Path $latest) { Remove-Item -Recurse -Force $latest }
    Move-Item -Path (Join-Path $tmp 'cmdline-tools') -Destination $latest
    Remove-Item -Recurse -Force $tmp
    Remove-Item -Force $zip
}
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot

$packagesPresent = (Test-Path (Join-Path $sdkRoot 'platform-tools')) -and
    (Test-Path (Join-Path $sdkRoot 'platforms\android-35')) -and
    (Test-Path (Join-Path $sdkRoot 'build-tools\35.0.0'))
if (-not $packagesPresent) {
    Write-Host 'Accepting Android SDK licenses and installing packages...'
    # PowerShell pipes do not reach sdkmanager.bat's stdin reliably; feed answers from a file via cmd.
    $yesFile = Join-Path $tools 'yes.txt'
    [IO.File]::WriteAllText($yesFile, ("y`r`n" * 50))
    cmd /c "`"$sdkmanager`" --sdk_root=`"$sdkRoot`" --licenses < `"$yesFile`" > nul 2>&1"
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager --licenses failed with exit code $LASTEXITCODE" }
    cmd /c "`"$sdkmanager`" --sdk_root=`"$sdkRoot`" platform-tools platforms;android-35 build-tools;35.0.0 < `"$yesFile`""
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager package install failed with exit code $LASTEXITCODE" }
    Remove-Item -Force $yesFile
    foreach ($p in @('platform-tools', 'platforms\android-35', 'build-tools\35.0.0')) {
        if (-not (Test-Path (Join-Path $sdkRoot $p))) { throw "Android SDK package missing after install: $p" }
    }
}

# 3. Gradle 8.11.1
$gradleHome = Join-Path $tools 'gradle-8.11.1'
$gradleBat = Join-Path $gradleHome 'bin\gradle.bat'
if (-not (Test-Path $gradleBat)) {
    Write-Host 'Downloading Gradle 8.11.1...'
    $zip = Join-Path $tools 'gradle-8.11.1-bin.zip'
    Get-Download 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' $zip
    Expand-Archive -Path $zip -DestinationPath $tools -Force
    Remove-Item -Force $zip
}

# 4. local.properties
$sdkForward = $sdkRoot -replace '\\', '/'
Set-Content -Path (Join-Path $root 'local.properties') -Value "sdk.dir=$sdkForward" -Encoding ASCII

# 5. Release signing keystore
$signingDir = Join-Path $root '.signing'
$keystoreProps = Join-Path $signingDir 'keystore.properties'
if (-not $Debug -and -not (Test-Path $keystoreProps)) {
    New-Item -ItemType Directory -Force -Path $signingDir | Out-Null
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $one = New-Object byte[] 1
    $pwChars = New-Object System.Collections.Generic.List[char]
    while ($pwChars.Count -lt 24) {
        $rng.GetBytes($one)
        $pwChars.Add($alphabet[$one[0] % $alphabet.Length])
    }
    $pw = -join $pwChars

    $keystore = Join-Path $signingDir 'release.jks'
    & (Join-Path $jdkDir 'bin\keytool.exe') -genkeypair -v -keystore $keystore -alias sma -keyalg RSA -keysize 4096 -validity 10000 -storepass $pw -keypass $pw -dname 'CN=sameaslooks, O=SMA'
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }

    $props = @(
        'storeFile=../.signing/release.jks',
        "storePassword=$pw",
        'keyAlias=sma',
        "keyPassword=$pw"
    )
    Set-Content -Path $keystoreProps -Value $props -Encoding ASCII
    Write-Host 'WARNING: A new release keystore was generated in .signing\. Back up the whole .signing folder now. It is required to publish updates signed with the same key.' -ForegroundColor Yellow
}

# 6. Build and unit tests
if ($Debug) {
    $variant = 'debug'
    $tasks = @('testDebugUnitTest', 'assembleDebug')
    $suffix = '-debug'
} else {
    $variant = 'release'
    $tasks = @('testReleaseUnitTest', 'assembleRelease')
    $suffix = ''
}
$gradleArgs = @('-p', $root, '--no-daemon')
if ($Clean) { $gradleArgs += 'clean' }
$gradleArgs += $tasks
& $gradleBat @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }

# 7. Copy artifact to dist\ and print hash
$gradleText = Get-Content -Raw -Path (Join-Path $root 'app\build.gradle.kts')
if ($gradleText -notmatch 'versionName\s*=\s*"([^"]+)"') { throw 'versionName not found in app\build.gradle.kts' }
$versionName = $Matches[1]

$apkDir = Join-Path $root "app\build\outputs\apk\$variant"
$apkSrc = Join-Path $apkDir "app-$variant.apk"
if (-not (Test-Path $apkSrc)) { $apkSrc = Join-Path $apkDir "app-$variant-unsigned.apk" }
if (-not (Test-Path $apkSrc)) { throw "APK not found in $apkDir" }

$distDir = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$apkDst = Join-Path $distDir "sma-$versionName$suffix.apk"
Copy-Item -Path $apkSrc -Destination $apkDst -Force

$hash = (Get-FileHash -Algorithm SHA256 -Path $apkDst).Hash
Write-Host "APK: $apkDst"
Write-Host "SHA-256: $hash"
