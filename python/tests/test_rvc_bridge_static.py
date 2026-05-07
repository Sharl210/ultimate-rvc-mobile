from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RVC_BRIDGE = ROOT / 'lib/services/rvc_bridge.dart'


def test_infer_subscribes_to_progress_before_starting_native_inference():
    source = RVC_BRIDGE.read_text(encoding='utf-8')
    infer_start = source.index('Future<String> infer({')
    infer_source = source[infer_start:source.index('  /// Get app version', infer_start)]

    subscribe_index = infer_source.index('receiveBroadcastStream()')
    invoke_index = infer_source.index("invokeMethod('infer'")

    assert subscribe_index < invoke_index


def test_infer_returns_when_native_method_returns_without_waiting_for_100_percent_event():
    source = RVC_BRIDGE.read_text(encoding='utf-8')
    infer_start = source.index('Future<String> infer({')
    infer_source = source[infer_start:source.index('  /// Get app version', infer_start)]

    assert 'final result = await _channel.invokeMethod(' in infer_source
    assert 'return result as String;' in infer_source
    assert 'progressDone' not in infer_source
    assert 'await progressDone.future' not in infer_source


def test_infer_exposes_optional_index_path_to_native_layer():
    source = RVC_BRIDGE.read_text(encoding='utf-8')

    assert 'String? indexPath,' in source
    assert "'indexPath': indexPath," in source


def test_infer_exposes_output_sample_rate_to_native_layer():
    source = RVC_BRIDGE.read_text(encoding='utf-8')

    assert 'int sampleRate = 48000,' in source
    assert "'sampleRate': sampleRate," in source


def test_infer_does_not_expose_runtime_device_to_native_layer():
    source = RVC_BRIDGE.read_text(encoding='utf-8')

    assert 'runtimeDevice' not in source
