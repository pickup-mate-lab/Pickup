# 픽업메이트 로컬 데모 서버

이 서버는 차량 앱과 탑승자 웹 화면의 첫 연결을 시험하기 위한 로컬 프로토타입입니다. 세션은 메모리에만 저장되며 서버를 종료하면 사라집니다. HTTP에는 암호화가 없으므로 테스트 위치만 사용하세요.

## 실행

```powershell
python server.py
```

기본 주소는 `http://127.0.0.1:8787`입니다. 같은 PC의 Android 에뮬레이터에서는 `http://10.0.2.2:8787`로 접속합니다. 실제 휴대전화에서 현재 위치를 공유하려면 HTTPS로 제공해야 합니다. 일반 휴대전화 브라우저는 LAN HTTP 페이지의 위치 요청을 허용하지 않습니다.

```powershell
python -m unittest -v test_server.py
```

## API

모든 세션별 API 요청은 `Authorization: Bearer <역할 토큰>`을 사용합니다. 모든 POST는 JSON 객체를 보냅니다.

| 요청 | 권한 | 내용 |
| --- | --- | --- |
| `POST /api/sessions` | 없음 | `{"contactName":"김민수","pickupTime":"18:30"}` → `id`, `driverToken`, `passengerToken`, `expiresAt` |
| `GET /api/sessions/{id}` | 운전자 또는 탑승자 | 상태·승차 지점·현재 위치·ETA 조회 |
| `POST /api/sessions/{id}/pickup` | 탑승자 | `{"lat":37.5,"lon":127.0,"label":"승차 지점"}`. 지점 변경 때 `revision` 증가, 운전자 재확인 필요. 이 요청만으로 탑승자의 현재 위치 공유가 시작되지는 않음 |
| `POST /api/sessions/{id}/confirm` | 운전자 | `{"revision":1}`. 최신 지점만 확정 가능 |
| `POST /api/sessions/{id}/location-consent` | 탑승자 | `{"enabled":true}`로 현재 위치 공유 시작, `{"enabled":false}`로 중단하고 서버에 저장된 탑승자 위치 삭제 |
| `POST /api/sessions/{id}/location` | 양측 | `{"lat":37.5,"lon":127.0}`. 운전자는 선택적으로 `etaSeconds` 추가 가능 |
| `POST /api/sessions/{id}/finish` | 운전자 | `{}`. 위치 정보 삭제 및 공유 종료 |

탑승자 주소는 `/p/{id}/{passengerToken}`입니다. 초대 주소에는 접근 토큰이 들어 있으므로 제삼자에게 공개하지 마세요. 세션은 생성 2시간 후 종료되고 위치 정보가 삭제됩니다. 탑승자가 승차 지점을 지정한 뒤에도 운전자가 최신 `revision`을 확인해야 목적지가 확정됩니다.

탑승자는 고정된 승차 지점만 보내고 자신의 **현재 위치 공유는 선택**할 수 있습니다. 현재 위치 공유에 동의한 경우에만 `location-consent`를 활성화한 뒤 탑승자 `location` 요청을 보냅니다. 체크를 해제하면 브라우저 추적과 서버 저장 위치가 모두 중단됩니다.

탑승자 화면의 지도 타일은 OpenStreetMap 서버에서 불러옵니다. 네트워크가 없으면 지도 배경이 표시되지 않습니다. ETA는 운전자 앱이 전달한 값만 표시하며, 그 값이 없으면 도로 주행 시간 대신 **직선거리**로 표기합니다.
