import 'package:flutter/material.dart';

class ParameterGuideScreen extends StatelessWidget {
  const ParameterGuideScreen();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('参数解释'),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: const [
            _ParameterCard(
              title: 'Pitch（音调设置）',
              recommended: '推荐值：0',
              body: '改变整体音高，范围 -24 到 24 半音。调高适合男声转女声或目标声音更高；调低适合女声转男声或目标声音更低。优先在 -4 到 +4 半音内微调，幅度太大容易失真。',
            ),
            _ParameterCard(
              title: 'Formant（性别因子/声线粗细）',
              recommended: '推荐值：0',
              body: '通过共振峰偏移改变声线粗细和性别感，范围 -4 到 4。调高更细、更亮；调低更粗、更厚。它和 Pitch 可以共存：Pitch 调音调高低，Formant 调声线粗细。',
            ),
            _ParameterCard(
              title: 'Index Rate（检索特征占比）',
              recommended: '推荐值：0.5 到 0.7，稳妥从 0.6 开始',
              body: '控制检索特征参与程度。调高更像目标音色，但更容易带出底噪和毛刺；调低更干净稳定，但相似度会下降。',
            ),
            _ParameterCard(
              title: 'Filter Radius（音高滤波）',
              recommended: '推荐值：3',
              body: '平滑音高波动，减少抖动、破音和细碎毛刺。调高会更平稳，但可能抹掉细节；调低会保留更多细节，但更容易抖。',
            ),
            _ParameterCard(
              title: 'RMS Mix（响度因子）',
              recommended: '推荐值：0.25',
              body: '控制输出保留多少原音的响度起伏。调高更平稳；调低更保留原始情绪和力度。原音底噪明显时不要调得太低。',
            ),
            _ParameterCard(
              title: 'Protect（辅音保护）',
              recommended: '推荐值：0.33',
              body: '保护齿音、呼吸声和清辅音，减少发糊和吞字。调高更清晰但不够像；调低更像目标音色，但更容易糊。',
            ),
            _ParameterCard(
              title: 'Noise Gate（噪声过滤）',
              recommended: '音频推理推荐值：0；实时推理推荐值：35 dB',
              body: '只在处理时生效，不改原始录音文件。0 表示不过滤；更适合压低麦克风底噪、空窗噪声和残留低电平杂音。门限过高会吞掉气声、尾音和弱音，断续时先降低 Noise Gate。',
            ),
            _ParameterCard(
              title: '降噪优化',
              recommended: '推荐值：默认开启',
              body: '处理时输入和输出都生效，不改原始录音文件。用于压低底噪、突刺、电流感、包络噪声和刺耳齿音，尽量保住尾音与弱音；如果人声被压得太干，可以关闭。',
            ),
            _ParameterCard(
              title: '音域过滤',
              recommended: '推荐值：默认开启；保留区：60–2500 Hz',
              body: '只在处理时生效，不改原始录音文件。50–60 Hz 以下轻收低频轰鸣；60–2500 Hz 保持主体；2500–4500 Hz 保留咬字清晰度并逐步收高频；4500 Hz 以上用柔和高架衰减压毛刺，避免声音发闷。二次录制的人声会带扬声器、房间和麦克风噪声，这个开关用于清理明显越界的低频轰鸣和高频毛刺。',
            ),
            _ParameterCard(
              title: 'Sample Rate（采样率）',
              recommended: '推荐值：48000',
              body: '决定导出音频规格。默认使用 48000，便于和模型输出及常见视频音频流程保持一致。',
            ),
            _ParameterCard(
              title: '采样长度',
              recommended: '推荐值：6.0',
              body: '实时推理每次处理的音频块长度。调大更稳但延迟更高；调小延迟更低，但更容易卡顿或音色不稳定。',
            ),
            _ParameterCard(
              title: '淡入淡出长度',
              recommended: '推荐值：0.0',
              body: '实时推理块之间的衔接长度。调高能减少断裂和爆音，但会增加拖尾；调低响应更快，但更容易听到接缝。',
            ),
            _ParameterCard(
              title: '额外推理时长',
              recommended: '推荐值：0.0',
              body: '给实时推理保留的上下文长度。调高更稳定，但会增加延迟和计算量；设备性能不足时应适当降低。',
            ),
            _ParameterCard(
              title: '延时缓冲',
              recommended: '推荐值：10 秒；范围：0 到 60 秒',
              body: '实时推理会先缓存一段输出再播放。调高更稳但延迟更长；调到 0 会尽快播放，设备性能不足时更容易断续。',
            ),
            _ParameterCard(
              title: '播放延迟',
              recommended: '推荐值：3 秒；范围：0 到 10 秒',
              body: '变声器悬浮窗在正常播放和试听前显示倒计时。0 秒表示立即播放；需要留出切回目标应用或准备录入环境时可调高。',
            ),
            _ParameterCard(
              title: '中断生成',
              recommended: '需要停止时手动触发',
              body: '离线生成时可点红色“终止生成”。变声器橙色处理阶段可长按悬浮按钮中断。',
            ),
          ],
        ),
      ),
    );
  }
}

class _ParameterCard extends StatelessWidget {
  final String title;
  final String recommended;
  final String body;

  const _ParameterCard({
    required this.title,
    required this.recommended,
    required this.body,
  });

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
            Text(recommended, style: Theme.of(context).textTheme.bodyMedium),
            SizedBox(height: 8),
            Text(body, style: Theme.of(context).textTheme.bodyMedium),
          ],
        ),
      ),
    );
  }
}
