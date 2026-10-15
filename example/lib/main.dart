import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_gallery/flutter_gallery.dart';

void main() {
  runApp(const MaterialApp(home: GalleryScreen()));
}

class GalleryScreen extends StatefulWidget {
  const GalleryScreen({super.key});

  @override
  State<GalleryScreen> createState() => _GalleryScreenState();
}

class _GalleryScreenState extends State<GalleryScreen> {
  static const _sampleAsset = 'assets/sample.jpg';
  static const _pageSize = 30;
  static const _thumbnailSize = 300;

  List<String> _uris = const [];
  String _status = '';

  @override
  void initState() {
    super.initState();
    _onReloadPressed();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('flutter_gallery')),
      body: Column(
        children: [
          Wrap(
            spacing: 8,
            children: [
              FilledButton(
                onPressed: _onPickPressed,
                child: const Text('Pick'),
              ),
              OutlinedButton(
                onPressed: _onReloadPressed,
                child: const Text('Reload'),
              ),
              OutlinedButton(
                onPressed: _onSavePressed,
                child: const Text('Save asset'),
              ),
              OutlinedButton(
                onPressed: _onKeepPressed,
                child: const Text('Keep asset'),
              ),
              OutlinedButton(
                onPressed: _onClearCachePressed,
                child: const Text('Clear cache'),
              ),
            ],
          ),
          Padding(
            padding: const EdgeInsets.all(8),
            child: Text('${_uris.length} images. $_status'),
          ),
          Expanded(
            child: GridView.builder(
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 3,
                mainAxisSpacing: 2,
                crossAxisSpacing: 2,
              ),
              itemCount: _uris.length,
              itemBuilder: (context, index) => _Thumbnail(
                uri: _uris[index],
                size: _thumbnailSize,
                onTap: () => _onThumbnailTapped(_uris[index]),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _onReloadPressed() async {
    await _run('Reloaded', _reload);
  }

  Future<void> _onPickPressed() async {
    await _run('Picked', () async {
      final picked = await FlutterGallery.pickImages();
      _status = 'Picked ${picked.length}';
      await _reload();
    });
  }

  Future<void> _onSavePressed() async {
    await _run('Saved', () async {
      final file = await _writeSample();
      final name =
          'flutter_gallery_${DateTime.now().millisecondsSinceEpoch}.jpg';
      await FlutterGallery.saveImage(file.path, name);
      await _reload();
    });
  }

  Future<void> _onKeepPressed() async {
    await _run('Kept', () async {
      final file = await _writeSample();
      await FlutterGallery.keepImage(file.path);
      await _reload();
    });
  }

  Future<void> _onClearCachePressed() async {
    await _run('Cache cleared', FlutterGallery.clearCache);
  }

  Future<void> _onThumbnailTapped(String uri) async {
    await _run('Checked', () async {
      final hasImage = await FlutterGallery.hasImage(uri);
      final path = await FlutterGallery.readOriginal(uri);
      final length = await File(path).length();
      _status = 'hasImage: $hasImage, original: $length bytes at $path';
    });
  }

  Future<void> _reload() async {
    final uris = <String>[];
    List<String> page = const [];
    do {
      page = await FlutterGallery.loadImages(
        offset: uris.length,
        limit: _pageSize,
      );
      uris.addAll(page);
    } while (page.length == _pageSize);
    _uris = uris;
  }

  Future<File> _writeSample() async {
    final data = await rootBundle.load(_sampleAsset);
    final file = File('${Directory.systemTemp.path}/sample.jpg');

    return file.writeAsBytes(data.buffer.asUint8List());
  }

  Future<void> _run(String done, Future<void> Function() action) async {
    try {
      _status = done;
      await action();
    } on PlatformException catch (e) {
      _status = 'Error ${e.code}: ${e.message}';
    }
    if (mounted) {
      setState(() {});
    }
  }
}

class _Thumbnail extends StatelessWidget {
  final String uri;
  final int size;
  final VoidCallback onTap;

  const _Thumbnail({
    required this.uri,
    required this.size,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: FutureBuilder<Uint8List>(
        future: FlutterGallery.readThumbnail(uri, size),
        builder: (context, snapshot) {
          final bytes = snapshot.data;
          if (snapshot.hasError) {
            return const ColoredBox(
              color: Colors.red,
              child: Icon(Icons.broken_image),
            );
          }
          if (bytes == null) {
            return const ColoredBox(color: Colors.black12);
          }

          return Image.memory(bytes, fit: BoxFit.cover, gaplessPlayback: true);
        },
      ),
    );
  }
}
