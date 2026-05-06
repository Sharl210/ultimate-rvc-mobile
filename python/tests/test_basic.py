import os
import sys
import wave
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from download_weights import get_models_status
from main import infer_rvc, get_progress

def _write_wav(path: Path):
    sample_rate = 16000
    duration_seconds = 1
    frames = bytearray()
    for index in range(sample_rate * duration_seconds):
        sample = int(12000 * ((index % 64) / 32 - 1))
        frames.extend(sample.to_bytes(2, byteorder='little', signed=True))

    with wave.open(str(path), 'wb') as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(bytes(frames))


def test_model_status_values_are_plain_booleans(tmp_path):
    status = get_models_status(str(tmp_path))
    assert status
    assert all(type(value) is bool for value in status.values())


def test_infer_rvc_rejects_placeholder_generation_until_real_engine_exists(tmp_path):
    song_path = tmp_path / 'input.wav'
    model_path = tmp_path / 'voice.pth'
    _write_wav(song_path)
    model_path.write_bytes(b'model placeholder')

    try:
        infer_rvc(str(song_path), str(model_path))
    except RuntimeError as error:
        assert 'ONNX Runtime Mobile inference engine is not implemented' in str(error)
    else:
        raise AssertionError('placeholder fallback must not report successful RVC generation')

def test_progress_function():
    progress = get_progress()
    assert isinstance(progress, dict)
    assert 'percent' in progress
    assert 'current_step' in progress
    assert 'eta' in progress

if __name__ == "__main__":
    test_progress_function()
    print("All basic tests passed!")
