import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';

class ModelPickerScreen extends StatelessWidget {
  final Function(String) onModelSelected;
  final VoidCallback? onModelCleared;
  final Function(String?) onIndexSelected;
  final VoidCallback onContinue;
  final String? selectedModelPath;
  final String? selectedModelDisplayName;
  final String? selectedIndexPath;
  final String? selectedIndexDisplayName;
  final String? selectedSongPath;
  final String? selectedSongDisplayName;

  const ModelPickerScreen({
    required this.onModelSelected,
    this.onModelCleared,
    required this.onIndexSelected,
    required this.onContinue,
    this.selectedModelPath,
    this.selectedModelDisplayName,
    this.selectedIndexPath,
    this.selectedIndexDisplayName,
    this.selectedSongPath,
    this.selectedSongDisplayName,
  });

  Future<void> _pickModel(BuildContext context) async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.any,
        allowMultiple: false,
      );

      if (result != null && result.files.isNotEmpty) {
        final filePath = result.files.single.path;
        if (filePath != null && _isSupportedModelFile(filePath)) {
          onModelSelected(filePath);
        } else if (filePath != null) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('请选择 .onnx 模型文件')),
          );
        }
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('选择模型失败：$e')),
      );
    }
  }

  Future<void> _pickIndex(BuildContext context) async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.any,
        allowMultiple: false,
      );

      if (result != null && result.files.isNotEmpty) {
        final filePath = result.files.single.path;
        if (filePath != null && _isSupportedIndexFile(filePath)) {
          onIndexSelected(filePath);
        } else if (filePath != null) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('请选择 mobile.index 文件')),
          );
        }
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('选择索引失败：$e')),
      );
    }
  }

  bool _isSupportedModelFile(String filePath) {
    final lowerPath = filePath.toLowerCase();
    return lowerPath.endsWith('.onnx');
  }

  bool _isSupportedIndexFile(String filePath) {
    return filePath.toLowerCase().endsWith('.mobile.index');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('选择音色模型'),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24.0),
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.mic,
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
                        selectedModelPath == null ? Icons.description : Icons.check_circle,
                        color: selectedModelPath == null ? Colors.grey : Colors.green,
                      ),
                      SizedBox(height: 8),
                      Text(
                        '已选择音色：',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      Text(
                        selectedModelDisplayName ?? selectedModelPath?.split('/').last ?? '未选择',
                        style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                        textAlign: TextAlign.center,
                      ),
                      SizedBox(height: 8),
                      Text(
                        '支持：.onnx 模型',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Colors.grey),
                      ),
                      SizedBox(height: 12),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          ElevatedButton.icon(
                            onPressed: () => _pickModel(context),
                            icon: Icon(Icons.folder_open),
                            label: Text(selectedModelPath != null ? '更换模型' : '选择模型'),
                            style: ElevatedButton.styleFrom(
                              padding: EdgeInsets.symmetric(horizontal: 24, vertical: 14),
                            ),
                          ),
                          if (selectedModelPath != null) ...[
                            SizedBox(width: 8),
                            IconButton(
                              onPressed: onModelCleared,
                              icon: Icon(Icons.close),
                              tooltip: '清除模型',
                            ),
                          ],
                        ],
                      ),
                    ],
                  ),
                ),
              ),
              SizedBox(height: 16),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    children: [
                      Text(
                        '索引文件（可选）',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      SizedBox(height: 8),
                      Text(
                        selectedIndexDisplayName ?? selectedIndexPath?.split('/').last ?? '未选择',
                        style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                              fontWeight: FontWeight.bold,
                        ),
                        textAlign: TextAlign.center,
                      ),
                      SizedBox(height: 8),
                      Text(
                        '支持：mobile.index 索引',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: Colors.grey,
                            ),
                      ),
                      SizedBox(height: 12),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          OutlinedButton.icon(
                            onPressed: () => _pickIndex(context),
                            icon: Icon(Icons.folder_open),
                            label: Text(selectedIndexPath != null ? '更换 mobile.index' : '选择 mobile.index'),
                          ),
                          if (selectedIndexPath != null) ...[
                            SizedBox(width: 8),
                            IconButton(
                              onPressed: () => onIndexSelected(null),
                              icon: Icon(Icons.close),
                              tooltip: '清除索引',
                            ),
                          ],
                        ],
                      ),
                    ],
                  ),
                ),
              ),
              SizedBox(height: 16),
              ElevatedButton.icon(
                onPressed: selectedModelPath != null ? onContinue : null,
                icon: Icon(Icons.arrow_forward),
                label: Text('继续'),
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
                        '当前音频：',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      Text(
                        selectedSongDisplayName ?? selectedSongPath?.split('/').last ?? '未选择',
                        style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
