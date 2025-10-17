# Ultimate RVC Mobile 🎵

Cross-platform mobile app for AI-powered voice conversion using Retrieval-based Voice Conversion (RVC) technology. Convert songs and audio to different voices directly on your device!

## Features ✨

- **🎤 Voice Conversion**: Transform any song or audio to sound like a different voice
- **📱 Cross-Platform**: Native apps for both Android and iOS
- **🔒 Privacy-First**: All processing happens locally on your device
- **⚡ High Quality**: Preserves the original RVC inference pipeline
- **🎛️ Customizable**: Adjust pitch, voice strength, and other parameters
- **📤 Share Results**: Export and share your generated audio

## Quick Start 🚀

### Prerequisites

- **Flutter 3.19.0** or higher
- **Python 3.11** (for local development)
- **Android Studio** (for Android builds)
- **Xcode** (for iOS builds, macOS only)

### Installation

```bash
# Clone the repository
git clone https://github.com/JackismyShephard/ultimate-rvc-mobile.git
cd ultimate-rvc-mobile

# Run the build script
./build.sh
```

The build script will:
- Install dependencies
- Build Android APK
- Build iOS app (macOS only)
- Generate release artifacts

### Manual Build

```bash
# Install Flutter dependencies
flutter pub get

# Build Android APK
flutter build apk --release

# Build iOS app (macOS only)
flutter build ios --release
```

## Technology Stack 🛠️

### Frontend
- **Flutter 3.19**: Cross-platform UI framework
- **Dart**: Programming language
- **Material Design 3**: Modern UI components

### Backend (Local Processing)
- **Python 3.11**: AI processing engine
- **PyTorch 2.2**: Deep learning framework
- **Hubert**: Speech representation learning
- **RMVPE**: Robust F0 estimation
- **Librosa**: Audio processing library

### Platform Integration
- **Android**: Chaquopy for Python integration
- **iOS**: PythonKit + Kivy-ios for Python support

## Architecture 🏗️

```
ultimate_rvc_mobile/
├── android/           # Android-specific code (Chaquopy)
├── ios/              # iOS-specific code (PythonKit)
├── lib/              # Flutter UI and business logic
├── python/           # Python RVC engine
│   ├── ultimate_rvc/ # Core RVC implementation
│   ├── main.py       # Python entry point
│   └── download_weights.py  # Model management
├── models/           # AI model placeholders
└── build.sh          # Build automation script
```

## Usage 📱

1. **Select Audio**: Choose a song or audio file from your device
2. **Pick Voice Model**: Select a trained voice model (.pth or .index)
3. **Configure Settings**: Adjust pitch, voice strength, and other parameters
4. **Generate**: Convert the audio to the selected voice
5. **Share**: Listen to the result and share with others

### Voice Parameters
- **Pitch Change**: Adjust voice pitch (-12 to +12 semitones)
- **Voice Strength**: Control how much of the target voice to apply (0-100%)
- **Filter Radius**: Post-processing filter strength
- **RMS Mix Rate**: Volume mixing between original and converted audio
- **Protect Rate**: Preserve consonants and speech clarity

## Voice Models 🎭

The app supports standard RVC voice models:
- `.pth` files: PyTorch model weights
- `.index` files: Feature index files

### Getting Models
- Download from RVC community repositories
- Train your own using the Ultimate RVC desktop version
- Share models with the community

## Privacy & Security 🔐

- **No Data Collection**: We don't collect any personal data
- **Local Processing**: All AI processing happens on your device
- **No Cloud Uploads**: Audio files never leave your device
- **Secure Storage**: Models and generated files stay in app storage

## Building from Source 🔧

### Android Build
```bash
# Install dependencies
flutter pub get

# Build APK
flutter build apk --release --target-platform android-arm64

# Output: build/app/outputs/flutter-apk/app-release.apk
```

### iOS Build (macOS only)
```bash
# Install CocoaPods dependencies
cd ios && pod install && cd ..

# Build iOS app
flutter build ios --release

# Open in Xcode for final configuration
open ios/Runner.xcworkspace
```

## Development 🚧

### Project Structure
- `lib/main.dart`: App entry point
- `lib/screens/`: UI screens
- `lib/services/`: Platform bridges
- `python/main.py`: Python-Flutter interface
- `python/ultimate_rvc/`: Core RVC implementation

### Adding Features
1. Update Flutter UI in `lib/`
2. Add Python functionality in `python/`
3. Update platform bridges for Android/iOS
4. Test on both platforms

## Troubleshooting 🔧

### Common Issues

**Build Fails**: 
- Ensure Flutter 3.19.0 is installed
- Check Python 3.11 availability
- Verify Android/iOS development tools

**Model Download Fails**:
- Check internet connection
- Ensure sufficient storage space (2GB+)
- Verify app permissions

**Audio Processing Fails**:
- Check audio file format (MP3, WAV, M4A supported)
- Ensure voice model is valid
- Verify device has sufficient RAM (4GB+ recommended)

### Getting Help
- Check [GitHub Issues](https://github.com/JackismyShephard/ultimate-rvc-mobile/issues)
- Join our [Discord Community](https://discord.gg/ultimatervc)
- Read the [Ultimate RVC Documentation](https://github.com/JackismyShephard/ultimate-rvc)

## Contributing 🤝

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for details.

### Development Setup
1. Fork the repository
2. Set up development environment
3. Make your changes
4. Test on both platforms
5. Submit a pull request

## License 📄

This project is licensed under the MIT License - see [LICENSE](LICENSE) file for details.

### Dependencies
- Uses Ultimate RVC (MIT License)
- Incorporates various open-source libraries
- Voice model usage subject to original owner rights

## Acknowledgments 🙏

- **Ultimate RVC Team**: Original RVC implementation
- **Flutter Community**: Cross-platform framework
- **PyTorch Team**: Deep learning framework
- **Open Source Contributors**: Various libraries and tools

## Disclaimer ⚠️

This software is for educational and research purposes. Users are responsible for:
- Complying with local laws and regulations
- Obtaining appropriate permissions for voice usage
- Respecting voice owner rights and copyrights

---

Made with ❤️ by the Ultimate RVC Mobile Team

**GitHub**: https://github.com/JackismyShephard/ultimate-rvc-mobile  
**Version**: 1.0.0  
**Last Updated**: October 18, 2025