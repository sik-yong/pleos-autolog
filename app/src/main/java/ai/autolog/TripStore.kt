package ai.autolog

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 내보낸 운행기록부. uri가 있으면 다른 앱으로 공유할 수 있다. */
class Exported(val name: String, val location: String, val uri: Uri?)

// ponytail: 단말 로컬 JSON 파일. 서버 동기화·웹 관리자 붙일 때 교체.
class TripStore(context: Context, private val profiles: ProfileStore) {
    private val resolver = context.contentResolver
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
                dept = o.optString("dept"),
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

    /**
     * 운행기록부 CSV를 공용 다운로드/AutoLog 폴더에 새 파일로 저장한다(Android 10+, 저장소 권한 불필요).
     * 앱 전용 폴더는 사용자가 꺼낼 수 없어서 공용 폴더를 쓴다. Android 9는 앱 전용 폴더로 대신한다.
     */
    fun exportCsv(nowMillis: Long = System.currentTimeMillis()): Exported {
        val name = "운행기록부_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMillis))}.csv"
        val bytes = toNtsCsv(all(), profiles.load()).toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val f = File(exportDir, name).apply { writeBytes(bytes) }
            return Exported(name, f.absolutePath, null)
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("다운로드 폴더에 파일을 만들 수 없습니다")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("파일을 열 수 없습니다")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // 반쯤 쓴 파일을 남기지 않는다
            throw e
        }
        return Exported(name, "다운로드/$EXPORT_FOLDER/$name", uri)
    }

    private companion object { const val EXPORT_FOLDER = "AutoLog" }

    private fun saveAll(trips: List<Trip>) {
        val arr = JSONArray()
        trips.forEach { t ->
            arr.put(
                JSONObject()
                    .put("start", t.startMillis).put("end", t.endMillis)
                    .put("odoStart", t.odoStartKm.toDouble()).put("odoEnd", t.odoEndKm.toDouble())
                    .put("driver", t.driver).put("purpose", t.purpose?.name ?: "")
                    .put("note", t.note).put("arrived", t.arrived).put("dept", t.dept)
            )
        }
        file.writeText(arr.toString())
    }
}
