## 0.1.2

* Sizes system thumbnails on Android 10 and above to cover the requested square.

## 0.1.1

* Keeps a picked image's URI in memory when its grant cannot persist.
* Lists images page by page without probing the whole list.
* Sizes thumbnails to cover the requested square.
* Removes the MediaStore row a failed save inserted on Android 9 and below.
* Lowers the Dart SDK constraint to ^3.12.2.

## 0.1.0

* Android system picker for images with persisted read access.
* The plugin's own image list, newest first: picked, saved and kept images.
* Thumbnails, original copies, `hasImage`, `saveImage`, `keepImage`, `clearCache`.
