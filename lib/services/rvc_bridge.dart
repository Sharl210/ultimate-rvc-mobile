import 'dart:async';
import 'package:flutter/services.dart';

class ResumableRvcJobMetadata {
  final String jobId;
  final double overallProgress;
  final String state;
  final int accumulatedElapsedMs;

  const ResumableRvcJobMetadata({
    required this.jobId,
    required this.overallProgress,
    required this.state,
    required this.accumulatedElapsedMs,
  });

  factory ResumableRvcJobMetadata.fromMap(Map<String, dynamic> map) {
    return ResumableRvcJobMetadata(
      jobId: map['jobId'] as String,
      overallProgress: (map['overallProgress'] as num).toDouble(),
      state: map['state'] as String,
      accumulatedElapsedMs: (map['accumulatedElapsedMs'] as num?)?.toInt() ?? 0,
    );
  }
}

class InferenceProgressSnapshot {
  final double progress;
  final String status;
  final int? runId;

  const InferenceProgressSnapshot({
    required this.progress,
    required this.status,
    this.runId,
  });
}

class ImportedFileHandle {
  final String path;
  final int referenceCount;
  final int lastUpdatedAtMs;

  const ImportedFileHandle({
    required this.path,
    required this.referenceCount,
    required this.lastUpdatedAtMs,
  });

  factory ImportedFileHandle.fromMap(Map<String, dynamic> map) {
    return ImportedFileHandle(
      path: map['path'] as String,
      referenceCount: (map['referenceCount'] as num).toInt(),
      lastUpdatedAtMs: (map['lastUpdatedAtMs'] as num).toInt(),
    );
  }
}

class PitchDetectionSnapshot {
  final double frequencyHz;
  final double confidence;
  final String note;

  const PitchDetectionSnapshot({
    required this.frequencyHz,
    required this.confidence,
    required this.note,
  });

  factory PitchDetectionSnapshot.fromMap(Map<String, dynamic> map) {
    return PitchDetectionSnapshot(
      frequencyHz: (map['frequencyHz'] as num?)?.toDouble() ?? 0.0,
      confidence: (map['confidence'] as num?)?.toDouble() ?? 0.0,
      note: (map['note'] as String?) ?? '—',
    );
  }
}

class RVCBridge {
  static const MethodChannel _channel = MethodChannel('ultimate_rvc');
  static const EventChannel _progressChannel = EventChannel('ultimate_rvc_progress');
  static const EventChannel _realtimeChannel = EventChannel('ultimate_rvc_realtime_status');
  static const EventChannel _decibelChannel = EventChannel('ultimate_rvc_decibel');
  static const EventChannel _pitchChannel = EventChannel('ultimate_rvc_pitch');

  Stream<Map<String, dynamic>>? _progressStream;
  final StreamController<InferenceProgressSnapshot> _progressSnapshots =
      StreamController<InferenceProgressSnapshot>.broadcast();
  StreamSubscription<Map<String, dynamic>>? _progressBroadcastSubscription;

  /// Initialize RVC bridge
  Future<void> initialize() async {
    try {
      _ensureProgressBroadcastAttached();
      await _channel.invokeMethod('initialize');
    } on PlatformException catch (e) {
      throw Exception('RVC 初始化失败：${e.message}');
    }
  }

  void _ensureProgressBroadcastAttached() {
    _progressStream ??= _progressChannel
        .receiveBroadcastStream()
        .map((data) => Map<String, dynamic>.from(data));
    _progressBroadcastSubscription ??= _progressStream!.listen((progress) {
      final percentValue = progress['percent'];
      final statusValue = progress['current_step'];
      if (percentValue is! num || statusValue is! String) return;
      final runIdValue = progress['run_id'];
      _progressSnapshots.add(InferenceProgressSnapshot(
        progress: percentValue.toDouble(),
        status: statusValue,
        runId: runIdValue is num ? runIdValue.toInt() : null,
      ));
    });
  }

  Stream<InferenceProgressSnapshot> progressSnapshots() {
    _ensureProgressBroadcastAttached();
    return _progressSnapshots.stream;
  }

  /// Check if models are available
  Future<bool> checkModels() async {
    try {
      final result = await _channel.invokeMethod('checkModels');
      return result as bool;
    } on PlatformException catch (e) {
      throw Exception('检查模型失败：${e.message}');
    }
  }

  Future<bool> isInferenceProcessRunning() async {
    try {
      final result = await _channel.invokeMethod('isInferenceProcessRunning');
      return result as bool? ?? false;
    } on PlatformException {
      return false;
    }
  }

