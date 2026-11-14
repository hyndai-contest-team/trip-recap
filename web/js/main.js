// 화면 코드. hash 를 풀어 여행을 그리고, 고른 사진을 방문지에 붙이고, 공유 이미지를 만든다.
// 네트워크 요청을 하지 않는다. 사진은 이 브라우저 안에서만 읽는다.
// 여행 데이터(hash)는 누구나 만들 수 있는 입력이므로 DOM 에는 textContent 로만 넣는다.

import { decodeHash } from './codec.js';
import { resolvePhotoMinute } from './exif.js';
import { timelineRows, photoCounts, photoSummary, thumbLabel } from './photos.js';
import { barState } from './bar.js';
import {
  formatDate, formatDuration, formatTime, formatTimeRange, kakaoMapUrl, naverMapUrl, valueParts,
} from './format.js';
import {
  font, tripStats, stopMarks, groupMarkers, routeHeight, routeLabel, layoutRoute, layoutCard, layoutStopPages,
  drawCard, drawRoute, drawStopPage,
} from './cards.js';

/** EXIF 는 파일 앞쪽 APP1 에 있다. 앞 256KB 만 읽는다 */
const EXIF_READ_BYTES = 256 * 1024;

/** 휴대폰 화면의 경로 그림: 선 4, 번호 점 지름 28(숫자 13/700), 잠깐 정차 점 지름 10 */
const SCREEN_ROUTE_STYLE = {
  lineWidth: 4, underWidth: 0, dotRadius: 14, dotFont: 13, briefRadius: 5, briefStroke: 2, briefStrokeColor: '#1D1E21',
};
const SCREEN_ROUTE_PADDING = 24;

const KIND_NAMES = { photo: '사진 배경', story: '스토리', square: '정사각형', stops: '들른 곳별' };
const KIND_HINTS = {
  photo: '고른 사진 위에 경로와 숫자를 얹어요. 아래에서 배경 사진을 바꿀 수 있어요.',
  story: '인스타그램 스토리에 맞는 세로 이미지예요.',
  square: '카카오톡, 인스타그램 게시물에 맞는 정사각형 이미지예요.',
  stops: '들른 곳마다 대표 사진과 이름, 머문 시간을 넣어요. 4곳부터는 여러 장으로 나눠요.',
};

const $ = (id) => document.getElementById(id);

const state = {
  trip: null,
  /** {id, file, url, image(HTMLImageElement|null), minute, source('exif'|'file')} */
  photos: [],
  rows: [],
  /** 정차 index → 대표 사진 */
  representative: new Map(),
  cardKind: 'story',
  /** 사용자가 카드 종류를 직접 골랐는지. 고르지 않았으면 사진이 생길 때 "사진 배경"으로 바꾼다 */
  kindChosen: false,
  /** 사진 배경 카드에 쓸 사진. null 이면 첫 대표 사진 */
  cardPhoto: null,
  cardCount: 0,
  /** 공유할 파일. 버튼을 누른 직후 바로 공유 화면을 열 수 있게 미리 만들어 둔다 */
  filesPromise: Promise.resolve([]),
  cardsVisible: false,
  reading: false,
  renderToken: 0,
  nextPhotoId: 1,
};

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

const nowMinute = () => Math.floor(Date.now() / 60000);

const measureContext = document.createElement('canvas').getContext('2d');
function measure(text, size, weight) {
  measureContext.font = font(weight, size);
  return measureContext.measureText(text).width;
}

const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;

// ── 시작 ───────────────────────────────────────────────

