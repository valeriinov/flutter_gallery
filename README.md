# flutter_gallery

Android-only Flutter plugin: the images the user picked in the system picker
and the images the app created itself, read without `READ_MEDIA_*` or
`READ_EXTERNAL_STORAGE`.

## API

All methods are static on `FlutterGallery`; images are addressed by URI strings.

| method | does |
|---|---|
| `pickImages()` | opens the system picker (`PickMultipleVisualMedia`, images only), persists read access, adds the picked URIs to the top of the list; an image the picker grants no persistable access to (the Google Play services picker) is listed as a kept copy |
| `loadImages(offset:, limit:)` | a page of URIs, newest first; deleted or revoked images are dropped from the list first |
| `readThumbnail(uri, size)` | JPEG bytes covering a `size` square, rotated upright |
| `readOriginal(uri)` | path of a copy in `cacheDir/flutter_gallery/` |
| `hasImage(uri)` | the URI is in the list and still opens; removes nothing |
| `saveImage(path, fileName)` | inserts the file into MediaStore under `Pictures/GoGoBag`, adds it to the list |
| `keepImage(path)` | copies the file into `filesDir/flutter_gallery/`, adds it to the list; at most 100 copies, the oldest are deleted |
| `clearCache()` | deletes the `readOriginal` copies |

Errors are `PlatformException`s with `code`: `picker_open`, `no_activity`,
`not_found`, `io_error`.

## Host app

- `minSdk` 24. The plugin depends on `androidx.activity:activity:1.9.0`.
- For the picker backport on Android 10 and below, declare the Google Play
  services `ModuleDependencies` service (see `example/android/app/src/main/AndroidManifest.xml`).
- `saveImage` on Android 9 and below needs `WRITE_EXTERNAL_STORAGE`
  (`maxSdkVersion="28"`) granted at runtime; it writes into `Pictures` and
  lists a kept copy, since the app reads no MediaStore row back there without
  `READ_EXTERNAL_STORAGE`.

## Install

```yaml
dependencies:
  flutter_gallery:
    git:
      url: https://github.com/valeriinov/flutter_gallery
      ref: 0.1.0
```

## Example

`example/` picks, reloads, saves and keeps a bundled image, and shows the list
as a grid. Tap a tile to run `hasImage` and `readOriginal`.

```sh
cd example && fvm flutter run
```
