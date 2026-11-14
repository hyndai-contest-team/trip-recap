// 아래 고정 바의 상태. 버튼 하나의 문구와 동작이 화면 상태에 따라 바뀐다.

/**
 * @param {{photos:number, placed:number, places:number, cardsVisible:boolean, cardCount:number}} input
 *   photos 고른 사진 수, placed 방문지에 붙은 사진 수, places 사진이 붙은 곳 수,
 *   cardsVisible "공유할 이미지" 구역이 화면 안에 있는지, cardCount 지금 만들 수 있는 이미지 장 수
 * @returns {{action:'pick'|'make'|'share', label:string, hint:string}}
 *   pick 파일 선택 열기, make 카드 구역으로 이동, share 공유 화면 열기
 */
export function barState({ photos, placed, places, cardsVisible, cardCount }) {
  if (cardsVisible && cardCount > 0) {
    return {
      action: 'share',
      label: cardCount > 1 ? `이미지 ${cardCount}장 공유하기` : '이미지 공유하기',
      hint: '카카오톡, 인스타그램으로 보낼 수 있어요',
    };
  }
  if (photos === 0 || cardsVisible) {
    return { action: 'pick', label: '갤러리에서 사진 고르기', hint: '사진은 이 휴대폰 안에서만 써요. 어디에도 올리지 않아요.' };
  }
  return {
    action: 'make',
    label: '공유 이미지 만들기',
    hint: placed > 0 ? `사진 ${placed}장을 ${places}곳에 붙였어요` : `사진 ${photos}장을 골랐어요`,
  };
}
