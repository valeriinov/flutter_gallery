# flutter_gallery_example

Device check for `flutter_gallery`: Pick, Reload, Save asset, Keep asset,
Clear cache, and a grid of thumbnails. Tap a tile to run `hasImage` and
`readOriginal`.

On Android 9 and below grant the save permission before **Save asset**:

```sh
adb shell pm grant com.valeriinov.flutter_gallery_example android.permission.WRITE_EXTERNAL_STORAGE
```
