package kr.triprecap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.triprecap.app.domain.DrivingView

/** 주행 중 화면 숫자 칸 폭. 칸 폭을 고정해 숫자가 바뀌어도 칸 위치가 움직이지 않는다 */
private val CellWidth = 360.dp
private val CellGap = 120.dp
/** 아래 줄 32/400. PleosType 의 32 크기는 [PleosType.Title32](700)와 [PleosType.Title32Medium](500)뿐이라 굵기만 바꾼다 */
private val FooterStyle = PleosType.Title32Medium.copy(fontWeight = FontWeight.Normal)

/**
 * 주행 중 화면(숫자 칸이 있는 상태와 기록을 끈 상태). [view] 를 그리기만 한다: 시계·상태 수집은 AppRoot 가 한다.
 * 터치 요소와 애니메이션이 없다.
 */
@Composable
fun DrivingScreen(view: DrivingView) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = if (isPortrait(maxWidth)) 32.dp else 56.dp
        val inner = maxWidth - side * 2
        // 2칸(360 + 120 + 360 = 840)이 안 들어가는 좁은 창(841 - 64 = 777)에서는 간격부터 줄이고, 그래도 모자라면 칸을 줄인다
        val gap = (inner - CellWidth * 2).coerceIn(24.dp, CellGap)
        val cell = minOf(CellWidth, (inner - gap) / 2)
        Column(
            Modifier.fillMaxSize().padding(horizontal = side, vertical = 40.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(view.status, style = PleosType.Title48, color = PleosColor.Text, textAlign = TextAlign.Center)
            if (view.stats.isNotEmpty()) {
                Spacer(Modifier.height(72.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (s in view.stats) {
                        StatCell(
                            s.value,
                            s.label,
                            Modifier.width(cell),
                            valueColor = PleosColor.Accent,
                            valueStyle = PleosType.Stat96,
                            labelStyle = PleosType.Title32Medium,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        )
                    }
                }
            }
            view.footer?.let {
                // 숫자 칸이 있으면 96, 없으면(기록을 끈 상태 등) 16
                Spacer(Modifier.height(if (view.stats.isEmpty()) 16.dp else 96.dp))
                Text(it, style = FooterStyle, color = PleosColor.Text72, textAlign = TextAlign.Center)
            }
        }
    }
}
