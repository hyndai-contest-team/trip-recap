// 경로 그림과 공유 이미지. 좌표와 문자열 계산(layout*, group*, route*)은 순수 함수이고,
// 그리기(draw*)는 CanvasRenderingContext2D 를 받는다.
// 공유 이미지는 4종이다: 사진 배경·스토리(1080×1920), 정사각형(1080×1080), 들른 곳별 이미지(1080×1350, 여러 장).
// 앞의 3종 좌표는 CARD_SPECS, 들른 곳별 이미지 좌표는 layoutStopPages 에 적는다.

import { fit, mercator } from './projection.js';
import { formatDate, formatDistance, formatDuration, formatTimeRange, formatTripDuration, valueParts } from './format.js';

export const CARD_SIZES = {
  photo: { width: 1080, height: 1920 },
  story: { width: 1080, height: 1920 },
  square: { width: 1080, height: 1080 },
};
export const STOP_PAGE_SIZE = { width: 1080, height: 1350 };
/** 공유 이미지에 넣는 앱 이름과 영어 부제 */
export const BRAND = '왔다감 · Wheel Be Back';

export const COLORS = {
  background: '#131417',
  surface: '#1D1E21',
  accent: '#F5A623',
  onAccent: '#131417',
  idle: '#A4A8AE',
  rain: '#6FB8FF',
  text: '#FFFFFF',
  text72: 'rgba(255,255,255,0.72)',
  divider: 'rgba(255,255,255,0.2)',
  under: 'rgba(19,20,23,0.55)',
};
const FONT_FAMILY = '-apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Noto Sans KR", "Malgun Gothic", sans-serif';
export const font = (weight, size) => `${weight} ${size}px ${FONT_FAMILY}`;

/**
 * 한글 글꼴의 글자 상자 위 끝에서 기준선까지 거리(글자 크기 대비).
 * 배치 좌표(STORY_TEXT 등)는 line-height 1 인 글자 상자의 위 끝으로 적고, 숫자와 단위처럼 크기가 다른 글자를
 * 기준선에 맞춰 그리려면 기준선 좌표가 필요하다.
 */
const ASCENT = 0.88;

/** 숫자 3칸: 달린 거리, 들른 곳(종류가 VISIT 인 것. 차량 앱 Trip.visits 와 같은 기준), 걸린 시간 */
export function tripStats(trip) {
  const visits = trip.stops.filter((s) => s.kind === 'VISIT').length;
  return [
    { value: formatDistance(trip.distanceM), label: '달린 거리' },
    { value: `${visits}곳`, label: '들른 곳' },
    { value: formatTripDuration(trip), label: '걸린 시간' },
  ];
}

/** 정차마다 점 모양. 방문지만 번호를 세고, 숙박은 "숙", 잠깐 정차는 번호 없는 회색 점 */
export function stopMarks(stops) {
  let n = 0;
  return stops.map((s) => {
    if (s.kind === 'VISIT') return { kind: 'visit', label: String(++n) };
    if (s.kind === 'LODGING') return { kind: 'lodging', label: '숙' };
    return { kind: 'brief', label: '' };
  });
}

/**
 * 겹치는 번호 점을 pill 하나로 묶는다. dots 는 정차 순서대로 {x, y, kind, label}.
 * 번호가 붙는 점(방문지, 숙박)을 순서대로 보면서, 바로 앞 묶음의 어느 점과든 중심 거리가 지름×1.1 보다 작으면
 * 그 묶음에 넣는다. 그래서 연속한 번호만 묶인다. 묶은 뒤 이웃한 두 묶음의 pill(추정 폭)이 겹치면 다시 합친다.
 * 잠깐 정차 점은 묶지 않고 번호 점 아래에 그리도록 앞에 둔다.
 * 묶음 글자는 방문지 번호 범위("2–3"), 숙박이 섞이면 뒤에 "·숙", 숙박만이면 "숙". 위치는 묶인 점들의 평균.
 */
