#!/usr/bin/env bash
# Air 릴레이 프로젝트 생성 스크립트 (macOS / Linux)
# 사용법: bash setup.sh
set -euo pipefail

if ! command -v flutter >/dev/null 2>&1; then
  echo "Flutter가 설치되어 있지 않거나 PATH에 없습니다. https://docs.flutter.dev/get-started/install" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"

if [ -d "$APP" ]; then
  echo "이미 app 폴더가 있습니다. 지우고 다시 실행하세요." >&2
  exit 1
fi

# 1) 기본 Flutter 안드로이드 프로젝트 생성 (패키지: kr.airrelay.air_relay_kr)
(cd "$ROOT" && flutter create --org kr.airrelay --project-name air_relay_kr --platforms android app)

# 2) 준비된 소스 덮어쓰기
cp -R "$ROOT/template/." "$APP/"
rm -rf "$APP/test"

# 3) minSdk 를 26(Android 8.0)으로 올림
GRADLE="$(ls "$APP"/android/app/build.gradle* | head -n 1)"
sed -i.bak 's/flutter\.minSdkVersion/26/' "$GRADLE" && rm -f "$GRADLE.bak"

# 4) 의존성 설치
(cd "$APP" && flutter pub get)

echo
echo "완료! 다음 명령으로 APK 를 만드세요:"
echo "  cd app && flutter build apk --release"
echo "결과 파일: app/build/app/outputs/flutter-apk/app-release.apk"
