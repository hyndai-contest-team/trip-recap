package kr.triprecap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kr.triprecap.app.AppContainer
import kr.triprecap.app.domain.DailyMoveRow
import kr.triprecap.app.domain.Toasts
import kr.triprecap.app.domain.Trip
import kr.triprecap.app.domain.TripLists
import java.time.ZoneId

private val zone: ZoneId = ZoneId.of("Asia/Seoul")

private const val DAILY_NOTE = "여행으로 남기지 않은 이동이에요. 7일이 지나면 자동으로 지워요."

/**
 * 일상 이동 (docs/product-plan.md 2-1 일상 이동, 2-4). 목록 맨 아래 "일상 이동 n건" 줄에서 연다.
 * 확실한 일상과 묻는 카드에 답하지 않은 이동이다. 검색·1년 전 이맘때·공유·다시 가기 대상이 아니므로 행을 눌러 열지 않는다.
 * 행: 날짜·출발 시각(32/700) / "집 → 회사 · 45분 · 13km"(28 Text72) / 오른쪽 [여행으로 남기기](보조 버튼 80).
 * 주 행동(앰버)은 쓰지 않는다: 행마다 같은 버튼이 있어 화면당 1개 규칙을 지킬 수 없다.
 */
@Composable
fun DailyMovesScreen(c: AppContainer, trips: List<Trip>, portrait: Boolean, onBack: () -> Unit) {
    val toast = LocalToast.current
    val places by c.session.places.collectAsStateWithLifecycle()
    val now = remember(trips) { c.clock.now() }
    val daily = remember(trips) { TripLists.daily(trips) }
    val side = if (portrait) 32.dp else 56.dp

    Column(Modifier.fillMaxSize().padding(start = side, end = side, top = 40.dp)) {
        TopBar("일상 이동", onBack = onBack, subtitle = DAILY_NOTE)
        if (daily.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(bottom = 120.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("일상 이동이 없어요", style = PleosType.Title36, color = PleosColor.Text, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text("출퇴근처럼 늘 다니는 이동은 여기에 7일 동안 남아요.", style = PleosType.Body28, color = PleosColor.Text72, textAlign = TextAlign.Center)
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 32.dp, bottom = 48.dp),
        ) {
            items(daily, key = { "d-${it.id}" }) { trip ->
                val row = remember(trip, places, now) { DailyMoveRow.of(trip, places, zone, now) }
                DailyMoveCard(row, onKeep = {
                    c.scope.launch { c.session.keepAsTrip(trip.id) }
                    toast.show(Toasts.KEPT_AS_TRIP)
                })
            }
        }
    }
}

/** 일상 이동 1건 카드(Surface, 반지름 28, 여백 40/28, 높이 168 이상). 카드 자체는 누를 수 없다 */
@Composable
private fun DailyMoveCard(row: DailyMoveRow, onKeep: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 168.dp).listCard(onClick = null),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.title, style = PleosType.Title32, color = PleosColor.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.meta, style = PleosType.Body28, color = PleosColor.Text72, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SecondaryButton("여행으로 남기기", onKeep, height = 80.dp)
    }
}
