param(
    [switch]$Offline,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$socketDir = Join-Path $repo '.tools\gradle-sockets'
New-Item -ItemType Directory -Force -Path $socketDir | Out-Null

# JDK 21 on Windows may fail to create the NIO pipe used by Gradle when its
# Unix-domain socket is placed in the default user TEMP directory. Keep this
# setting in JAVA_TOOL_OPTIONS so it reaches the Gradle client, daemon and
# forked workers alike.
if ($socketDir.Length -gt 80) {
    throw "Build path is too long for a reliable Unix-domain socket: $socketDir. Move the checkout to a shorter path."
}
$socketOption = '-Djdk.net.unixdomain.tmpdir=' + $socketDir
if ($socketDir.Contains(' ')) { $socketOption = '"' + $socketOption + '"' }
$existingJavaOptions = [Environment]::GetEnvironmentVariable('JAVA_TOOL_OPTIONS')
$env:JAVA_TOOL_OPTIONS = if ($existingJavaOptions) { "$existingJavaOptions $socketOption" } else { $socketOption }

if (-not $env:JAVA_HOME -and (Test-Path 'C:\Program Files\Java\jdk-21\bin\java.exe')) {
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw 'Set JAVA_HOME to a working JDK 17 or 21 installation.'
}
if (-not $env:ANDROID_HOME -and (Test-Path (Join-Path $repo '.tools\android-sdk'))) {
    $env:ANDROID_HOME = Join-Path $repo '.tools\android-sdk'
}
if (-not $env:ANDROID_HOME) {
    throw 'Set ANDROID_HOME to the Android SDK directory.'
}
if (-not $env:GRADLE_USER_HOME -and (Test-Path (Join-Path $repo '.tools\gradle-home'))) {
    $env:GRADLE_USER_HOME = Join-Path $repo '.tools\gradle-home'
}

$gradle = Join-Path $repo '.tools\gradle-8.13\bin\gradle.bat'
if (-not (Test-Path $gradle)) { $gradle = Join-Path $repo 'gradlew.bat' }
if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    $GradleArgs = @('testDebugUnitTest', 'assembleDebug', 'lintDebug')
}
$options = @('--no-daemon', '--console=plain')
if ($Offline) { $options += '--offline' }

Push-Location $repo
try {
    & $gradle @GradleArgs @options
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
