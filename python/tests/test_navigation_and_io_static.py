from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / 'lib/main.dart'
SONG = ROOT / 'lib/screens/song_picker_screen.dart'
RESULT = ROOT / 'lib/screens/result_screen.dart'
INDEX_CONVERTER = ROOT / 'lib/screens/index_converter_screen.dart'
PARAMETER_GUIDE = ROOT / 'lib/screens/parameter_guide_screen.dart'
DECIBEL_METER = ROOT / 'lib/screens/decibel_meter_screen.dart'
MODEL_PICKER = ROOT / 'lib/screens/model_picker_screen.dart'
BRIDGE = ROOT / 'lib/services/rvc_bridge.dart'
PLUGIN = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/RVCPlugin.kt'
INFERENCE_SERVICE = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/InferenceProcessService.kt'
MANIFEST = ROOT / 'android/app/src/main/AndroidManifest.xml'
PUBSPEC = ROOT / 'pubspec.yaml'


def service_realtime_source() -> str:
    source = INFERENCE_SERVICE.read_text(encoding='utf-8')
    return source[source.index('private inner class ServiceRealtimeRvcSession'):source.index('    private class ShortRingBuffer')]


def test_main_uses_drawer_for_audio_conversion_and_mobile_index_pages():
    source = MAIN.read_text(encoding='utf-8')

    assert "import 'screens/index_converter_screen.dart';" in source
    assert "import 'screens/parameter_guide_screen.dart';" in source
    assert "import 'screens/realtime_inference_screen.dart';" in source
    assert "import 'screens/decibel_meter_screen.dart';" in source
    assert "import 'screens/pitch_detection_screen.dart';" in source
    assert "title: Text('音频推理')" in source
    assert "title: Text('实时推理')" in source
    assert "title: Text('分贝仪')" in source
    assert "title: Text('音高检测')" in source
    assert "title: Text('音频转换')" not in source
    assert "title: Text('mobile.index 转换教程')" in source
    assert "title: Text('参数解释')" in source
    assert 'Drawer(' in source
    assert 'ListTile(' in source
    assert '_scaffoldKey.currentState?.closeDrawer()' in source
    assert 'Navigator.of(context).maybePop()' not in source
    assert 'NavigationRail(' not in source
    assert 'Transform.translate(' not in source
    assert 'IndexConverterScreen(' in source
    assert 'ParameterGuideScreen(' in source
    assert 'RealtimeInferenceScreen(' in source
    assert 'VoiceChangerScreen(' in source
    assert 'DecibelMeterScreen(isActive: _moduleIndex == 5)' in source
    assert 'PitchDetectionScreen(isActive: _moduleIndex == 6)' in source


def test_audio_conversion_tabs_can_be_swiped_between_steps():
    source = MAIN.read_text(encoding='utf-8')

    assert 'late final PageController _audioPageController;' in source
    assert '_audioPageController = PageController(initialPage: _audioStepIndex);' in source
    assert 'PageView(' in source
    assert 'controller: _audioPageController' in source
    assert 'onPageChanged: _onAudioPageChanged' in source
    assert '_setAudioStep(index, animate: true);' in source


def test_audio_conversion_swipe_requires_deliberate_horizontal_gesture():
    source = MAIN.read_text(encoding='utf-8')

    assert 'NeverScrollableScrollPhysics()' in source
    assert 'onHorizontalDragUpdate: _handleAudioHorizontalDragUpdate' in source
    assert 'onHorizontalDragEnd: _handleAudioHorizontalDragEnd' in source
    assert '_audioSwipeDistanceThreshold = 96.0' in source
    assert '_audioSwipeVelocityThreshold = 800.0' in source
    assert 'details.primaryVelocity ?? 0.0' in source


def test_right_swipe_opens_drawer_without_breaking_audio_step_swipes():
    source = MAIN.read_text(encoding='utf-8')

    assert 'double _moduleHorizontalDragDistance = 0.0;' in source
    assert 'void _openDrawerFromSwipe()' in source
    assert '_scaffoldKey.currentState?.openDrawer();' in source
    assert 'void _handleModuleHorizontalDragUpdate(DragUpdateDetails details)' in source
    assert 'void _handleModuleHorizontalDragEnd(DragEndDetails details)' in source
    assert 'onHorizontalDragUpdate: _moduleIndex == 0 ? null : _handleModuleHorizontalDragUpdate' in source
    assert 'onHorizontalDragEnd: _moduleIndex == 0 ? null : _handleModuleHorizontalDragEnd' in source
    assert 'if (_audioStepIndex == 0 && shouldGoPrevious) {' in source
    assert '_openDrawerFromSwipe();' in source[source.index('if (_audioStepIndex == 0 && shouldGoPrevious) {'):source.index('final nextIndex = shouldGoPrevious')]


def test_main_requires_two_back_presses_to_exit_with_toast():
    source = MAIN.read_text(encoding='utf-8')
    bridge_source = BRIDGE.read_text(encoding='utf-8')
    plugin_source = PLUGIN.read_text(encoding='utf-8')

    assert 'DateTime? _lastBackPressedAt;' in source
    assert 'Future<void> _handleBackPressed() async' in source
    assert 'const backExitWindow = Duration(milliseconds: 900);' in source
    assert "await _rvcBridge.showToast('再按一次退出');" in source
    assert 'SnackBar(content: Text(\'再按一次退出\'))' not in source
    assert '_lastBackPressedAt = now;' in source
    back_source = source[source.index('Future<void> _handleBackPressed() async'):source.index('@override', source.index('Future<void> _handleBackPressed() async'))]
    assert 'if (_isDrawerOpen) {' in back_source
    assert '_scaffoldKey.currentState?.closeDrawer();' in back_source
    assert 'return;' in back_source
    assert 'SystemNavigator.pop();' in back_source
    assert 'PopScope(' in source
    assert 'canPop: false' in source
    assert 'onPopInvoked:' in source
    assert '_handleBackPressed();' in source
    assert 'Future<void> showToast(String message) async' in bridge_source
    assert "await _channel.invokeMethod('showToast', {'message': message});" in bridge_source
    assert '"showToast" -> showToast(call, result)' in plugin_source
    assert 'Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()' in plugin_source


def test_drawer_navigation_closes_drawer_without_triggering_back_exit_confirmation():
    source = MAIN.read_text(encoding='utf-8')

    for method in [
        '_openAudioConversion',
        '_openRealtimeInference',
        '_openVoiceChanger',
        '_openIndexConverter',
        '_openParameterGuide',
        '_openDecibelMeter',
        '_openPitchDetection',
    ]:
        method_source = source[source.index(f'void {method}()'):source.index('  }', source.index(f'void {method}()'))]
        assert '_scaffoldKey.currentState?.closeDrawer();' in method_source
        assert 'Navigator.of(context).maybePop()' not in method_source


def test_global_menu_button_is_centered_on_appbar_title_line():
    source = MAIN.read_text(encoding='utf-8')

    menu_source = source[source.index('SafeArea('):source.index('bottomNavigationBar:')]
    assert 'padding: const EdgeInsets.only(left: 12)' in menu_source
    assert 'SizedBox(' in menu_source
    assert 'height: kToolbarHeight' in menu_source
    assert 'Align(' in menu_source
    assert 'alignment: Alignment.centerLeft' in menu_source
    assert 'top: 8' not in menu_source


