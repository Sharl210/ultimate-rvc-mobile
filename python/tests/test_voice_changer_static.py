from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / 'lib/main.dart'
VOICE_CHANGER = ROOT / 'lib/screens/voice_changer_screen.dart'
BRIDGE = ROOT / 'lib/services/voice_changer_bridge.dart'
MANIFEST = ROOT / 'android/app/src/main/AndroidManifest.xml'
MAIN_ACTIVITY = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/MainActivity.kt'
PLUGIN = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerPlugin.kt'
SERVICE = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerOverlayService.kt'
STATE = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerState.kt'
RECORDER = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerRecorder.kt'


def test_voice_changer_entry_is_below_realtime_inference():
    source = MAIN.read_text(encoding='utf-8')

    assert "import 'screens/voice_changer_screen.dart';" in source
    assert "title: Text('实时推理')" in source
    assert "title: Text('变声器模式')" in source
    assert source.index("title: Text('实时推理')") < source.index("title: Text('变声器模式')")
    assert 'VoiceChangerScreen(' in source


def test_voice_changer_screen_has_recording_mode_and_help_button():
    source = VOICE_CHANGER.read_text(encoding='utf-8')

    assert 'class VoiceChangerScreen extends StatefulWidget' in source
    assert "Text('录制模式')" in source
    assert "Text('实时变声')" not in source
    assert 'Icons.help_outline' in source
    assert 'showDialog<void>' in source
    assert '灰色' in source
    assert '绿色' in source
    assert '橙色' in source
    assert '天蓝色' in source
    assert '青绿色' in source
    assert '蓝色' in source
    assert '红色' in source
    assert '长按' in source
    assert '长按中止录制并回到灰色' in source
    assert '橙色显示处理进度' in source
    assert '天蓝色显示播放或试听倒计时' in source
    assert '蓝色显示处理后总时长' in source
    assert '红色显示播放剩余时间' in source
    assert '青绿色表示暂停' in source
    assert '继续后恢复红色' in source
    assert '黄色' not in source
    assert '录制和处理会占用推理资源' in source


def test_voice_changer_help_dialog_is_structured_and_scrolls_only_when_needed():
    source = VOICE_CHANGER.read_text(encoding='utf-8')

    help_source = source[source.index('  void _showHelp()'):source.index('  Widget _buildSelectedFile')]
    assert 'AlertDialog(' in help_source
    assert "title: const Text('录制模式')" in help_source
    assert 'LayoutBuilder(' in help_source
    assert 'ConstrainedBox(' in help_source
    assert 'SingleChildScrollView(' in help_source
    assert 'maxHeight:' in help_source
    assert 'content: const Text(' not in help_source
    for text in [
        '灰色是待机',
        '长按关闭悬浮窗',
        '绿色是录音并显示录制时长',
        '橙色显示处理进度',
        '蓝色显示处理后总时长',
        '点一下进入天蓝色试听倒计时',
        '长按结束本轮并清理文件，回到灰色',
        '试听结束回蓝色',
        '天蓝色显示播放或试听倒计时',
        '红色显示播放剩余时间',
        '青绿色表示暂停',
    ]:
        assert text in help_source


def test_voice_changer_recording_mode_has_persisted_parameters():
    source = VOICE_CHANGER.read_text(encoding='utf-8')

    assert "import 'package:file_picker/file_picker.dart';" in source
    assert "prefs.getDouble('voiceChangerRecordingNoiseGateDb') ?? 35.0" in source
    assert "prefs.getBool('voiceChangerRecordingOutputDenoiseEnabled') ?? true" in source
    assert "prefs.getBool('voiceChangerRecordingVocalRangeFilterEnabled') ?? true" in source
    assert "prefs.getDouble('voiceChangerPlaybackDelaySeconds') ?? 3.0" in source
    assert "prefs.getDouble('voiceChangerRecordingOverlayDiameter') ?? 100.0" in source
    assert "prefs.getDouble('voiceChangerRecordingOverlayOpacity') ?? 0.7" in source
    assert 'voiceChangerRecordingModelPath' in source
    assert 'VoiceChangerMode.recording' in source
    assert 'VoiceChangerMode.realtime' not in source
    assert 'voiceChangerRecordingPitchChange' in source
    assert 'voiceChangerRecordingFilterRadius' in source
    assert 'voiceChangerRecordingSampleRate' in source
    assert 'voiceChangerRecordingParallelChunkCount' not in source
    assert '_parallelChunkCount' not in source
    assert "prefs.getInt('voiceChangerRecordingParallelChunkCount')" not in source
    assert 'voiceChangerRecordingPerformancePlan' not in source
    assert 'FilePicker.platform.pickFiles' in source
    assert "endsWith('.onnx')" in source
    assert "endsWith('.mobile.index')" in source
    assert "label: Text(_modelPath == null ? '选择 .onnx 模型' : '更换 .onnx 模型')" in source
    assert "label: Text(_indexPath == null ? '选择 mobile.index' : '更换 mobile.index')" in source
    assert 'min: -24.0' in source
    assert 'max: 24.0' in source
    assert 'divisions: 96' in source
    assert 'min: -4.0' in source
    assert 'max: 4.0' in source
    assert 'divisions: 160' in source
    assert 'min: 100.0' in source
    assert 'max: 300.0' in source
    assert 'min: 0.2' in source
    assert 'max: 1.0' in source
    assert '处理声线参数' in source
    assert '悬浮控制' in source
    assert '悬浮窗不透明度' in source
    assert '悬浮窗透明度' not in source
    assert '播放延迟：${_playbackDelaySeconds.toStringAsFixed(0)} 秒' in source
    assert 'voiceChangerPlaybackDelaySeconds' in source
    assert 'min: 0.0' in source
    assert 'max: 10.0' in source
    assert 'Permission.microphone.request()' in source


