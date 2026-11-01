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
- 탑승자 링크 주소: `http://127.0.0.1:8787`

연락처 권한을 허용한 뒤 연락처에서 탑승자를 선택하거나 이름을 입력하고 **픽업 시작**을 누릅니다. 같은 이름의 연락처가 여러 개면 번호 끝자리와 함께 후보를 보여 줍니다. **초대 링크 공유**는 선택한 연락처의 SMS 작성 화면을 열며, 보낼 앱이 없으면 공유 메뉴를 엽니다. 발송은 사용자가 직접 완료합니다.

## 3. 탑승자와 운전자 흐름 확인

1. 생성된 초대 링크를 **서버를 실행한 PC의 브라우저**에서 엽니다.
2. 탑승자가 지도에서 승차 지점을 누르고 장소 이름을 입력해 보냅니다.
3. 차량 앱에서 **승차 지점 확인 · 경로 안내**를 누릅니다. Pleos NaviHelper가 해당 좌표로 경로를 요청합니다.
4. 차량 앱은 운전자 위치를, 탑승자 화면은 차량 위치를 주기적으로 공유·조회합니다. 탑승자 현재 위치는 브라우저에서 별도로 동의하고 위치 접근을 허용했을 때만 공유됩니다.
5. 실제 탑승 후 차량 앱에서 **픽업 완료 · 위치 공유 종료**를 누릅니다. 서버가 두 위치를 지우고 공유를 중단합니다.

앱을 다시 열어도 진행 중인 세션을 조회합니다. 데모 서버는 세션을 메모리에 저장하므로 서버를 재시작하면 이전 세션이 사라지고, 앱이 이를 감지해 초기화합니다. 세션은 생성 후 2시간이 지나도 만료됩니다.

## 음성 명령과 ETA

`app/src/main/assets/schema.json`에는 Gleo의 픽업 시작, 동명이인 선택, 승차 지점 확인 액션이 들어 있습니다. 앱은 해당 인텐트와 callback을 처리합니다. **실제 Gleo 음성 호출은 App Schema 등록·승인 후 별도로 검증해야 합니다.** 현재 확인한 것은 인텐트를 직접 전달한 시연입니다. [Gleo App Schema 안내](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)

현재 Pleos 에뮬레이터 시연에서는 NaviHelper 경로 요청을 확인했지만 ETA 수신은 확인되지 않았습니다. 공식 문서에 `remainTime`의 단위도 명시되지 않아 이 값을 임의로 분으로 환산하지 않습니다. 지금은 예상 시간 대신 차량과 승차 지점의 **직선거리**를 보여 줍니다. [NaviHelper 목적지 안내](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations), [remainTime API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/destination-info/remain-time)

## 실제 휴대전화에서 시험할 때

기본 초대 링크의 `127.0.0.1`은 서버를 실행한 PC에서만 열립니다. 실제 탑승자 휴대전화에서는 서버를 접근 가능한 **HTTPS 주소**로 제공하고 앱의 두 주소를 그 주소로 바꿔야 합니다. 휴대전화 브라우저는 일반 LAN HTTP 페이지의 현재 위치 접근을 허용하지 않습니다. 현재 Python 서버는 인증·암호화·영구 저장을 갖춘 배포용 서버가 아니므로 테스트 연락처와 위치만 사용하세요.

## 확인 명령

```powershell
.\gradlew.bat :app:assembleDebug
cd .\demo_server
python -m unittest -v test_server.py
```

APK는 `app\build\outputs\apk\debug\app-debug.apk`에 생성됩니다.

## 화면 미리보기

- [Pleos 차량 화면](docs/driver-preview.png)
- [탑승자 화면](docs/passenger-preview.png)

탑승자 지도의 OpenStreetMap 타일을 불러오지 못하면 안내와 격자 배경이 표시됩니다. 이때도 승차 지점 좌표를 선택할 수 있습니다.