def test_song_and_model_picker_hero_icons_are_compact():
    song_source = SONG.read_text(encoding='utf-8')
    model_source = MODEL_PICKER.read_text(encoding='utf-8')

    assert 'Icons.music_note' in song_source
    assert 'Icons.mic' in model_source
    assert 'size: 60' in song_source
    assert 'size: 60' in model_source
    assert 'size: 120' not in song_source
    assert 'size: 120' not in model_source


def test_main_cleans_replaced_app_owned_cache_recordings_and_previous_outputs():
    source = MAIN.read_text(encoding='utf-8')

    assert "import 'dart:io';" in source
    assert "import 'package:path_provider/path_provider.dart';" in source
    assert 'Future<void> _deleteOwnedPathIfNeeded(String? path) async' in source
    assert 'getTemporaryDirectory()' in source
    assert 'getApplicationDocumentsDirectory()' in source
    assert "'/recordings/'" in source
    assert "'/outputs/'" in source
    assert 'await _deleteOwnedPathIfNeeded(previousSongPath);' in source
    assert 'await _deleteOwnedPathIfNeeded(previousModelPath);' in source
    assert 'await _deleteOwnedPathIfNeeded(previousIndexPath);' in source
    assert 'if (previousSongPath != importedPath)' in source
    assert 'if (previousModelPath != path)' in source
    assert 'if (previousIndexPath != path)' in source
    assert '_queueOutputForDeletionAfterNextSuccess(previousOutputPath);' in source
    assert 'if (outputToDelete != outputPath)' in source
    assert '_deleteOwnedPathIfNeeded(_selectedSongPath)' not in source[source.index('Future<void> _loadSavedState'):source.index('Future<void> _saveState')]


def test_song_picker_can_record_audio_directly():
    source = SONG.read_text(encoding='utf-8')

    assert "import 'package:permission_handler/permission_handler.dart';" in source
    assert 'final RVCBridge _rvcBridge = RVCBridge();' in source
    assert 'Permission.microphone.request()' in source
    assert 'await _rvcBridge.startRecording()' in source
    assert 'final recordingPath = await _rvcBridge.stopRecording()' in source
    assert "Text(_isRecording ? '停止录音' : '直接录音')" in source


def test_song_picker_has_audio_preview_with_progress_slider():
    source = SONG.read_text(encoding='utf-8')

    assert 'SingleChildScrollView(' in source
    assert "import 'package:audioplayers/audioplayers.dart';" in source
    assert 'final AudioPlayer _audioPlayer = AudioPlayer();' in source
    assert 'Duration _position = Duration.zero;' in source
    assert 'Duration _duration = Duration.zero;' in source
    assert '_audioPlayer.onPositionChanged.listen' in source
    assert '_audioPlayer.onDurationChanged.listen' in source
    assert 'DeviceFileSource(widget.selectedSongPath!)' in source
    assert '_loadSelectedAudioDuration();' in source
    assert 'await _audioPlayer.setSource(DeviceFileSource(widget.selectedSongPath!))' in source
    assert 'Slider(' in source
    assert 'value: _position.inMilliseconds' in source
    assert 'max: _duration.inMilliseconds' in source
    assert 'String _formatDuration(Duration duration)' in source
    assert "_formatDuration(_position)" in source
    assert "_formatDuration(_duration)" in source


def test_song_picker_keeps_change_and_clear_actions_inside_selected_file_card():
    source = SONG.read_text(encoding='utf-8')

    assert 'final VoidCallback? onSongCleared;' in source
    assert 'this.onSongCleared,' in source
    assert "Text('选择音频文件')" not in source
    assert "Text('选择要转换的音频')" not in source
    assert '支持：MP3、WAV、M4A、FLAC、OGG' in source
    assert "label: Text(widget.selectedSongPath != null ? '更换音频' : '选择音频')" in source
    assert "tooltip: '清除音频'" in source
    assert 'onPressed: widget.onSongCleared' in source

    selected_card_source = source[source.index("'已选择：'"):source.index('OutlinedButton.icon', source.index("'已选择：'"))]
    assert '支持：MP3、WAV、M4A、FLAC、OGG' in selected_card_source
    assert "label: Text(widget.selectedSongPath != null ? '更换音频' : '选择音频')" in selected_card_source
    assert "tooltip: '清除音频'" in selected_card_source


def test_result_screen_can_save_generated_audio_to_local_storage():
    source = RESULT.read_text(encoding='utf-8')

    assert "import 'package:permission_handler/permission_handler.dart';" in source
    assert 'final RVCBridge _rvcBridge = RVCBridge();' in source
    assert '_requestSavePermission();' in source
    assert 'Permission.audio.request()' in source
    assert 'Permission.storage.request()' in source
    assert 'await _rvcBridge.saveGeneratedAudio(widget.outputPath)' in source
    assert 'await _requestSavePermission();' in source
    assert 'final canSave = await _requestSavePermission();' not in source
    assert "SnackBar(content: Text('需要存储权限'))" not in source
    assert "label: Text('保存')" in source


def test_result_screen_has_playback_progress_slider():
    source = RESULT.read_text(encoding='utf-8')

    assert 'with WidgetsBindingObserver' in source
    assert 'WidgetsBinding.instance.addObserver(this);' in source
    assert 'WidgetsBinding.instance.removeObserver(this);' in source
    assert 'void didChangeAppLifecycleState(AppLifecycleState state)' in source
    assert '_pauseForBackground();' in source
    assert 'Future<void> _pauseForBackground() async' in source
    assert 'await _audioPlayer.pause();' in source
    assert '_isPlaying = false;' in source
    assert 'Duration _position = Duration.zero;' in source
    assert 'Duration _duration = Duration.zero;' in source
    assert '_audioPlayer.onPositionChanged.listen' in source
    assert '_audioPlayer.onDurationChanged.listen' in source
    assert '_loadOutputAudioDuration();' in source
    assert 'await _audioPlayer.setSource(DeviceFileSource(widget.outputPath))' in source
    assert 'Slider(' in source
    assert 'value: _position.inMilliseconds' in source
    assert 'max: _duration.inMilliseconds' in source
    assert 'String _formatDuration(Duration duration)' in source
    assert "_formatDuration(_position)" in source
    assert "_formatDuration(_duration)" in source


def test_audio_preview_and_result_playback_continue_across_pages_and_background():
    song_source = SONG.read_text(encoding='utf-8')
    result_source = RESULT.read_text(encoding='utf-8')

    for source in [song_source, result_source]:
        assert 'with WidgetsBindingObserver' in source
        assert 'WidgetsBinding.instance.addObserver(this);' in source
        assert 'WidgetsBinding.instance.removeObserver(this);' in source
        assert 'void didChangeAppLifecycleState(AppLifecycleState state)' in source
        assert 'AppLifecycleState.paused' in source
        assert 'AppLifecycleState.inactive' in source
        assert 'AppLifecycleState.hidden' in source
        assert 'Future<void> _pauseForBackground() async' in source
        assert '_userPausedAudio = true;' in source
        assert 'bool _userPausedAudio = false;' in source
        assert '_audioPlayer.onPlayerStateChanged.listen' in source
        assert 'if (_userPausedAudio && state == PlayerState.playing)' in source
        assert 'await _audioPlayer.pause();' in source
        assert 'await _audioPlayer.resume();' in source
        assert '_userPausedAudio = true;' in source
        assert '_userPausedAudio = false;' in source


