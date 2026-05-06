# Single-Instance File Import + Temp Task Workspaces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split app-private file handling into a shared import area for user-selected files and per-mode single-instance TEMP task workspaces, with resumable unfinished jobs and strict cleanup on switches/new runs.

**Architecture:** The app will use two distinct storage layers under the app-private files root. Layer 1 is a shared import area for user-selected source audio, models, and index files that are copied in from the file picker and tracked by reference count, so multiple modes can point to the same imported asset without accidental deletion. Layer 2 is `TEMP/` task workspaces that hold only execution-state files: manifests, chunk inputs, chunk outputs, current unsaved result, and voice changer working recordings. `TEMP/audio_inference/` and `TEMP/voice_changer/` are each single-instance workspaces inside their own mode. Manual save/export is treated as “另存为”: saved files leave the working lifecycle and are not stored in the app’s working data directories.

**Tech Stack:** Kotlin, Dart, Flutter platform channels, JSON manifest files, file-based reference counting, existing static pytest contracts.

---

### Task 1: Add an imported-file registry and per-mode TEMP workspace manager

**Files:**
- Create: `android/app/src/main/kotlin/com/ultimatervc/mobile/TempWorkspaceManager.kt`
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/RVCPlugin.kt`
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerPlugin.kt`
- Test: `python/tests/test_native_bridge_static.py`

- [ ] **Step 1: Write the failing test**

```python
def test_temp_workspace_manager_exposes_import_root_temp_roots_and_reference_counting():
    source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/TempWorkspaceManager.kt').read_text(encoding='utf-8')
    assert 'enum class TempWorkspaceMode' in source
    assert 'AUDIO_INFERENCE' in source
    assert 'VOICE_CHANGER' in source
    assert 'FILE_PICKER' in source or 'IMPORTS_DIRECTORY_NAME' in source
    assert 'TEMP' in source
    assert 'audio_inference' in source
    assert 'voice_changer' in source
    assert 'referenceCount' in source
    assert 'acquireReference' in source
    assert 'releaseReference' in source
    assert 'deleteIfUnused' in source
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest python/tests/test_native_bridge_static.py -k temp_workspace_manager -v`

Expected: FAIL because the manager file and API do not exist yet.

- [ ] **Step 3: Write minimal implementation**

Create a Kotlin manager with two responsibilities:

1. imported-file registry under an app-private import root, for example:

```text
files/FILE_PICKER/audio/
files/FILE_PICKER/models/
files/FILE_PICKER/indexes/
```

2. per-mode task workspaces under:

```text
files/TEMP/audio_inference/
files/TEMP/voice_changer/
```

Only the import root uses reference counting. The TEMP roots are single-instance execution areas and are cleaned per mode.

The manager must support:

```kotlin
enum class TempWorkspaceMode { AUDIO_INFERENCE, VOICE_CHANGER }

data class ManagedTempFile(
    val path: String,
    val referenceCount: Int,
    val lastUpdatedAtMs: Long,
)
```

The manager should expose:
- `acquireReference(mode, path)`
- `releaseReference(mode, path)`
- `deleteIfUnused(path)`
- `importPickedFile(kind, sourcePath)`
- `clearModeTempWorkspace(mode)`
- `resolveModeRoot(mode)`
- `resolveImportRoot(kind)`

The registry must ensure that when one mode releases an imported file, it is only deleted after all modes have released it.

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m pytest python/tests/test_native_bridge_static.py -k temp_workspace_manager -v`

Expected: PASS.

---

### Task 2: Make audio inference use imported assets plus `files/TEMP/audio_inference` task state

**Files:**
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/RvcInferenceEngine.kt`
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/ResumableInferenceJobStore.kt`
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/RVCPlugin.kt`
- Modify: `lib/services/rvc_bridge.dart`
- Modify: `lib/screens/generate_screen.dart`
- Modify: `lib/main.dart`
- Test: `python/tests/test_native_bridge_static.py`, `python/tests/test_generate_flow_static.py`