def test_voice_changer_recording_mode_reuses_audio_inference_parameters_without_shared_keys():
    source = VOICE_CHANGER.read_text(encoding='utf-8')
    bridge = BRIDGE.read_text(encoding='utf-8')
    recorder = RECORDER.read_text(encoding='utf-8')

    for parameter in [
        'pitchChange',
        'indexRate',
        'formant',
        'filterRadius',
        'rmsMixRate',
        'protectRate',
        'sampleRate',
        'noiseGateDb',
        'outputDenoiseEnabled',
        'vocalRangeFilterEnabled',
    ]:
        assert f'{parameter}:' in source
        assert f'{parameter} =' in bridge
        assert f"'{parameter}': {parameter}" in bridge

    for label in [
        'Pitch（音调设置）',
        'Index Rate（索引强度）',
        'Formant（性别因子/声线粗细）',
        'Sample Rate（采样率）',
        'Filter Radius（音高滤波）',
        'RMS Mix（响度混合）',
        'Protect（辅音保护）',
        'Noise Gate（噪声过滤）',
        '降噪优化',
        '音域过滤',
    ]:
        assert label in source

    assert "prefs.getDouble('pitchChange')" not in source
    assert "prefs.getDouble('realtimePitchChange')" not in source
    assert 'val filterRadius: Int' in recorder
    assert 'val sampleRate: Int' in recorder
    assert 'val parallelChunkCount: Int' in recorder
    assert 'filterRadius = config.filterRadius,' in recorder
    assert 'sampleRate = config.sampleRate,' in recorder
    assert 'parallelChunkCount = config.parallelChunkCount,' in recorder
    assert 'outputDenoiseEnabled = config.outputDenoiseEnabled,' in recorder
    assert 'vocalRangeFilterEnabled = config.vocalRangeFilterEnabled,' in recorder
    assert 'sampleRate = SAMPLE_RATE,' not in recorder
    assert 'parallelChunkCount = 3,' not in recorder
    assert 'int parallelChunkCount = 1' in bridge


def test_voice_changer_opens_overlay_without_preemptively_claiming_engine():
    plugin = PLUGIN.read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')

    plugin_start = plugin[plugin.index('private fun startVoiceChangerOverlay'):plugin.index('    private fun stopVoiceChangerOverlay')]
    assert 'NativeModeGuard.tryEnter(NativeActiveMode.VOICE_CHANGER)' not in plugin_start
    assert 'result.error("INFERENCE_BUSY", NativeModeGuard.busyMessage(), null)' not in plugin_start
    assert 'appContext.startForegroundService(intent)' in plugin_start

    service_start = service[service.index('override fun onStartCommand'):service.index('        if (intent != null) {')]
    assert 'NativeModeGuard.tryEnter(NativeActiveMode.VOICE_CHANGER)' not in service_start
    assert 'stopSelf()' not in service_start
    assert 'return START_NOT_STICKY' not in service_start

    recording_start = service[service.index('private fun startRecording()'):service.index('    private fun stopRecordingAndProcess()')]
    assert 'NativeModeGuard.tryEnter(NativeActiveMode.VOICE_CHANGER)' in recording_start
    assert 'showOverlayToast(NativeModeGuard.busyMessage())' in recording_start
    assert recording_start.index('showOverlayToast(NativeModeGuard.busyMessage())') < recording_start.index('recorder?.startRecording()')


def test_voice_changer_protect_slider_uses_one_percent_steps():
    source = VOICE_CHANGER.read_text(encoding='utf-8')

    protect_source = source[source.index("Text('Protect（辅音保护）：${(_protectRate * 100).toStringAsFixed(0)}%')"):source.index('Align(', source.index("Text('Protect（辅音保护）：${(_protectRate * 100).toStringAsFixed(0)}%')"))]
    assert 'divisions: 100' in protect_source


