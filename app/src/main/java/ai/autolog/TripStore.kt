package ai.autolog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ponytail: 단말 로컬 JSON 파일. 서버 동기화·웹 관리자 붙일 때 교체.
class TripStore(context: Context) {
    private val file = File(context.filesDir, "trips.json")
    private val exportDir = context.getExternalFilesDir(null) ?: context.filesDir

    fun all(): List<Trip> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Trip(
                startMillis = o.getLong("start"),
                endMillis = o.getLong("end"),
                odoStartKm = o.getDouble("odoStart").toFloat(),
                odoEndKm = o.getDouble("odoEnd").toFloat(),
                driver = o.getString("driver"),
                purpose = o.optString("purpose").takeIf { it.isNotEmpty() }?.let(Purpose::valueOf),
                note = o.optString("note"),
                arrived = o.optBoolean("arrived"),
            )
        }
    }

    fun add(trip: Trip) = saveAll(all() + trip)

    /** 용도가 비어 있는 운행 중 가장 오래된 것. 다음 출발·정차 때 묻는다. */
    fun pending(): Trip? = all().filter { it.purpose == null }.minByOrNull { it.startMillis }

    fun lastEnd(): Trip? = all().maxByOrNull { it.endMillis }

    fun setPurpose(startMillis: Long, purpose: Purpose, note: String) =
        saveAll(all().map { if (it.startMillis == startMillis) it.copy(purpose = purpose, note = note) else it })

    fun remove(startMillis: Long) = saveAll(all().filter { it.startMillis != startMillis })

    fun exportCsv(): File = File(exportDir, "운행기록부.csv").apply { writeText(toNtsCsv(all())) }

    private fun saveAll(trips: List<Trip>) {
        val arr = JSONArray()
        trips.forEach { t ->
            arr.put(
                JSONObject()
                    .put("start", t.startMillis).put("end", t.endMillis)
                    .put("odoStart", t.odoStartKm.toDouble()).put("odoEnd", t.odoEndKm.toDouble())
                    .put("driver", t.driver).put("purpose", t.purpose?.name ?: "")
                    .put("note", t.note).put("arrived", t.arrived)
            )
        }
        file.writeText(arr.toString())
    }
}