- [ ] **Step 1: Write the failing test**

```python
def test_audio_inference_uses_imported_assets_temp_root_and_manifest_must_match_to_continue():
    engine = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/RvcInferenceEngine.kt').read_text(encoding='utf-8')
    store = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/ResumableInferenceJobStore.kt').read_text(encoding='utf-8')
    bridge = (ROOT / 'lib/services/rvc_bridge.dart').read_text(encoding='utf-8')
    screen = (ROOT / 'lib/screens/generate_screen.dart').read_text(encoding='utf-8')

    assert 'files/TEMP/audio_inference' in engine
    assert 'FILE_PICKER' in engine or 'importPickedFile' in engine
    assert 'segmentCount' in store
    assert 'getResumableJobMetadata' in bridge
    assert 'clearResumableJobCache' in bridge
    assert "label: Text('继续未完成')" in screen
    assert '当前任务与历史不一致，无法继续' in screen
    assert '不做任何操作' in screen
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest python/tests/test_native_bridge_static.py python/tests/test_generate_flow_static.py -k audio_inference -v`

Expected: FAIL because the audio temp root and mismatch dialog are not yet wired correctly.

- [ ] **Step 3: Write minimal implementation**

Implement audio inference so it consumes imported source assets from the shared import root, while the resumable task state lives entirely under `files/TEMP/audio_inference/`. The TEMP workspace must hold only:
- the manifest JSON,
- the chunk input files,
- the chunk output files,
- the current unsaved final output file for this run.

The selected source audio, model, and index files must first be imported from the file picker into the app-private import root, then referenced from the task manifest.

`generate_screen.dart` must only show the “继续未完成” button when native metadata says the current `songPath + modelPath + indexPath + parameters` match the manifest. If the metadata exists but does not match, show a dialog that says the current task is inconsistent with history and then do nothing.

When the user taps the normal generate button, the app must first clear the old `TEMP/audio_inference/` workspace, then start the new run. When the run completes successfully, the previous unsaved audio-inference output must be deleted and replaced by the current run’s unsaved output. If the user later taps “保存”, that saved copy leaves the working lifecycle and is kept outside the working directories.

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m pytest python/tests/test_native_bridge_static.py python/tests/test_generate_flow_static.py -k audio_inference -v`

Expected: PASS.

---

### Task 3: Make source/model/index switching update imported-file references and clear stale temp workspaces

**Files:**
- Modify: `lib/main.dart`
- Modify: `lib/screens/song_picker_screen.dart`
- Modify: `lib/screens/model_picker_screen.dart`
- Modify: `lib/screens/realtime_inference_screen.dart`
- Modify: `lib/screens/voice_changer_screen.dart`
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/RVCPlugin.kt`
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerPlugin.kt`
- Test: `python/tests/test_generate_flow_static.py`, `python/tests/test_voice_changer_static.py`, `python/tests/test_navigation_and_io_static.py`

- [ ] **Step 1: Write the failing test**

```python
def test_switching_audio_or_models_releases_imported_files_and_clears_stale_temp_workspaces():
    main = (ROOT / 'lib/main.dart').read_text(encoding='utf-8')
    assert '_deleteOwnedPathIfNeeded' in main
    assert 'referenceCount' in main or 'releaseReference' in main
    assert 'FILE_PICKER' in main or 'importPickedFile' in main
    assert 'TEMP/audio_inference' in main
    assert 'TEMP/voice_changer' in main
    assert 'switching files' not in main
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest python/tests/test_generate_flow_static.py python/tests/test_voice_changer_static.py python/tests/test_navigation_and_io_static.py -k switching_audio_or_models -v`

Expected: FAIL because the shared reference-count cleanup is not yet implemented.

- [ ] **Step 3: Write minimal implementation**

Whenever the user changes song/model/index in any mode, the previous imported file must be released from the registry first; only when its reference count drops to zero may the imported file be deleted. If the file is still referenced by another mode, it must stay in the import root. Separately, the mode’s own TEMP workspace must be cleared before starting a new task in that mode.

This rule must apply to:
- audio song selection,
- audio model selection,
- audio index selection,
- realtime model/index selection,
- voice changer model/index selection,
- new generation starts,
- voice changer recording restarts,
- save-as/export actions not stored in temp.

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m pytest python/tests/test_generate_flow_static.py python/tests/test_voice_changer_static.py python/tests/test_navigation_and_io_static.py -k switching_audio_or_models -v`

