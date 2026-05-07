package com.ultimatervc.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class VoiceChangerOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayButton: IconOverlayButton
    private lateinit var vibrator: Vibrator
    private var overlayState = VoiceChangerOverlayState.IDLE
    private var recorder: VoiceChangerRecorder? = null
    private var voiceChangerModeToken: NativeModeGuardToken? = null
    private var overlayDiameter = 100.0
    private var overlayOpacity = 0.7
    private var playbackDelaySeconds = 3.0
    private var playbackCountdownTimer: CountDownTimer? = null
    private var countdownSecondsRemaining = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayHintView: TextView? = null
    private val removeOverlayHintRunnable = Runnable { removeOverlayHint() }
    private var recordingStartedAtMs = 0L
    private var recordingElapsedSeconds = 0
    private var processingProgressPercent = 0
    private var processedDurationSeconds = 0
    private var playbackRemainingSeconds = 0
    private val statusTicker = object : Runnable {
        override fun run() {
            updateTimedStatus()
            mainHandler.postDelayed(this, STATUS_UPDATE_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        if (intent != null) {
            overlayDiameter = intent.getDoubleExtra("overlayDiameter", 100.0).coerceIn(100.0, 300.0)
            overlayOpacity = intent.getDoubleExtra("overlayOpacity", 0.7).coerceIn(0.2, 1.0)
            playbackDelaySeconds = intent.getDoubleExtra("playbackDelaySeconds", 3.0).coerceIn(0.0, 10.0)
            recorder = VoiceChangerRecorder(
                context = this,
                modelsDir = File(filesDir, "models"),
                config = VoiceChangerRecordingConfig(
                    modelPath = intent.getStringExtra("modelPath") ?: "",
                    indexPath = intent.getStringExtra("indexPath"),
                    pitchChange = intent.getDoubleExtra("pitchChange", 0.0),
                    indexRate = intent.getDoubleExtra("indexRate", 0.75),
                    formant = intent.getDoubleExtra("formant", 0.0),
                    rmsMixRate = intent.getDoubleExtra("rmsMixRate", 0.25),
                    protectRate = intent.getDoubleExtra("protectRate", 0.33),
                    filterRadius = intent.getIntExtra("filterRadius", 3),
                    sampleRate = intent.getIntExtra("sampleRate", 48_000),
                    noiseGateDb = intent.getDoubleExtra("noiseGateDb", 35.0),
                    outputDenoiseEnabled = intent.getBooleanExtra("outputDenoiseEnabled", true),
                    vocalRangeFilterEnabled = intent.getBooleanExtra("vocalRangeFilterEnabled", true),
                    parallelChunkCount = intent.getIntExtra("parallelChunkCount", 4),
                    playbackDelaySeconds = playbackDelaySeconds,
                    enableRootPerformanceMode = intent.getBooleanExtra("enableRootPerformanceMode", false),
                ),
                onProcessingProgress = { percent ->
                    processingProgressPercent = percent.roundToInt().coerceIn(0, 100)
                    updateButton()
                },
                onProcessingComplete = {
                    RemoteInferenceClient.stopInferenceProcessAndWait(this)
                    VoiceChangerPlugin.notifyOverlayProcessingChanged(false)
                    releaseVoiceChangerMode()
                    processingProgressPercent = 100
                    processedDurationSeconds = recorder?.processedDurationSeconds() ?: 0
                    overlayState = VoiceChangerOverlayState.READY
                    updateButton()
                },
                onProcessingFailed = {
                    RemoteInferenceClient.stopInferenceProcessAndWait(this)
                    VoiceChangerPlugin.notifyOverlayProcessingChanged(false)
                    releaseVoiceChangerMode()
                    showOverlayToast("处理出错了，可点击重试")
                    overlayState = VoiceChangerOverlayState.PROCESSING_FAILED
                    processingProgressPercent = 0
                    updateButton()
                },
                onNormalPlaybackComplete = {
                    overlayState = VoiceChangerOverlayState.IDLE
                    updateButton()
                },
                onTrialPlaybackComplete = {
                    overlayState = VoiceChangerOverlayState.READY
                    updateButton()
                },
            )
        }
        showOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        VoiceChangerPlugin.notifyOverlayStopped()
        VoiceChangerPlugin.notifyOverlayProcessingChanged(false)
        mainHandler.removeCallbacks(statusTicker)
        cancelPlaybackCountdown()
        mainHandler.removeCallbacks(removeOverlayHintRunnable)
        removeOverlayHint()
        recorder?.cancelProcessing()
        recorder = null
        releaseVoiceChangerMode()
        if (::overlayButton.isInitialized) {
            runCatching { windowManager.removeView(overlayButton) }
        }
        super.onDestroy()
    }

    private fun showOverlay() {
        if (::overlayButton.isInitialized) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        overlayButton = IconOverlayButton(this).apply {
            alpha = overlayOpacity.toFloat()
            onTap = { handleTap() }
            onDoubleTap = { handleDoubleTap() }
            onTripleTap = { handleTripleTap() }
            onLongPress = { handleLongPress() }
            setOnTouchListener(DragTouchListener())
        }
        val size = overlayDiameter.toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        loadOverlayPosition(params, size)
        windowManager.addView(overlayButton, params)
        mainHandler.post(statusTicker)
        updateButton()
    }

    private fun handleTap() {
        when (overlayState) {
            VoiceChangerOverlayState.IDLE -> startRecording()
            VoiceChangerOverlayState.RECORDING -> stopRecordingAndProcess()
            VoiceChangerOverlayState.PROCESSING -> pauseProcessing()
            VoiceChangerOverlayState.PROCESSING_PAUSED,
            VoiceChangerOverlayState.PROCESSING_FAILED -> retryProcessing()
            VoiceChangerOverlayState.READY -> startTrialPlayback()
            VoiceChangerOverlayState.PLAYING, VoiceChangerOverlayState.PAUSED -> togglePlayback()
            VoiceChangerOverlayState.TRIAL_PLAYING, VoiceChangerOverlayState.TRIAL_PAUSED -> togglePlayback()
        }
        updateButton()
    }

    private fun handleDoubleTap() {
        when (overlayState) {
            VoiceChangerOverlayState.READY,
            VoiceChangerOverlayState.PLAYING,
            VoiceChangerOverlayState.PAUSED,
            VoiceChangerOverlayState.TRIAL_PLAYING,
            VoiceChangerOverlayState.TRIAL_PAUSED -> saveProcessedAudioWithFeedback()
            else -> Unit
        }
    }

    private fun handleTripleTap() {
        when (overlayState) {
            VoiceChangerOverlayState.READY,
            VoiceChangerOverlayState.PLAYING,
            VoiceChangerOverlayState.PAUSED,
            VoiceChangerOverlayState.TRIAL_PLAYING,
            VoiceChangerOverlayState.TRIAL_PAUSED -> saveRawRecordingWithFeedback()
            else -> Unit
        }
    }

    private fun handleLongPress() {
        when (overlayState) {
            VoiceChangerOverlayState.IDLE -> {
                vibrator.vibrate(LONG_PRESS_VIBRATION_MS)
                showOverlayToast("已关闭悬浮窗")
                recorder?.discardWorkingFiles()
                RemoteInferenceClient.stopInferenceProcessAndWait(this)
                mainHandler.postDelayed({ stopSelf() }, OVERLAY_HINT_SHORT_MS)
            }
            VoiceChangerOverlayState.RECORDING -> {
                vibrator.vibrate(LONG_PRESS_VIBRATION_MS)
                stopRecordingWithoutProcessing()
            }
            VoiceChangerOverlayState.PROCESSING -> {
                vibrator.vibrate(LONG_PRESS_VIBRATION_MS)
                cancelProcessing()
            }
            VoiceChangerOverlayState.PROCESSING_PAUSED,
            VoiceChangerOverlayState.PROCESSING_FAILED -> {
                vibrator.vibrate(LONG_PRESS_VIBRATION_MS)
                discardFailedProcessing()
            }
            VoiceChangerOverlayState.READY -> {
                vibrator.vibrate(LONG_PRESS_VIBRATION_MS)
                showOverlayToast("结束播放")
                recorder?.discardWorkingFiles()
                RemoteInferenceClient.stopInferenceProcessAndWait(this)
                overlayState = VoiceChangerOverlayState.IDLE
            }
            VoiceChangerOverlayState.PLAYING, VoiceChangerOverlayState.PAUSED -> {
                vibrator.vibrate(LONG_PRESS_VIBRATION_MS)
                finishPlayback()
            }
            VoiceChangerOverlayState.TRIAL_PLAYING, VoiceChangerOverlayState.TRIAL_PAUSED -> {
                vibrator.vibrate(LONG_PRESS_VIBRATION_MS)
                finishPlayback()
            }
        }
        updateButton()
    }

    private fun saveProcessedAudioWithFeedback() {
        vibrator.vibrate(DOUBLE_TAP_SAVE_VIBRATION_MS)
        showOverlayToast("正在保存处理后的音频", OVERLAY_HINT_LONG_MS)
        val targetPath = recorder?.targetSaveDisplayPath() ?: "Download/RVC_Convert/变声器模式/processed.rvc.wav"
        try {
            val savedPath = recorder?.saveProcessedOutput() ?: error("暂无处理后的音频")
            showOverlayToast("保存成功：$savedPath", OVERLAY_HINT_LONG_MS)
        } catch (error: Exception) {
            showOverlayToast("保存失败：$targetPath，${error.message}", OVERLAY_HINT_LONG_MS)
        }
    }

    private fun saveRawRecordingWithFeedback() {
        vibrator.vibrate(TRIPLE_TAP_SAVE_VIBRATION_MS)
        showOverlayToast("正在保存原始录音", OVERLAY_HINT_LONG_MS)
        val targetPath = recorder?.targetSaveRawRecordingDisplayPath() ?: "Download/RVC_Convert/变声器模式/recording.wav"
        try {
            val savedPath = recorder?.saveRawRecording() ?: error("暂无原始录音")
            showOverlayToast("已保存原始录音：$savedPath", OVERLAY_HINT_LONG_MS)
        } catch (error: Exception) {
            showOverlayToast("原始录音保存失败：$targetPath，${error.message}", OVERLAY_HINT_LONG_MS)
        }
    }

    private fun startRecording() {
        val token = NativeModeGuard.tryEnter(NativeActiveMode.VOICE_CHANGER)
        if (token == null) {
            showOverlayToast(NativeModeGuard.busyMessage())
            return
        }
        voiceChangerModeToken = token
        overlayState = VoiceChangerOverlayState.RECORDING
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        recordingElapsedSeconds = 0
        showOverlayToast("开始录制")
        recorder?.startRecording()
    }

    private fun stopRecordingAndProcess() {
        val recorder = recorder
        if (recorder == null || !ensureVoiceChangerModeReady()) {
            recorder?.stopRecordingWithoutProcessing()
            releaseVoiceChangerMode()
            overlayState = VoiceChangerOverlayState.IDLE
            showOverlayToast(NativeModeGuard.busyMessage())
            updateButton()
            return
        }
        overlayState = VoiceChangerOverlayState.PROCESSING
        processingProgressPercent = 0
        VoiceChangerPlugin.notifyOverlayProcessingChanged(true)
        showOverlayToast("RVC处理中")
        recorder?.stopRecordingAndProcess()
    }

    private fun stopRecordingWithoutProcessing() {
        recorder?.stopRecordingWithoutProcessing()
        VoiceChangerPlugin.notifyOverlayProcessingChanged(false)
        releaseVoiceChangerMode()
        overlayState = VoiceChangerOverlayState.IDLE
        showOverlayToast("已取消录制")
    }

    private fun cancelProcessing() {
        showOverlayToast("正在取消处理")
        recorder?.cancelProcessing()
        RemoteInferenceClient.stopInferenceProcessAndWait(this)
        VoiceChangerPlugin.notifyOverlayProcessingChanged(false)
        releaseVoiceChangerMode()
        overlayState = VoiceChangerOverlayState.IDLE
        showOverlayToast("已取消处理")
    }

    private fun pauseProcessing() {
        showOverlayToast("正在暂停处理，可稍后继续")
        Thread({
            recorder?.pauseProcessing()
            RemoteInferenceClient.stopInferenceProcessAndWait(this)
            VoiceChangerPlugin.notifyOverlayProcessingChanged(false)
            releaseVoiceChangerMode()
            overlayState = VoiceChangerOverlayState.PROCESSING_PAUSED
            updateButton()
            showOverlayToast("处理已停止，可点击继续处理")
        }, "VoiceChangerPauseProcessor").start()
    }

    private fun retryProcessing() {
        showOverlayToast("正在继续处理")
        if (!ensureVoiceChangerModeReady()) {
            overlayState = VoiceChangerOverlayState.PROCESSING_PAUSED
            processingProgressPercent = 0
            showOverlayToast(NativeModeGuard.busyMessage())
            updateButton()
            return
        }
        val started = recorder?.retryProcessing() == true
        if (!started) {
            VoiceChangerPlugin.notifyOverlayProcessingChanged(false)
            releaseVoiceChangerMode()
            overlayState = VoiceChangerOverlayState.PROCESSING_FAILED
            showOverlayToast("继续处理失败，请重试")
            updateButton()
            return
        }
        overlayState = VoiceChangerOverlayState.PROCESSING
        VoiceChangerPlugin.notifyOverlayProcessingChanged(true)
        processingProgressPercent = recorder?.currentOverallProgress()?.roundToInt()?.coerceIn(0, 100) ?: processingProgressPercent
        showOverlayToast("继续处理")
    }

    private fun discardFailedProcessing() {
        showOverlayToast("正在清空本轮处理")
        recorder?.discardWorkingFiles()
        RemoteInferenceClient.stopInferenceProcessAndWait(this)
        VoiceChangerPlugin.notifyOverlayProcessingChanged(false)
        releaseVoiceChangerMode()
        overlayState = VoiceChangerOverlayState.IDLE
        showOverlayToast("已放弃处理")
    }

    private fun ensureVoiceChangerModeReady(): Boolean {
        if (voiceChangerModeToken != null) return true
        val token = NativeModeGuard.tryEnter(NativeActiveMode.VOICE_CHANGER) ?: return false
        voiceChangerModeToken = token
        return true
    }

    private fun startNormalPlayback() {
        overlayState = VoiceChangerOverlayState.PLAYING
        showOverlayToast("准备播放")
        startPlaybackCountdown { recorder?.playNormal() }
    }

    private fun startTrialPlayback() {
        overlayState = VoiceChangerOverlayState.TRIAL_PLAYING
        showOverlayToast("准备试听")
        startPlaybackCountdown { recorder?.playTrial() }
    }

    private fun togglePlayback() {
        if (countdownSecondsRemaining > 0) {
            val wasTrial = overlayState == VoiceChangerOverlayState.TRIAL_PLAYING || overlayState == VoiceChangerOverlayState.TRIAL_PAUSED
            cancelPlaybackCountdown()
            if (wasTrial) {
                showOverlayToast("已取消试听")
            } else {
                showOverlayToast("已取消播放")
            }
            return
        }
        val nextState = overlayState.transitionOnTap()
        when (overlayState) {
            VoiceChangerOverlayState.PLAYING -> showOverlayToast("暂停播放")
            VoiceChangerOverlayState.PAUSED -> showOverlayToast("继续播放")
            VoiceChangerOverlayState.TRIAL_PLAYING -> showOverlayToast("暂停试听")
            VoiceChangerOverlayState.TRIAL_PAUSED -> showOverlayToast("继续试听")
            else -> Unit
        }
        overlayState = nextState
        recorder?.togglePlayback()
    }

    private fun finishPlayback() {
        val wasTrial = overlayState == VoiceChangerOverlayState.TRIAL_PLAYING || overlayState == VoiceChangerOverlayState.TRIAL_PAUSED
        cancelPlaybackCountdown()
        recorder?.stopPlayback()
        overlayState = overlayState.transitionOnLongPress()
        if (wasTrial) {
            showOverlayToast("结束试听")
        } else {
            showOverlayToast("结束播放")
        }
    }

    private fun showOverlayToast(message: String, durationMs: Long = OVERLAY_HINT_SHORT_MS) {
        val show = {
            if (::windowManager.isInitialized) {
                mainHandler.removeCallbacks(removeOverlayHintRunnable)
                val hint = overlayHintView ?: TextView(this).apply {
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(28, 16, 28, 16)
                    background = GradientDrawable().apply {
                        setColor(0xDD222222.toInt())
                        cornerRadius = 36f
                    }
                }.also { view ->
                    overlayHintView = view
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = DEFAULT_OVERLAY_MARGIN_PX * 2
                    }
                    windowManager.addView(view, params)
                }
                hint.text = message
                mainHandler.postDelayed(removeOverlayHintRunnable, durationMs)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            show()
        } else {
            mainHandler.post(show)
        }
    }

    private fun removeOverlayHint() {
        val hint = overlayHintView ?: return
        overlayHintView = null
        if (::windowManager.isInitialized) {
            runCatching { windowManager.removeView(hint) }
        }
    }

    private fun startPlaybackCountdown(onComplete: () -> Unit) {
        cancelPlaybackCountdown()
        val seconds = playbackDelaySeconds.toInt()
        if (seconds <= 0) {
            onComplete()
            return
        }
        countdownSecondsRemaining = seconds
        updateButton()
        playbackCountdownTimer = object : CountDownTimer(seconds * 1_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                countdownSecondsRemaining = ((millisUntilFinished + 999L) / 1_000L).toInt()
                updateButton()
            }

            override fun onFinish() {
                countdownSecondsRemaining = 0
                updateButton()
                onComplete()
            }
        }.start()
    }

    private fun cancelPlaybackCountdown() {
        playbackCountdownTimer?.cancel()
        playbackCountdownTimer = null
        countdownSecondsRemaining = 0
        updateButton()
    }

    private fun releaseVoiceChangerMode() {
        voiceChangerModeToken?.release()
        voiceChangerModeToken = null
    }

    private fun updateTimedStatus() {
        when (overlayState) {
            VoiceChangerOverlayState.RECORDING -> recordingElapsedSeconds = elapsedSecondsSince(recordingStartedAtMs)
            VoiceChangerOverlayState.READY -> processedDurationSeconds = recorder?.processedDurationSeconds() ?: 0
            VoiceChangerOverlayState.PLAYING,
            VoiceChangerOverlayState.PAUSED,
            VoiceChangerOverlayState.TRIAL_PLAYING,
            VoiceChangerOverlayState.TRIAL_PAUSED -> playbackRemainingSeconds = recorder?.playbackRemainingSeconds() ?: 0
            else -> Unit
        }
        updateButton()
    }

    private fun elapsedSecondsSince(startedAtMs: Long): Int {
        if (startedAtMs <= 0L) return 0
        return ((SystemClock.elapsedRealtime() - startedAtMs) / 1_000L).toInt().coerceAtLeast(0)
    }

    private fun currentStatusText(): String? {
        if (countdownSecondsRemaining > 0) return countdownSecondsRemaining.toString()
        return when (overlayState) {
            VoiceChangerOverlayState.RECORDING -> formatSeconds(recordingElapsedSeconds)
            VoiceChangerOverlayState.PROCESSING -> "${processingProgressPercent}%"
            VoiceChangerOverlayState.PROCESSING_PAUSED -> "继续"
            VoiceChangerOverlayState.PROCESSING_FAILED -> "重试"
            VoiceChangerOverlayState.READY -> formatSeconds(processedDurationSeconds)
            VoiceChangerOverlayState.PLAYING,
            VoiceChangerOverlayState.PAUSED,
            VoiceChangerOverlayState.TRIAL_PLAYING,
            VoiceChangerOverlayState.TRIAL_PAUSED -> formatSeconds(playbackRemainingSeconds)
            else -> null
        }
    }

    private fun formatSeconds(seconds: Int): String {
        val bounded = seconds.coerceAtLeast(0)
        return "${bounded / 60}:${(bounded % 60).toString().padStart(2, '0')}"
    }

    private fun updateButton() {
        if (!::overlayButton.isInitialized) return
        overlayButton.post {
            overlayButton.state = overlayState
            overlayButton.countdownSecondsRemaining = countdownSecondsRemaining
            overlayButton.statusText = currentStatusText()
        }
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "变声器模式", NotificationManager.IMPORTANCE_LOW))
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID).setContentTitle("变声器模式").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("变声器模式").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        }
    }

    private fun loadOverlayPosition(params: WindowManager.LayoutParams, size: Int) {
        val preferences = getSharedPreferences("voice_changer_overlay", MODE_PRIVATE)
        if (preferences.contains(OVERLAY_POSITION_X_KEY) && preferences.contains(OVERLAY_POSITION_Y_KEY)) {
            params.x = preferences.getInt(OVERLAY_POSITION_X_KEY, 0)
            params.y = preferences.getInt(OVERLAY_POSITION_Y_KEY, 0)
            return
        }
        val displayMetrics = resources.displayMetrics
        params.x = displayMetrics.widthPixels - size - DEFAULT_OVERLAY_MARGIN_PX
        params.y = displayMetrics.heightPixels / 8
    }

    private fun saveOverlayPosition(x: Int, y: Int) {
        getSharedPreferences("voice_changer_overlay", MODE_PRIVATE)
            .edit()
            .putInt(OVERLAY_POSITION_X_KEY, x)
            .putInt(OVERLAY_POSITION_Y_KEY, y)
            .apply()
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = view.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - touchX
                    val deltaY = event.rawY - touchY
                    if (abs(deltaX) > DRAG_SLOP || abs(deltaY) > DRAG_SLOP) {
                        dragging = true
                        overlayButton.cancelGesture()
                        params.x = startX + deltaX.toInt()
                        params.y = startY + deltaY.toInt()
                        windowManager.updateViewLayout(view, params)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        view.cancelPendingInputEvents()
                        saveOverlayPosition(params.x, params.y)
                        return true
                    }
                }
            }
            return false
        }
    }

    class IconOverlayButton(context: android.content.Context) : View(context) {
        var state: VoiceChangerOverlayState = VoiceChangerOverlayState.IDLE
            set(value) {
                field = value
                invalidate()
            }
        var countdownSecondsRemaining = 0
            set(value) {
                field = value
                invalidate()
            }
        var statusText: String? = null
            set(value) {
                field = value
                invalidate()
            }
        var onTap: (() -> Unit)? = null
        var onDoubleTap: (() -> Unit)? = null
        var onTripleTap: (() -> Unit)? = null
        var onLongPress: (() -> Unit)? = null
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private val path = Path()
        private var lastTapAtMs = 0L
        private var pendingTapCount = 0
        private var downAtMs = 0L
        private var longPressTriggered = false
        private var gestureCancelled = false
        private val longPressRunnable = Runnable {
            longPressTriggered = true
            onLongPress?.invoke()
        }
        private val singleTapRunnable = Runnable {
            when (pendingTapCount) {
                1 -> onTap?.invoke()
                2 -> onDoubleTap?.invoke()
                3 -> onTripleTap?.invoke()
            }
            pendingTapCount = 0
            lastTapAtMs = 0L
        }

        fun cancelGesture() {
            removeCallbacks(longPressRunnable)
            removeCallbacks(singleTapRunnable)
            pendingTapCount = 0
            lastTapAtMs = 0L
            gestureCancelled = true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downAtMs = System.currentTimeMillis()
                    longPressTriggered = false
                    gestureCancelled = false
                    postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPressRunnable)
                    if (!longPressTriggered && !gestureCancelled) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapAtMs <= DOUBLE_TAP_TIMEOUT_MS) {
                            pendingTapCount = (pendingTapCount + 1).coerceAtMost(3)
                            lastTapAtMs = now
                            removeCallbacks(singleTapRunnable)
                            if (pendingTapCount >= 3) {
                                singleTapRunnable.run()
                            } else {
                                postDelayed(singleTapRunnable, DOUBLE_TAP_TIMEOUT_MS)
                            }
                        } else {
                            lastTapAtMs = now
                            pendingTapCount = 1
                            postDelayed(singleTapRunnable, DOUBLE_TAP_TIMEOUT_MS)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPressRunnable)
                    removeCallbacks(singleTapRunnable)
                    pendingTapCount = 0
                    lastTapAtMs = 0L
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = min(width, height) / 2f
            fillPaint.color = if (countdownSecondsRemaining > 0) COUNTDOWN_OVERLAY_COLOR else when (state) {
                VoiceChangerOverlayState.IDLE -> Color.GRAY
                VoiceChangerOverlayState.RECORDING -> Color.GREEN
                VoiceChangerOverlayState.PROCESSING -> PROCESSING_OVERLAY_COLOR
                VoiceChangerOverlayState.PROCESSING_PAUSED -> Color.YELLOW
                VoiceChangerOverlayState.PROCESSING_FAILED -> Color.YELLOW
                VoiceChangerOverlayState.READY -> Color.BLUE
                VoiceChangerOverlayState.PLAYING -> Color.RED
                VoiceChangerOverlayState.PAUSED -> PAUSED_OVERLAY_COLOR
                VoiceChangerOverlayState.TRIAL_PLAYING -> Color.RED
                VoiceChangerOverlayState.TRIAL_PAUSED -> PAUSED_OVERLAY_COLOR
            }
            canvas.drawCircle(width / 2f, height / 2f, radius, fillPaint)
            val text = statusText
            if (!text.isNullOrBlank()) {
                drawStatusText(canvas, radius)
            } else {
                drawIcon(canvas, radius)
            }
        }

        private fun drawCountdown(canvas: Canvas, radius: Float) {
            textPaint.textSize = radius * 0.72f
            val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(countdownSecondsRemaining.toString(), width / 2f, y, textPaint)
        }

        private fun drawStatusText(canvas: Canvas, radius: Float) {
            val text = statusText ?: return
            val maxTextWidth = radius * 1.55f
            val minTextSize = radius * 0.26f
            textPaint.textSize = radius * 0.58f
            while (textPaint.measureText(text) > maxTextWidth && textPaint.textSize > minTextSize) {
                textPaint.textSize -= 1f
            }
            val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, width / 2f, y, textPaint)
        }

        private fun drawIcon(canvas: Canvas, radius: Float) {
            iconPaint.strokeWidth = radius * 0.12f
            path.reset()
            when (state) {
                VoiceChangerOverlayState.IDLE -> canvas.drawCircle(width / 2f, height / 2f, radius * 0.28f, iconPaint)
                VoiceChangerOverlayState.RECORDING -> drawStop(canvas, radius)
                VoiceChangerOverlayState.PROCESSING -> drawBolt(canvas, radius)
                VoiceChangerOverlayState.PROCESSING_PAUSED,
                VoiceChangerOverlayState.PROCESSING_FAILED,
                VoiceChangerOverlayState.READY,
                VoiceChangerOverlayState.PAUSED,
                VoiceChangerOverlayState.TRIAL_PAUSED -> drawPlay(canvas, radius)
                VoiceChangerOverlayState.PLAYING,
                VoiceChangerOverlayState.TRIAL_PLAYING -> drawPause(canvas, radius)
            }
        }

        private fun drawPlay(canvas: Canvas, radius: Float) {
            path.moveTo(width / 2f - radius * 0.18f, height / 2f - radius * 0.3f)
            path.lineTo(width / 2f - radius * 0.18f, height / 2f + radius * 0.3f)
            path.lineTo(width / 2f + radius * 0.34f, height / 2f)
            path.close()
            canvas.drawPath(path, iconPaint)
        }

        private fun drawStop(canvas: Canvas, radius: Float) {
            val half = radius * 0.28f
            canvas.drawRect(width / 2f - half, height / 2f - half, width / 2f + half, height / 2f + half, iconPaint)
        }

        private fun drawPause(canvas: Canvas, radius: Float) {
            val barWidth = radius * 0.16f
            val barHeight = radius * 0.56f
            canvas.drawRect(width / 2f - radius * 0.28f, height / 2f - barHeight / 2f, width / 2f - radius * 0.28f + barWidth, height / 2f + barHeight / 2f, iconPaint)
            canvas.drawRect(width / 2f + radius * 0.12f, height / 2f - barHeight / 2f, width / 2f + radius * 0.12f + barWidth, height / 2f + barHeight / 2f, iconPaint)
        }

        private fun drawBolt(canvas: Canvas, radius: Float) {
            path.moveTo(width / 2f + radius * 0.08f, height / 2f - radius * 0.42f)
            path.lineTo(width / 2f - radius * 0.28f, height / 2f + radius * 0.08f)
            path.lineTo(width / 2f - radius * 0.02f, height / 2f + radius * 0.08f)
            path.lineTo(width / 2f - radius * 0.12f, height / 2f + radius * 0.42f)
            path.lineTo(width / 2f + radius * 0.28f, height / 2f - radius * 0.1f)
            path.lineTo(width / 2f + radius * 0.02f, height / 2f - radius * 0.1f)
            path.close()
            canvas.drawPath(path, iconPaint)
        }

        private fun drawWave(canvas: Canvas, radius: Float) {
            iconPaint.style = Paint.Style.STROKE
            path.moveTo(width / 2f - radius * 0.42f, height / 2f)
            path.cubicTo(width / 2f - radius * 0.22f, height / 2f - radius * 0.34f, width / 2f - radius * 0.06f, height / 2f + radius * 0.34f, width / 2f + radius * 0.12f, height / 2f)
            path.cubicTo(width / 2f + radius * 0.24f, height / 2f - radius * 0.22f, width / 2f + radius * 0.34f, height / 2f + radius * 0.22f, width / 2f + radius * 0.42f, height / 2f)
            canvas.drawPath(path, iconPaint)
            iconPaint.style = Paint.Style.FILL
        }
    }

    private companion object {
        const val CHANNEL_ID = "voice_changer_overlay"
        const val NOTIFICATION_ID = 2102
        const val LONG_PRESS_VIBRATION_MS = 80L
        const val DOUBLE_TAP_SAVE_VIBRATION_MS = 20L
        const val TRIPLE_TAP_SAVE_VIBRATION_MS = 30L
        const val OVERLAY_HINT_SHORT_MS = 2_000L
        const val OVERLAY_HINT_LONG_MS = 3_500L
        const val LONG_PRESS_TIMEOUT_MS = 360L
        const val DOUBLE_TAP_TIMEOUT_MS = 280L
        const val STATUS_UPDATE_INTERVAL_MS = 250L
        const val DRAG_SLOP = 8f
        const val DEFAULT_OVERLAY_MARGIN_PX = 24
        const val OVERLAY_POSITION_X_KEY = "overlay_position_x"
        const val OVERLAY_POSITION_Y_KEY = "overlay_position_y"
        const val PROCESSING_OVERLAY_COLOR = 0xFFFF9800.toInt()
        const val COUNTDOWN_OVERLAY_COLOR = 0xFF03A9F4.toInt()
        const val PAUSED_OVERLAY_COLOR = 0xFF26A69A.toInt()
    }
}
