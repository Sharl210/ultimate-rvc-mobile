import 'dart:async';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/voice_changer_bridge.dart';

enum VoiceChangerMode { recording }

class VoiceChangerScreen extends StatefulWidget {
  final bool otherModeRunning;
  final ValueChanged<bool>? onProcessingChanged;

  const VoiceChangerScreen({
    super.key,
    this.otherModeRunning = false,
    this.onProcessingChanged,
  });

  @override
  State<VoiceChangerScreen> createState() => _VoiceChangerScreenState();
}

class _VoiceChangerScreenState extends State<VoiceChangerScreen> {
  final VoiceChangerBridge _bridge = VoiceChangerBridge();
  VoiceChangerMode _mode = VoiceChangerMode.recording;
  String? _modelPath;
  String? _indexPath;
  double _pitchChange = 0.0;
  double _indexRate = 0.75;
  double _formant = 0.0;
  int _filterRadius = 3;
  double _rmsMixRate = 0.25;
  double _protectRate = 0.33;
  double _noiseGateDb = 35.0;
  bool _outputDenoiseEnabled = true;
  bool _vocalRangeFilterEnabled = true;
  int _sampleRate = 48000;
  double _overlayDiameter = 130.0;
  double _overlayOpacity = 0.7;
  double _playbackDelaySeconds = 3.0;
  bool _overlayRunning = false;
  StreamSubscription<void>? _overlayStoppedSubscription;
  StreamSubscription<bool>? _processingChangedSubscription;
  ResumableVoiceChangerJobMetadata? _resumableJobMetadata;
  String? _lastVoiceChangerInputPathValue;
  static const String _voiceChangerLastInputPathKey = 'voiceChangerLastInputPath';

  static const _sampleRates = [48000, 44100, 40000];

  @override
  void initState() {
    super.initState();
    _overlayStoppedSubscription = _bridge.overlayStoppedStream().listen((_) {
      widget.onProcessingChanged?.call(false);
      if (mounted) setState(() => _overlayRunning = false);
    });
    _processingChangedSubscription = _bridge.processingChangedStream().listen((processing) {
      widget.onProcessingChanged?.call(processing);
    });
    _loadParameters();
  }

  @override
  void dispose() {
    _overlayStoppedSubscription?.cancel();
    _processingChangedSubscription?.cancel();
    widget.onProcessingChanged?.call(false);
    super.dispose();
  }

  void _setOverlayRunning(bool running) {
    if (_overlayRunning == running) return;
    if (mounted) {
      setState(() => _overlayRunning = running);
    }
  }

  void _setProcessingActive(bool processing) {
    widget.onProcessingChanged?.call(processing);
  }

