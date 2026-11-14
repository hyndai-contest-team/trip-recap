package kr.triprecap.app.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToastStateTest {

    private fun TestScope.after(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `처음에는 알림이 없다`() = runTest {
        val toast = ToastState(backgroundScope)
        assertNull(toast.message.value)
    }

    @Test
    fun `보인 알림은 3초 동안 남고 3초가 되면 사라진다`() = runTest {
        val toast = ToastState(backgroundScope)
        toast.show("내비에 안목해변 카페 안내를 보냈어요")
        runCurrent()
        assertEquals("내비에 안목해변 카페 안내를 보냈어요", toast.message.value)

        after(2_999)
        assertEquals("내비에 안목해변 카페 안내를 보냈어요", toast.message.value)

        after(1)
        assertNull(toast.message.value)
    }

    @Test
    fun `새 알림이 오면 바로 바꾸고 3초를 다시 센다`() = runTest {
        val toast = ToastState(backgroundScope)
        toast.show("첫 알림")
        after(2_000)

        toast.show("둘째 알림")
        runCurrent()
        assertEquals("둘째 알림", toast.message.value)

        // 첫 알림의 3초(3_000)가 지나도 둘째 알림은 남아 있다
        after(2_999)
        assertEquals("둘째 알림", toast.message.value)

        after(1)
        assertNull(toast.message.value)
    }

    @Test
    fun `clear 는 보통 알림도 바로 지우고 뒤에 오는 알림은 다시 3초를 센다`() = runTest {
        val toast = ToastState(backgroundScope)
        toast.show("첫 알림")
        after(1_000)
        toast.clear()
        assertNull(toast.message.value)

        after(1_500)
        toast.show("둘째 알림")
        // 첫 알림의 3초 시점(3_000)이 지나도 둘째 알림은 남아 있다
        after(2_999)
        assertEquals("둘째 알림", toast.message.value)
        after(1)
        assertNull(toast.message.value)
    }
}
