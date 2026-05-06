# 变声器模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有实时推理下面新增“变声器模式”，只提供录制模式，并通过悬浮窗完成开始录音、停止录音、处理、中断、播放、暂停和试听操作。

**Architecture:** Flutter 只负责入口、录制模式参数配置、说明弹窗和模式状态展示；Android 原生负责前台服务、悬浮窗、录音、播放、震动反馈和调用现有 RVC 推理。实时劫持其他 App 麦克风输入不做；突破其他 App 的录音或扬声器屏蔽也不做。当前版本集中交付稳定的普通录制模式。

**Tech Stack:** Flutter/Dart、Android Kotlin、MethodChannel、前台服务、SYSTEM_ALERT_WINDOW 悬浮窗、AudioRecord、AudioTrack、Vibrator、ONNX Runtime Mobile。

---

## 事实与边界

1. 普通 Android 应用不能可靠劫持其他 App 的麦克风输入再注入回系统输入链路。实时变声模式本轮不做。
2. 录制模式不需要劫持系统输入。它通过悬浮窗录音，停止后用现有 RVC 离线推理处理，再播放转换后的声音。
3. 某些 App 录制麦克风时会压低或屏蔽后台扬声器播放，这是 Android 音频焦点和路由策略导致。本轮不尝试突破，也不做 ROOT 兜底；只保证 App 内悬浮窗录音、处理和播放闭环。
4. 当前电音感调查结果指向 Android 推理链路：PCM 解码假设、线性重采样、RMVPE 后处理、无上下文分块、伪 formant 和全局 RMS 增益。变声器功能必须先复用已修正的音质链路，不能再复制一套新推理。

## 文件结构

- Modify: `lib/main.dart`，在侧边栏“实时推理”下面增加“变声器模式”入口。
- Create: `lib/screens/voice_changer_screen.dart`，录制模式参数配置、帮助说明按钮。
- Create: `lib/services/voice_changer_bridge.dart`，封装 Flutter 到 Android 的变声器控制接口。
- Modify: `lib/services/rvc_bridge.dart`，复用实时缓冲参数和推理参数，不增加重复通道。
- Modify: `android/app/src/main/AndroidManifest.xml`，声明悬浮窗、前台服务、录音、震动权限和服务组件。
- Create: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerPlugin.kt`，MethodChannel 分发、权限与模式启动。
- Create: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerOverlayService.kt`，悬浮窗、圆形按钮、状态机、震动反馈。
- Create: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerRecorder.kt`，录制模式的录音、临时 WAV 写入、推理、播放。
- Create: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerState.kt`，状态枚举和状态转换约束。
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/RVCPlugin.kt`，只复用已有模型目录和推理入口，不把悬浮窗逻辑塞进现有插件。
- Test: `python/tests/test_voice_changer_static.py`，覆盖入口、参数记忆、权限声明、悬浮窗状态机和录制处理闭环。
- Test: `python/tests/test_navigation_and_io_static.py`，补导航入口和参数透传静态断言。

## Task 1: 变声器入口和计划级静态测试

**Files:**
- Modify: `lib/main.dart`
- Create: `lib/screens/voice_changer_screen.dart`
- Test: `python/tests/test_voice_changer_static.py`

- [ ] **Step 1: Write the failing test**

```python
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / 'lib/main.dart'
VOICE_CHANGER = ROOT / 'lib/screens/voice_changer_screen.dart'


def test_voice_changer_entry_is_below_realtime_inference():
    source = MAIN.read_text(encoding='utf-8')
    realtime_index = source.index("NavigationDestination(")
    assert "import 'screens/voice_changer_screen.dart';" in source
    assert "label: '实时推理'" in source
    assert "label: '变声器模式'" in source
    assert source.index("label: '实时推理'") < source.index("label: '变声器模式'")
    assert 'VoiceChangerScreen(' in source


def test_voice_changer_screen_has_recording_mode_and_help_button():
    source = VOICE_CHANGER.read_text(encoding='utf-8')
    assert 'class VoiceChangerScreen extends StatefulWidget' in source
    assert "Text('录制模式')" in source
    assert "Text('实时变声')" not in source
    assert 'Icons.help_outline' in source
    assert 'showDialog<void>' in source
    assert '悬浮窗用于录音、处理、播放和试听' in source
    assert '录制模式' in source
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m pytest python/tests/test_voice_changer_static.py -q`

Expected: FAIL because `voice_changer_screen.dart` and the navigation entry do not exist yet.

- [ ] **Step 3: Implement the minimal UI entry**

Create `lib/screens/voice_changer_screen.dart` with mode cards, a help icon in the app bar, and no explanatory clutter outside the help dialog. Modify `lib/main.dart` to import and add the new destination immediately after “实时推理”.

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m pytest python/tests/test_voice_changer_static.py -q`

