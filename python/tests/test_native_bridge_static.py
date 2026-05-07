from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PLUGIN = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/RVCPlugin.kt'
ENGINE = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/RvcInferenceEngine.kt'
MODE_GUARD = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/NativeModeGuard.kt'
MANIFEST = ROOT / 'android/app/src/main/AndroidManifest.xml'
REMOTE_CLIENT = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/RemoteInferenceClient.kt'
INFERENCE_SERVICE = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/InferenceProcessService.kt'
JOB_STORE = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/ResumableInferenceJobStore.kt'


def service_realtime_source() -> str:
    source = INFERENCE_SERVICE.read_text(encoding='utf-8')
    return source[source.index('    private inner class ServiceRealtimeRvcSession'):source.index('    private fun sendProgress')]


def test_flutter_event_sink_is_dispatched_on_android_main_thread():
    source = PLUGIN.read_text(encoding='utf-8')

    assert 'Handler(Looper.getMainLooper())' in source
    assert 'runOnMain { eventSink?.success(progress) }' in source
    assert 'runOnMain { eventSink?.error("PYTHON_ERROR", error, null) }' in source


def test_android_inference_uses_onnx_engine_instead_of_python_fallback():
    source = PLUGIN.read_text(encoding='utf-8')
    infer_start = source.index('private fun infer(call: MethodCall, result: Result)')
    infer_source = source[infer_start:source.index('    private fun getVersion', infer_start)]

    assert 'RemoteInferenceClient(appContext).infer(' in infer_source
    assert 'RvcInferenceEngine(File(getModelsDirectory())).infer(' not in infer_source
    assert 'mainModule.callAttr(' not in infer_source


def test_android_inference_reads_optional_index_path_argument():
    source = PLUGIN.read_text(encoding='utf-8')

    assert 'val indexPath = call.argument<String>("indexPath")' in source
    assert 'indexPath = indexPath,' in source


def test_android_checks_bundled_runtime_models_instead_of_python_downloader():
    source = PLUGIN.read_text(encoding='utf-8')
    check_start = source.index('private fun checkModels(call: MethodCall, result: Result)')
    check_source = source[check_start:source.index('    private fun infer', check_start)]

    assert 'ensureBundledModelsReady()' in check_source
    assert 'downloadWeights.callAttr' not in check_source


def test_android_plugin_has_no_download_models_channel():
    source = PLUGIN.read_text(encoding='utf-8')

    assert '"downloadModels"' not in source
    assert 'private fun downloadModels' not in source


def test_android_initialization_and_inference_prepare_bundled_models():
    source = PLUGIN.read_text(encoding='utf-8')
    initialize_start = source.index('private fun initialize(call: MethodCall, result: Result)')
    initialize_source = source[initialize_start:source.index('    private fun checkModels', initialize_start)]
    infer_start = source.index('private fun infer(call: MethodCall, result: Result)')
    infer_source = source[infer_start:source.index('    private fun getVersion', infer_start)]

    assert 'ensureBundledModelsReady()' in initialize_source
    assert 'ensureBundledModelsReady()' in infer_source
    assert 'File(appContext.filesDir, "models")' in source
    assert 'assets/flutter_assets/assets/models/$modelName' in source
    assert 'flutter_assets/assets/models/$modelName' in source
    assert '"/data/data/com.ultimatervc.mobile/files/models"' not in source


def test_android_inference_engine_has_no_unfinished_pipeline_placeholder():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'ONNX Runtime RVC 推理管线尚未完成' not in source
    assert 'UnsupportedOperationException' not in source


def test_android_inference_engine_runs_real_onnx_voice_model_and_writes_wav():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'voiceSession.run(tensors, runOptions)' in source
    assert 'phone_lengths' in source
    assert 'pitchf' in source
    assert 'rnd' in source
    assert 'writeWav(outputPath, filteredAudio, request.sampleRate)' in source
    assert 'MIN_RMVPE_FRAMES = 32' in source


def test_android_rmvpe_validation_frame_count_is_aligned_for_unet_concat():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'alignRmvpeFrameCount(' in source
    assert 'RMVPE_FRAME_ALIGNMENT = 32' in source
    assert 'alignRmvpeFrameCount(max(MIN_RMVPE_FRAMES, targetFrameCount))' in source


def test_android_voice_model_inference_runs_fixed_200_frame_chunks():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'VOICE_CHUNK_FRAMES = 200' in source
    assert 'VOICE_CENTER_FRAMES = 80' in source
    assert 'VOICE_CONTEXT_FRAMES = (VOICE_CHUNK_FRAMES - VOICE_CENTER_FRAMES) / 2' in source
    assert 'requestedVoiceChunkFrames.coerceIn' not in source
    assert 'for (centerStart in 0 until frameCount step VOICE_CENTER_FRAMES)' in source
    assert 'val chunkStart = (centerStart - VOICE_CONTEXT_FRAMES).coerceAtLeast(0)' in source
    assert 'val chunkFrameCount = min(VOICE_CHUNK_FRAMES, frameCount - chunkStart)' in source
    assert 'copyFrameSlice(phone, chunkStart, chunkFrameCount, VOICE_CHUNK_FRAMES, HUBERT_FEATURE_SIZE)' in source
    assert 'copyLongSlice(pitch, chunkStart, chunkFrameCount, VOICE_CHUNK_FRAMES)' in source
    assert 'copyFloatSlice(pitchf, chunkStart, chunkFrameCount, VOICE_CHUNK_FRAMES)' in source
    assert 'runVoiceChunk(' in source
    assert 'appendCrossfadedChunkAudio(chunkAudio, chunk.centerOffsetFrames, chunk.centerFrameCount, VOICE_CHUNK_FRAMES, outputChunks)' in source


def test_android_inference_uses_rvc_100hz_features_and_configurable_sample_rate():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    engine_source = ENGINE.read_text(encoding='utf-8')
    infer_source = engine_source[engine_source.index('    fun infer(request: RvcInferenceRequest): String'):engine_source.index('    private fun applyNoiseGate')]

    assert 'val sampleRate = call.argument<Int>("sampleRate") ?: DEFAULT_OUTPUT_SAMPLE_RATE' in plugin_source
    assert 'sampleRate = sampleRate,' in plugin_source
    assert 'val phone100Hz = repeatFeaturesForRvc(features)' in engine_source
    assert 'val actualFrameCount = phone100Hz.size / HUBERT_FEATURE_SIZE' in engine_source
    assert 'val frameCount = alignVoiceFrameCount(actualFrameCount)' in engine_source
    assert 'writeWav(outputPath, filteredAudio, request.sampleRate)' in engine_source
    assert 'mixRms(formantAudio, gatedMono16k, request.rmsMixRate, request.sampleRate)' in engine_source
    assert 'resampleMono(matchedAudio,' not in infer_source
    assert 'MODEL_OUTPUT_SAMPLE_RATE' not in engine_source
    assert 'RVC_MODEL_SAMPLE_RATE' not in engine_source


def test_android_inference_accepts_parallel_chunk_count_defaulting_to_three():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    engine_source = ENGINE.read_text(encoding='utf-8')

    assert 'val parallelChunkCount = call.argument<Int>("parallelChunkCount") ?: DEFAULT_PARALLEL_CHUNK_COUNT' in plugin_source
    assert 'parallelChunkCount = parallelChunkCount,' in plugin_source
    assert 'val parallelChunkCount: Int,' in engine_source
    assert 'parallelChunkCount = request.parallelChunkCount,' in engine_source
    assert 'DEFAULT_PARALLEL_CHUNK_COUNT = 4' in engine_source
    assert 'MAX_PARALLEL_CHUNK_COUNT = 32' in engine_source
    assert 'parallelChunkCount = request.parallelChunkCount,' in engine_source
    assert 'val boundedParallelChunkCount = parallelChunkCount.coerceIn(1, MAX_PARALLEL_CHUNK_COUNT)' in engine_source
    assert 'val actualParallelChunkCount = min(' in engine_source
    assert 'min(chunkInputs.size.coerceAtLeast(1), voiceSessions.size.coerceAtLeast(1))' in engine_source
    assert '.chunked(actualParallelChunkCount)' in engine_source
    assert 'chunkBatch.mapIndexed { index, chunk ->' in engine_source
    assert 'parallelStream()' not in engine_source


def test_android_parallel_chunk_count_is_maximum_not_required_chunk_count():
    source = ENGINE.read_text(encoding='utf-8')
    synthesize_source = source[source.index('    private fun synthesizeVoice'):source.index('    private fun runVoiceChunk')]

    assert 'val totalChunks = ceil(frameCount / VOICE_CENTER_FRAMES.toDouble()).toInt()' in synthesize_source
    assert 'val actualParallelChunkCount = min(' in synthesize_source
    assert 'min(chunkInputs.size.coerceAtLeast(1), voiceSessions.size.coerceAtLeast(1))' in synthesize_source
    assert 'for (chunkBatch in chunkInputs.chunked(actualParallelChunkCount))' in synthesize_source
    assert 'require(totalChunks >= boundedParallelChunkCount)' not in synthesize_source
    assert 'error("' not in synthesize_source[synthesize_source.index('val boundedParallelChunkCount'):synthesize_source.index('for (chunkBatch')]


def test_android_voice_chunks_keep_only_center_audio_to_avoid_boundary_duplicates():
    source = ENGINE.read_text(encoding='utf-8')
    chunk_input_source = source[source.index('private data class VoiceChunkInput'):source.index('class CancellationToken')]
    synthesize_source = source[source.index('    private fun synthesizeVoice'):source.index('    private fun runVoiceChunk')]
    append_source = source[source.index('    private fun appendCrossfadedChunkAudio'):source.index('    private fun mixRms')]

    assert 'val centerOffsetFrames: Int,' in chunk_input_source
    assert 'val centerFrameCount: Int,' in chunk_input_source
    assert 'val centerOffsetFrames = centerStart - chunkStart' in synthesize_source
    assert 'val centerFrameCount = min(VOICE_CENTER_FRAMES, frameCount - centerStart)' in synthesize_source
    assert 'centerOffsetFrames = centerOffsetFrames,' in synthesize_source
    assert 'centerFrameCount = centerFrameCount,' in synthesize_source
    assert 'val keepStartSamples = (chunkAudio.size.toLong() * centerOffsetFrames / voiceChunkFrames).toInt().coerceIn(0, chunkAudio.size)' in append_source
    assert 'val keepEndSamples = (chunkAudio.size.toLong() * (centerOffsetFrames + centerFrameCount) / voiceChunkFrames).toInt().coerceIn(keepStartSamples, chunkAudio.size)' in append_source
    assert 'val chunk = chunkAudio.copyOfRange(keepStartSamples, keepEndSamples)' in append_source
    assert 'outputChunks.add(chunk.copyOfRange(crossfadeSamples, chunk.size))' in append_source


