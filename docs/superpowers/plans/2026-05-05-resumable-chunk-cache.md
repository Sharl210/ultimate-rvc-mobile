# Resumable Chunk Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make long-audio segmentation resumable across app restarts by persisting chunk files and manifest metadata, with explicit continue/new-generation cleanup behavior.

**Architecture:** Android native code will own a persistent job folder for each resumable inference attempt. That folder will hold chunk input files, per-chunk processed outputs, and a JSON manifest that records source identity, model and audio parameters, chunk completion state, and the last known progress values. Flutter will query native for a resumable job summary, show a "继续未完成" action only when the current input and parameters still match the manifest, and use the ordinary generate action as a fresh start that clears old cache state before beginning.

**Tech Stack:** Kotlin, Dart, Flutter platform channels, `org.json` for native manifest persistence, `dart:convert` for bridge-side JSON parsing, existing pytest static contract tests.

---

### Task 1: Define the resumable job record and folder layout

**Files:**
- Create: `android/app/src/main/kotlin/com/ultimatervc/mobile/ResumableInferenceJobStore.kt`
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/RvcInferenceEngine.kt:102-126,194-245,980-1030`
- Test: `python/tests/test_native_bridge_static.py:193-213`

- [ ] **Step 1: Write the failing test**

```python
def test_resumable_job_store_persists_manifest_and_chunk_paths():
    source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/ResumableInferenceJobStore.kt').read_text(encoding='utf-8')
    assert 'data class ResumableInferenceJobManifest' in source
    assert 'sourcePath: String' in source
    assert 'sourceSizeBytes: Long' in source
    assert 'sourceLastModifiedMs: Long' in source
    assert 'sourceFingerprint: String' in source
    assert 'modelPath: String' in source
    assert 'indexPath: String?' in source
    assert 'chunkInputDir' in source
    assert 'chunkOutputDir' in source
    assert 'completedChunkIndexes: List<Int>' in source
    assert 'lastCompletedChunkIndex: Int' in source
    assert 'overallProgressPercent: Double' in source
    assert 'writeAtomicJson' in source
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest python/tests/test_native_bridge_static.py -k resumable_job_store -v`

Expected: FAIL because the manifest store file and persisted chunk layout do not exist yet.

- [ ] **Step 3: Write minimal implementation**

Implement a small native store with these rules:

```kotlin
data class ResumableInferenceJobManifest(
    val jobId: String,
    val sourcePath: String,
    val sourceSizeBytes: Long,
    val sourceLastModifiedMs: Long,
    val sourceFingerprint: String,
    val modelPath: String,
    val indexPath: String?,
    val pitchChange: Double,
    val indexRate: Double,
    val formant: Double,
    val filterRadius: Int,
    val rmsMixRate: Double,
    val protectRate: Double,
    val sampleRate: Int,
    val noiseGateDb: Double,
    val outputDenoiseEnabled: Boolean,
    val vocalRangeFilterEnabled: Boolean,
    val parallelChunkCount: Int,
    val totalDurationUs: Long,
    val segmentCount: Int,
    val completedChunkIndexes: List<Int>,
    val lastCompletedChunkIndex: Int,
    val overallProgressPercent: Double,
    val state: String,
    val updatedAtMs: Long,
)
```

Store each run under one durable directory with this shape:

```text
<app-cache>/resumable_inference/<jobId>/
  manifest.json
  chunks/
    input/
    output/
  final/
```

The manifest must be the source of truth for resume eligibility. Chunk input files are the raw audio slices. Chunk output files are the processed audio slices that let resume skip completed chunks.

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m pytest python/tests/test_native_bridge_static.py -k resumable_job_store -v`

Expected: PASS.

---

### Task 2: Persist chunk processing and resume from the last unfinished chunk

**Files:**
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/RvcInferenceEngine.kt:121-186,194-245,1026-1030,1856-1857`
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/InferenceProcessService.kt:80-135`
- Test: `python/tests/test_native_bridge_static.py:215-245`

- [ ] **Step 1: Write the failing test**

```python
def test_long_audio_segmentation_writes_chunk_files_and_can_resume():
    source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/RvcInferenceEngine.kt').read_text(encoding='utf-8')
    assert 'ResumableInferenceJobStore' in source
    assert 'chunkInputFile' in source
    assert 'chunkOutputFile' in source
    assert 'manifest.markChunkStarted' in source
    assert 'manifest.markChunkCompleted' in source
    assert 'if (chunkOutputFile.exists())' in source
    assert 'completedChunkIndexes' in source
    assert 'resume from the last unfinished chunk' not in source
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest python/tests/test_native_bridge_static.py -k long_audio_segmentation_writes_chunk_files_and_can_resume -v`

Expected: FAIL because chunk persistence and skip-on-resume behavior are not implemented yet.

- [ ] **Step 3: Write minimal implementation**

In `inferSegmented(...)`, create the job directory before the first chunk, write each input chunk to disk, process one chunk at a time, and write each processed chunk to a durable output file before marking it complete in the manifest. When the service restarts or the app returns later, the engine should load the manifest, verify the source and parameter fingerprint, and skip every chunk whose output file already exists and is listed as complete.

Keep the progress bar on the original total scale by mapping each chunk's inner progress into the whole-job range. The resume point must always be the last unfinished chunk, not an arbitrary user-picked chunk.