export function groupMarkers(dots, diameter) {
  const limit = diameter * 1.1;
  const briefs = [];
  let groups = [];
  let current = null;
  dots.forEach((dot, index) => {
    if (dot.kind === 'brief') {
      briefs.push({ kind: 'brief', x: dot.x, y: dot.y, label: '', stops: [index] });
      return;
    }
    const member = { ...dot, index };
    if (current && current.some((m) => Math.hypot(m.x - dot.x, m.y - dot.y) < limit)) {
      current.push(member);
    } else {
      current = [member];
      groups.push(current);
    }
  });
  let markers = groups.map(toMarker);
  for (let i = 0; i < markers.length - 1;) {
    if (pillsOverlap(markers[i], markers[i + 1], diameter)) {
      groups = [...groups.slice(0, i), [...groups[i], ...groups[i + 1]], ...groups.slice(i + 2)];
      markers = groups.map(toMarker);
      i = Math.max(0, i - 1);
    } else {
      i++;
    }
  }
  return [...briefs, ...markers];
}

function toMarker(members) {
  const visits = members.filter((m) => m.kind === 'visit').map((m) => m.label);
  const lodging = members.some((m) => m.kind === 'lodging');
  let label = '숙';
  if (visits.length > 0) {
    label = visits.length > 1 ? `${visits[0]}–${visits[visits.length - 1]}` : visits[0];
    if (lodging) label += '·숙';
  }
  return {
    kind: 'number',
    x: members.reduce((s, m) => s + m.x, 0) / members.length,
    y: members.reduce((s, m) => s + m.y, 0) / members.length,
    label,
    stops: members.map((m) => m.index),
  };
}

/**
 * pill 폭 추정. 글자 하나면 원(지름), 여러 글자면 좌우 여백 0.6×지름 + 글자당 0.5×지름.
 * 번호 글자 크기가 지름의 0.46~0.59 배(화면 13/28, 공유 이미지 40/68)라서 글자 폭을 넉넉하게 잡았다.
 */
function pillWidth(label, diameter) {
  const chars = [...label].length;
  return chars <= 1 ? diameter : diameter * 0.6 + chars * diameter * 0.5;
}

function pillsOverlap(a, b, diameter) {
  return Math.abs(a.x - b.x) < (pillWidth(a.label, diameter) + pillWidth(b.label, diameter)) / 2
    && Math.abs(a.y - b.y) < diameter * 1.1;
}

/**
 * 화면 경로 그림의 높이(CSS px). 경로와 정차 범위의 Mercator 세로/가로 비율에 맞추고 160~300 으로 자른다.
 * 그릴 점이 없으면 0.
 */
export function routeHeight(trip, width, { padding = 24, min = 160, max = 300 } = {}) {
  const points = [...trip.route, ...trip.stops.map((s) => [s.lat, s.lon])].map(mercator);
  if (points.length === 0) return 0;
  const xs = points.map((p) => p[0]);
  const ys = points.map((p) => p[1]);
  const dx = Math.max(...xs) - Math.min(...xs);
  const dy = Math.max(...ys) - Math.min(...ys);
  if (dx <= 0 && dy <= 0) return min;
  if (dx <= 0) return max;
  const height = (width - 2 * padding) * (dy / dx) + 2 * padding;
  return Math.round(Math.min(max, Math.max(min, height)));
}

/** 경로 그림의 대체 글. 번호가 붙는 곳(방문지, 숙박)을 순서대로 적는다 */
export function routeLabel(stops) {
  const names = stops.filter((s) => s.kind !== 'BRIEF').map((s) => s.name || '방문지');
  if (names.length === 0) return '달린 경로';
  if (names.length === 1) return `${names[0]}에 들른 경로`;
  return `${names.join(', ')} 순서로 들른 경로`;
}

/**
 * 경로 선과 정차 점의 좌표. box = {x, y, width, height}.
 * 범위는 경로 점과 정차를 합쳐서 잡는다.
 * 경로 점이 2개 미만이면 정차를 순서대로 이은 선을 쓰고 routeFromStops 를 true 로 둔다.
 */
