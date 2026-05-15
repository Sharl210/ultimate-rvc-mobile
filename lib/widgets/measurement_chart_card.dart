import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import 'measurement_models.dart';

class MeasurementChartCard extends StatelessWidget {
  final String title;
  final String yAxisLabel;
  final List<MeasurementSamplePoint> points;
  final MeasurementAxisRange axisRange;
  final VoidCallback? onOpenFullscreen;
  final bool showExpandButton;
  final bool dense;
  final bool showHeader;
  final bool showFrame;
  final bool expandToFit;
  final VoidCallback? onClear;
  final bool centerZero;
  final double? yStep;
  final String Function(double value)? yValueFormatter;

  const MeasurementChartCard({
    super.key,
    required this.title,
    required this.yAxisLabel,
    required this.points,
    required this.axisRange,
    this.onOpenFullscreen,
    this.showExpandButton = true,
    this.dense = false,
    this.showHeader = true,
    this.showFrame = true,
    this.expandToFit = false,
    this.onClear,
    this.centerZero = false,
    this.yStep,
    this.yValueFormatter,
  });

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final headerHeight = showHeader ? 44.0 : 0.0;
        final fallbackHeight = dense ? 320.0 : 220.0;
        final computedChartHeight = expandToFit && constraints.maxHeight.isFinite
            ? math.max(140.0, constraints.maxHeight - headerHeight)
            : fallbackHeight;

        final content = Padding(
          padding: EdgeInsets.all(showFrame ? 16 : 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (showHeader)
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        title,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                    ),
                    if (onClear != null)
                      IconButton(
                        onPressed: onClear,
                        tooltip: '清空曲线',
                        visualDensity: VisualDensity.compact,
                        icon: const Icon(Icons.delete_outline, color: Colors.red),
                      ),
                    if (showExpandButton && onOpenFullscreen != null)
                      IconButton(
                        onPressed: onOpenFullscreen,
                        tooltip: '放大图表',
                        visualDensity: VisualDensity.compact,
                        icon: SvgPicture.string(
                          _expandSvg,
                          width: 20,
                          height: 20,
                          colorFilter: ColorFilter.mode(
                            Theme.of(context).colorScheme.onSurface,
                            BlendMode.srcIn,
                          ),
                        ),
                      ),
                  ],
                ),
              if (showHeader) const SizedBox(height: 12),
              SizedBox(
                height: computedChartHeight,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.surfaceVariant.withOpacity(showFrame ? 0.28 : 0.18),
                    borderRadius: BorderRadius.circular(showFrame ? 16 : 10),
                  ),
                  child: CustomPaint(
                    painter: _MeasurementChartPainter(
                      context: context,
                      points: points,
                      axisRange: axisRange,
                      yAxisLabel: yAxisLabel,
                      dense: dense,
                      centerZero: centerZero,
                      yStep: yStep,
                      yValueFormatter: yValueFormatter,
                    ),
                  ),
                ),
              ),
            ],
          ),
        );

        if (!showFrame) {
          return content;
        }

        return Card(
          clipBehavior: Clip.antiAlias,
          child: onOpenFullscreen == null
              ? content
              : InkWell(
                  onTap: onOpenFullscreen,
                  child: content,
                ),
        );
      },
    );
  }
}

const _expandSvg = '''
<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
  <path d="M8 3H3v5M16 3h5v5M21 16v5h-5M3 16v5h5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>
</svg>
''';

class _MeasurementChartPainter extends CustomPainter {
  final BuildContext context;
  final List<MeasurementSamplePoint> points;
  final MeasurementAxisRange axisRange;
  final String yAxisLabel;
  final bool dense;
  final bool centerZero;
  final double? yStep;
  final String Function(double value)? yValueFormatter;

