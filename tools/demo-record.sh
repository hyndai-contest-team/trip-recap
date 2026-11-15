#!/bin/bash
# 시연 영상 1개(mp4)를 녹화한다. 실행 중인 Pleos Connect 에뮬레이터에서 아래 순서로 앱을 조작하며 화면을 녹화한다.
#   1. 새로 설치한 앱의 동의 화면 → [동의하고 시작하기]
#   2. 강릉 당일 시나리오 재생 → 주행 중 화면
#   3. 집 도착 → 도착 카드 "3곳을 다녀왔어요"
#   4. 도착 카드 [여행 보기] → 상세 화면
#   5. 뒤로 → 목록
#   6. 목록 카드 → 상세 → [코스로 다시 가기]
# 사용: tools/demo-record.sh [출력 mp4 경로]   (먼저 ./gradlew assembleDebug, 에뮬레이터 실행)
# 환경 변수: SIZE=1920x1080 (녹화 해상도. 비우면 화면 해상도. 녹화가 바로 끝나면 이 값으로 다시 시도한다)
#           BITRATE=12000000, DRIVE_SEC=15(주행 중 화면을 보여줄 초)
#
# - 앱 데이터를 지운다(adb uninstall). 기존 여행 기록이 사라진다.
# - screenrecord 는 소리를 녹음하지 않는다. 도착 카드 음성이 필요하면 OBS 로 따로 녹화한다.
# - screenrecord 한 번의 최대 길이는 180초다. 이 스크립트의 흐름은 약 90-110초다.
# - 버튼은 화면의 글자(uiautomator dump)로 찾아 누르고, 못 찾으면 smoke-test.sh 와 같은 dp 계산 좌표를 누른다.
#   좌표도 맞지 않으면 에뮬레이터 창에서 직접 누르면 된다(녹화는 계속된다).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/../devenv/env.sh" ] && . "$ROOT/../devenv/env.sh"
if ! command -v adb >/dev/null 2>&1 && [ -n "${ANDROID_HOME:-}" ]; then PATH="$ANDROID_HOME/platform-tools:$PATH"; fi
command -v adb >/dev/null 2>&1 || { echo "adb 를 찾지 못했다: ANDROID_HOME 을 정하거나 platform-tools 를 PATH 에 넣는다" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "연결된 에뮬레이터가 없다 (adb devices)" >&2; exit 1; }

# Android Studio 의 Running Devices 창에 띄운 에뮬레이터는 화면을 host 스크린숏으로 가져가는데,
# 11-13 과 11-15 녹화 중 에뮬레이터가 이 경로(PostWorker::screenshot → glReadPixels)에서 SIGSEGV 로 두 번 꺼졌다.
# 터미널에서 띄운 독립 창 에뮬레이터로 녹화한다: emulator -avd pleos_connect -no-snapshot-load
QEMU_PID="$(pgrep -f qemu-system | head -1)"
if [ -n "$QEMU_PID" ] && ps -o command= -p "$(ps -o ppid= -p "$QEMU_PID" | tr -d ' ')" 2>/dev/null | grep -qi "studio"; then
    echo "에뮬레이터가 Android Studio 안에서 실행 중이다. 녹화 중 꺼질 수 있다(위 주석)." >&2
    echo "Studio 의 에뮬레이터를 끄고 터미널에서 띄운 뒤 다시 실행하거나, 그대로 하려면 Enter" >&2
    read -r _
fi

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "APK 가 없다: ./gradlew assembleDebug 를 먼저 실행한다" >&2; exit 1; }
OUT="${1:-$ROOT/build/demo/demo-$(date +%Y%m%d-%H%M%S).mp4}"; mkdir -p "$(dirname "$OUT")"
REMOTE=/sdcard/triprecap-demo.mp4
SIZE="${SIZE:-}"; BITRATE="${BITRATE:-12000000}"; DRIVE_SEC="${DRIVE_SEC:-15}"
PKG=kr.triprecap.app

log() { echo "[$(date +%H:%M:%S)] $*"; }
dbg() { adb shell am broadcast --user current -a $PKG.DEBUG_SCENARIO -n $PKG/.DebugScenarioReceiver "$@" >/dev/null; }

# 화면에서 글자(text 또는 content-desc)가 정확히 같은 첫 요소의 가운데 좌표를 "x y" 로 출력한다. 없으면 빈 출력
find_text() {
    adb shell uiautomator dump /sdcard/triprecap-ui.xml >/dev/null 2>&1 || return 0
    adb exec-out cat /sdcard/triprecap-ui.xml 2>/dev/null | T="$1" perl -0777 -ne '
        for (split /<node /) {
            if ((/ text="\Q$ENV{T}\E"/ || / content-desc="\Q$ENV{T}\E"/) && /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/) {
                print int(($1+$3)/2), " ", int(($2+$4)/2), "\n"; last
            }
        }'
}

