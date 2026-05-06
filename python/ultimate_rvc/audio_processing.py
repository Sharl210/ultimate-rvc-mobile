"""
Audio Processing Utilities
"""

import librosa
import numpy as np
import soundfile as sf
from pathlib import Path
from typing import Tuple, Optional

class AudioProcessor:
    """Audio processing utilities for RVC"""
    
    @staticmethod
    def load_audio(path: str, sr: int = 16000) -> Tuple[np.ndarray, int]:
        """Load audio file"""
        audio, sample_rate = librosa.load(path, sr=sr)
        return audio, sample_rate
    
    @staticmethod
    def save_audio(path: str, audio: np.ndarray, sr: int):
        """Save audio file"""
        sf.write(path, audio, sr)
    
    @staticmethod
    def normalize_audio(audio: np.ndarray) -> np.ndarray:
        """Normalize audio to [-1, 1]"""
        return librosa.util.normalize(audio)
    
    @staticmethod
    def trim_silence(audio: np.ndarray, sr: int) -> np.ndarray:
        """Trim silence from audio"""
        return librosa.effects.trim(audio)[0]
    
    @staticmethod
    def change_pitch(audio: np.ndarray, sr: int, n_steps: float) -> np.ndarray:
        """Change pitch by n semitones"""
        return librosa.effects.pitch_shift(audio, sr=sr, n_steps=n_steps)
    
    @staticmethod
    def change_speed(audio: np.ndarray, speed: float) -> np.ndarray:
        """Change playback speed"""
        return librosa.effects.time_stretch(audio, rate=speed)
    
    @staticmethod
    def apply_fade(audio: np.ndarray, fade_in: float = 0.1, fade_out: float = 0.1) -> np.ndarray:
        """Apply fade in/out"""
        fade_in_samples = int(fade_in * len(audio))
        fade_out_samples = int(fade_out * len(audio))
        
        # Fade in
        if fade_in_samples > 0:
            fade_in_curve = np.linspace(0, 1, fade_in_samples)
            audio[:fade_in_samples] *= fade_in_curve
        
        # Fade out
        if fade_out_samples > 0:
            fade_out_curve = np.linspace(1, 0, fade_out_samples)
            audio[-fade_out_samples:] *= fade_out_curve
        
        return audio