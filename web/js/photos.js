import { spokenTime } from './format.js';

/**
 * 사진을 방문지에 배치한다. 순수 함수이며 입력을 바꾸지 않는다.
 * 촬영 시각(photo.minute, epoch 분)이 방문지 도착~출발(arriveMinute ~ arriveMinute + stayMinutes, 양 끝 포함)
 * 안이면 그 방문지에, 어느 방문지에도 들지 않으면 "이동 중" 묶음에 넣는다.
 * 구간이 겹치면 목록에서 앞에 있는 방문지에 넣는다. 각 묶음은 시각순이고 같은 시각은 입력 순서를 지킨다.
 * @returns {{stops: object[][], moving: object[]}} stops[i] 는 stops 입력의 i 번째 방문지에 붙은 사진
 */
export function assignPhotos(stops, photos) {
  const result = { stops: stops.map(() => []), moving: [] };
  const sorted = photos
    .map((photo, order) => ({ photo, order }))
    .sort((a, b) => a.photo.minute - b.photo.minute || a.order - b.order);
  for (const { photo } of sorted) {
    const index = stops.findIndex((s) => photo.minute >= s.arriveMinute && photo.minute <= s.arriveMinute + s.stayMinutes);
    (index >= 0 ? result.stops[index] : result.moving).push(photo);
  }
  return result;
}

/**
 * 타임라인 줄 목록. 정차마다 {type:'stop', index, photos} 한 줄, 이동 중 사진은 {type:'moving', photos} 줄로 끼운다.
 * 이동 중 사진은 그 사진 시각보다 앞서 도착한 마지막 정차 다음 줄에 놓고, 첫 정차보다 앞서면 맨 위에 놓는다.
 * 잠깐 정차는 화면에서 사진 줄이 없으므로 그 구간에 찍은 사진도 이동 중으로 다룬다.
 * stops 는 도착 시각순이어야 한다.
 */
export function timelineRows(stops, photos) {
  const assigned = assignPhotos(stops, photos);
  const loose = [...assigned.moving];
  stops.forEach((stop, index) => { if (stop.kind === 'BRIEF') loose.push(...assigned.stops[index]); });
  const order = new Map(photos.map((p, i) => [p, i]));
  loose.sort((a, b) => a.minute - b.minute || order.get(a) - order.get(b));

  const after = new Map();
  for (const photo of loose) {
    let slot = -1;
    stops.forEach((stop, index) => { if (stop.arriveMinute <= photo.minute) slot = index; });
    if (!after.has(slot)) after.set(slot, []);
    after.get(slot).push(photo);
  }
  const rows = [];
  if (after.has(-1)) rows.push({ type: 'moving', photos: after.get(-1) });
  stops.forEach((stop, index) => {
    rows.push({ type: 'stop', index, photos: stop.kind === 'BRIEF' ? [] : assigned.stops[index] });
    if (after.has(index)) rows.push({ type: 'moving', photos: after.get(index) });
  });
  return rows;
}

/**
 * 요약 문장과 아래 바 안내에 쓰는 수. photo.image 가 없으면 열 수 없는 형식, source 가 'file' 이면 시각 추정이다.
 * 열 수 없는 사진도 시각으로는 배치하므로 placed·moving 에 들어간다. 시각 추정 수에는 넣지 않는다(열 수 없음 안내를 따로 한다).
 */
export function photoCounts(rows, photos) {
  const stopRows = rows.filter((r) => r.type === 'stop');
  return {
    total: photos.length,
    placed: stopRows.reduce((sum, r) => sum + r.photos.length, 0),
    places: stopRows.filter((r) => r.photos.length > 0).length,
    moving: rows.filter((r) => r.type === 'moving').reduce((sum, r) => sum + r.photos.length, 0),
    estimated: photos.filter((p) => p.source === 'file' && p.image).length,
    unreadable: photos.filter((p) => !p.image).length,
  };
}

/** "들른 곳" 제목 아래 요약 줄. 첫 줄은 배치 결과, 다음 줄부터는 시각 추정·열 수 없는 형식 안내 */
export function photoSummary({ total, placed, places, moving, estimated, unreadable }) {
  if (total === 0) return [];
  const parts = [];
  if (placed > 0) parts.push(`${places}곳에 ${placed}장`);
  if (moving > 0) parts.push(`이동 중 ${moving}장`);
  const lines = [`사진 ${total}장: ${parts.join(', ')}`];
  if (estimated > 0) lines.push(`찍은 시각이 없는 ${estimated}장은 저장된 시각으로 놓았어요`);
  if (unreadable > 0) lines.push(`${unreadable}장은 이 브라우저에서 열 수 없는 형식(HEIC 등)이라 뺐어요`);
  return lines;
}

/**
 * 썸네일 버튼의 aria-label. "경포해변 사진, 11시 10분. 누르면 대표 사진으로 써요"
 * estimated 면 시각 뒤에 "쯤", representative 면 "대표 사진", action 이 false 면 누르는 안내를 뺀다.
 */
export function thumbLabel({ place, minute, estimated = false, representative = false, readable = true, action = true }) {
  const head = `${place} ${representative ? '대표 사진' : '사진'}, ${spokenTime(minute)}${estimated ? '쯤' : ''}`;
  if (!readable) return `${head}. 이 브라우저에서 열 수 없어요`;
  if (representative || !action) return head;
  return `${head}. 누르면 대표 사진으로 써요`;
}
