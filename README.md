# PDF Maker Android

Native Android PDF utility app with a UI modeled from the supplied reference screenshots.

## Working features
- Image to PDF (multi-image)
- Smart Scan using the device camera
- Import PDF
- Compress PDF by rebuilding pages at a lower raster resolution
- PDF to JPG (all pages)
- Merge PDF (2+ files)
- Basic DOCX to PDF text conversion
- Local Files screen with page count, size, date, share and delete
- Multi-select UI
- Sort by size/name/date and ascending/descending bottom sheet
- Settings screen and dark mode
- Android 8.0+ (minSdk 26)

## Build APK
Open **Actions → Build Android APK → Run workflow**. After the build completes, download the `PDF-Maker-debug-apk` artifact.

Local build:
```bash
gradle :app:assembleDebug
```
APK output: `app/build/outputs/apk/debug/app-debug.apk`.