# wait_text 글자 제한초: 글자가 화면에 나올 때까지 기다린다. 나오면 0
wait_text() {
    local end=$(( $(date +%s) + $2 ))
    while [ "$(date +%s)" -lt "$end" ]; do
        [ -n "$(find_text "$1")" ] && return 0
        sleep 1
    done
    return 1
}

# tap_text 글자 [대체x 대체y]: 글자를 찾아 누른다. 못 찾으면 대체 좌표, 그것도 없으면 직접 누를 때까지 기다린다
tap_text() {
    local xy; xy="$(find_text "$1")"
    if [ -n "$xy" ]; then
        log "누름: $1 ($xy)"; adb shell input -d 0 tap $xy
    elif [ $# -ge 3 ]; then
        log "글자를 찾지 못해 좌표를 누름: $1 ($2 $3)"; adb shell input -d 0 tap "$2" "$3"
    else
        log "'$1' 을(를) 찾지 못했다. 에뮬레이터에서 직접 누른 뒤 Enter"; read -r _
    fi
}

REC_PID=""
start_record() {
    local size_opt=""; [ -n "$SIZE" ] && size_opt="--size $SIZE"
    adb shell rm -f $REMOTE
    adb shell screenrecord --bit-rate "$BITRATE" --time-limit 180 $size_opt $REMOTE &
    REC_PID=$!
    sleep 2
    if ! kill -0 "$REC_PID" 2>/dev/null; then
        [ -n "$SIZE" ] && { echo "녹화를 시작하지 못했다 (SIZE=$SIZE)" >&2; exit 1; }
        log "화면 해상도로 녹화를 시작하지 못해 1920x1080 으로 다시 시도"
        SIZE=1920x1080; start_record
    fi
}
stop_record() {
    adb shell pkill -INT screenrecord >/dev/null 2>&1
    wait "$REC_PID" 2>/dev/null
    sleep 2
    adb pull $REMOTE "$OUT" >/dev/null && adb shell rm -f $REMOTE
}
trap '[ -n "$REC_PID" ] && stop_record; exit 1' INT

# 좌표: 디스플레이(2560x1440) = 앱 창 안 좌표 + (853, 90). 앱 창 1682x1210. 계산 근거는 smoke-test.sh 주석.
# 동의 버튼: 좌우 여백 72, 폭 1040, 높이 112, 아래 여백 56 → 앱 (592, 1098)

log "준비: 앱 지우고 새로 설치"
adb uninstall $PKG >/dev/null 2>&1
adb install -g "$APK" | tail -1
adb shell input keyevent KEYCODE_HOME; sleep 2

log "녹화 시작 → $OUT"
start_record
T0=$(date +%s)

# 1. 동의 화면
adb shell am start -n $PKG/.MainActivity >/dev/null
dbg --es cmd mode --es mode scenario      # 동의 뒤 기록이 시나리오 신호를 쓰게 먼저 바꾼다
wait_text "동의하고 시작하기" 20 || log "동의 화면 글자를 찾지 못했다"
sleep 5                                   # 동의 화면 내용을 보여주는 시간
tap_text "동의하고 시작하기" 1445 1188
sleep 3                                   # 빈 목록

# 2. 주행 중 화면 (600배속: 1초 = 10분)
log "2. 강릉 당일 시나리오 재생"
dbg --es cmd load --ei index 0
dbg --es cmd restrict --es value auto
dbg --es cmd speed --ed speed 600
dbg --es cmd play
sleep "$DRIVE_SEC"

# 3. 도착 카드: 남은 구간은 3600배속(1초 = 1시간)으로 넘긴다
log "3. 집 도착까지 빨리 재생"
dbg --es cmd speed --ed speed 3600
wait_text "여행 보기" 60 || log "도착 카드를 찾지 못했다"
sleep 6                                   # "3곳을 다녀왔어요" 를 보여주는 시간

# 4. 상세
log "4. 여행 보기 → 상세"
tap_text "여행 보기"
sleep 10

# 5. 목록
log "5. 뒤로 → 목록"
tap_text "뒤로" 949 170
sleep 6

# 6. 다시 가기: 목록 첫 카드(앱 (841, 296)) → 상세 → 코스로 다시 가기
log "6. 목록 카드 → 상세 → 코스로 다시 가기"
adb shell input -d 0 tap 1694 386
wait_text "코스로 다시 가기" 10
sleep 2
tap_text "코스로 다시 가기" 1907 1216
sleep 10

stop_record
log "끝: $(( $(date +%s) - T0 ))초 녹화, $OUT"
