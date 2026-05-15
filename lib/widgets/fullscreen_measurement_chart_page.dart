import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class FullscreenMeasurementChartPage extends StatefulWidget {
  final Widget child;

  const FullscreenMeasurementChartPage({
    super.key,
    required this.child,
  });

  @override
  State<FullscreenMeasurementChartPage> createState() => _FullscreenMeasurementChartPageState();
}

class _FullscreenMeasurementChartPageState extends State<FullscreenMeasurementChartPage> {
  Future<void> _closeFullscreen() async {
    await SystemChrome.setPreferredOrientations([
      DeviceOrientation.portraitUp,
      DeviceOrientation.portraitDown,
    ]);
    if (mounted) {
      Navigator.of(context).pop();
    }
  }

  @override
  void initState() {
    super.initState();
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
  }

  @override
  void dispose() {
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.portraitUp,
      DeviceOrientation.portraitDown,
    ]);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surface,
      body: SafeArea(
        child: Stack(
          children: [
            Positioned.fill(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: widget.child,
              ),
            ),
            Positioned(
              top: 8,
              right: 8,
              child: Material(
                color: Theme.of(context).colorScheme.surface.withOpacity(0.82),
                shape: const CircleBorder(),
                child: IconButton(
                  onPressed: _closeFullscreen,
                  icon: const Icon(Icons.close_fullscreen),
                  tooltip: '退出全屏',
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
