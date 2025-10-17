import 'dart:async';
import 'package:flutter/services.dart';

class RVCBridge {
  static const MethodChannel _channel = MethodChannel('ultimate_rvc');
  static const EventChannel _progressChannel = EventChannel('ultimate_rvc_progress');

  Stream<Map<String, dynamic>>? _progressStream;

  /// Initialize RVC bridge
  Future<void> initialize() async {
    try {
      await _channel.invokeMethod('initialize');
    } on PlatformException catch (e) {
      throw Exception('Failed to initialize RVC: ${e.message}');
    }
  }

  /// Check if models are available
  Future<bool> checkModels() async {
    try {
      final result = await _channel.invokeMethod('checkModels');
      return result as bool;
    } on PlatformException catch (e) {
      throw Exception('Failed to check models: ${e.message}');
    }
  }

  /// Download required models
  Future<void> downloadModels({
    required Function(double progress, String status) onProgress,
  }) async {
    try {
      // Start download
      await _channel.invokeMethod('downloadModels');
      
      // Listen to progress
      _progressStream ??= _progressChannel
          .receiveBroadcastStream()
          .map((data) => Map<String, dynamic>.from(data));
      
      await for (final progress in _progressStream!) {
        final percent = (progress['percent'] as num).toDouble();
        final status = progress['current_step'] as String;
        onProgress(percent, status);
        
        if (percent >= 100) {
          break;
        }
      }
    } on PlatformException catch (e) {
      throw Exception('Failed to download models: ${e.message}');
    }
  }

  /// Run RVC inference
  Future<String> infer({
    required String songPath,
    required String modelPath,
    double pitchChange = 0.0,
    double indexRate = 0.75,
    int filterRadius = 3,
    double rmsMixRate = 0.25,
    double protectRate = 0.33,
    required Function(double progress, String status) onProgress,
  }) async {
    try {
      // Start inference
      final result = await _channel.invokeMethod('infer', {
        'songPath': songPath,
        'modelPath': modelPath,
        'pitchChange': pitchChange,
        'indexRate': indexRate,
        'filterRadius': filterRadius,
        'rmsMixRate': rmsMixRate,
        'protectRate': protectRate,
      });
      
      // Listen to progress
      _progressStream ??= _progressChannel
          .receiveBroadcastStream()
          .map((data) => Map<String, dynamic>.from(data));
      
      await for (final progress in _progressStream!) {
        final percent = (progress['percent'] as num).toDouble();
        final status = progress['current_step'] as String;
        onProgress(percent, status);
        
        if (percent >= 100) {
          break;
        }
      }
      
      return result as String;
    } on PlatformException catch (e) {
      throw Exception('RVC inference failed: ${e.message}');
    }
  }

  /// Get app version
  Future<String> getVersion() async {
    try {
      final result = await _channel.invokeMethod('getVersion');
      return result as String;
    } on PlatformException catch (e) {
      throw Exception('Failed to get version: ${e.message}');
    }
  }
}