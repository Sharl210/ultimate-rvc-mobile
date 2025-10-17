"""
Basic tests for Ultimate RVC Mobile
"""

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from main import infer_rvc, download_models, get_progress
from ultimate_rvc.rvc_inference import RVCInference
from ultimate_rvc.audio_processing import AudioProcessor
from ultimate_rvc.model_loader import ModelLoader

def test_imports():
    """Test that all modules can be imported"""
    try:
        from ultimate_rvc import RVCInference, AudioProcessor, ModelLoader
        assert True
    except ImportError as e:
        assert False, f"Failed to import modules: {e}"

def test_rvc_inference_class():
    """Test RVCInference class initialization"""
    try:
        rvc = RVCInference("./models")
        assert rvc.model_dir == "./models"
        assert rvc.device is not None
    except Exception as e:
        assert False, f"Failed to initialize RVCInference: {e}"

def test_audio_processor():
    """Test AudioProcessor class methods"""
    try:
        processor = AudioProcessor()
        assert hasattr(processor, 'load_audio')
        assert hasattr(processor, 'save_audio')
        assert hasattr(processor, 'normalize_audio')
    except Exception as e:
        assert False, f"Failed to test AudioProcessor: {e}"

def test_model_loader():
    """Test ModelLoader class methods"""
    try:
        loader = ModelLoader()
        assert hasattr(loader, 'load_hubert_model')
        assert hasattr(loader, 'load_rmvpe_model')
        assert hasattr(loader, 'load_voice_model')
    except Exception as e:
        assert False, f"Failed to test ModelLoader: {e}"

def test_progress_function():
    """Test progress tracking function"""
    try:
        progress = get_progress()
        assert isinstance(progress, dict)
        assert 'percent' in progress
        assert 'current_step' in progress
        assert 'eta' in progress
    except Exception as e:
        assert False, f"Failed to test progress function: {e}"

def test_main_functions():
    """Test main module functions exist"""
    try:
        from main import infer_rvc, download_models, get_progress
        assert callable(infer_rvc)
        assert callable(download_models)
        assert callable(get_progress)
    except Exception as e:
        assert False, f"Failed to test main functions: {e}"

if __name__ == "__main__":
    test_imports()
    test_rvc_inference_class()
    test_audio_processor()
    test_model_loader()
    test_progress_function()
    test_main_functions()
    print("All basic tests passed!")