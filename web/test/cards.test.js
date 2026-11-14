import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  CARD_SIZES, STOP_PAGE_SIZE, tripStats, stopMarks, groupMarkers, routeHeight, routeLabel, layoutRoute,
  layoutCard, coverRect, layoutStopPages, fitText, drawCard, drawStopPage,
} from '../js/cards.js';

const fixture = JSON.parse(readFileSync(new URL('./fixtures/share-v1.json', import.meta.url), 'utf8')).expected;

// 이 테스트용 강릉 당일 표본. 출발·정차 시각과 좌표는 Kotlin 테스트 표본 Samples.gangneung,
// 골든 시나리오 GoldenScenarios.gangneungDayTrip 과 같다(9:10 출발, 635분 뒤 19:45 도착).
// route 는 집 → 횡성휴게소 → 정차 순서로 줄인 경로다. 집 좌표는 송파구청 근처 공공 지점의 근사값이다.
const kst = (h, m) => Date.UTC(2026, 10, 7, h, m) / 60000 - 540;
const NOW = Date.UTC(2026, 10, 11, 3, 0) / 60000;
const stop = (kind, name, lat, lon, arriveMinute, stayMinutes, rainy = false) => ({ kind, rainy, lat, lon, arriveMinute, stayMinutes, name });
const gangneung = {
  title: '경포해변 → 강릉중앙시장',
  startMinute: kst(9, 10),
  durationMinutes: 635,
  distanceM: 413000,
  people: 3,
  stops: [
    stop('VISIT', '경포해변', 37.8057, 128.9087, kst(11, 20), 80),
    stop('VISIT', '안목해변 카페', 37.7729, 128.9499, kst(13, 5), 65, true),
    stop('VISIT', '강릉중앙시장', 37.7553, 128.8963, kst(15, 0), 90),
    stop('BRIEF', '횡성휴게소', 37.4783, 127.9989, kst(17, 45), 8),
  ],
  route: [[37.5145, 127.1059], [37.4783, 127.9989], [37.8057, 128.9087], [37.7729, 128.9499], [37.7553, 128.8963], [37.4783, 127.9989]],
};

/** 시험용 글자 폭: 한글은 글자 크기만큼, 그 밖의 글자(숫자, 영문, 공백, 화살표)는 0.6배 */
const measure = (text, size) => [...text].reduce((w, ch) => w + (/[가-힣]/.test(ch) ? size : size * 0.6), 0);
const options = { measure, nowMinute: NOW };

