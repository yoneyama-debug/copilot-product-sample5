package jp.co.sstinc.lsimpledev

import android.os.SystemClock
import androidx.databinding.ObservableField
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

import java.util.*

data class ReceiveHistoryItem(
    val id: Long,
    val timestamp: String,
    val byteSizeText: String,
    val rawDataText: String,
    val processedDataText: String,
    val processedTitle: String,
    val showProcessed: Boolean,
)

data class DecodeOkEvent(
    val data: ByteArray?,
    val fid: Int,
    val sid: Int,
    val detectDataFreq: Float,
    val timestampText: String,
    val byteSizeText: String,
    val accumulatedTrims: List<Int>,
    val bufferUsage: Float,
    val hardErrorCountOk: Int,
    val hardErrorCountNg: Int,
    val softDataBitLength: Int,
    val isEnd: Boolean,
)

class LSimpleViewModel : ViewModel() {
    var message = ObservableField<String>()
    var messageColor = ObservableField(R.color.black)
    var volume = ObservableField<Int>()
    var running = ObservableField<Boolean>()
    var fileName = ObservableField("-")
    var postErrorText = ObservableField("")

    var recTimString = ObservableField("")
    var currentFreqString = ObservableField("-")
    var rawDetectDataString = ObservableField("")
    var processedDetectDataString = ObservableField("")
    var isRawDetectDataVisible = ObservableField(false)
    var trimsString = ObservableField("-")
    var detectDataFreqString = ObservableField("-")
    var detectType = ObservableField("-")
    var bufferUsedString = ObservableField("-")
    var hardErrorRateString = ObservableField("-")
    var validDataRateString = ObservableField("-")

    var startDate : Date? = null
    var stopDate : Date? = null
    var detectData : ByteArray? = null
    var detectFid : Int? = null
    var detectSid : Int? = null
    var detectDataFreq : Float = 0f
    var bufferUsed : Float = 0f
    var hardErrorRate : Float? = null
    var validDataRate : Float? = null
    var currentFreq : Float = 0f
    var detectTimestampString = ObservableField("")
    var detectByteSizeString = ObservableField("")


    // demoFlavor用
    var isProcessedDetectDataVisible = ObservableField(false)
    var isDetailVisible = ObservableField(false)

    // internal(社内dev)Flavor用
    var isProcessedDetectDataColumnVisible = ObservableField(false)
    var detectDataFormatName = ObservableField("")
    var detectFidString = ObservableField("-")
    var detectSidString = ObservableField("-")

    fun appendDecodeOkEvent(event: DecodeOkEvent) {
        detectData = event.data
        detectFid = event.fid
        detectSid = event.sid
        detectDataFreq = event.detectDataFreq
        detectTimestampString.set(event.timestampText)
        detectByteSizeString.set(event.byteSizeText)
        trimsString.set("[" + event.accumulatedTrims.joinToString(",") + "]")
        if (event.isEnd) {
            currentFreq = 0f
        }
        bufferUsed = event.bufferUsage

        val decodedBitCount = event.hardErrorCountOk + event.hardErrorCountNg
        hardErrorRate =
            if (decodedBitCount > 0) {
                event.hardErrorCountNg.toFloat() / decodedBitCount
            } else {
                null
            }
        validDataRate =
            if (event.softDataBitLength > 0) {
                decodedBitCount.toFloat() / event.softDataBitLength
            } else {
                null
            }

        update()
        appendCurrentReceiveHistory()
    }