def test_generation_elapsed_time_is_shown_during_generation_and_on_result_page():
    main_source = MAIN.read_text(encoding='utf-8')
    generate_source = (ROOT / 'lib/screens/generate_screen.dart').read_text(encoding='utf-8')
    result_source = RESULT.read_text(encoding='utf-8')

    assert "import 'dart:async';" in generate_source
    assert 'final Stopwatch _generationStopwatch = Stopwatch();' in generate_source
    assert 'Timer? _elapsedTimer;' in generate_source
    assert '_elapsedTimer = Timer.periodic' in generate_source
    assert "Text('生成用时：${_formatDuration(_elapsedGenerationTime)}')" in generate_source
    assert 'final void Function(String outputPath, Duration generationDuration) onGenerationComplete;' in generate_source
    assert 'widget.onGenerationComplete(outputPath, elapsedGenerationTime);' in generate_source

    assert 'Duration? _generationDuration;' in main_source
    assert "prefs.getInt('generationDurationMs')" in main_source
    assert "prefs.setInt('generationDurationMs', _generationDuration!.inMilliseconds)" in main_source
    assert 'void _onGenerationComplete(String outputPath, Duration generationDuration)' in main_source
    assert 'generationDuration: _generationDuration,' in main_source

    assert 'final Duration? generationDuration;' in result_source
    assert "Text('生成用时：${_formatDuration(widget.generationDuration!)}')" in result_source


def test_generate_screen_exposes_formant_shift_without_performance_plan_ui():
    source = (ROOT / 'lib/screens/generate_screen.dart').read_text(encoding='utf-8')

    assert 'double _formant = 0.0;' in source
    assert "prefs.getDouble('formant') ?? 0.0" in source
    assert "prefs.setDouble('formant', _formant)" in source
    assert 'formant: _formant,' in source
    assert "Text('Formant（性别因子/声线粗细）：${_formant.toStringAsFixed(2)}')" in source
    assert 'min: -4.0' in source
    assert 'max: 4.0' in source
    assert 'divisions: 160' in source
    assert 'min: -24.0' in source
    assert 'max: 24.0' in source
    assert 'divisions: 96' in source
    assert 'PerformancePlan' not in source
    assert "labelText: '性能优化方案'" not in source
    assert 'enableRootPerformanceMode' not in source


def test_main_persists_selected_files_result_and_current_page():
    source = MAIN.read_text(encoding='utf-8')

    assert "import 'package:shared_preferences/shared_preferences.dart';" in source
    assert '_loadSavedState()' in source
    assert '_saveState()' in source
    assert "prefs.getString('selectedSongPath')" in source
    assert "prefs.getString('selectedModelPath')" in source
    assert "prefs.getString('selectedIndexPath')" in source
    assert "prefs.getString('generatedOutputPath')" in source
    assert "prefs.getInt('moduleIndex')" in source
    assert "prefs.getInt('audioStepIndex')" in source
    assert "prefs.setString('generatedOutputPath', _generatedOutputPath!)" in source


def test_rvc_bridge_exposes_recording_saving_and_index_conversion_methods():
    source = BRIDGE.read_text(encoding='utf-8')

    assert 'double formant = 0.0' in source
    assert "'formant': formant" in source
    assert 'bool enableRootPerformanceMode = false' not in source
    assert "'enableRootPerformanceMode': enableRootPerformanceMode" not in source
    assert 'Future<void> startRealtimeInference({' in source
    assert 'Future<void> stopRealtimeInference() async' in source
    assert "invokeMethod('startRealtimeInference'" in source
    assert "invokeMethod('stopRealtimeInference')" in source
    assert 'Future<void> startRecording() async' in source
    assert "invokeMethod('startRecording')" in source
    assert 'Future<String> stopRecording() async' in source
    assert "invokeMethod('stopRecording')" in source
    assert 'Future<String> saveGeneratedAudio(String sourcePath) async' in source
    assert "invokeMethod('saveGeneratedAudio'" in source
    assert 'Future<String> convertIndex(String sourcePath) async' in source
    assert "invokeMethod('convertIndex'" in source
    assert 'Stream<double> decibelStream()' in source
    assert "EventChannel('ultimate_rvc_decibel')" in source


def test_decibel_meter_page_starts_sampling_only_while_visible():
    source = DECIBEL_METER.read_text(encoding='utf-8')
    plugin_source = PLUGIN.read_text(encoding='utf-8')

    assert 'class DecibelMeterScreen extends StatefulWidget' in source
    assert 'final bool isActive;' in source
    assert 'if (widget.isActive)' in source
    assert 'didUpdateWidget' in source
    assert '_stopMeter()' in source
    assert "title: Text('分贝仪')" in source
    assert 'Permission.microphone.request()' in source
    assert '_rvcBridge.decibelStream().listen' in source
    assert '_subscription?.cancel()' in source
    assert 'dispose()' in source
    assert '_currentDb' not in source
    assert "_status = '实时测量中';" in source
    assert 'void _stopMeter({bool updateStatus = true})' in source
    assert 'if (updateStatus && mounted)' in source
    assert '_stopMeter(updateStatus: false);' in source
    assert "'${shownDb.toStringAsFixed(1)} dB'" in source
    assert 'static const _smoothingFactor = 0.2;' in source
    assert '_displayDb = _displayDb + (value - _displayDb) * _smoothingFactor;' in source
    assert 'bool _isLocked = false;' in source
    assert 'final shownDb = _isLocked ? _lockedDb : _displayDb;' in source
    assert 'SvgPicture.string(' in source
    assert '_isLocked ? _lockSvg : _unlockSvg' in source
    assert '_toggleLock()' in source
    assert 'MeasurementChartCard(' in source
    assert 'FullscreenMeasurementChartPage(' in source
    assert 'showHeader: false' in source
    assert 'showFrame: false' in source
    assert 'expandToFit: true' in source
    assert "tooltip: '清空曲线'" in source
    assert '_stopMeter(updateStatus: false);' in source
    assert 'WidgetsBindingObserver' in source
    assert 'didChangeAppLifecycleState(AppLifecycleState state)' in source
    assert '_autoLockedByBackground' in source
    assert '"ultimate_rvc_decibel"' in plugin_source
    assert 'private var decibelSession: DecibelMeterSession? = null' in plugin_source
    assert 'startDecibelMeter(events)' in plugin_source
    assert 'stopDecibelMeter()' in plugin_source
    assert 'AudioRecord(' in plugin_source
    assert 'DecibelMeterSession { value -> runOnMain { events.success(value) } }' in plugin_source
    decibel_session_source = plugin_source[plugin_source.index('private class DecibelMeterSession'):plugin_source.index('private data class RootCommandResult')]
    assert 'eventSink.success' not in decibel_session_source
    assert 'sendDecibel(calculateDecibel(read))' in decibel_session_source
    assert '20.0 * kotlin.math.log10(rms.coerceAtLeast(1.0))' in plugin_source