def test_voice_changer_keeps_noise_gate_outside_advanced_parameters():
    source = VOICE_CHANGER.read_text(encoding='utf-8')

    noise_gate_text = "Text('Noise Gate（噪声过滤）：${_noiseGateDb.toStringAsFixed(0)} dB')"
    sample_rate_label = "labelText: 'Sample Rate（采样率）'"
    advanced_title = "title: const Text('高级参数')"
    assert noise_gate_text in source
    assert sample_rate_label in source
    assert source.index(noise_gate_text) < source.index(sample_rate_label)
    assert source.index(noise_gate_text) < source.index(advanced_title)
    between_noise_gate_and_sample_rate = source[source.index(noise_gate_text):source.index(sample_rate_label)]
    assert advanced_title not in between_noise_gate_and_sample_rate
    assert "Text('Filter Radius（音高滤波）" not in between_noise_gate_and_sample_rate
    advanced_source = source[source.index(advanced_title):source.index('Align(', source.index(advanced_title))]
    assert 'Noise Gate（噪声过滤）' not in advanced_source
    assert '性能优化方案' not in advanced_source


def test_voice_changer_exposes_output_denoise_and_vocal_range_filter_in_advanced_parameters():
    source = VOICE_CHANGER.read_text(encoding='utf-8')
    bridge = BRIDGE.read_text(encoding='utf-8')
    plugin = PLUGIN.read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')
    recorder = RECORDER.read_text(encoding='utf-8')

    assert 'bool _outputDenoiseEnabled = true;' in source
    assert 'bool _vocalRangeFilterEnabled = true;' in source
    assert "title: const Text('降噪优化')" in source
    assert "title: const Text('音域过滤')" in source
    assert "subtitle: const Text('只影响处理结果，不改原始录音文件')" in source
    assert 'value: _outputDenoiseEnabled' in source
    assert 'value: _vocalRangeFilterEnabled' in source
    assert 'outputDenoiseEnabled: _outputDenoiseEnabled,' in source
    assert 'vocalRangeFilterEnabled: _vocalRangeFilterEnabled,' in source
    assert "await prefs.setBool('voiceChangerRecordingOutputDenoiseEnabled', _outputDenoiseEnabled);" in source
    assert "await prefs.setBool('voiceChangerRecordingVocalRangeFilterEnabled', _vocalRangeFilterEnabled);" in source
    for parameter in ['outputDenoiseEnabled', 'vocalRangeFilterEnabled']:
        assert f'bool {parameter} = true' in bridge
        assert f"'{parameter}': {parameter}" in bridge
        assert f'putExtra("{parameter}", call.argument<Boolean>("{parameter}") ?: true)' in plugin
        assert f'{parameter} = intent.getBooleanExtra("{parameter}", true)' in service
        assert f'val {parameter}: Boolean' in recorder


def test_overlay_native_close_syncs_back_to_flutter_button_state():
    source = VOICE_CHANGER.read_text(encoding='utf-8')
    bridge = BRIDGE.read_text(encoding='utf-8')
    plugin = PLUGIN.read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')

    assert "import 'dart:async';" in source
    assert 'StreamSubscription<void>? _overlayStoppedSubscription;' in source
    assert '_overlayStoppedSubscription = _bridge.overlayStoppedStream().listen' in source
    assert 'setState(() => _overlayRunning = false);' in source
    assert '_overlayStoppedSubscription?.cancel();' in source
    assert "label: Text(_overlayRunning ? '关闭悬浮窗' : '打开悬浮窗')" in source

    assert "import 'dart:async';" in bridge
    assert 'static final StreamController<void> _overlayStoppedController = StreamController<void>.broadcast();' in bridge
    assert "case 'voiceChangerOverlayStopped':" in bridge
    assert '_overlayStoppedController.add(null);' in bridge
    assert 'Stream<void> overlayStoppedStream()' in bridge

    assert 'fun notifyOverlayStopped()' in plugin
    assert 'methodChannel?.invokeMethod("voiceChangerOverlayStopped", null)' in plugin
    assert 'VoiceChangerPlugin.notifyOverlayStopped()' in service


