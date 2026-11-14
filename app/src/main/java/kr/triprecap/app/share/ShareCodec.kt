package kr.triprecap.app.share

import kr.triprecap.app.domain.GeoPoint
import kr.triprecap.app.domain.KnownPlaces
import kr.triprecap.app.domain.StopKind
import kr.triprecap.app.domain.Trip
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 휴대폰으로 보내는 여행 데이터. 좌표는 소수 4자리, 시각은 분 단위다 */
data class SharedTrip(
    val title: String,
    /** 여행 시작 (epoch 분) */
    val startMinute: Long,
    val durationMinutes: Int,
    /** 100m 단위로 반올림한 거리 */
    val distanceM: Int,
    val people: Int,
    val stops: List<SharedStop>,
    val route: List<GeoPoint>,
)

data class SharedStop(
    val kind: StopKind,
    val rainy: Boolean,
    val point: GeoPoint,
    /** 도착 (epoch 분) */
    val arriveMinute: Long,
    val stayMinutes: Int,
    val name: String,
)

/** 공유 미리보기의 한 행: 라벨("보내는 것")과 값("방문지 3곳, 경로") */
data class ShareRow(val label: String, val value: String)

/**
 * 공유 전에 화면에 보여 주는 내용 (docs/product-plan.md 2-9)
 * @property excludedCount 사용자가 공유에서 직접 뺀 정차 수
 */
data class SharePreview(val visitCount: Int, val hasRoute: Boolean, val hidesHomeOrOffice: Boolean, val excludedCount: Int = 0) {
    /** 공유 화면의 두 행: "보내는 것" / "보내지 않는 것" */
    val rows: List<ShareRow>
        get() {
            val sent = listOfNotNull(if (visitCount > 0) "방문지 ${visitCount}곳" else null, if (hasRoute) "경로" else null)
            val withheld = listOfNotNull(
                if (hidesHomeOrOffice) "집·회사 근처" else null,
                if (excludedCount > 0) "직접 뺀 ${excludedCount}곳" else null,
                "사진",
            )
            return listOf(
                ShareRow("보내는 것", sent.joinToString(", ").ifEmpty { "없음" }),
                ShareRow("보내지 않는 것", withheld.joinToString(", ")),
            )
        }
}

class SharePayload(val bytes: ByteArray, val trip: SharedTrip, val preview: SharePreview) {
    /** URL 의 # 뒤에 붙이는 문자열 (base64url, 패딩 없음) */
    val hash: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun url(baseUrl: String): String = "$baseUrl#$hash"
    /** 공유 페이지 주소가 설정되지 않았으면(빈 값) null. 화면은 이때 QR 을 그리지 않는다 */
    fun urlOrNull(baseUrl: String?): String? = baseUrl?.takeIf { it.isNotBlank() }?.let(::url)
}

/**
 * 여행 → 1KB 이하 바이트 → URL hash. 서버는 # 뒤를 받지 않는다. (docs/product-plan.md 2-7)
 *
 * 위치 가리기: HOME·OFFICE 500m 안의 경로 점과 정차, 사용자가 공유 제외로 표시한 정차를 뺀다.
 * 잠깐 정차는 보내지 않는다.
 *
 * 바이트 형식 v1 (web/js/codec.js 가 같은 형식을 읽는다. 형식 설명: docs/share-format.md)
 *   u8 version=1 · varint startMinute · varint durationMinutes · varint distance(100m) · u8 people · str title
 *   varint stopCount · stop*{ u8 flags(bit0-1 kind, bit2 rainy) · svarint dLat · svarint dLon · varint arriveOffsetMin · varint stayMin · str name }
 *   varint routeCount · point*{ svarint dLat · svarint dLon }
 *   좌표는 1e-4 도 정수, 직전 값과의 차이(첫 값은 0 기준). str = varint 바이트 길이 + UTF-8
 */
object ShareCodec {
    const val VERSION = 1
    const val MAX_BYTES = 1024
    const val HIDE_RADIUS_M = 500.0
    private const val E4 = 10_000.0

    fun build(trip: Trip, places: KnownPlaces, maxBytes: Int = MAX_BYTES): SharePayload {
        fun hidden(p: GeoPoint) = places.isHomeOrOffice(p, HIDE_RADIUS_M)
        val startMinute = trip.startedAt / 60_000L
        val stops = trip.stops
            .filter { it.kind != StopKind.BRIEF && !it.excludedFromShare }
            .mapNotNull { s ->
                val p = s.point?.takeIf { !hidden(it) } ?: return@mapNotNull null
                SharedStop(s.kind, s.rainy, round4(p), s.arrivedAt / 60_000L, s.stayMinutes.toInt(), trip.nameOf(s))
            }
        val rawRoute = trip.drives.flatMap { d -> d.track.map { it.point } }.filter { !hidden(it) }.map(::round4)
        val route = rawRoute.filterIndexed { i, p -> i == 0 || p != rawRoute[i - 1] }
        val base = SharedTrip(
            title = trip.title,
            startMinute = startMinute,
            durationMinutes = ((trip.endedAt - trip.startedAt) / 60_000L).toInt(),
            distanceM = ((trip.distanceM / 100.0).roundToInt()) * 100,
            people = trip.peopleCount,
            stops = stops,
            route = route,
        )
        val shared = shrink(base, maxBytes)
        val preview = SharePreview(
            visitCount = shared.stops.count { it.kind == StopKind.VISIT },
            hasRoute = shared.route.size >= 2,
            hidesHomeOrOffice = places.home != null || places.office != null,
            excludedCount = trip.stops.count { it.excludedFromShare },
        )
        return SharePayload(encode(shared), shared, preview)
    }