function start() {
  const hash = location.hash.replace(/^#/, '');
  if (!hash) {
    showNotice('empty');
    return;
  }
  let trip;
  try {
    trip = decodeHash(hash);
  } catch (e) {
    showNotice(/버전/.test(e.message) ? 'version' : 'broken');
    return;
  }
  for (const photo of state.photos) URL.revokeObjectURL(photo.url);
  Object.assign(state, {
    trip, photos: [], rows: [], representative: new Map(), cardKind: 'story', kindChosen: false, cardPhoto: null,
  });
  $('notice').hidden = true;
  $('trip').hidden = false;
  $('bar').hidden = false;
  renderHeader();
  renderRoute();
  refreshPhotos();
}

const NOTICES = {
  empty: { title: '차에서 QR 코드를 찍어 주세요', steps: true },
  broken: { title: '여행 정보를 끝까지 읽지 못했어요', body: 'QR 코드를 다시 찍어 주세요. 카메라를 QR 코드에 가까이 대면 돼요.' },
  version: {
    title: '이 여행을 열 수 없어요',
    body: '차량 앱과 이 페이지의 버전이 달라요. 새로 고친 뒤에도 안 열리면 QR 코드를 다시 찍어 주세요.',
    reload: true,
  },
};

function showNotice(kind) {
  const notice = NOTICES[kind];
  $('trip').hidden = true;
  $('bar').hidden = true;
  $('notice').hidden = false;
  $('notice-title').textContent = notice.title;
  $('notice-body').textContent = notice.body || '';
  $('notice-body').hidden = !notice.body;
  $('notice-steps').hidden = !notice.steps;
  $('notice-reload').hidden = !notice.reload;
  document.documentElement.style.setProperty('--bar-h', '0px');
  document.title = '왔다감 - 여행 기록';
}

// ── 머리: 날짜·인원, 제목, 숫자 3칸 ─────────────────────────

function renderHeader() {
  const { trip } = state;
  const title = trip.title || '여행';
  document.title = `${title} - 왔다감`;
  $('trip-title').textContent = title;
  const date = formatDate(trip.startMinute, nowMinute());
  $('trip-meta').textContent = trip.people > 0 ? `${date} · ${trip.people}명` : date;
  $('trip-stats').replaceChildren(...tripStats(trip).map((stat) => {
    const box = el('div', 'stat');
    const value = el('dd');
    for (const part of valueParts(stat.value)) value.append(el('span', 'num', part.num), el('span', 'unit', part.unit));
    box.append(el('dt', '', stat.label), value);
    return box;
  }));
}

// ── 경로 그림 ──────────────────────────────────────────

function renderRoute() {
  const { trip } = state;
  const figure = $('route');
  const canvas = $('route-canvas');
  const note = $('route-note');
  const width = figure.clientWidth || 350;
  const height = routeHeight(trip, width, { padding: SCREEN_ROUTE_PADDING });
  figure.hidden = height === 0;
  if (height === 0) {
    note.hidden = true;
    return;
  }
  const ratio = Math.min(window.devicePixelRatio || 1, 3);
  canvas.style.height = `${height}px`;
  canvas.width = Math.round(width * ratio);
  canvas.height = Math.round(height * ratio);
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, width, height);
  const route = layoutRoute(trip, { x: 0, y: 0, width, height }, SCREEN_ROUTE_PADDING);
  drawRoute(ctx, { ...route, markers: groupMarkers(route.dots, SCREEN_ROUTE_STYLE.dotRadius * 2) }, SCREEN_ROUTE_STYLE, { dotWeight: 700 });
  canvas.setAttribute('aria-label', routeLabel(trip.stops));
  note.hidden = !(route.routeFromStops && route.line.length >= 2);
  note.textContent = '실제 경로 대신 들른 곳을 순서대로 이었어요.';
}

// ── 들른 곳: 요약 줄과 타임라인 ─────────────────────────────

function placeName(stop) {
  return stop.name || '방문지';
}

function renderStops() {
  const { trip, rows, photos } = state;
  const visits = trip.stops.filter((s) => s.kind === 'VISIT').length;
  $('stops-heading').textContent = trip.stops.length > 0 ? `들른 곳 ${visits}곳` : '들른 곳';
  $('more-photos').hidden = photos.length === 0;
  $('timeline-empty').hidden = trip.stops.length > 0;

  const summary = $('photo-summary');
  if (!state.reading) {
    summary.replaceChildren(...photoSummary(photoCounts(rows, photos)).map((line, i) => el('p', i === 0 ? 'main' : 'sub', line)));
  }

  const focusedId = document.activeElement?.dataset?.photoId;
  const marks = stopMarks(trip.stops);
  $('timeline').replaceChildren(...rows.map((row) => {
    if (row.type === 'moving') return movingRow(row.photos);
    const stop = trip.stops[row.index];
    return stop.kind === 'BRIEF' ? briefRow(stop) : stopRow(stop, row.index, marks[row.index].label, row.photos);
  }));
  if (focusedId) document.querySelector(`#timeline [data-photo-id="${focusedId}"]`)?.focus();
}

