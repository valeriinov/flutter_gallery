import 'package:flutter/services.dart';

/// Images the user picked in the Android system picker and images the app
/// created itself, read without any media permission.
///
/// Every image is addressed by its URI string. Failures surface as a
/// [PlatformException] whose `code` is one of:
/// - `picker_open`: [pickImages] was called while the picker is open;
/// - `no_activity`: the plugin has no foreground activity;
/// - `not_found`: the image is deleted or access to it is gone;
/// - `io_error`: reading, decoding or writing failed.
abstract final class FlutterGallery {
  static const MethodChannel _channel = MethodChannel('flutter_gallery');

  /// Opens the system picker for images and returns the URIs the user
  /// picked, already added to the top of the list. Returns an empty list
  /// when the user cancels.
  static Future<List<String>> pickImages() async {
    final uris = await _channel.invokeListMethod<String>('pickImages');

    return uris ?? const [];
  }

  /// Returns a page of image URIs, newest first.
  ///
  /// The [offset] parameter is the number of images to skip.
  /// The [limit] parameter is the maximum number of URIs returned.
  static Future<List<String>> loadImages({
    required int offset,
    required int limit,
  }) async {
    final uris = await _channel.invokeListMethod<String>('loadImages', {
      'offset': offset,
      'limit': limit,
    });

    return uris ?? const [];
  }

  /// Returns JPEG bytes of a thumbnail no larger than [size] on each side,
  /// rotated upright.
  ///
  /// The [uri] parameter is an image URI from the list.
  /// The [size] parameter is the longest side of the thumbnail, in pixels.
  static Future<Uint8List> readThumbnail(String uri, int size) async {
    final bytes = await _channel.invokeMethod<Uint8List>('readThumbnail', {
      'uri': uri,
      'size': size,
    });

    return bytes ?? Uint8List(0);
  }

  /// Copies the original image into the plugin cache and returns the copy's
  /// file path. [clearCache] deletes the copies.
  ///
  /// The [uri] parameter is an image URI from the list.
  static Future<String> readOriginal(String uri) async {
    final path = await _channel.invokeMethod<String>('readOriginal', {
      'uri': uri,
    });

    return path ?? '';
  }

  /// Returns whether [uri] is in the list and still opens. Removes nothing.
  static Future<bool> hasImage(String uri) async {
    final hasImage = await _channel.invokeMethod<bool>('hasImage', {
      'uri': uri,
    });

    return hasImage ?? false;
  }

  /// Saves the file at [path] into the shared `Pictures/GoGoBag` folder
  /// under [fileName], adds it to the top of the list and returns its URI.
  ///
  /// On Android 9 and below the app needs `WRITE_EXTERNAL_STORAGE`; the file
  /// goes to `Pictures` and the list holds a copy, as [keepImage] does.
  static Future<String> saveImage(String path, String fileName) async {
    final uri = await _channel.invokeMethod<String>('saveImage', {
      'path': path,
      'fileName': fileName,
    });

    return uri ?? '';
  }

  /// Copies the file at [path] into the app's private storage, adds it to
  /// the top of the list and returns its URI. The oldest kept copies are
  /// deleted past the plugin's limit.
  static Future<String> keepImage(String path) async {
    final uri = await _channel.invokeMethod<String>('keepImage', {
      'path': path,
    });

    return uri ?? '';
  }

  /// Deletes the copies made by [readOriginal].
  static Future<void> clearCache() {
    return _channel.invokeMethod<void>('clearCache');
  }
}
