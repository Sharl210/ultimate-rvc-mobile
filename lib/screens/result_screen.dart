import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:share_plus/share_plus.dart';

import '../services/rvc_bridge.dart';

class ResultScreen extends StatefulWidget {
  final String outputPath;
  final Duration? generationDuration;
  final VoidCallback onNewGeneration;

  const ResultScreen({
    required this.outputPath,
    this.generationDuration,
    required this.onNewGeneration,
  });

  @override
  State<ResultScreen> createState() => _ResultScreenState();
}

class _ResultScreenState extends State<ResultScreen> with WidgetsBindingObserver {
  final AudioPlayer _audioPlayer = AudioPlayer();
  final RVCBridge _rvcBridge = RVCBridge();
  bool _isPlaying = false;
  bool _userPausedAudio = false;
  bool _playbackCompleted = false;
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _requestSavePermission();
    _loadOutputAudioDuration();
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
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
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
    _isPlaying = false;
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _playPause() async {
    if (_isPlaying) {
      _userPausedAudio = true;
      await _audioPlayer.pause();
    } else {
      _userPausedAudio = false;
      if (_playbackCompleted) {
        await _audioPlayer.stop();
        await _audioPlayer.setSource(DeviceFileSource(widget.outputPath));
        await _audioPlayer.play(DeviceFileSource(widget.outputPath));
        _playbackCompleted = false;
        return;
      }
      await _audioPlayer.resume();
    }
  }

  Future<void> _share() async {
    try {
      await Share.shareXFiles(
        [XFile(widget.outputPath)],
        subject: 'Ultimate RVC 生成音频',
        text: '这是 Ultimate RVC 生成的音频。',
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('分享失败：$e')),
      );
    }
  }

  Future<void> _requestSavePermission() async {
    final audioPermission = await Permission.audio.request();
    if (audioPermission.isGranted || audioPermission.isLimited) {
      return;
    }
    await Permission.storage.request();
  }

  Future<void> _seek(double milliseconds) async {
    _playbackCompleted = false;
    await _audioPlayer.seek(Duration(milliseconds: milliseconds.round()));
  }

  Future<void> _loadOutputAudioDuration() async {
    _playbackCompleted = false;
    Duration? nativeDuration;
    try {
      nativeDuration = await _rvcBridge.getAudioDuration(widget.outputPath);
    } catch (_) {
      nativeDuration = null;
    }
    await _audioPlayer.setSource(DeviceFileSource(widget.outputPath));
    final duration = await _audioPlayer.getDuration();
    if (mounted) {
      setState(() => _duration = (nativeDuration != null && nativeDuration > Duration.zero)
          ? nativeDuration
          : (duration ?? Duration.zero));
    }
  }

  String _formatDuration(Duration duration) {
    final hours = duration.inHours.toString().padLeft(2, '0');
    final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
    final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
    return '$hours:$minutes:$seconds';
  }

  Future<void> _save() async {
    try {
      await _requestSavePermission();
      final savedPath = await _rvcBridge.saveGeneratedAudio(widget.outputPath);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('已保存：$savedPath')),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('保存失败：$e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('生成结果'),
        centerTitle: true,
      ),
      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  children: [
                    Icon(Icons.check_circle, color: Colors.green, size: 48),
                    SizedBox(height: 16),
                    Text(
                      '音频已生成',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    SizedBox(height: 8),
                    Text(
                      widget.outputPath.split('/').last,
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.bodyMedium,
                    ),
                    if (widget.generationDuration != null) ...[
                      SizedBox(height: 8),
                      Text('生成用时：${_formatDuration(widget.generationDuration!)}'),
                    ],
                  ],
                ),
              ),
            ),
            SizedBox(height: 16),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                ElevatedButton.icon(
                  onPressed: _playPause,
                  icon: Icon(_isPlaying ? Icons.pause : Icons.play_arrow),
                  label: Text(_isPlaying ? '暂停' : '播放'),
                ),
                ElevatedButton.icon(
                  onPressed: _share,
                  icon: Icon(Icons.share),
                  label: Text('分享'),
                ),
                ElevatedButton.icon(
                  onPressed: _save,
                  icon: Icon(Icons.save_alt),
                  label: Text('保存'),
                ),
              ],
            ),
            SizedBox(height: 16),
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
            Spacer(),
            OutlinedButton.icon(
              onPressed: widget.onNewGeneration,
              icon: Icon(Icons.refresh),
              label: Text('重新生成'),
            ),
          ],
        ),
      ),
    );
  }
}
