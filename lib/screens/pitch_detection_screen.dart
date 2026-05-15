import 'dart:async';
import 'dart:collection';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/rvc_bridge.dart';
import '../widgets/fullscreen_measurement_chart_page.dart';
import '../widgets/measurement_chart_card.dart';
import '../widgets/measurement_models.dart';

enum PitchSensitivity {
  stable(windowSize: 5, smoothingFactor: 0.32, label: '稳定'),
  responsive(windowSize: 3, smoothingFactor: 0.58, label: '灵敏'),
  raw(windowSize: 1, smoothingFactor: 1.0, label: '极灵敏');

  final int windowSize;
  final double smoothingFactor;
  final String label;

  const PitchSensitivity({
    required this.windowSize,
    required this.smoothingFactor,
    required this.label,
  });
}

class PitchDetectionScreen extends StatefulWidget {
  final bool isActive;

  const PitchDetectionScreen({super.key, required this.isActive});

  @override
  State<PitchDetectionScreen> createState() => _PitchDetectionScreenState();
}

class _PitchDetectionScreenState extends State<PitchDetectionScreen> with WidgetsBindingObserver {
  static const _chartTick = Duration(milliseconds: 180);

  final RVCBridge _rvcBridge = RVCBridge();
  final List<MeasurementSamplePoint> _history = [];
  final List<MeasurementSamplePoint> _frequencyHistory = [];
  final ListQueue<double> _frequencyWindow = ListQueue<double>();
  final TextEditingController _referenceController = TextEditingController();

