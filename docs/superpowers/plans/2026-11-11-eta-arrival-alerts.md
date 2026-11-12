# ETA·도착 알림·위치 공유 구현 계획

설계: [2026-11-11-eta-arrival-alerts-design.md](../specs/2026-11-11-eta-arrival-alerts-design.md)
브랜치: 두 저장소 모두 `feat/eta-arrival-alerts`

## 커밋 1 — pickup-passenger/server: 출발·임박·도착 알림 규칙

1. `session`에 `WalkingSeconds *int`, `WalkingRevision int`, `AlertRevision int`, `AlertStage int` 필드를 추가한다. 5분 전 1회 발송용이던 `NotifiedRevision`은 이 두 필드로 대체한다.
2. `pickup` 액션에서 revision이 증가하면 도보 시간과 알림 단계를 초기화한다.
3. `freshETA()`는 차량 위치가 60초 이내로 갱신된 경우에만 ETA를 반환한다. `view`가 이 값을 사용한다.
4. `stage()`는 도착(100m) > 임박(300초) > 출발(ETA - 도보 - 120 ≤ 0) 순서로 판정한다.
5. `view`에 `walkingSeconds`, `departureInSeconds`, `arrivalStage` 필드를 추가한다.
6. `walking-estimate` 결과를 세션에 저장하고, 기존 5분 전 푸시 로직을 단계 기반 `alertLocked`로 교체한다. 차량 위치 갱신과 도보 시간 저장 시점에 호출한다.
7. 테스트: 단계별 1회 발송, 여러 단계를 건너뛸 때 마지막 단계만 발송, revision 변경 시 초기화, 오래된 ETA는 `null`, view 필드 값.
   - 검증: `go test ./...`

## 커밋 2 — pickup-passenger/app: 위치 공유 안정화와 도착 안내

1. `didChangeAppLifecycleState`는 `paused`/`hidden`/`detached`일 때만 공유를 멈춘다. 공유 의사는 `wantsSharing`에 기억하고, `resumed` 시 진행 중인 픽업이면 다시 켠다.
2. 상태 카드에 ETA, 출발 안내(`departureInSeconds`), 도착 단계(`arrivalStage`)를 표시한다.
3. `arrivalStage`가 바뀌면 SnackBar와 `HapticFeedback`으로 알린다.
4. 도보 시간 문구는 `walkingSeconds`/`departureInSeconds` 서버 값을 쓰고 "(조회 시점 기준)"을 제거한다.
5. 문구 함수(`arrivalText`)를 위젯 밖 순수 함수로 두고 `flutter test`로 검증한다.
   - 검증: `flutter analyze`, `flutter test`

## 커밋 3 — Pickup/app: remainTime ETA 전송

1. `PleosNavigation`
   - `onRouteStarted`에서 `duration`을 저장한다.
   - `onDestinationInfo`에서 목적지가 요청한 승차 지점과 일치하고 `0 ≤ remainTime ≤ 86400`이면 `onEtaSeconds(remainTime)`을 호출한다. 첫 값이 `duration`과 3배 이상 차이 나면 해당 경로의 ETA를 비활성화한다.
   - `onDestinationArrived`에서는 0, `onRouteCancelled`에서는 `null`을 보낸다.
   - 실측 확인용으로 `Log.d("PickupEta", ...)`를 남긴다.
2. `DriverLocationService`
   - 경로가 확인된 상태에서 15초마다 `refreshEta()`를 호출한다.
   - ETA 수신 시각을 기록해, 60초 넘게 지난 값은 `null`로 보낸다.
   - 새 revision으로 경로를 요청하면 ETA를 초기화한다.
3. `PickupApi.location`은 `etaSeconds`를 항상 포함한다(없으면 JSON `null`).
4. `PickupActivity` 탑승자 위치 줄에 승차 지점 거리와 갱신 경과 시간을 표시한다.
   - 검증: `gradlew :app:assembleDebug`. 에뮬레이터 실측은 `adb logcat -s PickupEta`.

## 커밋 4 — Pickup/docs: 설계·계획 문서와 MVP_GUIDE의 ETA 설명 갱신
