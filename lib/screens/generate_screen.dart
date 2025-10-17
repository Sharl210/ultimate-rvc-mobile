import 'package:flutter/material.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:share_plus/share_plus.dart';
import 'package:path_provider/path_provider.dart';
import 'dart:io';
import '../services/rvc_bridge.dart';

class GenerateScreen extends StatefulWidget {
  final String songPath;
  final String modelPath;
  final VoidCallback onGenerationComplete;

  const GenerateScreen({
    required this.songPath,
    required this.modelPath,
    required this.onGenerationComplete,
  });

  @override
  _GenerateScreenState createState() => _GenerateScreenState();
}

class _GenerateScreenState extends State<GenerateScreen> {
  final RVCBridge _rvcBridge = RVCBridge();
  final AudioPlayer _audioPlayer = AudioPlayer();
  
  bool _isGenerating = false;
  bool _generationComplete = false;
  double _progress = 0.0;
  String _status = 'Ready to generate';
  String? _outputPath;
  bool _isPlaying = false;

  // RVC Parameters
  double _pitchChange = 0.0;
  double _indexRate = 0.75;
  int _filterRadius = 3;
  double _rmsMixRate = 0.25;
  double _protectRate = 0.33;

  @override
  void dispose() {
    _audioPlayer.dispose();
    super.dispose();
  }