def test_voice_changer_declares_overlay_foreground_service_and_vibration():
    manifest = MANIFEST.read_text(encoding='utf-8')
    main_activity = MAIN_ACTIVITY.read_text(encoding='utf-8')
    bridge = BRIDGE.read_text(encoding='utf-8')
    plugin = PLUGIN.read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')

    assert 'android.permission.SYSTEM_ALERT_WINDOW' in manifest
    assert 'android.permission.FOREGROUND_SERVICE' in manifest
    assert 'android.permission.VIBRATE' in manifest
    assert '.VoiceChangerOverlayService' in manifest
    assert 'flutterEngine.plugins.add(VoiceChangerPlugin())' in main_activity
    assert "MethodChannel('ultimate_rvc_voice_changer')" in bridge
    assert 'startVoiceChangerOverlay' in bridge
    assert 'stopVoiceChangerOverlay' in bridge
    assert 'Settings.canDrawOverlays' in plugin
    assert 'WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY' in service
    assert 'IconOverlayButton' in service
    assert 'drawCircle' in service
    assert 'drawPath' in service
    assert 'overlayButton.text =' not in service
    assert 'overlayButton.text =' not in service


def test_recording_mode_overlay_state_machine_colors_and_long_press():
    state = STATE.read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')
    long_press_source = service[service.index('    private fun handleLongPress()'):service.index('    private fun saveProcessedAudioWithFeedback')]

    assert 'enum class VoiceChangerOverlayState' in state
    for state_name in ['IDLE', 'RECORDING', 'PROCESSING', 'READY', 'PLAYING', 'PAUSED', 'TRIAL_PLAYING', 'TRIAL_PAUSED']:
        assert state_name in state
    for color in ['Color.GRAY', 'Color.GREEN', 'PROCESSING_OVERLAY_COLOR', 'COUNTDOWN_OVERLAY_COLOR', 'PAUSED_OVERLAY_COLOR', 'Color.BLUE', 'Color.RED']:
        assert color in service
    assert 'if (countdownSecondsRemaining > 0) COUNTDOWN_OVERLAY_COLOR else when (state)' in service
    assert 'VoiceChangerOverlayState.PROCESSING -> PROCESSING_OVERLAY_COLOR' in service
    assert 'VoiceChangerOverlayState.PAUSED -> PAUSED_OVERLAY_COLOR' in service
    assert 'VoiceChangerOverlayState.TRIAL_PAUSED -> PAUSED_OVERLAY_COLOR' in service
    assert 'VoiceChangerOverlayState.PROCESSING_FAILED -> Color.YELLOW' in service
    assert 'color = Color.WHITE' in service
    assert 'onLongPress' in service
    assert 'vibrator.vibrate' in service
    assert 'transitionOnTap' in state
    assert 'transitionOnLongPress' in state
    assert 'VoiceChangerOverlayState.TRIAL_PLAYING -> Color.RED' in service
    assert 'VoiceChangerOverlayState.TRIAL_PAUSED -> PAUSED_OVERLAY_COLOR' in service
    assert 'VoiceChangerOverlayState.IDLE -> {' in long_press_source
    assert 'VoiceChangerOverlayState.PROCESSING -> {' in long_press_source
    processing_branch = long_press_source[long_press_source.index('VoiceChangerOverlayState.PROCESSING -> {'):long_press_source.index('VoiceChangerOverlayState.READY -> {')]
    assert 'vibrator.vibrate' in processing_branch
    assert 'cancelProcessing()' in processing_branch
    assert long_press_source.index('when (overlayState)') < long_press_source.index('vibrator.vibrate')


def test_recording_mode_processing_failure_turns_yellow_and_can_retry_or_discard():
    state = STATE.read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')
    recorder = RECORDER.read_text(encoding='utf-8')

    assert 'PROCESSING_FAILED' in state
    assert 'PROCESSING_FAILED -> PROCESSING' in state
    assert 'PROCESSING_FAILED -> IDLE' in state
    assert 'private fun retryProcessing()' in service
    assert 'VoiceChangerOverlayState.PROCESSING_FAILED -> retryProcessing()' in service
    assert 'private fun discardFailedProcessing()' in service
    assert 'VoiceChangerOverlayState.PROCESSING_FAILED -> {' in service
    failed_long_press = service[service.index('VoiceChangerOverlayState.PROCESSING_FAILED -> {'):service.index('VoiceChangerOverlayState.READY -> {')]
    assert 'discardFailedProcessing()' in failed_long_press
    failure_callback = service[service.index('onProcessingFailed = {'):service.index('onNormalPlaybackComplete = {')]
    assert 'showOverlayToast("处理出错了，可点击重试")' in failure_callback
    assert failure_callback.index('showOverlayToast("处理出错了，可点击重试")') < failure_callback.index('overlayState = VoiceChangerOverlayState.PROCESSING_FAILED')
    assert 'onProcessingFailed: (String) -> Unit,' in recorder
    assert 'fun retryProcessing()' in recorder
    assert 'onProcessingFailed(error.message ?: "处理出错")' in recorder


