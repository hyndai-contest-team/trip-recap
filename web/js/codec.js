// 공유 바이트 형식 v1. app/src/main/java/kr/triprecap/app/share/ShareCodec.kt 와 같은 형식이다.
//   u8 version=1 · varint startMinute · varint durationMinutes · varint distance(100m) · u8 people · str title
//   varint stopCount · stop*{ u8 flags(bit0-1 kind, bit2 rainy) · svarint dLat · svarint dLon · varint arriveOffsetMin · varint stayMin · str name }
//   varint routeCount · point*{ svarint dLat · svarint dLon }
//   좌표는 1e-4 도 정수, 직전 값과의 차이(첫 값은 0 기준). str = varint 바이트 길이 + UTF-8
// hash 는 base64url(패딩 없음)

export const VERSION = 1;
const KINDS = ['VISIT', 'BRIEF', 'LODGING'];
const E4 = 10000;

/** URL 의 # 뒤 문자열 → 여행. 잘못된 hash, 지원하지 않는 버전이면 Error */
export function decodeHash(hash) {
  return decode(fromBase64Url(hash));
}

export function encodeHash(trip) {
  return toBase64Url(encode(trip));
}

export function decode(bytes) {
  const r = new Reader(bytes);
  if (r.u8() !== VERSION) throw new Error('지원하지 않는 형식 버전');
  const startMinute = r.varint();
  const durationMinutes = r.varint();
  const distanceM = r.varint() * 100;
  const people = r.u8();
  const title = r.str();
  let lat = 0;
  let lon = 0;
  const stops = [];
  for (let n = r.varint(); n > 0; n--) {
    const flags = r.u8();
    const kind = KINDS[flags & 3];
    if (!kind) throw new Error('알 수 없는 방문지 종류');
    lat += r.svarint();
    lon += r.svarint();
    const arriveMinute = startMinute + r.varint();
    const stayMinutes = r.varint();
    stops.push({ kind, rainy: (flags & 4) !== 0, lat: lat / E4, lon: lon / E4, arriveMinute, stayMinutes, name: r.str() });
  }
  lat = 0;
  lon = 0;
  const route = [];
  for (let n = r.varint(); n > 0; n--) {
    lat += r.svarint();
    lon += r.svarint();
    route.push([lat / E4, lon / E4]);
  }
  return { title, startMinute, durationMinutes, distanceM, people, stops, route };
}

/** 시험과 개발용. 차량 앱의 ShareCodec.encode 와 같은 바이트를 만든다 */
export function encode(trip) {
  const out = [];
  const e4 = (v) => Math.round(v * E4);
  const varint = (value) => {
    if (!Number.isSafeInteger(value) || value < 0) throw new Error('varint 범위 밖의 값');
    let v = value;
    while (v >= 0x80) {
      out.push((v % 0x80) | 0x80);
      v = Math.floor(v / 0x80);
    }
    out.push(v);
  };
  const svarint = (value) => varint(value >= 0 ? value * 2 : -value * 2 - 1);
  const str = (s) => {
    const b = new TextEncoder().encode(s);
    varint(b.length);
    out.push(...b);
  };

  out.push(VERSION);
  varint(trip.startMinute);
  varint(trip.durationMinutes);
  varint(Math.floor(trip.distanceM / 100));
  out.push(trip.people & 0xff);
  str(trip.title);
  varint(trip.stops.length);
  let lat = 0;
  let lon = 0;
  for (const s of trip.stops) {
    const kind = KINDS.indexOf(s.kind);
    if (kind < 0) throw new Error('알 수 없는 방문지 종류');
    out.push(kind | (s.rainy ? 4 : 0));
    svarint(e4(s.lat) - lat);
    svarint(e4(s.lon) - lon);
    lat = e4(s.lat);
    lon = e4(s.lon);
    varint(s.arriveMinute - trip.startMinute);
    varint(s.stayMinutes);
    str(s.name);
  }
  varint(trip.route.length);
  lat = 0;
  lon = 0;
  for (const [pLat, pLon] of trip.route) {
    svarint(e4(pLat) - lat);
    svarint(e4(pLon) - lon);
    lat = e4(pLat);
    lon = e4(pLon);
  }
  return new Uint8Array(out);
}

function fromBase64Url(hash) {
  if (typeof hash !== 'string') throw new Error('공유 데이터가 없다');
  const text = hash.startsWith('#') ? hash.slice(1) : hash;
  if (text.length === 0) throw new Error('공유 데이터가 없다');
  if (!/^[A-Za-z0-9_-]+$/.test(text) || text.length % 4 === 1) throw new Error('공유 데이터 형식이 잘못됐다');
  const binary = atob(text.replace(/-/g, '+').replace(/_/g, '/'));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function toBase64Url(bytes) {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

class Reader {
  constructor(bytes) {
    this.b = bytes;
    this.i = 0;
  }

  u8() {
    if (this.i >= this.b.length) throw new Error('공유 데이터가 중간에 끊겼다');
    return this.b[this.i++];
  }

  varint() {
    let v = 0;
    let scale = 1;
    for (let n = 0; n < 8; n++) {
      const x = this.u8();
      v += (x & 0x7f) * scale;
      if ((x & 0x80) === 0) return v;
      scale *= 0x80;
    }
    throw new Error('공유 데이터의 숫자가 너무 크다');
  }

  /** zigzag: 0,1,2,3 → 0,-1,1,-2 */
  svarint() {
    const v = this.varint();
    return v % 2 === 0 ? v / 2 : -(v + 1) / 2;
  }

  str() {
    const n = this.varint();
    if (this.i + n > this.b.length) throw new Error('공유 데이터가 중간에 끊겼다');
    const s = new TextDecoder('utf-8').decode(this.b.subarray(this.i, this.i + n));
    this.i += n;
    return s;
  }
}
