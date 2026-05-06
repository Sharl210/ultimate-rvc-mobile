import 'dart:async';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../services/rvc_bridge.dart';

class DecibelMeterScreen extends StatefulWidget {
  final bool isActive;

  const DecibelMeterScreen({required this.isActive});

  @override
  State<DecibelMeterScreen> createState() => _DecibelMeterScreenState();
}

class _DecibelMeterScreenState extends State<DecibelMeterScreen> {
  final RVCBridge _rvcBridge = RVCBridge();
  StreamSubscription<double>? _subscription;
  double _displayDb = 0.0;
  double _lockedDb = 0.0;
  bool _isLocked = false;
  String _status = '未启动';

  @override
  void initState() {
    super.initState();
    if (widget.isActive) {
      _startMeter();
    }
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
    _subscription = _rvcBridge.decibelStream().listen(
      (value) => setState(() {
        _displayDb = value;
        _status = '实时测量中';
      }),
      onError: (error) => setState(() => _status = '测量失败：$error'),
    );
  }

  void _stopMeter({bool updateStatus = true}) {
    _subscription?.cancel();
    _subscription = null;
    if (updateStatus && mounted) {
      setState(() => _status = '未启动');
    }
  }

  void _toggleLock() {
    setState(() {
      if (_isLocked) {
        _isLocked = false;
      } else {
        _lockedDb = _displayDb;
        _isLocked = true;
      }
    });
  }

  @override
  void dispose() {
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
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_status, style: Theme.of(context).textTheme.bodyMedium),
            SizedBox(height: 16),
            Text(
              '${shownDb.toStringAsFixed(1)} dB',
              style: Theme.of(context).textTheme.displayMedium?.copyWith(fontWeight: FontWeight.bold),
            ),
            SizedBox(height: 16),
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
