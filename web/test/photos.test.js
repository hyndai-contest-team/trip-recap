import { test } from 'node:test';
import assert from 'node:assert/strict';
import { assignPhotos, timelineRows, photoCounts, photoSummary, thumbLabel } from '../js/photos.js';

const stops = [
  { name: '경포해변', arriveMinute: 29900300, stayMinutes: 80 },
  { name: '숙소', arriveMinute: 29900405, stayMinutes: 1200 },
];
const photo = (id, minute) => ({ id, minute });
const ids = (list) => list.map((p) => p.id);

test('도착 시각과 출발 시각에 찍은 사진은 그 방문지에 들어간다 (양 끝 포함)', () => {
  const out = assignPhotos(stops, [photo('도착', 29900300), photo('출발', 29900380)]);
  assert.deepEqual(ids(out.stops[0]), ['도착', '출발']);
  assert.deepEqual(out.stops[1], []);
  assert.deepEqual(out.moving, []);
});

test('도착 1분 전, 출발 1분 뒤 사진은 이동 중 묶음', () => {
  const out = assignPhotos(stops, [photo('뒤', 29900381), photo('앞', 29900299)]);
  assert.deepEqual(out.stops, [[], []]);
  assert.deepEqual(ids(out.moving), ['앞', '뒤']);
});

test('방문지별로 시각순 정렬하고, 같은 시각은 입력 순서를 지킨다', () => {
  const out = assignPhotos(stops, [
    photo('c', 29900350), photo('a', 29900310), photo('b1', 29900320), photo('b2', 29900320),
    photo('숙소2', 29901605), photo('숙소1', 29900405), photo('이동', 29900390),
  ]);
  assert.deepEqual(ids(out.stops[0]), ['a', 'b1', 'b2', 'c']);
  assert.deepEqual(ids(out.stops[1]), ['숙소1', '숙소2']);
  assert.deepEqual(ids(out.moving), ['이동']);
});

test('사진 객체를 그대로 돌려주고 입력 배열을 바꾸지 않는다', () => {
  const a = photo('a', 29900350);
  const b = photo('b', 29900310);
  const input = [a, b];
  const out = assignPhotos(stops, input);
  assert.equal(out.stops[0][0], b);
  assert.equal(out.stops[0][1], a);
  assert.deepEqual(input, [a, b]);
});

test('체류 0분 방문지는 도착 시각의 사진만 받는다', () => {
  const out = assignPhotos([{ arriveMinute: 100, stayMinutes: 0 }], [photo('x', 100), photo('y', 101)]);
  assert.deepEqual(ids(out.stops[0]), ['x']);
  assert.deepEqual(ids(out.moving), ['y']);
});

test('두 방문지 구간이 겹치면 먼저 도착한 방문지에 넣는다', () => {
  const overlapping = [{ arriveMinute: 100, stayMinutes: 20 }, { arriveMinute: 120, stayMinutes: 10 }];
  const out = assignPhotos(overlapping, [photo('x', 120)]);
  assert.deepEqual(ids(out.stops[0]), ['x']);
  assert.deepEqual(out.stops[1], []);
});

test('방문지나 사진이 없을 때', () => {
  assert.deepEqual(assignPhotos([], []), { stops: [], moving: [] });
  assert.deepEqual(ids(assignPhotos([], [photo('x', 5)]).moving), ['x']);
  assert.deepEqual(assignPhotos(stops, []), { stops: [[], []], moving: [] });
});

// 강릉 당일 표본(Kotlin Samples.gangneung 과 같은 정차 시각): 경포해변 11:20–12:40, 안목해변 카페 13:05–14:10, 강릉중앙시장 15:00–16:30, 횡성휴게소 17:45–17:53
const kst = (h, m) => Date.UTC(2026, 10, 7, h, m) / 60000 - 540;
const day = [
  { kind: 'VISIT', name: '경포해변', arriveMinute: kst(11, 20), stayMinutes: 80 },
  { kind: 'VISIT', name: '안목해변 카페', arriveMinute: kst(13, 5), stayMinutes: 65 },
  { kind: 'VISIT', name: '강릉중앙시장', arriveMinute: kst(15, 0), stayMinutes: 90 },
  { kind: 'BRIEF', name: '횡성휴게소', arriveMinute: kst(17, 45), stayMinutes: 8 },
];
const shape = (rows) => rows.map((r) => (r.type === 'stop' ? `${r.index}:${ids(r.photos).join(',')}` : `이동:${ids(r.photos).join(',')}`));

test('타임라인: 이동 중 사진은 그 시각보다 앞선 마지막 정차 다음에 놓는다', () => {
  const rows = timelineRows(day, [
    photo('바다', kst(11, 30)), photo('가는길', kst(12, 50)), photo('카페', kst(13, 30)), photo('시장', kst(15, 10)),
    photo('저녁', kst(17, 0)), photo('밤', kst(19, 0)),
  ]);
  assert.deepEqual(shape(rows), ['0:바다', '이동:가는길', '1:카페', '2:시장', '이동:저녁', '3:', '이동:밤']);
});

