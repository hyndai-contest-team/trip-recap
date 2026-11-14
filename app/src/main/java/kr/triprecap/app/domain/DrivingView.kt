package kr.triprecap.app.domain

import kr.triprecap.app.LiveStatus

/** 주행 중 화면(기록 중 / 기록 꺼짐)에 보이는 내용 */
data class DrivingView(
    /** 상태 줄: "기록 중 · 3시간 10분". 주행 중에는 출퇴근인지 여행인지 모르므로 "여행"이라고 쓰지 않는다 */
    val status: String,
    /** 숫자 칸 0~2개 */
    val stats: List<Stat>,
    /** 아래 안내 줄. 없으면 null */
    val footer: String?,
) {
    companion object {
        const val RECORDING = "기록 중"
        const val OFF = "여행 기록이 꺼져 있어요"
        const val OFF_FOOTER = "차를 세운 뒤 설정에서 켤 수 있어요"
        const val RETURN_FOOTER = "차를 세우면 보던 화면으로 돌아가요"

        /** 출발 직후에는 0km 칸을 보이지 않는다. 1km 부터 숫자 칸을 띄운다 */
        private const val MIN_DISTANCE_M = 1_000.0

        /**
         * @param recordingEnabled 기록 설정이 켜져 있는지(동의 포함)
         * @param hasPreviousScreen 주행 전에 보던 화면이 있었는지. 있으면 차를 세운 뒤 그 화면으로 돌아간다
         */
        fun of(recordingEnabled: Boolean, live: LiveStatus, now: Long, hasPreviousScreen: Boolean): DrivingView {
            if (!recordingEnabled) return DrivingView(OFF, emptyList(), OFF_FOOTER)
            val footer = if (hasPreviousScreen) RETURN_FOOTER else null
            val started = live.tripStartedAt
            if (!live.recording || started == null) return DrivingView(RECORDING, emptyList(), footer)
            val status = "$RECORDING · ${Texts.durationMs((now - started).coerceAtLeast(0))}"
            val km = Stat("${(live.distanceM / 1000.0).toLong()}km", Texts.LABEL_DISTANCE)
            val stats = when {
                live.visitCount > 0 -> listOf(km, Stat("${live.visitCount}곳", "방문지"))
                live.distanceM >= MIN_DISTANCE_M -> listOf(km)
                else -> emptyList()
            }
            return DrivingView(status, stats, footer)
        }
    }
}
