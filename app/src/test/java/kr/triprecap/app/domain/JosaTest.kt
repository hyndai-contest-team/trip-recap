package kr.triprecap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class JosaTest {
    @Test
    fun particles() {
        assertEquals("경포해변은", Josa.eunNeun("경포해변"))
        assertEquals("안목해변 카페는", Josa.eunNeun("안목해변 카페"))
        assertEquals("강릉중앙시장이", Josa.iGa("강릉중앙시장"))
        assertEquals("카페가", Josa.iGa("카페"))
        assertEquals("강릉중앙시장이었어요", Josa.ieoss("강릉중앙시장"))
        assertEquals("카페였어요", Josa.ieoss("카페"))
        assertEquals("Cafe는", Josa.eunNeun("Cafe"))
    }

    @Test
    fun `object particle`() {
        assertEquals("강릉중앙시장을", Josa.eulReul("강릉중앙시장"))
        assertEquals("안목해변 카페를", Josa.eulReul("안목해변 카페"))
    }

    @Test
    fun `direction particle uses ro after rieul`() {
        assertEquals("강릉시로", Josa.euroRo("강릉시"))
        assertEquals("경포해변으로", Josa.euroRo("경포해변"))
        assertEquals("서울로", Josa.euroRo("서울"))
        assertEquals("QR로", Josa.euroRo("QR"))
    }

    @Test
    fun `numbers and units follow how they are read`() {
        assertEquals("41km를", Josa.eulReul("41km"))       // 킬로미터
        assertEquals("8.5km를", Josa.eulReul("8.5km"))
        assertEquals("3곳을", Josa.eulReul("3곳"))
        assertEquals("1을", Josa.eulReul("1"))              // 일
        assertEquals("2를", Josa.eulReul("2"))              // 이
        assertEquals("10을", Josa.eulReul("10"))            // 십
        assertEquals("1000을", Josa.eulReul("1000"))        // 천
        assertEquals("1000으로", Josa.euroRo("1000"))       // 천
        assertEquals("3000만을", Josa.eulReul("3000만"))    // 만
        assertEquals("1로", Josa.euroRo("1"))               // 일
        assertEquals("3으로", Josa.euroRo("3"))             // 삼
        assertEquals("5로", Josa.euroRo("5"))               // 오
        assertEquals("6이", Josa.iGa("6"))                  // 육
    }

    @Test
    fun `every last digit follows how it is read`() {
        // 영 일 이 삼 사 오 육 칠 팔 구
        val eulReul = listOf("0을", "1을", "2를", "3을", "4를", "5를", "6을", "7을", "8을", "9를")
        val euroRo = listOf("0으로", "1로", "2로", "3으로", "4로", "5로", "6으로", "7로", "8로", "9로")
        for (d in 0..9) {
            assertEquals(eulReul[d], Josa.eulReul("$d"))
            assertEquals(euroRo[d], Josa.euroRo("$d"))
        }
        assertEquals("21을", Josa.eulReul("21"))            // 이십일
        assertEquals("17로", Josa.euroRo("17"))             // 십칠
        assertEquals("108로", Josa.euroRo("108"))           // 백팔
        assertEquals("8.5km를", Josa.eulReul("8.5km"))
        assertEquals("2.0을", Josa.eulReul("2.0"))          // 이 점 영
    }

    @Test
    fun `place names follow how they are read`() {
        assertEquals("10을", Josa.eulReul("10"))                           // 십
        assertEquals("10으로", Josa.euroRo("10"))
        assertEquals("100을", Josa.eulReul("100"))                         // 백
        assertEquals("100으로", Josa.euroRo("100"))
        assertEquals("3000을", Josa.eulReul("3000"))                       // 천
        assertEquals("3000으로", Josa.euroRo("3000"))
        assertEquals("10000을", Josa.eulReul("10000"))                     // 만
        assertEquals("10000으로", Josa.euroRo("10000"))
        assertEquals("120000을", Josa.eulReul("120000"))                   // 십이만
        assertEquals("1000000을", Josa.eulReul("1000000"))                 // 백만
        assertEquals("10000000을", Josa.eulReul("10000000"))               // 천만
        assertEquals("100000000을", Josa.eulReul("100000000"))             // 억
        assertEquals("100000000으로", Josa.euroRo("100000000"))
        assertEquals("1000000000000를", Josa.eulReul("1000000000000"))     // 조
        assertEquals("1000000000000로", Josa.euroRo("1000000000000"))
        assertEquals("20000000000000를", Josa.eulReul("20000000000000"))   // 이십조
    }
}
