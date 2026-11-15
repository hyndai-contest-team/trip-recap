# 왔다감 · Wheel Be Back — 여행 단위 자동 기록 (Pleos Connect)

## 1. 앱 이름과 한 줄 소개
**왔다감 (Wheel Be Back)**: 기어·도어·좌석 같은 차량 신호로 여행을 자동으로 기록해, 휴대폰으로 사진을 지정해 공유하고 같은 코스로 다시 안내 받을 수 있는 앱이다.

여행이 끝나면 들른 곳·머문 시간·비·동승 인원이 정리된 여행 1건이 차에 남는다. 현대자동차그룹 차량용 앱 개발 공모전(팀 tridrive) 출품작이다.
- 앱 프로젝트 소개(접수양식 02): `docs/app/project-intro.md`
- 기획: `docs/product-plan.md`
- 에뮬레이터 확인 기록: `docs/verification-2026-11-10.md`(SDK 어댑터), `docs/verification-2026-11-11.md`(시안 v3 화면), `docs/verification-2026-11-13.md`(Virtual Console 실제 신호, 판정 3단계, 외기온, 기간 검색)
- repo 이름 `trip-recap`, 패키지 `kr.triprecap.app` 은 그대로 쓴다.

## 2. 실행 방법
```sh
adb install -g apk/tridrive_왔다감_release.apk     # -g: 위치·차속 등 런타임 권한을 설치 때 허용
adb shell am start -n kr.triprecap.app/.MainActivity
```
1. 처음 실행하면 동의 화면이 나온다. [동의하고 시작하기]를 누르면 빈 목록이 나오고 백그라운드 기록이 시작된다.
2. release 빌드는 차량 신호로만 기록한다. 에뮬레이터에서는 아래 "실제 신호 넣기"의 Virtual Console 로 기어·속도·도어·위치를 바꿔 드라이브와 방문지를 만든다.
3. 시연 시나리오 재생(강릉 당일 등 9개)과 시연 여행 넣기는 debug 빌드에만 있다(아래 "빌드·테스트·실행", "시연 조작").

## 3. 테스트 환경과 필요한 설정
| 항목 | 내용 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (`system-images;android-34;pleos_car_emulator;arm64-v8a`, Android 14 / API 34). cold boot 로 띄운다 |
| 최소·대상 SDK | minSdk 28, targetSdk 35 |
| 권한 | 위치·차속 런타임 권한(`adb install -g` 로 허용). 그 밖의 차량 권한은 아래 "SDK·권한" 표 |
| 네트워크 | 장소 이름 조회(카카오 로컬 API)에 인터넷 연결이 필요하다. 연결이 없으면 "방문지 n"으로 저장하고 나중에 다시 조회한다 |
| 소스 빌드 | JDK·Gradle·SDK 버전과 `local.properties` 값은 아래 "빌드·테스트·실행" |

## 4. 개발에 사용한 에뮬레이터 버전과 화면 규격
- Pleos Connect Emulator v2.1.0
- 화면 2560x1440, 160dpi. 앱 창은 1682x1210 (화면 왼쪽 차량 패널과 아래 공조 막대를 뺀 영역)

## 5. APK 위치
[`apk/tridrive_왔다감_release.apk`](apk/) — release 빌드, 서명함, versionName 0.2.0, 47MB

