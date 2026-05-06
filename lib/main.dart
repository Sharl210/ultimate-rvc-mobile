import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'screens/song_picker_screen.dart';
import 'screens/model_picker_screen.dart';
import 'screens/generate_screen.dart';
import 'screens/result_screen.dart';
import 'screens/index_converter_screen.dart';
import 'screens/parameter_guide_screen.dart';
import 'screens/realtime_inference_screen.dart';
import 'screens/voice_changer_screen.dart';
import 'screens/decibel_meter_screen.dart';
import 'services/rvc_bridge.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);
  runApp(UltimateRVCMobileApp());
}

class UltimateRVCMobileApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Ultimate RVC Mobile',
      theme: ThemeData(
        primarySwatch: Colors.blue,
        visualDensity: VisualDensity.adaptivePlatformDensity,
        useMaterial3: true,
      ),
      home: MainScreen(),
      debugShowCheckedModeBanner: false,
    );
  }
}

class MainScreen extends StatefulWidget {
  @override
  _MainScreenState createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> {
  final GlobalKey<ScaffoldState> _scaffoldKey = GlobalKey<ScaffoldState>();
  int _moduleIndex = 0;
  int _audioStepIndex = 0;
  late final PageController _audioPageController;
  double _audioHorizontalDragDistance = 0.0;
  double _moduleHorizontalDragDistance = 0.0;
  bool _isDrawerOpen = false;
  Timer? _inferenceProcessPollTimer;
  DateTime? _lastBackPressedAt;
  String? _selectedSongPath;
  String? _selectedSongDisplayName;
  String? _selectedModelPath;
  String? _selectedModelDisplayName;
  String? _selectedIndexPath;
  String? _selectedIndexDisplayName;
  String? _generatedOutputPath;
  String? _pendingOutputDeletionPath;
  Duration? _generationDuration;
  final RVCBridge _rvcBridge = RVCBridge();
  final RvcGenerationState _generationState = RvcGenerationState();
  bool _realtimeInferenceRunning = false;
  bool _voiceChangerProcessing = false;
  bool _inferenceProcessRunning = false;

  @override
  void dispose() {
    _inferenceProcessPollTimer?.cancel();
    _audioPageController.dispose();
    _generationState.dispose();
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    _audioPageController = PageController(initialPage: _audioStepIndex);
    _loadSavedState();
    _initializeRVC();
    _startInferenceProcessPolling();
  }

  void _startInferenceProcessPolling() {
    _inferenceProcessPollTimer?.cancel();
    _pollInferenceProcessRunning();
    _inferenceProcessPollTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      _pollInferenceProcessRunning();
    });
  }

  Future<void> _pollInferenceProcessRunning() async {
    final running = await _rvcBridge.isInferenceProcessRunning();
    if (!mounted) return;

    final shouldRecoverGeneration = !running && (_generationState.isGenerating || _generationState.isStopping);
    final shouldRecoverRealtime = !running && _realtimeInferenceRunning;
    final shouldRecoverVoiceChanger = !running && _voiceChangerProcessing;
    final shouldUpdateRunningFlag = _inferenceProcessRunning != running;

    if (!shouldRecoverGeneration && !shouldRecoverRealtime && !shouldRecoverVoiceChanger && !shouldUpdateRunningFlag) {
      return;
    }

    if (shouldRecoverGeneration) {
      if (_generationState.isStopping) {
        _generationState.fail('生成已中止');
      } else if (_generationState.progress >= 0.999) {
        _generationState.complete('生成完成');
      } else {
        _generationState.fail('生成已中止：推理进程已结束');
      }
    }

    setState(() {
      _inferenceProcessRunning = running;
      if (shouldRecoverRealtime) {
        _realtimeInferenceRunning = false;
      }
      if (shouldRecoverVoiceChanger) {
        _voiceChangerProcessing = false;
      }
    });
  }

  Future<void> _loadSavedState() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _selectedSongPath = prefs.getString('selectedSongPath');
      _selectedSongDisplayName = prefs.getString('selectedSongDisplayName');
      _selectedModelPath = prefs.getString('selectedModelPath');
      _selectedModelDisplayName = prefs.getString('selectedModelDisplayName');
      _selectedIndexPath = prefs.getString('selectedIndexPath');
      _selectedIndexDisplayName = prefs.getString('selectedIndexDisplayName');
      _generatedOutputPath = prefs.getString('generatedOutputPath');
      final generationDurationMs = prefs.getInt('generationDurationMs');
      _generationDuration = generationDurationMs == null ? null : Duration(milliseconds: generationDurationMs);
      _moduleIndex = prefs.getInt('moduleIndex') ?? 0;
      _audioStepIndex = prefs.getInt('audioStepIndex') ?? 0;
    });
    if (_audioPageController.hasClients) {
      _audioPageController.jumpToPage(_audioStepIndex);
    } else {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _audioPageController.hasClients) {
          _audioPageController.jumpToPage(_audioStepIndex);
        }
      });
    }
  }

  Future<void> _saveState() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('moduleIndex', _moduleIndex);
    await prefs.setInt('audioStepIndex', _audioStepIndex);
    if (_selectedSongPath == null) {
      await prefs.remove('selectedSongPath');
      await prefs.remove('selectedSongDisplayName');
    } else {
      await prefs.setString('selectedSongPath', _selectedSongPath!);
      await prefs.setString('selectedSongDisplayName', _selectedSongDisplayName ?? File(_selectedSongPath!).uri.pathSegments.last);
    }
    if (_selectedModelPath == null) {
      await prefs.remove('selectedModelPath');
      await prefs.remove('selectedModelDisplayName');
    } else {
      await prefs.setString('selectedModelPath', _selectedModelPath!);
      await prefs.setString('selectedModelDisplayName', _selectedModelDisplayName ?? File(_selectedModelPath!).uri.pathSegments.last);
    }
    if (_selectedIndexPath == null) {
      await prefs.remove('selectedIndexPath');
      await prefs.remove('selectedIndexDisplayName');
    } else {
      await prefs.setString('selectedIndexPath', _selectedIndexPath!);
      await prefs.setString('selectedIndexDisplayName', _selectedIndexDisplayName ?? File(_selectedIndexPath!).uri.pathSegments.last);
    }
    if (_generatedOutputPath == null) {
      await prefs.remove('generatedOutputPath');
    } else {
      await prefs.setString('generatedOutputPath', _generatedOutputPath!);
    }
    if (_generationDuration == null) {
      await prefs.remove('generationDurationMs');
    } else {
      await prefs.setInt('generationDurationMs', _generationDuration!.inMilliseconds);
    }
  }

  Future<void> _initializeRVC() async {
    try {
      await _rvcBridge.initialize();
    } catch (e) {
        _showErrorDialog('初始化失败', 'RVC 初始化失败：$e');
    }
  }

  void _showErrorDialog(String title, String message) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text('确定'),
          ),
        ],
      ),
    );
  }

  Future<void> _handleBackPressed() async {
    final now = DateTime.now();
    const backExitWindow = Duration(milliseconds: 900);
    final shouldExit = _lastBackPressedAt != null && now.difference(_lastBackPressedAt!) <= backExitWindow;
    if (_isDrawerOpen) {
      _scaffoldKey.currentState?.closeDrawer();
      return;
    }
    if (shouldExit) {
      SystemNavigator.pop();
      return;
    }
    _lastBackPressedAt = now;
    await _rvcBridge.showToast('再按一次退出');
  }

  Future<void> _releaseImportedPathIfNeeded(String? path) async {
    if (path == null) return;
    try {
      await _rvcBridge.releaseImportedFile(path);
    } catch (_) {
      // 释放失败时保留旧文件，避免误删共享引用。
    }
  }

  Future<void> _deleteOwnedPathIfNeeded(String? path) async {
    if (path == null || path.isEmpty) return;
    final absolutePath = File(path).absolute.path;
    if (absolutePath.contains('/FILE_PICKER/') || absolutePath.contains('/file_picker/')) {
      await _releaseImportedPathIfNeeded(absolutePath);
      return;
    }
    final temporaryDirectory = await getTemporaryDirectory();
    final applicationDocumentsDirectory = await getApplicationDocumentsDirectory();
    final isAppOwnedFile = absolutePath.startsWith(temporaryDirectory.path) ||
        absolutePath.startsWith(applicationDocumentsDirectory.path) ||
        absolutePath.contains('/recordings/') ||
        absolutePath.contains('/outputs/') ||
        absolutePath.contains('/voice_changer/') ||
        absolutePath.contains('/TEMP/');
    if (!isAppOwnedFile) return;
    try {
      final file = File(absolutePath);
      if (await file.exists()) {
        await file.delete();
      }
    } catch (_) {
      // 删除失败时保留旧文件，避免误删用户外部文件。
    }
  }

  Future<String> _importPickedFile({required String kind, required String sourcePath}) async {
    final imported = await _rvcBridge.importPickedFile(kind: kind, sourcePath: sourcePath);
    return imported.path;
  }

  Future<void> _clearAudioInferenceTempWorkspace() async {
    await _rvcBridge.clearTempWorkspace('audio_inference');
  }

  Future<void> _clearVoiceChangerTempWorkspace() async {
    await _rvcBridge.clearTempWorkspace('voice_changer');
  }

  void _queueOutputForDeletionAfterNextSuccess(String? path) {
    if (path == null || path.isEmpty) return;
    _pendingOutputDeletionPath = path;
  }

  void _setAudioStep(int index, {required bool animate}) {
    setState(() => _audioStepIndex = index);
    _saveState();
    if (animate) {
      _audioPageController.animateToPage(
        index,
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
      );
    }
  }

  void _onAudioPageChanged(int index) {
    if (!_canOpenAudioStep(index)) {
      _audioPageController.animateToPage(
        _audioStepIndex,
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOutCubic,
      );
      return;
    }
    setState(() => _audioStepIndex = index);
    _saveState();
  }

  bool _canOpenAudioStep(int index) {
    return index == 0 ||
        (index == 1 && _selectedSongPath != null) ||
        (index == 2 && _selectedSongPath != null && _selectedModelPath != null) ||
        (index == 3 && _generatedOutputPath != null);
  }

  void _handleAudioHorizontalDragUpdate(DragUpdateDetails details) {
    _audioHorizontalDragDistance += details.primaryDelta ?? 0.0;
  }

  void _handleAudioHorizontalDragEnd(DragEndDetails details) {
    final velocity = details.primaryVelocity ?? 0.0;
    final shouldGoPrevious = _audioHorizontalDragDistance > _audioSwipeDistanceThreshold || velocity > _audioSwipeVelocityThreshold;
    final shouldGoNext = _audioHorizontalDragDistance < -_audioSwipeDistanceThreshold || velocity < -_audioSwipeVelocityThreshold;
    _audioHorizontalDragDistance = 0.0;
    if (_audioStepIndex == 0 && shouldGoPrevious) {
      _openDrawerFromSwipe();
      return;
    }
    final nextIndex = shouldGoPrevious
        ? _audioStepIndex - 1
        : shouldGoNext
            ? _audioStepIndex + 1
            : _audioStepIndex;
    if (nextIndex != _audioStepIndex && nextIndex >= 0 && nextIndex < _audioStepCount && _canOpenAudioStep(nextIndex)) {
      _setAudioStep(nextIndex, animate: true);
    }
  }

  void _openDrawerFromSwipe() {
    _scaffoldKey.currentState?.openDrawer();
  }

  void _handleModuleHorizontalDragUpdate(DragUpdateDetails details) {
    _moduleHorizontalDragDistance += details.primaryDelta ?? 0.0;
  }

  void _handleModuleHorizontalDragEnd(DragEndDetails details) {
    final velocity = details.primaryVelocity ?? 0.0;
    final shouldOpenDrawer = _moduleHorizontalDragDistance > _audioSwipeDistanceThreshold || velocity > _audioSwipeVelocityThreshold;
    _moduleHorizontalDragDistance = 0.0;
    if (shouldOpenDrawer) {
      _openDrawerFromSwipe();
    }
  }

  void _onSongSelected(String path) async {
    final previousSongPath = _selectedSongPath;
    final previousOutputPath = _generatedOutputPath;
    final importedPath = await _importPickedFile(kind: 'audio', sourcePath: path);
    setState(() {
      _selectedSongPath = importedPath;
      _selectedSongDisplayName = File(path).uri.pathSegments.last;
      _moduleIndex = 0;
      _audioStepIndex = 1; // Move to model picker
      _generatedOutputPath = null;
      _generationDuration = null;
    });
    _queueOutputForDeletionAfterNextSuccess(previousOutputPath);
    _audioPageController.animateToPage(1, duration: const Duration(milliseconds: 220), curve: Curves.easeOutCubic);
    _saveState();
    await _clearAudioInferenceTempWorkspace();
    await _clearVoiceChangerTempWorkspace();
    if (previousSongPath != path) {
      await _deleteOwnedPathIfNeeded(previousSongPath);
    }
  }

  void _onSongCleared() async {
    final previousSongPath = _selectedSongPath;
    final previousOutputPath = _generatedOutputPath;
    setState(() {
      _selectedSongPath = null;
      _selectedSongDisplayName = null;
      _generatedOutputPath = null;
      _generationDuration = null;
      _audioStepIndex = 0;
    });
    _queueOutputForDeletionAfterNextSuccess(previousOutputPath);
    _audioPageController.animateToPage(0, duration: const Duration(milliseconds: 220), curve: Curves.easeOutCubic);
    _saveState();
    await _clearAudioInferenceTempWorkspace();
    await _deleteOwnedPathIfNeeded(previousSongPath);
  }

  void _onModelSelected(String path) async {
    final previousModelPath = _selectedModelPath;
    final previousOutputPath = _generatedOutputPath;
    final importedPath = await _importPickedFile(kind: 'model', sourcePath: path);
    setState(() {
      _selectedModelPath = importedPath;
      _selectedModelDisplayName = File(path).uri.pathSegments.last;
      _generatedOutputPath = null;
      _generationDuration = null;
    });
    _queueOutputForDeletionAfterNextSuccess(previousOutputPath);
    _saveState();
    await _clearAudioInferenceTempWorkspace();
    if (previousModelPath != path) {
      await _deleteOwnedPathIfNeeded(previousModelPath);
    }
  }

  void _onModelCleared() async {
    final previousModelPath = _selectedModelPath;
    final previousIndexPath = _selectedIndexPath;
    final previousOutputPath = _generatedOutputPath;
    setState(() {
      _selectedModelPath = null;
      _selectedModelDisplayName = null;
      _selectedIndexPath = null;
      _selectedIndexDisplayName = null;
      _generatedOutputPath = null;
      _generationDuration = null;
      _audioStepIndex = 1;
    });
    _queueOutputForDeletionAfterNextSuccess(previousOutputPath);
    _audioPageController.animateToPage(1, duration: const Duration(milliseconds: 220), curve: Curves.easeOutCubic);
    _saveState();
    await _clearAudioInferenceTempWorkspace();
    await _deleteOwnedPathIfNeeded(previousModelPath);
    await _deleteOwnedPathIfNeeded(previousIndexPath);
  }

  void _continueToGenerate() {
    if (_selectedSongPath == null || _selectedModelPath == null) {
      return;
    }
    setState(() {
      _moduleIndex = 0;
      _audioStepIndex = 2;
    });
    _audioPageController.animateToPage(2, duration: const Duration(milliseconds: 220), curve: Curves.easeOutCubic);
    _saveState();
  }

  void _onIndexSelected(String? path) async {
    final previousIndexPath = _selectedIndexPath;
    final previousOutputPath = _generatedOutputPath;
    final importedPath = path == null ? null : await _importPickedFile(kind: 'index', sourcePath: path);
    setState(() {
      _selectedIndexPath = importedPath;
      _selectedIndexDisplayName = path == null ? null : File(path).uri.pathSegments.last;
      _generatedOutputPath = null;
      _generationDuration = null;
    });
    _queueOutputForDeletionAfterNextSuccess(previousOutputPath);
    _saveState();
    await _clearAudioInferenceTempWorkspace();
    if (previousIndexPath != path) {
      await _deleteOwnedPathIfNeeded(previousIndexPath);
    }
  }

  void _onGenerationComplete(String outputPath, Duration generationDuration) async {
    final previousOutputPath = _generatedOutputPath;
    final pendingOutputDeletionPath = _pendingOutputDeletionPath;
    setState(() {
      _generatedOutputPath = outputPath;
      _generationDuration = generationDuration;
      _pendingOutputDeletionPath = null;
      _moduleIndex = 0;
      _audioStepIndex = 3;
    });
    _audioPageController.animateToPage(3, duration: const Duration(milliseconds: 220), curve: Curves.easeOutCubic);
    _saveState();
    final outputToDelete = pendingOutputDeletionPath ?? previousOutputPath;
    if (outputToDelete != outputPath) {
      await _deleteOwnedPathIfNeeded(outputToDelete);
    }
  }

  void _startNewGeneration() async {
    final previousOutputPath = _generatedOutputPath;
    setState(() {
      _generationDuration = null;
      _moduleIndex = 0;
      _audioStepIndex = 2;
    });
    _queueOutputForDeletionAfterNextSuccess(previousOutputPath);
    _audioPageController.animateToPage(2, duration: const Duration(milliseconds: 220), curve: Curves.easeOutCubic);
    _saveState();
    await _clearAudioInferenceTempWorkspace();
  }

  void _onRealtimeInferenceRunningChanged(bool running) {
    if (_realtimeInferenceRunning == running) return;
    setState(() => _realtimeInferenceRunning = running);
  }

  void _onVoiceChangerProcessingChanged(bool processing) {
    if (_voiceChangerProcessing == processing) return;
    setState(() => _voiceChangerProcessing = processing);
  }

  void _openAudioConversion() {
    setState(() => _moduleIndex = 0);
    _saveState();
    _scaffoldKey.currentState?.closeDrawer();
  }

  void _openRealtimeInference() {
    setState(() => _moduleIndex = 1);
    _saveState();
    _scaffoldKey.currentState?.closeDrawer();
  }

  void _openVoiceChanger() {
    setState(() => _moduleIndex = 2);
    _saveState();
    _scaffoldKey.currentState?.closeDrawer();
  }

  void _openIndexConverter() {
    setState(() => _moduleIndex = 3);
    _saveState();
    _scaffoldKey.currentState?.closeDrawer();
  }

  void _openParameterGuide() {
    setState(() => _moduleIndex = 4);
    _saveState();
    _scaffoldKey.currentState?.closeDrawer();
  }

  void _openDecibelMeter() {
    setState(() => _moduleIndex = 5);
    _saveState();
    _scaffoldKey.currentState?.closeDrawer();
  }

  Drawer _buildDrawer() {
    return Drawer(
      child: ListView(
        padding: EdgeInsets.zero,
        children: [
          DrawerHeader(
            child: Align(
              alignment: Alignment.bottomLeft,
              child: Text('Ultimate RVC', style: Theme.of(context).textTheme.headlineSmall),
            ),
          ),
          ListTile(
            leading: Icon(Icons.graphic_eq),
            title: Text('音频推理'),
            selected: _moduleIndex == 0,
            onTap: _openAudioConversion,
          ),
          ListTile(
            leading: Icon(Icons.settings_voice),
            title: Text('实时推理'),
            selected: _moduleIndex == 1,
            onTap: _openRealtimeInference,
          ),
          ListTile(
            leading: Icon(Icons.record_voice_over),
            title: Text('变声器模式'),
            selected: _moduleIndex == 2,
            onTap: _openVoiceChanger,
          ),
          ListTile(
            leading: Icon(Icons.sync_alt),
            title: Text('mobile.index 转换教程'),
            selected: _moduleIndex == 3,
            onTap: _openIndexConverter,
          ),
          ListTile(
            leading: Icon(Icons.tune),
            title: Text('参数解释'),
            selected: _moduleIndex == 4,
            onTap: _openParameterGuide,
          ),
          ListTile(
            leading: Icon(Icons.speed),
            title: Text('分贝仪'),
            selected: _moduleIndex == 5,
            onTap: _openDecibelMeter,
          ),
          ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final canGenerate = _selectedSongPath != null && _selectedModelPath != null;
    final audioScreens = [
      SongPickerScreen(
        onSongSelected: _onSongSelected,
        onSongCleared: _onSongCleared,
        selectedSongPath: _selectedSongPath,
        selectedSongDisplayName: _selectedSongDisplayName,
      ),
      ModelPickerScreen(
        onModelSelected: _onModelSelected,
        onModelCleared: _onModelCleared,
        onIndexSelected: _onIndexSelected,
        onContinue: _continueToGenerate,
        selectedModelPath: _selectedModelPath,
        selectedModelDisplayName: _selectedModelDisplayName,
        selectedIndexPath: _selectedIndexPath,
        selectedIndexDisplayName: _selectedIndexDisplayName,
        selectedSongPath: _selectedSongPath,
        selectedSongDisplayName: _selectedSongDisplayName,
      ),
      canGenerate
          ? GenerateScreen(
              songPath: _selectedSongPath!,
              songDisplayName: _selectedSongDisplayName ?? File(_selectedSongPath!).uri.pathSegments.last,
              modelPath: _selectedModelPath!,
              modelDisplayName: _selectedModelDisplayName ?? File(_selectedModelPath!).uri.pathSegments.last,
              indexPath: _selectedIndexPath,
              indexDisplayName: _selectedIndexDisplayName,
              generationState: _generationState,
              otherModeRunning: _inferenceProcessRunning && !_generationState.isGenerating,
              onGenerationComplete: _onGenerationComplete,
            )
          : SongPickerScreen(
              onSongSelected: _onSongSelected,
              onSongCleared: _onSongCleared,
              selectedSongPath: _selectedSongPath,
              selectedSongDisplayName: _selectedSongDisplayName,
            ),
      _generatedOutputPath != null
          ? ResultScreen(
              outputPath: _generatedOutputPath!,
              generationDuration: _generationDuration,
              onNewGeneration: _startNewGeneration,
            )
          : SongPickerScreen(
              onSongSelected: _onSongSelected,
              onSongCleared: _onSongCleared,
              selectedSongPath: _selectedSongPath,
              selectedSongDisplayName: _selectedSongDisplayName,
            ),
    ];
    final moduleScreens = [
      GestureDetector(
        behavior: HitTestBehavior.translucent,
        onHorizontalDragUpdate: _handleAudioHorizontalDragUpdate,
        onHorizontalDragEnd: _handleAudioHorizontalDragEnd,
        child: PageView(
          controller: _audioPageController,
          physics: const NeverScrollableScrollPhysics(),
          onPageChanged: _onAudioPageChanged,
          children: audioScreens,
        ),
      ),
      RealtimeInferenceScreen(
        otherModeRunning: _generationState.isGenerating || (_inferenceProcessRunning && !_realtimeInferenceRunning),
        onRunningChanged: _onRealtimeInferenceRunningChanged,
      ),
      VoiceChangerScreen(
        otherModeRunning: _generationState.isGenerating || (_inferenceProcessRunning && !_voiceChangerProcessing),
        onProcessingChanged: _onVoiceChangerProcessingChanged,
      ),
      IndexConverterScreen(),
      ParameterGuideScreen(),
      DecibelMeterScreen(isActive: _moduleIndex == 5),
    ];

    return PopScope(
      canPop: false,
      onPopInvoked: (didPop) {
        if (!didPop) _handleBackPressed();
      },
      child: Scaffold(
        key: _scaffoldKey,
        drawer: _buildDrawer(),
        onDrawerChanged: (isOpened) => setState(() => _isDrawerOpen = isOpened),
        body: Stack(
        children: [
          GestureDetector(
            behavior: HitTestBehavior.translucent,
            onHorizontalDragUpdate: _moduleIndex == 0 ? null : _handleModuleHorizontalDragUpdate,
            onHorizontalDragEnd: _moduleIndex == 0 ? null : _handleModuleHorizontalDragEnd,
            child: IndexedStack(
              index: _moduleIndex,
              children: moduleScreens,
            ),
          ),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.only(left: 12),
              child: SizedBox(
                height: kToolbarHeight,
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: AnimatedRotation(
                    turns: _isDrawerOpen ? 0.25 : 0.0,
                    duration: const Duration(milliseconds: 180),
                    child: IconButton(
                      onPressed: () => _scaffoldKey.currentState?.openDrawer(),
                      icon: SvgPicture.string(
                        _menuSvg,
                        width: 28,
                        height: 28,
                        colorFilter: ColorFilter.mode(Theme.of(context).colorScheme.onSurface, BlendMode.srcIn),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
        bottomNavigationBar: _moduleIndex == 0
          ? NavigationBar(
              selectedIndex: _audioStepIndex,
              onDestinationSelected: (index) {
                if (_canOpenAudioStep(index)) {
                  _setAudioStep(index, animate: true);
                }
              },
              destinations: const [
                NavigationDestination(icon: Icon(Icons.music_note), label: '音频'),
                NavigationDestination(icon: Icon(Icons.mic), label: '音色'),
                NavigationDestination(icon: Icon(Icons.play_arrow), label: '生成'),
                NavigationDestination(icon: Icon(Icons.library_music), label: '结果'),
              ],
            )
          : null,
      ),
    );
  }

  static const _menuSvg = '''
<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
  <path d="M4 7h16M4 12h16M4 17h16" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/>
</svg>
''';
  static const _audioStepCount = 4;
  static const _audioSwipeDistanceThreshold = 96.0;
  static const _audioSwipeVelocityThreshold = 800.0;
}
