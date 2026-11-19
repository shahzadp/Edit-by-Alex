# Edit by Alex

Offline gallery and photo editor for Android. Kotlin + Jetpack Compose, no
network calls, no ads.

## Features

- **Tap-to-remove background** — on-device interactive segmentation (MediaPipe,
  bundled model, nothing leaves the phone)
- Colour grading and global adjustments
- Layers with blend controls, draw layer and crop
- EXIF-aware export (orientation normalised; no GPS re-embedded)
- **Storage gallery** — folder-first browser for everything on the device
  (grouped strictly by bucket id; sortable Recent / Alphabetical / Size /
  Custom), with in-place playback, folder creation, and move/copy/delete to
  the system Trash — long-press to select

Everything happens on-device. There is no analytics, no account, no upload.
The single permission is `MANAGE_EXTERNAL_STORAGE` (full file access): the
app browses, moves, copies and deletes storage files directly, and does not
start without it. The claim to check is simply that no bytes leave the phone.

## Build

```powershell
$env:GRADLE_USER_HOME = "D:\Dev\BuildTools\gradle"
$env:JAVA_HOME = "<your JDK 21 path>"
.\gradlew.bat assembleRelease     # requires a signing keystore (see below)
.\gradlew.bat assembleDebug       # no signing setup needed
.\gradlew.bat testDebugUnitTest
```

Release signing reads `keystore.properties` (git-ignored) or `-D` system
properties; the build fails loudly rather than silently shipping a
debug-signed "release". Create your own keystore; no signing material is
committed to this repository.

## Status

v1.1 — gallery-first redesign. Signed APKs are attached to
[GitHub Releases](https://github.com/shahzadp/Edit-by-Alex/releases).

## License

All rights reserved, copyright 2026 shahzadp. See the repository history for
what changed and when.
