from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODEL_PICKER = ROOT / 'lib/screens/model_picker_screen.dart'
MAIN = ROOT / 'lib/main.dart'


def test_model_picker_uses_unfiltered_picker_for_rvc_model_files_on_android():
    source = MODEL_PICKER.read_text(encoding='utf-8')

    assert 'type: FileType.any' in source
    assert 'allowedExtensions' not in source


def test_model_picker_supports_required_onnx_model_and_optional_index_file():
    source = MODEL_PICKER.read_text(encoding='utf-8')

    assert ".onnx" in source
    assert ".mobile.index" in source
    assert '选择 mobile.index' in source
    assert '索引文件（可选）' in source


def test_model_picker_keeps_user_on_picker_after_model_selection():
    source = MODEL_PICKER.read_text(encoding='utf-8')
    pick_model_start = source.index('Future<void> _pickModel(BuildContext context) async')
    pick_model_source = source[pick_model_start:source.index('  Future<void> _pickIndex', pick_model_start)]

    assert 'Navigator.' not in pick_model_source
    assert 'onNext' not in pick_model_source
    assert '继续' in source
    assert 'onContinue' in source


def test_main_screen_model_selection_does_not_auto_advance_to_generate_screen():
    source = MAIN.read_text(encoding='utf-8')
    handler_start = source.index('void _onModelSelected(String path)')
    handler_source = source[handler_start:source.index('  void _continueToGenerate', handler_start)]

    assert '_currentIndex = 2' not in handler_source
    assert 'onContinue: _continueToGenerate' in source