def test_android_inference_reports_real_chunk_progress():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    engine_source = ENGINE.read_text(encoding='utf-8')

    assert 'onProgress = { percent, step ->' in plugin_source
    assert '"run_id" to inferenceRunId' in plugin_source
    assert 'val onProgress: (Double, String) -> Unit' in engine_source
    assert 'onProgress(55.0 + 40.0 * completedChunks / totalChunks, "分块生成中")' in engine_source


def test_remote_inference_client_uses_heartbeat_and_idle_progress_timeout():
    client_source = REMOTE_CLIENT.read_text(encoding='utf-8')
    service_source = INFERENCE_SERVICE.read_text(encoding='utf-8')
    protocol_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/InferenceIpcProtocol.kt').read_text(encoding='utf-8')

    assert 'MSG_HEARTBEAT = 8' in protocol_source
    assert 'HEARTBEAT_INTERVAL_MS = 3_000L' in service_source
    assert 'sendHeartbeat(replyTo)' in service_source
    assert 'InferenceIpcProtocol.MSG_HEARTBEAT -> {' in client_source
    assert 'val lastProgressAtMs = AtomicReference(SystemClock.elapsedRealtime())' in client_source
    assert 'val lastHeartbeatAtMs = AtomicReference(SystemClock.elapsedRealtime())' in client_source
    assert 'lastProgressAtMs.set(SystemClock.elapsedRealtime())' in client_source
    assert 'lastHeartbeatAtMs.set(SystemClock.elapsedRealtime())' in client_source
    assert 'REMOTE_INFERENCE_IDLE_TIMEOUT_MS' in client_source
    assert 'REMOTE_INFERENCE_HEARTBEAT_TIMEOUT_MS' in client_source
    assert 'if (now - lastProgressAtMs.get() >= REMOTE_INFERENCE_IDLE_TIMEOUT_MS)' in client_source
    assert 'setRemoteError("INFERENCE_STALLED", "推理进程长时间无进度")' in client_source
    assert 'if (now - lastHeartbeatAtMs.get() >= REMOTE_INFERENCE_HEARTBEAT_TIMEOUT_MS)' in client_source
    assert 'setRemoteError("INFERENCE_HEARTBEAT_TIMEOUT", "推理进程心跳中断")' in client_source
    assert 'val deadlineMs = SystemClock.elapsedRealtime() + REMOTE_INFERENCE_TIMEOUT_MS' not in client_source


def test_android_long_audio_uses_segmented_inference_and_aggregates_progress():
    source = ENGINE.read_text(encoding='utf-8')
    infer_source = source[source.index('    fun infer(request: RvcInferenceRequest): String'):source.index('    fun openStreamingSession')]

    assert 'MAX_DIRECT_INFERENCE_DURATION_US = 30_000_000L' in source
    assert 'MAX_DIRECT_INFERENCE_FILE_BYTES = 5L * 1024L * 1024L' in source
    assert 'SEGMENT_CENTER_DURATION_US = 30_000_000L' in source
    assert 'SEGMENT_CONTEXT_DURATION_US = 1_000_000L' in source
    assert 'val audioPlan = inspectAudioPlan(songFile)' in infer_source
    assert 'if (audioPlan.requiresSegmentation)' in infer_source
    assert 'return inferSegmented(request, songFile, voiceModelFile, hubertModel, rmvpeModel, audioPlan, outputPath)' in infer_source
    assert 'private fun inferSegmented(' in source
    assert 'private fun decodeAudioSegment(file: File, startUs: Long, endUs: Long): DecodedAudio' in source
    assert 'private fun mapSegmentProgress(segmentIndex: Int, segmentCount: Int, innerPercent: Double): Double' in source
    assert '8.0 + 90.0 * ((segmentIndex + normalizedInner) / segmentCount)' in source
    assert 'appendWavSegment(' in source
    assert 'writeWav(outputPath, filteredAudio, request.sampleRate)' in infer_source


def test_resumable_job_store_manifest_fields_and_layout_contract():
    source = JOB_STORE.read_text(encoding='utf-8')

    assert 'data class ResumableInferenceJobManifest' in source
    for field in [
        'jobId',
        'sourceAudioPath',
        'sourceAudioFingerprint',
        'modelPath',
        'modelFingerprint',
        'indexPath',
        'parameterFingerprint',
        'jobDirectoryPath',
        'chunksDirectoryPath',
        'outputsDirectoryPath',
        'manifestPath',
        'segmentCount',
        'completedChunkIndexes',
        'lastCompletedChunkIndex',
        'overallProgress',
        'state',
    ]:
        assert field in source
    assert 'JOBS_DIRECTORY_NAME = "resumable_inference_jobs"' in source
    assert 'MANIFEST_FILE_NAME = "manifest.json"' in source
    assert 'CHUNKS_DIRECTORY_NAME = "chunks"' in source
    assert 'OUTPUTS_DIRECTORY_NAME = "outputs"' in source
    assert 'Os.rename' in source


def test_segmented_inference_persists_resumable_chunks_and_cleans_cache_on_success():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'private val resumableJobStore: ResumableInferenceJobStore? = null' in source
    assert 'findReusableManifest(' in source
    assert 'createManifest(' in source
    assert 'prepareInputChunkFiles(' in source
    assert 'writeDecodedAudioWav(chunkFile, decoded)' in source
    assert 'writeWav(layout.convertedChunkFile(segmentIndex)' in source
    assert 'completedChunkIndexes.contains(segmentIndex)' in source
    assert 'readCachedSegmentAudio(convertedChunkFile)' in source
    assert 'store?.saveManifest(manifest)' in source
    assert 'store?.deleteJob(it.jobId)' in source


def test_plugin_exposes_resumable_job_query_and_cache_clear_methods():
    source = PLUGIN.read_text(encoding='utf-8')

    assert '"getResumableJobMetadata" -> getResumableJobMetadata(call, result)' in source
    assert '"clearResumableJobCache" -> clearResumableJobCache(call, result)' in source
    assert 'private fun getResumableJobMetadata(call: MethodCall, result: Result)' in source
    assert 'private fun clearResumableJobCache(call: MethodCall, result: Result)' in source
    assert 'ResumableInferenceJobStore(appContext.filesDir)' in source


def test_android_inference_accepts_formant_parameter():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    engine_source = ENGINE.read_text(encoding='utf-8')

    assert 'val formant = call.argument<Double>("formant") ?: 0.0' in plugin_source
    assert 'formant = formant,' in plugin_source
    assert 'val formant: Double,' in engine_source
    assert 'applyFormant(audio, request.formant)' in engine_source
    assert 'private fun applyFormant(audio: FloatArray, formant: Double): FloatArray' in engine_source


def test_android_uses_rmvpe_output_as_rvc_pitch_source():
    source = ENGINE.read_text(encoding='utf-8')
    infer_start = source.index('    fun infer(request: RvcInferenceRequest): String')
    infer_source = source[infer_start:source.index('    private fun decodeAudio', infer_start)]

    assert 'val pitchf = extractRmvpePitch(environment, rmvpeSession, gatedMono16k, frameCount, request.pitchChange)' in infer_source
    assert 'runRmvpeForValidation(' not in source
    assert 'estimatePitch(decoded, frameCount, request.pitchChange)' not in source
    assert 'private fun estimatePitch(' not in source
    assert 'private fun estimateFramePitch(' not in source


def test_android_rmvpe_pitch_uses_real_mel_preprocessing_and_cent_postprocess():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'private fun buildRmvpeMelSpectrogram(audio16k: FloatArray, frameCount: Int): FloatArray' in source
    assert 'RMVPE_FFT_SIZE = 1024' in source
    assert 'RMVPE_HOP_SIZE = 160' in source
    assert 'RMVPE_MEL_FMIN = 30.0' in source
    assert 'RMVPE_MEL_FMAX = 8000.0' in source
    assert 'private fun rmvpeBinToFrequency(bin: Int): Float' in source
    assert 'RMVPE_CENTS_BASE = 1997.3794084376191' in source
    assert 'RMVPE_CENTS_PER_BIN = 20.0' in source
    assert 'RMVPE_VOICED_THRESHOLD = 0.03f' in source


def test_android_audio_decode_respects_pcm_encoding_to_avoid_electronic_artifacts():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'val outputFormat = codec.outputFormat' in source
    assert 'decodedSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)' in source
    assert 'decodedChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)' in source
    assert 'pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))' in source
    assert 'AudioFormat.ENCODING_PCM_FLOAT' in source
    assert 'AudioFormat.ENCODING_PCM_16BIT' in source
    assert 'bytesToFloatPcm(output.toByteArray(), decodedChannelCount, pcmEncoding)' in source
    assert 'private fun bytesToFloatPcm(bytes: ByteArray, channelCount: Int, pcmEncoding: Int): FloatArray' in source


def test_android_resampler_uses_windowed_sinc_filter_instead_of_linear_interpolation():
    source = ENGINE.read_text(encoding='utf-8')
    resampler_source = source[source.index('    private fun resampleMono'):source.index('    private fun extractHubertFeatures')]

    assert 'private fun resampleMono(mono: FloatArray, sourceSampleRate: Int, targetSampleRate: Int): FloatArray' in source
    assert 'return mono' in source
    assert 'WINDOWED_SINC_RADIUS' in source
    assert 'val normalizedCutoff = min(1.0, targetSampleRate.toDouble() / sourceSampleRate)' in resampler_source
    assert 'sinc(distance * normalizedCutoff)' in resampler_source
    assert 'hannWindow(' in resampler_source
    assert 'mono[left] * (1f - fraction) + mono[right] * fraction' not in resampler_source


def test_android_chunk_audio_uses_equal_power_crossfade_at_boundaries():
    source = ENGINE.read_text(encoding='utf-8')
    crossfade_source = source[source.index('    private fun appendCrossfadedChunkAudio'):source.index('    private fun mixRms')]

    assert 'CHUNK_CROSSFADE_SAMPLES = 768' in source
    assert 'val fadeIn = sin(0.5 * Math.PI * phase).toFloat()' in crossfade_source
    assert 'val fadeOut = cos(0.5 * Math.PI * phase).toFloat()' in crossfade_source
    assert 'blendBoundarySample(previous[previousIndex], chunk[index], fadeOut, fadeIn)' in crossfade_source
    assert 'output[outputIndex] = output[outputIndex] * fadeOut + chunkAudio[chunkIndex] * fadeIn' not in crossfade_source
    assert 'val fadeIn = (index + 1).toFloat() / (crossfadeSamples + 1)' not in crossfade_source


