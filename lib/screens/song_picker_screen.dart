import 'dart:async';

import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:permission_handler/permission_handler.dart';

import '../services/rvc_bridge.dart';

class SongPickerScreen extends StatefulWidget {
  final Future<void> Function(String) onSongSelected;
  final VoidCallback? onSongCleared;
  final String? selectedSongPath;
  final String? selectedSongDisplayName;

  const SongPickerScreen({
    required this.onSongSelected,
    this.onSongCleared,
    this.selectedSongPath,
    this.selectedSongDisplayName,
  });

  @override
  State<SongPickerScreen> createState() => _SongPickerScreenState();
}

class _SongPickerScreenState extends State<SongPickerScreen> with WidgetsBindingObserver, AutomaticKeepAliveClientMixin {
  final AudioPlayer _audioPlayer = AudioPlayer();
  final RVCBridge _rvcBridge = RVCBridge();
  bool _isRecording = false;
  final Stopwatch _recordingStopwatch = Stopwatch();
  Timer? _recordingTimer;
  Duration _recordingElapsed = Duration.zero;
  bool _isPlaying = false;
  bool _userPausedAudio = false;
  bool _playbackCompleted = false;
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;
  String? _preparedSourcePath;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _audioPlayer.onPositionChanged.listen((position) {
      if (mounted) setState(() => _position = position);
    });
    _audioPlayer.onDurationChanged.listen((duration) {
      if (mounted) setState(() => _duration = duration);
    });
    _audioPlayer.onPlayerStateChanged.listen((state) async {
      if (_userPausedAudio && state == PlayerState.playing) {
        await _audioPlayer.pause();
        if (mounted) setState(() => _isPlaying = false);
        return;
      }
      if (mounted) setState(() => _isPlaying = state == PlayerState.playing);
    });
    _audioPlayer.onPlayerComplete.listen((_) {
      if (mounted) {
        setState(() {
          _isPlaying = false;
          _userPausedAudio = false;
          _playbackCompleted = true;
          _position = Duration.zero;
        });
      }
      _audioPlayer.seek(Duration.zero);
    });
    _loadSelectedAudioDuration();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _recordingTimer?.cancel();
    _audioPlayer.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.inactive ||
        state == AppLifecycleState.hidden) {
      _pauseForBackground();
    }
  }

  Future<void> _pauseForBackground() async {
    if (!_isPlaying) return;
    _userPausedAudio = true;
    await _audioPlayer.pause();
    if (mounted) {
      setState(() => _isPlaying = false);
    }
  }

  void _startRecordingTimer() {
    _recordingTimer?.cancel();
    _recordingElapsed = Duration.zero;
    _recordingStopwatch
      ..reset()
      ..start();
    _recordingTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted || !_isRecording) return;
      setState(() => _recordingElapsed = _recordingStopwatch.elapsed);
    });
  }

  void _stopRecordingTimer() {
    _recordingStopwatch.stop();
    _recordingTimer?.cancel();
    _recordingTimer = null;
    _recordingElapsed = _recordingStopwatch.elapsed;
  }

  void _resetRecordingTimer() {
    _recordingStopwatch
      ..stop()
      ..reset();
    _recordingTimer?.cancel();
    _recordingTimer = null;
    _recordingElapsed = Duration.zero;
  }

  @override
  void didUpdateWidget(SongPickerScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.selectedSongPath != widget.selectedSongPath) {
      _audioPlayer.stop();
      setState(() {
        _isPlaying = false;
        _userPausedAudio = false;
        _playbackCompleted = false;
        _position = Duration.zero;
        _duration = Duration.zero;
      });
      _loadSelectedAudioDuration();
    }
  }

  Future<void> _loadSelectedAudioDuration() async {
    if (widget.selectedSongPath == null) return;
    final path = widget.selectedSongPath!;
    Duration? nativeDuration;
    try {
      nativeDuration = await _rvcBridge.getAudioDuration(path);
    } catch (_) {
      nativeDuration = null;
    }
    await _audioPlayer.stop();
    await _audioPlayer.setSource(DeviceFileSource(widget.selectedSongPath!));
    _preparedSourcePath = path;
    Duration? duration;
    for (final delay in const [Duration.zero, Duration(milliseconds: 120), Duration(milliseconds: 260)]) {
      if (delay > Duration.zero) {
        await Future.delayed(delay);
      }
      duration = await _audioPlayer.getDuration();
      if (duration != null && duration > Duration.zero) {
        break;
      }
    }
    if (mounted) {
      setState(() => _duration = (nativeDuration != null && nativeDuration > Duration.zero)
          ? nativeDuration
          : (duration ?? Duration.zero));
    }
  }

  Future<void> _pickSong(BuildContext context) async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.audio,
        allowMultiple: false,
      );

      if (result != null && result.files.isNotEmpty) {
        final filePath = result.files.single.path;
        if (filePath != null) {
          await widget.onSongSelected(filePath);
        }
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('选择音频失败：$e')),
      );
    }
  }

  Future<void> _toggleRecording(BuildContext context) async {
    try {
      if (_isRecording) {
        final recordingPath = await _rvcBridge.stopRecording();
        _stopRecordingTimer();
        setState(() => _isRecording = false);
        await widget.onSongSelected(recordingPath);
        await _loadSelectedAudioDuration();
        _resetRecordingTimer();
        return;
      }

      final permission = await Permission.microphone.request();
      if (!permission.isGranted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('需要麦克风权限')),
        );
        return;
      }
      await _rvcBridge.startRecording();
      setState(() => _isRecording = true);
      _startRecordingTimer();
    } catch (e) {
      _resetRecordingTimer();
      setState(() => _isRecording = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('录音失败：$e')),
      );
    }
  }

  Future<void> _playPause() async {
    if (widget.selectedSongPath == null) return;
    if (_isPlaying) {
      _userPausedAudio = true;
      await _audioPlayer.pause();
    } else {
      final selectedPath = widget.selectedSongPath!;
      if (_userPausedAudio && _preparedSourcePath == selectedPath && _position > Duration.zero) {
        _userPausedAudio = false;
        await _audioPlayer.resume();
        return;
      }
      _userPausedAudio = false;
      if (_playbackCompleted) {
        await _audioPlayer.stop();
        await _audioPlayer.setSource(DeviceFileSource(selectedPath));
        _preparedSourcePath = selectedPath;
        await _audioPlayer.play(DeviceFileSource(selectedPath));
        _playbackCompleted = false;
        return;
      }
      if (_preparedSourcePath != selectedPath || _position == Duration.zero) {
        await _audioPlayer.stop();
        await _audioPlayer.setSource(DeviceFileSource(selectedPath));
        _preparedSourcePath = selectedPath;
        await _audioPlayer.play(DeviceFileSource(selectedPath));
        _playbackCompleted = false;
        return;
      }
      await _audioPlayer.resume();
    }
  }

  Future<void> _seek(double milliseconds) async {
    _playbackCompleted = false;
    await _audioPlayer.seek(Duration(milliseconds: milliseconds.round()));
  }

  String _formatDuration(Duration duration) {
    final hours = duration.inHours.toString().padLeft(2, '0');
    final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
    final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
    return '$hours:$minutes:$seconds';
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Scaffold(
      appBar: AppBar(
        title: Text('选择音频'),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24.0),
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.music_note,
                size: 60,
                color: Theme.of(context).primaryColor,
              ),
              SizedBox(height: 32),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    children: [
                      Icon(
                        widget.selectedSongPath == null ? Icons.audio_file : Icons.check_circle,
                        color: widget.selectedSongPath == null ? Colors.grey : Colors.green,
                      ),
                      SizedBox(height: 8),
                      Text(
                        '已选择：',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      Text(
                        widget.selectedSongDisplayName ?? widget.selectedSongPath?.split('/').last ?? '未选择',
                        style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                        textAlign: TextAlign.center,
                      ),
                      SizedBox(height: 8),
                      Text(
                        '支持：MP3、WAV、M4A、FLAC、OGG',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: Colors.grey,
                            ),
                      ),
                      SizedBox(height: 12),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          ElevatedButton.icon(
                            onPressed: () => _pickSong(context),
                            icon: Icon(Icons.folder_open),
                            label: Text(widget.selectedSongPath != null ? '更换音频' : '选择音频'),
                            style: ElevatedButton.styleFrom(
                              padding: EdgeInsets.symmetric(horizontal: 24, vertical: 14),
                            ),
                          ),
                          if (widget.selectedSongPath != null) ...[
                            SizedBox(width: 8),
                            IconButton(
                              onPressed: widget.onSongCleared,
                              icon: Icon(Icons.close),
                              tooltip: '清除音频',
                            ),
                          ],
                        ],
                      ),
                    ],
                  ),
                ),
              ),
              SizedBox(height: 16),
              if (widget.selectedSongPath != null) ...[
                SizedBox(height: 16),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    ElevatedButton.icon(
                      onPressed: _playPause,
                      icon: Icon(_isPlaying ? Icons.pause : Icons.play_arrow),
                      label: Text(_isPlaying ? '暂停' : '播放'),
                    ),
                  ],
                ),
                Slider(
                  value: _position.inMilliseconds.toDouble().clamp(
                        0,
                        _duration.inMilliseconds.toDouble().clamp(1, double.infinity),
                      ),
                  max: _duration.inMilliseconds.toDouble().clamp(1, double.infinity),
                  onChanged: _seek,
                ),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(_formatDuration(_position)),
                    Text(_formatDuration(_duration)),
                  ],
                ),
                SizedBox(height: 16),
              ],
              OutlinedButton.icon(
                onPressed: () => _toggleRecording(context),
                icon: Icon(_isRecording ? Icons.stop : Icons.mic),
                label: Text(_isRecording ? '停止录音' : '直接录音'),
                style: OutlinedButton.styleFrom(
                  padding: EdgeInsets.symmetric(horizontal: 32, vertical: 16),
                ),
              ),
              if (_isRecording) ...[
                SizedBox(height: 12),
                Text(
                  '录音时长：${_formatDuration(_recordingElapsed)}',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: Colors.red,
                        fontWeight: FontWeight.bold,
                      ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  @override
  bool get wantKeepAlive => true;
}
