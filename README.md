# Air 릴레이 (카카오톡 · 전화 → Fitbit Air 진동)

Fitbit Air는 화면과 알림 기능이 없어서 공식적으로는 알람 때만 진동합니다.
이 프로젝트는 비공식 Flutter 플러그인 [`fitbit_air`](https://pub.dev/packages/fitbit_air)로
Air를 블루투스로 직접 울려서 **카카오톡 메시지**와 **전화 수신**을 진동으로 알려 줍니다.

- 카카오톡 메시지 → 짧은 진동 1회
- 전화 수신 → 벨이 울리는 동안 진동 2회씩, 약 8초 간격으로 최대 4번
- 알림 내용(보낸 사람, 메시지 본문)은 읽거나 저장하지 않습니다. "알림이 왔다"는 사실만 사용합니다.
- 인터넷 권한이 없습니다.

> ⚠️ 이 코드는 **실제 기기에서 테스트하지 않았고 컴파일도 해보지 못했습니다.**
> 빌드 중 오류가 나면 오류 메시지를 그대로 보내 주세요.
> 또한 Air의 비공개 통신 방식을 이용하므로 Air 펌웨어 업데이트 후 동작하지 않을 수 있습니다.

## 준비물

1. Windows / macOS / Linux PC
2. [Flutter SDK](https://docs.flutter.dev/get-started/install) (Android 개발 환경 포함: Android Studio + SDK)
3. 안드로이드 폰 (Fitbit Air가 **Google Health 앱에 이미 페어링**되어 있어야 함)

`flutter doctor` 에서 Android toolchain 이 ✓ 로 나오면 준비 끝입니다.

## 빌드 방법

이 폴더에서:

**Windows (PowerShell)**
```powershell
.\setup.ps1
cd app
flutter build apk --release
```

**macOS / Linux**
```bash
bash setup.sh
cd app && flutter build apk --release
```

완성된 APK: `app/build/app/outputs/flutter-apk/app-release.apk`
폰으로 옮겨 설치하세요. (설정에서 "출처를 알 수 없는 앱 설치" 허용 필요)
폰을 USB로 연결하고 `flutter install` 또는 `flutter run --release` 로 바로 설치해도 됩니다.

`setup` 스크립트가 하는 일: 기본 Flutter 프로젝트를 만들고(`flutter create`), `template/` 안의 소스를 덮어쓰고, minSdk 를 26으로 올립니다.

## 앱 사용법

1. **권한 허용** 버튼 → 근처 기기, 알림, 전화 상태 권한 허용
2. **알림 접근 설정 열기** → 목록에서 "Air 릴레이"를 켜기 (카카오톡 진동에 필요)
3. 진동으로 받을 항목(카카오톡 / 전화) 선택
4. **릴레이 시작** → 상단바에 "Air 릴레이 실행 중" 알림이 뜹니다
5. **테스트 진동** 으로 Air가 울리는지 확인
6. **배터리 최적화 설정** 에서 Air 릴레이를 "제한 없음/최적화 안 함"으로 두면 백그라운드에서 더 안정적입니다

## 알아둘 점

- 재부팅하거나 앱을 강제 종료하면 릴레이가 멈춥니다. 앱을 열어 다시 **릴레이 시작**을 누르세요.
- Google Health 앱이 Air와 동기화 중이면 진동이 몇 초 늦어질 수 있습니다.
- 진동 세기는 Google Health 앱 → 연결 → Fitbit Air → 기기 환경설정 → 진동에서 조절합니다.
- 카카오톡 알림이 꺼져 있거나 채팅방이 무음이면 폰에 알림이 안 뜨므로 진동도 없습니다.
- 전화 진동은 통화 상태(벨 울림)를 직접 감지하므로 전화 앱 종류와 무관합니다. 카카오 보이스톡은 지원하지 않습니다.
- 다른 앱(문자, 라인 등)을 추가하려면 `RelayNotificationListener.kt`의 `KAKAO_PACKAGE` 비교 부분을 패키지 목록 비교로 바꾸면 됩니다.

## 파일 구성

| 파일 | 역할 |
|---|---|
| `template/lib/main.dart` | 화면 + Air에 진동 명령을 보내는 백그라운드 워커 |
| `.../RelayService.kt` | 포그라운드 서비스, 전화 상태 감지, 워커 엔진 실행 |
| `.../RelayNotificationListener.kt` | 카카오톡 알림 감지 |
| `.../MainActivity.kt` | 권한 요청, 설정 화면 연결 |
| `template/android/.../AndroidManifest.xml` | 권한과 서비스 선언 |

플러그인 라이선스: 플러그인 코드는 MIT, 포함된 Golden Gate 라이브러리 등은 Apache-2.0 입니다. 앱 시작 시 `FitbitAir.registerLicenses()`로 라이선스 화면에 등록해 두었습니다.