  Future<void> _generate() async {
    setState(() {
      _isGenerating = true;
      _progress = 0.0;
      _status = 'Initializing...';
    });

    try {
      final outputPath = await _rvcBridge.infer(
        songPath: widget.songPath,
        modelPath: widget.modelPath,
        pitchChange: _pitchChange,
        indexRate: _indexRate,
        filterRadius: _filterRadius,
        rmsMixRate: _rmsMixRate,
        protectRate: _protectRate,
        onProgress: (progress, status) {
          setState(() {
            _progress = progress / 100.0;
            _status = status;
          });
        },
      );

      setState(() {
        _outputPath = outputPath;
        _generationComplete = true;
        _isGenerating = false;
        _status = 'Generation complete!';
      });
    } catch (e) {
      setState(() {
        _isGenerating = false;
        _status = 'Generation failed: $e';
      });
      
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Generation failed: $e')),
      );
    }
  }

  Future<void> _playPause() async {
    if (_outputPath == null) return;

    if (_isPlaying) {
      await _audioPlayer.pause();
      setState(() {
        _isPlaying = false;
      });
    } else {
      await _audioPlayer.play(DeviceFileSource(_outputPath!));
      setState(() {
        _isPlaying = true;
      });
    }
  }

  Future<void> _share() async {
    if (_outputPath == null) return;

    try {
      await Share.shareXFiles(
        [XFile(_outputPath!)],
        subject: 'Ultimate RVC Generated Audio',
        text: 'Check out this AI-generated voice conversion!',
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Failed to share: $e')),
      );
    }
  }

  void _reset() {
    setState(() {
      _generationComplete = false;
      _outputPath = null;
      _progress = 0.0;
      _status = 'Ready to generate';
      _isPlaying = false;
    });
    widget.onGenerationComplete();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Generate Voice Conversion'),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Song and Model Info
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Input Song:',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    Text(
                      widget.songPath.split('/').last,
                      style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                    SizedBox(height: 8),
                    Text(
                      'Voice Model:',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    Text(
                      widget.modelPath.split('/').last,
                      style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                  ],
                ),
              ),
            ),
            
            SizedBox(height: 16),

            // RVC Parameters
            if (!_generationComplete) ...[
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Voice Settings',
                        style: Theme.of(context).textTheme.titleSmall?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                      ),
                      SizedBox(height: 16),
                      
                      // Pitch Change
                      Text('Pitch Change: ${_pitchChange.toStringAsFixed(1)} semitones'),
                      Slider(
                        value: _pitchChange,
                        min: -12.0,
                        max: 12.0,
                        divisions: 24,
                        label: _pitchChange.toStringAsFixed(1),
                        onChanged: (value) {
                          setState(() {
                            _pitchChange = value;
                          });
                        },
                      ),
                      
                      // Index Rate
                      Text('Voice Strength: ${(_indexRate * 100).toStringAsFixed(0)}%'),
                      Slider(
                        value: _indexRate,
                        min: 0.0,
                        max: 1.0,
                        divisions: 20,
                        label: (_indexRate * 100).toStringAsFixed(0),
                        onChanged: (value) {
                          setState(() {
                            _indexRate = value;
                          });
                        },
                      ),
                      
                      // Advanced Settings
                      ExpansionTile(
                        title: Text('Advanced Settings'),
                        children: [
                          // Filter Radius
                          Text('Filter Radius: $_filterRadius'),
                          Slider(
                            value: _filterRadius.toDouble(),
                            min: 0,
                            max: 10,
                            divisions: 10,
                            label: _filterRadius.toString(),
                            onChanged: (value) {
                              setState(() {
                                _filterRadius = value.toInt();
                              });
                            },
                          ),
                          
                          // RMS Mix Rate
                          Text('Volume Mix: ${(_rmsMixRate * 100).toStringAsFixed(0)}%'),
                          Slider(
                            value: _rmsMixRate,
                            min: 0.0,
                            max: 1.0,
                            divisions: 20,
                            label: (_rmsMixRate * 100).toStringAsFixed(0),
                            onChanged: (value) {
                              setState(() {
                                _rmsMixRate = value;
                              });
                            },
                          ),
                          
                          // Protect Rate
                          Text('Consonant Protection: ${(_protectRate * 100).toStringAsFixed(0)}%'),
                          Slider(
                            value: _protectRate,
                            min: 0.0,
                            max: 1.0,
                            divisions: 20,
                            label: (_protectRate * 100).toStringAsFixed(0),
                            onChanged: (value) {
                              setState(() {
                                _protectRate = value;
                              });
                            },
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
              
              SizedBox(height: 16),
            ],

            // Progress and Generation
            if (_isGenerating) ...[
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    children: [
                      Text(
                        'Generating...',
                        style: Theme.of(context).textTheme.titleSmall,
                      ),
                      SizedBox(height: 16),
                      LinearProgressIndicator(value: _progress),
                      SizedBox(height: 8),
                      Text(
                        _status,
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                      SizedBox(height: 8),
                      Text(
                        '${(_progress * 100).toStringAsFixed(1)}%',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
              ),
            ] else if (_generationComplete) ...[
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    children: [
                      Icon(
                        Icons.check_circle,
                        color: Colors.green,
                        size: 48,
                      ),
                      SizedBox(height: 16),
                      Text(
                        'Generation Complete!',
                        style: Theme.of(context).textTheme.titleSmall?.copyWith(
                              color: Colors.green,
                            ),
                      ),
                      SizedBox(height: 8),
                      Text(
                        'Your AI voice conversion is ready',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                    ],
                  ),
                ),
              ),
              
              SizedBox(height: 16),

              // Playback Controls
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                    children: [
                      ElevatedButton.icon(
                        onPressed: _playPause,
                        icon: Icon(_isPlaying ? Icons.pause : Icons.play_arrow),
                        label: Text(_isPlaying ? 'Pause' : 'Play'),
                      ),
                      ElevatedButton.icon(
                        onPressed: _share,
                        icon: Icon(Icons.share),
                        label: Text('Share'),
                      ),
                      ElevatedButton.icon(
                        onPressed: _reset,
                        icon: Icon(Icons.refresh),
                        label: Text('New'),
                      ),
                    ],
                  ),
                ),
              ),
            ] else ...[
              // Generate Button
              ElevatedButton.icon(
                onPressed: _generate,
                icon: Icon(Icons.magic_button),
                label: Text('Generate Voice Conversion'),
                style: ElevatedButton.styleFrom(
                  padding: EdgeInsets.symmetric(horizontal: 32, vertical: 16),
                ),
              ),
            ],

            SizedBox(height: 24),

            // Tips
            if (!_generationComplete) ...[
              Card(
                color: Colors.blue.shade50,
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '💡 Tips:',
                        style: Theme.of(context).textTheme.titleSmall?.copyWith(
                              color: Colors.blue.shade700,
                            ),
                      ),
                      SizedBox(height: 8),
                      Text(
                        '• Pitch: Higher values make the voice higher-pitched',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: Colors.blue.shade700,
                            ),
                      ),
                      Text(
                        '• Voice Strength: Controls how much of the target voice to apply',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: Colors.blue.shade700,
                            ),
                      ),
                      Text(
                        '• Processing takes 2-5 minutes depending on song length',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: Colors.blue.shade700,
                            ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}