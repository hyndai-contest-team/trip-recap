package kr.triprecap.app.domain

import kr.triprecap.app.domain.TripRecorder.Event
import kr.triprecap.app.domain.TripRecorder.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecorderTest {

    private val t0 = 1_700_000_000_000L
    private fun min(m: Double) = t0 + (m * 60_000).toLong()
    private fun min(m: Int) = min(m.toDouble())
    /** 북쪽으로 m 미터 옮긴 점 (위도 0.001도 = 111.2m) */
    private fun north(m: Double) = GeoPoint(37.0 + m / 111_195.0, 127.0)

    private val out = mutableListOf<Output>()
    private val r = TripRecorder()
    private fun ev(e: Event, at: Long) { out += r.onEvent(e, at) }
    private fun drives() = out.filterIsInstance<Output.DriveEnded>().map { it.drive }
    private fun stays() = out.filterIsInstance<Output.StayEnded>().map { it.stay }

    private fun startDriving(at: Long = min(1)) {
        ev(Event.GearChanged(Gear.PARK), t0)
        ev(Event.GearChanged(Gear.DRIVE), at)
    }

    @Test
    fun `drive starts when gear leaves park`() {
        ev(Event.GearChanged(Gear.PARK), t0)
        assertFalse(r.isDriving)
        ev(Event.GearChanged(Gear.REVERSE), min(1))
        assertTrue(r.isDriving)
        assertEquals(min(1), r.driveStartedAt)
    }

    @Test
    fun `drive ends after park is held 3 minutes and its end time is when park began`() {
        startDriving()
        ev(Event.GearChanged(Gear.PARK), min(30))
        out += r.tick(min(32))
        assertTrue(drives().isEmpty())
        out += r.tick(min(33))
        assertEquals(1, drives().size)
        assertEquals(min(1), drives()[0].startedAt)
        assertEquals(min(30), drives()[0].endedAt)
        assertFalse(r.isDriving)
    }

    @Test
    fun `park shorter than 3 minutes does not end the drive`() {
        startDriving()
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.GearChanged(Gear.DRIVE), min(32))
        ev(Event.GearChanged(Gear.PARK), min(50))
        out += r.tick(min(53))
        assertEquals(1, drives().size)
        assertEquals(min(50), drives()[0].endedAt)
        assertTrue(stays().isEmpty())
    }

    @Test
    fun `ignition off ends the drive at once`() {
        startDriving()
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.IgnitionOff, min(30.5))
        assertEquals(1, drives().size)
        assertEquals(min(30), drives()[0].endedAt)
    }

    @Test
    fun `departing after a long park ends the drive even without a tick and emits the stay`() {
        startDriving()
        ev(Event.LocationChanged(LocationSample(north(0.0), "경포해변")), min(29))
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.DoorChanged(true), min(31))
        ev(Event.DoorChanged(false), min(32))
        ev(Event.GearChanged(Gear.DRIVE), min(45))
        assertEquals(1, drives().size)
        val stay = stays().single()
        assertEquals(min(30), stay.arrivedAt)
        assertEquals(min(45), stay.departedAt)
        assertTrue(stay.doorOpened)
        assertEquals("경포해변", stay.placeName)
        assertEquals(north(0.0), stay.point)
        assertTrue(r.isDriving)
        assertEquals(min(45), r.driveStartedAt)
    }

    @Test
    fun `stay without door open reports doorOpened false`() {
        startDriving()
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.GearChanged(Gear.DRIVE), min(45))
        assertFalse(stays().single().doorOpened)
    }

    @Test
    fun `door opened during a short park is not carried into a later stay`() {
        startDriving()
        ev(Event.GearChanged(Gear.PARK), min(10))
        ev(Event.DoorChanged(true), min(11))
        ev(Event.DoorChanged(false), min(11.5))
        ev(Event.GearChanged(Gear.DRIVE), min(12))
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.GearChanged(Gear.DRIVE), min(45))
        assertFalse(stays().single().doorOpened)
    }

    @Test
    fun `wiper during a drive marks the drive and the following stay rainy only`() {
        startDriving()
        ev(Event.WiperChanged(true), min(10))
        ev(Event.WiperChanged(false), min(20))
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.GearChanged(Gear.DRIVE), min(45))
        ev(Event.GearChanged(Gear.PARK), min(60))
        ev(Event.GearChanged(Gear.DRIVE), min(75))
        assertEquals(listOf(true, false), drives().map { it.rainy })
        assertEquals(listOf(true, false), stays().map { it.rainy })
    }

    @Test
    fun `wiper left on from the previous drive marks the next drive rainy`() {
        startDriving()
        ev(Event.WiperChanged(true), min(10))
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.GearChanged(Gear.DRIVE), min(45))
        ev(Event.GearChanged(Gear.PARK), min(60))
        out += r.tick(min(63))
        assertEquals(listOf(true, true), drives().map { it.rainy })
    }

    // 외기온

    @Test
    fun `stay and the drive before it keep the outside temperature known when parking, rounded`() {
        startDriving()
        ev(Event.OutsideTempChanged(9.0), min(5))
        ev(Event.OutsideTempChanged(11.6), min(25))
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.OutsideTempChanged(15.0), min(40))
        ev(Event.GearChanged(Gear.DRIVE), min(45))
        assertEquals(12, drives().single().arrivalTempC)
        assertEquals(12, stays().single().outsideTempC)
    }

    @Test
    fun `below zero temperatures round to the nearest degree`() {
        startDriving()
        ev(Event.OutsideTempChanged(-2.6), min(25))
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.GearChanged(Gear.DRIVE), min(45))
        assertEquals(-3, stays().single().outsideTempC)
    }

    @Test
    fun `when nothing is known at parking the first temperature during the stay is used`() {
        startDriving()
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.OutsideTempChanged(8.2), min(31))
        ev(Event.OutsideTempChanged(10.0), min(40))
        ev(Event.GearChanged(Gear.DRIVE), min(45))
        assertEquals(8, drives().single().arrivalTempC)
        assertEquals(8, stays().single().outsideTempC)
    }

    @Test
    fun `first temperature after the drive has ended still goes to the stay`() {
        startDriving()
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.OutsideTempChanged(8.2), min(34))
        assertEquals(8, r.currentStay(min(35))!!.outsideTempC)
        ev(Event.OutsideTempChanged(10.0), min(40))
        ev(Event.GearChanged(Gear.DRIVE), min(45))
        assertEquals(null, drives().single().arrivalTempC)
        assertEquals(8, stays().single().outsideTempC)
    }

    @Test
    fun `temperature from an earlier stop does not carry into the next stay that got none`() {
        startDriving()
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.OutsideTempChanged(8.2), min(34))
        ev(Event.GearChanged(Gear.DRIVE), min(45))
        ev(Event.GearChanged(Gear.PARK), min(60))
        ev(Event.GearChanged(Gear.DRIVE), min(75))
        // 두 번째 정차 도착 때 알던 가장 최근 값은 첫 정차에서 받은 8도다
        assertEquals(listOf(8, 8), stays().map { it.outsideTempC })
    }

    @Test
    fun `without any temperature signal the stay has none`() {
        startDriving()
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.GearChanged(Gear.DRIVE), min(45))
        assertNull(drives().single().arrivalTempC)
        assertNull(stays().single().outsideTempC)
    }

    @Test
    fun `the first temperature received before the first departure is kept`() {
        ev(Event.GearChanged(Gear.PARK), t0)
        assertNull(r.tempBeforeFirstDrive)
        ev(Event.OutsideTempChanged(4.4), min(1))
        ev(Event.OutsideTempChanged(6.0), min(2))
        ev(Event.GearChanged(Gear.DRIVE), min(3))
        ev(Event.OutsideTempChanged(7.0), min(4))
        assertEquals(4, r.tempBeforeFirstDrive)
    }

    @Test
    fun `track keeps points at least 20m apart and sums distance`() {
        startDriving()
        ev(Event.LocationChanged(LocationSample(north(0.0))), min(2))
        ev(Event.LocationChanged(LocationSample(north(10.0))), min(2.1))
        ev(Event.LocationChanged(LocationSample(north(25.0))), min(2.2))
        ev(Event.LocationChanged(LocationSample(north(30.0))), min(2.3))
        ev(Event.LocationChanged(LocationSample(north(50.0))), min(2.4))
        ev(Event.GearChanged(Gear.PARK), min(3))
        out += r.tick(min(6))
        val d = drives().single()
        assertEquals(listOf(north(0.0), north(25.0), north(50.0)), d.track.map { it.point })
        assertEquals(50.0, d.distanceM, 0.5)
        assertEquals(50.0, r.totalDistanceM, 0.5)
    }

    @Test
    fun `location known before departure becomes the first track point`() {
        ev(Event.LocationChanged(LocationSample(north(0.0))), t0)
        startDriving()
        ev(Event.GearChanged(Gear.PARK), min(3))
        out += r.tick(min(6))
        assertEquals(north(0.0), drives().single().startPoint)
    }

    @Test
    fun `locations while stopped are not added to the track`() {
        startDriving()
        ev(Event.LocationChanged(LocationSample(north(0.0))), min(2))
        ev(Event.GearChanged(Gear.PARK), min(3))
        out += r.tick(min(6))
        ev(Event.LocationChanged(LocationSample(north(100.0))), min(7))
        ev(Event.GearChanged(Gear.DRIVE), min(20))
        assertEquals(1, drives()[0].track.size)
        // 정차 위치는 P 단이 된 시점의 위치다
        assertEquals(north(0.0), stays().single().point)
    }

    @Test
    fun `location arriving after park is used when none was known`() {
        startDriving()
        ev(Event.GearChanged(Gear.PARK), min(30))
        ev(Event.LocationChanged(LocationSample(north(0.0), "경포해변")), min(31))
        ev(Event.GearChanged(Gear.DRIVE), min(45))
        assertEquals("경포해변", stays().single().placeName)
    }

    @Test
    fun `seats seen during the drive are recorded`() {
        ev(Event.SeatsChanged(setOf(SeatId.DRIVER, SeatId.PASSENGER)), t0)
        startDriving()
        ev(Event.SeatsChanged(setOf(SeatId.DRIVER, SeatId.PASSENGER, SeatId.REAR_LEFT)), min(5))
        ev(Event.SeatsChanged(setOf(SeatId.DRIVER)), min(6))
        ev(Event.GearChanged(Gear.PARK), min(30))
        out += r.tick(min(33))
        assertEquals(setOf(SeatId.DRIVER, SeatId.PASSENGER, SeatId.REAR_LEFT), drives().single().seats)
    }

    @Test
    fun `currentStay describes the ongoing park`() {
        startDriving()
        ev(Event.GearChanged(Gear.PARK), min(30))
        assertNull(r.currentStay(min(31)))   // 아직 드라이브가 끝나지 않았다
        ev(Event.DoorChanged(true), min(31))
        out += r.tick(min(40))
        val s = r.currentStay(min(40))!!
        assertEquals(min(30), s.arrivedAt)
        assertEquals(min(40), s.departedAt)
        assertTrue(s.doorOpened)
    }

    // 기어 신호가 없을 때: 시속 5km 10초로 시작, 정지 3분으로 종료

    @Test
    fun `without gear signal a drive starts after 10 seconds above 5 kmh`() {
        ev(Event.SpeedChanged(12.0), t0)
        assertFalse(r.isDriving)
        ev(Event.SpeedChanged(20.0), t0 + 9_000)
        assertFalse(r.isDriving)
        ev(Event.SpeedChanged(22.0), t0 + 10_000)
        assertTrue(r.isDriving)
        assertEquals(t0, r.driveStartedAt)
    }

    @Test
    fun `without gear signal a dip below 5 kmh restarts the 10 second count`() {
        ev(Event.SpeedChanged(12.0), t0)
        ev(Event.SpeedChanged(3.0), t0 + 5_000)
        ev(Event.SpeedChanged(12.0), t0 + 8_000)
        ev(Event.SpeedChanged(12.0), t0 + 12_000)
        assertFalse(r.isDriving)
        ev(Event.SpeedChanged(12.0), t0 + 18_000)
        assertTrue(r.isDriving)
    }

    @Test
    fun `without gear signal a drive ends after 3 minutes standing still`() {
        ev(Event.SpeedChanged(30.0), t0)
        ev(Event.SpeedChanged(30.0), t0 + 10_000)
        ev(Event.SpeedChanged(0.0), min(20))
        out += r.tick(min(22))
        assertTrue(drives().isEmpty())
        out += r.tick(min(23))
        assertEquals(min(20), drives().single().endedAt)
    }

    @Test
    fun `speed is ignored once a gear signal has been seen`() {
        ev(Event.GearChanged(Gear.PARK), t0)
        ev(Event.SpeedChanged(30.0), t0 + 1_000)
        ev(Event.SpeedChanged(30.0), t0 + 20_000)
        assertFalse(r.isDriving)
    }

    @Test
    fun `an unknown gear value does not count as a gear signal`() {
        ev(Event.GearChanged(Gear.UNKNOWN), t0)
        ev(Event.SpeedChanged(30.0), t0 + 1_000)
        ev(Event.SpeedChanged(30.0), t0 + 11_000)
        assertTrue(r.isDriving)
    }
}