When the last chunk finishes and the final output has been assembled, delete the whole job directory so only the exported result remains.

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m pytest python/tests/test_native_bridge_static.py -k long_audio_segmentation_writes_chunk_files_and_can_resume -v`

Expected: PASS.

---

### Task 3: Expose resumable-job queries and clear-before-new-generation behavior to Flutter

**Files:**
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/RVCPlugin.kt:40-90,540-590`
- Modify: `lib/services/rvc_bridge.dart:4-120`
- Modify: `lib/screens/generate_screen.dart`
- Test: `python/tests/test_navigation_and_io_static.py:530-580`

- [ ] **Step 1: Write the failing test**

```python
def test_generate_screen_shows_continue_unfinished_action_and_clears_cache_on_fresh_start():
    source = (ROOT / 'lib/screens/generate_screen.dart').read_text(encoding='utf-8')
    bridge_source = BRIDGE.read_text(encoding='utf-8')
    assert '继续未完成' in source
    assert 'getPendingInferenceJob' in bridge_source
    assert 'clearPendingInferenceJob' in bridge_source
    assert 'await _rvcBridge.clearPendingInferenceJob()' in source
    assert 'await _rvcBridge.infer(' in source
    assert 'resume' not in source.lower()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest python/tests/test_navigation_and_io_static.py -k continue_unfinished_action -v`

Expected: FAIL because the continue action and bridge methods do not exist yet.

- [ ] **Step 3: Write minimal implementation**

Add a bridge method that returns the pending resumable-job summary as a Dart map, including the manifest fingerprint, source path, parameter set, completed chunk indexes, and current progress. Add a second bridge method that clears the resumable-job directory immediately before a fresh generation starts.

In the generate screen, show a separate "继续未完成" button only when the pending job matches the current selected file and parameters. The normal generate button should first clear the cache, then start a fresh run.

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m pytest python/tests/test_navigation_and_io_static.py -k continue_unfinished_action -v`

Expected: PASS.

---

### Task 4: Lock down mismatch and cleanup rules

**Files:**
- Modify: `android/app/src/main/kotlin/com/ultimatervc/mobile/ResumableInferenceJobStore.kt`
- Modify: `python/tests/test_native_bridge_static.py`
- Modify: `python/tests/test_generate_flow_static.py`

- [ ] **Step 1: Write the failing test**

```python
def test_resume_job_is_rejected_when_input_or_parameters_change():
    source = (ROOT / 'android/app/src/main/kotlin/com/ultimatervc/mobile/ResumableInferenceJobStore.kt').read_text(encoding='utf-8')
    assert 'fun matches(request: RvcInferenceRequest): Boolean' in source
    assert 'sourceFingerprint == request.sourceFingerprint()' in source
    assert 'parametersFingerprint == request.parametersFingerprint()' in source
    assert 'clearIfMismatch' in source
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest python/tests/test_native_bridge_static.py -k resume_job_is_rejected_when_input_or_parameters_change -v`

Expected: FAIL because the mismatch guard and stale-cache cleanup path do not exist yet.

- [ ] **Step 3: Write minimal implementation**

Implement a strict manifest match check so resume is allowed only when the audio source identity and the full inference parameter fingerprint still match. If the file has changed, the model has changed, or any timing parameter has changed, the continue action must stay hidden and the stale cache must not be reused.

Keep the cache on disk only when a run is interrupted. Delete it in two cases only: successful end-to-end completion, or explicit fresh generation before a new run starts.

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m pytest python/tests/test_native_bridge_static.py -k resume_job_is_rejected_when_input_or_parameters_change -v`

Expected: PASS.

---

### Task 5: Full verification

**Files:**
- All changed Kotlin and Dart files
- `python/tests/test_native_bridge_static.py`
- `python/tests/test_generate_flow_static.py`
- `python/tests/test_navigation_and_io_static.py`

- [ ] **Step 1: Run the focused static tests**

Run: `python3 -m pytest python/tests/test_native_bridge_static.py python/tests/test_generate_flow_static.py python/tests/test_navigation_and_io_static.py -q`

Expected: PASS.

- [ ] **Step 2: Run language-server diagnostics on modified code**

Run:

```bash
lsp_diagnostics --extension .kt android/app/src/main/kotlin/com/ultimatervc/mobile all
lsp_diagnostics --extension .dart lib all
```

Expected: zero errors.

- [ ] **Step 3: Run project-level Flutter checks**

Run:

```bash
flutter analyze
flutter test test/main_screen_test.dart
flutter build apk --release
```

Expected: all pass, and the release APK is produced.

- [ ] **Step 4: Install the release APK if a device is attached**

Run:

```bash
/home/harl/android-sdk/platform-tools/adb install -r -d build/app/outputs/flutter-apk/app-release.apk
```

Expected: `Success` when a device is connected.

---

### Coverage check

- Chunk files persisted on disk: covered by Tasks 1 and 2.
- Manifest and metadata for resume validation: covered by Task 1.
- Resume from last unfinished chunk after restart: covered by Task 2.
- Original total progress bar preserved: covered by Task 2.
- Continue button in UI: covered by Task 3.
- Fresh generation clears old cache: covered by Tasks 3 and 4.
- Successful completion clears the cache: covered by Task 2.
- Input/parameter mismatch blocks resume: covered by Task 4.