## 동작 규칙 (수치는 단위 테스트로 고정)
| 항목 | 규칙 | 코드 |
|---|---|---|
| 드라이브 | 기어 P→D/R 시작. P 유지 3분 또는 시동 OFF 로 종료(종료 시각 = P 가 된 시각). 기어 신호가 없으면 시속 5km 10초로 시작, 정지 3분으로 종료 | `domain/TripRecorder` |
| 경로 | 직전 저장 점에서 20m 이상 떨어진 표본만 저장 | `domain/TripRecorder` |
| 정차 구분 | 방문지 = 도어 열림 + 10분 이상. 숙박 = 6~48시간 + 오전 3시 포함. 그 밖은 잠깐 정차. HOME·OFFICE 300m 안은 저장하지 않음. 150m 안 재정차는 합침 | `domain/StopClassifier` |
| 여행 묶기 | 드라이브 간격 6시간 미만이면 같은 이동. 닫는 시점: HOME 300m 도착, 집이 아닌 곳에 6시간 정차(그 시점에 닫음, endedAway), 숙박 정차 48시간 초과. 6시간 정차로 닫은 뒤 그 정차가 숙박(오전 3시 포함, HOME 등록 시)이 되면 같은 id 로 다시 열어 이어 붙임 | `domain/TripGrouper`, `domain/TripPipeline` |
| 판정 3단계 | 닫힌 이동을 일상 → 여행 → 애매 순서로 판정. 일상: 집↔회사(각 300m)이고 방문지·숙박 0개, 또는 방문지가 단골 1곳뿐. 여행: 방문지 2곳 이상, 숙박 1회 이상, 또는 경로 한 점이 집·회사 모두에서 30km 이상. 그 밖은 애매(도착 카드에서 물음). 단골 = 최근 30일 방문지 3번 이상(150m), 이번 이동은 세지 않음 | `domain/TripJudge`, `domain/Regulars` |
| 일상 이동·보관 | 모든 닫힌 이동을 분류(TRIP / DAILY / PENDING)와 함께 저장. 일상 이동은 끝난 지 7일, 정차 요약(좌표 소수 3자리·시각·방문지 여부)은 출발 30일 뒤 지움(앱 시작 때와 1시간마다, 실제 시각 기준). 전체 삭제는 정차 요약·추정 위치도 지움 | `TripSession`, `domain/StopLog`, `data/` |
| 집·회사 위치 | 칸마다 내비 즐겨찾기 > 설정에서 직접 정한 위치 > 추정. 추정은 빈 칸만 하고 한 번 정하면 저장해 계속 씀(설정 "추정으로 되돌리기"로 다시 추정). 집: 최근 7일 중 4일 이상 오전 3시에 서 있던 곳. 회사: 최근 14일 중 평일 6일 이상 9~18시 4시간 넘게 서 있던 곳 | `domain/HomeOffice`, `domain/PlaceEstimator`, `TripSession` |
| 장소 이름 | ① 내비 목적지(300m 안) ② 카카오 로컬 주변 POI(150m 안 관광명소 우선, 없으면 가장 가까운 곳. "경포해변인증센터" → "경포해변"처럼 장소 이름 뒤 시설 이름은 뗌) ③ 행정동 ④ 통신 실패면 "방문지 n"으로 저장 후 재조회 | `domain/PlaceNameResolver`, `kakao/` |
| 시동 OFF | 프로세스가 끝나도 저장된 마지막 드라이브 종료~다음 드라이브 시작을 정차로 복원 | `domain/TripPipeline` |
| 주행 중 화면 | 상태 한 줄("기록 중 · 3시간 10분", 출퇴근일 수 있어 "여행"이라고 쓰지 않음) + 숫자 칸 0~2개: 달린 거리(정수 km 버림, 1km 부터), 방문지가 생기면 방문지 수. 보던 화면이 있으면 "차를 세우면 보던 화면으로 돌아가요". 기록이 꺼져 있으면 "여행 기록이 꺼져 있어요". 터치·모션 없음, 화면은 분 단위로만 바뀜 | `domain/DrivingView`, `ui/DrivingScreen` |
| 도착 카드 | 확실한 여행이 집 도착으로 닫히면 바로 "여행을 저장했어요" + 음성. 애매한 이동은 같은 요약 + "이번 이동도 여행으로 남길까요?" [지우기](바로 영구 삭제) / [남기기](여행), 음성 없음. 답 없이 닫거나 10분이 지나거나 출발하면 일상 이동. 일상 이동은 카드 없음. 집이 아닌 곳에서 닫힌 이동(endedAway)은 다음 시동 때(프로세스 시작 뒤 첫 P 단 또는 시동 ON) 정차 중에 띄움("지난 여행을 저장했어요" 또는 질문), 그 전에 다시 열리거나 출발하면 띄우지 않음. 바로 띄우는 카드는 끝난 지 10분 안의 이동만. 큰 제목: 방문 0곳 "41km를 달렸어요", 1곳 "의암호에 다녀왔어요", 2곳 이상 "3곳을 다녀왔어요". 숫자 칸 달린 거리(방문 0곳이면 뺌)·여행 시간·함께 탄 사람(2명 이상). 방문 2곳 이상이면 가장 오래 머문 곳, 비가 왔으면 비 온 곳(3곳 이상은 "A 등 n곳"). 음성 한 문장 "세 곳 다녀온 여행을 저장했어요."(설정에서 끔) | `domain/ArrivalCard`, `domain/TripSummary`, `TripSession` |
| "새 여행" 표시 | 닫힌 여행을 처음 열어 보기 전까지 목록 카드에 "새 여행" 칩. 목록 카드나 도착 카드 "여행 보기"로 열면 사라짐. 시연 여행(seed)은 표시하지 않음 | `domain/Models`(`Trip.isNew`), `TripSession.markSeen` |
| 목록 | 분류가 여행인 것 중 닫힌 여행과, 열려 있어도 방문지가 생긴 여행만 보임(열린 여행은 "기록 중" 표시). 일상 이동·묻는 중인 이동은 목록·검색·1년 전 이맘때·공유·다시 가기에서 빠짐. 일상 이동이 있으면 맨 아래 "일상 이동 n건 · 7일 뒤 자동으로 지워요" → 일상 이동 화면(날짜·출발 시각, 집 → 회사, 시간, 거리, [여행으로 남기기]). 카드 한 줄: "11월 7일 (토) · 방문 3곳 · 3명 · 413km". 숙박이 있으면 날짜 뒤에 "2박 3일", 혼자면 인원 생략, 올해면 연도 생략 | `ui/TripListScreen`, `domain/TripSummary`(`Texts.listMeta`) |
| 숙박 표기 | 숙박이 있는 여행의 여행 시간은 "n박 n+1일"(숙박 정차 수 = 박). 번호는 방문지만 세고 숙박은 "숙", 잠깐 정차는 번호 없는 회색 점 | `domain/TripSummary`(`Texts.tripLength`), `ui/Components`(`NumberDot`) |
| 다시 찾기 | 장소·시군구·제목 검색, 올해·작년, 기간(달력에서 첫날과 마지막 날을 누름. 오늘 이후는 고를 수 없음), 동승 2명 이상, 비 온 날. 목록 맨 위 "1년 전 이맘때" 카드(작년 같은 날 ±3일), 계절 묶음 머리말("2026년 가을 · 여행 4번") | `domain/TripSearch`, `domain/DateRangePick` |
| 다시 가기 | 방문지 3곳씩(경유지 2 + 목적지) 구간으로 나눔. 구간 목적지 도착 뒤 출발하면 다음 구간 요청. 지난번 휴게소는 켜면 경유지로 넣음 | `domain/CoursePlanner`, `CourseController`, `ui/CourseList` |
| 공유 | 여행 → 1KB 이하 바이트 → URL `#` 뒤. HOME·OFFICE 500m 안 경로·정차와 공유 제외 정차는 뺌 | `share/ShareCodec`, `docs/share-format.md` |