    fun update() {
        rawDetectDataString.set(detectData?.toDisplayHexString() ?: "")
        isRawDetectDataVisible.set(detectData != null)
        detectDataFreqString.set(if (detectDataFreq == 0f) "-" else String.format(Locale.US, "%.8f", detectDataFreq))
        currentFreqString.set(if (currentFreq == 0f) "-" else String.format(Locale.US, "%.8f", currentFreq))
        bufferUsedString.set(if (bufferUsed == 0f) "-" else String.format(Locale.US, "%.2f", bufferUsed))
        hardErrorRateString.set(hardErrorRate?.let { String.format(Locale.US, "%.4f", it) } ?: "-")
        validDataRateString.set(validDataRate?.let { String.format(Locale.US, "%.4f", it) } ?: "-")

        // Flavorごとの処理
        if (buildConfigIsDemo()) {
            // demoFlavor用
            // fid=0b11, sid=0x00の場合以外はLSimpleFragmentで弾かれる
            val shouldShowDemoData = shouldShowDemoTemperatureHumidity()
            isProcessedDetectDataVisible.set(shouldShowDemoData)
            processedDetectDataString.set(
                if (shouldShowDemoData) detectData?.toDemoDataString().orEmpty() else ""
            )
        } else {
            // internal(社内dev)Flavor用
            detectFidString.set(detectFid?.let { "0b%2s".format(it.toString(2)).replace(" ", "0") } ?: "-")
            detectSidString.set(detectSid?.let { "0x%02X".format(it) } ?: "-")

            when {
                // fid, sidによってフォーマットを切り替える
                detectFid == 0b11 && detectSid == 0x00 -> {
                    val shouldShowDemoData = shouldShowDemoTemperatureHumidity()
                    isProcessedDetectDataVisible.set(shouldShowDemoData)
                    isProcessedDetectDataColumnVisible.set(shouldShowDemoData)
                    detectDataFormatName.set("デモ用フォーマット")
                    processedDetectDataString.set(
                        if (shouldShowDemoData) detectData?.toDemoDataString().orEmpty() else ""
                    )
                }
                else -> {
                    // フォーマットが定義されていない場合は加工後データ表示欄ごと非表示
                    isProcessedDetectDataVisible.set(false)
                    isProcessedDetectDataColumnVisible.set(false)
                    detectDataFormatName.set("")
                    processedDetectDataString.set("")
                }
            }
        }
    }
    private val _receiveHistory = MutableLiveData<List<ReceiveHistoryItem>>(emptyList())
    val receiveHistory: LiveData<List<ReceiveHistoryItem>> = _receiveHistory

    var isHistoryEmpty = ObservableField(true)

    fun appendCurrentReceiveHistory(maxCount: Int = 101) {
        if (detectData == null) return

        val showProcessed = isProcessedDetectDataVisible.get() == true

        val processedTitle = ""

        val item = ReceiveHistoryItem(
            id = SystemClock.elapsedRealtimeNanos(),
            timestamp = detectTimestampString.get().orEmpty(),
            byteSizeText = detectByteSizeString.get().orEmpty(),
            rawDataText = rawDetectDataString.get().orEmpty(),
            processedDataText = processedDetectDataString.get().orEmpty(),
            processedTitle = processedTitle,
            showProcessed = showProcessed && processedDetectDataString.get().isNullOrEmpty().not(),
        )

        val newList = listOf(item) + (_receiveHistory.value ?: emptyList())
        _receiveHistory.value = newList.take(maxCount)
        isHistoryEmpty.set(_receiveHistory.value.isNullOrEmpty())
    }

    fun clearHistory() {
        _receiveHistory.value = emptyList()
        isHistoryEmpty.set(true)
    }
    private fun shouldShowDemoTemperatureHumidity(): Boolean {
        val data = detectData ?: return false
        if(detectFid != 0b11 || detectSid != 0x00) {
            return false
        }
        val demoDataList = runCatching { data.getDemoData() }.getOrNull() ?: return false
        if (demoDataList.isEmpty()) {
            return false
        }
        return demoDataList.all {
            it is DemoData.Temperature || it is DemoData.Humidity
        }
    }

    fun updateTime(now: Date) {
        val text = startDate?.let { from ->
            val to = stopDate ?: now
            timeFormat(to.time - from.time)
        }

        recTimString.set(text ?: "-")
    }

    private fun timeFormat(msec : Long) : String {
        val minutes = msec / 60_000
        val seconds = (msec % 60_000) / 1_000
        val millis = (msec % 1_000) / 10
        return String.format(Locale.US, "%02d:%02d:%02d", minutes, seconds, millis)
    }

    fun toggleDetail(){
        // 現在の値を反転させてセットする（nullの場合はfalseとみなす）
        val currentValue = isDetailVisible.get() ?: false
        isDetailVisible.set(!currentValue)
    }
}

fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
fun ByteArray.toDisplayHexString() = joinToString(" ") {
    "%02X".format(it.toInt() and 0xFF)
}
