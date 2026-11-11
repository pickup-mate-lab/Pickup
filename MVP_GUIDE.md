# 픽업메이트 MVP 실행 안내

기존 [README.md](README.md)는 Pleos 가상 기기 설치 순서입니다. 이 문서는 현재 구현된 픽업 흐름을 로컬에서 시연하는 방법입니다.

## 준비

- Android Studio에서 이 저장소를 클론한 프로젝트 폴더를 엽니다.
- Pleos Connect v2.1.0 / API 34 가상 기기를 실행합니다.
- Windows에 Python 3이 설치되어 있어야 합니다. 데모 서버는 별도 Python 패키지가 필요하지 않습니다.

## 1. 로컬 서버 실행

PowerShell에서 프로젝트 폴더로 이동한 다음 명령을 실행하고 창을 열어 둡니다.

```powershell
python .\demo_server\server.py
```

이 서버는 기본적으로 PC의 `127.0.0.1:8787`에서 실행됩니다. 차량 에뮬레이터의 `10.0.2.2:8787`은 이 PC의 서버를 가리킵니다.

## 2. 차량 앱 실행

Android Studio에서 실행 대상 `Pleos Connect v2.1.0`을 선택하고 `app`을 실행합니다. 서버 주소는 화면 아래 **데모 서버 설정**을 펼쳐 변경할 수 있습니다. 기본값은 다음 로컬 시연에 맞춰져 있습니다.

- 차량 앱 서버 주소: `http://10.0.2.2:8787`

연락처 권한을 허용한 뒤 연락처에서 탑승자를 선택하거나 이름을 입력하고 **픽업 시작**을 누릅니다. 같은 이름의 연락처가 여러 개면 번호 끝자리와 함께 후보를 보여 줍니다. 탑승자 앱 연결은 아직 개발 전이며 초대 링크나 문자를 보내지 않습니다.

## 3. 탑승자와 운전자 흐름 확인

1. 차량 앱에서 위치 권한을 허용하고 픽업을 시작합니다.
2. **데모 서버 설정**을 펼쳐 **시연: 탑승자 승인**을 누릅니다. 이는 실제 사람에게 연락하지 않고 시험용 승차 지점을 전달합니다.
3. 차량 서비스가 승차 지점을 받아 Pleos NaviHelper에 경로를 자동으로 요청합니다. 내비게이션 앱에서 경로 탐색 결과를 확인합니다.
4. 차량 앱으로 돌아와 설정의 **시연: 승차 지점 변경**을 누르면 새 지점으로 경로를 다시 요청합니다. **시연: 탑승자 메시지**를 누르면 차량 화면에 메시지가 표시되고 Pleos TTS가 읽기를 시도합니다.
5. 개발 시험에서는 **음성으로 답장**을 누르고 음성 안내가 끝나면 답장을 말합니다. 앱이 내용을 읽고 확인하면 “보내 줘” 또는 “취소”라고 답합니다. 에뮬레이터의 Extended Controls → Microphone에서 마이크 입력을 켜야 합니다. 실제 Gleo 음성 호출은 스키마 승인과 별도 검증이 필요합니다.
6. **픽업 완료 · 위치 공유 종료**를 누르면 서버가 위치와 메시지를 지우고 공유를 중단합니다.

앱을 다시 열어도 진행 중인 세션을 조회합니다. 데모 서버는 세션을 메모리에 저장하므로 서버를 재시작하면 이전 세션이 사라지고, 앱이 이를 감지해 초기화합니다. 세션은 생성 후 2시간이 지나도 만료됩니다.

## 음성 명령과 ETA

`app/src/main/assets/schema.json`에는 Gleo의 픽업 시작, 동명이인 선택, 음성 답장 시작 액션이 들어 있습니다. 앱은 해당 인텐트와 callback을 처리합니다. **실제 Gleo 음성 호출은 App Schema 등록·승인 후 별도로 검증해야 합니다.** 메시지 읽기와 음성 답장은 Pleos TTS·STT 온디바이스 모드로 연결되어 있습니다. 스키마의 actionCalls는 최대 3개여서 픽업 상태 음성 확인은 현재 스키마에서 빠져 있습니다. [Gleo App Schema 안내](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)

ETA는 NaviHelper `DestinationInfo.remainTime`을 사용합니다. 웹 문서에는 단위가 없지만 `ai.pleos.playground:navi-data:3.0.4` 소스의 KDoc에 **초(seconds)**로 명시되어 있습니다. 차량 앱은 경로가 확인되면 15초마다 남은 시간을 조회해 위치와 함께 서버에 보냅니다. 다음 경우에는 ETA를 보내지 않습니다.

- 내비 목적지가 승차 지점과 다를 때
- 첫 값이 경로 시작 시의 `RouteStartInfo.duration`(초)과 3배 이상 차이 날 때
- 60초 넘게 값이 갱신되지 않았을 때

에뮬레이터에서는 `adb logcat -s PickupEta`로 `remainTime`, `remainDistance`, `routeDuration` 값을 확인할 수 있습니다. 서버(Go)는 이 ETA로 탑승자에게 출발 시점·도착 임박(5분)·도착(100m) 안내를 보냅니다. 설계는 [docs/superpowers/specs/2026-11-11-eta-arrival-alerts-design.md](docs/superpowers/specs/2026-11-11-eta-arrival-alerts-design.md)에 있습니다. [NaviHelper 목적지 안내](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations)

## 실제 휴대전화에서 시험할 때

탑승자 앱 연결은 아직 구현되지 않았습니다. 현재 Python 서버는 운영용 계정 인증·암호화·영구 저장을 갖춘 배포용 서버가 아니므로 테스트 데이터만 사용하세요.

## 확인 명령

```powershell
.\gradlew.bat :app:assembleDebug
cd .\demo_server
python -m unittest -v test_server.py
```

APK는 `app\build\outputs\apk\debug\app-debug.apk`에 생성됩니다.

## 화면 미리보기

- [Pleos 차량 화면](docs/driver-preview.png)