    /** 상한에 들어갈 때까지 경로를 단순화한다. 그래도 넘으면 경로를 빼고, 그래도 넘으면 이름을 줄인다 */
    private fun shrink(trip: SharedTrip, maxBytes: Int): SharedTrip {
        var tolerance = 0.5 / E4
        var candidate = trip.copy(route = simplify(trip.route, tolerance))
        while (encode(candidate).size > maxBytes && candidate.route.size > 2) {
            tolerance *= 2
            candidate = trip.copy(route = simplify(trip.route, tolerance))
        }
        if (encode(candidate).size > maxBytes) candidate = candidate.copy(route = emptyList())
        var nameLimit = 24
        while (encode(candidate).size > maxBytes && nameLimit >= 2) {
            candidate = candidate.copy(title = candidate.title.take(nameLimit), stops = candidate.stops.map { it.copy(name = it.name.take(nameLimit)) })
            nameLimit /= 2
        }
        require(encode(candidate).size <= maxBytes) { "여행 데이터가 ${maxBytes}B 에 들어가지 않는다" }
        return candidate
    }

    /** Douglas-Peucker. 허용 오차는 도 단위(경도는 위도에 맞춰 줄인다) */
    internal fun simplify(points: List<GeoPoint>, toleranceDeg: Double): List<GeoPoint> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        val k = cos(Math.toRadians(points[0].lat))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var worst = -1
            var worstD = toleranceDeg
            for (i in a + 1 until b) {
                val d = distanceToSegment(points[i], points[a], points[b], k)
                if (d > worstD) { worstD = d; worst = i }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack.addLast(a to worst)
                stack.addLast(worst to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint, k: Double): Double {
        val px = (p.lon - a.lon) * k; val py = p.lat - a.lat
        val bx = (b.lon - a.lon) * k; val by = b.lat - a.lat
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - bx * t; val dy = py - by * t
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun round4(p: GeoPoint) = GeoPoint((p.lat * E4).roundToLong() / E4, (p.lon * E4).roundToLong() / E4)
    private fun e4(v: Double): Long = (v * E4).roundToLong()

    fun encode(trip: SharedTrip): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(VERSION)
        out.varint(trip.startMinute)
        out.varint(trip.durationMinutes.toLong())
        out.varint((trip.distanceM / 100).toLong())
        out.write(trip.people)
        out.str(trip.title)
        out.varint(trip.stops.size.toLong())
        var lat = 0L; var lon = 0L
        for (s in trip.stops) {
            out.write(s.kind.ordinal or (if (s.rainy) 4 else 0))
            out.svarint(e4(s.point.lat) - lat); out.svarint(e4(s.point.lon) - lon)
            lat = e4(s.point.lat); lon = e4(s.point.lon)
            out.varint(s.arriveMinute - trip.startMinute)
            out.varint(s.stayMinutes.toLong())
            out.str(s.name)
        }
        out.varint(trip.route.size.toLong())
        lat = 0L; lon = 0L
        for (p in trip.route) {
            out.svarint(e4(p.lat) - lat); out.svarint(e4(p.lon) - lon)
            lat = e4(p.lat); lon = e4(p.lon)
        }
        return out.toByteArray()
    }

    fun decodeHash(hash: String): SharedTrip = decode(Base64.getUrlDecoder().decode(hash))

    fun decode(bytes: ByteArray): SharedTrip {
        val r = Reader(bytes)
        require(r.u8() == VERSION) { "지원하지 않는 형식 버전" }
        val start = r.varint()
        val duration = r.varint().toInt()
        val distance = r.varint().toInt() * 100
        val people = r.u8()
        val title = r.str()
        var lat = 0L; var lon = 0L
        val stops = List(r.varint().toInt()) {
            val flags = r.u8()
            lat += r.svarint(); lon += r.svarint()
            val arrive = start + r.varint()
            val stay = r.varint().toInt()
            SharedStop(StopKind.entries[flags and 3], flags and 4 != 0, GeoPoint(lat / E4, lon / E4), arrive, stay, r.str())
        }
        lat = 0L; lon = 0L
        val route = List(r.varint().toInt()) {
            lat += r.svarint(); lon += r.svarint()
            GeoPoint(lat / E4, lon / E4)
        }
        return SharedTrip(title, start, duration, distance, people, stops, route)
    }

    private fun ByteArrayOutputStream.varint(value: Long) {
        require(value >= 0)
        var v = value
        while (v >= 0x80) { write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        write(v.toInt())
    }

    /** zigzag: 0,-1,1,-2 → 0,1,2,3 */
    private fun ByteArrayOutputStream.svarint(value: Long) = varint(if (value >= 0) value * 2 else abs(value) * 2 - 1)

    private fun ByteArrayOutputStream.str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        varint(b.size.toLong())
        write(b)
    }

    private class Reader(private val b: ByteArray) {
        private var i = 0
        fun u8(): Int = b[i++].toInt() and 0xFF
        fun varint(): Long {
            var shift = 0; var v = 0L
            while (true) {
                val x = u8()
                v = v or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return v
                shift += 7
            }
        }
        fun svarint(): Long { val v = varint(); return if (v and 1L == 0L) v / 2 else -(v + 1) / 2 }
        fun str(): String { val n = varint().toInt(); val s = String(b, i, n, Charsets.UTF_8); i += n; return s }
    }
}