export function layoutRoute(trip, box, padding) {
  const stopPoints = trip.stops.map((s) => [s.lat, s.lon]);
  const routeFromStops = trip.route.length < 2;
  const linePoints = routeFromStops ? (stopPoints.length >= 2 ? stopPoints : []) : trip.route;
  const bounds = [...trip.route, ...stopPoints];
  const place = (points) => fit(points, box.width, box.height, padding, bounds).map(([x, y]) => [box.x + x, box.y + y]);
  const marks = stopMarks(trip.stops);
  return {
    line: place(linePoints),
    routeFromStops,
    dots: place(stopPoints).map(([x, y], i) => ({ x, y, ...marks[i] })),
  };
}

// ── 공유 이미지 배치 ─────────────────────────────────────

const STORY_TEXT = {
  left: 80,
  right: 1000,
  dateY: 1213,
  title: { y: 1269, sizes: [76, 60], lineHeight: 66 },
  dividerY: 1399,
  stats: { top: 1425, left: 80, right: 1000, labelGap: 17, sizes: [[80, 44, 16], [72, 40, 14], [64, 36, 12]] },
  footerTop: 1603,
};

const CARD_SPECS = {
  story: {
    ...CARD_SIZES.story,
    background: 'plain',
    routeBox: { x: 110, y: 260, width: 860, height: 890 },
    routePadding: 50,
    style: { lineWidth: 14, underWidth: 0, dotRadius: 34, dotFont: 40, briefRadius: 12, briefStroke: 6, briefStrokeColor: '#131417', shadow: false },
    brand: null,
    text: STORY_TEXT,
  },
  photo: {
    ...CARD_SIZES.photo,
    background: 'photo',
    routeBox: { x: 300, y: 260, width: 700, height: 560 },
    routePadding: 46,
    style: { lineWidth: 14, underWidth: 22, dotRadius: 30, dotFont: 36, briefRadius: 12, briefStroke: 6, briefStrokeColor: COLORS.under, shadow: true },
    brand: null,
    text: STORY_TEXT,
  },
  square: {
    ...CARD_SIZES.square,
    background: 'plain',
    routeBox: { x: 120, y: 150, width: 840, height: 410 },
    routePadding: 50,
    style: { lineWidth: 14, underWidth: 0, dotRadius: 34, dotFont: 40, briefRadius: 12, briefStroke: 6, briefStrokeColor: '#131417', shadow: false },
    brand: { text: BRAND, x: 120, y: 73 },
    text: {
      left: 120,
      right: 960,
      dateY: 613,
      title: { y: 666, sizes: [68, 56], lineHeight: 62 },
      dividerY: 789,
      stats: { top: 833, left: 150, right: 930, labelGap: 13, sizes: [[72, 40, 14], [64, 36, 12]] },
      footerTop: null,
    },
  },
};

/** 숫자 칸 사이 최소 간격 */
const STAT_MIN_GAP = 40;
const STAT_LABEL_SIZE = 36;

/**
 * 폭을 넘는 글자는 뒤를 자르고 …를 붙인다. widthOf(text) 는 그 글자 폭.
 */
function ellipsize(text, maxWidth, widthOf) {
  if (widthOf(text) <= maxWidth) return text;
  const chars = [...text];
  while (chars.length > 0) {
    chars.pop();
    const candidate = `${chars.join('').trimEnd()}…`;
    if (widthOf(candidate) <= maxWidth) return candidate;
  }
  return '…';
}

/** 두 줄로 나눈다. 첫 줄은 띄어쓰기 단위로 채우고(한 단어도 안 들어가면 글자 단위), 나머지가 넘치면 …로 자른다 */
function wrapTwoLines(text, maxWidth, widthOf) {
  const words = text.split(' ');
  let first = '';
  let used = 0;
  for (const word of words) {
    const candidate = first ? `${first} ${word}` : word;
    if (widthOf(candidate) > maxWidth) break;
    first = candidate;
    used++;
  }
  let rest;
  if (used === 0) {
    const chars = [...text];
    let n = 0;
    while (n < chars.length && widthOf(chars.slice(0, n + 1).join('')) <= maxWidth) n++;
    first = chars.slice(0, Math.max(1, n)).join('');
    rest = chars.slice(Math.max(1, n)).join('').trim();
  } else {
    rest = words.slice(used).join(' ');
  }
  return rest ? [first, ellipsize(rest, maxWidth, widthOf)] : [first];
}