  Future<void> showToast(String message) async {
    try {
      await _channel.invokeMethod('showToast', {'message': message});
    } on PlatformException catch (e) {
      throw Exception('显示提示失败：${e.message}');
    }
  }

  /// Run RVC inference
  Future<String> infer({
    required String songPath,
    required String modelPath,
    String? indexPath,
    double pitchChange = 0.0,
    double indexRate = 0.75,
    double formant = 0.0,
    int filterRadius = 3,
    double rmsMixRate = 0.25,
    double protectRate = 0.33,
    int sampleRate = 48000,
    double noiseGateDb = 35.0,
    bool outputDenoiseEnabled = true,
    bool vocalRangeFilterEnabled = true,
    int parallelChunkCount = 1,
    bool allowResume = false,
    required Function(double progress, String status) onProgress,
  }) async {
    try {
      final inferenceRunId = DateTime.now().microsecondsSinceEpoch;
      _ensureProgressBroadcastAttached();

      late final StreamSubscription<InferenceProgressSnapshot> subscription;
      subscription = progressSnapshots().listen((progress) {
        if (progress.runId != null && progress.runId != inferenceRunId) {
          return;
        }
        onProgress(progress.progress, progress.status);
      });

      try {
        final result = await _channel.invokeMethod('infer', {
          'songPath': songPath,
          'modelPath': modelPath,
          'indexPath': indexPath,
          'pitchChange': pitchChange,
          'indexRate': indexRate,
          'formant': formant,
          'filterRadius': filterRadius,
          'rmsMixRate': rmsMixRate,
          'protectRate': protectRate,
          'sampleRate': sampleRate,
          'noiseGateDb': noiseGateDb,
          'outputDenoiseEnabled': outputDenoiseEnabled,
          'vocalRangeFilterEnabled': vocalRangeFilterEnabled,
          'parallelChunkCount': parallelChunkCount,
          'inferenceRunId': inferenceRunId,
          'allowResume': allowResume,
        });

        return result as String;
      } finally {
        await subscription.cancel();
      }
    } on PlatformException catch (e) {
      throw Exception('RVC 推理失败：${e.message}');
    }
  }

  Future<void> stopInference() async {
    try {
      await _channel.invokeMethod('stopInference');
    } on PlatformException catch (e) {
      throw Exception('终止生成失败：${e.message}');
    }
  }

  Future<ResumableRvcJobMetadata?> getResumableJobMetadata({
    required String songPath,
    required String modelPath,
    String? indexPath,
    double pitchChange = 0.0,
    double indexRate = 0.75,
    double formant = 0.0,
    int filterRadius = 3,
    double rmsMixRate = 0.25,
    double protectRate = 0.33,
    int sampleRate = 48000,
    double noiseGateDb = 35.0,
    bool outputDenoiseEnabled = true,
    bool vocalRangeFilterEnabled = true,
  }) async {
    final result = await _channel.invokeMethod('getResumableJobMetadata', {
      'songPath': songPath,
      'modelPath': modelPath,
      'indexPath': indexPath,
      'pitchChange': pitchChange,
      'indexRate': indexRate,
      'formant': formant,
      'filterRadius': filterRadius,
      'rmsMixRate': rmsMixRate,
      'protectRate': protectRate,
      'sampleRate': sampleRate,
      'noiseGateDb': noiseGateDb,
      'outputDenoiseEnabled': outputDenoiseEnabled,
      'vocalRangeFilterEnabled': vocalRangeFilterEnabled,
    });
    if (result == null) return null;
    return ResumableRvcJobMetadata.fromMap(Map<String, dynamic>.from(result as Map));
  }

  Future<bool> clearResumableJobCache({
    required String songPath,
    required String modelPath,
    String? indexPath,
    double pitchChange = 0.0,
    double indexRate = 0.75,
    double formant = 0.0,
    int filterRadius = 3,
    double rmsMixRate = 0.25,
    double protectRate = 0.33,
    int sampleRate = 48000,
    double noiseGateDb = 35.0,
    bool outputDenoiseEnabled = true,
    bool vocalRangeFilterEnabled = true,
  }) async {
    final result = await _channel.invokeMethod('clearResumableJobCache', {
      'songPath': songPath,
      'modelPath': modelPath,
      'indexPath': indexPath,
      'pitchChange': pitchChange,
      'indexRate': indexRate,
      'formant': formant,
      'filterRadius': filterRadius,
      'rmsMixRate': rmsMixRate,
      'protectRate': protectRate,
      'sampleRate': sampleRate,
      'noiseGateDb': noiseGateDb,
      'outputDenoiseEnabled': outputDenoiseEnabled,
      'vocalRangeFilterEnabled': vocalRangeFilterEnabled,
    });
    return result as bool? ?? false;
  }