  Future<void> _loadParameters() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _modelPath = prefs.getString('voiceChangerRecordingModelPath');
      _indexPath = prefs.getString('voiceChangerRecordingIndexPath');
      _pitchChange = prefs.getDouble('voiceChangerRecordingPitchChange') ?? 0.0;
      _indexRate = prefs.getDouble('voiceChangerRecordingIndexRate') ?? 0.75;
      _formant = prefs.getDouble('voiceChangerRecordingFormant') ?? 0.0;
      _filterRadius = prefs.getInt('voiceChangerRecordingFilterRadius') ?? 3;
      _rmsMixRate = prefs.getDouble('voiceChangerRecordingRmsMixRate') ?? 0.25;
      _protectRate = prefs.getDouble('voiceChangerRecordingProtectRate') ?? 0.33;
      _noiseGateDb = prefs.getDouble('voiceChangerRecordingNoiseGateDb') ?? 35.0;
      _outputDenoiseEnabled = prefs.getBool('voiceChangerRecordingOutputDenoiseEnabled') ?? true;
      _vocalRangeFilterEnabled = prefs.getBool('voiceChangerRecordingVocalRangeFilterEnabled') ?? true;
      _sampleRate = prefs.getInt('voiceChangerRecordingSampleRate') ?? 48000;
      _overlayDiameter = prefs.getDouble('voiceChangerRecordingOverlayDiameter') ?? 130.0;
      _overlayOpacity = prefs.getDouble('voiceChangerRecordingOverlayOpacity') ?? 0.7;
      _playbackDelaySeconds = prefs.getDouble('voiceChangerPlaybackDelaySeconds') ?? 3.0;
      _lastVoiceChangerInputPathValue = prefs.getString(_voiceChangerLastInputPathKey);
    });
    _refreshResumableJobMetadata();
  }

  Future<void> _releaseImportedPathIfNeeded(String? path) async {
    if (path == null || path.isEmpty) return;
    try {
      await _bridge.releaseImportedFile(path);
    } catch (_) {
      // 释放失败时保留旧文件，避免误删共享引用。
    }
  }

  Future<void> _deleteOwnedPathIfNeeded(String? path) async {
    if (path == null || path.isEmpty) return;
    if (path.contains('/FILE_PICKER/') || path.contains('/file_picker/')) {
      await _releaseImportedPathIfNeeded(path);
      return;
    }
    final temporaryDirectory = await getTemporaryDirectory();
    final applicationDocumentsDirectory = await getApplicationDocumentsDirectory();
    final isAppOwnedFile = path.startsWith(temporaryDirectory.path) ||
        path.startsWith(applicationDocumentsDirectory.path) ||
        path.contains('/voice_changer/') ||
        path.contains('/recordings/') ||
        path.contains('/outputs/') ||
        path.contains('/TEMP/');
    if (!isAppOwnedFile) return;
    try {
      final file = File(path);
      if (await file.exists()) {
        await file.delete();
      }
    } catch (_) {
      // 删除失败时保留旧文件，避免误删外部路径。
    }
  }

  Future<void> _clearVoiceChangerTempWorkspace() async {
    await _bridge.clearTempWorkspace('voice_changer');
  }

  Future<void> _saveParameters() async {
    final prefs = await SharedPreferences.getInstance();
    if (_modelPath == null) {
      await prefs.remove('voiceChangerRecordingModelPath');
    } else {
      await prefs.setString('voiceChangerRecordingModelPath', _modelPath!);
    }
    if (_indexPath == null) {
      await prefs.remove('voiceChangerRecordingIndexPath');
    } else {
      await prefs.setString('voiceChangerRecordingIndexPath', _indexPath!);
    }
    await prefs.setDouble('voiceChangerRecordingPitchChange', _pitchChange);
    await prefs.setDouble('voiceChangerRecordingIndexRate', _indexRate);
    await prefs.setDouble('voiceChangerRecordingFormant', _formant);
    await prefs.setInt('voiceChangerRecordingFilterRadius', _filterRadius);
    await prefs.setDouble('voiceChangerRecordingRmsMixRate', _rmsMixRate);
    await prefs.setDouble('voiceChangerRecordingProtectRate', _protectRate);
    await prefs.setDouble('voiceChangerRecordingNoiseGateDb', _noiseGateDb);
    await prefs.setBool('voiceChangerRecordingOutputDenoiseEnabled', _outputDenoiseEnabled);
    await prefs.setBool('voiceChangerRecordingVocalRangeFilterEnabled', _vocalRangeFilterEnabled);
    await prefs.setInt('voiceChangerRecordingSampleRate', _sampleRate);
    await prefs.setDouble('voiceChangerRecordingOverlayDiameter', _overlayDiameter);
    await prefs.setDouble('voiceChangerRecordingOverlayOpacity', _overlayOpacity);
    await prefs.setDouble('voiceChangerPlaybackDelaySeconds', _playbackDelaySeconds);
    if (_lastVoiceChangerInputPathValue == null || _lastVoiceChangerInputPathValue!.isEmpty) {
      await prefs.remove(_voiceChangerLastInputPathKey);
    } else {
      await prefs.setString(_voiceChangerLastInputPathKey, _lastVoiceChangerInputPathValue!);
    }
  }

  Future<void> _resetParametersToDefaults() async {
    setState(() {
      _pitchChange = 0.0;
      _indexRate = 0.75;
      _formant = 0.0;
      _filterRadius = 3;
      _rmsMixRate = 0.25;
      _protectRate = 0.33;
      _noiseGateDb = 35.0;
      _outputDenoiseEnabled = true;
      _vocalRangeFilterEnabled = true;
      _sampleRate = 48000;
      _overlayDiameter = 130.0;
      _overlayOpacity = 0.7;
      _playbackDelaySeconds = 3.0;
    });
    await _saveParameters();
  }

  void _updateParameter(VoidCallback update) {
    setState(update);
    _saveParameters();
    _refreshResumableJobMetadata();
  }

  Future<void> _refreshResumableJobMetadata() async {
    if (_modelPath == null) {
      if (mounted) {
        setState(() => _resumableJobMetadata = null);
      }
      return;
    }
    try {
      final metadata = await _bridge.getResumableVoiceChangerJobMetadata(
        modelPath: _modelPath!,
        indexPath: _indexPath,
        pitchChange: _pitchChange,
        indexRate: _indexRate,
        formant: _formant,
        filterRadius: _filterRadius,
        rmsMixRate: _rmsMixRate,
        protectRate: _protectRate,
        sampleRate: _sampleRate,
        noiseGateDb: _noiseGateDb,
        outputDenoiseEnabled: _outputDenoiseEnabled,
        vocalRangeFilterEnabled: _vocalRangeFilterEnabled,
      );
      if (!mounted) return;
      setState(() => _resumableJobMetadata = metadata);
    } catch (_) {
      if (!mounted) return;
      setState(() => _resumableJobMetadata = null);
    }
  }

  String? _lastVoiceChangerInputPath() {
    return _lastVoiceChangerInputPathValue;
  }

  Future<void> _pickModel() async {
    final result = await FilePicker.platform.pickFiles(type: FileType.any, allowMultiple: false);
    final filePath = result?.files.single.path;
    if (filePath == null) return;
    if (!filePath.toLowerCase().endsWith('.onnx')) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('请选择 .onnx 模型文件')));
      return;
    }
    final previousModelPath = _modelPath;
    final imported = await _bridge.importPickedFile(kind: 'model', sourcePath: filePath);
    await _clearVoiceChangerTempWorkspace();
    _updateParameter(() => _modelPath = imported.path);
    if (previousModelPath != imported.path) {
      await _deleteOwnedPathIfNeeded(previousModelPath);
    }
  }

  Future<void> _pickIndex() async {
    final result = await FilePicker.platform.pickFiles(type: FileType.any, allowMultiple: false);
    final filePath = result?.files.single.path;
    if (filePath == null) return;
    if (!filePath.toLowerCase().endsWith('.mobile.index')) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('请选择 mobile.index 文件')));
      return;
    }
    final previousIndexPath = _indexPath;
    final imported = await _bridge.importPickedFile(kind: 'index', sourcePath: filePath);
    await _clearVoiceChangerTempWorkspace();
    _updateParameter(() => _indexPath = imported.path);
    if (previousIndexPath != imported.path) {
      await _deleteOwnedPathIfNeeded(previousIndexPath);
    }
  }

  Future<void> _toggleOverlay() async {
    try {
      if (_overlayRunning) {
        await _bridge.stopVoiceChangerOverlay();
        return;
      }
      if (_modelPath == null || _modelPath!.trim().isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('请先选择 .onnx 音色模型')));
        return;
      }
      final permission = await Permission.microphone.request();
      if (!permission.isGranted) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('需要麦克风权限')));
        return;
      }
      await _clearVoiceChangerTempWorkspace();
      _lastVoiceChangerInputPathValue = null;
      await _saveParameters();
      await _bridge.startVoiceChangerOverlay(
        modelPath: _modelPath!,
        indexPath: _indexPath,
        pitchChange: _pitchChange,
        indexRate: _indexRate,
        formant: _formant,
        filterRadius: _filterRadius,
        rmsMixRate: _rmsMixRate,
        protectRate: _protectRate,
        sampleRate: _sampleRate,
        noiseGateDb: _noiseGateDb,
        outputDenoiseEnabled: _outputDenoiseEnabled,
        vocalRangeFilterEnabled: _vocalRangeFilterEnabled,
        parallelChunkCount: 1,
        overlayDiameter: _overlayDiameter,
        overlayOpacity: _overlayOpacity,
        playbackDelaySeconds: _playbackDelaySeconds,
      );
      _setProcessingActive(false);
      _setOverlayRunning(true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  Future<void> _continueUnfinishedJob() async {
    if (_overlayRunning) return;
    if (_resumableJobMetadata == null) {
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('无法继续'),
          content: const Text('当前任务与历史不一致，无法继续。\n不做任何操作。'),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('确定'),
            ),
          ],
        ),
      );
      return;
    }
    await _saveParameters();
    await _bridge.startVoiceChangerOverlay(
      modelPath: _modelPath!,
      indexPath: _indexPath,
      pitchChange: _pitchChange,
      indexRate: _indexRate,
      formant: _formant,
      filterRadius: _filterRadius,
      rmsMixRate: _rmsMixRate,
      protectRate: _protectRate,
      sampleRate: _sampleRate,
      noiseGateDb: _noiseGateDb,
      outputDenoiseEnabled: _outputDenoiseEnabled,
      vocalRangeFilterEnabled: _vocalRangeFilterEnabled,
      parallelChunkCount: 1,
      overlayDiameter: _overlayDiameter,
      overlayOpacity: _overlayOpacity,
      playbackDelaySeconds: _playbackDelaySeconds,
    );
    _setProcessingActive(false);
    _setOverlayRunning(true);
    _refreshResumableJobMetadata();
  }

  void _showHelp() {
    showDialog<void>(
      context: context,
      builder: (context) => LayoutBuilder(
        builder: (context, constraints) => AlertDialog(
          title: const Text('录制模式'),
          content: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: constraints.maxHeight * 0.62),
            child: const SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('灰色：待机。点一下开始录音，长按关闭悬浮窗。'),
                  SizedBox(height: 8),
                  Text('绿色：录音中，并显示录制时长。点一下停止录音并开始处理，长按取消录音并回到灰色。'),
                  SizedBox(height: 8),
                  Text('橙色：处理中，并显示处理进度。点一下进入亮黄色暂停态，长按取消处理并清空临时文件，回到灰色初始态。'),
                  SizedBox(height: 8),
                  Text('亮黄色：处理已暂停。点一下继续处理，长按放弃本轮处理并清空临时文件，回到灰色初始态。'),
                  SizedBox(height: 8),
                  Text('蓝色：处理完成，显示处理后总时长。点一下进入天蓝色试听倒计时。'),
                  SizedBox(height: 8),
                  Text('蓝色长按：结束本轮并清理文件，回到灰色。'),
                  SizedBox(height: 8),
                  Text('天蓝色：显示播放或试听倒计时。'),
                  SizedBox(height: 8),
                  Text('红色：试听播放中，显示剩余时间。点一下暂停并变成青绿色，再点一下继续后恢复红色，长按直接结束试听。'),
                  SizedBox(height: 8),
                  Text('青绿色：播放已暂停。'),
                  SizedBox(height: 8),
                  Text('蓝色和红色：都可以双击保存处理后的音频。'),
                  SizedBox(height: 8),
                  Text('蓝色和红色：都可以三击保存本轮原始录音。'),
                  SizedBox(height: 8),
                  Text('手势一触发会先弹出提示，再执行保存、暂停、继续、取消等耗时操作。'),
                  SizedBox(height: 8),
                  Text('录制和处理会占用推理资源；如果音频推理或实时推理正在运行，点击开始录音会提示暂时不能用。'),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('知道了')),
          ],
        ),
      ),
    );
  }

  Widget _buildSelectedFile(String label, String? path, IconData icon) {
    return Row(
      children: [
        Icon(icon, color: Theme.of(context).colorScheme.primary),
        const SizedBox(width: 8),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label, style: Theme.of(context).textTheme.bodySmall),
              Text(
                path?.split('/').last ?? '未选择',
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodyLarge?.copyWith(fontWeight: FontWeight.bold),
              ),
            ],
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final overlayControlsLocked = _overlayRunning || widget.otherModeRunning;
    return Scaffold(
      appBar: AppBar(
        title: const Text('变声器模式'),
        centerTitle: true,
        actions: [IconButton(onPressed: _showHelp, icon: const Icon(Icons.help_outline))],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('录制模式', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 12),
                    SegmentedButton<VoiceChangerMode>(
                      segments: const [ButtonSegment(value: VoiceChangerMode.recording, label: Text('录制模式'))],
                      selected: {_mode},
                      onSelectionChanged: (value) => setState(() => _mode = value.first),
                    ),
                  const SizedBox(height: 16),
                  _buildSelectedFile('音色模型', _modelPath, Icons.mic),
                  const SizedBox(height: 8),
                  ElevatedButton.icon(
                    onPressed: _pickModel,
                    icon: const Icon(Icons.folder_open),
                    label: Text(_modelPath == null ? '选择 .onnx 模型' : '更换 .onnx 模型'),
                  ),
                  const SizedBox(height: 16),
                  _buildSelectedFile('索引文件', _indexPath, Icons.manage_search),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                        OutlinedButton.icon(
                          onPressed: _pickIndex,
                          icon: const Icon(Icons.folder_open),
                          label: Text(_indexPath == null ? '选择 mobile.index' : '更换 mobile.index'),
                        ),
                      if (_indexPath != null) ...[
                        const SizedBox(width: 8),
                        IconButton(
                          onPressed: () async {
                            final previousIndexPath = _indexPath;
                            await _clearVoiceChangerTempWorkspace();
                            _updateParameter(() => _indexPath = null);
                            await _deleteOwnedPathIfNeeded(previousIndexPath);
                          },
                          icon: const Icon(Icons.close),
                          tooltip: '移除索引',
                        ),
                      ],
                    ],
                  ),
                  if (_resumableJobMetadata != null) ...[
                    const SizedBox(height: 12),
                    OutlinedButton.icon(
                      onPressed: overlayControlsLocked ? null : _continueUnfinishedJob,
                      icon: const Icon(Icons.restore),
                      label: const Text('继续未完成'),
                    ),
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('处理声线参数', style: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 16),
                  Text('Pitch（音调设置）：${_pitchChange.toStringAsFixed(1)} 半音'),
                  Slider(value: _pitchChange, min: -24.0, max: 24.0, divisions: 96, label: _pitchChange.toStringAsFixed(1), onChanged: (value) => _updateParameter(() => _pitchChange = value)),
                  Text('Index Rate（索引强度）：${(_indexRate * 100).toStringAsFixed(0)}%'),
                  Slider(value: _indexRate, min: 0.0, max: 1.0, divisions: 20, label: (_indexRate * 100).toStringAsFixed(0), onChanged: (value) => _updateParameter(() => _indexRate = value)),
                  Text('Formant（性别因子/声线粗细）：${_formant.toStringAsFixed(2)}'),
                  Slider(value: _formant, min: -4.0, max: 4.0, divisions: 160, label: _formant.toStringAsFixed(2), onChanged: (value) => _updateParameter(() => _formant = value)),
                  Text('Noise Gate（噪声过滤）：${_noiseGateDb.toStringAsFixed(0)} dB'),
                  Slider(value: _noiseGateDb, min: 0.0, max: 100.0, divisions: 100, label: _noiseGateDb.toStringAsFixed(0), onChanged: (value) => _updateParameter(() => _noiseGateDb = value)),
                  DropdownButtonFormField<int>(
                    value: _sampleRate,
                    decoration: const InputDecoration(labelText: 'Sample Rate（采样率）'),
                    items: _sampleRates.map((rate) => DropdownMenuItem(value: rate, child: Text('${rate ~/ 1000} kHz'))).toList(),
                    onChanged: (value) {
                      if (value != null) _updateParameter(() => _sampleRate = value);
                    },
                  ),
                  ExpansionTile(
                    title: const Text('高级参数'),
                    children: [
                      SwitchListTile(
                        title: const Text('降噪优化'),
                        subtitle: const Text('只影响处理结果，不改原始录音文件'),
                        value: _outputDenoiseEnabled,
                        onChanged: (value) => _updateParameter(() => _outputDenoiseEnabled = value),
                      ),
                      SwitchListTile(
                        title: const Text('音域过滤'),
                        subtitle: const Text('只影响处理结果，不改原始录音文件'),
                        value: _vocalRangeFilterEnabled,
                        onChanged: (value) => _updateParameter(() => _vocalRangeFilterEnabled = value),
                      ),
                      Text('Filter Radius（音高滤波）：$_filterRadius'),
                      Slider(value: _filterRadius.toDouble(), min: 0, max: 10, divisions: 10, label: _filterRadius.toString(), onChanged: (value) => _updateParameter(() => _filterRadius = value.toInt())),
                      Text('RMS Mix（响度混合）：${(_rmsMixRate * 100).toStringAsFixed(0)}%'),
                      Slider(value: _rmsMixRate, min: 0.0, max: 1.0, divisions: 20, label: (_rmsMixRate * 100).toStringAsFixed(0), onChanged: (value) => _updateParameter(() => _rmsMixRate = value)),
                      Text('Protect（辅音保护）：${(_protectRate * 100).toStringAsFixed(0)}%'),
                      Slider(value: _protectRate, min: 0.0, max: 1.0, divisions: 100, label: (_protectRate * 100).toStringAsFixed(0), onChanged: (value) => _updateParameter(() => _protectRate = value)),
                    ],
                  ),
                  Align(
                    alignment: Alignment.centerRight,
                    child: TextButton.icon(
                      onPressed: _resetParametersToDefaults,
                      icon: const Icon(Icons.restore, color: Colors.red),
                      label: const Text('恢复默认', style: TextStyle(color: Colors.red)),
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('悬浮控制', style: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 16),
                  Text('悬浮窗直径：${_overlayDiameter.toStringAsFixed(0)} px'),
                  Slider(value: _overlayDiameter, min: 100.0, max: 300.0, divisions: 20, label: _overlayDiameter.toStringAsFixed(0), onChanged: (value) => _updateParameter(() => _overlayDiameter = value)),
                  Text('悬浮窗不透明度：${(_overlayOpacity * 100).toStringAsFixed(0)}%'),
                  Slider(value: _overlayOpacity, min: 0.2, max: 1.0, divisions: 16, label: (_overlayOpacity * 100).toStringAsFixed(0), onChanged: (value) => _updateParameter(() => _overlayOpacity = value)),
                  Text('播放延迟：${_playbackDelaySeconds.toStringAsFixed(0)} 秒'),
                  Slider(value: _playbackDelaySeconds, min: 0.0, max: 10.0, divisions: 10, label: _playbackDelaySeconds.toStringAsFixed(0), onChanged: (value) => _updateParameter(() => _playbackDelaySeconds = value)),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: overlayControlsLocked && !_overlayRunning ? null : _toggleOverlay,
            icon: Icon(_overlayRunning ? Icons.close : Icons.graphic_eq),
            label: Text(_overlayRunning ? '关闭悬浮窗' : '打开悬浮窗'),
          ),
        ],
      ),
    );
  }

}