def test_overlay_interactions_show_toasts_and_idle_long_press_closes_overlay():
    service = SERVICE.read_text(encoding='utf-8')
    double_tap_source = service[service.index('    private fun handleDoubleTap()'):service.index('    private fun handleLongPress()')]
    long_press_source = service[service.index('    private fun handleLongPress()'):service.index('    private fun saveProcessedAudioWithFeedback')]

    for text in [
        '开始录制',
        'RVC处理中',
        '已取消录制',
        '已取消处理',
        '准备播放',
        '准备试听',
        '暂停播放',
        '继续播放',
        '暂停试听',
        '继续试听',
        '结束播放',
        '结束试听',
        '已取消播放',
        '已取消试听',
        '已关闭悬浮窗',
    ]:
        assert f'showOverlayToast("{text}")' in service

    assert 'private fun showOverlayToast(message: String, durationMs: Long = OVERLAY_HINT_SHORT_MS)' in service
    assert 'mainHandler.post {' in service
    assert 'Toast.makeText' not in service
    assert 'android.widget.Toast' not in service
    assert 'private var overlayHintView: TextView? = null' in service
    assert 'removeOverlayHint()' in service
    assert 'mainHandler.postDelayed(removeOverlayHintRunnable, durationMs)' in service
    assert 'WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY' in service
    assert 'WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE' in service
    assert 'showOverlayToast(NativeModeGuard.busyMessage())' in service

    assert 'VoiceChangerOverlayState.IDLE' not in double_tap_source
    assert 'VoiceChangerOverlayState.IDLE -> {' in long_press_source
    idle_branch = long_press_source[long_press_source.index('VoiceChangerOverlayState.IDLE -> {'):long_press_source.index('VoiceChangerOverlayState.RECORDING')]
    assert 'vibrator.vibrate' in idle_branch
    assert 'showOverlayToast("已关闭悬浮窗")' in idle_branch
    assert 'mainHandler.postDelayed({ stopSelf() }, OVERLAY_HINT_SHORT_MS)' in idle_branch
    assert 'Toast.makeText' not in idle_branch


def test_dragging_overlay_cancels_pending_tap_and_long_press_gestures():
    service = SERVICE.read_text(encoding='utf-8')
    drag_source = service[service.index('private inner class DragTouchListener'):service.index('    class IconOverlayButton')]
    button_source = service[service.index('    class IconOverlayButton'):service.index('    private companion object')]

    assert 'overlayButton.cancelGesture()' in drag_source
    assert 'view.cancelPendingInputEvents()' in drag_source
    assert 'return true' in drag_source[drag_source.index('MotionEvent.ACTION_MOVE'):drag_source.index('MotionEvent.ACTION_UP')]
    assert 'fun cancelGesture()' in button_source
    assert 'removeCallbacks(longPressRunnable)' in button_source
    assert 'removeCallbacks(singleTapRunnable)' in button_source
    assert 'gestureCancelled = true' in button_source
    assert 'if (!longPressTriggered && !gestureCancelled)' in button_source
    assert 'private val singleTapRunnable = Runnable { onTap?.invoke() }' in button_source
    assert 'postDelayed(singleTapRunnable, DOUBLE_TAP_TIMEOUT_MS)' in button_source
    double_tap_branch = button_source[button_source.index('if (now - lastTapAtMs <= DOUBLE_TAP_TIMEOUT_MS)'):button_source.index('} else {', button_source.index('if (now - lastTapAtMs <= DOUBLE_TAP_TIMEOUT_MS)'))]
    assert 'removeCallbacks(singleTapRunnable)' in double_tap_branch
    action_up_source = button_source[button_source.index('MotionEvent.ACTION_UP'):button_source.index('MotionEvent.ACTION_CANCEL')]
    assert 'onTap?.invoke()' not in action_up_source


def test_overlay_position_is_remembered_and_defaults_to_right_side_one_eighth_down():
    service = SERVICE.read_text(encoding='utf-8')

    assert 'getSharedPreferences("voice_changer_overlay", MODE_PRIVATE)' in service
    assert 'OVERLAY_POSITION_X_KEY' in service
    assert 'OVERLAY_POSITION_Y_KEY' in service
    assert 'loadOverlayPosition(params, size)' in service
    assert 'saveOverlayPosition(params.x, params.y)' in service
    assert 'displayMetrics.widthPixels - size - DEFAULT_OVERLAY_MARGIN_PX' in service
    assert 'displayMetrics.heightPixels / 8' in service