/** 제목: 큰 크기부터 한 줄에 들어가는 크기를 고르고, 가장 작은 크기로도 넘치면 두 줄. 두 줄이면 첫 줄을 한 줄 위에 둔다 */
function layoutTitle(text, t, measure) {
  const maxWidth = t.right - t.left;
  for (const size of t.title.sizes) {
    if (measure(text, size, 800) <= maxWidth) return { size, lines: [{ text, x: t.left, y: t.title.y }], shift: 0 };
  }
  const size = t.title.sizes[t.title.sizes.length - 1];
  const lines = wrapTwoLines(text, maxWidth, (s) => measure(s, size, 800));
  const shift = lines.length > 1 ? t.title.lineHeight : 0;
  return {
    size,
    lines: lines.map((line, i) => ({ text: line, x: t.left, y: t.title.y - shift + i * t.title.lineHeight })),
    shift,
  };
}

/**
 * 숫자 칸 3개. 칸 폭은 값(숫자+단위)과 라벨 중 넓은 쪽이고, 칸은 left~right 에 양 끝 맞춤(space-between)으로 놓는다.
 * 칸 폭 합 + 최소 간격 2개가 폭을 넘으면 다음(작은) 크기로 내린다.
 */
function layoutStats(trip, s, measure) {
  const stats = tripStats(trip).map((stat) => ({ parts: valueParts(stat.value), label: stat.label }));
  const span = s.right - s.left;
  let chosen = null;
  let widths = null;
  for (const [size, unitSize, unitGap] of s.sizes) {
    widths = stats.map((stat) => {
      const value = stat.parts.reduce((w, p) => w + measure(p.num, size, 800) + measure(p.unit, unitSize, 700), 0)
        + unitGap * (stat.parts.length - 1);
      return Math.max(value, measure(stat.label, STAT_LABEL_SIZE, 400));
    });
    chosen = { size, unitSize, unitGap };
    if (widths.reduce((a, b) => a + b, 0) + 2 * STAT_MIN_GAP <= span) break;
  }
  const gap = (span - widths.reduce((a, b) => a + b, 0)) / 2;
  const xs = [s.left, Math.round(s.left + widths[0] + gap), Math.round(s.right - widths[2])];
  const baseline = s.top + Math.round(chosen.size * ASCENT);
  const labelTop = s.top + chosen.size + s.labelGap;
  return {
    ...chosen,
    labelSize: STAT_LABEL_SIZE,
    columns: stats.map((stat, i) => ({ x: xs[i], parts: stat.parts, label: stat.label, baseline, labelTop })),
  };
}

/**
 * 공유 이미지 1장(사진 배경, 스토리, 정사각형)의 배치.
 * measure(text, size, weight) 는 글자 폭이다(화면에서는 canvas measureText). nowMinute 는 날짜의 연도 생략 판단에 쓴다.
 */
export function layoutCard(trip, kind, { measure, nowMinute }) {
  const spec = CARD_SPECS[kind];
  const t = spec.text;
  const route = layoutRoute(trip, spec.routeBox, spec.routePadding);
  const title = layoutTitle(trip.title || '여행', t, measure);
  const footerY = t.footerTop === null ? null : t.footerTop + Math.round(40 * ASCENT);
  return {
    kind,
    width: spec.width,
    height: spec.height,
    background: spec.background,
    routeBox: spec.routeBox,
    style: spec.style,
    line: route.line,
    routeFromStops: route.routeFromStops,
    markers: groupMarkers(route.dots, spec.style.dotRadius * 2),
    brand: spec.brand,
    date: { text: formatDate(trip.startMinute, nowMinute), x: t.left, y: t.dateY - title.shift, size: 40 },
    title: { size: title.size, lines: title.lines },
    divider: { x1: t.left, x2: t.right, y: t.dividerY },
    stats: layoutStats(trip, t.stats, measure),
    footer: footerY === null ? null : {
      brand: { text: BRAND, x: t.left, y: footerY },
      tagline: { text: '차에서 기록한 여행', x: t.right, y: footerY },
    },
  };
}