def test_mobile_index_converter_screen_exists_and_calls_native_converter():
    source = INDEX_CONVERTER.read_text(encoding='utf-8')

    assert 'class IndexConverterScreen extends StatelessWidget' in source
    assert "title: Text('mobile.index 转换教程')" in source
    assert 'ultimate_rvc_mobile/python/convert_faiss_index.py' in source
    assert 'python3 ultimate_rvc_mobile/python/convert_faiss_index.py input.index output.mobile.index' in source
    assert 'Download/RVC_Convert' not in source
    assert '回到“音频转换”' not in source
    assert '文件放哪里' not in source
    assert '使用方式' not in source
    assert 'FilePicker' not in source
    assert 'RVCBridge' not in source
    assert 'FilledButton' not in source
    assert 'ElevatedButton.icon' not in source


def test_parameter_guide_screen_explains_generation_parameters():
    source = PARAMETER_GUIDE.read_text(encoding='utf-8')

    assert 'class ParameterGuideScreen extends StatelessWidget' in source
    assert "title: Text('参数解释')" in source
    for text in [
        'Pitch（音调设置）',
        'Formant（性别因子/声线粗细）',
        'Index Rate（检索特征占比）',
        'Filter Radius（音高滤波）',
        'RMS Mix（响度因子）',
        'Protect（辅音保护）',
        'Noise Gate（噪声过滤）',
        '降噪优化',
        '音域过滤',
        'Sample Rate（采样率）',
        '延时缓冲',
        '推荐值',
        '调高',
        '调低',
        '实时推理推荐值：35 dB',
        '处理时输入和输出都生效，不改原始录音文件',
        '突刺、电流感、包络噪声和刺耳齿音',
        '断续时先降低 Noise Gate',
        '默认开启',
        '推荐值：48000',
        '推荐值：6.0',
        '推荐值：0.0',
        '推荐值：10 秒；范围：0 到 60 秒',
        '播放延迟',
        '推荐值：3 秒；范围：0 到 10 秒',
        '中断生成',
        '离线生成时可点红色“终止生成”',
        '橙色处理阶段可长按悬浮按钮中断',
    ]:
        assert text in source
    assert '性能优化方案' not in source
    assert 'Voice Chunk（固定分块）' not in source
    assert '同时处理分块数' not in source
    assert '固定值：200 帧' not in source
    assert '这不是可调音质参数' not in source


def test_realtime_inference_screen_exists_with_warning_help_and_demo_controls():
    source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')
    pubspec = PUBSPEC.read_text(encoding='utf-8')

    assert 'shared_preferences:' in pubspec
    assert 'class RealtimeInferenceScreen extends StatefulWidget' in source
    assert "title: Text('实时推理')" in source
    assert 'SharedPreferences.getInstance()' in source
    assert '_loadSavedParameters()' in source
    assert '_saveParameters()' in source
    assert '_resetParametersToDefaults()' in source
    assert '恢复默认' in source
    assert "prefs.getString('realtimeModelPath')" in source
    assert "prefs.setString('realtimeModelPath', _modelPath!)" in source
    assert "prefs.getString('realtimeIndexPath')" in source
    assert "prefs.setString('realtimeIndexPath', _indexPath!)" in source
    assert 'Permission.microphone.request()' in source
    assert 'Icons.error_outline' in source
    assert '_showLimitWarning()' in source
    assert 'startRealtimeInference(' in source
    assert 'stopRealtimeInference()' in source
    assert '仅供演示' in source
    assert '无法指定输入输出设备' in source
    assert '没有实际用途' in source
    assert "Text('Formant（性别因子/声线粗细）：${_formant.toStringAsFixed(2)}')" in source
    assert 'min: -4.0' in source
    assert 'max: 4.0' in source
    assert 'divisions: 160' in source
    assert 'min: -24.0' in source
    assert 'max: 24.0' in source
    assert 'divisions: 96' in source
    assert "labelText: '性能优化方案'" not in source
    assert 'PerformancePlan' not in source


def test_realtime_screen_collapses_advanced_parameters_and_keeps_noise_gate_visible():
    source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')

    assert 'ExpansionTile(' in source
    assert "title: Text('高级参数')" in source
    assert "Text('Noise Gate（噪声过滤）：${_noiseGateDb.toStringAsFixed(0)} dB')" in source
    assert source.index("Text('Noise Gate（噪声过滤）：${_noiseGateDb.toStringAsFixed(0)} dB')") < source.index("title: Text('高级参数')")
    assert "Text('延时缓冲：${_delayBufferSeconds.toStringAsFixed(0)} 秒')" in source
    assert source.index("Text('延时缓冲：${_delayBufferSeconds.toStringAsFixed(0)} 秒')") < source.index("title: Text('高级参数')")
    assert source.index("Text('Noise Gate（噪声过滤）：${_noiseGateDb.toStringAsFixed(0)} dB')") < source.index("Text('延时缓冲：${_delayBufferSeconds.toStringAsFixed(0)} 秒')")
    assert source.index("Text('延时缓冲：${_delayBufferSeconds.toStringAsFixed(0)} 秒')") < source.index("DropdownButtonFormField<int>(")

    advanced_source = source[source.index("title: Text('高级参数')"):source.index('Align(', source.index("title: Text('高级参数')"))]
    for text in [
        "Text('RMS Mix（响度因子）：${(_rmsMixRate * 100).toStringAsFixed(0)}%')",
        "Text('Protect（辅音保护）：${(_protectRate * 100).toStringAsFixed(0)}%')",
        "Text('采样长度：${_sampleLength.toStringAsFixed(2)}')",
        "Text('淡入淡出长度：${_crossfadeLength.toStringAsFixed(2)}')",
        "Text('额外推理时长：${_extraInferenceLength.toStringAsFixed(2)}')",
    ]:
        assert text in advanced_source
    assert 'Noise Gate（噪声过滤）' not in advanced_source
    assert '延时缓冲' not in advanced_source
    assert '同时处理分块数' not in advanced_source
    assert '性能优化方案' not in advanced_source


def test_realtime_advanced_timing_parameters_are_editable_and_persisted():
    source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')

    assert "prefs.getDouble('realtimeSampleLength') ?? 6.0" in source
    assert '.clamp(6.0, 6.0)' not in source
    assert "await prefs.setDouble('realtimeSampleLength', _sampleLength);" in source
    assert "await prefs.setDouble('realtimeCrossfadeLength', _crossfadeLength);" in source
    assert "await prefs.setDouble('realtimeExtraInferenceLength', _extraInferenceLength);" in source
    assert 'sampleLength: _sampleLength,' in source
    assert 'crossfadeLength: _crossfadeLength,' in source
    assert 'extraInferenceLength: _extraInferenceLength,' in source

    sample_slider = source[source.index("Text('采样长度："):source.index("Text('淡入淡出长度：")]
    assert 'min: 1.0' in sample_slider
    assert 'max: 12.0' in sample_slider
    assert 'divisions: 22' in sample_slider

    crossfade_slider = source[source.index("Text('淡入淡出长度："):source.index("Text('额外推理时长：")]
    assert 'min: 0.0' in crossfade_slider
    assert 'max: 2.0' in crossfade_slider
    assert 'divisions: 20' in crossfade_slider

    extra_slider = source[source.index("Text('额外推理时长："):source.index('                      ],', source.index("Text('额外推理时长："))]
    assert 'min: 0.0' in extra_slider
    assert 'max: 5.0' in extra_slider
    assert 'divisions: 20' in extra_slider