  Future<ImportedFileHandle> importPickedFile({
    required String kind,
    required String sourcePath,
  }) async {
    final result = await _channel.invokeMethod('importPickedFile', {
      'kind': kind,
      'sourcePath': sourcePath,
    });
    return ImportedFileHandle.fromMap(Map<String, dynamic>.from(result as Map));
  }

  Future<int> releaseImportedFile(String path) async {
    final result = await _channel.invokeMethod('releaseImportedFile', {
      'path': path,
    });
    return (result as num).toInt();
  }

  Future<void> clearTempWorkspace(String mode) async {
    await _channel.invokeMethod('clearTempWorkspace', {
      'mode': mode,
    });
  }

  /// Get app version
  Future<String> getVersion() async {
    try {
      final result = await _channel.invokeMethod('getVersion');
      return result as String;
    } on PlatformException catch (e) {
      throw Exception('获取版本失败：${e.message}');
    }
  }

  Stream<double> decibelStream() {
    return _decibelChannel.receiveBroadcastStream().map((data) => (data as num).toDouble());
  }

  Stream<Map<String, dynamic>> pitchStream() {
    return _pitchChannel.receiveBroadcastStream().map((data) => Map<String, dynamic>.from(data as Map));
  }

  Future<void> startRecording() async {
    try {
      await _channel.invokeMethod('startRecording');
    } on PlatformException catch (e) {
      throw Exception('开始录音失败：${e.message}');
    }
  }

  Future<void> startRealtimeInference({
    required String modelPath,
    String? indexPath,
    double pitchChange = 0.0,
    double formant = 0.0,
    double indexRate = 0.75,
    double rmsMixRate = 0.25,
    double protectRate = 0.33,
    double noiseGateDb = 35.0,
    bool outputDenoiseEnabled = true,
    bool vocalRangeFilterEnabled = true,
    double sampleLength = 6.0,
    int sampleRate = 48000,
    int parallelChunkCount = 1,
    double crossfadeLength = 0.0,
    double extraInferenceLength = 0.0,
    double delayBufferSeconds = 0.0,
  }) async {
    try {
      await _channel.invokeMethod('startRealtimeInference', {
        'modelPath': modelPath,
        'indexPath': indexPath,
        'pitchChange': pitchChange,
        'formant': formant,
        'indexRate': indexRate,
        'rmsMixRate': rmsMixRate,
        'protectRate': protectRate,
        'noiseGateDb': noiseGateDb,
        'outputDenoiseEnabled': outputDenoiseEnabled,
        'vocalRangeFilterEnabled': vocalRangeFilterEnabled,
        'sampleLength': sampleLength,
        'sampleRate': sampleRate,
        'parallelChunkCount': parallelChunkCount,
        'crossfadeLength': crossfadeLength,
        'extraInferenceLength': extraInferenceLength,
        'delayBufferSeconds': delayBufferSeconds,
      });
    } on PlatformException catch (e) {
      throw Exception('启动实时推理失败：${e.message}');
    }
  }

  Future<void> stopRealtimeInference() async {
    try {
      await _channel.invokeMethod('stopRealtimeInference');
    } on PlatformException catch (e) {
      throw Exception('停止实时推理失败：${e.message}');
    }
  }

  Stream<Map<String, dynamic>> realtimeEventStream() {
    return _realtimeChannel.receiveBroadcastStream().map((data) => Map<String, dynamic>.from(data));
  }

  Future<String> stopRecording() async {
    try {
      final result = await _channel.invokeMethod('stopRecording');
      return result as String;
    } on PlatformException catch (e) {
      throw Exception('停止录音失败：${e.message}');
    }
  }

  Future<String> saveGeneratedAudio(String sourcePath) async {
    try {
      final result = await _channel.invokeMethod('saveGeneratedAudio', {
        'sourcePath': sourcePath,
      });
      return result as String;
    } on PlatformException catch (e) {
      throw Exception('保存失败：${e.message}');
    }
  }

  Future<Duration> getAudioDuration(String path) async {
    try {
      final result = await _channel.invokeMethod('getAudioDurationMs', {
        'path': path,
      });
      final durationMs = (result as num?)?.toInt() ?? 0;
      return Duration(milliseconds: durationMs.clamp(0, 1 << 31));
    } on PlatformException catch (e) {
      throw Exception('读取音频时长失败：${e.message}');
    }
  }

  Future<String> convertIndex(String sourcePath) async {
    try {
      final result = await _channel.invokeMethod('convertIndex', {
        'sourcePath': sourcePath,
      });
      return result as String;
    } on PlatformException catch (e) {
      throw Exception('转换失败：${e.message}');
    }
  }
}
