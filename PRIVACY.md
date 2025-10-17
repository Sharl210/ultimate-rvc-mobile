# Privacy Policy

**Effective Date:** October 18, 2025

## Overview

Ultimate RVC Mobile ("we," "our," or "us") is committed to protecting your privacy. This Privacy Policy explains how we handle your data when you use our mobile application for AI-powered voice conversion.

## Key Privacy Principles

### 🔒 On-Device Processing
- **All AI inference runs locally on your device**
- No audio files are uploaded to external servers
- No voice data leaves your device
- All processing happens in your device's secure environment

### 📱 Data Collection
**We do NOT collect:**
- Your audio files
- Your voice recordings
- Generated audio content
- Personal information
- Usage analytics
- Device identifiers

**We do NOT:**
- Train AI models on your data
- Store your content on our servers
- Share your data with third parties
- Track your usage patterns

### 🎵 File Access
The app requires file access permissions to:
- Read audio files from your device storage
- Save generated audio to your device
- Access voice model files

These files remain on your device and are never transmitted externally.

## Technical Details

### Local Processing
- Voice conversion uses PyTorch models running locally
- Hubert and RMVPE models are downloaded once and stored locally
- All audio processing occurs in the app's sandboxed environment
- Generated files are saved to your device's local storage

### Model Downloads
- AI models are downloaded from Hugging Face and other public repositories
- Downloads happen only once during initial setup
- Model files are stored in app-private storage
- No user data is transmitted during model downloads

### Security
- App uses platform-specific security measures (iOS App Sandbox, Android permissions)
- No network communication during voice processing
- All temporary files are cleaned up after processing

## Compliance

### App Store Compliance
- **iOS:** Complies with App Store Review Guidelines
- **Android:** Complies with Google Play Policies
- No dynamic code loading
- No GPL libraries in final build

### Legal Compliance
- No data collection means GDPR compliance by design
- No COPPA concerns as no children's data is collected
- CCPA compliant as no personal information is processed

## Changes to Privacy Policy

We may update this Privacy Policy occasionally. Any changes will be reflected in the app's documentation and repository.

## Contact

For privacy questions or concerns, please open an issue in our GitHub repository.

---

**Last Updated:** October 18, 2025