숙박 상한 48시간(공항 장기 주차가 숙박으로 이어지는 것을 막는다)과 HOME 이 등록돼 있을 때만 숙박으로 닫았던 이동을 다시 여는 조건(끝나지 않는 여행을 막는다)은 기획서 2-1 "이동 묶기"에 적혀 있다.

## 구조
```
app/src/main/java/kr/triprecap/app/
  domain/     순수 Kotlin. TripRecorder StopClassifier TripGrouper TripPipeline TripJudge Regulars StopLog
              PlaceEstimator HomeOffice(집·회사 출처) TripLists(화면별 이동 고르기) TripSearch CoursePlanner
              TripSummary(Texts: 화면 숫자·날짜 표기) ArrivalCard DrivingView Stat Toasts TripEditor
              PlaceNameResolver RouteProjection RouteFraming DateRangePick(기간 달력) Josa Models
  share/      ShareCodec (QR 용 바이트 형식 v1)
  ports/      VehicleSignals LocationSource NaviSource UxRestrictionSource VoiceIo Clock TripStore Settings
  data/       Room DB v4(Trip, Drive, Stop, TrackPoint, StopSummary), PrefsSettings
  pleos/      실제 어댑터: PleosVehicleSignals PleosLocation PleosNavi CarUxRestrictions PleosVoiceIo
  kakao/      KakaoPlaceLookup (카카오 로컬 REST)
  scenario/   골든 시나리오 9개, ScenarioPlayer(가속 가상 시계), DemoSeeder
  service/    TripRecordingService(Foreground), BootReceiver
  ui/         화면: ConsentAndArrival(동의, 도착 카드) DrivingScreen TripListScreen TripDetailScreen
              ShareAndCourse(QR 공유, 코스 다시 가기) DailyMovesScreen(일상 이동) SettingsScreen
              DateRangeDialog(기간 달력). 화면 전환: AppRoot
              공통: Pleos(색·글자 토큰) Components(버튼·칩·스위치·숫자 칸·카드·아래 고정 줄) Dialogs Toast
              Icons RouteCanvas(경로 그림) CourseList
  TripSession 신호 → 기록 → 저장 → 도착 카드   AppContainer 구성(차량 모드 / 시나리오 모드)
  CourseController 코스 구간 요청   MainActivity   TripRecapApplication
app/src/debug/.../DebugScenarioReceiver.kt   adb 로 시연 조작
web/          휴대폰 공유 페이지(정적 파일). web/README.md 참고
```

