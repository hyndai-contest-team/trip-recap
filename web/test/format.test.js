import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  formatDistance, formatDuration, formatDate, formatTime, formatTimeRange, kakaoMapUrl, naverMapUrl,
  formatTripDuration, valueParts, spokenTime,
} from '../js/format.js';

test('거리: 10km 이상은 정수 km', () => {
  assert.equal(formatDistance(412000), '412km');
  assert.equal(formatDistance(235600), '236km');
  assert.equal(formatDistance(10000), '10km');
  assert.equal(formatDistance(10400), '10km');
});

test('거리: 10km 미만은 소수 1자리 (Kotlin Texts.distance 와 같은 표기)', () => {
  assert.equal(formatDistance(8500), '8.5km');
  assert.equal(formatDistance(8000), '8.0km');
  assert.equal(formatDistance(300), '0.3km');
  assert.equal(formatDistance(0), '0.0km');
  assert.equal(formatDistance(9900), '9.9km');
});

test('시간', () => {
  assert.equal(formatDuration(80), '1시간 20분');
  assert.equal(formatDuration(45), '45분');
  assert.equal(formatDuration(120), '2시간');
  assert.equal(formatDuration(0), '0분');
  assert.equal(formatDuration(1435), '23시간 55분');
  assert.equal(formatDuration(1200), '20시간');
});

test('날짜는 Asia/Seoul 기준', () => {
  assert.equal(formatDate(29900170), '2026년 11월 7일 (토)');
  assert.equal(formatDate(29901605), '2026년 11월 8일 (일)');
  // 2026-11-07 15:00 UTC = 한국 11-08 00:00
  assert.equal(formatDate(29901060), '2026년 11월 8일 (일)');
  assert.equal(formatDate(29901059), '2026년 11월 7일 (토)');
  assert.equal(formatDate(0), '1970년 1월 1일 (목)');
});

test('시각은 Asia/Seoul 기준 24시간 표기', () => {
  assert.equal(formatTime(29900300), '11:20');
  assert.equal(formatTime(29900170), '09:10');
  assert.equal(formatTime(29901060), '00:00');
  assert.equal(formatTime(29901059), '23:59');
});

test('도착-출발 시각', () => {
  assert.equal(formatTimeRange(29900300, 80), '11:20 – 12:40');
  assert.equal(formatTimeRange(29900405, 1200), '13:05 – 다음 날 09:05');
  assert.equal(formatTimeRange(29900405, 2640), '13:05 – 11월 9일 09:05');
  assert.equal(formatTimeRange(29900300, 0), '11:20 – 11:20');
});

test('카카오맵 링크', () => {
  assert.equal(
    kakaoMapUrl({ name: '경포해변', lat: 37.8057, lon: 128.9087 }),
    'https://map.kakao.com/link/map/%EA%B2%BD%ED%8F%AC%ED%95%B4%EB%B3%80,37.8057,128.9087',
  );
  assert.equal(
    kakaoMapUrl({ name: '카페 A&B, 본점', lat: 37.5, lon: 127 }),
    'https://map.kakao.com/link/map/%EC%B9%B4%ED%8E%98%20A%26B%2C%20%EB%B3%B8%EC%A0%90,37.5,127',
  );
});

test('네이버지도 링크', () => {
  assert.equal(
    naverMapUrl({ name: '경포해변', lat: 37.8057, lon: 128.9087 }),
    'https://map.naver.com/p/search/%EA%B2%BD%ED%8F%AC%ED%95%B4%EB%B3%80?c=128.9087,37.8057,15,0,0,0,dh',
  );
});

test('이름이 비면 지도 링크는 "방문지"를 이름으로 쓴다', () => {
  assert.equal(
    kakaoMapUrl({ name: '', lat: 37.5, lon: 127 }),
    'https://map.kakao.com/link/map/%EB%B0%A9%EB%AC%B8%EC%A7%80,37.5,127',
  );
});

// 2026-11-11 12:00 KST, 2027-01-02 09:00 KST
const NOW_2026 = Date.UTC(2026, 10, 11, 3, 0) / 60000;
const NOW_2027 = Date.UTC(2027, 0, 2, 0, 0) / 60000;

test('날짜: 올해면 연도를 빼고, 아니면 연도를 붙인다', () => {
  assert.equal(formatDate(29900170, NOW_2026), '11월 7일 (토)');
  assert.equal(formatDate(29900170, NOW_2027), '2026년 11월 7일 (토)');
  // 한국 시간으로 해를 가른다: 2026-12-31 15:00 UTC 는 한국 2027-01-01 00:00
  assert.equal(formatDate(Date.UTC(2026, 11, 31, 15, 0) / 60000, NOW_2027), '1월 1일 (금)');
  assert.equal(formatDate(Date.UTC(2026, 11, 31, 14, 59) / 60000, NOW_2027), '2026년 12월 31일 (목)');
});

test('걸린 시간: 숙박이 없으면 시간, 숙박 n번이면 "n박 n+1일"', () => {
  const stop = (kind) => ({ kind });
  assert.equal(formatTripDuration({ durationMinutes: 635, stops: [stop('VISIT'), stop('BRIEF')] }), '10시간 35분');
  assert.equal(formatTripDuration({ durationMinutes: 1435, stops: [stop('VISIT'), stop('LODGING')] }), '1박 2일');
  assert.equal(formatTripDuration({ durationMinutes: 3000, stops: [stop('LODGING'), stop('VISIT'), stop('LODGING')] }), '2박 3일');
  assert.equal(formatTripDuration({ durationMinutes: 45, stops: [] }), '45분');
});

test('숫자 칸 값은 숫자와 단위 조각으로 나눈다', () => {
  assert.deepEqual(valueParts('413km'), [{ num: '413', unit: 'km' }]);
  assert.deepEqual(valueParts('8.5km'), [{ num: '8.5', unit: 'km' }]);
  assert.deepEqual(valueParts('3곳'), [{ num: '3', unit: '곳' }]);
  assert.deepEqual(valueParts('10시간 35분'), [{ num: '10', unit: '시간' }, { num: '35', unit: '분' }]);
  assert.deepEqual(valueParts('2박 3일'), [{ num: '2', unit: '박' }, { num: '3', unit: '일' }]);
  assert.deepEqual(valueParts('45분'), [{ num: '45', unit: '분' }]);
});

test('읽어 주는 시각: "11시 10분", 정각은 "11시"', () => {
  assert.equal(spokenTime(29900290), '11시 10분');
  assert.equal(spokenTime(29900280), '11시');
  assert.equal(spokenTime(29901065), '0시 5분');
  assert.equal(spokenTime(29900565), '15시 45분');
});
