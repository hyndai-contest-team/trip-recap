package kr.triprecap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ToastsTest {
    @Test
    fun `stop deleted toast matches the particle`() {
        assertEquals("강릉중앙시장을 지웠어요", Toasts.stopDeleted("강릉중앙시장"))
        assertEquals("안목해변 카페를 지웠어요", Toasts.stopDeleted("안목해변 카페"))
    }

    @Test
    fun `navi toast names the place`() {
        assertEquals("내비에 경포해변 안내를 보냈어요", Toasts.naviSent("경포해변"))
    }

    @Test
    fun `setting a place names the slot`() {
        assertEquals("집 위치를 정했어요", Toasts.placeSet(PlaceSlot.HOME))
        assertEquals("회사 위치를 정했어요", Toasts.placeSet(PlaceSlot.OFFICE))
    }
}
