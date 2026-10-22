# Air 릴레이 프로젝트 생성 스크립트 (Windows PowerShell)
# 사용법: PowerShell에서 이 폴더로 이동 후  .\setup.ps1
$ErrorActionPreference = "Stop"

if (-not (Get-Command flutter -ErrorAction SilentlyContinue)) {
    Write-Host "Flutter가 설치되어 있지 않거나 PATH에 없습니다. https://docs.flutter.dev/get-started/install 를 참고하세요." -ForegroundColor Red
    exit 1
}

$root = $PSScriptRoot
$app = Join-Path $root "app"

if (Test-Path $app) {
    Write-Host "이미 app 폴더가 있습니다. 지우고 다시 실행하세요." -ForegroundColor Yellow
    exit 1
}

# 1) 기본 Flutter 안드로이드 프로젝트 생성 (패키지: kr.airrelay.air_relay_kr)
Push-Location $root
flutter create --org kr.airrelay --project-name air_relay_kr --platforms android app
Pop-Location

# 2) 준비된 소스 덮어쓰기
Copy-Item -Path (Join-Path $root "template\*") -Destination $app -Recurse -Force
$testDir = Join-Path $app "test"
if (Test-Path $testDir) { Remove-Item $testDir -Recurse -Force }

# 3) minSdk 를 26(Android 8.0)으로 올림
$gradle = Get-ChildItem (Join-Path $app "android\app") -Filter "build.gradle*" | Select-Object -First 1
$text = Get-Content $gradle.FullName -Raw
$text = $text -replace 'flutter\.minSdkVersion', '26'
Set-Content -Path $gradle.FullName -Value $text -NoNewline

# 4) 의존성 설치
Push-Location $app
flutter pub get
Pop-Location

Write-Host ""
Write-Host "완료! 다음 명령으로 APK 를 만드세요:" -ForegroundColor Green
Write-Host "  cd app"
Write-Host "  flutter build apk --release"
Write-Host "결과 파일: app\build\app\outputs\flutter-apk\app-release.apk"
