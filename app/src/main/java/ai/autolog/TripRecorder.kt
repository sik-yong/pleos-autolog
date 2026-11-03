package ai.autolog

import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode

private const val TAG = "AutoLog"
private const val LISTEN_TIMEOUT_MILLIS = 10_000L
private const val DRIVER = "운전자" // ponytail: 고정값, 차량 프로필 연동 시 교체

/**
 * P 해제 = 운행 시작. P 진입만으로는 끝내지 않고 하차 신호(운전석 벨트 해제·문 열림·내비 도착)를 기다린다.
 * 하차 신호 없이 10분 넘게 서 있으면 정차 한 번에 한 번만 "운행을 마칠까요?" 묻는다. 아니라고 하면 다시 묻지 않는다.
 * 끝난 지 30분 안에 다시 출발하면 이전 운행과 이어 붙일지 묻는다 (주유·충전 같은 짧은 정차).
 * 끝난 운행은 용도 없이 먼저 저장하고 음성으로 묻는다. 답을 못 받으면 다음 출발 때 다시 묻는다.
 */
class TripRecorder(context: Context, private val store: TripStore) {
    private val vehicle = Vehicle(context)
    private val navi = NaviHelper(context)
    private val tts = TextToSpeech(context, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context, SttMode.ON_DEVICE)
    private val main = Handler(Looper.getMainLooper())
    private val parkTimeout = Runnable { checkEnd() }
    // 답이 없으면 마이크를 닫는다. 질문은 그대로 남아 정차 중 화면에서 고르거나 다음 출발 때 다시 묻는다.
    private val listenTimeout = Runnable { stt.stop(); update("음성 답변 없음: 화면에서 선택하세요") }

    private var startMillis = 0L
    private var odoStart: Float? = null
    private var lastDestination = ""
    private var parkedSince: Long? = null
    private var arrived = false
    private var beltReleased = false
    private var doorOpened = false
    private var buckled: Boolean? = null
    private var asking: Trip? = null
    private var askedThisStop = false
    private var mergeWith: Trip? = null

    enum class Confirm { END, MERGE }
    /** 네/아니요로 답해야 하는 질문. */
    var confirm: Confirm? = null; private set

    var status = "대기 중"; private set
    var onChange: () -> Unit = {}

    val driving get() = odoStart != null
    /** 용도를 받아야 하는 운행 (지금 묻는 것 → 저장소의 미분류 순). */
    val pending get() = asking ?: store.pending()

