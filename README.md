# Smart Files

Android file manager: browse/edit/delete files, copy/move, recycle bin, junk & duplicate cleaner,
unused-app finder, and WhatsApp sticker re-add. Kotlin + Jetpack Compose, Android 11+.

## What's new in 1.4
- Files: folders load in the background (no more freezes in big folders), image thumbnails, hidden files toggle, select all, rename/new folder never overwrite existing files, and long copy/move/delete jobs run with a progress bar.
- Open/Share now work for files on SD cards and USB drives.
- Bin: one Bin per storage volume (deleting from an SD card is instant), shows size and days left, and a failed move can no longer lose data.
- Clean: scan results survive tab switches and rotation, backup/`.old` files are no longer pre-selected, duplicate scan is much faster on big videos, and you can choose "To Bin" or "Forever".
- Apps: new apps aren't flagged as unused, search box, and total space you could reclaim.
- Dark mode starts dark (no white flash), edge-to-edge layout, bottom bar with icons, Back returns to the Files tab.

## Get the APK
- **Actions tab → latest "Build APK" run → Artifacts → SmartFiles-apk** (download the zip, unzip, install `SmartFiles.apk`), or
- push a tag like `v1.4` and download `SmartFiles.apk` from the **Releases** page.

On the phone: allow "Install unknown apps", then grant "All files access" (and "Usage access" for the Apps tab) on first launch.

## Build locally
Open the folder in Android Studio, or run `gradle assembleDebug` (Gradle 8.9, JDK 17).

## Where the changed files go
- `MainActivity.kt`, `Extras.kt` → `app/src/main/java/com/example/smartfiles/`
- `AndroidManifest.xml` → `app/src/main/`
- `paths.xml` → `app/src/main/res/xml/`
- `themes.xml` (new) → `app/src/main/res/values/`
- `build.gradle.kts` → `app/`
