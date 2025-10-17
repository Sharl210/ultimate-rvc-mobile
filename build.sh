#!/bin/bash

# Ultimate RVC Mobile - Build Script
# Cross-platform build automation for Android and iOS

set -e

echo "🎵 Ultimate RVC Mobile - Build Script"
echo "======================================"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if running on macOS or Linux
if [[ "$OSTYPE" == "darwin"* ]]; then
    PLATFORM="macos"
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    PLATFORM="linux"
else
    print_error "Unsupported platform: $OSTYPE"
    exit 1
fi

print_status "Detected platform: $PLATFORM"

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Check dependencies
print_status "Checking dependencies..."

# Check Flutter
if ! command_exists flutter; then
    print_error "Flutter not found. Please install Flutter 3.19.0"
    print_status "Visit: https://flutter.dev/docs/get-started/install"
    exit 1
fi

# Check Flutter version
FLUTTER_VERSION=$(flutter --version | grep -o 'Flutter [0-9]\+\.[0-9]\+\.[0-9]\+' | head -1)
print_status "Flutter version: $FLUTTER_VERSION"

# Check Java (for Android)
if ! command_exists java; then
    print_warning "Java not found. Android build may fail."
    print_status "Install OpenJDK 11 or higher"
fi

# Check Xcode (for iOS on macOS)
if [[ "$PLATFORM" == "macos" ]]; then
    if ! command_exists xcodebuild; then
        print_warning "Xcode not found. iOS build will be skipped."
    fi
fi

# Clean previous builds
print_status "Cleaning previous builds..."
flutter clean
flutter pub get

# Build Android APK
print_status "Building Android APK..."
flutter build apk --release --target-platform android-arm64

if [[ $? -eq 0 ]]; then
    print_success "Android APK built successfully"
    APK_PATH="build/app/outputs/flutter-apk/app-release.apk"
    if [[ -f "$APK_PATH" ]]; then
        print_success "APK location: $APK_PATH"
        
        # Calculate SHA256 of APK
        if command_exists sha256sum; then
            APK_SHA256=$(sha256sum "$APK_PATH" | cut -d' ' -f1)
            print_success "APK SHA256: $APK_SHA256"
        elif command_exists shasum; then
            APK_SHA256=$(shasum -a 256 "$APK_PATH" | cut -d' ' -f1)
            print_success "APK SHA256: $APK_SHA256"
        fi
    fi
else
    print_error "Android APK build failed"
fi

# Build iOS (only on macOS)
if [[ "$PLATFORM" == "macos" ]] && command_exists xcodebuild; then
    print_status "Building iOS IPA..."
    
    # Check if iOS dependencies are installed
    if ! command_exists pod; then
        print_status "Installing CocoaPods..."
        sudo gem install cocoapods
    fi
    
    # Build iOS
    flutter build ios --release --no-codesign
    
    if [[ $? -eq 0 ]]; then
        print_success "iOS build completed"
        
        # Create IPA (simplified - in real scenario would need proper signing)
        IPA_PATH="build/ios/iphoneos/Runner.app"
        if [[ -d "$IPA_PATH" ]]; then
            print_success "iOS app built at: $IPA_PATH"
            
            # Note: Real IPA creation requires proper provisioning profiles
            print_warning "To create IPA for App Store, use Xcode with proper signing certificates"
        fi
    else
        print_error "iOS build failed"
    fi
else
    print_warning "iOS build skipped (requires macOS with Xcode)"
fi

# Build summary
print_status "Build Summary"
echo "=================="
if [[ -f "build/app/outputs/flutter-apk/app-release.apk" ]]; then
    print_success "✓ Android APK: build/app/outputs/flutter-apk/app-release.apk"
fi

if [[ "$PLATFORM" == "macos" ]] && [[ -d "build/ios/iphoneos/Runner.app" ]]; then
    print_success "✓ iOS App: build/ios/iphoneos/Runner.app"
fi

print_status "Next steps:"
print_status "1. Install APK on Android device for testing"
print_status "2. For iOS, use Xcode to create signed IPA for App Store"
print_status "3. Test voice conversion functionality"
print_status "4. Submit to app stores when ready"

print_success "Build script completed!"