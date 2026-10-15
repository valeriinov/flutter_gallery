import 'package:flutter/services.dart';
import 'package:flutter_gallery/flutter_gallery.dart';
import 'package:flutter_test/flutter_test.dart';

const channel = MethodChannel('flutter_gallery');
final calls = <MethodCall>[];

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  void mockHandler(Future<Object?> Function(MethodCall call) handler) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, handler);
  }

  void mockReply(Object? reply) {
    mockHandler((call) async {
      calls.add(call);

      return reply;
    });
  }

  tearDown(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  group('FlutterGallery', () {
    test('pickImages returns the picked URIs', () async {
      mockReply(['content://a', 'content://b']);

      final uris = await FlutterGallery.pickImages();

      expect(uris, ['content://a', 'content://b']);
      expect(calls.single.method, 'pickImages');
    });

    test('pickImages returns an empty list when nothing comes back', () async {
      mockReply(null);

      expect(await FlutterGallery.pickImages(), isEmpty);
    });

    test('loadImages sends offset and limit', () async {
      mockReply(['content://a']);

      final uris = await FlutterGallery.loadImages(offset: 20, limit: 10);

      expect(uris, ['content://a']);
      expect(calls.single.method, 'loadImages');
      expect(calls.single.arguments, {'offset': 20, 'limit': 10});
    });

    test('readThumbnail sends uri and size', () async {
      final bytes = Uint8List.fromList([1, 2, 3]);
      mockReply(bytes);

      final thumbnail = await FlutterGallery.readThumbnail('content://a', 200);

      expect(thumbnail, bytes);
      expect(calls.single.method, 'readThumbnail');
      expect(calls.single.arguments, {'uri': 'content://a', 'size': 200});
    });

    test('readOriginal returns the copy path', () async {
      mockReply('/cache/flutter_gallery/a.jpg');

      final path = await FlutterGallery.readOriginal('content://a');

      expect(path, '/cache/flutter_gallery/a.jpg');
      expect(calls.single.method, 'readOriginal');
      expect(calls.single.arguments, {'uri': 'content://a'});
    });

    test('hasImage returns the platform answer', () async {
      mockReply(true);

      final hasImage = await FlutterGallery.hasImage('content://a');

      expect(hasImage, isTrue);
      expect(calls.single.method, 'hasImage');
      expect(calls.single.arguments, {'uri': 'content://a'});
    });

    test('saveImage sends path and file name', () async {
      mockReply('content://media/1');

      final uri = await FlutterGallery.saveImage('/tmp/a.jpg', 'a.jpg');

      expect(uri, 'content://media/1');
      expect(calls.single.method, 'saveImage');
      expect(calls.single.arguments, {
        'path': '/tmp/a.jpg',
        'fileName': 'a.jpg',
      });
    });

    test('keepImage sends the path', () async {
      mockReply('file:///files/flutter_gallery/a.jpg');

      final uri = await FlutterGallery.keepImage('/tmp/a.jpg');

      expect(uri, 'file:///files/flutter_gallery/a.jpg');
      expect(calls.single.method, 'keepImage');
      expect(calls.single.arguments, {'path': '/tmp/a.jpg'});
    });

    test('clearCache calls the platform', () async {
      mockReply(null);

      await FlutterGallery.clearCache();

      expect(calls.single.method, 'clearCache');
    });

    test('a platform error surfaces as PlatformException with its code', () {
      mockHandler((call) async {
        throw PlatformException(code: 'picker_open');
      });

      expect(
        FlutterGallery.pickImages(),
        throwsA(
          isA<PlatformException>().having((e) => e.code, 'code', 'picker_open'),
        ),
      );
    });
  });
}