function near(actual, expected, tolerance = 1e-6) {
  assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} 는 ${expected} ± ${tolerance} 밖이다`);
}

/** 호출과 속성 지정을 기록하는 가짜 CanvasRenderingContext2D. 글자 폭은 글자 수 × 10 */
function fakeContext() {
  const calls = [];
  const target = {
    calls,
    measureText: (text) => ({ width: [...text].length * 10 }),
    createLinearGradient: (...args) => { calls.push(['createLinearGradient', ...args]); return { addColorStop() {} }; },
  };
  return new Proxy(target, {
    get(t, key) {
      if (key in t) return t[key];
      return (...args) => { calls.push([key, ...args]); };
    },
    set(t, key, value) { calls.push(['set', key, value]); return true; },
  });
}
const texts = (ctx) => ctx.calls.filter((c) => c[0] === 'fillText').map((c) => c[1]);
const indexOfCall = (ctx, match) => ctx.calls.findIndex((c) => match.every((v, i) => c[i] === v));

test('카드 크기', () => {
  assert.deepEqual(CARD_SIZES.photo, { width: 1080, height: 1920 });
  assert.deepEqual(CARD_SIZES.story, { width: 1080, height: 1920 });
  assert.deepEqual(CARD_SIZES.square, { width: 1080, height: 1080 });
  assert.deepEqual(STOP_PAGE_SIZE, { width: 1080, height: 1350 });
});

test('숫자 3칸: 달린 거리, 들른 곳, 걸린 시간', () => {
  assert.deepEqual(tripStats(gangneung), [
    { value: '413km', label: '달린 거리' },
    { value: '3곳', label: '들른 곳' },
    { value: '10시간 35분', label: '걸린 시간' },
  ]);
});

test('숙박이 있는 여행의 걸린 시간은 "n박 n+1일"', () => {
  assert.deepEqual(tripStats(fixture), [
    { value: '236km', label: '달린 거리' },
    { value: '1곳', label: '들른 곳' },
    { value: '1박 2일', label: '걸린 시간' },
  ]);
});

test('들른 곳은 잠깐 정차와 숙박을 세지 않는다', () => {
  const s = (kind) => stop(kind, '', 37, 127, 0, 10);
  const t = { ...gangneung, stops: [s('VISIT'), s('BRIEF'), s('VISIT'), s('LODGING'), s('VISIT')] };
  assert.equal(tripStats(t)[1].value, '3곳');
});

test('번호 점: 방문지만 번호를 세고, 숙박은 "숙", 잠깐 정차는 번호 없음', () => {
  const s = (kind) => stop(kind, '', 37, 127, 0, 10);
  const stops = [s('VISIT'), s('BRIEF'), s('VISIT'), s('LODGING'), s('VISIT')];
  assert.deepEqual(stopMarks(stops), [
    { kind: 'visit', label: '1' }, { kind: 'brief', label: '' }, { kind: 'visit', label: '2' },
    { kind: 'lodging', label: '숙' }, { kind: 'visit', label: '3' },
  ]);
  // 숫자 칸의 "들른 곳 n곳"과 번호 최댓값이 같다
  const max = Math.max(...stopMarks(stops).filter((m) => m.kind === 'visit').map((m) => Number(m.label)));
  assert.equal(`${max}곳`, tripStats({ ...gangneung, stops }).at(1).value);
});

const dot = (x, y, kind, label) => ({ x, y, kind, label });

test('겹침 묶기: 떨어진 점은 따로 두고, 잠깐 정차 점을 먼저(아래에) 그린다', () => {
  assert.deepEqual(groupMarkers([dot(0, 0, 'visit', '1'), dot(100, 0, 'brief', ''), dot(200, 0, 'visit', '2')], 28), [
    { kind: 'brief', x: 100, y: 0, label: '', stops: [1] },
    { kind: 'number', x: 0, y: 0, label: '1', stops: [0] },
    { kind: 'number', x: 200, y: 0, label: '2', stops: [2] },
  ]);
});

test('겹침 묶기: 중심 거리가 지름×1.1 보다 작으면 하나의 pill 로 묶고 가운데에 둔다', () => {
  assert.deepEqual(groupMarkers([dot(0, 0, 'visit', '1'), dot(30, 0, 'visit', '2')], 28), [
    { kind: 'number', x: 15, y: 0, label: '1–2', stops: [0, 1] },
  ]);
  assert.equal(groupMarkers([dot(0, 0, 'visit', '1'), dot(31, 0, 'visit', '2')], 28).length, 2);
});

test('겹침 묶기: 연속한 번호만 묶는다', () => {
  const markers = groupMarkers([dot(0, 0, 'visit', '1'), dot(200, 0, 'visit', '2'), dot(10, 0, 'visit', '3')], 28);
  assert.deepEqual(markers.map((m) => m.label), ['1', '2', '3']);
});

test('겹침 묶기: 사슬처럼 이어진 점도 한 묶음이고, 사이의 잠깐 정차는 묶음을 끊지 않는다', () => {
  assert.deepEqual(groupMarkers([dot(0, 0, 'visit', '1'), dot(25, 0, 'visit', '2'), dot(50, 0, 'visit', '3')], 28).map((m) => m.label), ['1–3']);
  assert.deepEqual(groupMarkers([dot(0, 0, 'visit', '1'), dot(5, 0, 'brief', ''), dot(10, 0, 'visit', '2'), dot(20, 0, 'visit', '3')], 28), [
    { kind: 'brief', x: 5, y: 0, label: '', stops: [1] },
    { kind: 'number', x: 10, y: 0, label: '1–3', stops: [0, 2, 3] },
  ]);
});

test('겹침 묶기: 숙박은 "숙", 방문지와 겹치면 번호 뒤에 "·숙"', () => {
  assert.deepEqual(groupMarkers([dot(0, 0, 'lodging', '숙')], 28).map((m) => m.label), ['숙']);
  assert.deepEqual(groupMarkers([dot(0, 0, 'visit', '2'), dot(10, 0, 'lodging', '숙')], 28).map((m) => m.label), ['2·숙']);
  assert.deepEqual(groupMarkers([dot(0, 0, 'visit', '1'), dot(10, 0, 'lodging', '숙'), dot(20, 0, 'visit', '2')], 28).map((m) => m.label), ['1–2·숙']);
  assert.deepEqual(groupMarkers([dot(0, 0, 'lodging', '숙'), dot(10, 0, 'lodging', '숙')], 28).map((m) => m.label), ['숙']);
});

test('겹침 묶기: 묶은 pill 끼리 겹치면(점은 떨어져 있어도) 연속한 묶음을 다시 합친다', () => {
  // "1–2"(가운데 14,0)와 "3–4"(가운데 54,30): 점 사이 거리는 30.8 이상이지만 pill 폭(약 59)과 높이(28)로는 겹친다
  const markers = groupMarkers([dot(0, 0, 'visit', '1'), dot(28, 0, 'visit', '2'), dot(40, 30, 'visit', '3'), dot(68, 30, 'visit', '4')], 28);
  assert.deepEqual(markers, [{ kind: 'number', x: 34, y: 15, label: '1–4', stops: [0, 1, 2, 3] }]);
  // 세로로 충분히 떨어지면 합치지 않는다
  const apart = groupMarkers([dot(0, 0, 'visit', '1'), dot(28, 0, 'visit', '2'), dot(40, 40, 'visit', '3'), dot(68, 40, 'visit', '4')], 28);
  assert.deepEqual(apart.map((m) => m.label), ['1–2', '3–4']);
});

test('화면 경로 그림 높이: 경로 범위의 세로/가로 비율, 160~300 사이', () => {
  const t = (route, stops = []) => ({ ...gangneung, route, stops });
  // 위도 37~37.5, 경도 127~128: Mercator 세로/가로 = 0.6280. (350 - 48) × 0.6280 + 48 = 237.6
  assert.equal(routeHeight(t([[37, 127], [37.5, 128]]), 350), 238);
  assert.equal(routeHeight(t([[37, 127], [37, 128]]), 350), 160);
  assert.equal(routeHeight(t([[37, 127], [38, 127]]), 350), 300);
  assert.equal(routeHeight(t([[37, 127]]), 350), 160);
  assert.equal(routeHeight(t([], []), 350), 0);
});

test('경로 그림 설명: 번호가 붙는 곳을 순서대로', () => {
  assert.equal(routeLabel(gangneung.stops), '경포해변, 안목해변 카페, 강릉중앙시장 순서로 들른 경로');
  assert.equal(routeLabel(fixture.stops), '경포해변, 숙소 순서로 들른 경로');
  assert.equal(routeLabel([gangneung.stops[0]]), '경포해변에 들른 경로');
  assert.equal(routeLabel([]), '달린 경로');
});

test('경로 배치: 동서 방향 경로는 상자 가로를 채우고 세로 가운데에 놓인다', () => {
  const l = layoutRoute({ ...gangneung, stops: [], route: [[37, 127], [37, 128]] }, { x: 110, y: 260, width: 860, height: 890 }, 50);
  near(l.line[0][0], 160); near(l.line[0][1], 705);
  near(l.line[1][0], 920); near(l.line[1][1], 705);
  assert.equal(l.routeFromStops, false);
});

test('경로 배치: 정차마다 점 하나, 경로 범위는 방문지까지 포함', () => {
  const l = layoutRoute(gangneung, { x: 0, y: 0, width: 1000, height: 1000 }, 50);
  assert.deepEqual(l.dots.map((d) => [d.kind, d.label]), [['visit', '1'], ['visit', '2'], ['visit', '3'], ['brief', '']]);
  const extra = stop('VISIT', 'x', 37, 130, 0, 10);
  const wide = layoutRoute({ ...gangneung, stops: [extra], route: [[37, 128], [37, 129]] }, { x: 0, y: 0, width: 1000, height: 1000 }, 50);
  near(wide.line[0][0], 50); near(wide.line[1][0], 500); near(wide.dots[0].x, 950);
});

test('경로 배치: 경로 점이 2개 미만이면 방문지를 이은 선을 쓰고 그 사실을 표시한다', () => {
  const l = layoutRoute({ ...fixture, route: [] }, { x: 0, y: 0, width: 1080, height: 1080 }, 50);
  assert.equal(l.routeFromStops, true);
  assert.equal(l.line.length, 2);
  near(l.line[0][0], l.dots[0].x);
  near(l.line[1][1], l.dots[1].y);
  const none = layoutRoute({ ...fixture, stops: [], route: [] }, { x: 0, y: 0, width: 1080, height: 1080 }, 50);
  assert.deepEqual(none.line, []);
  assert.deepEqual(none.dots, []);
});

test('스토리 카드: 글자 좌표(날짜 1213, 제목 1269, 구분선 1399, 숫자 칸 위 끝 1425 · 기준선 1495, 브랜드 줄 위 끝 1603 · 기준선 1638)', () => {
  const l = layoutCard(gangneung, 'story', options);
  assert.deepEqual([l.width, l.height, l.background], [1080, 1920, 'plain']);
  assert.deepEqual(l.routeBox, { x: 110, y: 260, width: 860, height: 890 });
  assert.deepEqual(l.style, { lineWidth: 14, underWidth: 0, dotRadius: 34, dotFont: 40, briefRadius: 12, briefStroke: 6, briefStrokeColor: '#131417', shadow: false });
  assert.deepEqual(l.markers.map((m) => [m.kind, m.label]), [['brief', ''], ['number', '1–3']]);
  assert.deepEqual(l.date, { text: '11월 7일 (토)', x: 80, y: 1213, size: 40 });
  assert.deepEqual(l.title, { size: 76, lines: [{ text: '경포해변 → 강릉중앙시장', x: 80, y: 1269 }] });
  assert.deepEqual(l.divider, { x1: 80, x2: 1000, y: 1399 });
  assert.deepEqual(l.stats, {
    size: 80, unitSize: 44, unitGap: 16, labelSize: 36,
    columns: [
      { x: 80, parts: [{ num: '413', unit: 'km' }], label: '달린 거리', baseline: 1495, labelTop: 1522 },
      { x: 404, parts: [{ num: '3', unit: '곳' }], label: '들른 곳', baseline: 1495, labelTop: 1522 },
      { x: 660, parts: [{ num: '10', unit: '시간' }, { num: '35', unit: '분' }], label: '걸린 시간', baseline: 1495, labelTop: 1522 },
    ],
  });
  assert.deepEqual(l.footer, { brand: { text: '왔다감 · Wheel Be Back', x: 80, y: 1638 }, tagline: { text: '차에서 기록한 여행', x: 1000, y: 1638 } });
  assert.equal(l.brand, null);
});

test('스토리 카드: 위 250px, 아래 280px 안에는 아무것도 놓지 않는다', () => {
  const l = layoutCard(gangneung, 'story', options);
  assert.ok(l.routeBox.y >= 250);
  assert.ok(l.date.y >= 250);
  assert.ok(l.footer.brand.y <= 1920 - 280);
  assert.ok(l.stats.columns.every((c) => c.labelTop + l.stats.labelSize <= 1920 - 280));
});

test('사진 배경 카드: 글자 좌표는 스토리와 같고 경로 상자는 오른쪽 위, 선 아래 어두운 선과 글자 그림자', () => {
  const l = layoutCard(gangneung, 'photo', options);
  const s = layoutCard(gangneung, 'story', options);
  assert.equal(l.background, 'photo');
  assert.deepEqual(l.routeBox, { x: 300, y: 260, width: 700, height: 560 });
  assert.deepEqual(l.style, { lineWidth: 14, underWidth: 22, dotRadius: 30, dotFont: 36, briefRadius: 12, briefStroke: 6, briefStrokeColor: 'rgba(19,20,23,0.55)', shadow: true });
  assert.deepEqual([l.date, l.title, l.divider, l.stats, l.footer], [s.date, s.title, s.divider, s.stats, s.footer]);
});

test('정사각형 카드: 글자·경로 상자 좌표, 숫자 3칸은 x 150–930 안', () => {
  const l = layoutCard(gangneung, 'square', options);
  assert.deepEqual([l.width, l.height], [1080, 1080]);
  assert.deepEqual(l.brand, { text: '왔다감 · Wheel Be Back', x: 120, y: 73 });
  assert.deepEqual(l.routeBox, { x: 120, y: 150, width: 840, height: 410 });
  assert.deepEqual(l.date, { text: '11월 7일 (토)', x: 120, y: 613, size: 40 });
  assert.deepEqual(l.title, { size: 68, lines: [{ text: '경포해변 → 강릉중앙시장', x: 120, y: 666 }] });
  assert.deepEqual(l.divider, { x1: 120, x2: 960, y: 789 });
  assert.deepEqual([l.stats.size, l.stats.unitSize, l.stats.unitGap], [72, 40, 14]);
  assert.deepEqual(l.stats.columns.map((c) => [c.x, c.baseline, c.labelTop]), [[150, 896, 918], [411, 896, 918], [623, 896, 918]]);
  assert.equal(l.footer, null);
});

test('제목: 76 에서 넘치면 60, 그래도 넘치면 60 두 줄(날짜와 함께 한 줄 위로)', () => {
  const one = layoutCard({ ...gangneung, title: '경포해변에서 강릉중앙시장까지' }, 'story', options);
  assert.deepEqual(one.title, { size: 60, lines: [{ text: '경포해변에서 강릉중앙시장까지', x: 80, y: 1269 }] });
  assert.equal(one.date.y, 1213);

  const two = layoutCard({ ...gangneung, title: '할머니 생신 기념 강릉 가족 여행 첫째 날 경포해변에서 중앙시장까지' }, 'story', options);
  assert.deepEqual(two.title, {
    size: 60,
    lines: [{ text: '할머니 생신 기념 강릉 가족', x: 80, y: 1203 }, { text: '여행 첫째 날 경포해변에서 중…', x: 80, y: 1269 }],
  });
  assert.equal(two.date.y, 1147);
});

test('제목이 비면 "여행"으로 적는다', () => {
  assert.equal(layoutCard({ ...gangneung, title: '' }, 'square', options).title.lines[0].text, '여행');
});

test('숫자 칸이 넘치면 80 → 72 → 64 로 낮춘다', () => {
  const wide = (text, size) => [...text].length * size;
  const big = { ...gangneung, distanceM: 1234567, durationMinutes: 6000, stops: Array.from({ length: 12 }, () => gangneung.stops[0]) };
  const l = layoutCard(big, 'story', { measure: wide, nowMinute: NOW });
  assert.deepEqual([l.stats.size, l.stats.unitSize, l.stats.unitGap], [64, 36, 12]);
  assert.deepEqual(l.stats.columns.map((c) => c.x), [80, 490, 736]);
  assert.equal(l.stats.columns[0].labelTop, 1425 + 64 + 17);
});

test('cover 맞춤: 가로 사진을 세로 상자에 넣으면 좌우를 자른다', () => {
  assert.deepEqual(coverRect(4000, 3000, 1080, 1920), { sx: 1156.25, sy: 0, sw: 1687.5, sh: 3000 });
});

test('cover 맞춤: 세로 사진을 정사각형 상자에 넣으면 위아래를 자른다', () => {
  assert.deepEqual(coverRect(3000, 4000, 1080, 1080), { sx: 0, sy: 500, sw: 3000, sh: 3000 });
});

test('cover 맞춤: 비율이 같으면 전체를 쓴다', () => {
  assert.deepEqual(coverRect(2160, 3840, 1080, 1920), { sx: 0, sy: 0, sw: 2160, sh: 3840 });
});

const allPhotos = () => true;

test('들른 곳별: 첫 장 머리 띠(날짜, 브랜드, 제목)와 칸 좌표. 잠깐 정차는 넣지 않는다', () => {
  const pages = layoutStopPages(gangneung, { nowMinute: NOW, hasPhoto: allPhotos });
  assert.equal(pages.length, 1);
  const [page] = pages;
  assert.deepEqual([page.width, page.height, page.index, page.total], [1080, 1350, 0, 1]);
  assert.deepEqual(page.header, {
    height: 170,
    date: { text: '11월 7일 (토)', x: 60, y: 38 },
    brand: { text: '왔다감 · Wheel Be Back', x: 1020, y: 38 },
    title: { text: '경포해변 → 강릉중앙시장', x: 60, y: 88, maxWidth: 960 },
  });
  assert.equal(page.band, null);
  assert.deepEqual(page.tiles.map((t) => [t.stopIndex, t.label, t.y, t.height]), [[0, '1', 170, 391], [1, '2', 565, 391], [2, '3', 960, 390]]);
  const [a, b] = page.tiles;
  assert.deepEqual(a, {
    stopIndex: 0, label: '1', kind: 'visit', x: 0, y: 170, width: 1080, height: 391,
    name: '경포해변', detail: '11:20 – 12:40 · 1시간 20분', rainy: false,
    badge: { x: 100, y: 455, radius: 40 }, textX: 168, nameY: 450, detailY: 507, maxWidth: 852, shadeTop: 301,
  });
  assert.equal(b.rainy, true);
  assert.equal(b.detail, '13:05 – 14:10 · 1시간 5분');
});

test('들른 곳별: 사진이 없는 칸은 높이를 절반으로', () => {
  const [page] = layoutStopPages(gangneung, { nowMinute: NOW, hasPhoto: (i) => i !== 1 });
  assert.deepEqual(page.tiles.map((t) => [t.y, t.height]), [[170, 469], [643, 234], [881, 469]]);
  // 칸보다 긴 어둡게 하는 띠는 칸 위 끝에서 자른다
  assert.equal(page.tiles[1].shadeTop, 643);
});

test('들른 곳별: 둘째 장부터는 머리 띠 대신 아래 띠와 "2/2"', () => {
  const s = (i) => stop('VISIT', `장소${i + 1}`, 37, 127, kst(11, 20) + i * 100, 80);
  const pages = layoutStopPages({ ...gangneung, stops: [0, 1, 2, 3, 4].map(s) }, { nowMinute: NOW, hasPhoto: allPhotos });
  assert.deepEqual(pages.map((p) => p.tiles.map((t) => t.label)), [['1', '2', '3'], ['4', '5']]);
  const second = pages[1];
  assert.equal(second.header, null);
  assert.deepEqual(second.band, { y: 1230, height: 120, brand: { text: '왔다감 · Wheel Be Back', x: 60, y: 1290 }, pill: { text: '2/2', right: 1020, y: 1290, height: 56 } });
  assert.deepEqual(second.tiles.map((t) => [t.y, t.height]), [[0, 613], [617, 613]]);
});

test('들른 곳별: 장마다 칸 수를 고르게 나누고, 숙박은 "숙"', () => {
  const s = (i, kind = 'VISIT') => stop(kind, '', 37, 127, kst(11, 0) + i * 100, 80);
  const counts = (n) => layoutStopPages({ ...gangneung, stops: Array.from({ length: n }, (_, i) => s(i)) }, { nowMinute: NOW, hasPhoto: allPhotos }).map((p) => p.tiles.length);
  assert.deepEqual(counts(0), []);
  assert.deepEqual(counts(1), [1]);
  assert.deepEqual(counts(3), [3]);
  assert.deepEqual(counts(4), [2, 2]);
  assert.deepEqual(counts(7), [3, 2, 2]);
  assert.deepEqual(counts(10), [3, 3, 2, 2]);
  const [page] = layoutStopPages({ ...gangneung, stops: [s(0), s(1, 'LODGING'), s(2)] }, { nowMinute: NOW, hasPhoto: allPhotos });
  assert.deepEqual(page.tiles.map((t) => [t.label, t.kind, t.name]), [['1', 'visit', '방문지'], ['숙', 'lodging', '방문지'], ['2', 'visit', '방문지']]);
});

test('글자 줄이기: 폭을 넘으면 뒤를 자르고 …를 붙인다', () => {
  const ctx = fakeContext();
  assert.equal(fitText(ctx, '가나다라마', 50), '가나다라마');
  assert.equal(fitText(ctx, '가나다라마바사', 50), '가나다라…');
  assert.equal(fitText(ctx, '가나다', 5), '…');
});

test('스토리 카드 그리기: 바탕, 경로 선 14, 번호 pill, 숫자·단위·라벨, 날짜, 제목, 브랜드 줄', () => {
  const ctx = fakeContext();
  drawCard(ctx, layoutCard(gangneung, 'story', options));
  assert.deepEqual(ctx.calls.find((c) => c[0] === 'fillRect'), ['fillRect', 0, 0, 1080, 1920]);
  assert.ok(indexOfCall(ctx, ['set', 'lineWidth', 14]) >= 0);
  assert.equal(indexOfCall(ctx, ['set', 'lineWidth', 22]), -1);
  const drawn = texts(ctx);
  for (const s of ['1–3', '413', 'km', '3', '곳', '10', '시간', '35', '분', '달린 거리', '들른 곳', '걸린 시간',
    '11월 7일 (토)', '경포해변 → 강릉중앙시장', '왔다감 · Wheel Be Back', '차에서 기록한 여행']) {
    assert.ok(drawn.includes(s), `"${s}" 를 그리지 않았다`);
  }
  assert.equal(ctx.calls.some((c) => c[0] === 'drawImage'), false);
  assert.equal(ctx.calls.some((c) => c[0] === 'set' && c[1] === 'shadowBlur' && c[2] > 0), false);
});

test('사진 배경 카드 그리기: cover 사진, 위 1/4 과 아래 정보 영역만 어둡게, 어두운 선 22 뒤 앰버 선 14, 글자 그림자', () => {
  const ctx = fakeContext();
  const image = { width: 4000, height: 3000 };
  drawCard(ctx, layoutCard(gangneung, 'photo', options), { photo: image });
  assert.deepEqual(ctx.calls.find((c) => c[0] === 'drawImage'), ['drawImage', image, 1156.25, 0, 1687.5, 3000, 0, 0, 1080, 1920]);
  const gradients = ctx.calls.filter((c) => c[0] === 'createLinearGradient');
  assert.deepEqual(gradients, [['createLinearGradient', 0, 0, 0, 480], ['createLinearGradient', 0, 1050, 0, 1920]]);
  const under = indexOfCall(ctx, ['set', 'lineWidth', 22]);
  const line = indexOfCall(ctx, ['set', 'lineWidth', 14]);
  assert.ok(under >= 0 && under < line, '어두운 선을 먼저 그린다');
  const shadow = indexOfCall(ctx, ['set', 'shadowBlur', 12]);
  const date = indexOfCall(ctx, ['fillText', '11월 7일 (토)']);
  assert.ok(shadow >= 0 && shadow < date, '글자 전에 그림자를 켠다');
});

test('들른 곳별 그리기: 머리 띠 글자, 사진이 있는 칸만 사진, 이름·시각, 비 칩', () => {
  const ctx = fakeContext();
  const [page] = layoutStopPages(gangneung, { nowMinute: NOW, hasPhoto: (i) => i === 0 });
  const image = { width: 3000, height: 4000 };
  drawStopPage(ctx, page, [image, null, null]);
  const images = ctx.calls.filter((c) => c[0] === 'drawImage');
  assert.equal(images.length, 1);
  // 사진 있는 칸 1 : 없는 칸 0.5 × 2 → 첫 칸 높이 1172 / 2 = 586.
  // 3000×4000 사진을 1080×586 칸에 cover: 폭 3000 전체, 높이 1627.78, 위쪽 여백 1186.11
  assert.equal(images[0][1], image);
  near(images[0][2], 0); near(images[0][3], 1186.111, 0.001); near(images[0][4], 3000); near(images[0][5], 1627.778, 0.001);
  assert.deepEqual(images[0].slice(6), [0, 170, 1080, 586]);
  const drawn = texts(ctx);
  for (const s of ['11월 7일 (토)', '왔다감 · Wheel Be Back', '경포해변 → 강릉중앙시장', '1', '2', '3', '경포해변', '안목해변 카페', '강릉중앙시장',
    '11:20 – 12:40 · 1시간 20분', '비']) {
    assert.ok(drawn.includes(s), `"${s}" 를 그리지 않았다`);
  }
  assert.equal(drawn.includes('횡성휴게소'), false);
});

test('들른 곳별 그리기: 둘째 장 아래 띠에 브랜드와 "2/2"', () => {
  const s = (i) => stop('VISIT', `장소${i + 1}`, 37, 127, kst(11, 20) + i * 100, 80);
  const pages = layoutStopPages({ ...gangneung, stops: [0, 1, 2, 3].map(s) }, { nowMinute: NOW, hasPhoto: () => false });
  const ctx = fakeContext();
  drawStopPage(ctx, pages[1], []);
  const drawn = texts(ctx);
  assert.ok(drawn.includes('2/2'));
  assert.ok(drawn.includes('왔다감 · Wheel Be Back'));
  assert.ok(drawn.includes('장소3'));
});
