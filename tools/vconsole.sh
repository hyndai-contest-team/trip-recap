#!/bin/bash
# Pleos Connect Virtual Console(에뮬레이터 v2.1.0 이상, 포트 8088)에 HTTP 요청을 보내 차량 신호를 바꾼다.
# 브라우저 화면(http://127.0.0.1:8088/)이 보내는 것과 같은 요청이다. 에뮬레이터는 cold boot 여야 한다(quick boot 는 VHAL 을 쓸 수 없다).
# 속도·시동 OFF 는 `adb shell cmd car_service inject-vhal-event` 로 넣을 수 없어서(user 빌드) 이 경로를 쓴다.
#
# 사용: tools/vconsole.sh <명령> [인자]
#   state                          기어·속도·도어·좌석·와이퍼 현재 값
#   gear P|R|N|D
#   speed <km/h>                   0~240
#   door fl|fr|rl|rr open|close
#   seat driver|passenger|rear_left|rear_right on|off
#   wiper off|auto|low|high        앞 유리
#   loc <위도> <경도>              에뮬레이터 GPS 한 점
#   drive <위도1> <경도1> <위도2> <경도2> <초> [km/h]
#                                  기어 D, 속도를 넣고 두 점 사이를 1초마다 한 점씩 옮긴 뒤 속도 0, 기어 P
#   visit [머무는 분]              방문지 1개를 만드는 순서 전체(기본 11분, 전체 약 18분). 아래 visit() 참고
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# 개발 환경 파일(저장소 밖 ../devenv/env.sh)이 있으면 읽고, 없으면 ANDROID_HOME 또는 PATH 의 adb 를 쓴다
[ -f "$ROOT/../devenv/env.sh" ] && . "$ROOT/../devenv/env.sh"
if ! command -v adb >/dev/null 2>&1 && [ -n "${ANDROID_HOME:-}" ]; then PATH="$ANDROID_HOME/platform-tools:$PATH"; fi
command -v adb >/dev/null 2>&1 || { echo "adb 를 찾지 못했다: ANDROID_HOME 을 정하거나 platform-tools 를 PATH 에 넣는다" >&2; exit 1; }
V="${VCONSOLE_URL:-http://127.0.0.1:8088}"

# Virtual Console 은 3초 안에 브라우저 세션 요청을 받은 동안에만 값을 바꾼다. 그 밖에는 503 emulator_inactive 를 돌려준다.
# 세션이 끊겨도 넣어 둔 값은 그대로 남는다
put() {
  curl -fsS -m 5 -X POST "$V/browser/session?client=vconsole-sh" -o /dev/null
  curl -fsS -m 10 -X PUT "$V$1" -o /dev/null
}
connect() {
  adb forward tcp:8088 tcp:8088 >/dev/null
  curl -fsS -m 5 -o /dev/null "$V/vehicle/state" || { echo "Virtual Console 에 연결하지 못했다: $V (에뮬레이터 실행, cold boot 확인)" >&2; exit 1; }
}

gear() {
  case "$1" in P|p) g=Park;; R|r) g=Reverse;; N|n) g=Neutral;; D|d) g=Drive;; *) echo "gear: P R N D 중 하나" >&2; exit 2;; esac
  put "/vehicle/gear?gear=$g"
}
# 브라우저 화면이 속도·가속 페달·브레이크를 한 요청에 함께 보내므로 같은 형식을 따른다
speed() { if [ "$1" = 0 ]; then put "/vehicle/controls?speed=0&accelerator=0&brake=100"; else put "/vehicle/controls?speed=$1&accelerator=20&brake=0"; fi; }
door() {
  case "$2" in open) v=true;; close) v=false;; *) echo "door: open 또는 close" >&2; exit 2;; esac
  case "$1" in fl|fr|rl|rr) put "/vehicle/body?door_$1=$v";; *) echo "door: fl fr rl rr 중 하나" >&2; exit 2;; esac
}
seat() {
  case "$2" in on) v=true;; off) v=false;; *) echo "seat: on 또는 off" >&2; exit 2;; esac
  put "/vehicle/seat?kind=occupied&value=$v&area=$1"
}
wiper() {
  case "$1" in off) m=Off;; auto) m=Auto;; low) m=Low;; high) m=High;; *) echo "wiper: off auto low high 중 하나" >&2; exit 2;; esac
  put "/vehicle/body?front_wiper=$m"
}
# adb emu geo fix 는 경도를 먼저 받는다
loc() { adb emu geo fix "$2" "$1" >/dev/null; }

state() {
  curl -fsS -m 10 "$V/vehicle/state" | python3 -c '
import json, sys
s = json.load(sys.stdin)
gear = (s["gear"].get("actual") or {}).get("value")
body = s["body"]
print("gear  ", gear)
print("speed ", s["controls"]["speed_kph"], "km/h")
print("doors ", ", ".join(k for k, v in body["doors"].items() if v) or "모두 닫힘")
print("seats ", ", ".join(k for k, v in s["seat"].get("occupied", {}).items() if v) or "모두 비어 있음")
print("wiper ", body["wipers"]["front"]["mode"])'
}

drive() {
  local lat1=$1 lon1=$2 lat2=$3 lon2=$4 secs=$5 kph=${6:-40}
  loc "$lat1" "$lon1"; sleep 2
  gear D; speed "$kph"
  for i in $(seq 1 "$secs"); do
    loc "$(echo "$lat1 + ($lat2 - $lat1) * $i / $secs" | bc -l)" "$(echo "$lon1 + ($lon2 - $lon1) * $i / $secs" | bc -l)"
    sleep 1
  done
  speed 0; gear P
}

# 방문지 1개를 실제 신호로 만든다. 앱은 차량 모드(시나리오 모드 아님)이고 동의한 상태여야 한다.
#   1) 강릉역 근처 → 강릉중앙시장 근처까지 60초 주행(직선 약 1.6km)
#   2) P, 운전석 도어 열고 닫기, 운전석 비움 → [머무는 분] 대기 (방문지 기준: 도어 열림 + 10분 이상)
#   3) 운전석 앉음, 도어 열고 닫기 → 안목해변 근처까지 60초 주행(직선 약 4.6km)
#   4) P 로 4분 대기 (드라이브 종료 기준: P 3분)
# 출발 전에 위치를 먼저 옮기고 5초 기다린다. 주행 중에 위치가 멀리 바뀌면 그 거리가 달린 거리에 더해진다.
visit() {
  local stay_min=${1:-11}
  gear P; speed 0; seat driver on
  loc 37.7637 128.8990; sleep 5
  echo "1/4 주행 60초"; drive 37.7637 128.8990 37.7540 128.8975 60
  echo "2/4 정차 ${stay_min}분 (도어 열고 닫기)"
  door fl open; sleep 3; seat driver off; door fl close
  sleep $((stay_min * 60))
  door fl open; sleep 3; seat driver on; door fl close; sleep 2
  echo "3/4 주행 60초"; drive 37.7540 128.8975 37.7720 128.9470 60
  echo "4/4 P 4분 대기"; sleep 240
  echo "끝. 앱 목록에서 여행을 확인한다"
}

cmd="${1:-}"; [ -n "$cmd" ] || { sed -n '2,16p' "$0"; exit 2; }
shift
connect
case "$cmd" in
  state|gear|speed|door|seat|wiper|loc|drive|visit) "$cmd" "$@";;
  *) echo "모르는 명령: $cmd" >&2; exit 2;;
esac
