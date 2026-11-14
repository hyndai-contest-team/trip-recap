package kr.triprecap.app.service

import kr.triprecap.app.LiveStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingNoticeTest {
    @Test
    fun `notification text follows the recording state`() {
        assertEquals("여행을 기록하고 있어요", RecordingNotice.text(recordingOn = true, live = LiveStatus(recording = true)))
        assertEquals("차가 출발하면 여행을 기록해요", RecordingNotice.text(recordingOn = true, live = LiveStatus(recording = false)))
        assertEquals("여행 기록이 꺼져 있어요", RecordingNotice.text(recordingOn = false, live = LiveStatus(recording = false)))
    }

    @Test
    fun `recording off wins over a stale live status`() {
        assertEquals("여행 기록이 꺼져 있어요", RecordingNotice.text(recordingOn = false, live = LiveStatus(recording = true)))
    }
}
