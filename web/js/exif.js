// JPEG 의 EXIF 촬영 시각을 읽는다. 라이브러리 없이 APP1 → TIFF → IFD0 → ExifIFD 를 직접 읽는다.

const TAG_DATE_TIME = 0x0132;
const TAG_EXIF_IFD = 0x8769;
const TAG_DATE_TIME_ORIGINAL = 0x9003;
const TYPE_ASCII = 2;
const KST_OFFSET_MINUTES = 9 * 60;

/**
 * JPEG ArrayBuffer(또는 Uint8Array)에서 DateTimeOriginal(없으면 DateTime)을 읽는다.
 * JPEG 가 아니거나 EXIF 날짜가 없거나 파일이 잘렸으면 null.
 * @returns {{year:number,month:number,day:number,hour:number,minute:number,second:number}|null}
 */
export function readExifDateTime(buffer) {
  const view = buffer instanceof ArrayBuffer
    ? new DataView(buffer)
    : new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  try {
    const tiff = findTiff(view);
    return tiff ? readTiffDateTime(tiff) : null;
  } catch (e) {
    // DataView 가 범위 밖을 읽으면 RangeError 를 낸다. 잘린 파일로 본다
    if (e instanceof RangeError) return null;
    throw e;
  }
}

/** EXIF 시각에는 시간대가 없다. 한국 시간(UTC+9)으로 해석해 epoch 분으로 바꾼다 */
export function exifToEpochMinute(dt) {
  const utcMs = Date.UTC(dt.year, dt.month - 1, dt.day, dt.hour, dt.minute);
  return utcMs / 60000 - KST_OFFSET_MINUTES;
}

/**
 * 사진 1장의 촬영 시각(epoch 분)과 출처.
 * EXIF 를 읽으면 source 'exif', 못 읽으면(HEIC, PNG, EXIF 없는 JPEG) File.lastModified 를 쓰고 source 'file'
 */
export function resolvePhotoMinute(buffer, lastModifiedMs) {
  const dt = readExifDateTime(buffer);
  if (dt) return { minute: exifToEpochMinute(dt), source: 'exif' };
  return { minute: Math.floor(lastModifiedMs / 60000), source: 'file' };
}

/** APP1 "Exif\0\0" 뒤의 TIFF 블록을 DataView 로 돌려준다 */
function findTiff(view) {
  if (view.byteLength < 4 || view.getUint16(0) !== 0xffd8) return null;
  let p = 2;
  while (p + 4 <= view.byteLength) {
    if (view.getUint8(p) !== 0xff) return null;
    const marker = view.getUint8(p + 1);
    if (marker === 0xff) { p += 1; continue; } // 채움 바이트
    if (marker === 0xda || marker === 0xd9) return null; // 이미지 데이터 시작 또는 끝
    const length = view.getUint16(p + 2);
    if (length < 2) return null;
    const start = p + 4;
    const end = p + 2 + length;
    if (marker === 0xe1 && length >= 8
      && view.getUint32(start) === 0x45786966 && view.getUint16(start + 4) === 0) {
      if (end > view.byteLength) return null;
      return new DataView(view.buffer, view.byteOffset + start + 6, end - start - 6);
    }
    p = end;
  }
  return null;
}

function readTiffDateTime(tiff) {
  const order = tiff.getUint16(0);
  let little;
  if (order === 0x4949) little = true;
  else if (order === 0x4d4d) little = false;
  else return null;
  if (tiff.getUint16(2, little) !== 42) return null;

  const ifd0 = readIfd(tiff, tiff.getUint32(4, little), little);
  const exifPointer = ifd0.get(TAG_EXIF_IFD);
  if (exifPointer) {
    const exifIfd = readIfd(tiff, exifPointer.value, little);
    const original = parseDateTime(asciiValue(tiff, exifIfd.get(TAG_DATE_TIME_ORIGINAL)));
    if (original) return original;
  }
  return parseDateTime(asciiValue(tiff, ifd0.get(TAG_DATE_TIME)));
}

/** IFD 항목을 tag → {type, count, value(4바이트 값 또는 offset), valueOffset(항목 안 값 위치)} 로 읽는다 */
function readIfd(tiff, offset, little) {
  const entries = new Map();
  const count = tiff.getUint16(offset, little);
  for (let i = 0; i < count; i++) {
    const p = offset + 2 + i * 12;
    entries.set(tiff.getUint16(p, little), {
      type: tiff.getUint16(p + 2, little),
      count: tiff.getUint32(p + 4, little),
      value: tiff.getUint32(p + 8, little),
      valueOffset: p + 8,
    });
  }
  return entries;
}

function asciiValue(tiff, entry) {
  if (!entry || entry.type !== TYPE_ASCII || entry.count < 19 || entry.count > 64) return null;
  const start = entry.count <= 4 ? entry.valueOffset : entry.value;
  let s = '';
  for (let i = 0; i < entry.count; i++) {
    const c = tiff.getUint8(start + i);
    if (c === 0) break;
    s += String.fromCharCode(c);
  }
  return s;
}

/** "YYYY:MM:DD HH:MM:SS" → 숫자. 형식이 다르거나 범위 밖이면 null */
function parseDateTime(s) {
  if (!s) return null;
  const m = /^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})/.exec(s);
  if (!m) return null;
  const [year, month, day, hour, minute, second] = m.slice(1).map(Number);
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 60) return null;
  return { year, month, day, hour, minute, second };
}
