# PickupPleos 설정 순서

이 문서는 Android Studio에서 **Pleos Connect v2.1.0 가상 기기**를 설정하고, 이 폴더의 빈 앱을 실행하는 순서만 정리합니다.

## 1. Android Studio 프로젝트 열기

`File > Open`에서 아래 폴더를 선택합니다.

`C:개인에 맞게 하세요`

## 2. Android API 34 확인

`Tools > SDK Manager > SDK Platforms`로 이동합니다. `Android 14.0 (API 34)`가 설치돼 있는지 확인합니다.

## 3. Pleos SDK Update Site 추가

`SDK Manager > SDK Update Sites` 탭에서 **+**를 눌러 다음을 입력하고 **Apply** 또는 **OK**를 누릅니다.

- Name: `Pleos Connect System Image`
- URL (Windows): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`

목록에서 `Pleos Connect System Image` 왼쪽의 **Enabled** 체크가 켜져 있어야 합니다.

## 4. Pleos 시스템 이미지 설치

`SDK Manager > SDK Platforms`로 돌아가 화면 아래의 **Show package details**를 켭니다. `Android 14.0 (API 34)` 아래에서 Pleos Connect 시스템 이미지를 선택하고 **Apply**로 설치합니다. 설치가 끝나면 Android Studio를 재시작합니다.

## 5. Pleos 가상 기기 만들기

`Tools > Device Manager > + > Create Virtual Device`를 열고 다음을 선택합니다.

1. 왼쪽 **Automotive**를 선택합니다.
2. 기기 프로필 **Pleos Connect v2.1.0**을 선택합니다.
3. 시스템 이미지 화면에서 API를 **34**로 변경합니다.
4. **Pleos Connect v2.1.0 APIs Intel x86_64 Atom System Image**를 선택합니다. 일반 `Android Automotive with Google APIs` 이미지를 선택하지 않도록 이름을 확인합니다.

이 화면의 **Services** 필터가 `Google APIs`로 표시돼 있어도, 목록에서 Pleos 시스템 이미지가 선택됐다면 그대로 진행하면 됩니다.

## 6. Additional settings

상단 **Additional settings** 탭에서 다음 두 값만 확인합니다.

- Default boot: **Cold**
- RAM: **4 GB**

내부 저장소, CPU 코어, 그래픽 가속, VM heap 등 나머지는 기본값으로 둡니다. **Finish**를 눌러 가상 기기를 생성합니다.

## 7. 에뮬레이터와 앱 실행

1. `Tools > Device Manager`에서 `Pleos Connect v2.1.0` 옆의 **▶**를 눌러 에뮬레이터를 실행합니다.
2. 차량 홈 화면이 표시되면 Android Studio 상단 실행 기기에서 이 에뮬레이터를 선택합니다.
3. Gradle 동기화가 끝나면 실행 구성 `app`을 선택하고 상단 **Run ▶**을 누릅니다.
4. 화면에 `지금 태우러 갑니다 / Pleos 환경 설정 확인용 빈 앱`이 표시되면 설정 확인이 끝납니다.

## 공식 설치 문서

- [Pleos Connect 앱 개발 시작하기](https://document.pleos.ai/docs/pleos-only/getting-started)
- [Pleos Connect Emulator 설치 및 실행](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