Expected: PASS.

---

### Task 4: Add voice changer resumable recovery using imported assets plus `files/TEMP/voice_changer`

**Files:**
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerRecorder.kt`
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerOverlayService.kt`
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/VoiceChangerPlugin.kt`
- Modify: `lib/services/voice_changer_bridge.dart`
- Modify: `lib/screens/voice_changer_screen.dart`
- Test: `python/tests/test_voice_changer_static.py`

- [ ] **Step 1: Write the failing test**

```python
def test_voice_changer_exposes_continue_unfinished_and_single_instance_temp_cleanup():
    screen = (ROOT / 'lib/screens/voice_changer_screen.dart').read_text(encoding='utf-8')
    service = SERVICE.read_text(encoding='utf-8')
    recorder = RECORDER.read_text(encoding='utf-8')

    assert '继续未完成' in screen or '继续处理' in screen
    assert 'FILE_PICKER' in recorder or 'importPickedFile' in service
    assert 'TEMP/voice_changer' in recorder
    assert 'TEMP/voice_changer' in service
    assert 'current task is inconsistent' not in screen
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest python/tests/test_voice_changer_static.py -k continue_unfinished -v`

Expected: FAIL because the voice changer recovery entry and cleanup are not yet implemented.

- [ ] **Step 3: Write minimal implementation**

The voice changer flow must use imported model/index assets from the shared import root, while `files/TEMP/voice_changer/` is its single-instance task workspace. That TEMP workspace holds only task-state files such as the current recording copy, manifest, processing intermediates, and the current unsaved output. If the user has not saved the result, a new recording/processing round must clear the old `TEMP/voice_changer/` files first.

Add a continue-unfinished path that checks the manifest and current file/parameter identity before resuming. If the manifest does not match, the app must show the same inconsistency dialog and do nothing.

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m pytest python/tests/test_voice_changer_static.py -k continue_unfinished -v`

Expected: PASS.

---

### Task 5: Final verification and install

**Files:**
- All modified Kotlin and Dart files
- `python/tests/test_native_bridge_static.py`
- `python/tests/test_generate_flow_static.py`
- `python/tests/test_voice_changer_static.py`

- [ ] **Step 1: Run focused static tests**

Run: `python3 -m pytest python/tests/test_native_bridge_static.py python/tests/test_generate_flow_static.py python/tests/test_voice_changer_static.py -q`

Expected: PASS.

- [ ] **Step 2: Run diagnostics**

Run:

```bash
lsp_diagnostics --extension .kt android/app/src/main/kotlin/com/ultimatervc/mobile all
lsp_diagnostics --extension .dart lib all
```

Expected: zero errors.

- [ ] **Step 3: Build release APK**

Run: `flutter build apk --release`

Expected: exit 0 and a fresh `build/app/outputs/flutter-apk/app-release.apk`.

- [ ] **Step 4: Install on device**

Run:

```bash
/home/harl/android-sdk/platform-tools/adb install -r -d build/app/outputs/flutter-apk/app-release.apk
```

Expected: `Success`.

---

### Coverage check

- Imported-file directory and mode TEMP directories are correctly separated: Task 1.
- File reference counting across modes applies only to imported assets: Task 1 and Task 3.
- Audio inference continue-unfinished and mismatch dialog: Task 2.
- Switch before clear / clear before new generation: Task 3.
- Voice changer continue-unfinished and cleanup: Task 4.
- Release build + install proof: Task 5.
