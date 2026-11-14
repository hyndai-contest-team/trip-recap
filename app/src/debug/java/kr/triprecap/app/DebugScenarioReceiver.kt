package kr.triprecap.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import kr.triprecap.app.scenario.DemoSeeder

/**
 * 시연·개발 조작 (debug 빌드 전용).
 *   adb shell am broadcast --user current -a kr.triprecap.app.DEBUG_SCENARIO \
 *     -n kr.triprecap.app/.DebugScenarioReceiver --es cmd next
 *   cmd: consent | mode(--es mode scenario|vehicle) | load(--ei index 0..8) | play | pause | speed(--ed speed 60)
 *        | next | chapter(--ei index N) | seed | arrive | ignition | restrict(--es value auto|on|off) | dismiss | clear
 *   ignition: 시동 ON 을 보낸다. 집이 아닌 곳에 6시간 서 있어서 닫힌 이동(endedAway)의 도착 카드는 닫힐 때 뜨지 않고
 *             다음 시동 때 뜬다. 시나리오 모드는 재생기의 시동 ON(ScenarioPlayer.simulateIgnitionOn), 차량 모드는 세션에 바로 넣는다.
 *             차가 서 있을 때만 카드가 뜬다
 *   dismiss: 도착 카드 닫기. 묻는 카드면 답하지 않은 것(일상 이동)으로 본다
 */
class DebugScenarioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TripRecapApplication
        val c = app.container
        val cmd = intent.getStringExtra("cmd")
        android.util.Log.i("TripRecapApp", "debug cmd $cmd")
        when (cmd) {
            "consent" -> { c.settings.setConsentGiven(true); app.startRecordingService() }
            "mode" -> c.setScenarioMode(intent.getStringExtra("mode") != "vehicle")
            "load" -> c.loadScenario(intent.getIntExtra("index", 0))
            "play" -> c.player.resume()
            "pause" -> c.player.pause()
            "speed" -> c.player.setSpeed(intent.getDoubleExtra("speed", 60.0))
            "next" -> c.player.nextChapter()
            "chapter" -> c.player.jumpToChapter(intent.getIntExtra("index", 0))
            "seed" -> c.scope.launch { DemoSeeder.seed(c.store, System.currentTimeMillis()) }
            "arrive" -> c.player.simulateArrival()
            "ignition" -> c.simulateIgnitionOn()
            "restrict" -> c.restrictionOverride.value = when (intent.getStringExtra("value")) { "on" -> true; "off" -> false; else -> null }
            "dismiss" -> c.scope.launch { c.session.dismissArrival() }
            "clear" -> c.scope.launch { c.session.onAllDeleted() }
        }
        android.util.Log.i("TripRecapApp", "debug cmd $cmd done")
    }
}
