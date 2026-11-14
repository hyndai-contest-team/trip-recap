import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readExifDateTime, exifToEpochMinute, resolvePhotoMinute } from '../js/exif.js';

/** TIFF 블록을 만든다. IFD0 = [DateTime?, ExifIFD pointer?], ExifIFD = [DateTimeOriginal?] */
function tiff({ little, dateTime, original }) {
  const bytes = [];
  const u16 = (v) => (little ? [v & 0xff, v >> 8] : [v >> 8, v & 0xff]);
  const u32 = (v) => (little ? [v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, v >>> 24] : [v >>> 24, (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff]);
  const ascii = (s) => [...s].map((c) => c.charCodeAt(0)).concat(0);

  const ifd0Count = (dateTime ? 1 : 0) + (original ? 1 : 0);
  const ifd0Size = 2 + ifd0Count * 12 + 4;
  let dataOffset = 8 + ifd0Size;
  const dateTimeOffset = dataOffset;
  if (dateTime) dataOffset += 20;
  const exifIfdOffset = dataOffset;
  const exifIfdSize = 2 + 12 + 4;
  const originalOffset = exifIfdOffset + exifIfdSize;

  bytes.push(...(little ? [0x49, 0x49] : [0x4d, 0x4d]), ...u16(42), ...u32(8));
  bytes.push(...u16(ifd0Count));
  if (dateTime) bytes.push(...u16(0x0132), ...u16(2), ...u32(20), ...u32(dateTimeOffset));
  if (original) bytes.push(...u16(0x8769), ...u16(4), ...u32(1), ...u32(exifIfdOffset));
  bytes.push(...u32(0));
  if (dateTime) bytes.push(...ascii(dateTime));
  if (original) {
    bytes.push(...u16(1), ...u16(0x9003), ...u16(2), ...u32(20), ...u32(originalOffset), ...u32(0));
    bytes.push(...ascii(original));
  }
  return bytes;
}

function segment(marker, payload) {
  const length = payload.length + 2;
  return [0xff, marker, length >> 8, length & 0xff, ...payload];
}

const EXIF_HEADER = [0x45, 0x78, 0x69, 0x66, 0, 0];
const JFIF = segment(0xe0, [0x4a, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0]);
const TAIL = [0xff, 0xdb, 0, 4, 0, 0, 0xff, 0xd9];

function jpeg(tiffBytes, { withJfif = false } = {}) {
  const parts = [0xff, 0xd8];
  if (withJfif) parts.push(...JFIF);
  if (tiffBytes) parts.push(...segment(0xe1, [...EXIF_HEADER, ...tiffBytes]));
  parts.push(...TAIL);
  return new Uint8Array(parts).buffer;
}

test('II(little endian) EXIF 의 DateTimeOriginal 을 읽는다', () => {
  const buffer = jpeg(tiff({ little: true, original: '2026:11:07 11:20:30' }));
  assert.deepEqual(readExifDateTime(buffer), { year: 2026, month: 11, day: 7, hour: 11, minute: 20, second: 30 });
});

test('MM(big endian) EXIF 의 DateTimeOriginal 을 읽는다', () => {
  const buffer = jpeg(tiff({ little: false, original: '2026:11:08 09:05:59' }));
  assert.deepEqual(readExifDateTime(buffer), { year: 2026, month: 11, day: 8, hour: 9, minute: 5, second: 59 });
});

test('DateTimeOriginal 이 없으면 DateTime(0x0132)을 읽는다 (II, MM)', () => {
  for (const little of [true, false]) {
    const buffer = jpeg(tiff({ little, dateTime: '2025:01:02 03:04:05' }));
    assert.deepEqual(readExifDateTime(buffer), { year: 2025, month: 1, day: 2, hour: 3, minute: 4, second: 5 });
  }
});

test('둘 다 있으면 DateTimeOriginal 을 쓴다 (II, MM)', () => {
  for (const little of [true, false]) {
    const buffer = jpeg(tiff({ little, dateTime: '2026:12:31 23:59:59', original: '2026:11:07 12:00:00' }));
    assert.deepEqual(readExifDateTime(buffer), { year: 2026, month: 11, day: 7, hour: 12, minute: 0, second: 0 });
  }
});

test('APP0(JFIF) 뒤에 오는 APP1 도 찾는다', () => {
  const buffer = jpeg(tiff({ little: true, original: '2026:11:07 11:20:30' }), { withJfif: true });
  assert.equal(readExifDateTime(buffer).minute, 20);
});

test('Uint8Array 로 넘겨도 읽는다 (byteOffset 이 0 이 아닌 경우 포함)', () => {
  const source = new Uint8Array(jpeg(tiff({ little: false, original: '2026:11:07 11:20:30' })));
  const padded = new Uint8Array(source.length + 3);
  padded.set(source, 3);
  assert.equal(readExifDateTime(padded.subarray(3)).hour, 11);
});

test('EXIF 가 없는 JPEG 는 null', () => {
  assert.equal(readExifDateTime(jpeg(null)), null);
  assert.equal(readExifDateTime(jpeg(null, { withJfif: true })), null);
});

test('JPEG 가 아니면 null', () => {
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13]).buffer;
  assert.equal(readExifDateTime(png), null);
  assert.equal(readExifDateTime(new ArrayBuffer(0)), null);
  assert.equal(readExifDateTime(new Uint8Array([0xff]).buffer), null);
});

