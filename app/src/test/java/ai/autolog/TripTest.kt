package ai.autolog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class TripTest {
    @Test fun classifiesSpokenAnswers() {
        assertEquals(Purpose.BUSINESS, classifyPurpose("업무, 거래처 미팅"))
        assertEquals(Purpose.COMMUTE, classifyPurpose("퇴근길이야"))
        assertEquals(Purpose.PERSONAL, classifyPurpose("아니 개인 용무"))
        assertNull(classifyPurpose("음..."))
    }

    @Test fun endsOnLeavingSignalsAndAsksAfterTimeout() {
        val min = 60_000L
        assertEquals(ParkDecision.WAIT, parkDecision(2 * min, beltReleased = false, doorOpened = false, arrived = false)) // 잠깐 정차
        assertEquals(ParkDecision.ASK, parkDecision(10 * min, false, false, false)) // 오래 정차 → 운전자에게 확인
        assertEquals(ParkDecision.END, parkDecision(0, beltReleased = true, doorOpened = false, arrived = false))
        assertEquals(ParkDecision.END, parkDecision(0, false, doorOpened = true, arrived = false))
        assertEquals(ParkDecision.END, parkDecision(0, false, false, arrived = true))
    }

    @Test fun understandsEndConfirmation() {
        assertEquals(true, isYes("네 종료해"))
        assertEquals(false, isYes("아니 계속 갈 거야"))
        assertNull(isYes("음..."))
    }

    @Test fun offersMergeOnlyForShortStops() {
        val min = 60_000L
        val prev = Trip(0, 100 * min, 100f, 120f, "a", Purpose.BUSINESS, "주유소")
        assertEquals(prev, mergeCandidate(prev, 120 * min)) // 20분 뒤 출발
        assertNull(mergeCandidate(prev, 131 * min)) // 30분 초과
        assertNull(mergeCandidate(prev.copy(arrived = true), 105 * min)) // 목적지 도착 후
        val merged = merge(prev, Trip(110 * min, 150 * min, 120f, 150f, "a", null, "판교"))
        assertEquals(0L, merged.startMillis)
        assertEquals(50f, merged.distanceKm)
        assertEquals(Purpose.BUSINESS, merged.purpose)
        assertEquals("주유소 → 판교", merged.note)
        val byOther = merge(prev.copy(dept = "영업팀"), Trip(110 * min, 150 * min, 120f, 150f, "b", null, ""))
        assertEquals("a", byOther.driver) // 이어 붙이면 먼저 시작한 운행의 사용자
        assertEquals("영업팀", byOther.dept)
    }

    @Test fun detectsMissedDistance() {
        assertNull(missedDistanceKm(null, 100f))
        assertNull(missedDistanceKm(100f, 100.3f))
        assertEquals(12f, missedDistanceKm(100f, 112f))
    }

    @Test fun exportsNtsColumns() {
        val trip = Trip(
            startMillis = 1_793_577_600_000, // 2026-11-02 09:00 KST
            endMillis = 1_793_580_000_000,
            odoStartKm = 12_000f, odoEndKm = 12_034.5f,
            driver = "홍길동", purpose = Purpose.BUSINESS, note = "판교, 거래처",
        )
        val profile = Profile(company = "오토로그(주)", bizNo = "123-45-67890", carModel = "쏘나타", plate = "12가3456")
        val csv = toNtsCsv(listOf(trip.copy(dept = "영업팀")), profile, ZoneId.of("Asia/Seoul"))
        assertEquals('\uFEFF', csv.first()) // 엑셀 한글 깨짐 방지용 BOM
        val lines = csv.removePrefix("\uFEFF").trim().lines()
        assertEquals("상호(법인명),오토로그(주),사업자등록번호,123-45-67890", lines[1])
        assertEquals("차종,쏘나타,자동차등록번호,12가3456", lines[2])
        assertEquals("사용일자,부서,성명,주행 전 계기판 거리(km),주행 후 계기판 거리(km),주행거리(km),출퇴근용(km),일반업무용(km),비고", lines[4])
        assertEquals("2026-11-02(월),영업팀,홍길동,12000.0,12034.5,34.5,,34.5,\"판교, 거래처\"", lines[5])
    }

    @Test fun exportsMonthlySubtotalsAndPeriodTotals() {
        val zone = ZoneId.of("Asia/Seoul")
        fun at(d: String) = java.time.LocalDateTime.parse(d).atZone(zone).toInstant().toEpochMilli()
        var odo = 1000f
        fun trip(d: String, km: Float, p: Purpose?) = Trip(at(d), at(d) + 60_000, odo, (odo + km).also { odo = it }, "홍길동", p, "")
        val trips = listOf(
            trip("2026-10-30T09:00", 10.0f, Purpose.BUSINESS),
            trip("2026-10-31T18:00", 5.0f, Purpose.PERSONAL),
            trip("2026-11-02T08:00", 20.0f, Purpose.COMMUTE),
            trip("2026-11-03T10:00", 30.0f, Purpose.BUSINESS),
            trip("2026-11-04T10:00", 50.0f, null),
        )
        val lines = toNtsCsv(trips.reversed(), Profile(), zone).removePrefix("\uFEFF").trim().lines()
        assertEquals("2026년 10월 소계,,,,,15.0,0.0,10.0,업무사용비율 66.7%", lines[7])
        assertEquals("2026년 11월 소계,,,,,100.0,20.0,30.0,업무사용비율 50.0% · 미분류 1건", lines[11])
        assertEquals("기간 합계,,,,,115.0,20.0,40.0,업무사용비율 52.2% · 미분류 1건", lines[13])
        assertEquals("총주행거리(km),115.0", lines[14])
        assertEquals("업무용 사용거리(km),60.0", lines[15])
        assertEquals("업무사용비율(%),52.2", lines[16])
        // 운행이 없으면 합계 행도 없다
        assertEquals(5, toNtsCsv(emptyList(), Profile(), zone).removePrefix("\uFEFF").trim().lines().size)
    }

    @Test fun summarizesCurrentMonth() {
        val zone = ZoneId.of("Asia/Seoul")
        fun at(d: String) = java.time.LocalDateTime.parse(d).atZone(zone).toInstant().toEpochMilli()
        fun trip(d: String, km: Float, p: Purpose?) = Trip(at(d), at(d) + 60_000, 100f, 100f + km, "운전자", p, "")
        val trips = listOf(
            trip("2026-10-31T23:50", 50f, Purpose.BUSINESS), // 지난달: 제외
            trip("2026-11-01T00:10", 10f, Purpose.BUSINESS),
            trip("2026-11-02T08:00", 20f, Purpose.COMMUTE),
            trip("2026-11-03T19:00", 30f, Purpose.PERSONAL),
            trip("2026-11-04T09:00", 40f, null),
        )
        val s = monthSummary(trips, at("2026-11-08T12:00"), zone)
        assertEquals(4, s.trips)
        assertEquals(100f, s.totalKm, 0.001f)
        assertEquals(30f, s.businessKm, 0.001f) // 출퇴근 + 일반업무
        assertEquals(1, s.unclassified)
        assertEquals(0.3f, s.businessRatio!!, 0.001f)
        assertNull(monthSummary(emptyList(), at("2026-11-08T12:00"), zone).businessRatio)
    }
}