def test_realtime_screen_updates_status_from_native_events_and_stops_immediately():
    source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')

    assert 'bool _isStopping = false;' in source
    assert "_status = '正在停止实时推理';" in source
    assert source.index("_status = '正在停止实时推理';") < source.index('await _rvcBridge.stopRealtimeInference();')
    assert '_isStopping = true;' in source
    assert '_isStopping = false;' in source
    assert "_status = '正在启动实时推理';" in source
    assert "final nextStatus = event['current_step'] ?? event['status'];" in source
    assert "final queuedOutputSamples = event['queued_output_samples'];" in source
    assert "final outputDelayTargetSamples = event['output_delay_target_samples'];" in source
    assert "final lastInferMs = event['last_infer_ms'];" in source
    assert "final pendingInputSamples = event['pending_input_samples'];" in source
    assert "final inputBacklogMs = event['input_backlog_ms'];" in source
    assert "final lastOutputSamples = event['last_output_samples'];" in source
    assert "final nonEmptyOutputCount = event['non_empty_output_count'];" in source
    assert "final outputChannelDropCount = event['output_channel_drop_count'];" in source
    assert "final audioTrackStarted = event['audio_track_started'];" in source
    assert "final audioTrackWriteCount = event['audio_track_write_count'];" in source
    assert "final audioTrackWrittenSamples = event['audio_track_written_samples'];" in source
    assert "_status = status;" in source
    assert 'onPressed: startLocked ? null : _toggleRealtimeInference' in source
    assert "Text(_isStopping ? '正在停止' : _isRunning ? '停止实时推理' : '启动实时推理')" in source


def test_realtime_locks_start_and_parameters_but_not_file_pickers_when_other_mode_is_running():
    main_source = MAIN.read_text(encoding='utf-8')
    source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')

    assert 'RealtimeInferenceScreen(' in main_source
    assert 'final bool otherModeRunning;' in source
    assert 'this.otherModeRunning = false,' in source
    assert 'final controlsLocked = _isRunning || widget.otherModeRunning;' in source
    assert 'final startLocked = _isStopping || widget.otherModeRunning;' in source
    assert 'if (widget.otherModeRunning && !_isRunning) return;' in source
    assert 'onPressed: startLocked ? null : _toggleRealtimeInference' in source

    parameter_source = source[source.index("Text('Pitch（音调设置）"):source.index('ElevatedButton.icon', source.index('SizedBox(height: 16)', source.index('Align(')))]
    assert 'onChanged: controlsLocked ? null' in parameter_source
    assert 'onPressed: controlsLocked ? null : _resetParametersToDefaults' in parameter_source

    model_card_source = source[source.index("'音色模型：'"):source.index("'索引文件：'")]
    index_card_source = source[source.index("'索引文件：'"):source.index("Text('Pitch（音调设置）")]
    assert 'widget.otherModeRunning' not in model_card_source
    assert 'widget.otherModeRunning' not in index_card_source
    assert 'onPressed: _isRunning ? null : _pickModel' in model_card_source
    assert 'onPressed: _isRunning ? null : _pickIndex' in index_card_source


def test_audio_inference_keeps_noise_gate_outside_advanced_parameters():
    source = (ROOT / 'lib/screens/generate_screen.dart').read_text(encoding='utf-8')
    noise_gate_text = "Text('Noise Gate（噪声过滤）：${_noiseGateDb.toStringAsFixed(0)} dB')"
    sample_rate_label = "labelText: 'Sample Rate（采样率）'"

    assert noise_gate_text in source
    assert sample_rate_label in source
    assert source.index(noise_gate_text) < source.index(sample_rate_label)
    assert source.index(noise_gate_text) < source.index("title: Text('高级参数')")
    between_noise_gate_and_sample_rate = source[source.index(noise_gate_text):source.index(sample_rate_label)]
    assert "title: Text('高级参数')" not in between_noise_gate_and_sample_rate
    assert "Text('Filter Radius（音高滤波）" not in between_noise_gate_and_sample_rate
    advanced_source = source[source.index("title: Text('高级参数')"):source.index('Align(', source.index("title: Text('高级参数')"))]
    assert 'Noise Gate（噪声过滤）' not in advanced_source


def test_generate_and_realtime_expose_output_denoise_and_vocal_range_filter_in_advanced_parameters():
    generate_source = (ROOT / 'lib/screens/generate_screen.dart').read_text(encoding='utf-8')
    realtime_source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')
    bridge_source = BRIDGE.read_text(encoding='utf-8')
    plugin_source = PLUGIN.read_text(encoding='utf-8')

    for source, prefix in [
        (generate_source, ''),
        (realtime_source, 'realtime'),
    ]:
        assert 'bool _outputDenoiseEnabled = true;' in source
        assert 'bool _vocalRangeFilterEnabled = true;' in source
        assert "SwitchListTile(" in source
        assert "title: Text('降噪优化')" in source
        assert "title: Text('音域过滤')" in source
        assert "subtitle: Text('只影响处理结果，不改原始录音文件')" in source
        assert '音频后处理' not in source
        assert 'value: _outputDenoiseEnabled' in source
        assert 'value: _vocalRangeFilterEnabled' in source
        assert 'outputDenoiseEnabled: _outputDenoiseEnabled,' in source
        assert 'vocalRangeFilterEnabled: _vocalRangeFilterEnabled,' in source
        key_prefix = f'{prefix}OutputDenoiseEnabled' if prefix else 'outputDenoiseEnabled'
        range_key_prefix = f'{prefix}VocalRangeFilterEnabled' if prefix else 'vocalRangeFilterEnabled'
        assert f"prefs.getBool('{key_prefix}') ?? true" in source
        assert f"prefs.getBool('{range_key_prefix}') ?? true" in source

    for parameter in ['outputDenoiseEnabled', 'vocalRangeFilterEnabled']:
        assert f'bool {parameter} = true' in bridge_source
        assert f"'{parameter}': {parameter}" in bridge_source
        assert f'val {parameter} = call.argument<Boolean>("{parameter}") ?: true' in plugin_source
        assert f'{parameter} = {parameter},' in plugin_source


def test_realtime_file_selection_cards_support_change_and_clear_in_place():
    source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')

    assert "tooltip: '清除模型'" in source
    assert "tooltip: '清除索引'" in source
    assert "label: Text(_modelPath == null ? '选择 .onnx 模型' : '更换 .onnx 模型')" in source
    assert "label: Text(_indexPath == null ? '选择 mobile.index' : '更换 mobile.index')" in source
    assert '_updateParameter(() => _modelPath = null)' in source
    assert '_updateParameter(() => _indexPath = null)' in source

    model_card_source = source[source.index("'音色模型：'"):source.index("'索引文件：'")]
    assert "label: Text(_modelPath == null ? '选择 .onnx 模型' : '更换 .onnx 模型')" in model_card_source
    assert "tooltip: '清除模型'" in model_card_source

    index_card_source = source[source.index("'索引文件：'"):source.index("Text('Pitch（音调设置）")]
    assert "label: Text(_indexPath == null ? '选择 mobile.index' : '更换 mobile.index')" in index_card_source
    assert "tooltip: '清除索引'" in index_card_source


