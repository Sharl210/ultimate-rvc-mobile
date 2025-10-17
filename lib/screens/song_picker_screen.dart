import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';

class SongPickerScreen extends StatelessWidget {
  final Function(String) onSongSelected;
  final String? selectedSongPath;

  const SongPickerScreen({
    required this.onSongSelected,
    this.selectedSongPath,
  });

  Future<void> _pickSong(BuildContext context) async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.audio,
        allowMultiple: false,
      );

      if (result != null && result.files.isNotEmpty) {
        final filePath = result.files.single.path;
        if (filePath != null) {
          onSongSelected(filePath);
        }
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Failed to pick song: $e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Select Song'),
        centerTitle: true,
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                Icons.music_note,
                size: 120,
                color: Theme.of(context).primaryColor,
              ),
              SizedBox(height: 32),
              Text(
                'Choose an Audio File',
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                textAlign: TextAlign.center,
              ),
              SizedBox(height: 16),
              Text(
                'Select a song or audio file to convert to a different voice',
                style: Theme.of(context).textTheme.bodyLarge,
                textAlign: TextAlign.center,
              ),
              SizedBox(height: 32),
              if (selectedSongPath != null) ...[
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Column(
                      children: [
                        Icon(Icons.check_circle, color: Colors.green),
                        SizedBox(height: 8),
                        Text(
                          'Selected:',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        Text(
                          selectedSongPath!.split('/').last,
                          style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                                fontWeight: FontWeight.bold,
                              ),
                          textAlign: TextAlign.center,
                        ),
                      ],
                    ),
                  ),
                ),
                SizedBox(height: 16),
              ],
              ElevatedButton.icon(
                onPressed: () => _pickSong(context),
                icon: Icon(Icons.folder_open),
                label: Text(
                  selectedSongPath != null ? 'Change Song' : 'Choose Song',
                ),
                style: ElevatedButton.styleFrom(
                  padding: EdgeInsets.symmetric(horizontal: 32, vertical: 16),
                ),
              ),
              SizedBox(height: 16),
              Text(
                'Supports: MP3, WAV, M4A, FLAC, OGG',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Colors.grey,
                    ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}