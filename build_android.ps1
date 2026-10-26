param()
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$stageRoot = Join-Path ([IO.Path]::GetTempPath()) ('woogong-android-' + [guid]::NewGuid().ToString('N'))
if ($stageRoot.ToCharArray() | Where-Object { [int]$_ -gt 127 }) { throw '영문 경로의 TEMP 디렉터리가 필요합니다.' }
New-Item -ItemType Directory -Path $stageRoot | Out-Null

function Copy-SourceTree([string]$source, [string]$destination) {
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    foreach ($item in Get-ChildItem -LiteralPath $source -Force) {
        if ($item.PSIsContainer) {
            if ($item.Name -notin @('build', '.gradle', '.kotlin')) {
                Copy-SourceTree $item.FullName (Join-Path $destination $item.Name)
            }
        } elseif ($item.Name -ne 'local.properties') {
            Copy-Item -LiteralPath $item.FullName -Destination $destination
        }
    }
}

Copy-SourceTree (Join-Path $projectRoot 'android') (Join-Path $stageRoot 'android')
$javaPath = $env:JAVA_HOME
if (-not $javaPath) {
    $javaPath = (Get-ChildItem (Join-Path $projectRoot '.tools/jdk64') -Directory | Select-Object -First 1).FullName
}
$sdkPath = $env:ANDROID_HOME
if (-not $sdkPath) { $sdkPath = Join-Path $projectRoot '.tools/sdk' }
$cachePath = Join-Path $projectRoot '.tools/gradle-home'
New-Item -ItemType Directory -Force -Path $cachePath | Out-Null
foreach ($entry in @(@('jdk',$javaPath), @('sdk',$sdkPath), @('gradle-cache',$cachePath))) {
    if (-not (Test-Path -LiteralPath $entry[1])) { throw "빌드 도구를 찾을 수 없습니다: $($entry[1])" }
    New-Item -ItemType Junction -Path (Join-Path $stageRoot $entry[0]) -Target $entry[1] | Out-Null
}
$env:JAVA_HOME = Join-Path $stageRoot 'jdk'
$env:ANDROID_HOME = Join-Path $stageRoot 'sdk'
$env:GRADLE_USER_HOME = Join-Path $stageRoot 'gradle-cache'
& (Join-Path $stageRoot 'android/gradlew.bat') -p (Join-Path $stageRoot 'android') testDebugUnitTest assembleDebug --no-daemon --max-workers=2
if ($LASTEXITCODE -ne 0) { throw "Android 빌드 실패. 진단 파일: $stageRoot" }
$output = Join-Path $projectRoot 'release'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$model = (Get-Content (Join-Path $projectRoot 'active_model.json') -Raw | ConvertFrom-Json).model_id
$apk = Join-Path $output "$model-debug.apk"
Copy-Item -LiteralPath (Join-Path $stageRoot 'android/app/build/outputs/apk/debug/app-debug.apk') -Destination $apk
$appVersion = ([regex]::Match((Get-Content (Join-Path $projectRoot 'android/app/build.gradle.kts') -Raw), 'versionName = "([^"]+)"')).Groups[1].Value
$testOutput = Join-Path $output ("android-tests-" + $appVersion)
New-Item -ItemType Directory -Force -Path $testOutput | Out-Null
Get-ChildItem -LiteralPath (Join-Path $stageRoot 'android/app/build/test-results/testDebugUnitTest') -Filter 'TEST-*.xml' | Copy-Item -Destination $testOutput -Force
Write-Output "APK: $apk"
Write-Output "Build workspace: $stageRoot"
