import { test } from 'node:test';
import assert from 'node:assert/strict';
import { barState } from '../js/bar.js';

const base = { photos: 0, placed: 0, places: 0, cardsVisible: false, cardCount: 1 };

test('사진 0장: 갤러리에서 사진 고르기', () => {
  assert.deepEqual(barState(base), {
    action: 'pick', label: '갤러리에서 사진 고르기', hint: '사진은 이 휴대폰 안에서만 써요. 어디에도 올리지 않아요.',
  });
});

test('사진이 있고 카드 구역이 화면 밖: 공유 이미지 만들기, 붙인 수 안내', () => {
  assert.deepEqual(barState({ ...base, photos: 8, placed: 7, places: 3 }), {
    action: 'make', label: '공유 이미지 만들기', hint: '사진 7장을 3곳에 붙였어요',
  });
  assert.equal(barState({ ...base, photos: 2, placed: 0, places: 0 }).hint, '사진 2장을 골랐어요');
});

test('카드 구역이 화면 안: 이미지 공유하기, 여러 장이면 장 수를 적는다', () => {
  const share = { action: 'share', label: '이미지 공유하기', hint: '카카오톡, 인스타그램으로 보낼 수 있어요' };
  assert.deepEqual(barState({ ...base, photos: 8, placed: 7, places: 3, cardsVisible: true }), share);
  assert.deepEqual(barState({ ...base, cardsVisible: true }), share);
  assert.equal(barState({ ...base, photos: 8, cardsVisible: true, cardCount: 3 }).label, '이미지 3장 공유하기');
});

test('카드 구역이 화면 안인데 만들 이미지가 없으면(사진 배경에 쓸 사진 없음) 사진 고르기', () => {
  assert.equal(barState({ ...base, cardsVisible: true, cardCount: 0 }).action, 'pick');
  assert.equal(barState({ ...base, photos: 1, cardsVisible: true, cardCount: 0 }).action, 'pick');
});
