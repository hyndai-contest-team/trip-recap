package kr.triprecap.app.pleos

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.triprecap.app.ports.UxRestrictionSource
import android.car.drivingstate.CarUxRestrictions as AaosUxRestrictions

/**
 * 주행 중 화면 제한 상태. AAOS `android.car` 만 쓴다.
 *
 * 사용 API (SDK 의 android.car.jar javap 로 확인)
 * - `Car.createCar(context)`, `Car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)`, `Car.disconnect()`
 * - `CarUxRestrictionsManager.getCurrentCarUxRestrictions()`, `registerListener(OnUxRestrictionsChangedListener)`, `unregisterListener()`
 * - `CarUxRestrictions.isRequiresDistractionOptimization()` 값을 `restricted` 에 그대로 넣는다.
 *
 * 권한: 없음 (CarUxRestrictionsManager 조회·구독은 권한을 요구하지 않는다).
 *
 * 미확인
 * - Pleos Connect 에뮬레이터에서 주행 상태(기어 D, 차속 > 0)를 넣었을 때 콜백이 오는지. 2026-11-10 Pleos Connect Emulator v2.1.0 에서는
 *   연결과 첫 값(`uxRestricted=false`) 수신까지 확인했다. 값이 바뀔 때의 콜백은 확인하지 못했다 (docs/verification-2026-11-10.md).
 */
class CarUxRestrictions(context: Context) : UxRestrictionSource {

    private val appContext = context.applicationContext

    private val _restricted = MutableStateFlow(false)
    override val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

    private val lock = Any()
    private var started = false
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var firstLogged = false

    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions ->
        apply(restrictions)
    }

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            val c = runCatching { Car.createCar(appContext) }
                .onFailure { Log.w(TAG, "Car.createCar failed (ux restrictions)", it) }
                .getOrNull() ?: return
            car = c
            val m = runCatching { c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager }
                .onFailure { Log.w(TAG, "CarUxRestrictionsManager unavailable", it) }
                .getOrNull() ?: return
            manager = m
            Log.i(TAG, "CarUxRestrictionsManager connected")
            runCatching { m.registerListener(listener) }
                .onFailure { Log.w(TAG, "ux registerListener failed", it) }
            runCatching { m.currentCarUxRestrictions }
                .onFailure { Log.w(TAG, "getCurrentCarUxRestrictions failed", it) }
                .getOrNull()?.let { apply(it) }
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            manager?.let { m -> runCatching { m.unregisterListener() }.onFailure { Log.w(TAG, "ux unregisterListener failed", it) } }
            manager = null
            car?.let { c -> runCatching { c.disconnect() }.onFailure { Log.w(TAG, "Car.disconnect failed (ux restrictions)", it) } }
            car = null
        }
    }

    private fun apply(restrictions: AaosUxRestrictions?) {
        restrictions ?: return
        val required = runCatching { restrictions.isRequiresDistractionOptimization }
            .onFailure { Log.w(TAG, "isRequiresDistractionOptimization failed", it) }
            .getOrNull() ?: return
        if (!firstLogged) {
            firstLogged = true
            Log.i(TAG, "first value: uxRestricted=$required")
        }
        _restricted.value = required
    }

    companion object {
        private const val TAG = "TripRecapPleos"
    }
}