/** 사진을 상자에 cover 로 맞출 때 사진에서 잘라 쓸 영역 */
export function coverRect(imageWidth, imageHeight, boxWidth, boxHeight) {
  const scale = Math.max(boxWidth / imageWidth, boxHeight / imageHeight);
  const sw = boxWidth / scale;
  const sh = boxHeight / scale;
  return { sx: (imageWidth - sw) / 2, sy: (imageHeight - sh) / 2, sw, sh };
}

const STOP_HEADER_HEIGHT = 170;
const STOP_BAND_HEIGHT = 120;
const STOP_TILE_GAP = 4;
/** 사진이 없는 칸의 높이 비율 */
const NO_PHOTO_WEIGHT = 0.5;

/**
 * 들른 곳별 이미지(1080×1350). 방문지·숙박마다 한 칸(대표 사진 + 번호 + 이름 + 시각·머문 시간)을 세로로 쌓는다.
 * 잠깐 정차는 넣지 않는다. 한 장에 perPage 칸까지 넣고, 넘으면 장 수를 늘린 뒤 장마다 칸 수를 고르게 나눈다(4곳 → 2 + 2).
 * 첫 장은 위에 머리 띠(날짜, 브랜드, 제목), 둘째 장부터는 아래 띠(브랜드, "2/3").
 * hasPhoto(stopIndex) 가 false 인 칸은 높이를 절반 비율로 줄인다.
 */
export function layoutStopPages(trip, { nowMinute, hasPhoto = () => false, perPage = 3 } = {}) {
  const { width, height } = STOP_PAGE_SIZE;
  const marks = stopMarks(trip.stops);
  const items = trip.stops.map((stop, stopIndex) => ({ stop, stopIndex, mark: marks[stopIndex] })).filter((it) => it.mark.kind !== 'brief');
  const total = Math.ceil(items.length / perPage);
  const pages = [];
  let next = 0;
  for (let index = 0; index < total; index++) {
    const count = Math.ceil((items.length - next) / (total - index));
    const chunk = items.slice(next, next + count);
    next += count;
    const first = index === 0;
    const areaTop = first ? STOP_HEADER_HEIGHT : 0;
    const areaBottom = first ? height : height - STOP_BAND_HEIGHT;
    const available = areaBottom - areaTop - STOP_TILE_GAP * (chunk.length - 1);
    const weights = chunk.map((it) => (hasPhoto(it.stopIndex) ? 1 : NO_PHOTO_WEIGHT));
    const weightSum = weights.reduce((a, b) => a + b, 0);
    let y = areaTop;
    let used = 0;
    const tiles = chunk.map((it, row) => {
      const tileHeight = row === chunk.length - 1 ? available - used : Math.round((available * weights[row]) / weightSum);
      used += tileHeight;
      const bottom = y + tileHeight;
      const tile = {
        stopIndex: it.stopIndex,
        label: it.mark.label,
        kind: it.mark.kind,
        x: 0,
        y,
        width,
        height: tileHeight,
        name: it.stop.name || '방문지',
        detail: `${formatTimeRange(it.stop.arriveMinute, it.stop.stayMinutes)} · ${formatDuration(it.stop.stayMinutes)}`,
        rainy: it.stop.rainy === true,
        badge: { x: 100, y: bottom - 106, radius: 40 },
        textX: 168,
        nameY: bottom - 111,
        detailY: bottom - 54,
        maxWidth: width - 168 - 60,
        shadeTop: Math.max(y, bottom - 260),
      };
      y = bottom + STOP_TILE_GAP;
      return tile;
    });
    pages.push({
      width,
      height,
      index,
      total,
      header: first ? {
        height: STOP_HEADER_HEIGHT,
        date: { text: formatDate(trip.startMinute, nowMinute), x: 60, y: 38 },
        brand: { text: BRAND, x: width - 60, y: 38 },
        title: { text: trip.title || '여행', x: 60, y: 88, maxWidth: width - 120 },
      } : null,
      band: first ? null : {
        y: height - STOP_BAND_HEIGHT,
        height: STOP_BAND_HEIGHT,
        brand: { text: BRAND, x: 60, y: height - STOP_BAND_HEIGHT / 2 },
        pill: { text: `${index + 1}/${total}`, right: width - 60, y: height - STOP_BAND_HEIGHT / 2, height: 56 },
      },
      tiles,
    });
  }
  return pages;
}

