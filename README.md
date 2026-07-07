# Doc Scanner

A CamScanner-style Android document scanner. Capture documents with the
camera, get automatic edge detection and cropping, and store the result as a
high-quality, cloud-optimized PDF that's easy to back up to Google Drive,
Dropbox, or any other cloud provider.

## Features

- **Camera scanning with auto-crop** — powered by Google's ML Kit Document
  Scanner (`SCANNER_MODE_FULL`): live edge detection with auto-capture,
  automatic perspective correction and cropping (with manual adjustment),
  and cleanup filters (shadow removal, stain removal, grayscale,
  auto-enhance). Multi-page scanning up to 50 pages, plus gallery import.
- **High quality, optimized for the cloud** — pages are captured at full
  camera resolution, then bounded to ~300 DPI (2480 px longest side) and
  re-encoded as JPEG quality 85 into a single PDF. That keeps text
  crisp and OCR-ready while typically shrinking uploads by 3–10×. If the
  scanner's own PDF happens to be smaller, the smaller file is kept.
- **Digital library** — every scan is stored on-device with a thumbnail,
  page count, file size, and date, backed by a Room database. Rename,
  open, and delete from the list.
- **Full-text search (OCR)** — each page is run through ML Kit's on-device
  text recognition when a scan is saved, and the search bar matches both
  titles and the recognized document text.
- **Cloud storage**
  - *Save to cloud*: exports the PDF through the Android document picker
    (Storage Access Framework), so you can drop it straight into Google
    Drive, OneDrive, Dropbox, or any provider that registers with Android.
  - *Share*: sends the PDF to any app via the system share sheet.
  - Scans and the library database are included in Android's automatic
    cloud backup (`data_extraction_rules`), so documents survive device
    migration.

## Tech stack

| Layer | Choice |
| --- | --- |
| Language / UI | Kotlin, Jetpack Compose (Material 3, dynamic color) |
| Scanning | ML Kit Document Scanner (`play-services-mlkit-document-scanner`) |
| Storage | Room + app-private `files/scans/` directory |
| PDF | `android.graphics.pdf.PdfDocument` rebuild from optimized pages |
| Images | Coil (thumbnails), sub-sampled bitmap decoding |

No app-level camera permission is required — the ML Kit scanning activity
handles capture itself, and the scanner module is delivered by Google Play
services (requested at install time via the manifest metadata).

## Building

Open the project in Android Studio (Ladybug or newer) and run the `app`
configuration, or build from the command line:

```sh
./gradlew :app:assembleDebug
```

Requirements: JDK 17, Android SDK 34. A device/emulator with Google Play
services is needed for the scanner itself (API 26+).

## Project structure

```
app/src/main/java/com/eugeneboon/docscanner/
├── MainActivity.kt          # Scanner launch, share/export/open intents
├── DocScannerApp.kt         # Application, dependency wiring
├── data/
│   ├── ScanDocument.kt      # Room entity
│   ├── ScanDao.kt           # Queries
│   ├── ScanDatabase.kt      # Database singleton
│   └── ScanRepository.kt    # Save/optimize/export/delete logic
├── ui/
│   ├── ScanListScreen.kt    # Compose library UI
│   ├── ScanViewModel.kt     # State + one-shot events
│   └── theme/Theme.kt       # Material 3 theme
└── util/
    └── ImageOptimizer.kt    # Bounded decode, PDF rebuild, thumbnails
```
