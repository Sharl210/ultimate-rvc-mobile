import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

import download_weights


def test_model_manifest_uses_real_public_rvc_urls_and_hashes():
    assert download_weights.MODELS_MANIFEST['hubert_base.pt']['url'] == (
        'https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/main/hubert_base.pt'
    )
    assert download_weights.MODELS_MANIFEST['hubert_base.pt']['sha256'] == (
        'f54b40fd2802423a5643779c4861af1e9ee9c1564dc9d32f54f20b5ffba7db96'
    )
    assert download_weights.MODELS_MANIFEST['rmvpe.pt']['sha256'] == (
        '6d62215f4306e3ca278246188607209f09af3dc77ed4232efdd069798c4ec193'
    )
    assert download_weights.MODELS_MANIFEST['pretraineds/f0D40k.pth']['url'] == (
        'https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/main/pretrained_v2/f0D40k.pth'
    )


def test_download_models_does_not_call_missing_progress_callback(monkeypatch, tmp_path):
    monkeypatch.setattr(download_weights, 'MODELS_MANIFEST', {
        'model.bin': {
            'url': 'https://example.invalid/model.bin',
            'sha256': '0' * 64,
            'size': 1,
        }
    })

    def fake_download_file(url, save_path, expected_size=0, progress_callback=None):
        if progress_callback is not None:
            progress_callback(50)
        with open(save_path, 'wb') as output:
            output.write(b'x')
        return True

    monkeypatch.setattr(download_weights, 'download_file', fake_download_file)
    monkeypatch.setattr(download_weights, 'verify_checksum', lambda path, expected: True)

    assert download_weights.download_models(str(tmp_path), progress_callback=None) == {'model.bin': True}
