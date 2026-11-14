// 화면과 카드에 쓰는 문자열. 시각은 기기 시간대와 상관없이 Asia/Seoul(UTC+9, 서머타임 없음) 기준이다.
// 거리·시간 표기는 차량 앱의 Texts(TripSummary.kt)와 같다.

const KST_OFFSET_MINUTES = 9 * 60;
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

/** epoch 분 → 한국 시간의 연·월·일·요일·시·분 */
function kst(epochMinute) {
  const d = new Date((epochMinute + KST_OFFSET_MINUTES) * 60000);
  return {
    year: d.getUTCFullYear(),
    month: d.getUTCMonth() + 1,
    day: d.getUTCDate(),
    weekday: WEEKDAYS[d.getUTCDay()],
    hour: d.getUTCHours(),
    minute: d.getUTCMinutes(),
    dayNumber: Math.floor((epochMinute + KST_OFFSET_MINUTES) / 1440),
  };
}

const two = (n) => String(n).padStart(2, '0');

/** 412000 → "412km", 8500 → "8.5km" (10km 미만은 소수 1자리) */
export function formatDistance(meters) {
  const km = meters / 1000;
  return km >= 10 ? `${Math.round(km)}km` : `${(Math.round(km * 10) / 10).toFixed(1)}km`;
}

/** 80 → "1시간 20분", 45 → "45분", 120 → "2시간" */
export function formatDuration(minutes) {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h > 0 && m > 0) return `${h}시간 ${m}분`;
  if (h > 0) return `${h}시간`;
  return `${m}분`;
}

/**
 * "11월 7일 (토)". nowMinute(epoch 분)과 한국 시간 기준 해가 다르거나 nowMinute 를 주지 않으면
 * 연도를 붙인다: "2025년 11월 10일 (월)"
 */
export function formatDate(epochMinute, nowMinute) {
  const t = kst(epochMinute);
  const md = `${t.month}월 ${t.day}일 (${t.weekday})`;
  return nowMinute !== undefined && kst(nowMinute).year === t.year ? md : `${t.year}년 ${md}`;
}

/** 숫자 칸 "걸린 시간". 숙박 정차가 n번이면 "n박 n+1일", 없으면 "10시간 35분" */
export function formatTripDuration(trip) {
  const nights = trip.stops.filter((s) => s.kind === 'LODGING').length;
  return nights > 0 ? `${nights}박 ${nights + 1}일` : formatDuration(trip.durationMinutes);
}

/** 숫자 칸 값을 숫자와 단위 조각으로 나눈다. 숫자와 단위를 다른 크기로 그리려고 쓴다. "10시간 35분" → [10 시간][35 분] */
export function valueParts(text) {
  return [...text.matchAll(/([0-9][0-9.,]*)\s*([^0-9\s]*)/g)].map((m) => ({ num: m[1], unit: m[2] }));
}

/** 화면 읽기 프로그램용 시각. "11시 10분", 정각은 "11시" */
export function spokenTime(epochMinute) {
  const t = kst(epochMinute);
  return t.minute === 0 ? `${t.hour}시` : `${t.hour}시 ${t.minute}분`;
}

/** "11:20" */
export function formatTime(epochMinute) {
  const t = kst(epochMinute);
  return `${two(t.hour)}:${two(t.minute)}`;
}

/** 도착–출발. 출발이 다음 날이면 "13:05 – 다음 날 09:05", 그 뒤 날짜면 "13:05 – 11월 9일 09:05" */
export function formatTimeRange(arriveMinute, stayMinutes) {
  const leaveMinute = arriveMinute + stayMinutes;
  const a = kst(arriveMinute);
  const l = kst(leaveMinute);
  const days = l.dayNumber - a.dayNumber;
  let prefix = '';
  if (days === 1) prefix = '다음 날 ';
  else if (days > 1) prefix = `${l.month}월 ${l.day}일 `;
  return `${formatTime(arriveMinute)} – ${prefix}${formatTime(leaveMinute)}`;
}

const placeName = (stop) => encodeURIComponent(stop.name || '방문지');

/** 카카오맵에서 이름을 붙인 좌표를 연다 */
export function kakaoMapUrl(stop) {
  return `https://map.kakao.com/link/map/${placeName(stop)},${stop.lat},${stop.lon}`;
}

/** 네이버지도에서 좌표를 중심으로 이름을 검색한다 */
export function naverMapUrl(stop) {
  return `https://map.naver.com/p/search/${placeName(stop)}?c=${stop.lon},${stop.lat},15,0,0,0,dh`;
}
