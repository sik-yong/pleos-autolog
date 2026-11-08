package ai.autolog

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
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

/** 차량 화면용 어두운 팔레트. */
private object Palette {
    val BG = Color.parseColor("#0F1216")
    val CARD = Color.parseColor("#1A1F26")
    val CARD_ALERT = Color.parseColor("#2A2416")
    val TEXT = Color.parseColor("#F2F4F7")
    val SUB = Color.parseColor("#9AA4B2")
    val ACCENT = Color.parseColor("#3D8BFD")
    val BUTTON = Color.parseColor("#2A313B")
    val IDLE = Color.parseColor("#6B7685")
    val DRIVING = Color.parseColor("#2FB37A")
    val PARKED = Color.parseColor("#E5A93B")

    fun of(p: Purpose?) = when (p) {
        Purpose.BUSINESS -> ACCENT
        Purpose.COMMUTE -> Color.parseColor("#26A6A6")
        Purpose.PERSONAL -> IDLE
        null -> PARKED
    }
}

class MainActivity : Activity() {
    private val app get() = application as AutoLogApp
    /** 에뮬레이터 시연용 버튼은 debug 빌드에서만 보인다. */
    private val demo by lazy { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    private lateinit var stateChip: TextView
    private lateinit var status: TextView
    private lateinit var confirmRow: LinearLayout
    private lateinit var purposeBox: LinearLayout
    private lateinit var purposeTitle: TextView
    private var demoRow: LinearLayout? = null
    private lateinit var summaryCard: LinearLayout
    private lateinit var monthTotal: TextView
    private lateinit var monthRatio: TextView
    private lateinit var monthDetail: TextView
    private lateinit var recordsSection: LinearLayout
    private lateinit var exportInfo: TextView
    private lateinit var tripList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wide = resources.configuration.screenWidthDp >= 900

        stateChip = text("", 16f, Color.WHITE, bold = true).apply { setPadding(dp(14), dp(6), dp(14), dp(6)) }
        status = text("", 30f, Palette.TEXT, bold = true).apply { setPadding(0, dp(14), 0, dp(4)) }
        confirmRow = buttonRow(listOf(
            button("네", Palette.ACCENT) { app.recorder.reply(true) },
            button("아니요") { app.recorder.reply(false) },
        ))
        purposeTitle = text("", 18f, Palette.SUB).apply { setPadding(0, dp(16), 0, dp(4)) }
        purposeBox = column().apply {
            addView(purposeTitle)
            addView(buttonRow(Purpose.entries.map { p -> button(p.label, Palette.of(p)) { app.recorder.choose(p) } }))
        }
        val statusCard = card().apply {
            addView(LinearLayout(context).apply { addView(stateChip) })
            addView(status)
            addView(confirmRow)
            addView(purposeBox)
            if (demo) addView(buttonRow(listOf(
                button("시연: 운행 시작") { app.recorder.startTrip() },
                button("시연: 운행 종료") { app.recorder.endTrip() },
            )).also { demoRow = it })
        }

        monthTotal = text("", 36f, Palette.TEXT, bold = true)
        monthRatio = text("", 20f, Palette.ACCENT, bold = true).apply { setPadding(0, dp(4), 0, 0) }
        monthDetail = text("", 16f, Palette.SUB).apply { setPadding(0, dp(8), 0, 0) }
        summaryCard = card().apply {
            addView(text("이번 달 운행", 18f, Palette.SUB))
            addView(monthTotal); addView(monthRatio); addView(monthDetail)
        }

        val top = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            addView(statusCard, if (wide) weighted(2f) else full())
            addView(summaryCard, (if (wide) weighted(1f) else full()).apply {
                if (wide) marginStart = dp(16) else topMargin = dp(16)
            })
        }

