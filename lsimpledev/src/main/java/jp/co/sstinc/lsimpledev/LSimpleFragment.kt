package jp.co.sstinc.lsimpledev

import android.Manifest.permission.RECORD_AUDIO
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import jp.co.sstinc.audio.FileRecorder
import jp.co.sstinc.audio.RawFloatRecorder
import jp.co.sstinc.file.copyFileToDownloads
import jp.co.sstinc.log.CsvWriter
import jp.co.sstinc.log.LogWriter
import jp.co.sstinc.lsimple.raw.DecodeOption
import jp.co.sstinc.lsimple.raw.LSimpleRawReceiver
import jp.co.sstinc.lsimple.raw.LSimpleRawReceiverListener
import jp.co.sstinc.lsimple.raw.LSimpleRawReceiverListener.EventType
import jp.co.sstinc.lsimpledev.databinding.FragmentLsimpleBinding
import jp.co.sstinc.net.EventPoster
import jp.co.sstinc.sstaudio.AudioError
import jp.co.sstinc.wavformview.WaveformDrawer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule
import kotlin.coroutines.resume
import jp.co.sstinc.net.PostLimitExceededException

/**
 * LSimpleライブラリの操作を行う。
 */
class LSimpleFragment : Fragment() {

    private var _binding: FragmentLsimpleBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private var lSimple : LSimpleRawReceiver? = null
    private var wavRecorder : FileRecorder? = null
    private var rawFloatRecorder : RawFloatRecorder? = null
    var logWriter : LogWriter? = null
    private var csvWriter : CsvWriter? = null
    private var eventPoster = EventPoster()

    private var levelMeterTimer : TimerTask? = null
    private var timeUpdateTimer : TimerTask? = null

    // drawWaveformのマーカーの色の定数を定義
    private val MARKER_COLOR_FREQ_DETECTED = 0xffff88ff.toInt()
    private val MARKER_COLOR_OK = 0xffffffff.toInt()
    private val MARKER_COLOR_CRC_ERROR = 0xff0000ff.toInt()
    private val MARKER_COLOR_END = 0xff00ffff.toInt()

    private var wavFile : String? = null
    private var logFile : String? = null
    private var tmpWavFile : File? = null
    private var tmpLogFile : File? = null
    private var pendingCsvFile : File? = null
    private var csvFile : String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var receiveHistoryAdapter = ReceiveHistoryAdapter()
    private var isCurrentPostErrorLowPriority = false


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLsimpleBinding.inflate(inflater, container, false)
        binding.setToggleRecord {
            if(binding.viewModel?.running?.get() == true) {
                stop()
            } else {
                start()
            }
        }
        binding.setClear { clear() }
        binding.setPlay { play() }

        binding.lsimpleInformationView.lsimpleInformationLayout.layoutTransition = null

        val waveformView = binding.waveform
        waveformView.init()

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.viewModel = ViewModelProvider.NewInstanceFactory().create(LSimpleViewModel::class.java)
        resetPostError()

        setupReceiveHistoryList()

        binding.viewModel?.receiveHistory?.observe(viewLifecycleOwner) { history ->
            val items = history.drop(1).take(100)
            val recyclerView = binding.lsimpleInformationView.receiveHistoryList
            val layoutManager = recyclerView.layoutManager as LinearLayoutManager

            val shouldAutoScroll =
                recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                        layoutManager.findFirstVisibleItemPosition() <= 0

            receiveHistoryAdapter.submitList(items) {
                if (shouldAutoScroll && items.isNotEmpty()) {
                    recyclerView.scrollToPosition(0)
                }
            }
        }