def test_voice_changer_runtime_guard_only_wraps_recording_and_processing():
    plugin = PLUGIN.read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')

    start_overlay = plugin[plugin.index('private fun startVoiceChangerOverlay'):plugin.index('    private fun stopVoiceChangerOverlay')]
    assert 'NativeModeGuard.tryEnter(NativeActiveMode.VOICE_CHANGER)' not in start_overlay
    assert 'result.error("INFERENCE_BUSY", NativeModeGuard.busyMessage(), null)' not in start_overlay

    assert 'private var voiceChangerModeToken: NativeModeGuardToken? = null' in service
    assert 'NativeModeGuard.tryEnter(NativeActiveMode.VOICE_CHANGER)' in service
    assert 'showOverlayToast(NativeModeGuard.busyMessage())' in service
    assert 'releaseVoiceChangerMode()' in service
    assert 'onProcessingComplete = {' in service
    assert 'releaseVoiceChangerMode()' in service[service.index('onProcessingComplete = {'):service.index('onNormalPlaybackComplete = {')]
    start_recording = service[service.index('private fun startRecording()'):service.index('    private fun stopRecordingAndProcess()')]
    assert 'val token = NativeModeGuard.tryEnter(NativeActiveMode.VOICE_CHANGER)' in start_recording
    assert 'showOverlayToast(NativeModeGuard.busyMessage())' in start_recording
    assert 'return' in start_recording[start_recording.index('if (token == null) {'):start_recording.index('voiceChangerModeToken = token')]
    assert 'VoiceChangerOverlayState.PROCESSING -> Unit' in service


def test_blue_and_red_playback_behaviour_matches_recording_mode_contract():
    state = STATE.read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')
    recorder = RECORDER.read_text(encoding='utf-8')

    assert 'READY -> TRIAL_PLAYING' in state
    assert 'PLAYING -> PAUSED' in state
    assert 'PAUSED -> PLAYING' in state
    assert 'TRIAL_PLAYING -> TRIAL_PAUSED' in state
    assert 'TRIAL_PAUSED -> TRIAL_PLAYING' in state
    assert 'READY -> IDLE' in state
    assert 'PLAYING -> IDLE' in state
    assert 'PAUSED -> IDLE' in state
    assert 'TRIAL_PLAYING -> READY' in state
    assert 'TRIAL_PAUSED -> READY' in state

    assert 'VoiceChangerOverlayState.READY -> startTrialPlayback()' in service
    assert 'VoiceChangerOverlayState.PLAYING, VoiceChangerOverlayState.PAUSED -> togglePlayback()' in service
    assert 'VoiceChangerOverlayState.TRIAL_PLAYING, VoiceChangerOverlayState.TRIAL_PAUSED -> togglePlayback()' in service
    assert 'VoiceChangerOverlayState.READY -> {' in service
    ready_long_press_source = service[service.index('VoiceChangerOverlayState.READY -> {'):service.index('VoiceChangerOverlayState.PLAYING, VoiceChangerOverlayState.PAUSED -> {')]
    assert 'recorder?.discardWorkingFiles()' in ready_long_press_source
    assert 'overlayState = VoiceChangerOverlayState.IDLE' in ready_long_press_source
    assert 'VoiceChangerOverlayState.PLAYING, VoiceChangerOverlayState.PAUSED -> {' in service
    assert 'VoiceChangerOverlayState.TRIAL_PLAYING, VoiceChangerOverlayState.TRIAL_PAUSED -> {' in service
    assert 'finishPlayback()' in service
    assert 'onNormalPlaybackComplete' in recorder
    assert 'onTrialPlaybackComplete' in recorder
    assert 'playOutput(deleteAfterPlayback = true)' in recorder
    assert 'playOutput(deleteAfterPlayback = false)' in recorder
    assert 'deleteWorkingFiles()' in recorder
    assert 'inputFile?.delete()' in recorder
    assert 'outputFile?.delete()' in recorder


def test_playback_delay_is_passed_to_overlay_and_drawn_as_countdown():
    source = VOICE_CHANGER.read_text(encoding='utf-8')
    bridge = BRIDGE.read_text(encoding='utf-8')
    plugin = PLUGIN.read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')
    recorder = RECORDER.read_text(encoding='utf-8')

    assert 'double _playbackDelaySeconds = 3.0;' in source
    assert "await prefs.setDouble('voiceChangerPlaybackDelaySeconds', _playbackDelaySeconds);" in source
    assert 'playbackDelaySeconds: _playbackDelaySeconds,' in source

    assert 'double playbackDelaySeconds = 3.0' in bridge
    assert "'playbackDelaySeconds': playbackDelaySeconds" in bridge
    assert 'putExtra("playbackDelaySeconds", call.argument<Double>("playbackDelaySeconds") ?: 3.0)' in plugin

    assert 'val playbackDelaySeconds: Double' in recorder
    assert 'playbackDelaySeconds = intent.getDoubleExtra("playbackDelaySeconds", 3.0).coerceIn(0.0, 10.0)' in service
    assert 'playbackDelaySeconds = playbackDelaySeconds,' in service
    assert 'private var countdownSecondsRemaining = 0' in service
    assert 'startPlaybackCountdown { recorder?.playNormal() }' in service
    assert 'startPlaybackCountdown { recorder?.playTrial() }' in service
    assert 'overlayButton.countdownSecondsRemaining = countdownSecondsRemaining' in service
    assert 'if (countdownSecondsRemaining > 0) return countdownSecondsRemaining.toString()' in service
    assert 'drawStatusText(canvas, radius)' in service


