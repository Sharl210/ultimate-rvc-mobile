import 'package:flutter/material.dart';

class IndexConverterScreen extends StatelessWidget {
  const IndexConverterScreen();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('mobile.index 转换教程'),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _GuideCard(
              title: '为什么要先转换',
              body: '手机端当前只读取 mobile.index。标准 RVC/FAISS .index 需要先在电脑上转换，转换后再传到手机选择。',
            ),
            _GuideCard(
              title: '转换命令',
              body: '在仓库目录执行：\npython3 ultimate_rvc_mobile/python/convert_faiss_index.py input.index output.mobile.index',
            ),
          ],
        ),
      ),
    );
  }
}

class _GuideCard extends StatelessWidget {
  final String title;
  final String body;

  const _GuideCard({required this.title, required this.body});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
            ),
            SizedBox(height: 8),
            Text(body, style: Theme.of(context).textTheme.bodyMedium),
          ],
        ),
      ),
    );
  }
}
