import 'dart:async';

import 'package:flutter/services.dart';

import 'rvc_bridge.dart';

class ResumableVoiceChangerJobMetadata {
  final String jobId;
  final double overallProgress;
  final String state;
  final int accumulatedElapsedMs;

  const ResumableVoiceChangerJobMetadata({
    required this.jobId,
    required this.overallProgress,
    required this.state,
    required this.accumulatedElapsedMs,
  });

  factory ResumableVoiceChangerJobMetadata.fromMap(Map<String, dynamic> map) {
    return ResumableVoiceChangerJobMetadata(
      jobId: map['jobId'] as String,
      overallProgress: (map['overallProgress'] as num).toDouble(),
      state: map['state'] as String,
      accumulatedElapsedMs: (map['accumulatedElapsedMs'] as num?)?.toInt() ?? 0,
    );
  }
}

class VoiceChangerBridge {
  static const MethodChannel _channel = MethodChannel('ultimate_rvc_voice_changer');
  static final StreamController<void> _overlayStoppedController = StreamController<void>.broadcast();
  static final StreamController<bool> _processingChangedController = StreamController<bool>.broadcast();

  VoiceChangerBridge() {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'voiceChangerOverlayStopped':
          _overlayStoppedController.add(null);
          break;
        case 'voiceChangerProcessingChanged':
          _processingChangedController.add((call.arguments as bool?) ?? false);
          break;
      }
    });
  }

  Stream<void> overlayStoppedStream() => _overlayStoppedController.stream;
  Stream<bool> processingChangedStream() => _processingChangedController.stream;

  Future<void> startVoiceChangerOverlay({
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
    double overlayDiameter = 100.0,
    double overlayOpacity = 0.7,
    double playbackDelaySeconds = 3.0,
  }) async {
    try {
      await _channel.invokeMethod('startVoiceChangerOverlay', {
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
        'overlayDiameter': overlayDiameter,
        'overlayOpacity': overlayOpacity,
        'playbackDelaySeconds': playbackDelaySeconds,
      });
    } on PlatformException catch (e) {
      throw Exception('启动变声器失败：${e.message}');
    }
  }

  Future<void> stopVoiceChangerOverlay() async {
    try {
      await _channel.invokeMethod('stopVoiceChangerOverlay');
    } on PlatformException catch (e) {
      throw Exception('停止变声器失败：${e.message}');
    }
  }

  Future<ResumableVoiceChangerJobMetadata?> getResumableVoiceChangerJobMetadata({
    required String modelPath,
    String? inputPath,
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
    final result = await _channel.invokeMethod('getResumableVoiceChangerJobMetadata', {
      'inputPath': inputPath,
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
    return ResumableVoiceChangerJobMetadata.fromMap(Map<String, dynamic>.from(result as Map));
  }

  Future<ImportedFileHandle> importPickedFile({
    required String kind,
    required String sourcePath,
  }) async {
    final result = await const MethodChannel('ultimate_rvc').invokeMethod('importPickedFile', {
      'kind': kind,
      'sourcePath': sourcePath,
    });
    return ImportedFileHandle.fromMap(Map<String, dynamic>.from(result as Map));
  }

  Future<int> releaseImportedFile(String path) async {
    final result = await const MethodChannel('ultimate_rvc').invokeMethod('releaseImportedFile', {
      'path': path,
    });
    return (result as num).toInt();
  }

  Future<void> clearTempWorkspace(String mode) async {
    await const MethodChannel('ultimate_rvc').invokeMethod('clearTempWorkspace', {
      'mode': mode,
    });
  }
}