function rail(dot, lineClass = 'rail-line') {
  const box = el('div', 'rail');
  box.setAttribute('aria-hidden', 'true');
  box.append(dot, el('span', lineClass));
  return box;
}

function stopRow(stop, index, label, photos) {
  const item = el('li', 'row');
  const body = el('div', 'row-body');
  const nameLine = el('div', 'name-line');
  nameLine.append(el('h3', '', placeName(stop)));
  if (stop.kind === 'LODGING') nameLine.append(el('span', 'chip', '숙박'));
  if (stop.rainy) nameLine.append(el('span', 'chip rain', '비'));
  body.append(nameLine);
  body.append(el('p', 'when', `${formatTimeRange(stop.arriveMinute, stop.stayMinutes)} · ${formatDuration(stop.stayMinutes)}`));

  const links = el('p', 'links');
  const sep = el('span', 'sep', '·');
  sep.setAttribute('aria-hidden', 'true');
  links.append(mapLink('카카오맵', kakaoMapUrl(stop), stop), sep, mapLink('네이버지도', naverMapUrl(stop), stop));
  body.append(links);

  if (photos.length > 0) {
    const thumbs = el('div', 'thumbs');
    const chosen = state.representative.get(index);
    for (const photo of photos) thumbs.append(stopThumb(photo, index, photo === chosen));
    body.append(thumbs);
  }
  item.append(rail(el('span', 'dot', label)), body);
  return item;
}

function briefRow(stop) {
  const item = el('li', 'row brief');
  item.append(rail(el('span', 'dot-brief')), el('p', 'brief-text', `${placeName(stop)} ${formatTime(stop.arriveMinute)} · ${formatDuration(stop.stayMinutes)}`));
  return item;
}

function movingRow(photos) {
  const item = el('li', 'row moving');
  const body = el('div', 'row-body');
  body.append(el('p', 'moving-title', `이동 중에 찍은 사진 ${photos.length}장`));
  const thumbs = el('div', 'thumbs small');
  for (const photo of photos) {
    const box = el('span', 'thumb');
    const label = thumbLabel({ place: '이동 중', minute: photo.minute, estimated: photo.source === 'file', readable: !!photo.image, action: false });
    if (photo.image) {
      box.append(photoImage(photo, label));
    } else {
      box.setAttribute('role', 'img');
      box.setAttribute('aria-label', label);
    }
    appendBand(box, photo);
    thumbs.append(box);
  }
  body.append(thumbs);
  item.append(rail(el('span', 'dot-moving'), 'rail-line'), body);
  return item;
}

function mapLink(label, url, stop) {
  const a = el('a', '', label);
  a.href = url;
  a.target = '_blank';
  a.rel = 'noopener noreferrer';
  a.setAttribute('aria-label', `${placeName(stop)} ${label}에서 보기`);
  return a;
}

function photoImage(photo, alt) {
  const img = el('img');
  img.src = photo.url;
  img.alt = alt;
  img.loading = 'lazy';
  img.decoding = 'async';
  return img;
}

function appendBand(box, photo) {
  const text = !photo.image ? '열 수 없음' : photo.source === 'file' ? '시각 추정' : '';
  if (!text) return;
  const band = el('span', 'thumb-band', text);
  band.setAttribute('aria-hidden', 'true');
  box.append(band);
}