Expected: PASS.

## Task 2: 录制模式参数模板与记忆

**Files:**
- Modify: `lib/screens/voice_changer_screen.dart`
- Create: `lib/services/voice_changer_bridge.dart`
- Test: `python/tests/test_voice_changer_static.py`

- [ ] **Step 1: Write the failing test**

```python
def test_voice_changer_recording_mode_has_persisted_parameters():
    source = VOICE_CHANGER.read_text(encoding='utf-8')
    assert "prefs.getDouble('voiceChangerRecordingNoiseGateDb') ?? 35.0" in source
    assert "prefs.getDouble('voiceChangerRecordingOverlayDiameter') ?? 72.0" in source
    assert 'voiceChangerRecordingModelPath' in source
    assert 'VoiceChangerMode.recording' in source
    assert 'VoiceChangerMode.realtime' not in source
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m pytest python/tests/test_voice_changer_static.py::test_voice_changer_recording_mode_has_persisted_parameters -q`

Expected: FAIL because the recording preference keys are missing.

- [ ] **Step 3: Implement persisted templates**

Add a `VoiceChangerMode.recording` enum value and one recording state object in `voice_changer_screen.dart`. The template stores model path, optional index path, pitch, formant, index rate, RMS mix, protect, noise gate default 35 dB, overlay diameter, and performance plan. Do not reuse realtime inference preference keys.

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m pytest python/tests/test_voice_changer_static.py::test_voice_changer_recording_mode_has_persisted_parameters -q`

Expected: PASS.

## Task 3: Android 权限、Bridge 和悬浮窗服务壳

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`
- Create: `lib/services/voice_changer_bridge.dart`
- Create: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerPlugin.kt`
- Create: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerOverlayService.kt`
- Test: `python/tests/test_voice_changer_static.py`

- [ ] **Step 1: Write the failing test**

```python
MANIFEST = ROOT / 'android/app/src/main/AndroidManifest.xml'
BRIDGE = ROOT / 'lib/services/voice_changer_bridge.dart'
PLUGIN = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerPlugin.kt'
SERVICE = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerOverlayService.kt'


def test_voice_changer_declares_overlay_foreground_service_and_vibration():
    manifest = MANIFEST.read_text(encoding='utf-8')
    bridge = BRIDGE.read_text(encoding='utf-8')
    plugin = PLUGIN.read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')

    assert 'android.permission.SYSTEM_ALERT_WINDOW' in manifest
    assert 'android.permission.FOREGROUND_SERVICE' in manifest
    assert 'android.permission.VIBRATE' in manifest
    assert '.VoiceChangerOverlayService' in manifest
    assert "MethodChannel('ultimate_rvc_voice_changer')" in bridge
    assert 'startVoiceChangerOverlay' in bridge
    assert 'stopVoiceChangerOverlay' in bridge
    assert 'Settings.canDrawOverlays' in plugin
    assert 'WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY' in service
    assert 'GradientDrawable.OVAL' in service
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m pytest python/tests/test_voice_changer_static.py::test_voice_changer_declares_overlay_foreground_service_and_vibration -q`

Expected: FAIL because permission, bridge, plugin, and service do not exist.

- [ ] **Step 3: Implement permissions and service shell**

Register a MethodChannel in the main plugin registration path, add overlay permission check, and create a foreground overlay service with one circular button whose diameter comes from Flutter.

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m pytest python/tests/test_voice_changer_static.py::test_voice_changer_declares_overlay_foreground_service_and_vibration -q`

Expected: PASS.

## Task 4: 录制模式状态机

**Files:**
- Create: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerState.kt`
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerOverlayService.kt`
- Test: `python/tests/test_voice_changer_static.py`

- [ ] **Step 1: Write the failing test**

```python
STATE = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerState.kt'