def test_overlay_controls_lock_while_running_and_use_requested_defaults():
    source = VOICE_CHANGER.read_text(encoding='utf-8')
    bridge = BRIDGE.read_text(encoding='utf-8')
    plugin = PLUGIN.read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')

    assert 'double _overlayDiameter = 100.0;' in source
    assert 'double _overlayOpacity = 0.7;' in source
    assert '_overlayDiameter = 100.0;' in source
    assert '_overlayOpacity = 0.7;' in source
    overlay_control_source = source[source.index("Text('悬浮窗直径") : source.index('FilledButton.icon')]
    assert '_overlayRunning ? null' in overlay_control_source
    assert 'updateVoiceChangerOverlayControls(' not in source

    assert 'double overlayDiameter = 100.0' in bridge
    assert 'double overlayOpacity = 0.7' in bridge
    assert 'Future<void> updateVoiceChangerOverlayControls' not in bridge
    assert "invokeMethod('updateVoiceChangerOverlayControls'" not in bridge

    assert '"updateVoiceChangerOverlayControls" -> updateVoiceChangerOverlayControls(call, result)' not in plugin
    assert 'putExtra("overlayDiameter", call.argument<Double>("overlayDiameter") ?: 100.0)' in plugin
    assert 'putExtra("overlayOpacity", call.argument<Double>("overlayOpacity") ?: 0.7)' in plugin
    assert 'ACTION_UPDATE_OVERLAY_CONTROLS' not in service


def test_overlay_displays_realtime_status_text_with_dynamic_font_fitting():
    service = SERVICE.read_text(encoding='utf-8')
    recorder = RECORDER.read_text(encoding='utf-8')

    assert 'private val mainHandler = Handler(Looper.getMainLooper())' in service
    assert 'recordingStartedAtMs = SystemClock.elapsedRealtime()' in service
    assert 'recordingElapsedSeconds = elapsedSecondsSince(recordingStartedAtMs)' in service
    assert 'processingProgressPercent = percent.roundToInt().coerceIn(0, 100)' in service
    assert 'processedDurationSeconds = recorder?.processedDurationSeconds() ?: 0' in service
    assert 'playbackRemainingSeconds = recorder?.playbackRemainingSeconds() ?: 0' in service
    assert 'formatSeconds(recordingElapsedSeconds)' in service
    assert '"${processingProgressPercent}%"' in service
    assert 'formatSeconds(processedDurationSeconds)' in service
    assert 'formatSeconds(playbackRemainingSeconds)' in service
    assert 'overlayButton.statusText = currentStatusText()' in service
    assert 'drawStatusText(canvas, radius)' in service
    assert 'color = Color.WHITE' in service
    assert 'STATUS_TEXT_COLOR' not in service
    assert 'while (textPaint.measureText(text) > maxTextWidth && textPaint.textSize > minTextSize)' in service
    assert 'textPaint.textSize -= 1f' in service

    assert 'private val onProcessingProgress: (Double) -> Unit,' in recorder
    assert 'progressCallback = { percent: Double, _: String -> onProcessingProgress(percent) }' in recorder
    assert 'progressCallback(percent, "")' in recorder
    assert 'fun processedDurationSeconds(): Int' in recorder
    assert 'fun playbackRemainingSeconds(): Int' in recorder
    assert 'track.playbackHeadPosition' in recorder


def test_recording_mode_double_tap_saves_processed_audio_with_feedback():
    service = SERVICE.read_text(encoding='utf-8')
    recorder = RECORDER.read_text(encoding='utf-8')

    assert 'onDoubleTap' in service
    assert 'saveProcessedAudioWithFeedback()' in service
    assert 'VoiceChangerOverlayState.READY' in service
    assert 'VoiceChangerOverlayState.PLAYING' in service
    assert 'VoiceChangerOverlayState.PAUSED' in service
    assert 'DOUBLE_TAP_SAVE_VIBRATION_MS = 20L' in service
    assert 'LONG_PRESS_VIBRATION_MS = 80L' in service
    assert 'showOverlayToast("保存成功：$savedPath", OVERLAY_HINT_LONG_MS)' in service
    assert 'showOverlayToast("保存失败：$targetPath' in service
    assert 'OVERLAY_HINT_LONG_MS)' in service
    assert 'targetSaveDisplayPath()' in recorder
    assert 'saveProcessedOutput()' in recorder
    assert 'Download/RVC_Convert/变声器模式/' in recorder
    assert 'Environment.DIRECTORY_DOWNLOADS + "/RVC_Convert/变声器模式"' in recorder