def test_realtime_parameters_reach_android_session_and_rvc_request():
    bridge_source = BRIDGE.read_text(encoding='utf-8')
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    client_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/RemoteInferenceClient.kt').read_text(encoding='utf-8')
    service_source = INFERENCE_SERVICE.read_text(encoding='utf-8')

    for parameter in [
        'pitchChange',
        'indexRate',
        'formant',
        'rmsMixRate',
        'protectRate',
        'sampleLength',
        'sampleRate',
        'crossfadeLength',
        'extraInferenceLength',
        'delayBufferSeconds',
    ]:
        assert f"'{parameter}': {parameter}" in bridge_source

    assert 'val pitchChange = call.argument<Double>("pitchChange") ?: 0.0' in plugin_source
    assert 'val indexRate = call.argument<Double>("indexRate") ?: 0.75' in plugin_source
    assert 'val rmsMixRate = call.argument<Double>("rmsMixRate") ?: 0.25' in plugin_source
    assert 'val protectRate = call.argument<Double>("protectRate") ?: 0.33' in plugin_source
    assert 'val sampleRate = call.argument<Int>("sampleRate") ?: DEFAULT_OUTPUT_SAMPLE_RATE' in plugin_source
    assert 'val crossfadeLength = call.argument<Double>("crossfadeLength") ?: 0.0' in plugin_source
    assert 'val extraInferenceLength = call.argument<Double>("extraInferenceLength") ?: 0.0' in plugin_source
    assert 'val delayBufferSeconds = call.argument<Double>("delayBufferSeconds") ?: 0.0' in plugin_source
    assert 'pitchChange = pitchChange,' in plugin_source
    assert 'indexRate = indexRate,' in plugin_source
    assert 'rmsMixRate = rmsMixRate,' in plugin_source
    assert 'protectRate = protectRate,' in plugin_source
    assert 'sampleRate = sampleRate,' in plugin_source
    assert 'crossfadeLength = crossfadeLength,' in plugin_source
    assert 'extraInferenceLength = extraInferenceLength,' in plugin_source
    assert 'delayBufferSeconds = delayBufferSeconds,' in plugin_source
    assert 'MIN_REALTIME_SAMPLE_LENGTH_SECONDS = 1.0' in plugin_source
    assert 'MAX_REALTIME_SAMPLE_LENGTH_SECONDS = 12.0' in plugin_source
    assert 'putDouble(InferenceIpcProtocol.KEY_PITCH_CHANGE, pitchChange)' in client_source
    assert 'putDouble(InferenceIpcProtocol.KEY_INDEX_RATE, indexRate)' in client_source
    assert 'putDouble(InferenceIpcProtocol.KEY_RMS_MIX_RATE, rmsMixRate)' in client_source
    assert 'putDouble(InferenceIpcProtocol.KEY_PROTECT_RATE, protectRate)' in client_source
    streaming_source = service_source[service_source.index('openStreamingSession('):service_source.index('sampleLength = data.getDouble')]
    assert 'pitchChange = data.getDouble(InferenceIpcProtocol.KEY_PITCH_CHANGE)' in streaming_source
    assert 'indexRate = data.getDouble(InferenceIpcProtocol.KEY_INDEX_RATE)' in streaming_source
    assert 'rmsMixRate = data.getDouble(InferenceIpcProtocol.KEY_RMS_MIX_RATE)' in streaming_source
    assert 'protectRate = data.getDouble(InferenceIpcProtocol.KEY_PROTECT_RATE)' in streaming_source


def test_offline_and_realtime_inference_are_mutually_exclusive():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    infer_source = plugin_source[plugin_source.index('private fun infer(call: MethodCall, result: Result)'):plugin_source.index('    private fun getVersion', plugin_source.index('private fun infer(call: MethodCall, result: Result)'))]
    realtime_source = plugin_source[plugin_source.index('private fun startRealtimeInference(call: MethodCall, result: Result)'):plugin_source.index('    private fun stopRealtimeInference', plugin_source.index('private fun startRealtimeInference(call: MethodCall, result: Result)'))]
    session_source = plugin_source[plugin_source.index('private class RealtimeRvcSession'):plugin_source.index('    private class DecibelMeterSession')]

    assert 'NativeModeGuard.tryEnter(NativeActiveMode.AUDIO_INFERENCE)' in infer_source
    assert 'NativeModeGuard.tryEnter(NativeActiveMode.REALTIME_INFERENCE)' in realtime_source
    assert 'result.error("INFERENCE_BUSY", NativeModeGuard.busyMessage(), null)' in infer_source
    assert 'result.error("INFERENCE_BUSY", NativeModeGuard.busyMessage(), null)' in realtime_source
    assert 'modeToken.release()' in realtime_source
    assert 'onStop()' in session_source


def test_realtime_warning_dialog_explains_mobile_limitations():
    realtime_source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')

    assert 'showDialog<void>' in realtime_source
    assert "title: const Text('手机端限制')" in realtime_source
    assert 'Icons.error_outline' in realtime_source
    assert '仅供演示' in realtime_source
    assert '无法指定输入输出设备' in realtime_source
    assert '没有实际用途' in realtime_source
    assert '长按停止可中断实时处理' not in realtime_source
    assert 'onLongPress: _isRunning && !_isStopping ? _toggleRealtimeInference : null' not in realtime_source


def test_realtime_screen_exposes_audio_sample_rate_defaulting_to_48000():
    realtime_source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')
    bridge_source = BRIDGE.read_text(encoding='utf-8')
    plugin_source = PLUGIN.read_text(encoding='utf-8')

    assert 'int _sampleRate = 48000;' in realtime_source
    assert "prefs.getInt('realtimeSampleRate') ?? 48000" in realtime_source
    assert "await prefs.setInt('realtimeSampleRate', _sampleRate);" in realtime_source
    assert 'sampleRate: _sampleRate,' in realtime_source
    assert "labelText: '音频采样率'" in realtime_source
    assert 'double sampleLength = 6.0' in bridge_source
    assert 'int sampleRate = 48000' in bridge_source
    assert "'sampleRate': sampleRate" in bridge_source
    assert 'val sampleRate = call.argument<Int>("sampleRate") ?: DEFAULT_OUTPUT_SAMPLE_RATE' in plugin_source


def test_realtime_screen_exposes_parallel_chunk_count_defaulting_to_four():
    realtime_source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')
    bridge_source = BRIDGE.read_text(encoding='utf-8')
    plugin_source = PLUGIN.read_text(encoding='utf-8')

    assert '_parallelChunkCount' not in realtime_source
    assert "prefs.getInt('realtimeParallelChunkCount')" not in realtime_source
    assert "await prefs.setInt('realtimeParallelChunkCount'" not in realtime_source
    assert 'parallelChunkCount: 1,' in realtime_source
    assert "Text('同时处理分块数：$_parallelChunkCount')" not in realtime_source
    assert 'int parallelChunkCount = 1' in bridge_source
    assert "'parallelChunkCount': parallelChunkCount" in bridge_source
    assert 'val parallelChunkCount = call.argument<Int>("parallelChunkCount") ?: DEFAULT_PARALLEL_CHUNK_COUNT' in plugin_source
    assert 'parallelChunkCount = parallelChunkCount,' in plugin_source


