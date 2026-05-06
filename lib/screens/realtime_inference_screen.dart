import 'dart:async';

import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/rvc_bridge.dart';

class RealtimeInferenceScreen extends StatefulWidget {
  final bool otherModeRunning;
  final ValueChanged<bool>? onRunningChanged;

  const RealtimeInferenceScreen({
    super.key,
    this.otherModeRunning = false,
    this.onRunningChanged,
  });

  @override
  State<RealtimeInferenceScreen> createState() => _RealtimeInferenceScreenState();
}

class _RealtimeInferenceScreenState extends State<RealtimeInferenceScreen> {
  final RVCBridge _rvcBridge = RVCBridge();
  bool _isRunning = false;
  bool _isStopping = false;
  String _status = '未启动';
  String? _modelPath;
  String? _indexPath;
  double _pitchChange = 0.0;
  double _formant = 0.0;
  double _indexRate = 0.75;
  double _rmsMixRate = 0.25;
  double _protectRate = 0.33;
  double _noiseGateDb = 35.0;
  bool _outputDenoiseEnabled = true;
  bool _vocalRangeFilterEnabled = true;
  double _sampleLength = 6.0;
  int _sampleRate = 48000;
  double _crossfadeLength = 0.0;
  double _extraInferenceLength = 0.0;
  double _delayBufferSeconds = 0.0;
  StreamSubscription<Map<String, dynamic>>? _realtimeEventSubscription;

  static const _sampleRates = [48000, 44100, 40000];

  @override
  void initState() {
    super.initState();
    _loadSavedParameters();
  }

  void _setRunning(bool running) {
    if (_isRunning == running) return;
    setState(() => _isRunning = running);
    widget.onRunningChanged?.call(running);
  }

