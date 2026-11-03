package ai.autolog

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 운행은 화면이 꺼져 있어도 이어져야 하므로 기록기는 앱 수명에 묶는다. */
class AutoLogApp : Application() {
    lateinit var store: TripStore
    lateinit var recorder: TripRecorder

    override fun onCreate() {
        super.onCreate()
        store = TripStore(this)
        recorder = TripRecorder(this, store).also { it.start() }
    }
}

class MainActivity : Activity() {
    private val app get() = application as AutoLogApp
    private lateinit var status: TextView
    private lateinit var purposeRow: LinearLayout
    private lateinit var confirmRow: LinearLayout
    private lateinit var parkedOnly: LinearLayout
    private lateinit var list: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = 32
        status = TextView(this).apply { textSize = 28f; setPadding(pad, pad, pad, pad) }
        purposeRow = row(Purpose.entries.map { p -> button(p.label) { app.recorder.choose(p) } })
        confirmRow = row(listOf(
            button("네") { app.recorder.reply(true) },
            button("아니요") { app.recorder.reply(false) },
        ))
        list = TextView(this).apply { textSize = 18f; setPadding(pad, pad, pad, pad) }
        // 에뮬레이터 시연용: 기어 조작 없이 운행 시작·종료
        val demoRow = row(listOf(
            button("운행 시작") { app.recorder.startTrip() },
            button("운행 종료") { app.recorder.endTrip() },
        ))
        parkedOnly = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(listOf(button("운행기록부 내보내기") { status.text = "저장 위치: ${app.store.exportCsv().absolutePath}" })))
            addView(list)
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = getString(R.string.app_name); textSize = 34f; setPadding(pad, pad, pad, 0) })
                addView(status); addView(confirmRow); addView(purposeRow); addView(demoRow); addView(parkedOnly)
            })
        })
        app.recorder.onChange = { runOnUiThread(::render) }
        render()
    }

    override fun onDestroy() {
        app.recorder.onChange = {}
        super.onDestroy()
    }

    // 주행 중에는 상태 한 줄만 보여 준다 (운전자 주의분산 기준).
    private fun render() {
        val r = app.recorder
        status.text = r.status
        purposeRow.visibility = if (r.pending != null && !r.driving) LinearLayout.VISIBLE else LinearLayout.GONE
        confirmRow.visibility = if (r.confirm != null) LinearLayout.VISIBLE else LinearLayout.GONE
        parkedOnly.visibility = if (r.driving) LinearLayout.GONE else LinearLayout.VISIBLE
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        list.text = app.store.all().sortedByDescending { it.startMillis }.joinToString("\n") {
            "${fmt.format(Date(it.startMillis))}  ${it.purpose?.label ?: "미분류"}  ${"%.1f".format(it.distanceKm)}km  ${it.note}"
        }.ifEmpty { "아직 기록된 운행이 없습니다." }
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; textSize = 22f; setOnClickListener { onClick() } }

    private fun row(views: List<Button>) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, 140, 1f)) }
    }
}