// ── 그리기 ─────────────────────────────────────────────

/** 폭을 넘는 글자는 뒤를 자르고 …를 붙인다. ctx.font 는 호출 전에 정해 둔다 */
export function fitText(ctx, text, maxWidth) {
  return ellipsize(text, maxWidth, (s) => ctx.measureText(s).width);
}

/** 가로 pill 경로. 높이의 절반이 반지름이다 */
function pillPath(ctx, x, y, width, height) {
  const r = height / 2;
  ctx.beginPath();
  ctx.arc(x + r, y + r, r, Math.PI / 2, (Math.PI * 3) / 2);
  ctx.lineTo(x + width - r, y);
  ctx.arc(x + width - r, y + r, r, -Math.PI / 2, Math.PI / 2);
  ctx.closePath();
}

/**
 * 경로 선과 정차 점. 화면의 경로 그림과 공유 이미지가 함께 쓴다.
 * route = {line, routeFromStops, markers}, style = layoutCard 의 style 과 같은 모양.
 * 정차를 이은 선(routeFromStops)은 점선으로 그린다.
 */
export function drawRoute(ctx, route, style, { dotWeight = 800 } = {}) {
  ctx.lineJoin = 'round';
  ctx.lineCap = 'round';
  if (route.line.length >= 2) {
    const path = () => {
      ctx.beginPath();
      route.line.forEach(([x, y], i) => (i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y)));
    };
    const dash = route.routeFromStops ? [style.lineWidth * 0.1, style.lineWidth * 2.2] : [];
    if (style.underWidth > 0) {
      ctx.setLineDash(dash);
      ctx.strokeStyle = COLORS.under;
      ctx.lineWidth = style.underWidth;
      path();
      ctx.stroke();
    }
    ctx.setLineDash(dash);
    ctx.strokeStyle = COLORS.accent;
    ctx.lineWidth = style.lineWidth;
    path();
    ctx.stroke();
    ctx.setLineDash([]);
  }
  for (const marker of route.markers) {
    if (marker.kind === 'brief') {
      ctx.beginPath();
      ctx.arc(marker.x, marker.y, style.briefRadius, 0, Math.PI * 2);
      ctx.fillStyle = COLORS.idle;
      ctx.fill();
      ctx.lineWidth = style.briefStroke;
      ctx.strokeStyle = style.briefStrokeColor;
      ctx.stroke();
      continue;
    }
    const r = style.dotRadius;
    ctx.font = font(dotWeight, style.dotFont);
    const textWidth = ctx.measureText(marker.label).width;
    const width = [...marker.label].length > 1 ? Math.max(2 * r, textWidth + r * 1.2) : 2 * r;
    ctx.fillStyle = COLORS.accent;
    if (width === 2 * r) {
      ctx.beginPath();
      ctx.arc(marker.x, marker.y, r, 0, Math.PI * 2);
    } else {
      pillPath(ctx, marker.x - width / 2, marker.y - r, width, 2 * r);
    }
    ctx.fill();
    ctx.fillStyle = COLORS.onAccent;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(marker.label, marker.x, marker.y);
  }
  ctx.textAlign = 'left';
  ctx.textBaseline = 'alphabetic';
}

function fillGradient(ctx, x, y0, width, y1, from, to) {
  const gradient = ctx.createLinearGradient(0, y0, 0, y1);
  gradient.addColorStop(0, from);
  gradient.addColorStop(1, to);
  ctx.fillStyle = gradient;
  ctx.fillRect(x, y0, width, y1 - y0);
}

/** 사진 배경 카드에서 어둡게 하는 아래 정보 영역의 시작 y */
const PHOTO_SHADE_BOTTOM_FROM = 1050;

/**
 * 공유 이미지 1장(사진 배경, 스토리, 정사각형)을 그린다.
 * 사진 배경은 options.photo(HTMLImageElement 등 width·height 가 있는 이미지)를 cover 로 깔고,
 * 위 1/4 과 아래 정보 영역만 어둡게 한 뒤 경로 선 아래에 어두운 선을 먼저 그리고 글자에 그림자를 준다.
 */
