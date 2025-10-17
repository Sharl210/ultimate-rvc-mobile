import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';

class ModelPickerScreen extends StatelessWidget {
  final Function(String) onModelSelected;
  final String? selectedModelPath;
  final String? selectedSongPath;

  const ModelPickerScreen({
    required this.onModelSelected,
    this.selectedModelPath,
    this.selectedSongPath,
  });

  Future<void> _pickModel(BuildContext context) async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.any,
        allowMultiple: false,
        allowedExtensions: ['pth', 'index'],
      );

      if (result != null && result.files.isNotEmpty) {
        final filePath = result.files.single.path;
        if (filePath != null) {
          onModelSelected(filePath);
        }
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Failed to pick model: $e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Select Voice Model'),
        centerTitle: true,
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                Icons.mic,
                size: 120,
                color: Theme.of(context).primaryColor,
              ),
              SizedBox(height: 32),
              Text(
                'Choose a Voice Model',
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                textAlign: TextAlign.center,
              ),
              SizedBox(height: 16),
              Text(
                'Select a trained voice model (.pth or .index file)',
                style: Theme.of(context).textTheme.bodyLarge,
                textAlign: TextAlign.center,
              ),
              SizedBox(height: 32),
              if (selectedModelPath != null) ...[
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Column(
                      children: [
                        Icon(Icons.check_circle, color: Colors.green),
                        SizedBox(height: 8),
                        Text(
                          'Selected Voice:',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        Text(
                          selectedModelPath!.split('/').last,
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
                onPressed: () => _pickModel(context),
                icon: Icon(Icons.folder_open),
                label: Text(
                  selectedModelPath != null ? 'Change Model' : 'Choose Model',
                ),
                style: ElevatedButton.styleFrom(
                  padding: EdgeInsets.symmetric(horizontal: 32, vertical: 16),
                ),
              ),
              SizedBox(height: 24),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Current Song:',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      Text(
                        selectedSongPath?.split('/').last ?? 'None selected',
                        style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                      ),
                    ],
                  ),
                ),
              ),
              SizedBox(height: 16),
              Text(
                'Supported: .pth, .index model files',
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