# 공유 데이터 형식 v1

차 화면의 QR 은 `{share.baseUrl}#{hash}` 이다. `hash` 는 아래 바이트를 base64url(패딩 없음)로 쓴 것이다.
`#` 뒤는 브라우저가 서버로 보내지 않는다. 인코더는 `app/.../share/ShareCodec.kt`, 디코더는 `web/js/codec.js` 다.
두 구현은 `web/test/fixtures/share-v1.json` 한 파일로 비교한다(Kotlin 은 이 hash 를 만들어야 하고, JS 는 이 hash 를 expected 로 풀어야 한다).

| 순서 | 필드 | 형식 |
|---|---|---|
| 1 | version | u8 = 1 |
| 2 | startMinute | varint, 여행 시작 epoch 분 |
| 3 | durationMinutes | varint |
| 4 | distance | varint, 100m 단위 |
| 5 | people | u8 |
| 6 | title | str |
| 7 | stopCount | varint |
| 8 | stop × n | u8 flags(bit0-1 종류: 0 방문지·1 잠깐 정차·2 숙박, bit2 비) · svarint dLat · svarint dLon · varint arriveOffsetMin(여행 시작 기준) · varint stayMin · str name |
| 9 | routeCount | varint |
| 10 | point × n | svarint dLat · svarint dLon |

- 좌표는 1e-4 도 정수다. 직전 값과의 차이를 쓴다(첫 값은 0 기준). 정차 목록과 경로는 각각 따로 누적한다.
- varint: 7비트씩 낮은 자리부터, 이어지면 최상위 비트 1. svarint: zigzag(0,-1,1,-2 → 0,1,2,3).
- str: varint 바이트 길이 + UTF-8.

상한은 1024B 다. 넘으면 경로를 Douglas-Peucker 로 단순화(허용 오차를 2배씩 키움) → 경로 제거 → 이름 줄이기 순서로 줄인다.
보내지 않는 것: 잠깐 정차, 공유 제외로 표시한 정차, HOME·OFFICE 500m 안의 정차와 경로 점, 메모, 사진.
