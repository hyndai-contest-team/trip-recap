// 위경도 → 화면 좌표. 차량 앱의 RouteProjection.kt 와 같은 계산이다.
// 두 구현은 web/test/fixtures/projection.json 으로 비교한다.
// 점은 [lat, lon], 결과는 [x, y] 배열이다.

/** 단위 정사각형 [0,1] 안의 Web Mercator 좌표. y 는 북쪽이 0 */
export function mercator([lat, lon]) {
  const x = (lon + 180) / 360;
  const rad = (lat * Math.PI) / 180;
  const y = (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2;
  return [x, y];
}

/**
 * bounds 의 점들이 width×height 상자의 padding 안쪽에 들어가도록 맞춘 변환으로 points 를 옮긴다.
 * bounds 가 없거나 비면 points 를 기준으로 삼는다. 점이 하나뿐이거나 모두 같으면 상자 가운데에 둔다.
 * 한 축의 길이가 0 이면 다른 축에 맞춰 배율을 정한다.
 */
export function fit(points, width, height, padding, bounds) {
  const ref = (bounds && bounds.length > 0 ? bounds : points).map(mercator);
  if (ref.length === 0) return [];
  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  for (const [x, y] of ref) {
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
  }
  const dx = maxX - minX;
  const dy = maxY - minY;
  const innerW = width - 2 * padding;
  const innerH = height - 2 * padding;
  let scale;
  if (dx <= 0 && dy <= 0) scale = 0;
  else if (dx <= 0) scale = innerH / dy;
  else if (dy <= 0) scale = innerW / dx;
  else scale = Math.min(innerW / dx, innerH / dy);
  const offX = (width - dx * scale) / 2;
  const offY = (height - dy * scale) / 2;
  return points.map(mercator).map(([x, y]) => [offX + (x - minX) * scale, offY + (y - minY) * scale]);
}