  const _MeasurementChartPainter({
    required this.context,
    required this.points,
    required this.axisRange,
    required this.yAxisLabel,
    required this.dense,
    required this.centerZero,
    required this.yStep,
    required this.yValueFormatter,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final leftInset = dense ? 58.0 : 42.0;
    final rightInset = dense ? 18.0 : 12.0;
    final topInset = dense ? 28.0 : 22.0;
    final bottomInset = dense ? 58.0 : 46.0;

    final chartRect = Rect.fromLTWH(
      leftInset,
      topInset,
      math.max(1, size.width - leftInset - rightInset),
      math.max(1, size.height - topInset - bottomInset),
    );
    final theme = Theme.of(context);
    final axisPaint = Paint()
      ..color = theme.colorScheme.outlineVariant
      ..strokeWidth = 1.2;
    final gridPaint = Paint()
      ..color = theme.colorScheme.outlineVariant.withOpacity(0.35)
      ..strokeWidth = 1;
    final linePaint = Paint()
      ..color = theme.colorScheme.primary
      ..strokeWidth = 2.2
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;

    canvas.drawLine(chartRect.bottomLeft, chartRect.bottomRight, axisPaint);
    canvas.drawLine(chartRect.bottomLeft, chartRect.topLeft, axisPaint);

    final labelStyle = theme.textTheme.bodySmall?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
          fontSize: dense ? 12 : 10,
        ) ??
        TextStyle(color: theme.colorScheme.onSurfaceVariant, fontSize: dense ? 12 : 10);

    void drawText(String text, Offset offset, {TextAlign align = TextAlign.left}) {
      final painter = TextPainter(
        text: TextSpan(text: text, style: labelStyle),
        textAlign: align,
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: dense ? 84 : 48);
      var dx = offset.dx;
      if (align == TextAlign.center) dx -= painter.width / 2;
      if (align == TextAlign.right) dx -= painter.width;
      painter.paint(canvas, Offset(dx, offset.dy));
    }

    double snapStep(double value, double step) {
      return (value / step).round() * step;
    }

    final displayRange = centerZero
        ? () {
            final rawMaxAbs = math.max(axisRange.max.abs(), axisRange.min.abs());
            final step = yStep ?? 1.0;
            final snapped = snapStep(rawMaxAbs, step);
            final bounded = snapped < step * 2 ? step * 2 : snapped;
            return MeasurementAxisRange(min: -bounded, max: bounded);
          }()
        : axisRange;

    const yTickCount = 5;
    for (var index = 0; index < yTickCount; index++) {
      final ratio = yTickCount == 1 ? 0.0 : index / (yTickCount - 1);
      final y = chartRect.bottom - chartRect.height * ratio;
      canvas.drawLine(Offset(chartRect.left, y), Offset(chartRect.right, y), gridPaint);
      final rawValue = displayRange.min + displayRange.span * ratio;
      final value = yStep == null ? rawValue : snapStep(rawValue, yStep!);
      drawText(yValueFormatter?.call(value) ?? value.toStringAsFixed(1), Offset(chartRect.left - 10, y - 8), align: TextAlign.right);
    }

    drawText(yAxisLabel, Offset(chartRect.left, 4), align: TextAlign.center);
    drawText('时间', Offset(chartRect.right, chartRect.bottom + 26), align: TextAlign.right);

    if (points.isEmpty) {
      drawText('暂无数据', Offset(size.width / 2, size.height / 2 - 8), align: TextAlign.center);
      return;
    }

    const minTime = 0.0;
    final maxTime = points.last.timeSeconds;
    final timeSpan = math.max(1.0, maxTime - minTime);

    const xTickCount = 5;
    for (var index = 0; index < xTickCount; index++) {
      final ratio = xTickCount == 1 ? 0.0 : index / (xTickCount - 1);
      final x = chartRect.left + chartRect.width * ratio;
      final tickTime = minTime + timeSpan * ratio;
      drawText('${tickTime.toStringAsFixed(0)}s', Offset(x, chartRect.bottom + 8), align: TextAlign.center);
    }

    final path = Path();
    for (var i = 0; i < points.length; i++) {
      final point = points[i];
      final x = chartRect.left + ((point.timeSeconds - minTime) / timeSpan) * chartRect.width;
      final normalizedY = displayRange.span <= 0 ? 0.5 : ((point.value - displayRange.min) / displayRange.span).clamp(0.0, 1.0);
      final y = chartRect.bottom - normalizedY * chartRect.height;
      if (i == 0) {
        path.moveTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    canvas.drawPath(path, linePaint);
  }

  @override
  bool shouldRepaint(covariant _MeasurementChartPainter oldDelegate) {
    return oldDelegate.points != points ||
        oldDelegate.axisRange.min != axisRange.min ||
        oldDelegate.axisRange.max != axisRange.max ||
        oldDelegate.yAxisLabel != yAxisLabel ||
        oldDelegate.dense != dense ||
        oldDelegate.centerZero != centerZero ||
        oldDelegate.yStep != yStep;
  }
}
