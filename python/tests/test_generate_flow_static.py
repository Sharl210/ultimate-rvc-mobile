from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GENERATE = ROOT / 'lib/screens/generate_screen.dart'
MAIN = ROOT / 'lib/main.dart'
RESULT = ROOT / 'lib/screens/result_screen.dart'
BRIDGE = ROOT / 'lib/services/rvc_bridge.dart'
PLUGIN = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/RVCPlugin.kt'
MODE_GUARD = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/NativeModeGuard.kt'
PUBSPEC = ROOT / 'pubspec.yaml'


def test_generate_screen_persists_parameters_and_has_reset_defaults_button():
    source = GENERATE.read_text(encoding='utf-8')
    pubspec = PUBSPEC.read_text(encoding='utf-8')

    assert 'shared_preferences:' in pubspec
    assert 'SharedPreferences.getInstance()' in source
    assert '_loadSavedParameters()' in source
    assert '_saveParameters()' in source
    assert '_resetParametersToDefaults()' in source
    assert '恢复默认' in source
    assert 'Colors.red' in source


def test_generate_screen_has_sample_rate_option_and_concise_term_explanations():
    source = GENERATE.read_text(encoding='utf-8')

    assert 'Sample Rate（采样率）' in source
    assert 'int _sampleRate = 48000;' in source
    assert 'static const _sampleRates = [48000, 44100, 40000];' in source
    assert "prefs.getInt('sampleRate') ?? 48000" in source
    assert '44100' in source
    assert '40000' in source
    assert 'Filter Radius（音高滤波）' in source
    assert 'RMS Mix（响度混合）' in source
    assert 'Protect（辅音保护）' in source


def test_generate_screen_exposes_parallel_chunk_count_defaulting_to_four():
    source = GENERATE.read_text(encoding='utf-8')

    assert '_parallelChunkCount' not in source
    assert "prefs.getInt('parallelChunkCount')" not in source
    assert "await prefs.setInt('parallelChunkCount'" not in source
    assert "Text('同时处理分块数：$_parallelChunkCount')" not in source
    assert "Text('Voice Chunk（固定分块）：200')" not in source
    assert 'parallelChunkCount: 1,' in source


def test_generate_screen_removes_runtime_device_advanced_option():
    source = GENERATE.read_text(encoding='utf-8')

    assert '_runtimeDevice' not in source
    assert '_runtimeDevices' not in source
    assert 'runtimeDevice' not in source
    assert "labelText: '推理设备'" not in source
    assert "value == 'gpu' ? 'GPU' : 'CPU'" not in source


def test_generation_result_is_shown_on_dedicated_result_screen():
    main_source = MAIN.read_text(encoding='utf-8')
    generate_source = GENERATE.read_text(encoding='utf-8')

    assert RESULT.exists()
    result_source = RESULT.read_text(encoding='utf-8')
    assert "import 'screens/result_screen.dart';" in main_source
    assert 'String? _generatedOutputPath;' in main_source
    assert 'ResultScreen(' in main_source
    assert 'onGenerationComplete: _onGenerationComplete' in main_source
    assert 'final void Function(String outputPath, Duration generationDuration) onGenerationComplete;' in generate_source
    assert 'widget.onGenerationComplete(outputPath, elapsedGenerationTime);' in generate_source
    assert 'ResultScreen' in result_source
    assert 'DeviceFileSource(widget.outputPath)' in result_source
    assert 'Share.shareXFiles' in result_source


def test_generate_screen_ignores_stale_generation_completion_callbacks():
    source = GENERATE.read_text(encoding='utf-8')

    assert 'int _activeGenerationRequestId = 0;' in source
    assert 'final requestId = ++_activeGenerationRequestId;' in source
    assert 'if (!mounted || requestId != _activeGenerationRequestId) return;' in source
    assert source.index('if (!mounted || requestId != _activeGenerationRequestId) return;') < source.index('widget.onGenerationComplete(outputPath, elapsedGenerationTime);')
    assert 'onProgress: (progress, status) {' in source
    progress_source = source[source.index('onProgress: (progress, status) {'):source.index('        },', source.index('onProgress: (progress, status) {'))]
    assert 'if (!mounted || requestId != _activeGenerationRequestId) return;' in progress_source


def test_generate_screen_exposes_explicit_stop_without_completing_stale_request():
    source = GENERATE.read_text(encoding='utf-8')
    bridge = BRIDGE.read_text(encoding='utf-8')

    assert 'Future<void> _stopGeneration() async' in source
    stop_source = source[source.index('Future<void> _stopGeneration() async'):source.index('  @override', source.index('Future<void> _stopGeneration() async'))]
    assert '++_activeGenerationRequestId;' in stop_source
    assert 'await _rvcBridge.stopInference();' in stop_source
    assert "widget.generationState.complete('生成已中止');" in stop_source
    assert "label: Text('终止生成')" in source
    assert 'Colors.red' in source[source.index("label: Text('终止生成')") - 500:source.index("label: Text('终止生成')") + 500]
    assert 'Future<void> stopInference() async' in bridge
    assert "invokeMethod('stopInference')" in bridge