def test_recording_mode_records_processes_and_plays_via_shared_rvc_engine():
    recorder = RECORDER.read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')

    assert 'AudioRecord(' in recorder
    assert 'AudioTrack(' in recorder
    assert 'MediaRecorder.AudioSource.MIC' in recorder
    assert 'MediaRecorder.AudioSource.VOICE_RECOGNITION' not in recorder
    assert 'RemoteInferenceClient(context).infer(' in recorder
    assert 'RvcInferenceEngine(modelsDir).infer(' not in recorder
    assert 'noiseGateDb = 35.0' not in recorder
    assert 'CancellationToken()' in recorder
    assert 'cancelProcessing()' in recorder
    assert 'RvcInferenceRequest(' in recorder
    assert 'startRecording()' in service
    assert 'stopRecordingAndProcess()' in service
    assert 'togglePlayback()' in service
    assert 'stopRecordingWithoutProcessing()' in recorder
    assert 'VoiceChangerOverlayState.RECORDING -> {' in service
    assert 'recorder?.stopRecordingWithoutProcessing()' in service
    assert 'RootPerformanceSession.startIfEnabled(' in recorder
    assert 'config.enableRootPerformanceMode,' in recorder
    assert 'File(context.filesDir, "root_performance/node_cache.txt")' in recorder
    assert 'rootPerformanceSession?.restore()' in recorder
    assert 'readableTimestamp()' in recorder
    assert 'SimpleDateFormat("yyyy.MM.dd_HH.mm.ss", Locale.US).format(Date())' in recorder
    assert 'TimeZone.getTimeZone("Asia/Shanghai")' not in recorder
    assert 'recording_${System.currentTimeMillis()}.wav' not in recorder


def test_recording_mode_noise_gate_defaults_to_35db_outside_recorder():
    screen = (ROOT / 'lib/screens/voice_changer_screen.dart').read_text(encoding='utf-8')
    bridge = (ROOT / 'lib/services/voice_changer_bridge.dart').read_text(encoding='utf-8')
    plugin = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerPlugin.kt').read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')
    recorder = RECORDER.read_text(encoding='utf-8')

    assert 'double _noiseGateDb = 35.0;' in screen
    assert "prefs.getDouble('voiceChangerRecordingNoiseGateDb') ?? 35.0" in screen
    assert '_noiseGateDb = 35.0;' in screen
    assert 'double noiseGateDb = 35.0' in bridge
    assert 'call.argument<Double>("noiseGateDb") ?: 35.0' in plugin
    assert 'intent.getDoubleExtra("noiseGateDb", 35.0)' in service
    assert 'noiseGateDb = 35.0' not in recorder


def test_recording_mode_root_performance_only_wraps_rvc_processing():
    recorder = RECORDER.read_text(encoding='utf-8')

    start_recording_source = recorder[recorder.index('    fun startRecording()'):recorder.index('    fun stopRecordingAndProcess()')]
    process_source = recorder[recorder.index('    private fun processRecording(file: File)'):recorder.index('    fun targetSaveDisplayPath()')]
    play_source = recorder[recorder.index('    private fun playOutput(deleteAfterPlayback: Boolean)'):recorder.index('    private fun deleteWorkingFiles()')]

    assert 'RootPerformanceSession.startIfEnabled' not in start_recording_source
    assert 'RootPerformanceSession.startIfEnabled(' in process_source
    assert 'config.enableRootPerformanceMode,' in process_source
    assert 'File(context.filesDir, "root_performance/node_cache.txt")' in process_source
    assert 'RemoteInferenceClient(context).infer(' in process_source
    assert 'RvcInferenceEngine(modelsDir).infer(' not in process_source
    assert process_source.index('RootPerformanceSession.startIfEnabled') < process_source.index('RemoteInferenceClient(context).infer(')
    assert process_source.index('rootPerformanceSession?.restore()') < process_source.index('onProcessingComplete()')
    assert 'rootPerformanceSession = null' in process_source
    assert 'RootPerformanceSession.startIfEnabled' not in play_source


def test_recording_mode_reads_wav_sample_rate_and_resumes_existing_track():
    recorder = RECORDER.read_text(encoding='utf-8')

    assert 'private data class WavPcm(val samples: ShortArray, val sampleRate: Int)' in recorder
    assert 'val wavPcm = readWavPcm(file)' in recorder
    assert 'wavPcm.sampleRate' in recorder
    assert 'ByteBuffer.wrap(bytes, 24, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int' in recorder
    assert 'activeTrack.play()' in recorder
    toggle_source = recorder[recorder.index('fun togglePlayback()'):recorder.index('    fun playNormal()')]
    assert toggle_source.index('activeTrack.play()') < toggle_source.index('playOutput(deleteAfterPlayback = deleteAfterCurrentPlayback)')
