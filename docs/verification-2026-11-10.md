# 검증 기록 2026-11-10 — 기획 v2(왔다감) 구현

이 기록 뒤(2026-11-11)에 화면을 시안 v3 로 다시 짰다. 아래 화면 흐름 확인은 그 이전 화면 기준이다. 지금 화면의 확인 기록과 캡처는 `verification-2026-11-11.md` 에 있다(`docs/screenshots/v3-*.png` 는 새 화면으로 바꿨다).

환경: Pleos Connect Emulator v2.1.0 (`pleos_connect` AVD, headless, cold boot), macOS 27. 빌드 `app-debug.apk` 0.2.0.

## 자동 시험
| 대상 | 결과 |
|---|---|
| `./gradlew testDebugUnitTest` | 156개 통과. 골든 시나리오 6개 포함. Room 저장소는 Robolectric(SQLite)에서 InMemory 와 같은 계약 시험 6개 통과 |
| `node --test web/test/` | 74개 통과 |
| Kotlin ↔ JS 교차 확인 | `web/test/fixtures/share-v1.json`(공유 hash), `projection.json`(경로 투영) 을 양쪽 테스트가 같이 쓴다 |

## 에뮬레이터에서 확인한 것
| 항목 | 결과 | 근거 |
|---|---|---|
| 동의 → 목록 → 주행 중 → 도착 카드 → 상세 → QR → 코스 | 시나리오 재생(강릉 당일, 제주)으로 화면이 순서대로 나옴. 앱 crash 0건 | `docs/screenshots/v3-*.png` |
| 부팅 후 자동 시작 | cold boot 뒤 앱을 열지 않아도 프로세스가 떠 있음(BootReceiver → Foreground Service, `isForeground=true`) | `pidof`, `dumpsys activity services` |
| Vehicle SDK | `Vehicle initialized`, 기어 `GEAR_PARK`, 도어 4개 0, 좌석 4개, 와이퍼 OFF 첫 값 수신 | logcat `TripRecapPleos` |
| AAOS 차속 | `speed(m/s)=0.0` 수신 | 같음 |
| FusedLocation | 초기화, 첫 좌표 수신(에뮬레이터 기본 좌표 39.237,-123.150) | 같음 |
| NaviHelper | 연결, 즐겨찾기 2개(HOME, OFFICE) 조회, `FREE_DRIVE` 상태 | 같음 |
| CarUxRestrictions | 연결, `uxRestricted=false` | 같음 |
| 공유 데이터 크기 | 강릉 당일 여행 858B | QR 화면 표시 |

## 고친 문제
- 차량 모드 시작 때 `Car.getCarManager()` binder 호출이 메인 스레드를 수 초 막아 ANR 이 났다. SDK 어댑터의 start/stop 을 전용 스레드(`pleos-sdk`)로 옮겼다. 그 뒤 차량 모드 전환·시작에서 ANR 0건.
- 시나리오가 HOME 등록 시각(0시)부터 재생돼 첫 주행까지 가상 9시간이 걸렸다. 첫 차량 이벤트 시각부터 시작하게 고쳤다.

## 확인하지 못한 것
- 기어·도어 신호 **변화**를 실제 SDK 로 받아 방문지 1개 만들기. user 빌드라 `cmd car_service inject-vhal-event` 가 거부된다. Virtual Console(GUI) 조작이 필요하다.
- `requestRoute` / `requestRouteWithWaypoints` 실제 호출과 구간 이어 붙이기. 시나리오 모드에서는 요청 내용을 화면에 글자로만 보여 준다.
- 카카오 로컬 API 실제 호출(키 없음). 응답 해석은 문서의 필드 이름으로 만든 예시로만 시험했다.
- 도착 TTS, 음성 메모 STT 를 새 화면 흐름에서 다시 듣는 것.
- 주행 중 CPU·RAM 측정.
- 휴대폰 페이지 실기기 동작(사진 고르기, EXIF 시각, 이미지 저장).

## 에뮬레이터 관찰
- 한 번 car service 가 응답하지 않는 상태가 되면 시스템 앱(`ai.umos.drivingview`)도 20초마다 ANR 을 내고 화면이 갱신되지 않는다. cold boot 로 풀린다. 우리 앱이 원인인지는 가리지 못했다(수정 전 빌드를 여러 번 재설치·재시작하던 중에 생겼고, 수정 뒤에는 다시 생기지 않았다).
- `adb shell input tap` 은 `-d 0` 을 줘야 앱에 전달된다.
- `ai.umos.homescreen` 이 부팅 직후 crash 하면 앱 창이 표시되지 않는다. 재부팅하면 표시된다.