def test_native_mode_conflict_only_reports_busy_message_without_stopping_active_job():
    plugin = PLUGIN.read_text(encoding='utf-8')
    guard = MODE_GUARD.read_text(encoding='utf-8')
    infer_source = plugin[plugin.index('private fun infer(call: MethodCall, result: Result)'):plugin.index('        val cancellationToken = CancellationToken()')]
    realtime_source = plugin[plugin.index('private fun startRealtimeInference(call: MethodCall, result: Result)'):plugin.index('        scope.launch {', plugin.index('private fun startRealtimeInference(call: MethodCall, result: Result)'))]

    assert 'return "当前有别的模式正在占用处理引擎"' in guard
    for source in [infer_source, realtime_source]:
        assert 'result.error("INFERENCE_BUSY", NativeModeGuard.busyMessage(), null)' in source
        assert 'activeCancellationToken?.cancel()' not in source
        assert 'activeInferenceJob?.cancel()' not in source
        assert 'realtimeSession?.stop()' not in source
        assert 'requestNativeMemoryCleanup()' not in source


def test_native_plugin_exposes_offline_inference_cancel_method():
    plugin = PLUGIN.read_text(encoding='utf-8')

    assert '"stopInference" -> stopInference(result)' in plugin
    assert 'private fun stopInference(result: Result)' in plugin
    stop_source = plugin[plugin.index('private fun stopInference(result: Result)'):plugin.index('    private fun startRealtimeInference', plugin.index('private fun stopInference(result: Result)'))]
    assert 'activeCancellationToken?.cancel()' in stop_source
    assert 'activeInferenceJob?.cancel()' not in stop_source
    assert 'realtimeSession?.stop()' not in stop_source
    assert 'requestNativeMemoryCleanup()' not in stop_source
    infer_source = plugin[plugin.index('private fun infer(call: MethodCall, result: Result)'):plugin.index('    private fun getVersion', plugin.index('private fun infer(call: MethodCall, result: Result)'))]
    assert 'cancellationToken.throwIfCancelled()' not in infer_source


def test_generate_screen_shows_fixed_inline_failure_message_without_progress_bar():
    source = GENERATE.read_text(encoding='utf-8')

    assert 'bool hasError = false;' in source
    assert 'void fail()' in source
    assert "status = '处理出错了，请重新生成';" in source
    assert 'color: Colors.red' in source[source.index("Text('处理出错了，请重新生成'") - 260:source.index("Text('处理出错了，请重新生成'") + 260]
    assert "widget.generationState.fail();" in source
    assert "SnackBar(content: Text('生成失败：$e'))" not in source

    idle_source = source[source.index('            ] else ...['):source.index('            SizedBox(height: 24),')]
    assert 'if (widget.generationState.hasError)' in idle_source
    assert 'LinearProgressIndicator' not in idle_source


def test_generate_screen_exposes_continue_unfinished_and_clears_cache_before_fresh_generation():
    source = GENERATE.read_text(encoding='utf-8')
    bridge = BRIDGE.read_text(encoding='utf-8')
    main_source = MAIN.read_text(encoding='utf-8')
    plugin_source = PLUGIN.read_text(encoding='utf-8')

    assert 'ResumableRvcJobMetadata? _resumableJobMetadata;' in source
    assert 'Future<void> _refreshResumableJobMetadata() async' in source
    assert 'await _rvcBridge.getResumableJobMetadata(' in source
    assert 'await _rvcBridge.clearResumableJobCache(' in source
    assert 'Future<void> _continueUnfinishedGeneration() async' in source
    assert "label: Text('继续未完成')" in source
    assert 'OutlinedButton.icon(' in source
    assert "content: const Text('当前任务与历史不一致，无法继续。\\n不做任何操作。')" in source
    clear_index = source.index('await _rvcBridge.clearResumableJobCache(')
    infer_index = source.index('final outputPath = await _rvcBridge.infer(')
    assert clear_index < infer_index
    assert 'Future<ResumableRvcJobMetadata?> getResumableJobMetadata({' in bridge
    assert 'Future<bool> clearResumableJobCache({' in bridge
    assert 'Future<ImportedFileHandle> importPickedFile({' in bridge
    assert 'Future<int> releaseImportedFile(String path) async' in bridge
    assert "await _rvcBridge.clearTempWorkspace('audio_inference');" in main_source
    assert "await _rvcBridge.clearTempWorkspace('voice_changer');" in main_source
    assert "final importedPath = await _importPickedFile(kind: 'audio', sourcePath: path);" in main_source
    assert "final importedPath = await _importPickedFile(kind: 'model', sourcePath: path);" in main_source
    assert "final importedPath = path == null ? null : await _importPickedFile(kind: 'index', sourcePath: path);" in main_source
    assert '"importPickedFile" -> importPickedFile(call, result)' in plugin_source
    assert '"releaseImportedFile" -> releaseImportedFile(call, result)' in plugin_source
    assert '"clearTempWorkspace" -> clearTempWorkspace(call, result)' in plugin_source
