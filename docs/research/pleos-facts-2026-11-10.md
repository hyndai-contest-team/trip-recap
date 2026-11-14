# 확인한 Pleos SDK 사실

작성 2026-11-10. 이 앱의 개발 전제와 Pleos 공식 문서 원문에서 확인한 사실을 적는다.

## 공모전과 대상 차량
- 현대자동차그룹 차량용 앱 개발 공모전(appdevchallenge.auto-hmg.io, "Code your App into Pleos App Market"). Pleos Connect(Android Automotive OS 기반 인포테인먼트)에서 도는 3rd party 앱을 만든다.
- 대상 차량은 **현재 판매되는 사람이 운전하는 차**(Pleos Connect 탑재 현대·기아·제네시스)다. 로보택시·완전자율주행 차가 아니다. 앱은 중앙 디스플레이에서 돈다. 주행 중에는 `distractionOptimized` 선언 화면만 쓸 수 있고, 운전자 주의 분산 규제(CarUxRestrictionsManager)를 받는다.
- 일정: 접수 마감 2026-11-15, 1차 결과 11-23, 2차 PT 12-03-04(APK 필요).

## 확인한 Pleos 사실 (2026-11-08-10, 문서 원문 기준)
- 공식 SDK: Vehicle 2.3.2, NaviHelper 3.0.x, TextToSpeech, SpeechToText 3.3.1, FusedLocation 1.1.3.
- Vehicle SDK: Door(`registerDoorPosition`), Wiper ON/OFF, Seat(`registerSeatOccupancy`, 안전벨트), Odometer·트립, EV 배터리, 연료. 권한은 App Market 사전 승인 필요. 외기온·시동은 AAOS `ENV_OUTSIDE_TEMPERATURE`·`IGNITION_STATE`, 기어는 AAOS `GEAR_SELECTION`, 차속은 `PERF_VEHICLE_SPEED`.
- 좌석 각도·공조 온도·조명(앰비언트) **제어** API는 공식 SDK 문서에서 확인하지 못했다. 차량 물리 제어·센서 직접 접근은 정책상 금지("직접 접근 금지: 차량의 물리적 제어나 센서에 직접 접근해서는 안 됩니다").
- 카메라: Camera 앱과 EVS(외부 카메라)는 시스템 앱. 3rd party 카메라 API 없음. 실내 카메라·무게 센서 API도 없음.
- NaviHelper: 목적지·경로 요청(`requestRoute`, `requestRouteWithWaypoints`, 경유지 최대 2개), `getCurrentLocationInfo`, 도착·경로 시작 콜백, 남은 시간/거리 정보. TBT(다음 회전) 정보 제공 여부는 확인하지 않았다.
- STT는 단발 `request()`/`stop()`, wake word·스티어링 키 이벤트 없음. TTS `speak()`, NAVIGATION·ANNOUNCEMENT 오디오 포커스는 ducking으로 동시 재생.
- 주행 중 내비 외 앱 CPU 5% 미만, RAM 400MB. 위치 앱은 이용약관·개인정보처리방침 URL 필요.
- 에뮬레이터: Odometer 0.0 고정, 연비 미지원, `onRouteEnded`가 경로 시작 때 발생하는 문제.
