import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ultimate_rvc_mobile/main.dart';
import 'package:ultimate_rvc_mobile/screens/model_picker_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('ultimate_rvc');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'initialize':
          return true;
        case 'checkModels':
          return true;
        default:
          return null;
      }
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('main screen starts on song picker without null assertion', (tester) async {
    await tester.pumpWidget(UltimateRVCMobileApp());
    await tester.pump();

    expect(find.text('已选择：'), findsOneWidget);
    expect(find.text('选择音频'), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('main screen does not block startup with model download dialog', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'initialize':
          return true;
        case 'checkModels':
          return false;
        default:
          return null;
      }
    });

    await tester.pumpWidget(UltimateRVCMobileApp());
    await tester.pump();

    expect(find.text('下载 AI Models'), findsNothing);
    expect(find.text('已选择：'), findsOneWidget);
    expect(find.text('选择音频'), findsWidgets);
  });

  testWidgets('model picker shows required model and optional index sections', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: ModelPickerScreen(
          onModelSelected: (_) {},
          onIndexSelected: (_) {},
          onContinue: () {},
          selectedSongPath: '/tmp/demo.wav',
          selectedModelPath: '/tmp/voice.onnx',
        ),
      ),
    );

    expect(find.text('voice.onnx'), findsOneWidget);
    expect(find.text('索引文件（可选）'), findsOneWidget);
    expect(find.text('未选择'), findsOneWidget);
  });
}