def test_generation_progress_survives_page_switch_without_cancel_action():
    main_source = MAIN.read_text(encoding='utf-8')
    generate_source = (ROOT / 'lib/screens/generate_screen.dart').read_text(encoding='utf-8')
    bridge_source = BRIDGE.read_text(encoding='utf-8')
    plugin_source = PLUGIN.read_text(encoding='utf-8')

    assert 'RvcGenerationState' in main_source
    assert 'final RvcGenerationState generationState;' in generate_source
    assert 'IndexedStack(' in main_source
    assert 'Offstage(' not in main_source
    assert 'NativeModeGuard.tryEnter(NativeActiveMode.AUDIO_INFERENCE)' in plugin_source
    assert 'modeToken.release()' in plugin_source
    assert 'CancellationToken()' in plugin_source
    assert 'cancellationToken = cancellationToken,' in plugin_source
    assert 'inferenceRunId' in plugin_source
    assert 'run_id' in plugin_source
    assert 'final inferenceRunId = DateTime.now().microsecondsSinceEpoch;' in bridge_source
    assert "'inferenceRunId': inferenceRunId" in bridge_source
    assert 'progress.runId != inferenceRunId' in bridge_source
    assert 'rootPerformanceSession?.restore()' in plugin_source
    assert 'if (widget.generationState.isGenerating) return;' in generate_source
    assert 'Future<void> cancelInference() async' not in bridge_source
    assert "invokeMethod('cancelInference')" not in bridge_source
    assert '"cancelInference" -> cancelInference(result)' not in plugin_source
    assert 'private fun cancelInference(result: Result)' not in plugin_source
    assert 'Future<void> stopInference() async' in bridge_source
    assert "invokeMethod('stopInference')" in bridge_source
    assert '"stopInference" -> stopInference(result)' in plugin_source
    assert 'private fun stopInference(result: Result)' in plugin_source
    assert "'终止生成'" in generate_source
    assert "widget.generationState.status = '正在等待推理进程结束';" in generate_source
    assert 'cancelRequested' not in generate_source
    assert 'markCancelling' not in generate_source
    assert "widget.generationState.status = '正在中止生成';" in generate_source


def test_generate_and_realtime_expose_noise_gate_and_chunk_frame_controls():
    generate_source = (ROOT / 'lib/screens/generate_screen.dart').read_text(encoding='utf-8')
    realtime_source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')
    bridge_source = BRIDGE.read_text(encoding='utf-8')
    plugin_source = PLUGIN.read_text(encoding='utf-8')

    assert 'double _noiseGateDb = 35.0;' in generate_source
    assert 'int _sampleRate = 48000;' in generate_source
    assert '_parallelChunkCount' not in generate_source
    assert '_voiceChunkFrames' not in generate_source
    assert "prefs.getDouble('noiseGateDb') ?? 35.0" in generate_source
    assert "prefs.getInt('sampleRate') ?? 48000" in generate_source
    assert "prefs.getInt('parallelChunkCount')" not in generate_source
    assert "prefs.getInt('voiceChunkFrames')" not in generate_source
    assert "Text('Noise Gate（噪声过滤）：${_noiseGateDb.toStringAsFixed(0)} dB')" in generate_source
    assert "Text('Voice Chunk（固定分块）：200')" not in generate_source
    assert "Text('同时处理分块数：$_parallelChunkCount')" not in generate_source
    assert 'noiseGateDb: _noiseGateDb,' in generate_source
    assert 'parallelChunkCount: 1,' in generate_source

    assert 'double _noiseGateDb = 35.0;' in realtime_source
    assert "prefs.getDouble('realtimeNoiseGateDb') ?? 35.0" in realtime_source
    assert "Text('Noise Gate（噪声过滤）：${_noiseGateDb.toStringAsFixed(0)} dB')" in realtime_source
    assert 'noiseGateDb: _noiseGateDb,' in realtime_source

    assert 'double noiseGateDb = 35.0' in bridge_source
    assert 'int sampleRate = 48000' in bridge_source
    assert 'int parallelChunkCount = 1' in bridge_source
    assert 'voiceChunkFrames' not in bridge_source
    assert "'noiseGateDb': noiseGateDb" in bridge_source
    assert "'parallelChunkCount': parallelChunkCount" in bridge_source
    assert "'voiceChunkFrames': voiceChunkFrames" not in bridge_source

    assert 'val noiseGateDb = call.argument<Double>("noiseGateDb") ?: 35.0' in plugin_source
    assert 'val parallelChunkCount = call.argument<Int>("parallelChunkCount") ?: DEFAULT_PARALLEL_CHUNK_COUNT' in plugin_source
    assert 'noiseGateDb = noiseGateDb,' in plugin_source
    assert 'parallelChunkCount = parallelChunkCount,' in plugin_source
    assert 'DEFAULT_VOICE_CHUNK_FRAMES' not in plugin_source


def test_generate_and_realtime_protect_sliders_use_one_percent_steps():
    generate_source = (ROOT / 'lib/screens/generate_screen.dart').read_text(encoding='utf-8')
    realtime_source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')

    generate_protect_source = generate_source[generate_source.index("Text('Protect（辅音保护）：${(_protectRate * 100).toStringAsFixed(0)}%')"):generate_source.index('Align(', generate_source.index("Text('Protect（辅音保护）：${(_protectRate * 100).toStringAsFixed(0)}%')"))]
    realtime_protect_source = realtime_source[realtime_source.index("Text('Protect（辅音保护）：${(_protectRate * 100).toStringAsFixed(0)}%')"):realtime_source.index("Text('采样长度：${_sampleLength.toStringAsFixed(2)}')")]

    assert 'divisions: 100' in generate_protect_source
    assert 'divisions: 100' in realtime_protect_source


def test_model_picker_requires_mobile_index_file_for_optional_index():
    source = MODEL_PICKER.read_text(encoding='utf-8')

    assert "SnackBar(content: Text('请选择 mobile.index 文件'))" in source
    assert "endsWith('.mobile.index')" in source
    assert "label: Text(selectedIndexPath != null ? '更换 mobile.index' : '选择 mobile.index')" in source
    assert '支持：mobile.index 索引' in source
    index_card_source = source[source.index("'索引文件（可选）"):source.index("'当前音频：'")]
    assert '支持：.onnx 模型，mobile.index 索引' not in index_card_source
    assert index_card_source.index('支持：mobile.index 索引') < index_card_source.index("label: Text(selectedIndexPath != null ? '更换 mobile.index' : '选择 mobile.index')")


def test_model_picker_keeps_change_and_clear_actions_inside_file_cards():
    source = MODEL_PICKER.read_text(encoding='utf-8')

    assert 'final VoidCallback? onModelCleared;' in source
    assert 'this.onModelCleared,' in source
    hero_source = source[source.index('children: ['):source.index('if (selectedModelPath != null)')]
    assert "Text('选择音色模型')" not in hero_source
    assert "Text('选择 .onnx 模型文件')" not in source
    assert "tooltip: '清除模型'" in source
    assert "tooltip: '清除索引'" in source
    assert 'onPressed: onModelCleared' in source
    assert 'onPressed: () => onIndexSelected(null)' in source

    model_card_source = source[source.index("'已选择音色：'"):source.index("'索引文件（可选）")]
    assert "label: Text(selectedModelPath != null ? '更换模型' : '选择模型')" in model_card_source
    assert "tooltip: '清除模型'" in model_card_source

    index_card_source = source[source.index("'索引文件（可选）"):source.index("'当前音频：'")]
    assert "label: Text(selectedIndexPath != null ? '更换 mobile.index' : '选择 mobile.index')" in index_card_source
    assert "tooltip: '清除索引'" in index_card_source
    assert '支持：mobile.index 索引' in index_card_source
    assert '支持：.onnx 模型' not in index_card_source


