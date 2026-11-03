package ai.autolog

import java.time.Instant
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
) {
    val distanceKm: Float get() = (odoEndKm - odoStartKm).coerceAtLeast(0f)
}

const val PARK_END_MILLIS = 10 * 60_000L

/** 이 시간 안에 다시 출발하면 이전 운행과 이어 붙일지 묻는다 (주유·급속충전 정도). */
const val MERGE_WINDOW_MILLIS = 30 * 60_000L

/** 이어 붙일지 물어볼 직전 운행. 목적지에 도착해 끝났거나 30분이 지났으면 새 운행으로 본다. */
fun mergeCandidate(last: Trip?, nowMillis: Long): Trip? =
    last?.takeIf { !it.arrived && nowMillis - it.endMillis <= MERGE_WINDOW_MILLIS }

/** 이어 붙인 운행. 시작은 이전 운행, 끝은 새 운행. 용도는 이전 답을 이어받는다. */
fun merge(prev: Trip, next: Trip) = next.copy(
    startMillis = prev.startMillis, odoStartKm = prev.odoStartKm,
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

// 국세청 업무용승용차 운행기록부 열 순서. UTF-8 BOM은 엑셀 한글 깨짐 방지용.
fun toNtsCsv(trips: List<Trip>, zone: ZoneId = ZoneId.of("Asia/Seoul")): String {
    val day = DateTimeFormatter.ofPattern("yyyy-MM-dd(E)", Locale.KOREAN).withZone(zone)
    val km = { v: Float -> String.format(Locale.US, "%.1f", v) }
    val header = "사용일자,사용자,주행 전 계기판 거리(km),주행 후 계기판 거리(km),주행거리(km),출퇴근용(km),일반업무용(km),비고"
    val rows = trips.sortedBy { it.startMillis }.map { t ->
        listOf(
            day.format(Instant.ofEpochMilli(t.startMillis)),
            t.driver,
            km(t.odoStartKm),
            km(t.odoEndKm),
            km(t.distanceKm),
            if (t.purpose == Purpose.COMMUTE) km(t.distanceKm) else "",
            if (t.purpose == Purpose.BUSINESS) km(t.distanceKm) else "",
            t.note,
        ).joinToString(",") { csvCell(it) }
    }
    return "﻿" + (listOf(header) + rows).joinToString("\r\n") + "\r\n"
}

private fun csvCell(s: String) =
    if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
