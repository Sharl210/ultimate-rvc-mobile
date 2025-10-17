"""
Ultimate RVC Package
Cross-platform RVC inference implementation
"""

__version__ = "1.0.0"
__author__ = "Ultimate RVC Mobile Team"

from .rvc_inference import RVCInference
from .audio_processing import AudioProcessor
from .model_loader import ModelLoader

__all__ = ["RVCInference", "AudioProcessor", "ModelLoader"]