        recoverPendingCsvFiles()
    }

    override fun onResume() {
        super.onResume()

        // 録音のパーミッションがない場合は取得を行う
        requestPermissionIfNeed()
    }

    override fun onPause() {
        super.onPause()
        stop()
        stopPlay()
    }

    override fun onDestroyView() {
        stopPlay()
        binding.lsimpleInformationView.receiveHistoryList.adapter = null
        super.onDestroyView()
        binding.waveform.release()
        _binding = null
    }

    // 録音パーミッションの取得
    // - 取得できた場合は何も行わない
    // - 拒否されている場合は、設定画面を開けるようにする。
    private fun requestPermissionIfNeed() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), RECORD_AUDIO) == PERMISSION_GRANTED &&
                     (Build.VERSION.SDK_INT >= 29 || ContextCompat.checkSelfPermission(requireContext(), WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED)
            -> {
                // 許可済みの場合は何もしない
            }

            shouldShowRequestPermissionRationale(RECORD_AUDIO) -> {
                // 拒否されている場合は設定を開けるようにする
                makeSnackbar(
                    "マイクの使用を許可してください",
                    Snackbar.LENGTH_INDEFINITE
                )
                    ?.setAction("設定を開く") {
                        val intent = Intent()
                        intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        intent.data = Uri.parse("package:" + requireActivity().packageName)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                    }
                    ?.show()
            }
            (Build.VERSION.SDK_INT < 29 && shouldShowRequestPermissionRationale(WRITE_EXTERNAL_STORAGE))-> {
                // 拒否されている場合は設定を開けるようにする
                makeSnackbar(
                    "ストレージ書き込み許可してください",
                    Snackbar.LENGTH_INDEFINITE
                )
                    ?.setAction("設定を開く") {
                        val intent = Intent()
                        intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        intent.data = Uri.parse("package:" + requireActivity().packageName)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                    }
                    ?.show()
            }

            else -> {
                // パーミッションの取得
                val permissions = mutableListOf(RECORD_AUDIO)
                if (Build.VERSION.SDK_INT < 29) {
                    permissions.add(WRITE_EXTERNAL_STORAGE)
                }
                requestPermissionLauncher.launch(permissions.toTypedArray())
            }
        }
    }
    private fun setupReceiveHistoryList() {
        binding.lsimpleInformationView.receiveHistoryList.apply {
            if (layoutManager == null) {
                layoutManager = LinearLayoutManager(requireContext())
            }
            adapter = receiveHistoryAdapter
            itemAnimator = DefaultItemAnimator().apply {
                addDuration = 180L
                moveDuration = 180L
                changeDuration = 120L
            }
        }
    }

    private fun recreateReceiveHistoryAdapter() {
        binding.lsimpleInformationView.receiveHistoryList.adapter = null
        receiveHistoryAdapter = ReceiveHistoryAdapter()
        setupReceiveHistoryList()
    }

    private fun animateLatestReceiveViews() {
        val latestDataContainer = _binding?.lsimpleInformationView?.latestDataContainer ?: return
        latestDataContainer.animate().cancel()
        latestDataContainer.post {
            latestDataContainer.alpha = 0f
            latestDataContainer.translationY = 12f * latestDataContainer.resources.displayMetrics.density
            latestDataContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .start()
        }
    }

    // パーミッション要求の結果が得られたあとの動作
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { isGranted ->
        if (!isGranted.containsValue(false)) {
            // 許可
            Log.d("lsimple", "許可")

        } else {
            // 拒否
            Log.d("lsimple", "拒否")
            val message = if (Build.VERSION.SDK_INT < 29) {
                "マイクとストレージの使用を許可してください"
            } else {
                "マイクの使用を許可してください"
            }

            makeSnackbar(message, Snackbar.LENGTH_INDEFINITE)
                ?.setAction("設定を開く") {
                    // 戻る
                    findNavController().popBackStack()
                }
                ?.show()
        }
    }

    private fun start() {
        if (lSimple != null) {
            return
        }

        val ctx = context ?: return

        if (ContextCompat.checkSelfPermission(requireContext(), RECORD_AUDIO) != PERMISSION_GRANTED) {
            return
        }
        recreateReceiveHistoryAdapter()
        binding.viewModel?.clearHistory()
        binding.waveform.drawer.setMode(WaveformDrawer.Mode.Spectrogram)

        val startTime = Date()
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val postEnabled = prefs.getBoolean(PREFS_KEY_POST_ENABLED, false)
        val postUrl = prefs.getString(PREFS_KEY_POST_URL, "")?.toHttpUrlOrNull()
        val isFileSaveEnabled = prefs.getBoolean(PREFS_KEY_FILE_SAVE, false)
        val isCsvSaveEnabled = prefs.getBoolean(PREFS_KEY_CSV_SAVE, false)

        resetPostError()
        if (isFileSaveEnabled) {
            wavFile = saveFileNameBase(startTime) + ".wav"
            logFile = saveFileNameBase(startTime) + ".txt"
            val cacheDir = ctx.cacheDir
            tmpWavFile = File(cacheDir, "tmp.wav")
            tmpLogFile = File(cacheDir, "tmp.txt")

            Log.d("lsimple", "file: " + tmpWavFile!!.absolutePath)

            wavRecorder = FileRecorder(ctx, tmpWavFile!!.absolutePath)
            wavRecorder?.startRecord()
            logWriter = LogWriter(tmpLogFile!!.absolutePath)

            lifecycleScope.launch (fileDispatcher) {
                logWriter?.open()
                logWriter?.write(startTime, "start TS-E1")
            }

            binding.viewModel?.message?.set("ファイル保存中")
            binding.viewModel?.messageColor?.set(R.color.red)
            binding.viewModel?.fileName?.set(wavFile!!)

        } else {
            binding.viewModel?.message?.set("待ち受け中")
            binding.viewModel?.messageColor?.set(R.color.black)
            binding.viewModel?.fileName?.set("-")
        }

        if (isCsvSaveEnabled) {
            csvFile = saveFileNameBase(startTime) + ".csv"
            val externalDir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            pendingCsvFile = File(externalDir, "pending_${csvFile}")
            csvWriter = CsvWriter(pendingCsvFile!!.absolutePath)
            lifecycleScope.launch(fileDispatcher) {
                csvWriter?.open()
                csvWriter?.appendHeader()
            }
        }

        lSimple = LSimpleRawReceiver(requireContext())
        val decodeOption = if (buildConfigIsDemo()) {
            DecodeOption(
                0b11,
                0x00, // デモ用sid
                DecodeOption.INVALID_SID,
                DecodeOption.INVALID_SID,
                DecodeOption.INVALID_SID,
                DecodeOption.INVALID_SID
            )
        } else {
            DecodeOption.AllPassOption()
        }
        lSimple!!.start(decodeOption, object : LSimpleRawReceiverListener {

            override fun onEvent(
                type: EventType,
                data: ByteArray?,
                fid: Int,
                sid: Int,
                bufferUsage: Float,
                fAdjust: Float,
                isEnd: Boolean,
                hardErrorCountOk: Int,
                hardErrorCountNg: Int,
                softDataBitLength: Int,
                findabTrim: Int,
                findabAbStart: Int,
                findabPacketLength: Int
            ) {
                if (type == EventType.DECODE_OK) {
                    if (buildConfigIsDemo()) {
                        // demoフレーバーの場合は、FID=0b11, SID=0x00(デモ用)のみを受信する
                        if (fid != 0b11 || sid != 0x00) {
                            return
                        }
                    }
                }

                Log.d("lsimple", "lsimple-event: type=$type, data=$data, bufferUsed=$bufferUsage fAdjust=$fAdjust, isEnd=$isEnd, hard_error_count_ok=$hardErrorCountOk, hard_error_count_ng=$hardErrorCountNg, soft_data_bit_length=$softDataBitLength, findab_ab_start=$findabAbStart, findab_packet_length=$findabPacketLength")

                val now = Date()
                val detectedDataHex = data?.toHexString().orEmpty()
                val dataSize = data?.size ?: 0
                val eventTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(LocalDateTime.now())
                val errorTimeText = DateFormat.format("HH:mm:ss", now).toString()
                lifecycleScope.launch (fileDispatcher) {
                    // json文字列にして書き出し

                    JSONObject (
                        mapOf(
                            "type" to type.name,
                            "data" to data?.toHexString(),
                            "fid" to fid,
                            "sid" to sid,
                            "bufferUsage" to bufferUsage,
                            "fAdjust" to fAdjust,
                            "isEnd" to isEnd,
                            "hardErrorCountOk" to hardErrorCountOk,
                            "hardErrorCountNg" to hardErrorCountNg,
                            "softDataBitLength" to softDataBitLength,
                            "findabTrim" to findabTrim,
                            "findabAbStart" to findabAbStart,
                            "findabPacketLength" to findabPacketLength,
                        )
                    ).let {
                        logWriter?.write(now, it.toString())
                    }

                    if (type == EventType.DECODE_OK) {
                        csvWriter?.appendRow(eventTimestamp, detectedDataHex, dataSize)
                    }
                }

                // サーバー通信
                if (type == EventType.DECODE_OK && postEnabled ) {
                    if (postUrl == null) {
                        showPostError("送信エラー: URL形式が不正です", lowPriority = false, errorTimeText)
                    } else {
                        lifecycleScope.launch {
                            // 送信実行
                            val result = eventPoster.postEvent(postUrl, eventTimestamp, detectedDataHex, dataSize)

                            // UIに反映(ここではmainスレッドに戻っている)
                            handlePostResult(result, errorTimeText)
                        }
                    }
                }


                when {
                    type == EventType.DECODE_OK -> {
                        binding.waveform.drawer.addMarker(MARKER_COLOR_OK, 1f)
                        val viewModel = binding.viewModel
                        val event = DecodeOkEvent(
                            data = data,
                            fid = fid,
                            sid = sid,
                            detectDataFreq = viewModel?.currentFreq ?: 0f,
                            timestampText = DateFormat.format("HH:mm:ss", now).toString(),
                            byteSizeText = "${dataSize}byte",
                            accumulatedTrims = lSimple!!.accumulatedTrims.toList(),
                            bufferUsage = bufferUsage,
                            hardErrorCountOk = hardErrorCountOk,
                            hardErrorCountNg = hardErrorCountNg,
                            softDataBitLength = softDataBitLength,
                            isEnd = isEnd,
                        )
                        viewModel?.appendDecodeOkEvent(event)
                        animateLatestReceiveViews()
                    }
                    type == EventType.DECODE_CRC_ERROR -> {
                        binding.waveform.drawer.addMarker(MARKER_COLOR_CRC_ERROR, 1f)
                        if (isEnd) {
                            binding.viewModel?.run {
                                currentFreq = 0f
                                update()
                            }
                        }

                    }
                    type == EventType.FREQ_DETECTED -> {
                        binding.waveform.drawer.addMarker(MARKER_COLOR_FREQ_DETECTED, 1f)
                        binding.viewModel?.run {
                            currentFreq = fAdjust
                            update()
                        }
                    }
                    isEnd -> {
                        binding.waveform.drawer.addMarker(MARKER_COLOR_END, 1f)
                        binding.viewModel?.run {
                            currentFreq = 0f
                            update()
                        }
                    }
                }

            }

            override fun onAudioError(error: AudioError) {
                Log.d("lsimple", "onAudioError: error=$error")
                _binding?.viewModel?.let {
                    it.message.set("error: ${error.message}")
                    it.messageColor.set(R.color.red)
                }
            }
        }, mainHandler)

        // スペクトラム表示の開始
        rawFloatRecorder = RawFloatRecorder()
        rawFloatRecorder?.callback = RawFloatRecorder.Callback { data ->
            mainHandler.post {
                _binding?.waveform?.drawer?.addData(data)
            }
        }
        rawFloatRecorder?.errorCallback = RawFloatRecorder.ErrorCallback { error ->
            Log.d("lsimple", "RawFloatRecorder error: $error")
            mainHandler.post {
                _binding?.viewModel?.let {
                    it.message.set("error: ${error.message}")
                    it.messageColor.set(R.color.red)
                }
            }
        }
        rawFloatRecorder?.start()

        // レベルメーター更新の開始
        levelMeterTimer = Timer().schedule(0, 100) {
            mainHandler.post {
                lSimple?.let {
                    val volumeRate = it.audioLevel()
                    _binding?.viewModel?.volume?.set((100.0 * volumeRate).toInt())
                }
            }
        }

        // 録音時間表示の開始
        timeUpdateTimer = Timer().schedule(0, 10) {
            mainHandler.post {
                _binding?.viewModel?.run {
                    updateTime(Date())
                }
            }
        }


        binding.viewModel?.run {
            running.set(true)
            message.set("録音中...")
            startDate = Date()
            stopDate = null
            updateTime(startDate!!)
            detectData = null
            detectFid = null
            detectSid = null
            detectType.set("-")
            bufferUsedString.set("-")
            currentFreq = 0f
            detectDataFreq = 0f
            hardErrorRate = null
            validDataRate = null
            update()
        }
    }

    private fun stop() {
        val lSimple = lSimple ?: return
        lSimple.stop()
        this.lSimple = null

        val now = Date()

        val savedFileNames = mutableListOf<String>()
        val hasWavLogSaveJob = tmpWavFile != null && tmpLogFile != null && wavFile != null && logFile != null
        val hasCsvSaveJob = pendingCsvFile != null && csvFile != null
        val expectedSaveJobs = (if (hasWavLogSaveJob) 1 else 0) + (if (hasCsvSaveJob) 1 else 0)
        var completedSaveJobs = 0

        fun completeSaveJob() {
            completedSaveJobs += 1
            if (completedSaveJobs == expectedSaveJobs && savedFileNames.isNotEmpty()) {
                mainHandler.post {
                    showSnackbar(buildSavedFilesMessage(savedFileNames))
                }
            }
        }

        wavRecorder?.stopRecord {

            Log.d("lsimple", "stopRecord")

            mainHandler.post {
                lifecycleScope.launch(fileDispatcher) {
                    logWriter?.write(now, "stop TS-E1")
                    logWriter?.stop()
                    logWriter = null

                    val wavTempFile = tmpWavFile
                    val logTempFile = tmpLogFile
                    val wavOutputName = wavFile
                    val logOutputName = logFile
                    if (wavTempFile == null || logTempFile == null || wavOutputName == null || logOutputName == null) {
                        if (hasWavLogSaveJob) {
                            completeSaveJob()
                        }
                        return@launch
                    }

                    // テンポラリファイルを実ファイルにコピー
                    val context = context ?: return@launch
                    copyFileToDownloads(context, wavTempFile.absolutePath, wavOutputName, "audio/wav")
                    savedFileNames += wavOutputName
                    wavFile = null
                    copyFileToDownloads(context, logTempFile.absolutePath, logOutputName, "text/plain")
                    savedFileNames += logOutputName
                    logFile = null

                    // テンポラリファイルを削除
                    wavTempFile.delete()
                    tmpWavFile = null
                    logTempFile.delete()
                    tmpLogFile = null

                    if (hasWavLogSaveJob) {
                        completeSaveJob()
                    }
                }
            }
            wavRecorder?.release()
            wavRecorder = null
        }

        lifecycleScope.launch(fileDispatcher) {
            csvWriter?.close()
            csvWriter = null

            val context = context ?: return@launch
            pendingCsvFile?.let { file ->
                if (file.exists()) {
                    val savedCsvName = csvFile ?: file.name.removePrefix("pending_")
                    copyFileToDownloads(context, file.absolutePath, savedCsvName, "text/csv")
                    savedFileNames += savedCsvName
                    file.delete()
                }
            }
            pendingCsvFile = null
            csvFile = null

            if (hasCsvSaveJob) {
                completeSaveJob()
            }
        }
        rawFloatRecorder?.callback = RawFloatRecorder.Callback { }
        rawFloatRecorder?.errorCallback = RawFloatRecorder.ErrorCallback { }
        rawFloatRecorder?.stop()
        rawFloatRecorder?.release()
        rawFloatRecorder = null

        levelMeterTimer?.cancel()
        levelMeterTimer = null
        timeUpdateTimer?.cancel()
        timeUpdateTimer = null

        _binding?.viewModel?.run {
            running.set(false)
            message.set("停止しました")

            messageColor.set(R.color.black)
            stopDate = Date()
            updateTime(stopDate!!)
            update()
        }
    }

    private fun clear(resetRecordTime: Boolean = false) {
        recreateReceiveHistoryAdapter()
        // waveformのクリア
        binding.waveform.drawer.setMode(WaveformDrawer.Mode.Spectrogram)

        binding.viewModel?.run {
            if (resetRecordTime || running.get() != true) {
                startDate = null
                stopDate = null
                recTimString.set("")
            }
            detectData = null
            detectFid = null
            detectSid = null
            trimsString.set("-")
            detectType.set("-")
            bufferUsed = 0f
            currentFreq = 0f
            detectDataFreq = 0f
            hardErrorRate = null
            validDataRate = null
            postErrorText.set("")
            detectTimestampString.set("")
            detectByteSizeString.set("")
            clearHistory()
            update()
        }
    }
    private fun resetPostError() {
        isCurrentPostErrorLowPriority = false
        _binding?.viewModel?.postErrorText?.set("")
    }

    private fun showPostError(message: String, lowPriority: Boolean, timeText: String) {
        val viewModel = _binding?.viewModel ?: return
        val hasCurrentError = !viewModel.postErrorText.get().isNullOrEmpty()

        if (lowPriority && hasCurrentError && !isCurrentPostErrorLowPriority) {
            return
        }

        viewModel.postErrorText.set("$message ($timeText)")
        isCurrentPostErrorLowPriority = lowPriority
    }

    private fun handlePostResult(result: Result<Unit>, timeText: String) {
        val error = result.exceptionOrNull() ?: return

        when (error) {
            is PostLimitExceededException -> {
                showPostError("送信エラー: 同時送信数の上限に達しました", lowPriority = true, timeText = timeText)
            }
            else -> {
                val message = error.message ?: getString(R.string.post_error_text)
                showPostError("送信エラー: $message", lowPriority = false, timeText = timeText)
            }
        }
    }
    private fun buildSavedFilesMessage(fileNames: List<String>): String {
        val fileTypes = mutableListOf<String>()
        if (fileNames.any { it.endsWith(".wav", ignoreCase = true) }) {
            fileTypes += "wav"
        }
        if (fileNames.any { it.endsWith(".txt", ignoreCase = true) }) {
            fileTypes += "log"
        }
        if (fileNames.any { it.endsWith(".csv", ignoreCase = true) }) {
            fileTypes += "csv"
        }
        return fileTypes.joinToString("、") + "ファイルを保存しました\n" + fileNames.joinToString("\n")
    }

    private fun recoverPendingCsvFiles() {
        val ctx = context ?: return
        val externalDir = ctx.getExternalFilesDir(null) ?: return
        val pendingFiles = externalDir.listFiles { file ->
            file.isFile && file.name.startsWith("pending_") && file.name.endsWith(".csv")
        } ?: return

        if (pendingFiles.isEmpty()) {
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var recoveredCount = 0
            pendingFiles.forEach { file ->
                if (!file.exists()) {
                    return@forEach
                }
                copyFileToDownloads(ctx, file.absolutePath, file.name.removePrefix("pending_"), "text/csv")
                if (file.delete()) {
                    recoveredCount += 1
                }
            }
            if (recoveredCount > 0) {
                mainHandler.post {
                    showSnackbar(getString(R.string.recover_csv_message, recoveredCount))
                }
            }
        }
    }

    private fun saveFileNameBase(date : Date): String {
        return TextUtils.join(
            "-",
            arrayOf(
                "TSE1Dev",
                DateFormat.format("yyyyMMdd-HHmmss", date),
                Build.MODEL.replace(" ", "")
            )
        )
    }

    private var playJob: Job? = null
    private fun play() {
        if(playJob != null) {
            return
        }
        val currentPlayer = MediaPlayer.create(requireContext(), R.raw.test) ?: return
        _binding?.lsimpleInformationView?.playButton?.isEnabled = false

        playJob = lifecycleScope.launch {
            try {
                currentPlayer.playSuspend()
            } finally {
                try {
                    currentPlayer.release()
                } catch (e: Exception) {
                    Log.w("sst", "release error: ${e.message}")
                }
                playJob = null
                _binding?.lsimpleInformationView?.playButton?.isEnabled = true
            }
        }
    }

    private fun stopPlay() {
        playJob?.cancel()
    }

    private fun makeSnackbar(message: String, duration: Int): Snackbar? {
        val root = _binding?.root ?: activity?.findViewById(android.R.id.content) ?: return null
        return Snackbar.make(root, message, duration).apply {
            _binding?.bottomToolsContainer?.let { anchorView = it }
        }
    }

    private fun showSnackbar(message: String) {
        if (!isAdded) return
        makeSnackbar(message, Snackbar.LENGTH_LONG)?.show()
    }

    // MediaPlayerの再生をsuspend関数で実装
    private suspend fun MediaPlayer.playSuspend() = suspendCancellableCoroutine { continuation ->
        setOnCompletionListener {
            if (continuation.isActive) {
                continuation.resume(this)
            }
        }

        setOnErrorListener { _, what, extra ->
            if (continuation.isActive) {
                continuation.cancel(
                    RuntimeException("MediaPlayer error what=$what extra=$extra")
                )
            }
            true
        }

        continuation.invokeOnCancellation {
            try {
                setOnCompletionListener(null)
                setOnErrorListener(null)
            } catch (e: Exception) {
                Log.w("sst", "listener clear error: ${e.message}")
            }
        }

        start()
    }

}