def test_android_manifest_declares_recording_and_audio_storage_permissions():
    source = MANIFEST.read_text(encoding='utf-8')

    assert 'android.permission.RECORD_AUDIO' in source
    assert 'android.permission.READ_MEDIA_AUDIO' in source
    assert 'android.permission.WRITE_EXTERNAL_STORAGE' in source
    assert 'android:hardwareAccelerated="true"' in source
    assert 'android:largeHeap="true"' in source


def test_android_plugin_implements_recording_saving_and_index_conversion_channels():
    source = PLUGIN.read_text(encoding='utf-8')

    assert '"startRecording" -> startRecording(result)' in source
    assert '"stopRecording" -> stopRecording(result)' in source
    assert '"saveGeneratedAudio" -> saveGeneratedAudio(call, result)' in source
    assert '"convertIndex" -> convertIndex(call, result)' in source
    assert 'MediaRecorder.AudioSource.VOICE_RECOGNITION' in source
    assert 'File(appContext.filesDir, "recordings")' in source
    assert 'readableTimestamp()' in source
    assert 'SimpleDateFormat("yyyy.MM.dd_HH.mm.ss", Locale.US).format(Date())' in source
    assert 'TimeZone.getTimeZone("Asia/Shanghai")' not in source
    assert 'recording_${System.currentTimeMillis()}.m4a' not in source
    assert 'MIN_RECORDING_DURATION_MS' in source
    assert '录音至少需要 1 秒' in source
    assert 'MediaStore.Downloads.EXTERNAL_CONTENT_URI' in source
    assert 'Environment.DIRECTORY_DOWNLOADS + "/RVC_Convert"' in source
    assert 'Toast.makeText(appContext, "已保存在 $displayPath", Toast.LENGTH_LONG).show()' in source
    assert 'FEATURE_INDEX_MAGIC' in source
    assert 'File(appContext.filesDir, "indexes")' in source
    assert 'source.copyTo(output, overwrite = true)' in source


def test_android_plugin_implements_true_realtime_streaming_channels():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    service_source = INFERENCE_SERVICE.read_text(encoding='utf-8')

    assert '"startRealtimeInference" -> startRealtimeInference(call, result)' in plugin_source
    assert '"stopRealtimeInference" -> stopRealtimeInference(result)' in plugin_source
    assert 'openRealtimeSession(' in plugin_source
    assert 'import android.media.AudioRecord' in service_source
    assert 'import android.media.AudioTrack' in service_source
    assert 'AudioRecord(' in service_source
    assert 'AudioTrack(' in service_source
    assert 'read(audioBuffer, 0, realtimeHopFrames.coerceAtMost(audioBuffer.size))' in service_source
    assert 'writeAudioTrackFully(output, output.size)' in service_source
    assert 'check(written >= 0)' in service_source
    assert 'ServiceRealtimeRvcSession' in service_source


def test_audio_players_restart_source_after_natural_completion_before_replay():
    song_source = (ROOT / 'lib/screens/song_picker_screen.dart').read_text(encoding='utf-8')
    result_source = (ROOT / 'lib/screens/result_screen.dart').read_text(encoding='utf-8')

    for source in [song_source, result_source]:
        assert 'bool _playbackCompleted = false;' in source
        assert '_playbackCompleted = true;' in source
        assert 'if (_playbackCompleted) {' in source


def test_pitch_detection_persists_settings_and_samples_only_while_visible():
    source = (ROOT / 'lib/screens/pitch_detection_screen.dart').read_text(encoding='utf-8')

    assert 'SharedPreferences.getInstance()' in source
    assert "prefs.getString('pitchReferenceNote')" in source
    assert "prefs.setString('pitchReferenceNote', _referenceNoteText)" in source
    assert "prefs.getString('pitchChartMode')" in source
    assert 'if (widget.isActive)' in source
    assert 'didUpdateWidget' in source
    assert '_stopDetection()' in source
    assert "title: Text('音高检测')" in source
    assert 'displayNote,' in source
    assert "Hz'" in source
    assert 'MeasurementChartCard(' in source
    assert 'FullscreenMeasurementChartPage(' in source
    assert 'showHeader: false' in source
    assert 'showFrame: false' in source
    assert 'expandToFit: true' in source
    assert "tooltip: '清空曲线'" in source
    assert '_stopDetection(updateStatus: false);' in source
    assert "toStringAsFixed(1)" in source
    assert 'WidgetsBindingObserver' in source
    assert 'didChangeAppLifecycleState(AppLifecycleState state)' in source
    assert '_autoLockedByBackground' in source
    assert "String _status = '未启动';" in source
    assert 'ListQueue<double>' in source
    assert '_frequencyWindow' in source
    assert '_hasValidPitch' in source

def test_pitch_bridge_exposes_pitch_stream_and_native_session():
    bridge_source = BRIDGE.read_text(encoding='utf-8')
    plugin_source = PLUGIN.read_text(encoding='utf-8')

    assert "EventChannel('ultimate_rvc_pitch')" in bridge_source
    assert 'Stream<Map<String, dynamic>> pitchStream()' in bridge_source
    assert 'private lateinit var pitchChannel: EventChannel' in plugin_source
    assert 'startPitchDetection(events)' in plugin_source
    assert 'private class PitchDetectionSession' in plugin_source
    assert 'private fun estimateFrequencyHz(read: Int): PitchEstimate' in plugin_source
    assert 'differenceFunction' in plugin_source
    assert 'cumulativeMeanNormalizedDifference' in plugin_source
    assert 'zeroCrossings' not in plugin_source


def test_audio_players_restart_source_after_natural_completion_before_replay():
    song_source = (ROOT / 'lib/screens/song_picker_screen.dart').read_text(encoding='utf-8')
    result_source = (ROOT / 'lib/screens/result_screen.dart').read_text(encoding='utf-8')

    for source in [song_source, result_source]:
        assert 'bool _playbackCompleted = false;' in source
        assert '_playbackCompleted = true;' in source
        assert 'if (_playbackCompleted) {' in source
        assert 'await _audioPlayer.play(DeviceFileSource(' in source
        assert '_playbackCompleted = false;' in source
        if 'await _audioPlayer.resume();' in source:
            snippet = source[source.index('if (_playbackCompleted) {'):source.index('await _audioPlayer.resume();')]
            if 'return;' in snippet:
                assert 'return;' in snippet
        else:
            assert 'return;' in source[source.index('if (_playbackCompleted) {'):source.index('Future<void> _share() async')]


def test_pubspec_includes_permission_handler_for_runtime_permissions():
    source = PUBSPEC.read_text(encoding='utf-8')

    assert 'permission_handler:' in source
    assert 'flutter_svg:' in source