export function drawCard(ctx, layout, { photo = null } = {}) {
  const { width, height } = layout;
  ctx.fillStyle = COLORS.background;
  ctx.fillRect(0, 0, width, height);
  if (layout.background === 'photo' && photo) {
    const { sx, sy, sw, sh } = coverRect(imageWidth(photo), imageHeight(photo), width, height);
    ctx.drawImage(photo, sx, sy, sw, sh, 0, 0, width, height);
    fillGradient(ctx, 0, 0, width, height / 4, 'rgba(19,20,23,0.25)', 'rgba(19,20,23,0)');
    fillGradient(ctx, 0, PHOTO_SHADE_BOTTOM_FROM, width, height, 'rgba(19,20,23,0)', 'rgba(19,20,23,0.92)');
  }

  drawRoute(ctx, layout, layout.style);

  if (layout.style.shadow) {
    ctx.shadowColor = 'rgba(0,0,0,0.5)';
    ctx.shadowBlur = 12;
    ctx.shadowOffsetY = 2;
  }
  ctx.textAlign = 'left';
  ctx.textBaseline = 'top';
  if (layout.brand) {
    ctx.fillStyle = COLORS.accent;
    ctx.font = font(800, 40);
    ctx.fillText(layout.brand.text, layout.brand.x, layout.brand.y);
  }
  ctx.fillStyle = COLORS.accent;
  ctx.font = font(600, layout.date.size);
  ctx.fillText(layout.date.text, layout.date.x, layout.date.y);
  ctx.fillStyle = COLORS.text;
  ctx.font = font(800, layout.title.size);
  for (const line of layout.title.lines) ctx.fillText(line.text, line.x, line.y);

  ctx.fillStyle = COLORS.divider;
  ctx.fillRect(layout.divider.x1, layout.divider.y, layout.divider.x2 - layout.divider.x1, 2);

  const { stats } = layout;
  for (const column of stats.columns) {
    ctx.textBaseline = 'alphabetic';
    ctx.fillStyle = COLORS.text;
    let x = column.x;
    column.parts.forEach((part, i) => {
      ctx.font = font(800, stats.size);
      ctx.fillText(part.num, x, column.baseline);
      x += ctx.measureText(part.num).width;
      ctx.font = font(700, stats.unitSize);
      ctx.fillText(part.unit, x, column.baseline);
      x += ctx.measureText(part.unit).width + (i < column.parts.length - 1 ? stats.unitGap : 0);
    });
    ctx.textBaseline = 'top';
    ctx.fillStyle = COLORS.text72;
    ctx.font = font(400, stats.labelSize);
    ctx.fillText(column.label, column.x, column.labelTop);
  }

  if (layout.footer) {
    ctx.textBaseline = 'alphabetic';
    ctx.fillStyle = COLORS.accent;
    ctx.font = font(800, 40);
    ctx.fillText(layout.footer.brand.text, layout.footer.brand.x, layout.footer.brand.y);
    ctx.fillStyle = COLORS.text72;
    ctx.font = font(400, 30);
    ctx.textAlign = 'right';
    ctx.fillText(layout.footer.tagline.text, layout.footer.tagline.x, layout.footer.tagline.y);
  }
  ctx.textAlign = 'left';
  ctx.textBaseline = 'alphabetic';
  if (layout.style.shadow) {
    ctx.shadowColor = 'rgba(0,0,0,0)';
    ctx.shadowBlur = 0;
    ctx.shadowOffsetY = 0;
  }
}