    fun start() {
        vehicle.initialize()
        vehicle.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = main.post { onGear(gear) }.let {}
            override fun onFailed(e: Exception) = log("기어 상태 수신 실패", e)
        })
        vehicle.getSeat().registerSeatBeltBuckledState(SeatArea(SeatValue.ROW_1_LEFT), object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(state: Boolean?) = main.post {
                if (parkedSince != null && buckled == true && state == false) { beltReleased = true; checkEnd() }
                buckled = state
            }.let {}
            override fun onFailed(e: Exception) = log("안전벨트 상태 수신 실패", e)
        })
        vehicle.getDoor().registerDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), object : DoorPositionListener {
            override fun onPositionUpdated(position: Int?) = main.post {
                if (parkedSince != null && (position ?: 0) > 0) { doorOpened = true; checkEnd() }
            }.let {}
            override fun onFailed(e: Exception) = log("운전석 문 상태 수신 실패", e)
        })
        navi.initialize(
            onConnected = {
                navi.addListener(object : NaviHelperEventListener {
                    override fun onDestinationArrived(info: DestinationArrivedInfo) = main.post {
                        lastDestination = info.name; arrived = true; checkEnd()
                    }.let {}
                })
            },
            onFailed = { log("내비 연결 실패", it) },
        )
        tts.initialize()
        tts.addEventListener(object : EventListener {
            override fun onStart() = Unit
            override fun onReady() = Unit
            override fun onStop() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onDone() {
                if (asking == null && confirm == null) return
                stt.request()
                main.postDelayed(listenTimeout, LISTEN_TIMEOUT_MILLIS)
            }
            override fun onError(errorType: TtsError) = update("음성 안내 실패: $errorType")
        })
        stt.initialize()
        stt.setLocale(Locale.KOREA)
        stt.addListener(object : ResultListener {
            override fun onReady() = Unit
            override fun onStartedRecognition() = update("답변 듣는 중…")
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onEndedRecognition() = Unit
            override fun onUpdated(stt: String, completed: Boolean) {
                if (completed) main.post { main.removeCallbacks(listenTimeout); answer(stt) }
            }
            override fun onError(errorType: SttError) = update("음성 인식 실패: 화면에서 선택하세요")
        })
    }

    private fun onGear(gear: VehicleGear?) {
        when {
            gear == VehicleGear.GEAR_PARK -> if (driving && parkedSince == null) park()
            gear == null || gear == VehicleGear.GEAR_UNKNOWN -> Unit
            parkedSince != null -> {
                main.removeCallbacks(parkTimeout); parkedSince = null
                if (confirm == Confirm.END) { confirm = null; stopListening() }
                update("운행 기록 중 (정차 후 재출발)")
            }
            else -> startTrip()
        }
    }

    // 벨트·문은 P 진입 이후 바뀐 것만 본다. 도착은 P 직전에 왔어도 인정.
    private fun park() {
        parkedSince = System.currentTimeMillis()
        beltReleased = false; doorOpened = false; askedThisStop = false
        main.postDelayed(parkTimeout, PARK_END_MILLIS)
        update("정차 중: 하차하면 운행을 마칩니다")
        checkEnd()
    }

    private fun checkEnd() {
        val since = parkedSince ?: return
        when (parkDecision(System.currentTimeMillis() - since, beltReleased, doorOpened, arrived)) {
            ParkDecision.END -> { if (confirm == Confirm.END) confirm = null; endTrip() }
            ParkDecision.ASK -> if (!askedThisStop) {
                askedThisStop = true
                askYesNo(Confirm.END, "오래 정차 중: 운행을 마칠까요?", "정차한 지 10분이 지났습니다. 운행을 마칠까요?")
            }
            ParkDecision.WAIT -> Unit
        }
    }

    private fun askYesNo(c: Confirm, status: String, question: String) {
        confirm = c
        update(status)
        tts.speak(question, true)
    }

    /**
     * 네/아니요 답변.
     * END: 아니면 이번 정차에서는 다시 묻지 않고 하차 신호나 재출발을 기다린다.
     * MERGE: 네면 이번 운행이 끝날 때 이전 운행과 합쳐 저장한다.
     */
    fun reply(yes: Boolean) {
        val c = confirm ?: return
        confirm = null
        stopListening()
        when (c) {
            Confirm.END -> if (yes) endTrip() else {
                update("운행 계속 기록 중 (정차)")
                tts.speak("운행을 계속 기록합니다.", true)
            }
            Confirm.MERGE -> {
                if (!yes) mergeWith = null
                update(if (yes) "이전 운행에 이어서 기록 중" else "새 운행 기록 중")
                if (!askPending()) tts.speak(if (yes) "이어서 기록합니다." else "새 운행으로 기록합니다.", true)
            }
        }
    }

    fun startTrip() {
        if (driving) return
        vehicle.getOdometer().getCurrentDistanceDriven(
            { km -> main.post { begin(km ?: 0f) } },
            { log("계기판 조회 실패", it) },
        )
    }

    private fun begin(km: Float) {
        val now = System.currentTimeMillis()
        val last = store.lastEnd()
        // 앱이 꺼져 있던 동안 달린 거리. 시각은 알 수 없어 마지막 종료~지금으로 둔다.
        val missed = last?.let { missedDistanceKm(it.odoEndKm, km) }
        if (missed != null) store.add(Trip(last.endMillis, now, last.odoEndKm, km, DRIVER, null, "미기록 구간"))
        startMillis = now; odoStart = km; lastDestination = ""; arrived = false
        asking = null
        mergeWith = if (missed == null) mergeCandidate(last, now) else null
        if (mergeWith != null) askYesNo(Confirm.MERGE, "운행 기록 중: 이전 운행에 이어서 기록할까요?", "잠시 정차 후 출발하셨네요. 이전 운행에 이어서 기록할까요?")
        else { update("운행 기록 중"); askPending() }
    }

    /** 이어 붙일 운행은 끝날 때 합쳐지므로 묻지 않는다. 물었으면 true. */
    private fun askPending(): Boolean {
        val t = store.pending()?.takeIf { it.startMillis != mergeWith?.startMillis } ?: return false
        ask(t, "지난 운행 ${"%.1f".format(t.distanceKm)}킬로미터의 용도를 알려 주세요. 업무, 출퇴근, 개인 중 무엇인가요?")
        return true
    }

    fun endTrip() {
        val from = odoStart ?: return
        vehicle.getOdometer().getCurrentDistanceDriven(
            { km -> main.post { finish(from, km ?: from) } },
            { log("계기판 조회 실패", it) },
        )
    }

    private fun finish(from: Float, to: Float) {
        if (odoStart == null) return
        main.removeCallbacks(parkTimeout)
        odoStart = null; parkedSince = null
        if (confirm == Confirm.MERGE) { confirm = null; mergeWith = null } // 답 없이 끝나면 따로 둔다
        var trip = Trip(startMillis, System.currentTimeMillis(), from, to, DRIVER, null, lastDestination, arrived)
        mergeWith?.let { prev -> store.remove(prev.startMillis); trip = merge(prev, trip) }
        mergeWith = null
        store.add(trip) // 답을 못 받아도 거리는 남긴다
        val p = trip.purpose ?: run {
            update("운행 종료: 용도 확인 중")
            return ask(trip, "운행이 끝났습니다. 업무 운행인가요, 출퇴근인가요, 개인 운행인가요?")
        }
        update("저장됨: ${p.label} ${"%.1f".format(trip.distanceKm)}km (이어서 기록)")
        tts.speak("이전 운행에 이어 ${p.label} 운행으로 저장했습니다.", true)
    }

    private fun ask(t: Trip, question: String) {
        asking = t
        tts.speak(question, true)
    }

    /** 음성 답변 또는 화면 선택. 음성은 note에 원문을 덧붙인다. */
    fun answer(spoken: String) {
        if (confirm != null) {
            val yes = isYes(spoken) ?: return update("\"$spoken\" 을(를) 이해하지 못했어요. 화면에서 선택하세요")
            return reply(yes)
        }
        val p = classifyPurpose(spoken) ?: return update("\"$spoken\" 을(를) 이해하지 못했어요. 화면에서 선택하세요")
        save(p, spoken)
    }

    fun choose(p: Purpose) = save(p, null)

    private fun save(p: Purpose, spoken: String?) {
        val t = pending ?: return
        stopListening() // 화면에서 고른 경우 듣기 중인 마이크를 닫는다
        val note = listOfNotNull(t.note.ifEmpty { null }, spoken).joinToString(" / ")
        store.setPurpose(t.startMillis, p, note)
        asking = null
        update("저장됨: ${p.label} ${"%.1f".format(t.distanceKm)}km")
        tts.speak("${p.label} 운행으로 저장했습니다.", true)
    }

    private fun stopListening() { main.removeCallbacks(listenTimeout); stt.stop() }

    fun release() {
        main.removeCallbacksAndMessages(null)
        navi.release(); tts.release(); stt.release(); vehicle.release()
    }

    private fun update(s: String) { status = s; Log.i(TAG, s); onChange() }
    private fun log(msg: String, e: Throwable) { Log.w(TAG, msg, e); update(msg) }
}