test('날짜 tag 가 없는 EXIF 는 null', () => {
  assert.equal(readExifDateTime(jpeg(tiff({ little: true }))), null);
});

test('값이 비었거나 형식이 다른 날짜는 null', () => {
  assert.equal(readExifDateTime(jpeg(tiff({ little: true, original: '0000:00:00 00:00:00' }))), null);
  assert.equal(readExifDateTime(jpeg(tiff({ little: true, original: '    :  :     :  :  ' }))), null);
  assert.equal(readExifDateTime(jpeg(tiff({ little: false, original: '2026:13:07 11:20:30' }))), null);
});

test('중간에 잘린 파일은 예외 없이 null', () => {
  const full = new Uint8Array(jpeg(tiff({ little: true, original: '2026:11:07 11:20:30' })));
  for (let length = 0; length < full.length - TAIL.length; length++) {
    assert.equal(readExifDateTime(full.slice(0, length).buffer), null, `길이 ${length}`);
  }
});

test('IFD offset 이 파일 밖을 가리키면 null', () => {
  const bytes = new Uint8Array(jpeg(tiff({ little: true, original: '2026:11:07 11:20:30' })));
  // TIFF 시작 = SOI(2) + marker(2) + length(2) + "Exif\0\0"(6) = 12. IFD0 offset 은 TIFF+4
  bytes.set([0xff, 0xff, 0xff, 0x7f], 12 + 4);
  assert.equal(readExifDateTime(bytes.buffer), null);
});

test('EXIF 시각을 한국 시간(UTC+9)으로 해석해 epoch 분으로 바꾼다', () => {
  assert.equal(exifToEpochMinute({ year: 2026, month: 11, day: 7, hour: 11, minute: 20, second: 30 }), 29900300);
  assert.equal(exifToEpochMinute({ year: 2026, month: 11, day: 8, hour: 9, minute: 5, second: 0 }), 29901605);
  // 한국 시간 1970-01-01 09:00 = epoch 0
  assert.equal(exifToEpochMinute({ year: 1970, month: 1, day: 1, hour: 9, minute: 0, second: 0 }), 0);
  // 한국 시간 00:30 은 UTC 로 전날 15:30
  assert.equal(exifToEpochMinute({ year: 2026, month: 11, day: 8, hour: 0, minute: 30, second: 0 }), 29901090);
});

test('촬영 시각: EXIF 가 있으면 EXIF, 없으면 파일 수정 시각을 쓰고 출처를 표시한다', () => {
  const withExif = jpeg(tiff({ little: true, original: '2026:11:07 11:20:30' }));
  assert.deepEqual(resolvePhotoMinute(withExif, 0), { minute: 29900300, source: 'exif' });
  // 1794018059999 ms = 2026-11-07 11:20:59.999 KST
  assert.deepEqual(resolvePhotoMinute(jpeg(null), 1794018059999), { minute: 29900300, source: 'file' });
  assert.deepEqual(resolvePhotoMinute(new ArrayBuffer(0), 1794018000000), { minute: 29900300, source: 'file' });
});
