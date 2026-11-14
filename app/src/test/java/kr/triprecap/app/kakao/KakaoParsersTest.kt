package kr.triprecap.app.kakao

import kr.triprecap.app.domain.RegionName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 응답 예시는 카카오 로컬 API 문서의 필드 이름을 따랐다. 실제 호출은 확인하지 않았다 */
class KakaoParsersTest {
    @Test
    fun `nearest place is the document with the smallest distance`() {
        val json = """{"meta":{"total_count":2},"documents":[
            {"place_name":"경포 횟집","distance":"120","category_group_code":"FD6","x":"128.90","y":"37.80"},
            {"place_name":"경포해변","distance":"35","category_group_code":"AT4","x":"128.90","y":"37.80"}]}"""
        assertEquals(KakaoParsers.Place("경포해변", 35), KakaoParsers.nearestPlace(json))
    }

    @Test
    fun `no documents means no place`() {
        assertNull(KakaoParsers.nearestPlace("""{"meta":{"total_count":0},"documents":[]}"""))
        assertNull(KakaoParsers.nearestPlace("""{"meta":{}}"""))
    }

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
