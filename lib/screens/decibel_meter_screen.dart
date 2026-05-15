import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:permission_handler/permission_handler.dart';

import '../services/rvc_bridge.dart';
import '../widgets/fullscreen_measurement_chart_page.dart';
import '../widgets/measurement_chart_card.dart';
import '../widgets/measurement_models.dart';

class DecibelMeterScreen extends StatefulWidget {
  final bool isActive;

  const DecibelMeterScreen({super.key, required this.isActive});

  @override
  State<DecibelMeterScreen> createState() => _DecibelMeterScreenState();
}

class _DecibelMeterScreenState extends State<DecibelMeterScreen> with WidgetsBindingObserver {
  static const _smoothingFactor = 0.2;
  static const _chartTick = Duration(milliseconds: 350);

  final RVCBridge _rvcBridge = RVCBridge();
  final List<MeasurementSamplePoint> _history = [];
  StreamSubscription<double>? _subscription;
  Timer? _chartTimer;
  void Function(void Function())? _fullscreenDecibelRefresh;

  double _displayDb = 0.0;
  double _lockedDb = 0.0;
  bool _isLocked = false;
  bool _autoLockedByBackground = false;
  String _status = '未启动';
  DateTime? _startedAt;
  Duration _accumulatedRunDuration = Duration.zero;
  DateTime? _lastResumeAt;
  MeasurementAxisRange _axisRange = const MeasurementAxisRange(min: 0, max: 50);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    if (widget.isActive) {
      _startMeter();
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
  void didUpdateWidget(covariant DecibelMeterScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!oldWidget.isActive && widget.isActive) {
      _startMeter();
    } else if (oldWidget.isActive && !widget.isActive) {
      _stopMeter();
    }
  }

  Future<void> _startMeter() async {
    if (_subscription != null) return;
    final permission = await Permission.microphone.request();
    if (!mounted) return;
    if (!permission.isGranted) {
      setState(() => _status = '需要麦克风权限');
      return;
    }
    _startedAt ??= DateTime.now();
    if (_history.isEmpty) {
      _axisRange = const MeasurementAxisRange(min: 0, max: 50);
      _displayDb = 0.0;
      _lockedDb = 0.0;
      _accumulatedRunDuration = Duration.zero;
    }
    _lastResumeAt = DateTime.now();
    _subscription = _rvcBridge.decibelStream().listen(
      (value) => setState(() {
        if (_isLocked) return;
        _displayDb = _displayDb + (value - _displayDb) * _smoothingFactor;
        _status = '实时测量中';
      }),
      onError: (error) => setState(() => _status = '测量失败：$error'),
    );
    _chartTimer?.cancel();
    _chartTimer = Timer.periodic(_chartTick, (_) {
      if (!mounted || _isLocked || _subscription == null) return;
      setState(() => _appendHistory(_displayDb));
    });
  }

  void _appendHistory(double value) {
    final activeDuration = _accumulatedRunDuration +
        ((_lastResumeAt == null) ? Duration.zero : DateTime.now().difference(_lastResumeAt!));
    final elapsed = activeDuration.inMilliseconds / 1000.0;
    _history.add(MeasurementSamplePoint(timeSeconds: elapsed, value: value));
    final nextRange = buildAxisRange(
      _history,
      previous: _axisRange,
      defaultMin: 0,
      defaultMax: 50,
      minimumSpan: 10,
    );
    _axisRange = MeasurementAxisRange(min: 0, max: nextRange.max < 10 ? 10 : nextRange.max);
    _fullscreenDecibelRefresh?.call(() {});
  }

  void _stopMeter({bool updateStatus = true}) {
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
      _startedAt = DateTime.now();
      _accumulatedRunDuration = Duration.zero;
      _lastResumeAt = _subscription != null ? DateTime.now() : null;
      _axisRange = const MeasurementAxisRange(min: 0, max: 50);
    });
  }

  void _toggleLock() {
    if (_isLocked) {
      _autoLockedByBackground = false;
      setState(() {
        _isLocked = false;
        _status = '实时测量中';
      });
      _startMeter();
      return;
    }
    _lockedDb = _displayDb;
    setState(() {
      _isLocked = true;
      _status = '已锁定';
    });
    _stopMeter(updateStatus: false);
  }

  Future<void> _openFullscreenChart() async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => FullscreenMeasurementChartPage(
          child: StatefulBuilder(
            builder: (context, setDialogState) {
              _fullscreenDecibelRefresh = setDialogState;
              return MeasurementChartCard(
                title: '分贝实时曲线',
                points: _history,
                axisRange: _axisRange,
                yAxisLabel: 'dB',
                showExpandButton: false,
                dense: true,
                showHeader: false,
                showFrame: false,
                expandToFit: true,
                centerZero: false,
                yStep: null,
              );
            },
          ),
        ),
      ),
    );
    _fullscreenDecibelRefresh = null;
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _stopMeter(updateStatus: false);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final shownDb = _isLocked ? _lockedDb : _displayDb;

    return Scaffold(
      appBar: AppBar(
        title: Text('分贝仪'),
        centerTitle: true,
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
                    Text(
                      '${shownDb.toStringAsFixed(1)} dB',
                      style: Theme.of(context).textTheme.displayMedium?.copyWith(fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 16),
                    IconButton(
                      onPressed: _toggleLock,
                      icon: SvgPicture.string(
                        _isLocked ? _lockSvg : _unlockSvg,
                        width: 28,
                        height: 28,
                        colorFilter: ColorFilter.mode(Theme.of(context).colorScheme.onSurface, BlendMode.srcIn),
                      ),
                      tooltip: _isLocked ? '解除锁定' : '锁定当前数值',
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            MeasurementChartCard(
              title: '分贝实时曲线',
              points: _history,
              axisRange: _axisRange,
              yAxisLabel: 'dB',
              onOpenFullscreen: _openFullscreenChart,
              // tooltip: '清空曲线'
              onClear: _clearHistory,
              centerZero: false,
              yStep: null,
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
}