def test_android_formant_parameter_is_noop_until_real_formant_shift_exists():
    source = ENGINE.read_text(encoding='utf-8')
    formant_source = source[source.index('    private fun applyFormant'):source.index('    private fun coarsePitch')]

    assert 'private fun applyFormant(audio: FloatArray, formant: Double): FloatArray' in formant_source
    assert 'return audio' in formant_source
    assert 'val factor = Math.pow' not in formant_source
    assert 'sourcePosition = index * factor' not in formant_source


def test_android_inference_uses_cpu_optimized_sessions_without_runtime_device_option():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    engine_source = ENGINE.read_text(encoding='utf-8')

    assert 'runtimeDevice' not in plugin_source
    assert 'runtimeDevice' not in engine_source
    assert 'DEFAULT_RUNTIME_DEVICE' not in plugin_source
    assert 'providers.NNAPIFlags' not in engine_source
    assert 'options.addQnn' not in engine_source
    assert 'options.addNnapi' not in engine_source
    assert 'session.disable_cpu_ep_fallback' not in engine_source
    assert 'private fun createCpuOptimizedSession(' in engine_source
    assert 'OrtSession.SessionOptions()' in engine_source
    assert 'options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)' in engine_source
    assert 'options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.PARALLEL)' not in engine_source
    assert 'threadCount: Int = inferenceThreadCount()' in engine_source
    assert 'options.setIntraOpNumThreads(threadCount)' in engine_source
    assert 'options.setInterOpNumThreads(1)' in engine_source
    assert 'options.setCPUArenaAllocator(false)' in engine_source
    assert 'options.setMemoryPatternOptimization(false)' in engine_source
    assert 'options.addXnnpack(mapOf("intra_op_num_threads" to threadCount.toString()))' in engine_source
    assert 'environment.createSession(modelFile.absolutePath, options)' in engine_source
    assert 'environment.createSession(hubertModel.absolutePath).use' not in engine_source
    assert 'environment.createSession(rmvpeModel.absolutePath).use' not in engine_source
    assert 'environment.createSession(voiceModelFile.absolutePath).use' not in engine_source


