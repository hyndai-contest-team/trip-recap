package kr.triprecap.app.kakao

import kr.triprecap.app.domain.RegionName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 응답 예시는 카카오 로컬 API 문서의 필드 이름을 따랐다. 실제 응답으로 고른 이름은 2026-11-15 에 확인했다 */
class KakaoParsersTest {
    @Test
    fun `places keeps every document with its distance and category`() {
        val json = """{"meta":{"total_count":2},"documents":[
            {"place_name":"경포 횟집","distance":"120","category_group_code":"FD6","x":"128.90","y":"37.80"},
            {"place_name":"","distance":"10","category_group_code":"FD6","x":"128.90","y":"37.80"},
            {"place_name":"경포해변","distance":"35","category_group_code":"FD6","x":"128.90","y":"37.80"}]}"""
        assertEquals(
            listOf(KakaoParsers.Place("경포 횟집", 120, "FD6"), KakaoParsers.Place("경포해변", 35, "FD6")),
            KakaoParsers.places(json, "FD6"),
        )
    }

    @Test
    fun `no documents means no place`() {
        assertEquals(emptyList<KakaoParsers.Place>(), KakaoParsers.places("""{"meta":{"total_count":0},"documents":[]}""", "AT4"))
        assertEquals(emptyList<KakaoParsers.Place>(), KakaoParsers.places("""{"meta":{}}""", "AT4"))
        assertNull(KakaoParsers.pick(emptyList()))
    }

    // ── 관광명소 우선 ──────────────────────────────────────────────

    @Test
    fun `a tourist attraction in the radius wins over a closer restaurant or cafe`() {
        val picked = KakaoParsers.pick(
            listOf(
                KakaoParsers.Place("에비다", 28, "FD6"),
                KakaoParsers.Place("더벤티 강릉금학점", 38, "CE7"),
                KakaoParsers.Place("강릉대도호부관아", 140, "AT4"),
            ),
        )
        assertEquals("강릉대도호부관아", picked)
    }

    @Test
    fun `the nearest attraction wins when there are several`() {
        val picked = KakaoParsers.pick(listOf(KakaoParsers.Place("오죽헌", 120, "AT4"), KakaoParsers.Place("오죽헌 시립박물관", 60, "AT4")))
        assertEquals("오죽헌 시립박물관", picked)
    }

    @Test
    fun `without an attraction the nearest place of any category wins`() {
        val picked = KakaoParsers.pick(
            listOf(
                KakaoParsers.Place("강릉독립예술극장 신영", 114, "CT1"),
                KakaoParsers.Place("에비다", 28, "FD6"),
                KakaoParsers.Place("더벤티 강릉금학점", 38, "CE7"),
            ),
        )
        assertEquals("에비다", picked)
    }

    @Test
    fun `the picked name is cleaned`() {
        assertEquals("경포해변", KakaoParsers.pick(listOf(KakaoParsers.Place("경포해변인증센터", 127, "AT4"), KakaoParsers.Place("경포 횟집", 30, "FD6"))))
    }

    // ── 이름 정리 ──────────────────────────────────────────────────

    @Test
    fun `a facility tail after a place name is removed`() {
        assertEquals("경포해변", KakaoParsers.cleanName("경포해변인증센터"))
        assertEquals("협재해수욕장", KakaoParsers.cleanName("협재해수욕장 캠핑장"))
        assertEquals("강릉중앙시장", KakaoParsers.cleanName("강릉중앙시장 공영주차장"))
        assertEquals("설악산", KakaoParsers.cleanName("설악산 탐방지원센터"))
        assertEquals("강릉역", KakaoParsers.cleanName("강릉역 주차장"))
    }

    @Test
    fun `several facility tails are removed one after another`() {
        assertEquals("속초해수욕장", KakaoParsers.cleanName("속초해수욕장 주차장 입구"))
    }

    @Test
    fun `a name whose rest is not a place name is kept`() {
        assertEquals("솔향캠핑장", KakaoParsers.cleanName("솔향캠핑장"))
        assertEquals("주차장", KakaoParsers.cleanName("주차장"))
        assertEquals("하늘전망대", KakaoParsers.cleanName("하늘전망대"))
        assertEquals("더벤티 강릉금학점", KakaoParsers.cleanName("더벤티 강릉금학점"))
    }

    @Test
    fun `surrounding spaces are trimmed`() {
        assertEquals("오죽헌", KakaoParsers.cleanName("  오죽헌 "))
    }

    // ── 행정구역 ───────────────────────────────────────────────────

    @Test
    fun `region prefers the administrative dong`() {
        val json = """{"documents":[
            {"region_type":"B","region_1depth_name":"강원특별자치도","region_2depth_name":"강릉시","region_3depth_name":"안현동"},
            {"region_type":"H","region_1depth_name":"강원특별자치도","region_2depth_name":"강릉시","region_3depth_name":"경포동"}]}"""
        assertEquals(RegionName("강릉시", "경포동"), KakaoParsers.region(json))
    }

    @Test
    fun `region without a second level uses the first level`() {
        val json = """{"documents":[{"region_type":"H","region_1depth_name":"세종특별자치시","region_2depth_name":"","region_3depth_name":"한솔동"}]}"""
        assertEquals(RegionName("세종특별자치시", "한솔동"), KakaoParsers.region(json))
    }

    @Test
    fun `empty region response gives null`() {
        assertNull(KakaoParsers.region("""{"documents":[]}"""))
    }
}