  StreamSubscription<Map<String, dynamic>>? _subscription;
  Timer? _chartTimer;
  void Function(void Function())? _fullscreenPitchRefresh;
  MeasurementChartMode _chartMode = MeasurementChartMode.relativeSemitone;
  PitchSensitivity _sensitivity = PitchSensitivity.responsive;
  MeasurementAxisRange _axisRange = const MeasurementAxisRange(min: -12, max: 12);
  DateTime? _startedAt;
  bool _isLocked = false;
  String _status = '未启动';
  String _referenceNoteText = '';
  double _displayFrequencyHz = 0.0;
  double _lockedFrequencyHz = 0.0;
  String _displayNote = '—';
  String _lockedNote = '—';
  double _displaySemitoneValue = 0.0;
  double _lockedSemitoneValue = 0.0;
  bool _autoLockedByBackground = false;
  bool _hasValidPitch = false;
  Duration _accumulatedRunDuration = Duration.zero;
  DateTime? _lastResumeAt;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadSettings();
    if (widget.isActive) {
      _startDetection();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _autoLockedByBackground && _isLocked) {
      _autoLockedByBackground = false;
      _toggleLock();
      return;
    }
    if (state == AppLifecycleState.hidden) {
      _autoLockForBackground();
    }
  }

  void _autoLockForBackground() {
    if (_isLocked || _subscription == null) return;
    _autoLockedByBackground = true;
    _toggleLock();
  }

  @override
  void didUpdateWidget(covariant PitchDetectionScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!oldWidget.isActive && widget.isActive) {
      _startDetection();
    } else if (oldWidget.isActive && !widget.isActive) {
      _stopDetection();
    }
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _referenceNoteText = prefs.getString('pitchReferenceNote') ?? '';
      _referenceController.text = _referenceNoteText;
      _chartMode = MeasurementChartMode.values.byName(
        prefs.getString('pitchChartMode') ?? MeasurementChartMode.relativeSemitone.name,
      );
      _sensitivity = PitchSensitivity.values.byName(
        prefs.getString('pitchSensitivity') ?? PitchSensitivity.responsive.name,
      );
    });
  }

  Future<void> _saveSettings() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('pitchReferenceNote', _referenceNoteText);
    await prefs.setString('pitchChartMode', _chartMode.name);
    await prefs.setString('pitchSensitivity', _sensitivity.name);
  }

  Future<void> _startDetection() async {
    if (_subscription != null) return;
    final permission = await Permission.microphone.request();
    if (!mounted) return;
    if (!permission.isGranted) {
      setState(() => _status = '需要麦克风权限');
      return;
    }
    if (mounted) {
      setState(() => _status = '未启动');
    }
    _startedAt ??= DateTime.now();
    _frequencyWindow.clear();
    if (_history.isEmpty) {
      _axisRange = _defaultAxisRange();
      _displayFrequencyHz = 0.0;
      _displayNote = '—';
      _displaySemitoneValue = 0.0;
      _accumulatedRunDuration = Duration.zero;
      _hasValidPitch = false;
    }
    _lastResumeAt = null;
    _subscription = _rvcBridge.pitchStream().listen((event) {
      if (!mounted || _isLocked) return;
      final snapshot = PitchDetectionSnapshot.fromMap(event);
      final rawFrequency = snapshot.frequencyHz <= 0 ? _displayFrequencyHz : snapshot.frequencyHz;
      if (rawFrequency <= 0 && _displayFrequencyHz <= 0) return;
      _frequencyWindow.add(rawFrequency);
      while (_frequencyWindow.length > _sensitivity.windowSize) {
        _frequencyWindow.removeFirst();
      }
      final averageFrequency = _frequencyWindow.isEmpty
          ? rawFrequency
          : _frequencyWindow.reduce((sum, item) => sum + item) / _frequencyWindow.length;
      final nextFrequency = _displayFrequencyHz <= 0
          ? averageFrequency
          : _displayFrequencyHz + (averageFrequency - _displayFrequencyHz) * _sensitivity.smoothingFactor;
      final nextNote = snapshot.note == '—' ? _frequencyToNote(nextFrequency) : snapshot.note;
      final nextSemitone = _relativeSemitoneFromReference(nextFrequency);
      setState(() {
        _displayFrequencyHz = nextFrequency;
        _displayNote = nextNote;
        _displaySemitoneValue = nextSemitone;
        _hasValidPitch = true;
        _startedAt ??= DateTime.now();
        _lastResumeAt ??= DateTime.now();
        _status = '实时检测中';
      });
    }, onError: (error) {
      if (!mounted) return;
      setState(() => _status = '检测失败：$error');
    });
    _chartTimer?.cancel();
    _chartTimer = Timer.periodic(_chartTick, (_) {
      if (!mounted || _isLocked || _subscription == null || !_hasValidPitch) return;
      setState(() => _appendHistory(_displayFrequencyHz));
    });
  }

  void _stopDetection({bool updateStatus = true}) {
    if (_lastResumeAt != null) {
      _accumulatedRunDuration += DateTime.now().difference(_lastResumeAt!);
      _lastResumeAt = null;
    }
    _chartTimer?.cancel();
    _chartTimer = null;
    _subscription?.cancel();
    _subscription = null;
    if (updateStatus && mounted) {
      setState(() => _status = '未启动');
    }
  }

  void _clearHistory() {
    setState(() {
      _history.clear();
      _frequencyHistory.clear();
      _startedAt = DateTime.now();
      _accumulatedRunDuration = Duration.zero;
      _lastResumeAt = _subscription != null ? DateTime.now() : null;
      _hasValidPitch = false;
      _axisRange = _defaultAxisRange();
    });
  }

  void _appendHistory(double frequency) {
    final activeDuration = _accumulatedRunDuration +
        ((_lastResumeAt == null) ? Duration.zero : DateTime.now().difference(_lastResumeAt!));
    final elapsed = activeDuration.inMilliseconds / 1000.0;
    _frequencyHistory.add(MeasurementSamplePoint(timeSeconds: elapsed, value: frequency));
    _rebuildVisibleHistory();
  }

  List<MeasurementSamplePoint> _visibleHistory() {
    return _frequencyHistory
        .map((point) => MeasurementSamplePoint(timeSeconds: point.timeSeconds, value: _chartValue(point.value)))
        .toList(growable: false);
  }

  void _rebuildVisibleHistory() {
    _history
      ..clear()
      ..addAll(_visibleHistory());
    _rebuildAxisRange();
    _fullscreenPitchRefresh?.call(() {});
  }

  void _rebuildAxisRange() {
    final nextRange = buildAxisRange(
      _history,
      previous: _axisRange,
      defaultMin: _defaultAxisRange().min,
      defaultMax: _defaultAxisRange().max,
      minimumSpan: _minimumAxisSpan(),
    );
    _axisRange = _chartMode == MeasurementChartMode.frequencyHz || _chartMode == MeasurementChartMode.pitchNoteName
        ? MeasurementAxisRange(
            min: _chartMode == MeasurementChartMode.frequencyHz ? 0 : nextRange.min,
            max: nextRange.max < _minimumAxisSpan() ? _minimumAxisSpan() : nextRange.max,
          )
        : nextRange;
  }

  MeasurementAxisRange _defaultAxisRange() {
    return switch (_chartMode) {
      MeasurementChartMode.frequencyHz => const MeasurementAxisRange(min: 0, max: 400),
      MeasurementChartMode.pitchNoteName => const MeasurementAxisRange(min: 36, max: 72),
      _ => const MeasurementAxisRange(min: -12, max: 12),
    };
  }

  double _minimumAxisSpan() {
    return switch (_chartMode) {
      MeasurementChartMode.frequencyHz => 30,
      MeasurementChartMode.pitchNoteName => 12,
      _ => 4,
    };
  }

  double _chartValue(double frequency) {
    return switch (_chartMode) {
      MeasurementChartMode.frequencyHz => frequency,
      MeasurementChartMode.pitchNoteName => _frequencyToMidi(frequency),
      _ => _relativeSemitoneFromReference(frequency),
    };
  }

  String _currentReferenceNote() {
    return _referenceNoteText.trim().isEmpty ? 'C4' : _referenceNoteText.trim();
  }

  double _referenceMidiValue() {
    return (_noteToMidi(_currentReferenceNote()) ?? 60).toDouble();
  }

  double _relativeSemitoneFromReference(double frequency) {
    if (frequency <= 0) return 0.0;
    final midi = _frequencyToMidi(frequency);
    final relative = midi - _referenceMidiValue();
    return (relative * 2).round() / 2;
  }

  double _frequencyToMidi(double frequency) {
    if (frequency <= 0) return 0.0;
    return 69 + 12 * math.log(frequency / 440.0) / math.ln2;
  }

  int? _noteToMidi(String note) {
    final match = RegExp(r'^([A-G])(#?)(-?\d+)$').firstMatch(note.trim());
    if (match == null) return null;
    const baseOffsets = {
      'C': 0,
      'D': 2,
      'E': 4,
      'F': 5,
      'G': 7,
      'A': 9,
      'B': 11,
    };
    final base = baseOffsets[match.group(1)!]!;
    final sharp = match.group(2) == '#' ? 1 : 0;
    final octave = int.tryParse(match.group(3)!) ?? 4;
    return (octave + 1) * 12 + base + sharp;
  }

  String _frequencyToNote(double frequency) {
    if (frequency <= 0) return '—';
    const noteNames = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
    final midi = _frequencyToMidi(frequency).round();
    final note = noteNames[midi % 12];
    final octave = (midi ~/ 12) - 1;
    return '$note$octave';
  }

  String _frequencyToNoteLabel(double value) {
    if (value <= 0) return '—';
    const noteNames = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
    final midi = value.round();
    final note = noteNames[midi % 12];
    final octave = (midi ~/ 12) - 1;
    return '$note$octave';
  }

  void _toggleLock() {
    if (_isLocked) {
      _autoLockedByBackground = false;
      setState(() {
        _isLocked = false;
        _status = '实时检测中';
      });
      _startDetection();
      return;
    }
    _lockedFrequencyHz = _displayFrequencyHz;
    _lockedNote = _displayNote;
    _lockedSemitoneValue = _displaySemitoneValue;
    setState(() {
      _isLocked = true;
      _status = '已锁定';
    });
    _stopDetection(updateStatus: false);
  }

  Future<void> _captureCurrentAsReference() async {
    if (_displayNote == '—') return;
    setState(() {
      _referenceNoteText = _displayNote;
      _referenceController.text = _displayNote;
      _displaySemitoneValue = _relativeSemitoneFromReference(_displayFrequencyHz);
      _axisRange = _defaultAxisRange();
      _rebuildVisibleHistory();
    });
    await _saveSettings();
    await _rvcBridge.showToast('已将当前音高设为相对音高基准 0');
  }

  Future<void> _showSettingsDialog() async {
    final tempController = TextEditingController(text: _referenceController.text);
    var selectedMode = _chartMode;
    var selectedSensitivity = _sensitivity;
    await showDialog<void>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('音高检测设置'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: tempController,
                decoration: const InputDecoration(
                  labelText: '基准音高（留空默认 C4）',
                ),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<MeasurementChartMode>(
                value: selectedMode,
                decoration: const InputDecoration(labelText: '图表 Y 轴模式'),
                items: const [
                  DropdownMenuItem(
                    value: MeasurementChartMode.relativeSemitone,
                    child: Text('相对半音值'),
                  ),
                  DropdownMenuItem(
                    value: MeasurementChartMode.frequencyHz,
                    child: Text('频率 Hz'),
                  ),
                  DropdownMenuItem(
                    value: MeasurementChartMode.pitchNoteName,
                    child: Text('音名（A2/C4）'),
                  ),
                ],
                onChanged: (value) {
                  if (value == null) return;
                  setDialogState(() => selectedMode = value);
                },
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<PitchSensitivity>(
                value: selectedSensitivity,
                decoration: const InputDecoration(labelText: '检测灵敏度'),
                items: PitchSensitivity.values
                    .map((sensitivity) => DropdownMenuItem(
                          value: sensitivity,
                          child: Text(sensitivity.label),
                        ))
                    .toList(growable: false),
                onChanged: (value) {
                  if (value == null) return;
                  setDialogState(() => selectedSensitivity = value);
                },
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () async {
                final text = tempController.text.trim();
                if (text.isNotEmpty && _noteToMidi(text) == null) {
                  Navigator.of(context).pop();
                  ScaffoldMessenger.of(this.context).showSnackBar(
                    const SnackBar(content: Text('基准音高格式无效，请使用 C4 / F#3 这类格式')),
                  );
                  return;
                }
                setState(() {
                  _referenceNoteText = text;
                  _referenceController.text = text;
                  _chartMode = selectedMode;
                  _sensitivity = selectedSensitivity;
                  while (_frequencyWindow.length > _sensitivity.windowSize) {
                    _frequencyWindow.removeFirst();
                  }
                  _displaySemitoneValue = _relativeSemitoneFromReference(_displayFrequencyHz);
                  _axisRange = _defaultAxisRange();
                  _rebuildVisibleHistory();
                });
                await _saveSettings();
                if (mounted) Navigator.of(context).pop();
              },
              child: const Text('保存'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _openFullscreenChart() async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => FullscreenMeasurementChartPage(
          child: StatefulBuilder(
            builder: (context, setDialogState) {
              _fullscreenPitchRefresh = setDialogState;
              return MeasurementChartCard(
                title: '音高实时曲线',
                points: _history,
                axisRange: _axisRange,
                yAxisLabel: _chartYAxisLabel(),
                showExpandButton: false,
                dense: true,
                showHeader: false,
                showFrame: false,
                expandToFit: true,
                centerZero: _chartMode == MeasurementChartMode.relativeSemitone,
                yStep: _chartMode == MeasurementChartMode.relativeSemitone ? 0.5 : null,
                yValueFormatter: _chartMode == MeasurementChartMode.pitchNoteName ? _chartYValueLabel : null,
              );
            },
          ),
        ),
      ),
    );
    _fullscreenPitchRefresh = null;
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _stopDetection(updateStatus: false);
    _referenceController.dispose();
    super.dispose();
  }

  String _chartYAxisLabel() {
    return switch (_chartMode) {
      MeasurementChartMode.frequencyHz => 'Hz',
      MeasurementChartMode.pitchNoteName => '音名',
      _ => '半音',
    };
  }

  String _chartYValueLabel(double value) => _frequencyToNoteLabel(value);

  @override
  Widget build(BuildContext context) {
    final displayFrequency = _isLocked ? _lockedFrequencyHz : _displayFrequencyHz;
    final displayNote = _isLocked ? _lockedNote : _displayNote;
    final displaySemitone = _isLocked ? _lockedSemitoneValue : _displaySemitoneValue;
    final displaySemitoneText = '相对半音值：${displaySemitone >= 0 ? '+' : ''}${displaySemitone.toStringAsFixed(1)}';

    return Scaffold(
      appBar: AppBar(
        title: Text('音高检测'),
        centerTitle: true,
        actions: [
          IconButton(
            onPressed: _showSettingsDialog,
            icon: const Icon(Icons.settings),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(_status, style: Theme.of(context).textTheme.bodyMedium),
            const SizedBox(height: 16),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  children: [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Spacer(),
                        IconButton(
                          onPressed: _captureCurrentAsReference,
                          icon: SvgPicture.string(
                            _pinSvg,
                            width: 28,
                            height: 28,
                            colorFilter: ColorFilter.mode(Theme.of(context).colorScheme.onSurface, BlendMode.srcIn),
                          ),
                          tooltip: '将当前音高设为相对 0',
                        ),
                      ],
                    ),
                    Text(
                      displayNote,
                      style: Theme.of(context).textTheme.displayMedium?.copyWith(fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 8),
                    Text('${displayFrequency.toStringAsFixed(1)} Hz'),
                    const SizedBox(height: 16),
                    Text(
                      displaySemitoneText,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                    const SizedBox(height: 18),
                    Align(
                      alignment: Alignment.center,
                      child: IconButton(
                        onPressed: _toggleLock,
                        icon: SvgPicture.string(
                          _isLocked ? _lockSvg : _unlockSvg,
                          width: 28,
                          height: 28,
                          colorFilter: ColorFilter.mode(Theme.of(context).colorScheme.onSurface, BlendMode.srcIn),
                        ),
                        tooltip: _isLocked ? '解除锁定' : '锁定当前音高',
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            MeasurementChartCard(
              title: '音高实时曲线',
              points: _history,
              axisRange: _axisRange,
              yAxisLabel: _chartYAxisLabel(),
              onOpenFullscreen: _openFullscreenChart,
              // tooltip: '清空曲线'
              onClear: _clearHistory,
              centerZero: _chartMode == MeasurementChartMode.relativeSemitone,
              yStep: _chartMode == MeasurementChartMode.relativeSemitone ? 0.5 : null,
              yValueFormatter: _chartMode == MeasurementChartMode.pitchNoteName ? _chartYValueLabel : null,
            ),
          ],
        ),
      ),
    );
  }

  static const _lockSvg = '''
<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
  <path d="M7 10V8a5 5 0 0 1 10 0v2" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
  <rect x="5" y="10" width="14" height="10" rx="2" fill="none" stroke="currentColor" stroke-width="2"/>
</svg>
''';

  static const _unlockSvg = '''
<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
  <path d="M8 10V8a5 5 0 0 1 8.5-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
  <rect x="5" y="10" width="14" height="10" rx="2" fill="none" stroke="currentColor" stroke-width="2"/>
</svg>
''';

  static const _pinSvg = '''
<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
  <path d="M8 4h8v2.5l-2 2v4l2 1.5V16H8v-1.5l2-1.5v-4l-2-2V4z" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linejoin="round" stroke-linecap="round"/>
  <path d="M12 16v5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/>
</svg>
''';
}
