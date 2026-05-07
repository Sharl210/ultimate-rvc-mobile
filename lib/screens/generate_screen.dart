import 'dart:async';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../services/rvc_bridge.dart';

class RvcGenerationState extends ChangeNotifier {
  bool isGenerating = false;
  bool isStopping = false;
  bool hasError = false;
  double progress = 0.0;
  String status = '准备生成';
  String? errorMessage;
  final Stopwatch _generationStopwatch = Stopwatch();
  Timer? _elapsedTimer;
  Duration elapsedGenerationTime = Duration.zero;
  Duration _baseElapsedGenerationTime = Duration.zero;

  void start({Duration initialElapsed = Duration.zero}) {
    _elapsedTimer?.cancel();
    _generationStopwatch
      ..reset()
      ..start();
    isGenerating = true;
    isStopping = false;
    hasError = false;
    errorMessage = null;
    progress = 0.0;
    status = '初始化中...';
    _baseElapsedGenerationTime = initialElapsed;
    elapsedGenerationTime = initialElapsed;
    _elapsedTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      elapsedGenerationTime = _baseElapsedGenerationTime + _generationStopwatch.elapsed;
      notifyListeners();
    });
    notifyListeners();
  }

  void updateProgress(double percent, String nextStatus) {
    if (!isGenerating) {
      isGenerating = true;
    }
    if (hasError) {
      hasError = false;
      errorMessage = null;
    }
    progress = percent / 100.0;
    status = nextStatus;
    notifyListeners();
  }

  Duration complete(String nextStatus) {
    final elapsed = _stopTimer();
    isGenerating = false;
    isStopping = false;
    hasError = false;
    errorMessage = null;
    status = nextStatus;
    elapsedGenerationTime = elapsed;
    notifyListeners();
    return elapsed;
  }

  void fail([String? message]) {
    final elapsed = _stopTimer();
    isGenerating = false;
    isStopping = false;
    hasError = true;
    errorMessage = message;
    status = message == null || message.isEmpty ? '处理出错了，请重新生成' : message;
    elapsedGenerationTime = elapsed;
    notifyListeners();
  }

  Duration _stopTimer() {
    _generationStopwatch.stop();
    _elapsedTimer?.cancel();
    _elapsedTimer = null;
    return _baseElapsedGenerationTime + _generationStopwatch.elapsed;
  }

  @override
  void dispose() {
    _elapsedTimer?.cancel();
    super.dispose();
  }
}

class GenerateScreen extends StatefulWidget {
  final String songPath;
  final String songDisplayName;
  final String modelPath;
  final String modelDisplayName;
  final String? indexPath;
  final String? indexDisplayName;
  final RvcGenerationState generationState;
  final bool otherModeRunning;
  final void Function(String outputPath, Duration generationDuration) onGenerationComplete;

  const GenerateScreen({
    required this.songPath,
    required this.songDisplayName,
    required this.modelPath,
    required this.modelDisplayName,
    this.indexPath,
    this.indexDisplayName,
    required this.generationState,
    this.otherModeRunning = false,
    required this.onGenerationComplete,
  });

  @override
  _GenerateScreenState createState() => _GenerateScreenState();
}

class _GenerateScreenState extends State<GenerateScreen> with AutomaticKeepAliveClientMixin {
  final RVCBridge _rvcBridge = RVCBridge();
  static const String _resumeElapsedPrefix = 'audioInferenceResumeElapsedMs:';
  static const Duration _progressRecoveryPollInterval = Duration(seconds: 1);

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
  int _activeGenerationRequestId = 0;
  ResumableRvcJobMetadata? _resumableJobMetadata;
  int _resumableLookupRequestId = 0;
  StreamSubscription<InferenceProgressSnapshot>? _progressRecoverySubscription;
  Timer? _progressRecoveryTimer;
  DateTime? _lastProgressEventAt;

  static const _sampleRates = [48000, 44100, 40000];

  void _updateParameter(VoidCallback update) {
    setState(update);
    _saveParameters();
  }

  @override
  void initState() {
    super.initState();
    widget.generationState.addListener(_handleGenerationStateChanged);
    _loadSavedParameters();
    _refreshResumableJobMetadata();
  }