def test_recording_mode_overlay_state_machine_colors_and_long_press():
    state = STATE.read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')
    assert 'enum class VoiceChangerOverlayState' in state
    assert 'IDLE' in state
    assert 'RECORDING' in state
    assert 'PROCESSING' in state
    assert 'READY' in state
    assert 'PLAYING' in state
    assert 'PAUSED' in state
    assert 'TRIAL_PLAYING' in state
    assert 'Color.GRAY' in service
    assert 'Color.GREEN' in service
    assert 'Color.YELLOW' in service
    assert 'Color.BLUE' in service
    assert 'Color.RED' in service
    assert 'setOnLongClickListener' in service
    assert 'vibrator.vibrate' in service
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m pytest python/tests/test_voice_changer_static.py::test_recording_mode_overlay_state_machine_colors_and_long_press -q`

Expected: FAIL because state machine is missing.

- [ ] **Step 3: Implement state transitions**

Implement these transitions: gray idle → tap green recording → tap yellow processing → processing long press aborts to gray → completed blue ready → tap red paused/playing state toggle as defined by implementation naming → finish normal playback returns gray → long press blue trial plays and returns blue → long press red seeks to end and returns gray for normal playback or blue for trial playback. Every successful long press vibrates.

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m pytest python/tests/test_voice_changer_static.py::test_recording_mode_overlay_state_machine_colors_and_long_press -q`

Expected: PASS.

## Task 5: 录制模式音频处理与播放

**Files:**
- Create: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerRecorder.kt`
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerOverlayService.kt`
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/RVCPlugin.kt`
- Test: `python/tests/test_voice_changer_static.py`

- [ ] **Step 1: Write the failing test**

```python
RECORDER = ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerRecorder.kt'


def test_recording_mode_records_processes_and_plays_via_shared_rvc_engine():
    recorder = RECORDER.read_text(encoding='utf-8')
    assert 'AudioRecord(' in recorder
    assert 'AudioTrack(' in recorder
    assert 'RvcInferenceEngine(modelsDir).infer(' in recorder
    assert 'noiseGateDb = 35.0' not in recorder
    assert 'CancellationToken()' in recorder
    assert 'cancelProcessing()' in recorder
    assert 'RvcInferenceRequest(' in recorder
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m pytest python/tests/test_voice_changer_static.py::test_recording_mode_records_processes_and_plays_via_shared_rvc_engine -q`

Expected: FAIL because recorder implementation is missing.

- [ ] **Step 3: Implement recorder**

Use `AudioRecord` to write a temporary WAV file under app files, call shared `RvcInferenceEngine`, then use `AudioTrack` for playback. Pass all mode parameters from Flutter; noise gate defaults to 35 only in Flutter template, not hardcoded in recorder.

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m pytest python/tests/test_voice_changer_static.py::test_recording_mode_records_processes_and_plays_via_shared_rvc_engine -q`

Expected: PASS.

## Task 6: 音质修复先导任务

**Files:**
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/RvcInferenceEngine.kt`
- Test: `python/tests/test_native_bridge_static.py`

- [ ] **Step 1: Write failing tests for the known artifact risks**

Add tests asserting that PCM encoding is inspected, `applyFormant` is not a fake waveform resampler, chunk output uses context or stable-region trimming, and RMS mixing is not one global gain.

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 -m pytest python/tests/test_native_bridge_static.py -q`

Expected: FAIL for the new artifact-risk tests.

- [ ] **Step 3: Implement minimal audio-quality fixes**

Start with the safest changes: validate decoded PCM encoding, disable or neutralize fake formant shifting when formant is 0, avoid hard clipping where possible, and keep `parallelChunkCount` configurable. Defer full RMVPE replacement until there is a direct reference implementation or test fixture.

- [ ] **Step 4: Run targeted and full tests**

Run: `python3 -m pytest python/tests -q`, then `flutter analyze`, then `flutter test test/main_screen_test.dart`, then `flutter build apk --release`.

Expected: all pass and release APK builds.

## Verification Checklist

- [ ] `python3 -m pytest python/tests -q` passes.
- [ ] `flutter analyze` passes.
- [ ] `flutter test test/main_screen_test.dart` passes.
- [ ] `flutter build apk --release` passes.
- [ ] Install with `/home/harl/android-sdk/platform-tools/adb install -r -d build/app/outputs/flutter-apk/app-release.apk` when a device is available.

## Recommended execution order

1. Finish current sample-rate, parallel chunk, and realtime delay buffer changes.
2. Implement Task 1 to Task 3 so the new mode exists and permissions are wired.
3. Implement Task 4 and Task 5 to make recording mode fully usable.
4. Implement Task 7 audio-quality fixes before exposing voice changer broadly.
5. Do not implement realtime microphone injection in this plan.
