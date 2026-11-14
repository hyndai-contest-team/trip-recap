package kr.triprecap.app.domain

/**
 * 도착 카드의 종류 (docs/product-plan.md 2-3)
 * - SAVED: 확실한 여행이 방금 닫힘. "여행을 저장했어요"
 * - SAVED_EARLIER: 집이 아닌 곳에 오래 서 있어서 닫힌 확실한 여행을 다음 시동 때 보여 줌. "지난 여행을 저장했어요"
 * - ASK: 애매한 이동. 여행으로 남길지 묻는다
 */
enum class ArrivalKind { SAVED, SAVED_EARLIER, ASK }

/** 화면에 띄울 도착 카드: 이동과 카드 종류 */
data class Arrival(val trip: Trip, val kind: ArrivalKind)

/** 도착 카드 화면에 보이는 내용. 없는 줄은 null 이다 */
data class ArrivalCard(
    /** 작은 줄: "여행을 저장했어요" / "지난 여행을 저장했어요". 묻는 카드는 아직 저장하지 않았으므로 null */
    val topLine: String?,
    /** 큰 제목: "3곳을 다녀왔어요" / "의암호에 다녀왔어요" / "41km를 달렸어요" */
    val headline: String,
    /** 여행 제목. 큰 제목과 같은 내용이거나 방문지가 없으면 null */
    val subline: String?,
    /** 달린 거리(방문지가 없으면 제목에 있으므로 뺀다), 여행 시간, 함께 탄 사람(2명 이상) */
    val stats: List<Stat>,
    /** "강릉중앙시장에서 가장 오래 머물렀어요 · 1시간 30분". 방문지가 1곳 이하면 null */
    val longestStayLine: String?,
    /** "안목해변 카페 가는 길에 비가 왔어요". 비가 오지 않았으면 null */
    val rainLine: String?,
    /** 묻는 카드의 질문 "이번 이동도 여행으로 남길까요?". 그 밖에는 null */
    val question: String? = null,
) {
    companion object {
        const val TOP_LINE = "여행을 저장했어요"
        const val TOP_LINE_EARLIER = "지난 여행을 저장했어요"
        const val QUESTION = "이번 이동도 여행으로 남길까요?"

        fun of(trip: Trip, kind: ArrivalKind = ArrivalKind.SAVED): ArrivalCard {
            val visits = trip.visits
            val headline = when (visits.size) {
                0 -> "${Josa.eulReul(Texts.distance(trip.distanceM))} 달렸어요"
                1 -> "${trip.nameOf(visits.single())}에 다녀왔어요"
                else -> "${visits.size}곳을 다녀왔어요"
            }
            val subline = when {
                visits.isEmpty() -> null
                visits.size == 1 && trip.title == trip.nameOf(visits.single()) -> null
                else -> trip.title
            }
            val stats = buildList {
                if (visits.isNotEmpty()) add(Stat(Texts.distance(trip.distanceM), Texts.LABEL_DISTANCE))
                add(Stat(Texts.tripLength(trip), Texts.LABEL_TRIP_TIME))
                if (trip.peopleCount >= 2) add(Stat("${trip.peopleCount}명", Texts.LABEL_PEOPLE))
            }
            val longest = if (visits.size >= 2) visits.maxByOrNull { it.stayMinutes } else null
            val longestLine = longest?.let { "${trip.nameOf(it)}에서 가장 오래 머물렀어요 · ${Texts.stay(it)}" }
            val topLine = when (kind) {
                ArrivalKind.SAVED -> TOP_LINE
                ArrivalKind.SAVED_EARLIER -> TOP_LINE_EARLIER
                ArrivalKind.ASK -> null
            }
            val question = if (kind == ArrivalKind.ASK) QUESTION else null
            return ArrivalCard(topLine, headline, subline, stats, longestLine, rainLine(TripSummarizer.summarize(trip).rainyNames), question)
        }

        /** 1곳 "A", 2곳 "A, B", 3곳 이상 "A 등 3곳" 뒤에 "가는 길에 비가 왔어요" */
        private fun rainLine(names: List<String>): String? {
            val where = when (names.size) {
                0 -> return null
                1, 2 -> names.joinToString(", ")
                else -> "${names.first()} 등 ${names.size}곳"
            }
            return "$where 가는 길에 비가 왔어요"
        }
    }
}
