import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'screens/song_picker_screen.dart';
import 'screens/model_picker_screen.dart';
import 'screens/generate_screen.dart';
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
  int _currentIndex = 0;
  String? _selectedSongPath;
  String? _selectedModelPath;
  final RVCBridge _rvcBridge = RVCBridge();

  @override
  void initState() {
    super.initState();
    _initializeRVC();
  }

  Future<void> _initializeRVC() async {
    try {
      await _rvcBridge.initialize();
      // Check if models need to be downloaded
      final modelsExist = await _rvcBridge.checkModels();
      if (!modelsExist) {
        _showDownloadDialog();
      }
    } catch (e) {
      _showErrorDialog('Initialization Error', 'Failed to initialize RVC: $e');
    }
  }

  void _showDownloadDialog() {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => DownloadModelsDialog(
        onDownloadComplete: () {
          Navigator.of(context).pop();
        },
      ),
    );
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
            child: Text('OK'),
          ),
        ],
      ),
    );
  }

  void _onSongSelected(String path) {
    setState(() {
      _selectedSongPath = path;
      _currentIndex = 1; // Move to model picker
    });
  }

  void _onModelSelected(String path) {
    setState(() {
      _selectedModelPath = path;
      _currentIndex = 2; // Move to generate screen
    });
  }

  void _onGenerationComplete() {
    setState(() {
      _currentIndex = 0; // Return to song picker
      _selectedSongPath = null;
      _selectedModelPath = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final screens = [
      SongPickerScreen(
        onSongSelected: _onSongSelected,
        selectedSongPath: _selectedSongPath,
      ),
      ModelPickerScreen(
        onModelSelected: _onModelSelected,
        selectedModelPath: _selectedModelPath,
        selectedSongPath: _selectedSongPath,
      ),
      GenerateScreen(
        songPath: _selectedSongPath!,
        modelPath: _selectedModelPath!,
        onGenerationComplete: _onGenerationComplete,
      ),
    ];

    return Scaffold(
      body: screens[_currentIndex],
      bottomNavigationBar: NavigationBar(
        selectedIndex: _currentIndex,
        onDestinationSelected: (index) {
          if (index == 0 || 
              (index == 1 && _selectedSongPath != null) ||
              (index == 2 && _selectedSongPath != null && _selectedModelPath != null)) {
            setState(() {
              _currentIndex = index;
            });
          }
        },
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.music_note),
            label: 'Song',
          ),
          NavigationDestination(
            icon: Icon(Icons.mic),
            label: 'Voice',
          ),
          NavigationDestination(
            icon: Icon(Icons.play_arrow),
            label: 'Generate',
          ),
        ],
      ),
    );
  }
}

class DownloadModelsDialog extends StatefulWidget {
  final VoidCallback onDownloadComplete;

  const DownloadModelsDialog({required this.onDownloadComplete});

  @override
  _DownloadModelsDialogState createState() => _DownloadModelsDialogState();
}

class _DownloadModelsDialogState extends State<DownloadModelsDialog> {
  double _progress = 0.0;
  String _status = 'Initializing...';
  bool _isDownloading = true;

  @override
  void initState() {
    super.initState();
    _startDownload();
  }

  Future<void> _startDownload() async {
    try {
      final rvcBridge = RVCBridge();
      await rvcBridge.downloadModels(
        onProgress: (progress, status) {
          setState(() {
            _progress = progress / 100.0;
            _status = status;
          });
        },
      );
      
      setState(() {
        _isDownloading = false;
        _status = 'Download complete!';
      });
      
      await Future.delayed(Duration(seconds: 2));
      widget.onDownloadComplete();
    } catch (e) {
      setState(() {
        _isDownloading = false;
        _status = 'Download failed: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Downloading AI Models'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          LinearProgressIndicator(value: _progress),
          SizedBox(height: 16),
          Text(_status),
          SizedBox(height: 8),
          Text(
            'Downloading 1.8 GB of AI models. This may take a while...',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
      actions: _isDownloading
          ? null
          : [
              TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: Text('OK'),
              ),
            ],
    );
  }
}