/** 방문지 썸네일. 누르면 그 방문지의 대표 사진(들른 곳별 이미지에 들어가는 사진)이 바뀐다 */
function stopThumb(photo, stopIndex, representative) {
  const button = el('button', 'thumb');
  button.type = 'button';
  button.dataset.photoId = String(photo.id);
  const readable = !!photo.image;
  button.setAttribute('aria-pressed', String(representative));
  button.setAttribute('aria-label', thumbLabel({
    place: placeName(state.trip.stops[stopIndex]), minute: photo.minute, estimated: photo.source === 'file', representative, readable,
  }));
  if (readable) {
    button.append(photoImage(photo, ''));
  } else {
    button.setAttribute('aria-disabled', 'true');
  }
  if (representative) {
    const chip = el('span', 'thumb-chip', '대표');
    chip.setAttribute('aria-hidden', 'true');
    button.append(chip);
  }
  appendBand(button, photo);
  button.addEventListener('click', () => {
    if (!readable || representative) return;
    state.representative.set(stopIndex, photo);
    renderStops();
    renderCards();
  });
  return button;
}

// ── 사진 읽기 ─────────────────────────────────────────

async function addFiles(files) {
  const summary = $('photo-summary');
  state.reading = true;
  let done = 0;
  try {
    for (const file of files) {
      summary.replaceChildren(el('p', 'main', `사진을 읽는 중이에요 (${done + 1}/${files.length})`));
      let time;
      try {
        const head = await file.slice(0, EXIF_READ_BYTES).arrayBuffer();
        time = resolvePhotoMinute(head, file.lastModified);
      } catch {
        time = { minute: Math.floor(file.lastModified / 60000), source: 'file' };
      }
      const url = URL.createObjectURL(file);
      const image = await loadImage(url);
      state.photos.push({ id: state.nextPhotoId++, file, url, image, ...time });
      done++;
    }
  } finally {
    state.reading = false;
  }
  refreshPhotos();
}

/** 브라우저가 풀지 못하는 형식(예: Android Chrome 의 HEIC)이면 null */
function loadImage(url) {
  return new Promise((resolve) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => resolve(null);
    image.src = url;
  });
}

function refreshPhotos() {
  const { trip, photos } = state;
  state.rows = timelineRows(trip.stops, photos);
  // 대표 사진이 없거나 목록에서 빠졌으면 그 곳의 첫 사진(열 수 있는 것)으로 정한다
  for (const row of state.rows) {
    if (row.type !== 'stop') continue;
    const current = state.representative.get(row.index);
    if (!current || !row.photos.includes(current)) {
      const first = row.photos.find((p) => p.image);
      if (first) state.representative.set(row.index, first);
      else state.representative.delete(row.index);
    }
  }
  if (!state.kindChosen && usablePhotos().length > 0) state.cardKind = 'photo';
  renderStops();
  renderCards();
}

/** 카드에 쓸 수 있는 사진(열 수 있는 것)을 시각순으로 */
function usablePhotos() {
  return state.rows.flatMap((row) => row.photos).filter((p) => p.image);
}

function backgroundPhoto() {
  const usable = usablePhotos();
  if (state.cardPhoto && usable.includes(state.cardPhoto)) return state.cardPhoto;
  const firstStop = state.rows.find((row) => row.type === 'stop' && state.representative.has(row.index));
  return firstStop ? state.representative.get(firstStop.index) : usable[0] || null;
}

// ── 공유 이미지 ─────────────────────────────────────────

function newCanvas(width, height, label) {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  canvas.setAttribute('role', 'img');
  canvas.setAttribute('aria-label', label);
  return canvas;
}