test('타임라인: 첫 정차보다 앞선 사진은 맨 위, 같은 구간의 이동 중 사진은 한 줄로 묶는다', () => {
  const rows = timelineRows(day, [photo('b', kst(10, 15)), photo('a', kst(9, 0)), photo('c', kst(12, 45)), photo('d', kst(12, 55))]);
  assert.deepEqual(shape(rows), ['이동:a,b', '0:', '이동:c,d', '1:', '2:', '3:']);
});

test('타임라인: 잠깐 정차 구간에 찍은 사진은 그 정차 다음 이동 중 줄에 넣는다', () => {
  const rows = timelineRows(day, [photo('휴게소', kst(17, 50))]);
  assert.deepEqual(shape(rows), ['0:', '1:', '2:', '3:', '이동:휴게소']);
});

test('타임라인: 사진이 없으면 정차만 순서대로 나온다', () => {
  assert.deepEqual(shape(timelineRows(day, [])), ['0:', '1:', '2:', '3:']);
  assert.deepEqual(timelineRows([], []), []);
});

test('사진 수 세기: 붙은 곳 수, 붙은 장 수, 이동 중, 시각 추정, 열 수 없음', () => {
  const p = (id, minute, extra = {}) => ({ id, minute, source: 'exif', image: {}, ...extra });
  const photos = [
    p(1, kst(11, 30)), p(2, kst(11, 40)), p(3, kst(12, 0), { image: null }),
    p(4, kst(13, 10)), p(5, kst(13, 20), { source: 'file' }),
    p(6, kst(15, 5)), p(7, kst(15, 30)), p(8, kst(10, 15)),
  ];
  assert.deepEqual(photoCounts(timelineRows(day, photos), photos), { total: 8, placed: 7, places: 3, moving: 1, estimated: 1, unreadable: 1 });
});

test('사진 수 세기: 열 수 없는 사진은 시각 추정 수에 넣지 않는다(열 수 없음 안내만 한다)', () => {
  const photos = [{ id: 1, minute: kst(11, 30), source: 'file', image: null }, { id: 2, minute: kst(11, 40), source: 'file', image: {} }];
  const counts = photoCounts(timelineRows(day, photos), photos);
  assert.deepEqual([counts.estimated, counts.unreadable], [1, 1]);
});

test('요약 문장: 강릉 당일 예시', () => {
  assert.deepEqual(photoSummary({ total: 8, placed: 7, places: 3, moving: 1, estimated: 1, unreadable: 0 }), [
    '사진 8장: 3곳에 7장, 이동 중 1장',
    '찍은 시각이 없는 1장은 저장된 시각으로 놓았어요',
  ]);
});

test('요약 문장: 이동 중이 없을 때, 모두 이동 중일 때, 열 수 없는 형식이 있을 때', () => {
  assert.deepEqual(photoSummary({ total: 2, placed: 2, places: 1, moving: 0, estimated: 0, unreadable: 0 }), ['사진 2장: 1곳에 2장']);
  assert.deepEqual(photoSummary({ total: 3, placed: 0, places: 0, moving: 3, estimated: 0, unreadable: 0 }), ['사진 3장: 이동 중 3장']);
  assert.deepEqual(photoSummary({ total: 9, placed: 8, places: 3, moving: 1, estimated: 2, unreadable: 1 }), [
    '사진 9장: 3곳에 8장, 이동 중 1장',
    '찍은 시각이 없는 2장은 저장된 시각으로 놓았어요',
    '1장은 이 브라우저에서 열 수 없는 형식(HEIC 등)이라 뺐어요',
  ]);
  assert.deepEqual(photoSummary({ total: 0, placed: 0, places: 0, moving: 0, estimated: 0, unreadable: 0 }), []);
});

test('썸네일 이름: 장소, 시각, 누르면 일어나는 일', () => {
  assert.equal(thumbLabel({ place: '경포해변', minute: kst(11, 10) }), '경포해변 사진, 11시 10분. 누르면 대표 사진으로 써요');
  assert.equal(thumbLabel({ place: '경포해변', minute: kst(11, 10), representative: true }), '경포해변 대표 사진, 11시 10분');
  assert.equal(thumbLabel({ place: '안목해변 카페', minute: kst(14, 0), estimated: true }), '안목해변 카페 사진, 14시쯤. 누르면 대표 사진으로 써요');
  assert.equal(thumbLabel({ place: '경포해변', minute: kst(11, 10), readable: false }), '경포해변 사진, 11시 10분. 이 브라우저에서 열 수 없어요');
  assert.equal(thumbLabel({ place: '이동 중', minute: kst(10, 15), action: false }), '이동 중 사진, 10시 15분');
});