## 빌드·테스트·실행
빌드 환경 (2026-11-14 macOS 에서 빌드·테스트를 확인한 값):
- JDK: Android Studio 내장 JBR 25.0.4 를 `JAVA_HOME` 으로 썼다. 소스·바이트코드 대상은 Java 17 이다(`app/build.gradle.kts`).
- Gradle 9.7.1(래퍼 `./gradlew` 가 받는다), Android Gradle Plugin 9.5.0, Kotlin 2.4.10(`gradle/libs.versions.toml`).
- Android SDK: `ANDROID_HOME` 에 SDK 경로를 둔다. compileSdk 37(SDK Platform `android-37.0`), targetSdk 35, minSdk 28.
- Pleos SDK(Vehicle, NaviHelper, FusedLocation, TextToSpeech, SpeechToText)는 `settings.gradle.kts` 에 적은 Pleos Maven 저장소에서 받는다.
- 에뮬레이터: Pleos Connect Emulator v2.1.0 시스템 이미지(`system-images;android-34;pleos_car_emulator;arm64-v8a`). developers.pleos.ai 에 로그인해 받은 SDK Update Site URL 을 Android Studio SDK Manager 의 SDK Update Sites 에 추가하면 설치할 수 있다.
- `tools/*.sh` 와 아래 adb 명령은 `PATH` 의 `adb` 를 쓴다.
```sh
export JAVA_HOME=<JDK 경로>            # 예: Android Studio 내장 JBR
export ANDROID_HOME=<Android SDK 경로>
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```
```sh
./gradlew testDebugUnitTest      # Kotlin 단위 테스트 406개 (Room 은 Robolectric)
node --test web/test/            # 휴대폰 페이지 테스트 105개
./gradlew assembleDebug
tools/smoke-test.sh              # 실행 중인 에뮬레이터에서 주요 화면 캡처 (터치 좌표는 화면 코드의 dp 수치로 계산한 값. 에뮬레이터에서 실행해 확인하지는 않았다)
```
`local.properties`(git 제외)에 넣는 값:
```
kakao.restApiKey=<카카오 개발자 콘솔 REST API 키>     # 없으면 장소 이름이 "방문지 n"으로 남는다
share.baseUrl=<휴대폰 공유 페이지 주소, 끝에 />        # 없으면 https://trip-recap.pages.dev/
```

## 시연 조작 (debug 빌드)
```sh
D="adb shell am broadcast --user current -a kr.triprecap.app.DEBUG_SCENARIO -n kr.triprecap.app/.DebugScenarioReceiver"
$D --es cmd consent; $D --es cmd mode --es mode scenario      # vehicle 이면 실제 SDK 신호
$D --es cmd seed                                              # 시연 여행 5건 + 일상 이동 2건 넣기
$D --es cmd load --ei index 0; $D --es cmd speed --ed speed 60; $D --es cmd play
# index: 0 강릉 당일 · 1 제주 2박 3일 · 2 출퇴근 5일 · 3 장거리 귀성 · 4 같은 장소 재정차 · 5 위치 신호 끊김 · 6 집→집 해안도로 드라이브 · 7 퇴근길 단골 마트 · 8 부모님 댁 1박
$D --es cmd chapter --ei index 2                              # 시나리오의 n번째 장면으로
$D --es cmd restrict --es value on                            # 주행 중 화면 제한: on | off | auto
$D --es cmd ignition                                          # 시동 켜기: 집이 아닌 곳에서 닫힌 이동의 도착 카드를 띄움(차가 서 있을 때)
# 그 밖: pause | next(다음 장면) | arrive(목적지 도착 보내기) | dismiss(도착 카드 닫기, 질문이면 일상 이동) | clear(모든 여행 지우기)
```
같은 조작이 앱의 설정 > 시연 도구에도 있다("시동 켜기 보내기" 포함, debug 빌드에서만 보인다). 시나리오를 올리면 시나리오의 HOME·OFFICE 만 쓰고, 정차 요약은 저장하지 않으며 집·회사 추정과 1시간마다의 보관 기간 정리를 하지 않는다(골든 시나리오 결과를 고정하려고).
"지난 여행을 저장했어요" 카드 보기: 부모님 댁 1박(index 8)을 "6시간 뒤" 장면 다음까지 재생한 뒤 오전 3시 전에 `ignition`. 3시가 지나면 이동이 다시 열려 카드가 뜨지 않는다. 에뮬레이터 터치는 `adb shell input -d 0 tap x y`.

