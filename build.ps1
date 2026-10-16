# 一键构建（Windows PowerShell）
# 用法： powershell -ExecutionPolicy Bypass -File .\build.ps1
# 需要：JDK 17 + Android SDK 34 + Gradle 8.7（放在 tools\ 下或用环境变量/系统 PATH 里的）

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

# 优先用仓库内自带的工具链，没有就用环境里已有的
if (Test-Path "$root\tools\jdk17") { $env:JAVA_HOME = "$root\tools\jdk17" }
if (Test-Path "$root\tools\android-sdk") { $env:ANDROID_HOME = "$root\tools\android-sdk" }

$gradle = if (Test-Path "$root\tools\gradle-8.7\bin\gradle.bat") {
    "$root\tools\gradle-8.7\bin\gradle.bat"
} else {
    "gradle"
}

if (-not (Test-Path "$root\local.properties")) {
    Write-Host "提示：没有 local.properties，需要自己建一个，例如： sdk.dir=C\:\\Android\\Sdk"
}

& $gradle -p $root assembleRelease --console=plain

Write-Host ""
Write-Host "APK 输出目录： $root\app\build\outputs\apk\release"
Get-ChildItem "$root\app\build\outputs\apk\release" -ErrorAction SilentlyContinue |
    Select-Object Name, @{ n = 'MB'; e = { [math]::Round($_.Length / 1MB, 2) } }
