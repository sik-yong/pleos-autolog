package ai.autolog

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Purpose(val label: String) { COMMUTE("출퇴근"), BUSINESS("일반업무"), PERSONAL("개인") }

data class Trip(
    val startMillis: Long,
    val endMillis: Long,
    val odoStartKm: Float,
    val odoEndKm: Float,
    val driver: String,
    val purpose: Purpose?,
    val note: String,
    val arrived: Boolean = false, // 내비 목적지 도착으로 끝난 운행
    val dept: String = "", // 사용자 부서 (driver는 성명)
) {
    val distanceKm: Float get() = (odoEndKm - odoStartKm).coerceAtLeast(0f)
}

const val PARK_END_MILLIS = 10 * 60_000L

/** 이 시간 안에 다시 출발하면 이전 운행과 이어 붙일지 묻는다 (주유·급속충전 정도). */
const val MERGE_WINDOW_MILLIS = 30 * 60_000L

/** 이어 붙일지 물어볼 직전 운행. 목적지에 도착해 끝났거나 30분이 지났으면 새 운행으로 본다. */
fun mergeCandidate(last: Trip?, nowMillis: Long): Trip? =
    last?.takeIf { !it.arrived && nowMillis - it.endMillis <= MERGE_WINDOW_MILLIS }

/** 이어 붙인 운행. 시작은 이전 운행, 끝은 새 운행. 용도와 사용자는 이전 운행을 이어받는다. */
fun merge(prev: Trip, next: Trip) = next.copy(
    startMillis = prev.startMillis, odoStartKm = prev.odoStartKm,
    driver = prev.driver, dept = prev.dept,
    purpose = next.purpose ?: prev.purpose,
    note = listOf(prev.note, next.note).filter { it.isNotEmpty() }.joinToString(" → "),
)

enum class ParkDecision { WAIT, ASK, END }

/**
 * P 상태에서 운행을 끝낼지. 하차 신호(벨트·문·도착)는 바로 끝내고, 오래 서 있기만 하면 운전자에게 묻는다.
 * 벨트·문은 "P 진입 후 바뀐 경우"만 넘긴다 (벨트를 안 맨 채 P에 둔 운전자가 끊기지 않도록).
 */
fun parkDecision(parkedForMillis: Long, beltReleased: Boolean, doorOpened: Boolean, arrived: Boolean) = when {
    beltReleased || doorOpened || arrived -> ParkDecision.END
    parkedForMillis >= PARK_END_MILLIS -> ParkDecision.ASK
    else -> ParkDecision.WAIT
}

// "운행을 마칠까요?" 답변. 판단 못 하면 null (화면에서 고르게 함)
fun isYes(text: String): Boolean? {
    val t = text.replace(" ", "")
    return when {
        listOf("아니", "계속", "아직", "안끝").any { it in t } -> false
        listOf("네", "예", "응", "어", "맞아", "종료", "마쳐", "끝").any { it in t } -> true
        else -> null
    }
}

/** 앱이 못 본 주행 거리 (전원 차단 등). 0.5km 미만은 계기판 오차로 본다. */
fun missedDistanceKm(lastEndKm: Float?, nowKm: Float): Float? =
    lastEndKm?.let { nowKm - it }?.takeIf { it >= 0.5f }

// 운전자 음성 답변 → 운행 용도. 판단 못 하면 null (화면에서 다시 고르게 함)
fun classifyPurpose(text: String): Purpose? {
    val t = text.replace(" ", "")
    return when {
        "출퇴근" in t || "출근" in t || "퇴근" in t -> Purpose.COMMUTE
        listOf("업무", "미팅", "회의", "거래처", "출장", "납품", "영업", "고객").any { it in t } -> Purpose.BUSINESS
        listOf("개인", "사적", "아니").any { it in t } -> Purpose.PERSONAL
        else -> null
    }
}

/** 한 달 운행 요약. 업무용 거리는 국세청 기록부처럼 출퇴근용과 일반업무용을 합친다. */
data class MonthSummary(val trips: Int, val totalKm: Float, val businessKm: Float, val unclassified: Int) {
    /** 업무사용비율 = 업무용 거리 / 총 주행거리. 달린 거리가 없으면 null. 미분류 운행은 분모에만 들어간다. */
    val businessRatio: Float? get() = if (totalKm > 0f) businessKm / totalKm else null
}

