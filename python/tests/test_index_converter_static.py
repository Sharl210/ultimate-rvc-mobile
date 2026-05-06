from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONVERTER = ROOT / 'python/convert_faiss_index.py'
REQUIREMENTS = ROOT / 'python/requirements-colab.txt'
ENGINE = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/RvcInferenceEngine.kt'


def test_repo_includes_faiss_index_converter_for_mobile_index_format():
    source = CONVERTER.read_text(encoding='utf-8')

    assert 'import faiss' in source
    assert 'FEATURE_INDEX_MAGIC = b"URVCIDX1"' in source
    assert 'index = faiss.read_index(str(input_path))' in source
    assert 'vectors = reconstruct_vectors(index)' in source
    assert 'return reconstruct_ivf_flat_vectors(index)' in source
    assert 'faiss.extract_index_ivf(index)' in source
    assert 'invlists.get_codes(list_no)' in source
    assert 'output.write(FEATURE_INDEX_MAGIC)' in source
    assert 'output.write(struct.pack("<i", vectors.shape[0]))' in source
    assert 'vectors.astype("<f4", copy=False).tofile(output)' in source


def test_converter_documents_standard_rvc_index_usage():
    source = CONVERTER.read_text(encoding='utf-8')

    assert 'python3 python/convert_faiss_index.py input.index output.mobile.index' in source
    assert '标准 RVC/FAISS .index' in source
    assert '移动端可读取的 .mobile.index' in source


def test_python_requirements_include_cpu_faiss_for_conversion():
    source = REQUIREMENTS.read_text(encoding='utf-8')

    assert 'faiss-cpu' in source


def test_android_unsupported_index_message_points_to_app_converter():
    source = ENGINE.read_text(encoding='utf-8')

    assert '索引格式暂不支持，请先导入 mobile.index' in source
    assert 'mobile.index 转换页' not in source


def test_android_outputs_generated_audio_to_persistent_app_directory():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'val outputDir = File(modelsDir.parentFile ?: modelsDir, "outputs")' in source
    assert 'outputDir.mkdirs()' in source
    assert 'val outputPath = File(outputDir, songFile.nameWithoutExtension + ".rvc.wav")' in source


def test_android_imports_mobile_index_without_duplicating_extension():
    source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/RVCPlugin.kt').read_text(encoding='utf-8')

    assert 'val outputDir = File(appContext.filesDir, "indexes")' in source
    assert 'val output = File(outputDir, source.name)' in source
    assert 'source.nameWithoutExtension + ".mobile.index"' not in source


def test_android_voice_chunks_use_safe_attention_frame_count():
    source = ENGINE.read_text(encoding='utf-8')

    assert 'VOICE_CHUNK_FRAMES = 200' in source
    assert 'VOICE_CENTER_FRAMES = 80' in source
    assert 'val actualFrameCount = phone100Hz.size / HUBERT_FEATURE_SIZE' in source
    assert 'val frameCount = alignVoiceFrameCount(actualFrameCount)' in source
    assert 'requestedVoiceChunkFrames.coerceIn' not in source
    assert 'noise = copyNoiseSlice(continuousNoise, chunkStart, chunkFrameCount, frameCount, VOICE_CHUNK_FRAMES)' in source
    assert 'runVoiceChunk(environment, voiceSession, chunk.phone, chunk.pitch, chunk.pitchf, chunk.noise, cancellationToken)' in source
    assert 'phone_lengths"' in source
    assert 'LongBuffer.wrap(longArrayOf(VOICE_CHUNK_FRAMES.toLong()))' in source
    assert 'LongBuffer.wrap(longArrayOf(voiceChunkFrames.toLong()))' not in source
    assert 'LongBuffer.wrap(longArrayOf(chunkFrameCount.toLong()))' not in source


def test_android_hubert_input_length_avoids_attention_mask_boundary_mismatch():
    source = ENGINE.read_text(encoding='utf-8')
    hubert_source = source[source.index('    private fun extractHubertFeatures('):source.index('    private fun alignHubertInputLength')]
    align_source = source[source.index('    private fun alignHubertInputLength'):source.index('    private fun calculateHubertFrameCount')]
    frame_count_source = source[source.index('    private fun calculateHubertFrameCount'):source.index('    private fun repeatFeaturesForRvc')]

    assert 'private fun alignHubertInputLength(length: Int): Int' in source
    assert 'var candidate = max(HUBERT_MIN_SAFE_INPUT_LENGTH, length)' in align_source
    assert 'val frameCount = calculateHubertFrameCount(candidate)' in align_source
    assert 'if (frameCount > 0 && frameCount % 2 == 0 && candidate % frameCount != 0)' in align_source
    assert 'candidate++' in align_source
    assert 'private fun calculateHubertFrameCount(length: Int): Int' in source
    assert 'HUBERT_CONV_KERNELS.indices' in frame_count_source
    assert '(frameCount - kernel) / stride + 1' in frame_count_source
    assert 'HUBERT_CONV_KERNELS = intArrayOf(10, 3, 3, 3, 3, 2, 2)' in source
    assert 'HUBERT_CONV_STRIDES = intArrayOf(5, 2, 2, 2, 2, 2, 2)' in source
    assert 'HUBERT_MIN_SAFE_INPUT_LENGTH = 801' in source
    assert 'val paddedLength = alignHubertInputLength(audio16k.size)' in hubert_source
    assert 'val paddedAudio = FloatArray(paddedLength)' in hubert_source
    assert 'System.arraycopy(audio16k, 0, paddedAudio, 0, audio16k.size)' in hubert_source
    assert 'for (index in audio16k.size until paddedLength)' in hubert_source
    assert 'mask[index] = 1' in hubert_source
    assert 'val inputShape = longArrayOf(1, paddedLength.toLong())' in hubert_source
    assert 'FloatBuffer.wrap(paddedAudio)' in hubert_source