def test_android_inference_requests_high_performance_during_generation():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    manifest_source = (ROOT / 'android/app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
    engine_source = ENGINE.read_text(encoding='utf-8')

    assert 'android.permission.WAKE_LOCK' in manifest_source
    assert 'PowerManager.PARTIAL_WAKE_LOCK' in plugin_source
    assert 'acquire(INFERENCE_WAKE_LOCK_TIMEOUT_MS)' in plugin_source
    assert 'wakeLock.release()' in plugin_source
    assert 'Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)' in plugin_source
    assert 'Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)' in plugin_source
    assert 'inferenceThreadCount()' in engine_source
    assert 'Runtime.getRuntime().availableProcessors()' in engine_source


def test_android_root_performance_mode_is_explicit_checked_and_restored():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    infer_start = plugin_source.index('private fun infer(call: MethodCall, result: Result)')
    infer_source = plugin_source[infer_start:plugin_source.index('    private fun getVersion', infer_start)]

    assert 'val enableRootPerformanceMode = call.argument<Boolean>("enableRootPerformanceMode") ?: false' in infer_source
    assert 'RootPerformanceSession.startIfEnabled(enableRootPerformanceMode, getRootPerformanceCacheFile())' in infer_source
    assert 'rootPerformanceSession?.restore()' in infer_source
    assert 'ROOT_UNAVAILABLE' in plugin_source
    assert '未获得 root 授权' in plugin_source
    assert 'su", "-c", "id"' in plugin_source
    assert 'uid=0' in plugin_source
    assert 'scaling_min_freq' in plugin_source
    assert 'scaling_max_freq' in plugin_source
    assert 'cpuinfo_max_freq' in plugin_source
    assert 'scaling_governor' in plugin_source
    assert 'scaling_available_governors' in plugin_source
    assert '"performance"' in plugin_source
    assert 'cpu*/online' not in plugin_source
    assert '性能优化方案' not in plugin_source


def test_android_root_performance_mode_forces_boost_and_restores_snapshot():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    service_source = INFERENCE_SERVICE.read_text(encoding='utf-8')

    assert 'Process.THREAD_PRIORITY_URGENT_AUDIO' in plugin_source
    assert 'RVCPlugin.RootPerformanceSession.startIfEnabled(' not in service_source
    assert 'CpuOnlineState' in plugin_source
    assert 'originalOnlineStates' in plugin_source
    assert 'writeRootFileWithLock("${cpu.path}/online", "1")' in plugin_source
    assert 'governor = readRootFile("$path/scaling_governor")' in plugin_source
    assert 'preferredGovernor(readRootFile("$path/scaling_available_governors"))' in plugin_source
    assert 'readRootMode("$path/scaling_governor")' in plugin_source
    assert 'readRootMode("$path/scaling_min_freq")' in plugin_source
    assert 'readRootMode("$path/scaling_max_freq")' in plugin_source
    assert 'restoreProcessPriority(originalNice)' in plugin_source
    assert 'restoreCpuAffinity(originalAffinity)' in plugin_source
    assert 'restoreCpuset(originalCpuset)' in plugin_source
    assert 'taskset -p' in plugin_source
    assert 'cpuset' in plugin_source
    assert 'schedtune.boost' in plugin_source
    assert 'uclamp.min' in plugin_source
    assert 'keepAliveJob?.cancel()' in plugin_source
    assert 'keepAliveJob = CoroutineScope(Dispatchers.IO).launch {' in plugin_source
    assert 'while (isActive)' in plugin_source
    assert 'ensureAllCpusOnline(originalOnlineStates)' in plugin_source
    assert 'ensureBoostEnabled(boost.path)' in plugin_source
    assert 'delay(ROOT_PERFORMANCE_REAPPLY_INTERVAL_MS)' in plugin_source
    assert 'writeRootFileWithLock(' in plugin_source
    assert 'restoreRootFile(' in plugin_source
    assert 'unlockRootFile(path)' in plugin_source
    assert 'chmodRootFile(path, ROOT_LOCK_MODE)' in plugin_source
    assert 'chmodRootFile(path, ROOT_UNLOCK_MODE)' in plugin_source
    assert 'loadRootNodeCache(cacheFile)' in plugin_source
    assert 'saveRootNodeCache(cacheFile, RootNodeCache(' in plugin_source
    assert 'ROOT 性能方案已启用' in plugin_source


def test_android_root_performance_mode_is_runtime_detected_not_device_specific():
    plugin_source = PLUGIN.read_text(encoding='utf-8')

    for device_specific_value in [
        'RMX',
        'cpu6',
        'cpu7',
        '3628800',
        '4608000',
    ]:
        assert device_specific_value not in plugin_source

    assert 'cpuinfo_max_freq' in plugin_source
    assert 'related_cpus' in plugin_source
    assert 'rootPathExists(' in plugin_source
    assert 'writeFirstExistingRootFile(' in plugin_source


def test_android_root_performance_mode_uses_dynamic_policy_targets_and_rejects_partial_apply():
    plugin_source = PLUGIN.read_text(encoding='utf-8')

    assert 'scaling_available_frequencies' in plugin_source
    assert 'resolvePolicyTargetFreq(path)' in plugin_source
    assert 'verifyPolicyApplied(path, targetFreq, governor)' in plugin_source
    assert 'targetFreq = targetFreq' in plugin_source
    assert 'if (appliedPolicies.any { !it.applied }) {' in plugin_source
    assert '部分 CPU 频率策略应用失败' in plugin_source
    assert 'appliedPolicies.none { it.changed }' not in plugin_source


def test_remote_realtime_session_keeps_pcm_pipeline_inside_inference_process():
    client_source = REMOTE_CLIENT.read_text(encoding='utf-8')
    service_source = INFERENCE_SERVICE.read_text(encoding='utf-8')

    assert 'MSG_START_REALTIME' in client_source
    assert 'MSG_STOP_REALTIME' in client_source
    assert 'putShortArray(InferenceIpcProtocol.KEY_INPUT_PCM' not in client_source
    assert 'getShortArray(InferenceIpcProtocol.KEY_OUTPUT_PCM)' not in client_source
    assert 'MSG_REALTIME_PCM' not in client_source
    assert 'AudioRecord(' in service_source
    assert 'AudioTrack(' in service_source
    assert 'openStreamingSession(' in service_source


def test_android_inference_no_longer_probes_gpu_hardware_providers():
    engine_source = ENGINE.read_text(encoding='utf-8')

    assert 'selectHardwareProvider' not in engine_source
    assert 'HARDWARE_PROVIDER_QNN' not in engine_source
    assert 'HARDWARE_PROVIDER_NNAPI' not in engine_source
    assert 'QNN_HTP_BACKEND_PATH' not in engine_source
    assert 'GPU 推理不可用' not in engine_source
    assert 'request.onProgress(2.0, "准备 CPU 推理")' in engine_source


def test_realtime_inference_uses_remote_process_session_instead_of_local_engine():
    source = PLUGIN.read_text(encoding='utf-8')
    realtime_source = source[source.index('private fun startRealtimeInference(call: MethodCall, result: Result)'):source.index('    private fun stopRealtimeInference')]
    session_source = source[source.index('private class RealtimeRvcSession'):source.index('    private class DecibelMeterSession')]
    service_source = service_realtime_source()

    assert 'openRealtimeSession(' in realtime_source
    assert 'RemoteRealtimeInferenceSession' in session_source
    assert 'RvcInferenceEngine(modelsDir).openStreamingSession(' not in session_source
    assert 'realtimeEngine.inferPcm16(' not in session_source
    assert 'realtimeEngine.inferPcm16(inferenceWindow, realtimeHopFrames)' in service_source


def test_android_chunk_audio_uses_crossfade_at_boundaries():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'appendCrossfadedChunkAudio(chunkAudio, chunk.centerOffsetFrames, chunk.centerFrameCount, VOICE_CHUNK_FRAMES, outputChunks)' in source
    assert 'private fun appendCrossfadedChunkAudio(chunkAudio: FloatArray, centerOffsetFrames: Int, centerFrameCount: Int, voiceChunkFrames: Int, outputChunks: MutableList<FloatArray>): Int' in source
    assert 'CHUNK_CROSSFADE_SAMPLES = 768' in source


def test_realtime_inference_uses_configurable_sample_rate_and_parallel_voice_chunks():
    source = PLUGIN.read_text(encoding='utf-8')
    realtime_source = source[source.index('private fun startRealtimeInference(call: MethodCall, result: Result)'):source.index('    private fun stopRealtimeInference')]
    session_source = service_realtime_source()

    assert 'val sampleRate = call.argument<Int>("sampleRate") ?: DEFAULT_OUTPUT_SAMPLE_RATE' in realtime_source
    assert 'val parallelChunkCount = call.argument<Int>("parallelChunkCount") ?: DEFAULT_PARALLEL_CHUNK_COUNT' in realtime_source
    assert 'sampleRate = sampleRate.coerceIn(MIN_REALTIME_SAMPLE_RATE, MAX_REALTIME_SAMPLE_RATE),' in realtime_source
    assert 'parallelChunkCount = parallelChunkCount,' in realtime_source
    assert 'val boundedSampleRate = sampleRate.coerceIn(MIN_REALTIME_SAMPLE_RATE, MAX_REALTIME_SAMPLE_RATE)' in session_source
    assert 'parallelChunkCount = parallelChunkCount,' in realtime_source
    assert 'AudioRecord.getMinBufferSize(boundedSampleRate' in session_source
    assert 'AudioTrack(' in session_source
    assert 'boundedSampleRate,' in session_source


def test_realtime_streaming_sessions_limit_onnx_threads_without_changing_offline_generation():
    source = ENGINE.read_text(encoding='utf-8')
    open_streaming_source = source[source.index('    fun openStreamingSession('):source.index('    private fun applyNoiseGate')]

    assert 'REALTIME_INFERENCE_THREAD_COUNT = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)' in source
    assert 'private fun createCpuOptimizedSession(environment: OrtEnvironment, modelFile: File, threadCount: Int = inferenceThreadCount()): OrtSession' in source
    assert 'options.setIntraOpNumThreads(threadCount)' in source
    assert 'options.addXnnpack(mapOf("intra_op_num_threads" to threadCount.toString()))' in source
    assert 'createCpuOptimizedSession(environment, hubertModel, REALTIME_INFERENCE_THREAD_COUNT)' in open_streaming_source
    assert 'createCpuOptimizedSession(environment, rmvpeModel, REALTIME_INFERENCE_THREAD_COUNT)' in open_streaming_source
    assert 'val sessionPoolSize = parallelChunkCount.coerceIn(1, MAX_PARALLEL_CHUNK_COUNT)' in open_streaming_source
    assert 'val voiceSessionThreadCount = voiceChunkThreadCount(sessionPoolSize)' in open_streaming_source
    assert 'createCpuOptimizedSession(environment, voiceModelFile, voiceSessionThreadCount)' in open_streaming_source
    infer_source = source[source.index('    fun infer(request: RvcInferenceRequest): String'):source.index('    fun openStreamingSession(')]
    assert 'createCpuOptimizedSession(environment, hubertModel).use' in infer_source
    assert 'createCpuOptimizedSession(environment, rmvpeModel).use' in infer_source
    assert 'MutableList(sessionPoolSize)' in infer_source
    assert 'createCpuOptimizedSession(environment, voiceModelFile, voiceSessionThreadCount)' in infer_source
    assert 'voiceSessions.asReversed().forEach { it.close() }' in infer_source


def test_realtime_audio_track_is_primed_before_playback_to_avoid_chunk_gaps():
    source = INFERENCE_SERVICE.read_text(encoding='utf-8')
    session_source = service_realtime_source()
    start_source = session_source[session_source.index('fun start()'):session_source.index('        fun stop()')]
    stop_source = session_source[session_source.index('        fun stop()'):session_source.index('        private fun appendRealtimeSamples')]

    assert 'private val queuedOutputSamples = ShortRingBuffer(' in session_source
    assert 'private val outputChannel = Channel<ShortArray>(capacity = 3)' in session_source
    assert 'private var audioTrackStarted = false' in session_source
    assert 'private val audioTrackLock = Object()' in session_source
    assert 'private var audioTrackReleased = false' in session_source
    assert 'private val outputDelayStartedAtMs = SystemClock.elapsedRealtime()' in session_source
    assert 'val sendResult = outputChannel.trySend(output)' in start_source
    assert 'if (sendResult.isFailure)' in start_source
    assert 'writeRealtimeOutput(output)' in start_source
    assert 'private fun writeRealtimeOutput(output: ShortArray)' in session_source
    assert 'writeRealtimeOutput(outputSize)' not in start_source
    assert 'private val outputDelayBufferFrames = (boundedSampleRate * delayBufferSeconds.coerceIn(0.0, 60.0)).toInt()' in session_source
    assert 'private val outputStartupThresholdFrames = if (outputDelayBufferFrames <= 0) 0 else minOf(outputDelayBufferFrames, realtimeHopFrames * REALTIME_OUTPUT_STARTUP_HOPS)' in session_source
    assert 'if (!audioTrackStarted && shouldStartAudioTrackLocked())' in session_source
    assert 'queuedOutputSamples.size >= outputStartupThresholdFrames' in session_source
    assert 'now - outputDelayStartedAtMs >= outputDelayBufferMaxWaitMs' in session_source
    assert 'startAudioTrackIfNeeded()' in session_source
    assert 'writeAudioTrackFully(output, output.size)' in session_source
    assert 'processRemainingRealtimeSamples()' not in stop_source
    assert 'flushQueuedRealtimeOutput()' not in stop_source
    assert 'outputChannel.close()' in stop_source
    assert 'worker?.cancel()' in stop_source
    assert stop_source.index('running = false') < stop_source.index('worker?.cancel()')
    assert stop_source.index('worker?.cancel()') < stop_source.index('audioRecord.stop()')
    assert 'audioTrack.write(samples, offset, size - offset)' in session_source
    assert 'synchronized(audioTrackLock)' in session_source
    assert 'if (stopped.get() || audioTrackReleased) return' in session_source
    assert 'audioTrackReleased = true' in stop_source
    assert 'AudioTrack.WRITE_BLOCKING' not in session_source
    assert 'check(written >= 0)' in session_source
    assert 'audioTrack.play()' in session_source


def test_realtime_pipeline_splits_inference_and_output_with_bounded_channel():
    source = INFERENCE_SERVICE.read_text(encoding='utf-8')
    session_source = service_realtime_source()
    start_source = session_source[session_source.index('fun start()'):session_source.index('        fun stop()')]

    assert 'import kotlinx.coroutines.channels.Channel' in source
    assert 'private val outputChannel = Channel<ShortArray>(capacity = 3)' in session_source
    assert 'val inferenceJob = launch {' in start_source
    assert 'val outputJob = launch {' in start_source
    assert 'val output = processRealtimeChunk()' in start_source
    assert 'val sendResult = outputChannel.trySend(output)' in start_source
    assert 'if (sendResult.isFailure)' in start_source
    assert 'outputChannelDropCount++' in start_source
    assert 'outputChannelDroppedSamples += output.size' in start_source
    assert 'for (output in outputChannel)' in start_source
    assert 'writeRealtimeOutput(output)' in start_source
    assert 'val outputSize = processRealtimeChunk()' not in start_source
    assert 'writeRealtimeOutput(outputSize)' not in start_source


def test_realtime_delay_buffer_is_output_queue_drained_from_head_after_start():
    session_source = service_realtime_source()
    write_source = session_source[session_source.index('        private fun writeRealtimeOutput'):session_source.index('        private fun shouldStartAudioTrackLocked')]

    assert 'if (!audioTrackStarted && shouldStartAudioTrackLocked())' in write_source
    assert '} else if (audioTrackStarted) {' in write_source
    assert 'queuedOutputSamples.addAll(output)' in write_source
    assert 'val outputToWrite = synchronized(sampleLock)' in write_source
    assert write_source.count('drainQueuedOutputLocked()') >= 2
    assert 'private fun drainQueuedOutputLocked(): ShortArray?' in write_source
    assert 'null to output.copyOf()' not in write_source
    assert write_source.index('shouldStartAudioTrackLocked()') < write_source.index('} else if (audioTrackStarted) {')
    assert 'queuedOutputSamples.size >= outputStartupThresholdFrames' in session_source
    assert 'now - outputDelayStartedAtMs >= outputDelayBufferMaxWaitMs' in session_source


def test_realtime_output_channel_close_does_not_throw_when_stop_races_with_producer():
    source = INFERENCE_SERVICE.read_text(encoding='utf-8')
    session_source = service_realtime_source()
    start_source = session_source[session_source.index('fun start()'):session_source.index('        fun stop()')]

    assert 'val sendResult = outputChannel.trySend(output)' in start_source
    assert 'if (sendResult.isFailure)' in start_source
    assert 'outputChannel.send(output)' not in start_source
    assert 'ClosedSendChannelException' not in source


def test_audio_inference_and_streaming_apply_complete_output_denoise_controls():
    source = ENGINE.read_text(encoding='utf-8')
    infer_source = source[source.index('    fun infer(request: RvcInferenceRequest): String'):source.index('    fun openStreamingSession(')]
    streaming_source = source[source.index('    inner class RvcStreamingSession'):source.index('        private fun combineContext')]

    assert 'val outputDenoiseEnabled: Boolean,' in source
    assert 'val vocalRangeFilterEnabled: Boolean,' in source
    assert 'applyInputAudioFilters(mono16k, request.noiseGateDb, request.outputDenoiseEnabled, request.vocalRangeFilterEnabled, HUBERT_SAMPLE_RATE)' in infer_source
    assert 'applyOutputAudioFilters(matchedAudio, request.noiseGateDb, request.outputDenoiseEnabled, request.vocalRangeFilterEnabled, request.sampleRate)' in infer_source
    assert 'applyRealtimeInputAudioFilters(resampleMono(mono, sampleRate, HUBERT_SAMPLE_RATE), noiseGateDb, vocalRangeFilterEnabled, HUBERT_SAMPLE_RATE)' in streaming_source
    assert 'applyOutputAudioFilters(currentAudio, noiseGateDb, outputDenoiseEnabled, vocalRangeFilterEnabled, sampleRate)' in streaming_source
    assert 'return floatToPcm16(resizeAudio(filteredAudio, outputFrames))' in streaming_source
    assert 'private fun applyInputAudioFilters(audio: FloatArray, noiseGateDb: Double, outputDenoiseEnabled: Boolean, vocalRangeFilterEnabled: Boolean, sampleRate: Int): FloatArray' in source
    assert 'applyInputArtifactCleanup(rangedAudio, sampleRate)' in source
    assert 'private fun applyInputArtifactCleanup(audio: FloatArray, sampleRate: Int): FloatArray' in source
    assert 'private fun applyHumNotchScaffold(audio: FloatArray, sampleRate: Int): FloatArray' in source
    assert 'private fun repairClickPopSpikes(audio: FloatArray): FloatArray' in source
    assert 'private fun smoothElectricalBursts(audio: FloatArray): FloatArray' in source
    assert 'repairClickPopSpikes(humFiltered)' in source
    assert 'smoothElectricalBursts(declicked)' in source
    assert 'private fun applyOutputDenoiseGate(audio: FloatArray, noiseGateDb: Double, sampleRate: Int): FloatArray' in source
    assert 'applyResidualNoiseGate(adaptive, noiseGateDb, sampleRate)' in source
    assert 'private fun applyResidualNoiseGate(audio: FloatArray, noiseGateDb: Double, sampleRate: Int): FloatArray' in source
    assert 'private fun suppressEnvelopeArtifacts(audio: FloatArray, sampleRate: Int): FloatArray' in source
    assert 'private fun applyOutputDeEsser(audio: FloatArray, sampleRate: Int): FloatArray' in source
    assert 'suppressEnvelopeArtifacts(residual, sampleRate)' in source
    assert 'applyOutputDeEsser(envelopeSmoothed, sampleRate)' in source
    assert 'private fun applySoftLimiter(audio: FloatArray): FloatArray' in source
    assert 'private fun applyAdaptiveDenoiseGate(audio: FloatArray, noiseGateDb: Double, sampleRate: Int): FloatArray' in source
    assert 'var noiseFloorDb = 100.0' in source
    assert 'DENOISE_NOISE_FLOOR_MARGIN_DB' in source
    assert 'DENOISE_ATTACK_RATE' in source
    assert 'DENOISE_RELEASE_RATE' in source
    assert 'private fun applyVocalRangeFilter(audio: FloatArray, sampleRate: Int): FloatArray' in source
    assert 'const val VOCAL_RANGE_LOW_CUTOFF_HZ = 50.0' in source
    assert 'const val VOCAL_RANGE_LOW_HZ = 60.0' in source
    assert 'const val VOCAL_RANGE_HIGH_HZ = 2500.0' in source
    assert 'const val VOCAL_RANGE_HIGH_ROLLOFF_HZ = 4500.0' in source
    assert 'BiquadFilter.highPass(sampleRate, VOCAL_RANGE_LOW_HZ, BIQUAD_Q)' in source
    assert 'BiquadFilter.highShelf(sampleRate, VOCAL_RANGE_HIGH_HZ, VOCAL_RANGE_HIGH_SHELF_GAIN_DB, BIQUAD_Q)' in source
    assert 'BiquadFilter.peaking(sampleRate, VOCAL_RANGE_PRESENCE_HZ, VOCAL_RANGE_PRESENCE_GAIN_DB, BIQUAD_Q)' in source
    assert 'applyOnePoleLowPass' not in source
    assert 'rnnoise' not in source.lower()
    assert 'webrtc' not in source.lower()
    assert 'NoiseSuppressor' not in source
    assert 'AcousticEchoCanceler' not in source
    assert 'AutomaticGainControl' not in source


def test_recorded_audio_files_remain_raw_until_processing_filters_are_applied():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    engine_source = ENGINE.read_text(encoding='utf-8')
    recorder_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerRecorder.kt').read_text(encoding='utf-8')
    bridge_source = (ROOT / 'lib/services/rvc_bridge.dart').read_text(encoding='utf-8')

    start_recording_source = plugin_source[plugin_source.index('    private fun startRecording'):plugin_source.index('    private fun stopRecording')]
    stop_recording_source = plugin_source[plugin_source.index('    private fun stopRecording'):plugin_source.index('    private fun saveGeneratedAudio')]
    voice_recording_source = recorder_source[recorder_source.index('    fun startRecording()'):recorder_source.index('    fun stopRecordingAndProcess()')]
    voice_processing_source = recorder_source[recorder_source.index('    private fun processRecording'):recorder_source.index('    fun targetSaveDisplayPath()')]

    assert "invokeMethod('startRecording')" in bridge_source
    assert "invokeMethod('stopRecording')" in bridge_source
    assert 'noiseGateDb' not in bridge_source[bridge_source.index('  Future<void> startRecording()'):bridge_source.index('  Future<void> startRealtimeInference')]
    assert 'vocalRangeFilterEnabled' not in bridge_source[bridge_source.index('  Future<void> startRecording()'):bridge_source.index('  Future<void> startRealtimeInference')]
    assert 'MediaRecorder().apply' in start_recording_source
    assert 'setOutputFile(file.absolutePath)' in start_recording_source
    assert 'result.success(file.absolutePath)' in stop_recording_source
    assert 'applyNoiseGate' not in start_recording_source + stop_recording_source
    assert 'applyVocalRangeFilter' not in start_recording_source + stop_recording_source
    assert 'writeWavStream(file, config.sampleRate)' in voice_recording_source
    assert 'writePcm16(output, buffer, read)' in voice_recording_source
    assert 'processRecording(file)' in voice_recording_source
    assert voice_recording_source.index('writeWavStream(file, config.sampleRate)') < voice_recording_source.index('processRecording(file)')
    assert 'RemoteInferenceClient(context).infer(' in voice_processing_source
    assert 'RvcInferenceEngine(modelsDir).infer(' not in voice_processing_source
    assert 'noiseGateDb = config.noiseGateDb,' in voice_processing_source
    assert 'vocalRangeFilterEnabled = config.vocalRangeFilterEnabled,' in voice_processing_source
    assert 'applyInputAudioFilters(mono16k, request.noiseGateDb, request.outputDenoiseEnabled, request.vocalRangeFilterEnabled, HUBERT_SAMPLE_RATE)' in engine_source


def test_realtime_inference_uses_large_window_demo_blocks_instead_of_short_hop_streaming():
    source = PLUGIN.read_text(encoding='utf-8')
    bridge_source = (ROOT / 'lib/services/rvc_bridge.dart').read_text(encoding='utf-8')
    screen_source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')
    realtime_source = source[source.index('private fun startRealtimeInference(call: MethodCall, result: Result)'):source.index('    private fun stopRealtimeInference')]
    session_source = service_realtime_source()

    assert 'val sampleLength = call.argument<Double>("sampleLength") ?: 6.0' in realtime_source
    assert 'val crossfadeLength = call.argument<Double>("crossfadeLength") ?: 0.0' in realtime_source
    assert 'val extraInferenceLength = call.argument<Double>("extraInferenceLength") ?: 0.0' in realtime_source
    assert 'val delayBufferSeconds = call.argument<Double>("delayBufferSeconds") ?: 0.0' in realtime_source
    assert 'double sampleLength = 6.0' in bridge_source
    assert 'double crossfadeLength = 0.0' in bridge_source
    assert 'double extraInferenceLength = 0.0' in bridge_source
    assert 'double delayBufferSeconds = 0.0' in bridge_source
    assert '_sampleLength = 6.0' in screen_source
    assert '_crossfadeLength = 0.0' in screen_source
    assert '_extraInferenceLength = 0.0' in screen_source
    assert '_delayBufferSeconds = 0.0' in screen_source
    assert 'private val realtimeHopFrames = inferenceWindow.size' in session_source
    assert 'pendingSamples.dropFirst(realtimeHopFrames)' in session_source
    assert 'realtimeEngine.inferPcm16(inferenceWindow, realtimeHopFrames)' in session_source


def test_realtime_timing_parameters_remain_editable_after_demo_rollback():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    service_source = INFERENCE_SERVICE.read_text(encoding='utf-8')
    screen_source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')

    assert "_sampleLength = prefs.getDouble('realtimeSampleLength') ?? 6.0;" in screen_source
    assert '.clamp(6.0, 6.0)' not in screen_source
    sample_slider = screen_source[screen_source.index("Text('采样长度："):screen_source.index("Text('淡入淡出长度：")]
    assert 'min: 1.0' in sample_slider
    assert 'max: 12.0' in sample_slider
    assert 'sampleLength = sampleLength.coerceIn(MIN_REALTIME_SAMPLE_LENGTH_SECONDS, MAX_REALTIME_SAMPLE_LENGTH_SECONDS),' in plugin_source
    assert 'val boundedSampleLength = sampleLength.coerceIn(MIN_REALTIME_SAMPLE_LENGTH_SECONDS, MAX_REALTIME_SAMPLE_LENGTH_SECONDS)' in service_source


def test_realtime_status_is_sent_as_structured_fields_not_map_string():
    service_source = INFERENCE_SERVICE.read_text(encoding='utf-8')

    assert 'sendRealtimeStatus(replyTo, diagnostics)' in service_source
    assert 'private fun sendRealtimeStatus(replyTo: Messenger, diagnostics: Map<String, Any>)' in service_source
    assert 'data = Bundle().apply {' in service_source
    assert 'diagnostics.forEach { (key, value) ->' in service_source
    assert 'sendProgress(replyTo, 0.0, diagnostics.toString())' not in service_source
    client_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/RemoteInferenceClient.kt').read_text(encoding='utf-8')
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    assert 'onStatus: (Map<String, Any>) -> Unit' in client_source
    assert 'onStatus(bundleToMap(message.data))' in client_source
    assert 'private fun bundleToMap(bundle: Bundle): Map<String, Any>' in client_source
    assert 'onStatus = { status -> sendProgress(status) }' in plugin_source


def test_realtime_output_is_safely_sliced_to_current_hop_for_all_paths():
    source = PLUGIN.read_text(encoding='utf-8')
    session_source = source[source.index('private class RealtimeRvcSession'):source.index('    private class DecibelMeterSession')]

    engine_source = ENGINE.read_text(encoding='utf-8')
    streaming_source = engine_source[engine_source.index('    inner class RvcStreamingSession'):engine_source.index('        private fun combineContext')]

    assert 'fun inferPcm16(inferenceWindow: ShortArray, outputFrameCount: Int): ShortArray' in streaming_source
    assert 'val outputFrames = outputFrameCount.coerceIn(1, inferenceWindow.size)' in streaming_source
    assert 'val currentAudio = resizedAudio.copyOfRange(' in streaming_source
    assert '(resizedAudio.size - outputFrames).coerceAtLeast(0),' in streaming_source
    assert 'val filteredAudio = applyOutputAudioFilters(currentAudio, noiseGateDb, outputDenoiseEnabled, vocalRangeFilterEnabled, sampleRate)' in streaming_source
    assert 'return floatToPcm16(resizeAudio(filteredAudio, outputFrames))' in streaming_source
    assert 'output.copyOfRange(output.size - realtimeHopFrames, output.size)' not in session_source


def test_native_mode_guard_serializes_active_inference_modes_across_plugins():
    guard_source = MODE_GUARD.read_text(encoding='utf-8')
    plugin_source = PLUGIN.read_text(encoding='utf-8')

    assert 'enum class NativeActiveMode' in guard_source
    assert 'AUDIO_INFERENCE' in guard_source
    assert 'REALTIME_INFERENCE' in guard_source
    assert 'VOICE_CHANGER' in guard_source
    assert 'object NativeModeGuard' in guard_source
    assert 'fun tryEnter(mode: NativeActiveMode): NativeModeGuardToken?' in guard_source
    assert 'fun busyMessage(): String' in guard_source

    infer_source = plugin_source[plugin_source.index('private fun infer(call: MethodCall, result: Result)'):plugin_source.index('    private fun getVersion', plugin_source.index('private fun infer(call: MethodCall, result: Result)'))]
    realtime_source = plugin_source[plugin_source.index('private fun startRealtimeInference(call: MethodCall, result: Result)'):plugin_source.index('    private fun stopRealtimeInference', plugin_source.index('private fun startRealtimeInference(call: MethodCall, result: Result)'))]
    session_source = plugin_source[plugin_source.index('private class RealtimeRvcSession'):plugin_source.index('    private class DecibelMeterSession')]

    assert 'NativeModeGuard.tryEnter(NativeActiveMode.AUDIO_INFERENCE)' in infer_source
    assert 'NativeModeGuard.tryEnter(NativeActiveMode.REALTIME_INFERENCE)' in realtime_source
    assert 'result.error("INFERENCE_BUSY", NativeModeGuard.busyMessage(), null)' in infer_source
    assert 'result.error("INFERENCE_BUSY", NativeModeGuard.busyMessage(), null)' in realtime_source
    assert 'modeToken.release()' in infer_source
    assert 'private val modeToken: NativeModeGuardToken,' in session_source
    assert 'modeToken.release()' in session_source


def test_realtime_uses_demo_recording_flow_instead_of_cached_streaming_engine():
    source = PLUGIN.read_text(encoding='utf-8')
    session_source = source[source.index('private class RealtimeRvcSession'):source.index('    private class DecibelMeterSession')]
    service_source = service_realtime_source()

    assert 'RemoteRealtimeInferenceSession' in session_source
    assert 'realtimeEngine.inferPcm16(inferenceWindow, realtimeHopFrames)' in service_source


def test_realtime_stop_cancels_immediately_without_tail_inference_or_flush():
    session_source = service_realtime_source()

    assert 'private val crossfadeSamples = (boundedSampleRate * crossfadeLength.coerceIn(0.0, 1.0)).toInt()' in session_source
    assert 'private var previousOutputTail = ShortArray(0)' in session_source
    assert 'private val inferenceLock = Object()' in session_source
    assert 'private val stopped = AtomicBoolean(false)' in session_source
    assert 'if (!stopped.compareAndSet(false, true)) return' in session_source
    stop_source = session_source[session_source.index('        fun stop()'):session_source.index('        private fun appendRealtimeSamples')]
    assert 'processRemainingRealtimeSamples()' not in stop_source
    assert 'flushQueuedRealtimeOutput()' not in stop_source
    assert 'worker?.cancel()' in stop_source
    assert 'synchronized(inferenceLock) { realtimeEngine.inferPcm16(inferenceWindow, realtimeHopFrames) }' in session_source
    assert 'private fun processRemainingRealtimeSamples()' in session_source
    assert 'pendingSamples.clear()' in session_source
    assert 'private fun applyOutputCrossfade(outputSize: Int)' in session_source
    assert 'previousOutputTail[previousOutputTail.size - fadeSize + index]' in session_source


def test_realtime_inference_reports_worker_failures_and_defaults_noise_gate_to_35db():
    source = PLUGIN.read_text(encoding='utf-8')
    screen_source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')
    bridge_source = (ROOT / 'lib/services/rvc_bridge.dart').read_text(encoding='utf-8')
    realtime_source = source[source.index('private fun startRealtimeInference(call: MethodCall, result: Result)'):source.index('    private fun stopRealtimeInference')]
    session_source = service_realtime_source()
    start_source = session_source[session_source.index('fun start()'):session_source.index('        fun stop()')]

    assert 'double _noiseGateDb = 35.0;' in screen_source
    assert "prefs.getDouble('realtimeNoiseGateDb') ?? 35.0" in screen_source
    assert '_noiseGateDb = 35.0;' in screen_source
    assert 'double noiseGateDb = 35.0' in bridge_source
    assert 'val noiseGateDb = call.argument<Double>("noiseGateDb") ?: 35.0' in realtime_source
    assert 'catch (error: CancellationException)' in start_source
    assert 'catch (error: Throwable)' in start_source
    assert 'sendError(replyTo, "REALTIME_FAILED", error.message ?: error.javaClass.simpleName)' in start_source


def test_realtime_hubert_padding_mask_marks_only_aligned_padding_tail():
    source = ENGINE.read_text(encoding='utf-8')
    hubert_source = source[source.index('    private fun extractHubertFeatures('):source.index('    private fun alignHubertInputLength')]

    assert 'val paddedLength = alignHubertInputLength(audio16k.size)' in hubert_source
    assert 'val paddedAudio = FloatArray(paddedLength)' in hubert_source
    assert 'System.arraycopy(audio16k, 0, paddedAudio, 0, audio16k.size)' in hubert_source
    assert 'val mask = ByteArray(paddedLength)' in hubert_source
    assert 'for (index in audio16k.size until paddedLength)' in hubert_source
    assert 'mask[index] = 1' in hubert_source
    assert 'val inputShape = longArrayOf(1, paddedLength.toLong())' in hubert_source
    assert 'FloatBuffer.wrap(paddedAudio), inputShape' in hubert_source
    assert 'OnnxTensor.createTensor(environment, ByteBuffer.wrap(mask), inputShape, OnnxJavaType.BOOL)' in hubert_source


def test_realtime_pending_input_backlog_is_bounded_to_avoid_oom():
    session_source = service_realtime_source()

    assert 'private val maxPendingInputSamples = inferenceWindow.size * 3' in session_source
    assert 'if (pendingSamples.size > maxPendingInputSamples) {' in session_source
    assert 'pendingSamples.dropFirst(pendingSamples.size - maxPendingInputSamples)' in session_source


def test_realtime_uses_primitive_short_buffers_instead_of_boxed_arraylists():
    session_source = service_realtime_source()

    assert 'ArrayList<Short>' not in session_source
    assert 'ShortArray' in session_source


def test_offline_synthesize_voice_no_longer_uses_arraylist_float_output():
    source = ENGINE.read_text(encoding='utf-8')
    synthesize_source = source[source.index('    private fun synthesizeVoice'):source.index('    private fun runVoiceChunk')]

    assert 'val output = ArrayList<Float>()' not in synthesize_source
    assert 'output.toFloatArray().normalizePeak()' not in synthesize_source


def test_voice_changer_recording_no_longer_boxes_full_recording_in_arraylist_short():
    source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerRecorder.kt').read_text(encoding='utf-8')
    recording_source = source[source.index('    fun startRecording()'):source.index('    fun stopRecordingAndProcess()')]

    assert 'ArrayList<Short>()' not in recording_source
    assert 'ByteArrayOutputStream' in recording_source or 'ShortArray' in recording_source


def test_realtime_audio_record_failures_are_reported_instead_of_spinning_silent():
    session_source = service_realtime_source()
    start_source = session_source[session_source.index('fun start()'):session_source.index('        fun stop()')]

    assert 'if (read < 0) {' in start_source
    assert 'error("实时音频采集失败：$read")' in start_source
    assert 'if (read == 0) {' in start_source
    assert 'delay(REALTIME_PROCESS_POLL_MS)' in start_source


def test_realtime_session_reports_capture_inference_and_output_status_events():
    source = PLUGIN.read_text(encoding='utf-8')
    realtime_source = source[source.index('private fun startRealtimeInference(call: MethodCall, result: Result)'):source.index('    private fun stopRealtimeInference')]
    session_source = service_realtime_source()

    assert 'onStatus = { status -> sendProgress(status) }' in realtime_source
    for status in ['实时采集中', '实时推理中', '实时输出中']:
        assert f'reportRealtimeStatus("{status}"' in session_source
    for key in [
        'pending_input_samples',
        'input_backlog_ms',
        'queued_output_samples',
        'output_delay_target_samples',
        'audio_track_started',
        'last_infer_ms',
        'last_output_samples',
        'non_empty_output_count',
        'empty_output_count',
        'output_channel_drop_count',
        'output_channel_dropped_samples',
        'audio_track_write_count',
        'audio_track_written_samples',
        'last_input_peak',
        'last_raw_output_peak',
        'last_output_peak',
        'realtime_hop_frames',
        'inference_window_frames',
    ]:
        assert f'"{key}"' in session_source
    assert 'pendingSamples.size.toLong() * 1_000 / boundedSampleRate' in session_source


def test_realtime_streaming_applies_complete_output_denoise_to_output_tail():
    source = ENGINE.read_text(encoding='utf-8')
    streaming_source = source[source.index('    inner class RvcStreamingSession'):source.index('    private fun requestNativeMemoryCleanup')]
    infer_source = streaming_source[streaming_source.index('        fun inferPcm16'):streaming_source.index('        private fun combineContext')]

    assert 'val mono16k = applyRealtimeInputAudioFilters(' in infer_source
    assert 'val currentAudio = resizedAudio.copyOfRange(' in infer_source
    assert 'applyOutputAudioFilters(currentAudio, noiseGateDb, outputDenoiseEnabled, vocalRangeFilterEnabled, sampleRate)' in infer_source
    assert 'return floatToPcm16(resizeAudio(filteredAudio, outputFrames))' in infer_source


def test_realtime_streaming_mix_rms_uses_hubert_timebase_for_short_windows():
    source = ENGINE.read_text(encoding='utf-8')
    streaming_source = source[source.index('    inner class RvcStreamingSession'):source.index('        private fun combineContext')]

    assert 'val matchedAudio = mixRms(formantAudio, mono16k, rmsMixRate, HUBERT_SAMPLE_RATE)' in streaming_source
    assert 'val matchedAudio = mixRms(formantAudio, mono16k, rmsMixRate, sampleRate)' not in streaming_source


def test_realtime_audio_track_zero_write_is_not_silently_dropped():
    session_source = service_realtime_source()
    write_source = session_source[session_source.index('        private fun writeAudioTrackFully'):session_source.index('        private fun processRealtimeChunk')]

    assert 'check(written >= 0) { "实时音频写入失败：$written" }' in write_source
    assert 'if (written == 0) return' not in write_source
    assert 'delayAudioTrackRetry()' in write_source
    assert 'check(written > 0) { "实时音频写入未接收数据" }' in write_source


def test_remote_inference_client_does_not_wait_forever_after_disconnect_or_timeout():
    client_source = REMOTE_CLIENT.read_text(encoding='utf-8')
    infer_source = client_source[client_source.index('    fun infer(request: RvcInferenceRequest): String'):client_source.index('    private fun RvcInferenceRequest.toBundle')]

    assert 'setRemoteError("INFERENCE_DISCONNECTED", "推理进程已断开")' in infer_source
    assert 'override fun onBindingDied(name: ComponentName?)' in infer_source
    assert 'override fun onNullBinding(name: ComponentName?)' in infer_source
    assert 'val deadlineMs = SystemClock.elapsedRealtime() + REMOTE_INFERENCE_TIMEOUT_MS' not in infer_source
    assert 'setRemoteError("INFERENCE_TIMEOUT", "推理进程响应超时")' not in infer_source
    assert 'REMOTE_INFERENCE_TIMEOUT_MS' not in client_source
    assert 'setRemoteError("INFERENCE_STALLED", "推理进程长时间无进度")' in infer_source
    assert 'setRemoteError("INFERENCE_HEARTBEAT_TIMEOUT", "推理进程心跳中断")' in infer_source


def test_realtime_streaming_skips_adaptive_input_denoise_for_short_hops():
    source = ENGINE.read_text(encoding='utf-8')
    streaming_source = source[source.index('    inner class RvcStreamingSession'):source.index('        private fun combineContext')]

    assert 'applyRealtimeInputAudioFilters(' in streaming_source
    assert 'applyInputAudioFilters(resampleMono(mono, sampleRate, HUBERT_SAMPLE_RATE)' not in streaming_source
    assert 'private fun applyRealtimeInputAudioFilters(audio: FloatArray, noiseGateDb: Double, vocalRangeFilterEnabled: Boolean, sampleRate: Int): FloatArray' in source
    realtime_filter_source = source[source.index('    private fun applyRealtimeInputAudioFilters'):source.index('    private fun applyInputAudioFilters')]
    assert 'applyAdaptiveDenoiseGate' not in realtime_filter_source


def test_realtime_status_uses_current_step_and_reports_signal_peaks_to_flutter():
    service_source = INFERENCE_SERVICE.read_text(encoding='utf-8')
    screen_source = (ROOT / 'lib/screens/realtime_inference_screen.dart').read_text(encoding='utf-8')

    send_status_source = service_source[service_source.index('    private fun sendRealtimeStatus'):service_source.index('    private fun sendError')]
    assert 'putString(InferenceIpcProtocol.KEY_STEP, diagnostics["status"] as? String ?: "实时推理中")' in send_status_source
    assert "final lastInputPeak = event['last_input_peak'];" in screen_source
    assert "final lastRawOutputPeak = event['last_raw_output_peak'];" in screen_source
    assert "final lastOutputPeak = event['last_output_peak'];" in screen_source
    assert "峰值 入" in screen_source


def test_realtime_reports_signal_peaks_to_distinguish_mute_from_playback_failure():
    session_source = service_realtime_source()

    assert 'private var lastInputPeak = 0' in session_source
    assert 'private var lastRawOutputPeak = 0' in session_source
    assert 'private var lastOutputPeak = 0' in session_source
    assert 'lastInputPeak = peakAbs(inferenceWindow, inferenceWindow.size)' in session_source
    assert 'lastRawOutputPeak = peakAbs(output, output.size)' in session_source
    assert 'lastOutputPeak = peakAbs(outputBuffer, outputSize)' in session_source
    assert 'private fun peakAbs(samples: ShortArray, size: Int): Int' in session_source


def test_voice_changer_processing_does_not_read_full_output_wav_just_to_measure_duration():
    source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerRecorder.kt').read_text(encoding='utf-8')
    process_source = source[source.index('    private fun processRecording'):source.index('    fun targetSaveDisplayPath()')]

    assert 'val wavInfo = readWavInfo(outputFile!!)' in process_source
    assert 'processedSampleCount = wavInfo.sampleCount' in process_source
    assert 'processedSampleRate = wavInfo.sampleRate' in process_source
    assert 'readWavPcm(outputFile!!)' not in process_source
    assert 'data class WavInfo' in source
    assert 'private fun readWavInfo(file: File): WavInfo' in source


def test_native_modes_request_memory_cleanup_after_processing_completion_points():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    recorder_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerRecorder.kt').read_text(encoding='utf-8')
    engine_source = ENGINE.read_text(encoding='utf-8')
    infer_source = plugin_source[plugin_source.index('private fun infer(call: MethodCall, result: Result)'):plugin_source.index('    private fun getVersion')]
    recorder_process_source = recorder_source[recorder_source.index('    private fun processRecording'):recorder_source.index('    fun targetSaveDisplayPath()')]

    assert 'requestNativeMemoryCleanup()' in infer_source
    assert 'requestNativeMemoryCleanup()' in recorder_process_source
    assert 'requestNativeMemoryCleanup()' in engine_source


def test_native_inference_engine_runs_in_dedicated_android_process_for_heap_release():
    manifest_source = MANIFEST.read_text(encoding='utf-8')
    service_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/InferenceProcessService.kt').read_text(encoding='utf-8')
    client_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/RemoteInferenceClient.kt').read_text(encoding='utf-8')

    assert 'android:name=".InferenceProcessService"' in manifest_source
    assert 'android:process=":inference"' in manifest_source
    assert 'android:exported="false"' in manifest_source
    assert 'class InferenceProcessService : Service()' in service_source
    assert 'RvcInferenceEngine(' in service_source
    assert 'stopSelf()' in service_source
    assert 'Process.killProcess(Process.myPid())' in service_source
    assert 'class RemoteInferenceClient' in client_source
    assert 'Intent(context, InferenceProcessService::class.java)' in client_source


def test_remote_inference_process_stays_warm_briefly_to_reduce_dedicated_process_overhead():
    service_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/InferenceProcessService.kt').read_text(encoding='utf-8')
    client_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/RemoteInferenceClient.kt').read_text(encoding='utf-8')

    assert 'runCatching { appContext.startService(intent) }' in client_source
    assert 'private val replyThread = sharedReplyThread' in client_source
    assert 'val sharedReplyThread = HandlerThread("RemoteInferenceReply").apply { start() }' in client_source
    assert 'HandlerThread("RemoteInferenceReply").apply { start() }' not in client_source[client_source.index('    fun infer('):client_source.index('    private fun RvcInferenceRequest.toBundle')]
    assert 'cancelScheduledShutdown()' in service_source[service_source.index('    private fun handleInferAudio'):service_source.index('    private fun handleOpenRealtime')]
    assert 'private val shutdownRunnable = Runnable {' in service_source
    assert 'stopSelf()' in service_source[service_source.index('private val shutdownRunnable = Runnable {'):service_source.index('private var activeCancellationToken')]
    assert 'mainHandler.postDelayed(shutdownRunnable, REMOTE_PROCESS_SHUTDOWN_DELAY_MS)' in service_source
    assert 'REMOTE_PROCESS_SHUTDOWN_DELAY_MS = 30_000L' in service_source


def test_warm_remote_process_does_not_yet_reuse_offline_onnx_sessions():
    service_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/InferenceProcessService.kt').read_text(encoding='utf-8')
    engine_source = ENGINE.read_text(encoding='utf-8')
    infer_audio_source = service_source[service_source.index('    private fun handleInferAudio'):service_source.index('    private fun handleOpenRealtime')]
    infer_source = engine_source[engine_source.index('    fun infer(request: RvcInferenceRequest): String'):engine_source.index('    fun openStreamingSession')]

    assert 'RvcInferenceEngine(' in infer_audio_source
    assert 'ResumableInferenceJobStore(filesDir)' in infer_audio_source
    assert 'cachedOffline' not in service_source
    assert 'createCpuOptimizedSession(environment, hubertModel)' in infer_source
    assert 'createCpuOptimizedSession(environment, rmvpeModel)' in infer_source
    assert 'createCpuOptimizedSession(environment, voiceModelFile, voiceSessionThreadCount)' in infer_source


def test_offline_remote_inference_success_path_does_not_force_gc_before_warm_shutdown():
    service_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/InferenceProcessService.kt').read_text(encoding='utf-8')
    infer_audio_source = service_source[service_source.index('    private fun handleInferAudio'):service_source.index('    private fun handleOpenRealtime')]
    infer_finally_source = infer_audio_source[infer_audio_source.index('            } finally {'):]

    assert 'requestNativeMemoryCleanup()' not in infer_finally_source
    assert 'scheduleShutdown()' in infer_finally_source


def test_audio_and_voice_changer_processing_use_remote_inference_client():
    plugin_source = PLUGIN.read_text(encoding='utf-8')
    recorder_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerRecorder.kt').read_text(encoding='utf-8')
    infer_source = plugin_source[plugin_source.index('private fun infer(call: MethodCall, result: Result)'):plugin_source.index('    private fun getVersion')]
    recorder_process_source = recorder_source[recorder_source.index('    private fun processRecording'):recorder_source.index('    fun targetSaveDisplayPath()')]

    assert 'RemoteInferenceClient(appContext).infer(' in infer_source
    assert 'RvcInferenceEngine(File(getModelsDirectory())).infer(' not in infer_source
    assert 'RemoteInferenceClient(context).infer(' in recorder_process_source
    assert 'RvcInferenceEngine(modelsDir).infer(' not in recorder_process_source


def test_realtime_stop_clears_buffers_and_requests_memory_cleanup_after_native_close():
    session_source = service_realtime_source()
    stop_source = session_source[session_source.index('        fun stop()'):session_source.index('        private fun appendRealtimeSamples')]

    assert 'pendingSamples.clear()' in stop_source
    assert 'queuedOutputSamples.clear()' in stop_source
    assert 'previousOutputTail = ShortArray(0)' in stop_source
    assert 'worker = null' in stop_source
    assert 'requestNativeMemoryCleanup()' in stop_source
    assert stop_source.index('realtimeEngine.close()') < stop_source.index('requestNativeMemoryCleanup()')


def test_offline_inference_requests_memory_cleanup_after_mode_release():
    source = PLUGIN.read_text(encoding='utf-8')
    infer_source = source[source.index('private fun infer(call: MethodCall, result: Result)'):source.index('    private fun getVersion')]

    assert 'modeToken.release()' in infer_source
    assert 'requestNativeMemoryCleanup()' in infer_source
    assert infer_source.index('modeToken.release()') < infer_source.index('requestNativeMemoryCleanup()')


def test_voice_changer_stop_releases_recorder_and_processing_references():
    service_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerOverlayService.kt').read_text(encoding='utf-8')
    recorder_source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerRecorder.kt').read_text(encoding='utf-8')
    destroy_source = service_source[service_source.index('    override fun onDestroy()'):service_source.index('    private fun showOverlay')]
    cancel_source = recorder_source[recorder_source.index('    fun cancelProcessing()'):recorder_source.index('    fun togglePlayback()')]
    process_source = recorder_source[recorder_source.index('    private fun processRecording'):recorder_source.index('    fun targetSaveDisplayPath()')]

    assert 'recorder?.cancelProcessing()' in destroy_source
    assert 'recorder = null' in destroy_source
    assert destroy_source.index('recorder?.cancelProcessing()') < destroy_source.index('recorder = null')
    assert 'processingToken = null' in cancel_source
    assert 'processingThread = null' in cancel_source
    assert 'processedSampleCount = 0' in cancel_source
    assert 'processedSampleRate = 0' in cancel_source
    assert 'processingToken = null' in process_source
    assert 'processingThread = null' in process_source


def test_realtime_output_starts_after_wall_clock_wait_even_when_delay_queue_is_short():
    session_source = service_realtime_source()

    assert 'private val outputDelayBufferMaxWaitMs = (delayBufferSeconds.coerceIn(0.0, 60.0) * 1_000).roundToLong()' in session_source
    assert 'private val outputDelayStartedAtMs = SystemClock.elapsedRealtime()' in session_source
    assert 'SystemClock.elapsedRealtime()' in session_source
    assert 'shouldStartAudioTrackLocked()' in session_source
    assert 'if (outputStartupThresholdFrames <= 0) return true' in session_source
    assert 'queuedOutputSamples.size >= outputStartupThresholdFrames' in session_source
    assert 'now - outputDelayStartedAtMs >= outputDelayBufferMaxWaitMs' in session_source
    assert 'if (queuedOutputSamples.isEmpty()) return false' in session_source
    assert 'firstQueuedOutputAtMs' not in session_source


def test_android_voice_chunks_use_continuous_noise_across_chunks():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'val continuousNoise = createContinuousNoise(frameCount)' in source
    assert 'noise = copyNoiseSlice(continuousNoise, chunkStart, chunkFrameCount, frameCount, VOICE_CHUNK_FRAMES)' in source
    run_voice_chunk = source[source.index('private fun runVoiceChunk'):source.index('        val tensors = linkedMapOf<String, OnnxTensor>()')]
    assert 'Random.nextFloat()' not in run_voice_chunk
    assert 'blendBoundarySample(previous[previousIndex], chunk[index], fadeOut, fadeIn)' in source
    assert 'appendTrimmedChunkAudio(' not in source


def test_android_inference_fuses_optional_index_features_before_voice_model():
    source = ENGINE.read_text(encoding='utf-8')
    infer_start = source.index('    fun infer(request: RvcInferenceRequest): String')
    infer_source = source[infer_start:source.index('    private fun decodeAudio', infer_start)]

    assert 'val index = loadOptionalFeatureIndex(request.indexPath, request.onProgress)' in infer_source
    assert 'val indexedPhone = fuseIndexFeatures(phone, frameCount, index, request.indexRate)' in infer_source
    assert 'phone = protectedPhone,' in infer_source
    assert 'private fun loadFeatureIndex(indexFile: File): FeatureIndex' in source
    assert 'private fun fuseIndexFeatures(' in source
    assert 'FEATURE_INDEX_MAGIC = "URVCIDX1"' in source


def test_android_standard_faiss_index_does_not_abort_inference():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'val index = loadOptionalFeatureIndex(request.indexPath, request.onProgress)' in source
    assert 'private fun loadOptionalFeatureIndex(indexPath: String?, onProgress: (Double, String) -> Unit): FeatureIndex?' in source
    assert 'onProgress(28.0, "索引格式暂不支持，请先导入 mobile.index")' in source
    assert 'return null' in source


def test_android_voice_chunking_avoids_context_window_that_breaks_where_broadcast():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'private fun alignVoiceFrameCount(frameCount: Int): Int' in source
    assert 'return max(2, frameCount + frameCount % 2)' in source
    assert 'VOICE_CENTER_FRAMES = 80' in source
    assert 'VOICE_CONTEXT_FRAMES = (VOICE_CHUNK_FRAMES - VOICE_CENTER_FRAMES) / 2' in source
    assert 'val desiredContextStart' not in source
    assert 'val contextStart' not in source
    assert 'val contextFrameCount' not in source
    assert 'requestedVoiceChunkFrames.coerceIn' not in source
    assert 'noise = copyNoiseSlice(continuousNoise, chunkStart, chunkFrameCount, frameCount, VOICE_CHUNK_FRAMES)' in source
    assert 'runVoiceChunk(environment, voiceSession, chunk.phone, chunk.pitch, chunk.pitchf, chunk.noise, cancellationToken)' in source
    assert 'appendCrossfadedChunkAudio(chunkAudio, chunk.centerOffsetFrames, chunk.centerFrameCount, VOICE_CHUNK_FRAMES, outputChunks)' in source


def test_android_inference_checks_cancel_around_each_fixed_voice_chunk():
    source = ENGINE.read_text(encoding='utf-8')
    synthesize_source = source[source.index('    private fun synthesizeVoice'):source.index('    private fun runVoiceChunk')]

    assert 'for (centerStart in 0 until frameCount step VOICE_CENTER_FRAMES)' in synthesize_source
    assert synthesize_source.count('cancellationToken.throwIfCancelled()') >= 2
    assert 'val chunkAudios = runBlocking(Dispatchers.Default)' in synthesize_source
    assert 'runVoiceChunk(environment, voiceSession, chunk.phone, chunk.pitch, chunk.pitchf, chunk.noise, cancellationToken)' in synthesize_source
    assert 'OrtSession.RunOptions()' in source
    assert 'activeRunOptions.toList().forEach { it.setTerminate(true) }' in source
    assert 'cancellationToken.bindRunOptions(runOptions)' in source
    assert 'voiceSession.run(tensors, runOptions)' in source
    before_run = synthesize_source[:synthesize_source.index('runVoiceChunk(environment, voiceSession, chunk.phone, chunk.pitch, chunk.pitchf, chunk.noise, cancellationToken)')]
    after_run = synthesize_source[synthesize_source.index('runVoiceChunk(environment, voiceSession, chunk.phone, chunk.pitch, chunk.pitchf, chunk.noise, cancellationToken)'):]
    assert 'cancellationToken.throwIfCancelled()' in before_run
    assert 'cancellationToken.throwIfCancelled()' in after_run


def test_android_inference_filters_low_level_noise_and_checks_cancellation():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'class CancellationToken' in source
    assert 'fun cancel()' in source
    assert 'fun throwIfCancelled()' in source
    assert 'val cancellationToken: CancellationToken,' in source
    assert 'val noiseGateDb: Double,' in source
    assert 'val voiceChunkFrames: Int,' not in source
    assert 'request.cancellationToken.throwIfCancelled()' in source
    assert 'val gatedMono16k = applyInputAudioFilters(mono16k, request.noiseGateDb, request.outputDenoiseEnabled, request.vocalRangeFilterEnabled, HUBERT_SAMPLE_RATE)' in source
    assert 'private fun applyNoiseGate(audio: FloatArray, noiseGateDb: Double): FloatArray' in source
    assert 'val sampleLevelDb = 20.0 * log10(abs(audio[index]).coerceAtLeast(NOISE_GATE_EPSILON).toDouble()) + 100.0' in source
    assert 'if (sampleLevelDb <= db) 0f else audio[index]' in source
    assert 'NOISE_GATE_EPSILON = 1e-5f' in source


def test_android_pth_quality_parameters_affect_pitch_features_and_output_audio():
    source = ENGINE.read_text(encoding='utf-8')
    infer_start = source.index('    fun infer(request: RvcInferenceRequest): String')
    infer_source = source[infer_start:source.index('    private fun decodeAudio', infer_start)]

    assert 'val filteredPitchf = applyMedianPitchFilter(pitchf, request.filterRadius)' in infer_source
    assert 'val protectedPhone = applyProtectBlend(indexedPhone, phone, filteredPitchf, request.protectRate)' in infer_source
    assert 'val formantAudio = applyFormant(audio, request.formant)' in infer_source
    assert 'val matchedAudio = mixRms(formantAudio, gatedMono16k, request.rmsMixRate, request.sampleRate)' in infer_source
    assert 'private fun applyMedianPitchFilter(pitchf: FloatArray, radius: Int): FloatArray' in source
    assert 'private fun applyProtectBlend(' in source
    assert 'private fun mixRms(converted: FloatArray, source16k: FloatArray, rmsMixRate: Double, sampleRate: Int): FloatArray' in source


def test_android_parallel_chunk_count_uses_voice_session_pool_for_offline_and_voice_changer():
    source = ENGINE.read_text(encoding='utf-8')
    infer_source = source[source.index('    fun infer(request: RvcInferenceRequest): String'):source.index('    fun openStreamingSession(')]
    synthesize_source = source[source.index('    private fun synthesizeVoice'):source.index('    private fun runVoiceChunk')]

    assert 'val sessionPoolSize = request.parallelChunkCount.coerceIn(1, MAX_PARALLEL_CHUNK_COUNT)' in infer_source
    assert 'val voiceSessionThreadCount = voiceChunkThreadCount(sessionPoolSize)' in infer_source
    assert 'val voiceSessions = MutableList(sessionPoolSize) {' in infer_source
    assert 'createCpuOptimizedSession(environment, voiceModelFile, voiceSessionThreadCount)' in infer_source
    assert 'voiceSessions = voiceSessions,' in infer_source
    assert 'voiceSessions.asReversed().forEach { it.close() }' in infer_source
    assert 'voiceSessions: List<OrtSession>' in synthesize_source
    assert 'val activeVoiceSessions = voiceSessions.take(actualParallelChunkCount)' in synthesize_source
    assert 'chunkBatch.mapIndexed { index, chunk ->' in synthesize_source
    assert 'val voiceSession = activeVoiceSessions[index]' in synthesize_source


def test_android_voice_chunk_thread_count_scales_with_session_pool_size():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'private fun voiceChunkThreadCount(workerCount: Int): Int' in source
    assert 'max(1, inferenceThreadCount() / workerCount.coerceAtLeast(1))' in source


def test_android_realtime_output_keeps_reservoir_queue_but_uses_small_startup_threshold():
    source = INFERENCE_SERVICE.read_text(encoding='utf-8')

    assert 'private val outputDelayBufferFrames = (boundedSampleRate * delayBufferSeconds.coerceIn(0.0, 60.0)).toInt()' in source
    assert 'private val outputStartupThresholdFrames = if (outputDelayBufferFrames <= 0) 0 else minOf(outputDelayBufferFrames, realtimeHopFrames * REALTIME_OUTPUT_STARTUP_HOPS)' in source
    assert 'if (outputStartupThresholdFrames <= 0) return true' in source
    assert 'if (queuedOutputSamples.size >= outputStartupThresholdFrames) return true' in source
    assert 'if (queuedOutputSamples.size >= outputDelayBufferFrames) return true' not in source
    assert 'reportRealtimeStatus("实时输出中")' in source[source.index('private fun writeRealtimeOutput'):source.index('private fun drainQueuedOutputLocked')]
    assert 'const val REALTIME_OUTPUT_STARTUP_HOPS = 2' in source
