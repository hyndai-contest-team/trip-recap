# 검증 기록 2026-11-09 — Pleos Connect Emulator v2.1.0

2026-11-09 빌드(이전 기획)에서 확인한 TTS·STT 동작 기록이다. 그 빌드의 화면 흐름은 2026-11-10 기획 변경으로 없어졌다.

환경: Pleos Connect system image `android-34/pleos_car_emulator/arm64-v8a`, AVD `pleos_connect` (Cold boot, RAM 4GB), Android Emulator 37.2.13, macOS 27.

## 확인된 것
| 항목 | 결과 | 근거 |
|---|---|---|
| 앱 설치·실행 | 정상. Pleos 분할 화면의 오른쪽 영역에 표시된다 | 화면 확인 |
| Pleos 권한 8개 (2026-11-09 빌드 기준) | 설치 시 모두 granted | `dumpsys package` |
| TTS 초기화 (ON_DEVICE) | 성공 | `TripRecapVoice: tts initialized` |
| STT 초기화 (ON_DEVICE) | 성공 | `TripRecapVoice: stt initialized` |
| TTS 재생 | 약 45자 문장을 on-device 엔진(화자 seoyoon)이 합성해 6.3~7.5초 재생. onReady → onStart → onDone 순서로 콜백 | 서비스 로그 `[GAAS]OnDeviceStreamPlayerImpl` |
| TTS 종료 직후 STT 시작 | onDone 즉시 request() → 0.1~0.4초 뒤 onReady, 마이크 열림(상태바 녹색 아이콘). 오디오 충돌 없음 | 화면 확인 |
| STT 시간 초과 처리 | 8초 동안 결과가 없으면 stop() 호출 | 06:21 실행 로그 |
| 취소 시 마이크 닫힘 | 수정 전에는 speak/listen 이 취소될 때 STT stop() 이 호출되지 않아 마이크가 열린 채 남았다. speak/listen 을 취소 안전하게 고침 | `pleos/PleosVoiceIo.kt` |

## 한국어 인식 (06:31, Android Studio 에서 띄운 에뮬레이터, 개발자가 직접 발화)
| 발화 | 인식 텍스트 | maxRms | 비고 |
|---|---|---|---|
| 네 | 네 | 828 | 중간 결과 → 최종 결과 → EPD 순으로 콜백 |
| 닭강정 | 닭강정 | 543 | 부분 결과 "닭" → "닭강정". 최종(completed) 콜백 없이 종료 콜백으로 확정 |
| — | (없음) | 382 | 8초 시간 초과 |
| 아니요 | 아니요 | 999 | |
| 떡볶이 | 떡볶이 | 871 | |
| 네 | 네 | 632 | |

- 듣기 6회 중 5회 인식, 1회 시간 초과. 인식된 5건은 모두 발화와 일치했다.
- 마이크가 열리지 않던 원인: 명령줄에서 띄운 에뮬레이터는 macOS 마이크 권한을 받지 못했다. Android Studio Device Manager 에서 띄우고 권한을 허용하자 들어왔다(RMS 4.8 → 380~1000).

## 확인되지 않은 것
- Vehicle·NaviHelper·FusedLocation 실제 어댑터 (2026-11-10 첫 값 수신 확인: `verification-2026-11-10.md`)
- 내비 안내 음성과 TTS 가 겹칠 때의 오디오 포커스 동작

## 부수 관찰
- 시스템 앱 `ai.umos.inputmethod` 가 부팅 직후 한 번 crash 한다. 우리 앱과 무관하다.
- 에뮬레이터 화면은 2560×1440 단일 디스플레이(ID 4619827259835644672). `screencap -d` 에 이 ID 를 준다.