/** 지금 고른 종류의 카드를 그린 canvas 목록과 저장 형식(type). 만들 수 없으면 빈 목록과 안내(empty) */
function buildCards() {
  const { trip, cardKind } = state;
  const options = { measure, nowMinute: nowMinute() };
  const name = KIND_NAMES[cardKind];
  if (cardKind === 'story' || cardKind === 'square') {
    const layout = layoutCard(trip, cardKind, options);
    const canvas = newCanvas(layout.width, layout.height, `${name} 이미지 미리보기`);
    drawCard(canvas.getContext('2d'), layout);
    return { canvases: [canvas], type: 'image/png' };
  }
  if (cardKind === 'photo') {
    const photo = backgroundPhoto();
    if (!photo) return { canvases: [], empty: '사진을 고르면 여기에 배경으로 들어가요' };
    const layout = layoutCard(trip, 'photo', options);
    const canvas = newCanvas(layout.width, layout.height, `${name} 이미지 미리보기`);
    drawCard(canvas.getContext('2d'), layout, { photo: photo.image });
    return { canvases: [canvas], type: 'image/jpeg' };
  }
  const images = trip.stops.map((_, index) => state.representative.get(index)?.image || null);
  const pages = layoutStopPages(trip, { nowMinute: options.nowMinute, hasPhoto: (index) => !!images[index] });
  if (pages.length === 0) return { canvases: [], empty: '보낸 방문지가 없어서 이 이미지는 만들 수 없어요' };
  const canvases = pages.map((page) => {
    const label = pages.length > 1 ? `${name} 이미지 ${page.index + 1}/${pages.length} 미리보기` : `${name} 이미지 미리보기`;
    const canvas = newCanvas(page.width, page.height, label);
    drawStopPage(canvas.getContext('2d'), page, images);
    return canvas;
  });
  return { canvases, type: images.some(Boolean) ? 'image/jpeg' : 'image/png' };
}

function canvasToBlob(canvas, type) {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('이미지를 만들지 못했다'))), type, 0.92);
  });
}

function fileName(index, total, type) {
  const d = new Date((state.trip.startMinute + 9 * 60) * 60000);
  const date = `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}${String(d.getUTCDate()).padStart(2, '0')}`;
  const page = total > 1 ? `-${index + 1}` : '';
  return `watdagam-${date}-${state.cardKind}${page}.${type === 'image/png' ? 'png' : 'jpg'}`;
}

function renderCards() {
  const token = ++state.renderToken;
  for (const button of $('card-kinds').querySelectorAll('button[data-kind]')) {
    button.setAttribute('aria-pressed', String(button.dataset.kind === state.cardKind));
  }
  $('card-hint').textContent = KIND_HINTS[state.cardKind];
  clearShareResult();

  let built;
  try {
    built = buildCards();
  } catch {
    built = { canvases: [], empty: '이미지를 그리지 못했어요. 다른 종류를 골라 주세요.' };
  }
  $('card-preview').replaceChildren(...built.canvases);
  $('card-empty').hidden = built.canvases.length > 0;
  $('card-empty').textContent = built.empty || '';
  renderBackgroundChoices();
  state.cardCount = built.canvases.length;
  state.filesPromise = built.canvases.length === 0
    ? Promise.resolve([])
    : Promise.all(built.canvases.map((c) => canvasToBlob(c, built.type)))
      .then((blobs) => blobs.map((blob, i) => new File([blob], fileName(i, blobs.length, built.type), { type: built.type })))
      .catch(() => {
        if (token === state.renderToken) $('card-status').textContent = '이미지를 만들지 못했어요. 다른 사진으로 다시 해 보세요.';
        return [];
      });
  renderBar();
}

/** 사진 배경을 골랐을 때 미리보기 아래의 배경 사진 줄 */
function renderBackgroundChoices() {
  const row = $('bg-photos');
  const usable = usablePhotos();
  row.hidden = state.cardKind !== 'photo' || usable.length === 0;
  if (row.hidden) {
    row.replaceChildren();
    return;
  }
  const chosen = backgroundPhoto();
  const placeOf = new Map();
  for (const r of state.rows) for (const p of r.photos) placeOf.set(p, r.type === 'stop' ? placeName(state.trip.stops[r.index]) : '이동 중');
  row.replaceChildren(...usable.map((photo) => {
    const button = el('button', 'bg-photo');
    button.type = 'button';
    button.setAttribute('aria-pressed', String(photo === chosen));
    button.setAttribute('aria-label', `${thumbLabel({ place: placeOf.get(photo), minute: photo.minute, estimated: photo.source === 'file', action: false })}. 배경으로 쓰기`);
    button.append(photoImage(photo, ''));
    button.addEventListener('click', () => {
      state.cardPhoto = photo;
      renderCards();
      row.querySelectorAll('button')[usable.indexOf(photo)]?.focus();
    });
    return button;
  }));
}