/** 들른 곳별 이미지 1장을 그린다. photos[stopIndex] 가 그 곳의 대표 사진(없으면 null) */
export function drawStopPage(ctx, page, photos = []) {
  ctx.fillStyle = COLORS.background;
  ctx.fillRect(0, 0, page.width, page.height);

  if (page.header) {
    const h = page.header;
    ctx.textBaseline = 'top';
    ctx.fillStyle = COLORS.accent;
    ctx.font = font(600, 34);
    ctx.textAlign = 'left';
    ctx.fillText(h.date.text, h.date.x, h.date.y);
    ctx.font = font(800, 34);
    ctx.textAlign = 'right';
    ctx.fillText(h.brand.text, h.brand.x, h.brand.y);
    ctx.textAlign = 'left';
    ctx.fillStyle = COLORS.text;
    ctx.font = font(800, 56);
    ctx.fillText(fitText(ctx, h.title.text, h.title.maxWidth), h.title.x, h.title.y);
  }

  for (const tile of page.tiles) {
    const photo = photos[tile.stopIndex] || null;
    const bottom = tile.y + tile.height;
    if (photo) {
      const { sx, sy, sw, sh } = coverRect(imageWidth(photo), imageHeight(photo), tile.width, tile.height);
      ctx.drawImage(photo, sx, sy, sw, sh, tile.x, tile.y, tile.width, tile.height);
      fillGradient(ctx, tile.x, tile.shadeTop, tile.width, bottom, 'rgba(19,20,23,0)', 'rgba(19,20,23,0.85)');
    } else {
      ctx.fillStyle = COLORS.surface;
      ctx.fillRect(tile.x, tile.y, tile.width, tile.height);
    }

    ctx.beginPath();
    ctx.arc(tile.badge.x, tile.badge.y, tile.badge.radius, 0, Math.PI * 2);
    ctx.fillStyle = COLORS.accent;
    ctx.fill();
    ctx.fillStyle = COLORS.onAccent;
    ctx.font = font(800, 44);
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(tile.label, tile.badge.x, tile.badge.y);
    ctx.textAlign = 'left';
    ctx.textBaseline = 'alphabetic';

    // 비 칩: 높이 56, 좌우 여백 20, 이름 뒤 간격 20
    ctx.font = font(500, 36);
    const chipWidth = tile.rainy ? ctx.measureText('비').width + 40 : 0;
    ctx.font = font(800, 60);
    const name = fitText(ctx, tile.name, tile.maxWidth - (tile.rainy ? chipWidth + 20 : 0));
    ctx.fillStyle = COLORS.text;
    ctx.fillText(name, tile.textX, tile.nameY);
    if (tile.rainy) {
      const chipX = tile.textX + ctx.measureText(name).width + 20;
      const chipTop = tile.nameY - 51;
      ctx.fillStyle = 'rgba(0,130,255,0.3)';
      pillPath(ctx, chipX, chipTop, chipWidth, 56);
      ctx.fill();
      ctx.fillStyle = COLORS.rain;
      ctx.font = font(500, 36);
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('비', chipX + chipWidth / 2, chipTop + 28);
      ctx.textAlign = 'left';
      ctx.textBaseline = 'alphabetic';
    }
    ctx.fillStyle = 'rgba(255,255,255,0.8)';
    ctx.font = font(400, 36);
    ctx.fillText(fitText(ctx, tile.detail, tile.maxWidth), tile.textX, tile.detailY);
  }

  if (page.band) {
    const b = page.band;
    ctx.fillStyle = COLORS.background;
    ctx.fillRect(0, b.y, page.width, b.height);
    ctx.textBaseline = 'middle';
    ctx.textAlign = 'left';
    ctx.fillStyle = COLORS.accent;
    ctx.font = font(800, 34);
    ctx.fillText(b.brand.text, b.brand.x, b.brand.y);
    ctx.font = font(700, 36);
    const pillWidth = ctx.measureText(b.pill.text).width + 48;
    ctx.fillStyle = 'rgba(255,255,255,0.12)';
    pillPath(ctx, b.pill.right - pillWidth, b.pill.y - b.pill.height / 2, pillWidth, b.pill.height);
    ctx.fill();
    ctx.fillStyle = COLORS.text;
    ctx.textAlign = 'center';
    ctx.fillText(b.pill.text, b.pill.right - pillWidth / 2, b.pill.y);
    ctx.textAlign = 'left';
    ctx.textBaseline = 'alphabetic';
  }
}

// HTMLImageElement 는 naturalWidth 가 원본 크기다(width 는 CSS 영향을 받을 수 있다)
const imageWidth = (image) => image.naturalWidth || image.width;
const imageHeight = (image) => image.naturalHeight || image.height;
