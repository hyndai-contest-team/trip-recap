package kr.triprecap.app.domain

/** 사용자가 한 일의 결과를 알리는 짧은 문구(토스트) */
object Toasts {
    const val TITLE_CHANGED = "제목을 바꿨어요"
    const val NAME_CHANGED = "이름을 바꿨어요"
    const val MEMO_SAVED = "메모를 저장했어요"
    const val MEMO_DELETED = "메모를 지웠어요"
    const val TRIP_DELETED = "여행을 지웠어요"
    const val ALL_TRIPS_DELETED = "여행을 모두 지웠어요"
    const val SHARE_EXCLUDED = "QR로 보낼 때 이곳을 빼요"
    const val SHARE_INCLUDED = "QR로 보낼 때 이곳을 넣어요"
    const val NAVI_FAILED = "내비에 안내를 보내지 못했어요. 잠시 뒤 다시 눌러 주세요."
    const val MEMO_NOT_HEARD = "메모를 듣지 못했어요. 다시 눌러 말해 주세요."
    const val RECORDING_ON = "이제 여행을 기록해요"
    const val RECORDING_OFF = "기록을 껐어요. 저장된 여행은 그대로 있어요"
    const val DEMO_SEEDED = "시연 여행을 넣었어요"
    /** 일상 이동 화면 [여행으로 남기기], 묻는 도착 카드 [남기기] */
    const val KEPT_AS_TRIP = "여행으로 남겼어요"
    /** 묻는 도착 카드 [지우기] */
    const val MOVE_DELETED = "이동을 지웠어요"
    const val PLACE_UNKNOWN = "지금 위치를 아직 몰라요. 잠시 뒤 다시 눌러 주세요."
    const val PLACE_RESET = "추정한 위치로 되돌렸어요"
    const val IGNITION_SENT = "시동 켜기를 보냈어요"

    /** "집 위치를 정했어요" / "회사 위치를 정했어요" */
    fun placeSet(slot: PlaceSlot): String = when (slot) {
        PlaceSlot.HOME -> "집 위치를 정했어요"
        PlaceSlot.OFFICE -> "회사 위치를 정했어요"
    }

    /** "강릉중앙시장을 지웠어요" */
    fun stopDeleted(name: String): String = "${Josa.eulReul(name)} 지웠어요"

    /** "내비에 경포해변 안내를 보냈어요" */
    fun naviSent(name: String): String = "내비에 $name 안내를 보냈어요"
}