fun monthSummary(trips: List<Trip>, nowMillis: Long, zone: ZoneId = ZoneId.of("Asia/Seoul")): MonthSummary {
    val month = YearMonth.from(Instant.ofEpochMilli(nowMillis).atZone(zone))
    val inMonth = trips.filter { YearMonth.from(Instant.ofEpochMilli(it.startMillis).atZone(zone)) == month }
    val km = { list: List<Trip> -> list.sumOf { it.distanceKm.toDouble() }.toFloat() }
    return MonthSummary(
        trips = inMonth.size,
        totalKm = km(inMonth),
        businessKm = km(inMonth.filter { it.purpose == Purpose.COMMUTE || it.purpose == Purpose.BUSINESS }),
        unclassified = inMonth.count { it.purpose == null },
    )
}

/**
 * 국세청 업무용승용차 운행기록부 서식(머리말: 상호·사업자등록번호·차종·자동차등록번호, 표: 사용일자·사용자(부서·성명)·운행내역·비고).
 * UTF-8 BOM은 엑셀 한글 깨짐 방지용.
 */
fun toNtsCsv(trips: List<Trip>, profile: Profile = Profile(), zone: ZoneId = ZoneId.of("Asia/Seoul")): String {
    val head = listOf(
        "업무용승용차 운행기록부",
        listOf("상호(법인명)", profile.company, "사업자등록번호", profile.bizNo).joinToString(",") { csvCell(it) },
        listOf("차종", profile.carModel, "자동차등록번호", profile.plate).joinToString(",") { csvCell(it) },
        "",
    )
    val header = "사용일자,부서,성명,주행 전 계기판 거리(km),주행 후 계기판 거리(km),주행거리(km),출퇴근용(km),일반업무용(km),비고"
    val sorted = trips.sortedBy { it.startMillis }
    val rows = sorted.groupBy { YearMonth.from(Instant.ofEpochMilli(it.startMillis).atZone(zone)) }.flatMap { (month, list) ->
        list.map { row(it, zone) } + subtotalRow("${month.year}년 ${month.monthValue}월 소계", list)
    }
    val totals = if (sorted.isEmpty()) emptyList() else {
        val s = TenthsSummary.of(sorted)
        listOf(
            "",
            subtotalRow("기간 합계", sorted),
            listOf("총주행거리(km)", tenths(s.total)).joinToString(","),
            listOf("업무용 사용거리(km)", tenths(s.commute + s.business)).joinToString(","),
            listOf("업무사용비율(%)", s.ratioPercent() ?: "").joinToString(","),
        )
    }
    return "\uFEFF" + (head + header + rows + totals).joinToString("\r\n") + "\r\n"
}

/** 거리는 표에 적힌 값(0.1km 단위)을 그대로 더해 소계가 행 합과 어긋나지 않게 한다. */
private fun tenthsOf(v: Float) = Math.round(v * 10.0)
private fun tenths(t: Long) = String.format(Locale.US, "%.1f", t / 10.0)

private class TenthsSummary(val total: Long, val commute: Long, val business: Long, val unclassified: Int) {
    /** 업무사용비율 = (출퇴근 + 일반업무) / 총주행거리. 미분류는 분모에만 들어간다. */
    fun ratioPercent(): String? = if (total > 0) String.format(Locale.US, "%.1f", (commute + business) * 100.0 / total) else null

    companion object {
        fun of(trips: List<Trip>) = TenthsSummary(
            total = trips.sumOf { tenthsOf(it.distanceKm) },
            commute = trips.filter { it.purpose == Purpose.COMMUTE }.sumOf { tenthsOf(it.distanceKm) },
            business = trips.filter { it.purpose == Purpose.BUSINESS }.sumOf { tenthsOf(it.distanceKm) },
            unclassified = trips.count { it.purpose == null },
        )
    }
}

private fun subtotalRow(label: String, trips: List<Trip>): String {
    val s = TenthsSummary.of(trips)
    val note = listOfNotNull(
        s.ratioPercent()?.let { "업무사용비율 $it%" },
        "미분류 ${s.unclassified}건".takeIf { s.unclassified > 0 },
    ).joinToString(" · ")
    return listOf(label, "", "", "", "", tenths(s.total), tenths(s.commute), tenths(s.business), note).joinToString(",") { csvCell(it) }
}

private fun row(t: Trip, zone: ZoneId): String {
    val day = DateTimeFormatter.ofPattern("yyyy-MM-dd(E)", Locale.KOREAN).withZone(zone)
    val km = { v: Float -> tenths(tenthsOf(v)) }
    return listOf(
            day.format(Instant.ofEpochMilli(t.startMillis)),
            t.dept,
            t.driver,
            km(t.odoStartKm),
            km(t.odoEndKm),
            km(t.distanceKm),
            if (t.purpose == Purpose.COMMUTE) km(t.distanceKm) else "",
            if (t.purpose == Purpose.BUSINESS) km(t.distanceKm) else "",
            t.note,
        ).joinToString(",") { csvCell(it) }
}

private fun csvCell(s: String) =
    if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