## 실제 신호 넣기 (Pleos Connect Virtual Console)
에뮬레이터 v2.1.0 이 8088 포트로 여는 Virtual Console 에 HTTP 요청을 보내 기어·속도·도어·좌석·와이퍼를 바꾼다. 앱은 차량 모드(`mode vehicle`)여야 한다.
```sh
tools/vconsole.sh state                    # 현재 값
tools/vconsole.sh gear D                   # P | R | N | D
tools/vconsole.sh speed 40                 # km/h
tools/vconsole.sh door fl open             # fl | fr | rl | rr, open | close
tools/vconsole.sh seat passenger on        # driver | passenger | rear_left | rear_right, on | off
tools/vconsole.sh wiper low                # off | auto | low | high
tools/vconsole.sh loc 37.7637 128.8990     # 에뮬레이터 GPS (위도 경도)
tools/vconsole.sh visit                    # 방문지 1개 만들기: 주행 60초 → 정차 11분 → 주행 60초 → P 4분
```
브라우저로 직접 조작하려면 `adb forward tcp:8088 tcp:8088` 뒤 `http://127.0.0.1:8088/` 을 연다. 에뮬레이터는 cold boot 로 띄운다.

## SDK·권한
| 데이터 | 경로 | 권한 | 상태 (2026-11-10 에뮬레이터 v2.1.0) |
|---|---|---|---|
| 기어·도어·좌석·와이퍼 | Pleos Vehicle 2.3.2 | `CAR_DRIVE` `CAR_DOORS` `CAR_SEATS` `CAR_WINDOWS` | 기어·도어 값 변화 수신과 방문지 1개 생성 확인 (11-13, `docs/verification-2026-11-13.md`). 좌석·와이퍼 변화는 미확인 |
| 차속·시동 | AAOS `CarPropertyManager` | `CAR_SPEED`(런타임) `CAR_POWERTRAIN` | 차속 첫 값 수신 확인 |
| 외기온 | AAOS `CarPropertyManager` `ENV_OUTSIDE_TEMPERATURE` | `CAR_EXTERIOR_ENVIRONMENT` | 실제 신호는 미확인. 차가 값을 주지 않으면 온도 없이 기록한다. 정차마다 도착 때 값을 정수 섭씨로 저장하고 상세 화면 시각 줄에 "· 14°C" 로 보인다(시나리오 재생으로 확인, `docs/verification-2026-11-13.md` 3부) |
| 위치 | Pleos FusedLocation 1.1.3 (대체: NaviHelper 10초 조회) | `FUSED_LOCATION`(런타임) | 첫 값 수신 확인 |
| 목적지·HOME/OFFICE·경로 요청 | Pleos NaviHelper 3.0.5 | `NAVI_ROUTE` `NAVI_ROUTE_SEARCH` | 연결·즐겨찾기 조회 확인. 경로 요청은 미확인 |
| 주행 제한 | `CarUxRestrictionsManager` | 없음 | 첫 값 수신 확인 |
| TTS·STT | Pleos TextToSpeech/SpeechToText 3.3.1 | `TTS_SERVICE` `STT_SERVICE` | 11-09 확인 (`docs/verification-2026-11-09.md`) |
차량 제어 API 는 쓰지 않는다. 저장은 기기 안 Room DB 이고 `allowBackup=false` 다.

## 아직 하지 않은 것
- 실제 주행 신호로 만든 방문지에 카카오 이름이 붙는 것을 에뮬레이터 화면에서 확인 (실제 키로 조회 코드를 실행해 이름·시군구가 나오는 것과, 시나리오 화면에 시군구가 붙는 것은 2026-11-15 에 확인했다)
- 휴대폰 카메라로 QR 을 찍어 여는 것(QR 해독과 페이지 열기는 PC 에서 확인했다. `docs/verification-2026-11-13.md` 4부)
- 내비 경로 요청(`requestRoute`, `requestRouteWithWaypoints`) 실제 호출 확인
- 시나리오 경로를 실제 도로 형상으로 교체 (지금은 주요 지점을 직선으로 이은 근사 경로)
- 주행 중 CPU 5%·RAM 측정, 이용약관·개인정보처리방침 URL
- release APK 로 Virtual Console 신호를 넣어 방문지를 만드는 것 (release APK 는 2026-11-15 에 설치, 동의, 빈 목록까지 확인했다)
- 휴대폰 페이지 실기기 확인(iOS Safari, Android Chrome)
- 에뮬레이터에서 아직 보지 못한 화면: 좁은 창(841px), 동의, 기록을 껐을 때의 주행 중 화면, 알림 위치, 눌림 효과, 메모 말하기 (`docs/verification-2026-11-11.md`)