  Future<void> _loadSavedParameters() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _modelPath = prefs.getString('realtimeModelPath');
      _indexPath = prefs.getString('realtimeIndexPath');
      _pitchChange = prefs.getDouble('realtimePitchChange') ?? 0.0;
      _formant = prefs.getDouble('realtimeFormant') ?? 0.0;
      _indexRate = prefs.getDouble('realtimeIndexRate') ?? 0.75;
      _rmsMixRate = prefs.getDouble('realtimeRmsMixRate') ?? 0.25;
      _protectRate = prefs.getDouble('realtimeProtectRate') ?? 0.33;
      _noiseGateDb = prefs.getDouble('realtimeNoiseGateDb') ?? 35.0;
      _outputDenoiseEnabled = prefs.getBool('realtimeOutputDenoiseEnabled') ?? true;
      _vocalRangeFilterEnabled = prefs.getBool('realtimeVocalRangeFilterEnabled') ?? true;
      _sampleLength = prefs.getDouble('realtimeSampleLength') ?? 6.0;
      _sampleRate = prefs.getInt('realtimeSampleRate') ?? 48000;
      _crossfadeLength = prefs.getDouble('realtimeCrossfadeLength') ?? 0.0;
      _extraInferenceLength = prefs.getDouble('realtimeExtraInferenceLength') ?? 0.0;
      _delayBufferSeconds = prefs.getDouble('realtimeDelayBufferSeconds') ?? 0.0;
    });
  }

  Future<void> _saveParameters() async {
    final prefs = await SharedPreferences.getInstance();
    if (_modelPath == null) {
      await prefs.remove('realtimeModelPath');
    } else {
      await prefs.setString('realtimeModelPath', _modelPath!);
    }
    if (_indexPath == null) {
      await prefs.remove('realtimeIndexPath');
    } else {
      await prefs.setString('realtimeIndexPath', _indexPath!);
    }
    await prefs.setDouble('realtimePitchChange', _pitchChange);
    await prefs.setDouble('realtimeFormant', _formant);
    await prefs.setDouble('realtimeIndexRate', _indexRate);
    await prefs.setDouble('realtimeRmsMixRate', _rmsMixRate);
    await prefs.setDouble('realtimeProtectRate', _protectRate);
    await prefs.setDouble('realtimeNoiseGateDb', _noiseGateDb);
    await prefs.setBool('realtimeOutputDenoiseEnabled', _outputDenoiseEnabled);
    await prefs.setBool('realtimeVocalRangeFilterEnabled', _vocalRangeFilterEnabled);
    await prefs.setDouble('realtimeSampleLength', _sampleLength);
    await prefs.setInt('realtimeSampleRate', _sampleRate);
    await prefs.setDouble('realtimeCrossfadeLength', _crossfadeLength);
    await prefs.setDouble('realtimeExtraInferenceLength', _extraInferenceLength);
    await prefs.setDouble('realtimeDelayBufferSeconds', _delayBufferSeconds);
  }

  Future<void> _resetParametersToDefaults() async {
    setState(() {
      _pitchChange = 0.0;
      _formant = 0.0;
      _indexRate = 0.75;
      _rmsMixRate = 0.25;
      _protectRate = 0.33;
      _noiseGateDb = 35.0;
      _outputDenoiseEnabled = true;
      _vocalRangeFilterEnabled = true;
      _sampleLength = 6.0;
      _sampleRate = 48000;
      _crossfadeLength = 0.0;
      _extraInferenceLength = 0.0;
      _delayBufferSeconds = 0.0;
    });
    await _saveParameters();
  }

  void _showLimitWarning() {
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('手机端限制'),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('这个页面仅供演示。'),
            SizedBox(height: 8),
            Text('手机端实时推理性能和系统音频能力限制很大。'),
            SizedBox(height: 8),
            Text('当前无法指定输入输出设备。'),
            SizedBox(height: 8),
            Text('实际效果可能大约每 6 秒卡一次，没有实际用途。'),
            SizedBox(height: 8),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('知道了'),
          ),
        ],
      ),
    );
  }

  void _updateParameter(VoidCallback update) {
    setState(update);
    _saveParameters();
  }

  @override
  void dispose() {
    _realtimeEventSubscription?.cancel();
    widget.onRunningChanged?.call(false);
    super.dispose();
  }

  Future<void> _releaseImportedPathIfNeeded(String? path) async {
    if (path == null || path.isEmpty) return;
    try {
      await _rvcBridge.releaseImportedFile(path);
    } catch (_) {
      // 释放失败时保留旧文件，避免误删共享引用。
    }
  }

  Future<void> _clearRealtimeTempWorkspace() async {
    await _rvcBridge.clearTempWorkspace('audio_inference');
  }

  Future<void> _pickModel() async {
    final result = await FilePicker.platform.pickFiles(type: FileType.any, allowMultiple: false);
    final filePath = result?.files.single.path;
    if (filePath == null) return;
    if (!filePath.toLowerCase().endsWith('.onnx')) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('请选择 .onnx 模型文件')));
      return;
    }
    final previousModelPath = _modelPath;
    final imported = await _rvcBridge.importPickedFile(kind: 'model', sourcePath: filePath);
    await _clearRealtimeTempWorkspace();
    _updateParameter(() => _modelPath = imported.path);
    if (previousModelPath != imported.path) {
      await _releaseImportedPathIfNeeded(previousModelPath);
    }
  }

  Future<void> _pickIndex() async {
    final result = await FilePicker.platform.pickFiles(type: FileType.any, allowMultiple: false);
    final filePath = result?.files.single.path;
    if (filePath == null) return;
    if (!filePath.toLowerCase().endsWith('.mobile.index')) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('请选择 mobile.index 文件')));
      return;
    }
    final previousIndexPath = _indexPath;
    final imported = await _rvcBridge.importPickedFile(kind: 'index', sourcePath: filePath);
    await _clearRealtimeTempWorkspace();
    _updateParameter(() => _indexPath = imported.path);
    if (previousIndexPath != imported.path) {
      await _releaseImportedPathIfNeeded(previousIndexPath);
    }
  }

  Future<void> _toggleRealtimeInference() async {
    if (widget.otherModeRunning && !_isRunning) return;
    if (_isRunning) {
      setState(() {
        _isStopping = true;
        _status = '正在停止实时推理';
      });
      try {
        await _rvcBridge.stopRealtimeInference();
      } finally {
        await _realtimeEventSubscription?.cancel();
        _realtimeEventSubscription = null;
      }
      if (!mounted) return;
      setState(() {
        _isStopping = false;
        _status = '已停止';
      });
      _setRunning(false);
      return;
    }

    final permission = await Permission.microphone.request();
    if (!permission.isGranted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('需要麦克风权限')),
      );
      return;
    }
    if (_modelPath == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('请选择 .onnx 音色模型')),
      );
      return;
    }

    try {
      await _saveParameters();
      await _realtimeEventSubscription?.cancel();
      setState(() {
        _status = '正在启动实时推理';
      });
      _realtimeEventSubscription = _rvcBridge.realtimeEventStream().listen((event) {
        final nextStatus = event['current_step'] ?? event['status'];
        final queuedOutputSamples = event['queued_output_samples'];
        final outputDelayTargetSamples = event['output_delay_target_samples'];
        final lastInferMs = event['last_infer_ms'];
        final pendingInputSamples = event['pending_input_samples'];
        final inputBacklogMs = event['input_backlog_ms'];
        final lastOutputSamples = event['last_output_samples'];
        final nonEmptyOutputCount = event['non_empty_output_count'];
        final outputChannelDropCount = event['output_channel_drop_count'];
        final audioTrackStarted = event['audio_track_started'];
        final audioTrackWriteCount = event['audio_track_write_count'];
        final audioTrackWrittenSamples = event['audio_track_written_samples'];
        final lastInputPeak = event['last_input_peak'];
        final lastRawOutputPeak = event['last_raw_output_peak'];
        final lastOutputPeak = event['last_output_peak'];
        if (!mounted || nextStatus == null) return;
        var status = '$nextStatus';
        if (pendingInputSamples != null && inputBacklogMs != null) {
          status = '$status（输入 $pendingInputSamples/$inputBacklogMs ms）';
        }
        if (queuedOutputSamples != null && outputDelayTargetSamples != null) {
          status = '$status（缓冲 $queuedOutputSamples/$outputDelayTargetSamples）';
        }
        if (lastOutputSamples != null && nonEmptyOutputCount != null) {
          status = '$status，输出 $lastOutputSamples/$nonEmptyOutputCount';
        }
        if (outputChannelDropCount != null) {
          status = '$status，丢包 $outputChannelDropCount';
        }
        if (audioTrackStarted != null && audioTrackWriteCount != null && audioTrackWrittenSamples != null) {
          status = '$status，播放 ${audioTrackStarted ? '已启动' : '待启动'}，写入 $audioTrackWriteCount/$audioTrackWrittenSamples';
        }
        if (lastInferMs != null) {
          status = '$status，推理 ${lastInferMs}ms';
        }
        if (lastInputPeak != null && lastRawOutputPeak != null && lastOutputPeak != null) {
          status = '$status，峰值 入 $lastInputPeak / 原 $lastRawOutputPeak / 出 $lastOutputPeak';
        }
        setState(() {
          _status = status;
        });
      }, onError: (error) {
        if (!mounted) return;
        setState(() {
          _isStopping = false;
          _status = '实时推理失败：$error';
        });
        _setRunning(false);
      });
      await _rvcBridge.startRealtimeInference(
        modelPath: _modelPath!,
        indexPath: _indexPath,
        pitchChange: _pitchChange,
        formant: _formant,
        indexRate: _indexRate,
        rmsMixRate: _rmsMixRate,
        protectRate: _protectRate,
        noiseGateDb: _noiseGateDb,
        outputDenoiseEnabled: _outputDenoiseEnabled,
        vocalRangeFilterEnabled: _vocalRangeFilterEnabled,
        sampleLength: _sampleLength,
        sampleRate: _sampleRate,
        parallelChunkCount: 1,
        crossfadeLength: _crossfadeLength,
        extraInferenceLength: _extraInferenceLength,
        delayBufferSeconds: _delayBufferSeconds,
      );
      setState(() {
        _isStopping = false;
        _status = '实时推理中';
      });
      _setRunning(true);
    } catch (e) {
      await _realtimeEventSubscription?.cancel();
      _realtimeEventSubscription = null;
      if (!mounted) return;
      setState(() => _status = '启动失败：$e');
      _setRunning(false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('启动失败：$e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final controlsLocked = _isRunning || widget.otherModeRunning;
    final startLocked = _isStopping || widget.otherModeRunning;
    return Scaffold(
      appBar: AppBar(
        title: Text('实时推理'),
        centerTitle: true,
        actions: [
          IconButton(
            onPressed: _showLimitWarning,
            icon: const Icon(Icons.error_outline),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('状态：$_status'),
                    SizedBox(height: 16),
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(16.0),
                        child: Column(
                          children: [
                            Text('音色模型：', style: Theme.of(context).textTheme.bodySmall),
                            Text(
                              _modelPath?.split('/').last ?? '未选择',
                              style: Theme.of(context).textTheme.bodyLarge?.copyWith(fontWeight: FontWeight.bold),
                              textAlign: TextAlign.center,
                            ),
                            SizedBox(height: 12),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                 ElevatedButton.icon(
                                  onPressed: _isRunning ? null : _pickModel,
                                  icon: Icon(Icons.folder_open),
                                  label: Text(_modelPath == null ? '选择 .onnx 模型' : '更换 .onnx 模型'),
                                ),
                                if (_modelPath != null) ...[
                                  SizedBox(width: 8),
                                  IconButton(
                                    onPressed: _isRunning ? null : () async {
                                      final previousModelPath = _modelPath;
                                      _updateParameter(() => _modelPath = null);
                                      await _releaseImportedPathIfNeeded(previousModelPath);
                                    },
                                    icon: Icon(Icons.close),
                                    tooltip: '清除模型',
                                  ),
                                ],
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                    SizedBox(height: 12),
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(16.0),
                        child: Column(
                          children: [
                            Text('索引文件：', style: Theme.of(context).textTheme.bodySmall),
                            Text(
                              _indexPath?.split('/').last ?? '未选择',
                              style: Theme.of(context).textTheme.bodyLarge?.copyWith(fontWeight: FontWeight.bold),
                              textAlign: TextAlign.center,
                            ),
                            SizedBox(height: 12),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                OutlinedButton.icon(
                                  onPressed: _isRunning ? null : _pickIndex,
                                  icon: Icon(Icons.folder_open),
                                  label: Text(_indexPath == null ? '选择 mobile.index' : '更换 mobile.index'),
                                ),
                                if (_indexPath != null) ...[
                                  SizedBox(width: 8),
                                  IconButton(
                                    onPressed: _isRunning ? null : () async {
                                      final previousIndexPath = _indexPath;
                                      _updateParameter(() => _indexPath = null);
                                      await _releaseImportedPathIfNeeded(previousIndexPath);
                                    },
                                    icon: Icon(Icons.close),
                                    tooltip: '清除索引',
                                  ),
                                ],
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                    SizedBox(height: 16),
                    Text('Pitch（音调设置）：${_pitchChange.toStringAsFixed(1)}'),
                    Slider(
                      value: _pitchChange,
                      min: -24.0,
                      max: 24.0,
                      divisions: 96,
                      label: _pitchChange.toStringAsFixed(1),
                      onChanged: controlsLocked ? null : (value) => _updateParameter(() => _pitchChange = value),
                    ),
                    Text('Formant（性别因子/声线粗细）：${_formant.toStringAsFixed(2)}'),
                    Slider(
                      value: _formant,
                      min: -4.0,
                      max: 4.0,
                      divisions: 160,
                      label: _formant.toStringAsFixed(2),
                      onChanged: controlsLocked ? null : (value) => _updateParameter(() => _formant = value),
                    ),
                    Text('Index Rate（检索特征占比）：${(_indexRate * 100).toStringAsFixed(0)}%'),
                    Slider(
                      value: _indexRate,
                      min: 0,
                      max: 1,
                      divisions: 20,
                      label: (_indexRate * 100).toStringAsFixed(0),
                      onChanged: controlsLocked ? null : (value) => _updateParameter(() => _indexRate = value),
                    ),
                    Text('Noise Gate（噪声过滤）：${_noiseGateDb.toStringAsFixed(0)} dB'),
                    Slider(
                      value: _noiseGateDb,
                      min: 0.0,
                      max: 100.0,
                      divisions: 100,
                      label: _noiseGateDb.toStringAsFixed(0),
                      onChanged: controlsLocked ? null : (value) => _updateParameter(() => _noiseGateDb = value),
                    ),
                    Text('延时缓冲：${_delayBufferSeconds.toStringAsFixed(0)} 秒'),
                    Slider(
                      value: _delayBufferSeconds,
                      min: 0.0,
                      max: 60.0,
                      divisions: 60,
                      label: _delayBufferSeconds.toStringAsFixed(0),
                      onChanged: controlsLocked ? null : (value) => _updateParameter(() => _delayBufferSeconds = value),
                    ),
                    DropdownButtonFormField<int>(
                      value: _sampleRate,
                      decoration: InputDecoration(labelText: '音频采样率'),
                      items: _sampleRates
                          .map((rate) => DropdownMenuItem(
                                value: rate,
                                child: Text('${rate ~/ 1000} kHz'),
                              ))
                          .toList(),
                      onChanged: controlsLocked ? null : (value) {
                        if (value != null) {
                          _updateParameter(() => _sampleRate = value);
                        }
                      },
                    ),
                    ExpansionTile(
                      title: Text('高级参数'),
                      children: [
                        SwitchListTile(
                          title: Text('降噪优化'),
                          subtitle: Text('只影响处理结果，不改原始录音文件'),
                          value: _outputDenoiseEnabled,
                          onChanged: controlsLocked ? null : (value) => _updateParameter(() => _outputDenoiseEnabled = value),
                        ),
                        SwitchListTile(
                          title: Text('音域过滤'),
                          subtitle: Text('只影响处理结果，不改原始录音文件'),
                          value: _vocalRangeFilterEnabled,
                          onChanged: controlsLocked ? null : (value) => _updateParameter(() => _vocalRangeFilterEnabled = value),
                        ),
                        Text('RMS Mix（响度因子）：${(_rmsMixRate * 100).toStringAsFixed(0)}%'),
                        Slider(
                          value: _rmsMixRate,
                          min: 0,
                          max: 1,
                          divisions: 20,
                          label: (_rmsMixRate * 100).toStringAsFixed(0),
                          onChanged: controlsLocked ? null : (value) => _updateParameter(() => _rmsMixRate = value),
                        ),
                        Text('Protect（辅音保护）：${(_protectRate * 100).toStringAsFixed(0)}%'),
                        Slider(
                          value: _protectRate,
                          min: 0,
                          max: 1,
                          divisions: 100,
                          label: (_protectRate * 100).toStringAsFixed(0),
                          onChanged: controlsLocked ? null : (value) => _updateParameter(() => _protectRate = value),
                        ),
                        Text('采样长度：${_sampleLength.toStringAsFixed(2)}'),
                        Slider(
                          value: _sampleLength,
                          min: 1.0,
                          max: 12.0,
                          divisions: 22,
                          label: _sampleLength.toStringAsFixed(2),
                          onChanged: controlsLocked ? null : (value) => _updateParameter(() => _sampleLength = value),
                        ),
                        Text('淡入淡出长度：${_crossfadeLength.toStringAsFixed(2)}'),
                        Slider(
                          value: _crossfadeLength,
                          min: 0.0,
                          max: 2.0,
                          divisions: 20,
                          label: _crossfadeLength.toStringAsFixed(2),
                          onChanged: controlsLocked ? null : (value) => _updateParameter(() => _crossfadeLength = value),
                        ),
                        Text('额外推理时长：${_extraInferenceLength.toStringAsFixed(2)}'),
                        Slider(
                          value: _extraInferenceLength,
                          min: 0.0,
                          max: 5.0,
                          divisions: 20,
                          label: _extraInferenceLength.toStringAsFixed(2),
                          onChanged: controlsLocked ? null : (value) => _updateParameter(() => _extraInferenceLength = value),
                        ),
                      ],
                    ),
                    Align(
                      alignment: Alignment.centerRight,
                      child: TextButton.icon(
                        onPressed: controlsLocked ? null : _resetParametersToDefaults,
                        icon: Icon(Icons.restore, color: Colors.red),
                        label: Text('恢复默认', style: TextStyle(color: Colors.red)),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            SizedBox(height: 16),
            ElevatedButton.icon(
              onPressed: startLocked ? null : _toggleRealtimeInference,
              icon: Icon(_isRunning ? Icons.stop : Icons.graphic_eq),
              label: Text(_isStopping ? '正在停止' : _isRunning ? '停止实时推理' : '启动实时推理'),
            ),
          ],
        ),
      ),
    );
  }
}