function clearShareResult() {
  $('card-status').textContent = '';
  for (const a of $('card-downloads').querySelectorAll('a')) URL.revokeObjectURL(a.href);
  $('card-downloads').replaceChildren();
}

async function shareCards() {
  const files = await state.filesPromise;
  if (files.length === 0) return;
  clearShareResult();
  const status = $('card-status');
  if (navigator.canShare && navigator.canShare({ files })) {
    try {
      await navigator.share({ files });
      status.textContent = '보냈어요. 다른 이미지도 만들 수 있어요.';
      return;
    } catch (e) {
      if (e.name === 'AbortError') return; // 사용자가 공유 화면을 닫았다
      // 그 밖의 실패는 아래 저장 링크로 대신한다
    }
  }
  status.textContent = '이 브라우저는 공유 화면을 열 수 없어요. 아래에서 한 장씩 저장해 주세요.';
  $('card-downloads').replaceChildren(...files.map((file, index) => {
    const a = el('a', '', `${index + 1}번째 이미지 저장`);
    a.href = URL.createObjectURL(file);
    a.download = file.name;
    const item = el('li');
    item.append(a);
    return item;
  }));
}

// ── 아래 고정 바 ───────────────────────────────────────

function renderBar() {
  if (!state.trip) return;
  const counts = photoCounts(state.rows, state.photos);
  const bar = barState({
    photos: counts.total, placed: counts.placed, places: counts.places, cardsVisible: state.cardsVisible, cardCount: state.cardCount,
  });
  const button = $('bar-button');
  button.textContent = bar.label;
  button.dataset.action = bar.action;
  button.disabled = state.reading;
  $('bar-hint').textContent = bar.hint;
}

function scrollToCards() {
  $('cards').scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' });
  $('cards-heading').focus({ preventScroll: true });
}

function setKind(kind, chosen) {
  state.cardKind = kind;
  if (chosen) state.kindChosen = true;
  renderCards();
}

$('bar-button').addEventListener('click', () => {
  const action = $('bar-button').dataset.action;
  if (action === 'pick') $('photo-input').click();
  else if (action === 'make') {
    setKind('photo', true);
    scrollToCards();
  } else if (action === 'share') shareCards();
});

$('more-photos').addEventListener('click', () => $('photo-input').click());

$('photo-input').addEventListener('change', async (event) => {
  const input = event.target;
  const files = [...input.files];
  input.value = ''; // 같은 사진을 다시 골라도 change 가 나게 한다
  if (files.length > 0 && state.trip) {
    renderBar();
    await addFiles(files);
  }
});

$('card-kinds').addEventListener('click', (event) => {
  const button = event.target.closest('button[data-kind]');
  if (button) setKind(button.dataset.kind, true);
});

$('notice-reload').addEventListener('click', () => location.reload());

// "공유할 이미지" 구역이 화면 아래 1/4 위로 올라오면 바의 버튼이 "이미지 공유하기"가 된다
if ('IntersectionObserver' in window) {
  new IntersectionObserver((entries) => {
    for (const entry of entries) state.cardsVisible = entry.isIntersecting;
    renderBar();
  }, { rootMargin: '0px 0px -25% 0px' }).observe($('cards'));
}

// main 아래 여백을 바 높이만큼 둔다. 글자 확대로 안내가 두 줄이 되면 바가 높아진다
if ('ResizeObserver' in window) {
  new ResizeObserver(() => {
    if (!$('bar').hidden) document.documentElement.style.setProperty('--bar-h', `${$('bar').offsetHeight}px`);
  }).observe($('bar'));
}

window.addEventListener('hashchange', start);

let resizeTimer = 0;
let lastWidth = window.innerWidth;
window.addEventListener('resize', () => {
  if (window.innerWidth === lastWidth) return; // 모바일 주소창이 접힐 때 나는 높이 변화는 무시한다
  lastWidth = window.innerWidth;
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => { if (state.trip) renderRoute(); }, 150);
});

start();