        exportInfo = text("", 16f, Palette.SUB).apply { setPadding(0, dp(8), 0, 0) }
        tripList = column()
        recordsSection = column().apply {
            setPadding(0, dp(24), 0, 0)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text("운행 기록", 24f, Palette.TEXT, bold = true), weighted(1f))
                addView(button("운행기록부 내보내기", Palette.ACCENT) { export() }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(64)))
            })
            addView(exportInfo)
            addView(tripList)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            addView(column().apply {
                setPadding(dp(32), dp(28), dp(32), dp(32))
                addView(text(getString(R.string.app_name), 28f, Palette.TEXT, bold = true))
                addView(text("차량 데이터로 기록하는 업무용 승용차 운행기록부", 16f, Palette.SUB).apply {
                    setPadding(0, dp(2), 0, dp(20))
                })
                addView(top)
                addView(recordsSection)
            })
        })
        app.recorder.onChange = { runOnUiThread(::render) }
        render()
    }

    override fun onDestroy() {
        app.recorder.onChange = {}
        super.onDestroy()
    }

    private fun export() {
        val f = app.store.exportCsv()
        exportInfo.text = "운행 ${app.store.all().size}건을 국세청 운행기록부 양식(CSV)으로 저장했습니다.\n${f.absolutePath}"
    }

    /**
     * 달리는 중에는 상태만 보여 준다(운전자 주의분산 기준). 질문 답변·기록 목록은 정차(P) 중이거나 운행이 끝났을 때만 연다.
     * 출발 직후의 질문(이어 붙이기, 지난 운행 용도)은 음성으로만 받는다.
     */
    private fun render() {
        val r = app.recorder
        val moving = r.driving && !r.parked
        val (label, color) = when {
            moving -> "주행 중" to Palette.DRIVING
            r.parked -> "정차 중" to Palette.PARKED
            else -> "대기" to Palette.IDLE
        }
        stateChip.text = label
        stateChip.background = rounded(color, dp(16).toFloat())
        status.text = r.status

        confirmRow.visibility = visible(r.confirm != null && !moving)
        val pending = r.pending
        purposeBox.visibility = visible(pending != null && !moving && r.confirm == null)
        if (pending != null) purposeTitle.text = "${dayTime(pending.startMillis)} · ${km(pending.distanceKm)} 운행의 용도를 골라 주세요"

        demoRow?.visibility = visible(!moving)
        summaryCard.visibility = visible(!moving)
        recordsSection.visibility = visible(!moving)
        if (moving) return

        val trips = app.store.all()
        val s = monthSummary(trips, System.currentTimeMillis())
        monthTotal.text = km(s.totalKm)
        monthRatio.text = s.businessRatio?.let { "업무 사용 비율 ${"%.0f".format(it * 100)}%" } ?: "업무 사용 비율 -"
        monthDetail.text = buildString {
            append("운행 ${s.trips}건 · 업무용 ${km(s.businessKm)}")
            if (s.unclassified > 0) append("\n용도 미분류 ${s.unclassified}건")
        }

        tripList.removeAllViews()
        if (trips.isEmpty()) {
            tripList.addView(text("아직 기록된 운행이 없습니다. 기어를 P에서 바꾸면 기록을 시작합니다.", 18f, Palette.SUB).apply {
                setPadding(0, dp(16), 0, 0)
            })
        }
        trips.sortedByDescending { it.startMillis }.take(MAX_ROWS).forEach { tripList.addView(tripRow(it)) }
    }

    private fun tripRow(t: Trip) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(if (t.purpose == null) Palette.CARD_ALERT else Palette.CARD, dp(14).toFloat())
        setPadding(dp(20), dp(16), dp(20), dp(16))
        layoutParams = full().apply { topMargin = dp(10) }
        addView(column().apply {
            addView(text("${dayTime(t.startMillis)} – ${time(t.endMillis)}", 18f, Palette.TEXT, bold = true))
            if (t.note.isNotEmpty()) addView(text(t.note, 16f, Palette.SUB).apply { setPadding(0, dp(4), 0, 0) })
        }, weighted(1f))
        addView(text(km(t.distanceKm), 22f, Palette.TEXT, bold = true).apply { setPadding(dp(16), 0, dp(16), 0) })
        addView(text(t.purpose?.label ?: "미분류", 16f, Color.WHITE, bold = true).apply {
            background = rounded(Palette.of(t.purpose), dp(14).toFloat())
            setPadding(dp(14), dp(6), dp(14), dp(6))
        })
    }

    // --- 화면 부품 ---

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun visible(show: Boolean) = if (show) View.VISIBLE else View.GONE
    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun weighted(w: Float) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun card() = column().apply {
        background = rounded(Palette.CARD, dp(20).toFloat())
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    // 차량 화면은 장갑·흔들림을 감안해 터치 영역을 크게 둔다 (높이 72dp).
    private fun button(label: String, color: Int = Palette.BUTTON, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 20f; setTextColor(Color.WHITE); isAllCaps = false
        background = rounded(color, dp(14).toFloat())
        setPadding(dp(24), 0, dp(24), 0)
        setOnClickListener { onClick() }
    }

    private fun buttonRow(views: List<Button>) = LinearLayout(this).apply {
        setPadding(0, dp(12), 0, 0)
        views.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, dp(72), 1f).apply { if (i > 0) marginStart = dp(12) })
        }
    }

    private fun km(v: Float) = "${"%.1f".format(v)}km"
    private fun dayTime(ms: Long) = SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA).format(Date(ms))
    private fun time(ms: Long) = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(ms))

    private companion object {
        const val MAX_ROWS = 50
    }
}
