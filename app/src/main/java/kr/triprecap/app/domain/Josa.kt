package kr.triprecap.app.domain

/**
 * 한국어 조사 선택. 마지막 글자를 읽는 소리의 받침으로 고른다.
 * - 한글: 그 글자의 받침
 * - 숫자: 읽는 소리 (1 일, 2 이, 10 십, 1000 천 …)
 * - 그 밖(영문·단위 "km" 등): 받침 없는 것으로 본다. "km"(킬로미터), "QR"(큐알 → "로")도 이 규칙으로 맞는다
 */
object Josa {
    private enum class Final { NONE, RIEUL, OTHER }

    /** 0~9 를 읽는 소리(영 일 이 삼 사 오 육 칠 팔 구)의 받침 */
    private val digitFinals = listOf(
        Final.OTHER, Final.RIEUL, Final.NONE, Final.OTHER, Final.NONE,
        Final.NONE, Final.OTHER, Final.RIEUL, Final.RIEUL, Final.NONE,
    )

    private fun finalOf(word: String): Final {
        val trimmed = word.trimEnd()
        val c = trimmed.lastOrNull() ?: return Final.NONE
        if (c in '0'..'9') return digitsFinal(trimmed)
        if (c !in '가'..'힣') return Final.NONE
        return when ((c - '가') % 28) {
            0 -> Final.NONE
            8 -> Final.RIEUL
            else -> Final.OTHER
        }
    }

    /**
     * 끝의 0 개수로 마지막에 읽는 자릿수 이름을 고른다. 0 이 아닌 끝자리는 그 숫자의 소리다.
     * - 0 이 1~3개: 십(ㅂ) 백(ㄱ) 천(ㄴ)
     * - 0 이 4개 이상: 네 자리 단위 이름이 마지막에 온다(100000 십만 → 만, 20000000000000 이십조 → 조).
     *   만(ㄴ) 억(ㄱ) 경(ㅇ)은 받침이 있고 조는 받침이 없다
     */
    private fun digitsFinal(word: String): Final {
        val digits = word.takeLastWhile { it in '0'..'9' }
        val zeros = digits.takeLastWhile { it == '0' }.length
        if (zeros == 0) return digitFinals[digits.last() - '0']
        if (zeros == digits.length) return Final.OTHER          // 영
        if (zeros < 4) return Final.OTHER                       // 십 백 천
        return if (zeros / 4 == 3) Final.NONE else Final.OTHER  // 조 / 만 억 경
    }

    private fun hasBatchim(word: String) = finalOf(word) != Final.NONE

    fun eunNeun(word: String) = word + if (hasBatchim(word)) "은" else "는"
    fun iGa(word: String) = word + if (hasBatchim(word)) "이" else "가"
    fun eulReul(word: String) = word + if (hasBatchim(word)) "을" else "를"
    /** ㄹ 받침 뒤에는 "로"를 쓴다 ("서울로") */
    fun euroRo(word: String) = word + if (finalOf(word) == Final.OTHER) "으로" else "로"
    /** "~이었어요" / "~였어요" */
    fun ieoss(word: String) = word + if (hasBatchim(word)) "이었어요" else "였어요"
}
