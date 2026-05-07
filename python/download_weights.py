#!/usr/bin/env python3
"""
Model Weights Downloader
Handles lazy downloading of AI models with progress tracking
"""

import os
import sys
import json
import hashlib
import requests
from pathlib import Path
from typing import Dict, List, Optional
import logging

logger = logging.getLogger(__name__)

# Model URLs and checksums
MODELS_MANIFEST = {
    "hubert_base.pt": {
        "url": "https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/main/hubert_base.pt",
        "sha256": "f54b40fd2802423a5643779c4861af1e9ee9c1564dc9d32f54f20b5ffba7db96",
        "size": 189507909
    },
    "rmvpe.pt": {
        "url": "https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/main/rmvpe.pt",
        "sha256": "6d62215f4306e3ca278246188607209f09af3dc77ed4232efdd069798c4ec193",
        "size": 181068963
    },
    "pretraineds/f0D40k.pth": {
        "url": "https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/main/pretrained_v2/f0D40k.pth",
        "sha256": "6b6ab091e70801b28e3f41f335f2fc5f3f35c75b39ae2628d419644ec2b0fa09",
        "size": 142875703
    }
}

def calculate_sha256(file_path: str) -> str:
    """Calculate SHA256 checksum of file"""
    hash_sha256 = hashlib.sha256()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(4096), b""):
            hash_sha256.update(chunk)
    return hash_sha256.hexdigest()

def verify_checksum(file_path: str, expected_sha256: str) -> bool:
    """Verify file checksum"""
    if not os.path.exists(file_path):
        return False
    
    actual_sha256 = calculate_sha256(file_path)
    return actual_sha256 == expected_sha256

def download_file(url: str, save_path: str, expected_size: int = 0, 
                  progress_callback=None) -> bool:
    """Download file with progress tracking"""
    try:
        logger.info(f"Downloading {url} to {save_path}")
        
        # Create directory if it doesn't exist
        os.makedirs(os.path.dirname(save_path), exist_ok=True)
        
        # Start download
        response = requests.get(url, stream=True)
        response.raise_for_status()
        
        actual_size = int(response.headers.get('content-length', expected_size))
        downloaded = 0
        
        with open(save_path, 'wb') as f:
            for chunk in response.iter_content(chunk_size=8192):
                if chunk:
                    f.write(chunk)
                    downloaded += len(chunk)
                    
                    # Update progress
                    if progress_callback and actual_size > 0:
                        progress = (downloaded / actual_size) * 100
                        progress_callback(progress)
        
        logger.info(f"Download completed: {save_path}")
        return True
        
    except Exception as e:
        logger.error(f"Download failed: {e}")
        if os.path.exists(save_path):
            os.remove(save_path)
        return False

def download_models(models_dir: str, force_download: bool = False,
                   progress_callback=None) -> Dict[str, bool]:
    """
    Download all required models
    
    Args:
        models_dir: Directory to save models
        force_download: Force re-download even if files exist
        progress_callback: Progress callback function
        
    Returns:
        Dictionary of model paths and success status
    """
    results = {}
    total_models = len(MODELS_MANIFEST)
    
    for i, (model_name, model_info) in enumerate(MODELS_MANIFEST.items()):
        model_path = os.path.join(models_dir, model_name)
        
        # Check if model already exists and is valid
        if not force_download and os.path.exists(model_path):
            if verify_checksum(model_path, model_info["sha256"]):
                logger.info(f"Model already exists and is valid: {model_name}")
                results[model_name] = True
                continue
            else:
                logger.warning(f"Model exists but checksum invalid: {model_name}")
                os.remove(model_path)
        
        # Download model
        if progress_callback:
            overall_progress = (i / total_models) * 100
            progress_callback(overall_progress, f"Downloading {model_name}")
        
        file_progress_callback = None
        if progress_callback:
            file_progress_callback = lambda p: progress_callback(
                (i + p / 100) / total_models * 100,
                f"Downloading {model_name}",
            )

        success = download_file(
            model_info["url"],
            model_path,
            model_info["size"],
            file_progress_callback,
        )
        
        # Verify checksum
        if success:
            if verify_checksum(model_path, model_info["sha256"]):
                logger.info(f"Model downloaded and verified: {model_name}")
                results[model_name] = True
            else:
                logger.error(f"Model downloaded but checksum invalid: {model_name}")
                os.remove(model_path)
                results[model_name] = False
        else:
            results[model_name] = False
    
    if progress_callback:
        progress_callback(100, "All downloads completed")
    
    return results

def get_models_status(models_dir: str) -> Dict[str, bool]:
    """Check status of all models"""
    status = {}
    
    for model_name, model_info in MODELS_MANIFEST.items():
        model_path = os.path.join(models_dir, model_name)
        
        if os.path.exists(model_path):
            if verify_checksum(model_path, model_info["sha256"]):
                status[model_name] = True
            else:
                status[model_name] = False
        else:
            status[model_name] = False
    
    return status

def get_total_size() -> int:
    """Get total size of all models in bytes"""
    return sum(info["size"] for info in MODELS_MANIFEST.values())

def main():
    """Main function for CLI usage"""
    if len(sys.argv) < 2:
        print("Usage:")
        print("  python download_weights.py status <models_dir>")
        print("  python download_weights.py download <models_dir> [--force]")
        sys.exit(1)
    
    command = sys.argv[1]
    models_dir = sys.argv[2] if len(sys.argv) > 2 else "./models"
    
    if command == "status":
        status = get_models_status(models_dir)
        print(json.dumps(status, indent=2))
        
    elif command == "download":
        force = "--force" in sys.argv
        
        def progress_callback(percent, message):
            print(f"\r[{percent:6.2f}%] {message}", end="", flush=True)
        
        results = download_models(models_dir, force, progress_callback)
        print("\nDownload results:")
        for model, success in results.items():
            status = "✓" if success else "✗"
            print(f"  {status} {model}")
        
        all_success = all(results.values())
        sys.exit(0 if all_success else 1)
    
    else:
        print(f"Unknown command: {command}")
        sys.exit(1)

if __name__ == "__main__":
    main()