  @override
  void didUpdateWidget(covariant GenerateScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.generationState != widget.generationState) {
      oldWidget.generationState.removeListener(_handleGenerationStateChanged);
      widget.generationState.addListener(_handleGenerationStateChanged);
    }
    if (oldWidget.songPath != widget.songPath ||
        oldWidget.modelPath != widget.modelPath ||
        oldWidget.indexPath != widget.indexPath) {
      _refreshResumableJobMetadata();
    }
  }

  void _handleGenerationStateChanged() {
    if (mounted) {
      setState(() {});
    }
  }

  void _refreshResumableJobMetadataSoon() {
    Future<void>(() async {
      const retryDelays = <Duration>[
        Duration.zero,
        Duration(milliseconds: 300),
        Duration(seconds: 1),
      ];
      for (final delay in retryDelays) {
        if (delay > Duration.zero) {
          await Future.delayed(delay);
        }
        if (!mounted) return;
        await _refreshResumableJobMetadata();
        if (!mounted) return;
        if (_resumableJobMetadata != null) return;
      }
    });
  }

  Future<void> _loadSavedParameters() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _pitchChange = prefs.getDouble('pitchChange') ?? 0.0;
      _indexRate = prefs.getDouble('indexRate') ?? 0.75;
      _formant = prefs.getDouble('formant') ?? 0.0;
      _filterRadius = prefs.getInt('filterRadius') ?? 3;
      _rmsMixRate = prefs.getDouble('rmsMixRate') ?? 0.25;
      _protectRate = prefs.getDouble('protectRate') ?? 0.33;
      _noiseGateDb = prefs.getDouble('noiseGateDb') ?? 35.0;
      _outputDenoiseEnabled = prefs.getBool('outputDenoiseEnabled') ?? true;
      _vocalRangeFilterEnabled = prefs.getBool('vocalRangeFilterEnabled') ?? true;
      _sampleRate = prefs.getInt('sampleRate') ?? 48000;
    });
    _refreshResumableJobMetadata();
  }

  Future<void> _saveParameters() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setDouble('pitchChange', _pitchChange);
    await prefs.setDouble('indexRate', _indexRate);
    await prefs.setDouble('formant', _formant);
    await prefs.setInt('filterRadius', _filterRadius);
    await prefs.setDouble('rmsMixRate', _rmsMixRate);
    await prefs.setDouble('protectRate', _protectRate);
    await prefs.setDouble('noiseGateDb', _noiseGateDb);
    await prefs.setBool('outputDenoiseEnabled', _outputDenoiseEnabled);
    await prefs.setBool('vocalRangeFilterEnabled', _vocalRangeFilterEnabled);
    await prefs.setInt('sampleRate', _sampleRate);
    _refreshResumableJobMetadata();
  }

  String _resumeElapsedStorageKey() {
    return [
      _resumeElapsedPrefix,
      widget.songPath,
      widget.modelPath,
      widget.indexPath ?? '',
      _pitchChange.toStringAsFixed(4),
      _indexRate.toStringAsFixed(4),
      _formant.toStringAsFixed(4),
      _filterRadius.toString(),
      _rmsMixRate.toStringAsFixed(4),
      _protectRate.toStringAsFixed(4),
      _sampleRate.toString(),
      _noiseGateDb.toStringAsFixed(4),
      _outputDenoiseEnabled ? '1' : '0',
      _vocalRangeFilterEnabled ? '1' : '0',
    ].join('|');
  }

  Future<void> _storeResumeElapsedDuration(Duration duration) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_resumeElapsedStorageKey(), duration.inMilliseconds);
  }

  Future<Duration> _loadResumeElapsedDuration() async {
    final prefs = await SharedPreferences.getInstance();
    return Duration(milliseconds: prefs.getInt(_resumeElapsedStorageKey()) ?? 0);
  }

  Future<void> _clearResumeElapsedDuration() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_resumeElapsedStorageKey());
  }

  Future<void> _refreshResumableJobMetadata() async {
    final requestId = ++_resumableLookupRequestId;
    try {
      final metadata = await _rvcBridge.getResumableJobMetadata(
        songPath: widget.songPath,
        modelPath: widget.modelPath,
        indexPath: widget.indexPath,
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
      if (!mounted || requestId != _resumableLookupRequestId) return;
      setState(() {
        _resumableJobMetadata = metadata;
      });
    } catch (_) {
      if (!mounted || requestId != _resumableLookupRequestId) return;
      setState(() {
        _resumableJobMetadata = null;
      });
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
    });
    await _saveParameters();
  }

  @override
  void dispose() {
    _progressRecoverySubscription?.cancel();
    _progressRecoveryTimer?.cancel();
    widget.generationState.removeListener(_handleGenerationStateChanged);
    super.dispose();
  }

  void _markProgressEventReceived() {
    _lastProgressEventAt = DateTime.now();
  }

  void _startProgressRecovery(int requestId) {
    _progressRecoverySubscription?.cancel();
    _progressRecoveryTimer?.cancel();
    _lastProgressEventAt = DateTime.now();
    _progressRecoverySubscription = _rvcBridge.progressSnapshots().listen((snapshot) {
      if (!mounted || requestId != _activeGenerationRequestId) return;
      _markProgressEventReceived();
    });
    _progressRecoveryTimer = Timer.periodic(_progressRecoveryPollInterval, (_) async {
      if (!mounted || requestId != _activeGenerationRequestId) return;
      if (!widget.generationState.isGenerating) return;
      final lastEventAt = _lastProgressEventAt;
      if (lastEventAt != null && DateTime.now().difference(lastEventAt) < const Duration(seconds: 2)) {
        return;
      }
      try {
        final metadata = await _rvcBridge.getResumableJobMetadata(
          songPath: widget.songPath,
          modelPath: widget.modelPath,
          indexPath: widget.indexPath,
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
        if (!mounted || requestId != _activeGenerationRequestId || metadata == null) return;
        setState(() {
          _resumableJobMetadata = metadata;
        });
        final recoveredProgress = metadata.overallProgress.clamp(0.0, 100.0);
        if (recoveredProgress > widget.generationState.progress * 100.0) {
          widget.generationState.updateProgress(recoveredProgress, '恢复进度中');
        }
      } catch (_) {
      }
    });
  }

  void _stopProgressRecovery() {
    _progressRecoverySubscription?.cancel();
    _progressRecoverySubscription = null;
    _progressRecoveryTimer?.cancel();
    _progressRecoveryTimer = null;
    _lastProgressEventAt = null;
  }

  String _formatDuration(Duration duration) {
    final hours = duration.inHours.toString().padLeft(2, '0');
    final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
    final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
    return '$hours:$minutes:$seconds';
  }

  Future<void> _generate() async {
    if (widget.otherModeRunning) return;
    if (widget.generationState.isGenerating) return;
    final requestId = ++_activeGenerationRequestId;
    await _clearResumeElapsedDuration();
    widget.generationState.start();
    _startProgressRecovery(requestId);

    try {
      await _saveParameters();
      final outputPath = await _rvcBridge.infer(
        songPath: widget.songPath,
        modelPath: widget.modelPath,
        indexPath: widget.indexPath,
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
        allowResume: false,
        onProgress: (progress, status) {
          if (!mounted || requestId != _activeGenerationRequestId) return;
          widget.generationState.updateProgress(progress, status);
          if (status.startsWith('保存进度点')) {
            _storeResumeElapsedDuration(widget.generationState.elapsedGenerationTime);
          }
        },
      );
      if (!mounted || requestId != _activeGenerationRequestId) return;
      _stopProgressRecovery();
      final elapsedGenerationTime = widget.generationState.complete('生成完成');
      setState(() {
        _resumableJobMetadata = null;
      });
      await _clearResumeElapsedDuration();
      widget.onGenerationComplete(outputPath, elapsedGenerationTime);
    } catch (e) {
      if (!mounted || requestId != _activeGenerationRequestId) return;
      _stopProgressRecovery();
      widget.generationState.fail(_normalizeGenerationError(e));
      _refreshResumableJobMetadataSoon();
    }
  }

  Future<void> _continueUnfinishedGeneration() async {
    if (widget.otherModeRunning) return;
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
    if (widget.generationState.isGenerating) return;
    final requestId = ++_activeGenerationRequestId;
    final persistedElapsed = await _loadResumeElapsedDuration();
    final initialElapsed = persistedElapsed.inMilliseconds > 0
        ? persistedElapsed
        : Duration(milliseconds: _resumableJobMetadata?.accumulatedElapsedMs ?? 0);
    widget.generationState.start(initialElapsed: initialElapsed);
    _startProgressRecovery(requestId);

    try {
      await _saveParameters();
      final outputPath = await _rvcBridge.infer(
        songPath: widget.songPath,
        modelPath: widget.modelPath,
        indexPath: widget.indexPath,
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
        allowResume: true,
        onProgress: (progress, status) {
          if (!mounted || requestId != _activeGenerationRequestId) return;
          widget.generationState.updateProgress(progress, status);
          if (status.startsWith('保存进度点')) {
            _storeResumeElapsedDuration(widget.generationState.elapsedGenerationTime);
          }
        },
      );
      if (!mounted || requestId != _activeGenerationRequestId) return;
      _stopProgressRecovery();
      final elapsedGenerationTime = widget.generationState.complete('生成完成');
      setState(() {
        _resumableJobMetadata = null;
      });
      await _clearResumeElapsedDuration();
      widget.onGenerationComplete(outputPath, elapsedGenerationTime);
    } catch (e) {
      if (!mounted || requestId != _activeGenerationRequestId) return;
      _stopProgressRecovery();
      widget.generationState.fail(_normalizeGenerationError(e));
      _refreshResumableJobMetadataSoon();
    }
  }

  String _normalizeGenerationError(Object error) {
    final message = error.toString();
    if (message.contains('生成已中止')) {
      return '生成已中止';
    }
    if (message.contains('推理进程已中断') || message.contains('推理进程已断开')) {
      return '生成已中止：推理进程已中断';
    }
    return '处理出错：$message';
  }

  Future<void> _stopGeneration() async {
    if (!widget.generationState.isGenerating || widget.generationState.isStopping) return;
    setState(() {
      widget.generationState.isStopping = true;
      widget.generationState.status = '正在中止生成';
    });
    try {
      await _rvcBridge.stopInference();
      if (!mounted) return;
      if (widget.generationState.isStopping) {
        widget.generationState.status = '正在等待推理进程结束';
        setState(() {});
        _refreshResumableJobMetadataSoon();
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        widget.generationState.isStopping = false;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('终止生成失败：$e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final _isGenerating = widget.generationState.isGenerating;
    final controlsLocked = _isGenerating || widget.otherModeRunning || widget.generationState.isStopping;
    final primaryActionLocked = widget.otherModeRunning || widget.generationState.isStopping;
    final _progress = widget.generationState.progress;
    final _status = widget.generationState.status;
    final _elapsedGenerationTime = widget.generationState.elapsedGenerationTime;

    return Scaffold(
      appBar: AppBar(
        title: Text('生成音频'),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
                    // Song and Model Info
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '输入音频：',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    Text(
                      widget.songDisplayName,
                      style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                    SizedBox(height: 8),
                    Text(
                      '音色模型：',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    Text(
                      widget.modelDisplayName,
                      style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                    SizedBox(height: 8),
                    Text(
                      '索引文件：',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    Text(
                      widget.indexDisplayName ?? widget.indexPath?.split('/').last ?? '未选择',
                      style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                  ],
                ),
              ),
            ),
            
            SizedBox(height: 16),

            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '参数',
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                    SizedBox(height: 16),
                    Text('Pitch（音调设置）：${_pitchChange.toStringAsFixed(1)} 半音'),
                    Slider(
                      value: _pitchChange,
                      min: -24.0,
                      max: 24.0,
                      divisions: 96,
                      label: _pitchChange.toStringAsFixed(1),
                      onChanged: controlsLocked ? null : (value) {
                        _updateParameter(() => _pitchChange = value);
                      },
                    ),
                    Text('Index Rate（索引强度）：${(_indexRate * 100).toStringAsFixed(0)}%'),
                    Slider(
                      value: _indexRate,
                      min: 0.0,
                      max: 1.0,
                      divisions: 20,
                      label: (_indexRate * 100).toStringAsFixed(0),
                      onChanged: controlsLocked ? null : (value) {
                        _updateParameter(() => _indexRate = value);
                      },
                    ),
                    Text('Formant（性别因子/声线粗细）：${_formant.toStringAsFixed(2)}'),
                    Slider(
                      value: _formant,
                      min: -4.0,
                      max: 4.0,
                      divisions: 160,
                      label: _formant.toStringAsFixed(2),
                      onChanged: controlsLocked ? null : (value) {
                        _updateParameter(() => _formant = value);
                      },
                    ),
                    Text('Noise Gate（噪声过滤）：${_noiseGateDb.toStringAsFixed(0)} dB'),
                    Slider(
                      value: _noiseGateDb,
                      min: 0.0,
                      max: 100.0,
                      divisions: 100,
                      label: _noiseGateDb.toStringAsFixed(0),
                      onChanged: controlsLocked ? null : (value) {
                        _updateParameter(() => _noiseGateDb = value);
                      },
                    ),
                    DropdownButtonFormField<int>(
                      value: _sampleRate,
                      decoration: InputDecoration(labelText: 'Sample Rate（采样率）'),
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
                          onChanged: controlsLocked ? null : (value) {
                            _updateParameter(() => _outputDenoiseEnabled = value);
                          },
                        ),
                        SwitchListTile(
                          title: Text('音域过滤'),
                          subtitle: Text('只影响处理结果，不改原始录音文件'),
                          value: _vocalRangeFilterEnabled,
                          onChanged: controlsLocked ? null : (value) {
                            _updateParameter(() => _vocalRangeFilterEnabled = value);
                          },
                        ),
                        Text('Filter Radius（音高滤波）：$_filterRadius'),
                        Slider(
                          value: _filterRadius.toDouble(),
                          min: 0,
                          max: 10,
                          divisions: 10,
                          label: _filterRadius.toString(),
                          onChanged: controlsLocked ? null : (value) {
                            _updateParameter(() => _filterRadius = value.toInt());
                          },
                        ),
                        Text('RMS Mix（响度混合）：${(_rmsMixRate * 100).toStringAsFixed(0)}%'),
                        Slider(
                          value: _rmsMixRate,
                          min: 0.0,
                          max: 1.0,
                          divisions: 20,
                          label: (_rmsMixRate * 100).toStringAsFixed(0),
                          onChanged: controlsLocked ? null : (value) {
                            _updateParameter(() => _rmsMixRate = value);
                          },
                        ),
                        Text('Protect（辅音保护）：${(_protectRate * 100).toStringAsFixed(0)}%'),
                        Slider(
                          value: _protectRate,
                          min: 0.0,
                          max: 1.0,
                          divisions: 100,
                          label: (_protectRate * 100).toStringAsFixed(0),
                          onChanged: _isGenerating ? null : (value) {
                            _updateParameter(() => _protectRate = value);
                          },
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

            // Progress and Generation
            if (_isGenerating) ...[
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    children: [
                      Text(
                        '生成中...',
                        style: Theme.of(context).textTheme.titleSmall,
                      ),
                      SizedBox(height: 16),
                      LinearProgressIndicator(value: _progress),
                      SizedBox(height: 8),
                      Text(
                        _status,
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                      SizedBox(height: 8),
                      Text('生成用时：${_formatDuration(_elapsedGenerationTime)}'),
                      SizedBox(height: 8),
                      Text(
                        '${(_progress * 100).toStringAsFixed(1)}%',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      SizedBox(height: 12),
                      ElevatedButton.icon(
                        onPressed: widget.generationState.isStopping ? null : _stopGeneration,
                        icon: Icon(Icons.stop_circle),
                        label: Text(widget.generationState.isStopping ? '正在中止' : '终止生成'),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.red,
                          foregroundColor: Colors.white,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ] else ...[
              if (widget.generationState.hasError) ...[
                Text(widget.generationState.status,
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: Colors.red,
                        fontWeight: FontWeight.bold,
                      ),
                ),
                SizedBox(height: 12),
              ],
              // Generate Button
              ElevatedButton.icon(
                onPressed: primaryActionLocked ? null : _generate,
                icon: Icon(Icons.auto_awesome),
                label: Text(_resumableJobMetadata != null ? '重新生成' : '开始生成'),
                style: ElevatedButton.styleFrom(
                  padding: EdgeInsets.symmetric(horizontal: 32, vertical: 16),
                ),
              ),
              if (_resumableJobMetadata != null) ...[
                SizedBox(height: 12),
                OutlinedButton.icon(
                  onPressed: primaryActionLocked ? null : _continueUnfinishedGeneration,
                  icon: Icon(Icons.restore),
                  label: Text('继续未完成'),
                  style: OutlinedButton.styleFrom(
                    padding: EdgeInsets.symmetric(horizontal: 32, vertical: 16),
                  ),
                ),
              ],
            ],

            SizedBox(height: 24),

            // Tips
            Card(
              color: Colors.blue.shade50,
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Text(
                  '处理时间取决于音频长度',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Colors.blue.shade700,
                      ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  bool get wantKeepAlive => true;
}
