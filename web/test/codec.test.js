import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { decodeHash, decode, encode, encodeHash } from '../js/codec.js';

const fixture = JSON.parse(readFileSync(new URL('./fixtures/share-v1.json', import.meta.url), 'utf8'));

test('Kotlin 이 만든 hash 를 fixture 의 기대값으로 푼다', () => {
  assert.deepEqual(decodeHash(fixture.hash), fixture.expected);
});

test('hash 앞의 # 는 무시한다', () => {
  assert.deepEqual(decodeHash('#' + fixture.hash), fixture.expected);
});

test('fixture 기대값을 인코딩하면 Kotlin 이 만든 hash 와 같다', () => {
  assert.equal(encodeHash(fixture.expected), fixture.hash);
});

test('남반구·서경 좌표, 좌표 감소, 큰 값도 왕복한다', () => {
  const trip = {
    title: 'Río → 山',
    startMinute: 29900170,
    durationMinutes: 4000,
    distanceM: 1234500,
    people: 0,
    stops: [
      { kind: 'VISIT', rainy: true, lat: -33.8568, lon: -151.2153, arriveMinute: 29900170, stayMinutes: 0, name: '' },
      { kind: 'BRIEF', rainy: false, lat: -33.9, lon: -151.3, arriveMinute: 29900200, stayMinutes: 5, name: '주유소' },
      { kind: 'LODGING', rainy: false, lat: 0, lon: 0.0001, arriveMinute: 29903000, stayMinutes: 2880, name: 'a'.repeat(200) },
    ],
    route: [[-33.8568, -151.2153], [-33.8569, -151.2152], [12.3456, 179.9999], [-0.0001, -0.0001]],
  };
  assert.deepEqual(decodeHash(encodeHash(trip)), trip);
});

test('방문지와 경로가 없어도 왕복한다', () => {
  const trip = { title: '빈 여행', startMinute: 1, durationMinutes: 0, distanceM: 0, people: 1, stops: [], route: [] };
  assert.deepEqual(decode(encode(trip)), trip);
});

test('바이트 형식: 첫 바이트는 버전 1, hash 는 base64url 문자만 쓴다', () => {
  const bytes = encode(fixture.expected);
  assert.equal(bytes[0], 1);
  assert.equal(bytes.length, 81);
  assert.match(encodeHash(fixture.expected), /^[A-Za-z0-9_-]+$/);
});

test('빈 hash 는 예외', () => {
  assert.throws(() => decodeHash(''));
  assert.throws(() => decodeHash('#'));
  assert.throws(() => decodeHash(undefined));
});

test('base64url 이 아닌 문자가 있으면 예외', () => {
  assert.throws(() => decodeHash('AYr7oA6b*C7QS'));
  assert.throws(() => decodeHash('한글'));
  assert.throws(() => decodeHash('AYr7o')); // 길이 4n+1 은 base64 로 풀 수 없다
});

test('지원하지 않는 버전은 예외', () => {
  assert.throws(() => decodeHash('Ag' + fixture.hash.slice(2)), /버전/);
  assert.throws(() => decode(new Uint8Array([0])), /버전/);
});

test('중간에 잘린 데이터는 예외', () => {
  assert.throws(() => decodeHash(fixture.hash.slice(0, 20)));
  assert.throws(() => decodeHash(fixture.hash.slice(0, 60)));
  assert.throws(() => decodeHash('AQ'));
});

test('정의되지 않은 방문지 종류(3)는 예외', () => {
  const bytes = encode(fixture.expected);
  const flagsIndex = 1 + 4 + 2 + 2 + 1 + 13 + 1; // version, start, duration, distance, people, title(1+12), stopCount
  assert.equal(bytes[flagsIndex], 0);
  bytes[flagsIndex] = 3;
  assert.throws(() => decode(bytes));
});

test('끝나지 않는 varint 는 예외', () => {
  assert.throws(() => decode(new Uint8Array([1, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff])));
});
