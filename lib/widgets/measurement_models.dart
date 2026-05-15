import 'dart:math' as math;

enum MeasurementChartMode {
  decibel,
  relativeSemitone,
  frequencyHz,
  pitchNoteName,
}

class MeasurementSamplePoint {
  final double timeSeconds;
  final double value;

  const MeasurementSamplePoint({
    required this.timeSeconds,
    required this.value,
  });
}

class MeasurementAxisRange {
  final double min;
  final double max;

  const MeasurementAxisRange({
    required this.min,
    required this.max,
  });

  double get span => max - min;
}

List<MeasurementSamplePoint> trimMeasurementWindow(
  List<MeasurementSamplePoint> points, {
  required double windowSeconds,
}) {
  if (points.isEmpty) return const [];
  final maxTime = points.last.timeSeconds;
  final minTime = math.max(0.0, maxTime - windowSeconds);
  return points.where((point) => point.timeSeconds >= minTime).toList(growable: false);
}

MeasurementAxisRange buildAxisRange(
  List<MeasurementSamplePoint> points, {
  required MeasurementAxisRange? previous,
  required double defaultMin,
  required double defaultMax,
  double paddingRatio = 0.12,
  double minimumSpan = 1.0,
  double shrinkLerp = 0.18,
}) {
  if (points.isEmpty) {
    return previous ?? MeasurementAxisRange(min: defaultMin, max: defaultMax);
  }

  var minValue = points.first.value;
  var maxValue = points.first.value;
  for (final point in points.skip(1)) {
    if (point.value < minValue) minValue = point.value;
    if (point.value > maxValue) maxValue = point.value;
  }

  final span = math.max(minimumSpan, maxValue - minValue);
  final padding = math.max(minimumSpan * 0.12, span * paddingRatio);
  final targetMin = minValue - padding;
  final targetMax = maxValue + padding;

  if (previous == null) {
    return MeasurementAxisRange(
      min: math.min(defaultMin, targetMin),
      max: math.max(defaultMax, targetMax),
    );
  }

  final nextMin = targetMin < previous.min
      ? targetMin
      : previous.min + (targetMin - previous.min) * shrinkLerp;
  final nextMax = targetMax > previous.max
      ? targetMax
      : previous.max + (targetMax - previous.max) * shrinkLerp;

  if ((nextMax - nextMin) < minimumSpan) {
    final center = (nextMax + nextMin) / 2;
    return MeasurementAxisRange(
      min: center - minimumSpan / 2,
      max: center + minimumSpan / 2,
    );
  }

  return MeasurementAxisRange(min: nextMin, max: nextMax);
}
