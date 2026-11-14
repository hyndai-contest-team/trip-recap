#!/bin/bash
# 실행 중인 에뮬레이터(Pleos Connect 또는 범용 Automotive AVD)에서 시나리오 재생으로 주요 화면을 돌리고 캡처한다.
# 사용: tools/smoke-test.sh [출력 폴더]   (먼저 ./gradlew assembleDebug, 에뮬레이터 실행)
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# 개발 환경 파일(저장소 밖 ../devenv/env.sh)이 있으면 읽고, 없으면 ANDROID_HOME 또는 PATH 의 adb 를 쓴다
[ -f "$ROOT/../devenv/env.sh" ] && . "$ROOT/../devenv/env.sh"
if ! command -v adb >/dev/null 2>&1 && [ -n "${ANDROID_HOME:-}" ]; then PATH="$ANDROID_HOME/platform-tools:$PATH"; fi
command -v adb >/dev/null 2>&1 || { echo "adb 를 찾지 못했다: ANDROID_HOME 을 정하거나 platform-tools 를 PATH 에 넣는다" >&2; exit 1; }
OUT="${1:-$ROOT/build/smoke-shots}"; mkdir -p "$OUT"
DISPLAY_ID="${DISPLAY_ID:-4619827259835644672}"
dbg() { adb shell am broadcast --user current -a kr.triprecap.app.DEBUG_SCENARIO -n kr.triprecap.app/.DebugScenarioReceiver "$@" >/dev/null; }
shot() { adb exec-out screencap -d "$DISPLAY_ID" -p > "$OUT/$1.png" 2>/dev/null || adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }

tap() { adb shell input -d 0 tap "$1" "$2"; sleep 2; }

adb install -r -g "$ROOT/app/build/outputs/apk/debug/app-debug.apk" | tail -1
adb logcat -c
adb shell am start -n kr.triprecap.app/.MainActivity >/dev/null; sleep 8
dbg --es cmd consent; dbg --es cmd mode --es mode scenario; sleep 2; dbg --es cmd clear; sleep 1
dbg --es cmd seed; sleep 6; shot 1-list
dbg --es cmd clear; sleep 1
dbg --es cmd load --ei index 0; dbg --es cmd speed --ed speed 600; dbg --es cmd play
sleep 6; shot 2-driving                      # 9:10 출발 뒤 주행 중 화면
sleep 66; shot 3-arrival                     # 19:45 집 도착 → 도착 카드
dbg --es cmd dismiss; sleep 1

# 터치 좌표: 디스플레이(2560x1440) = 앱 창 안 좌표 + (853, 90). 앱 창은 1682x1210(넓은 창 레이아웃).
# 화면 코드의 dp 수치(1dp = 1px)로 계산한 값이다.
# 목록은 강릉 여행 1건만 있는 상태다. 이때는 "1년 전 이맘때" 카드가 없고 계절 머리말이 맨 위에 온다.
# (seed 로 시연 여행을 넣은 상태는 "1년 전 이맘때" 카드가 맨 위에 오므로 첫 카드 위치가 다르다)
# 첫 카드: 위 여백 40 + TopBar 80 + 목록 위 여백 32 + 머리말 44(글자 40 + 아래 4) + 간격 16 = 카드 위 212, 높이 168 → 가운데 y 296.
#          x 는 좌우 여백 56 사이 카드 폭 1570 의 가운데 841.
tap 1694 386; shot 4-detail                  # 첫 카드 → 상세. 앱 (841, 296)
# 상세 아래 고정 줄: 오른쪽 열(x 868~1626, 폭 758) 아래에 붙는다. 버튼 높이 112, 아래 여백 28 → 버튼 y 1070~1182, 가운데 1126.
#          두 버튼은 간격 16 으로 폭 371 씩: 왼쪽 "코스로 다시 가기" 868~1239(가운데 1054), 오른쪽 "휴대폰으로 보내기" 1255~1626(가운데 1441).
tap 2294 1216; shot 5-share                  # 휴대폰으로 보내기. 앱 (1441, 1126)
# 뒤로: TopBar 왼쪽 80x80 원형 버튼(x 56~136, y 40~120) 가운데 → 앱 (96, 80)
tap 949 170; tap 1907 1216; shot 6-course    # 뒤로 → 상세 → 코스로 다시 가기. 앱 (96, 80), (1054, 1126)
echo "=== ANR / crash ==="; adb logcat -d | grep -c "ANR in kr.triprecap"; adb logcat -d -b crash | grep -c "kr.triprecap"
echo "=== main thread blocked ==="; adb logcat -d -s 'TripRecapApp:W' | grep -c blocked
