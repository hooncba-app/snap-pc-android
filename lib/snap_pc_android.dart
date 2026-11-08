library;

import 'package:flutter/services.dart';

class SnapPcAndroid {
  static const _channel = MethodChannel('snap_and_sort/pc_flutterflow_v1');

  static Future<String?> pickFile(int limit) =>
      _channel.invokeMethod<String>('pickFile', {'limit': limit});

  static Future<bool?> wifiActive() =>
      _channel.invokeMethod<bool>('wifiActive');

  static Future<Map<String, Object?>?> originalProof(
          Map<String, Object?> row) =>
      _channel.invokeMapMethod<String, Object?>('originalProof', row);
}
