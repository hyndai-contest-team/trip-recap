import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mercator, fit } from '../js/projection.js';

const fixture = JSON.parse(readFileSync(new URL('./fixtures/projection.json', import.meta.url), 'utf8'));

function near(actual, expected, tolerance) {
  assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} 는 ${expected} ± ${tolerance} 밖이다`);
}

test('Kotlin 과 공유하는 fixture 와 0.005 이내로 같다', () => {
  const out = fit(fixture.points, fixture.width, fixture.height, fixture.padding);
  assert.equal(out.length, 5);
  out.forEach(([x, y], i) => {
    near(x, fixture.expected[i][0], 0.005);
    near(y, fixture.expected[i][1], 0.005);
  });
});

test('mercator: 적도·본초자오선은 가운데, 위도 85.0511° 는 위쪽 끝', () => {
  const [x, y] = mercator([0, 0]);
  near(x, 0.5, 1e-12);
  near(y, 0.5, 1e-12);
  near(mercator([85.0511287798, 180])[1], 0, 1e-9);
  near(mercator([0, 180])[0], 1, 1e-12);
  near(mercator([0, -180])[0], 0, 1e-12);
});

test('동서 방향 선(세로 길이 0)은 padding 안쪽 가로를 채우고 세로 가운데에 놓인다', () => {
  const [a, b] = fit([[37, 127], [37, 128]], 400, 200, 20);
  near(a[0], 20, 1e-6); near(a[1], 100, 1e-6);
  near(b[0], 380, 1e-6); near(b[1], 100, 1e-6);
});

test('남북 방향 선(가로 길이 0)은 padding 안쪽 세로를 채우고 북쪽이 위다', () => {
  const [south, north] = fit([[37, 127], [38, 127]], 400, 200, 20);
  near(south[0], 200, 1e-6); near(south[1], 180, 1e-6);
  near(north[0], 200, 1e-6); near(north[1], 20, 1e-6);
});

test('가로세로 비율을 지킨다: 위도 60° 에서 북쪽 1° 는 동쪽 1° 의 2배 길이', () => {
  const [o, n, e] = fit([[60, 10], [60.1, 10], [60, 10.1]], 400, 400, 0);
  near((o[1] - n[1]) / (e[0] - o[0]), 2, 0.01);
  near(o[1] - n[1], 400, 1e-6);
});

test('점이 1개면 상자 가운데', () => {
  assert.deepEqual(fit([[37, 127]], 400, 200, 20), [[200, 100]]);
});

test('모든 점이 같으면 모두 상자 가운데', () => {
  assert.deepEqual(fit([[37, 127], [37, 127], [37, 127]], 400, 200, 20), [[200, 100], [200, 100], [200, 100]]);
});

test('점이 없으면 빈 배열', () => {
  assert.deepEqual(fit([], 400, 200, 20), []);
});

test('bounds 를 따로 주면 그 범위에 맞춘 변환으로 points 를 옮긴다', () => {
  const [p] = fit([[37, 127.5]], 400, 200, 20, [[37, 127], [37, 128]]);
  near(p[0], 200, 1e-6);
  near(p[1], 100, 1e-6);
});

test('bounds 가 빈 배열이면 points 를 기준으로 삼는다', () => {
  const [a, b] = fit([[37, 127], [37, 128]], 400, 200, 20, []);
  near(a[0], 20, 1e-6);
  near(b[0], 380, 1e-6);
});

test('bounds 가 있고 points 가 비면 빈 배열', () => {
  assert.deepEqual(fit([], 400, 200, 20, [[37, 127], [37, 128]]), []);
});
