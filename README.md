# Ninja Scan

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
- **In-app PDF viewer** — tap a scan to read it inside the app (platform
  `PdfRenderer`, no external PDF app needed). "Open with…" still hands the
  file to any installed PDF reader.
- **Folders** — add, rename, or delete folders from the chip row under the
  search bar (long-press a chip for rename/delete), move scans into them
  ("Move to folder…"), and filter the library by tapping a chip. Renaming
  onto an existing folder name merges the two; deleting a folder unfiles
  its scans without deleting them.
- **Page editor with watermark** — "Edit pages" opens an editor to
  reorder, rotate, and remove pages, scan additional pages into the
  document, and set an optional diagonal text watermark (with a Clear
  button to remove it in one tap). The watermark text is sized to fit
  fully within the page at any length. The stored PDF stays clean: the
  watermark is stamped on the fly when the document is viewed, shared,
  exported, or uploaded — directly into the rendered pixels, so shared
  copies can't have it stripped out — while staying editable or removable
  in-app at any time. Page changes rebuild the PDF, refresh the thumbnail
  and OCR text, and re-queue the scan for Drive backup.
- **Share in any format** — Share opens a format sheet: PDF, per-page
  JPEG images, one tall "long image", or every page as its own PDF
  (all watermarked when a watermark is set). Shared/exported files are
  named after the document with a "-scan-with-Ninja-Scan-App" suffix,
  and the share sheet includes a promotional caption. Long-press a
  document in the library to select several at once and share or
  delete them together in one action.
- **Viewer action bar** — an open document has Add watermark / Add Scan /
  Share / Edit / Save at the bottom; tap the title in the top bar to
  rename the document; each list row's menu offers Share, Rename, Delete,
  Save to cloud, and Move to folder.
- **Business card search, notes, and tags** — search across every card
  field, including tag names; tap a card to open a full-page editor
  (scanned card image, a taller address box, notes, and colored tags).
  Tags are reusable labels with a title, optional description, and one
  of 11 colors — apply existing tags from a "+ Tag" menu or create new
  ones on the fly, and rename/recolor a tag (via its pencil icon) or
  delete it everywhere it's used. Field extraction
  is layout-aware: ML Kit's per-line bounding boxes are used to split
  side-by-side columns (e.g. a name and job title printed next to each
  other) before the name/company/title heuristics run, and a name line
  with a job-title-shaped line directly below it is preferred over the
  tallest-line fallback (so a large logo doesn't outrank the real name).
- **Name and organize on save** — right after a scan is saved, a dialog
  offers to rename it and file it into a folder (or skip).
- **Originals kept** — the untouched full-resolution captures are stored
  alongside the optimized PDF and used as the quality source for page
  edits, so future enhance/filter passes never work from compressed data.
- **Business card scanner** — the contacts icon opens a card library:
  scan a card and the name, company, title, phone, email, website, and
  address are extracted with on-device OCR into an editable contact.
  Save any card straight into the phone's contacts app, and export the
  whole list as CSV or a real Excel (.xlsx) workbook.
- **Automatic Google Drive backup and restore** — the cloud icon in the
  top bar opens a menu to turn backup on/off or restore from Drive. Every
  scan and business card — including each card's photo — is uploaded by a
  background WorkManager job into a "Ninja Scan" folder in your Drive
  (clean, un-watermarked PDFs, a "cards" subfolder of card photos, and a
  JSON manifest carrying titles, folders, watermark text, OCR text, every
  contact's details, and the tag catalog), and backed-up scans show a
  small cloud check in the list. A backup pass runs immediately after
  every change and also on a 12-hour recurring schedule, so nothing is
  missed even if a change happened while offline. After an uninstall or
  clearing app data, turning backup back on with an empty library offers
  to restore — documents and business cards (with their photos and tags)
  come back with titles, folders, editable watermarks, and searchable OCR
  text intact; restoring is
  duplicate-safe, so running it again — or letting the periodic job run
  repeatedly — never creates copies. Uses the narrow `drive.file` OAuth
  scope, so the app can only ever see files it created itself. Requires a
  one-time OAuth client registration — see below.
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

## Release builds

The debug build above is signed with a fixed, committed debug key and needs no
setup. A signed release `.aab` for the Play Store needs your own upload
keystore and a GitHub Actions secret — see [docs/RELEASE.md](docs/RELEASE.md)
for the full walkthrough, including registering the resulting Play App
Signing key with the Drive OAuth client below.

## Enabling Google Drive backup (one-time setup)

The Drive backup code is complete, but Google requires every app that
requests OAuth scopes to be registered. Do this once (free):

1. Go to <https://console.cloud.google.com> and create a project
   (e.g. "Ninja Scan").
2. **APIs & Services → Library** → search for **Google Drive API** →
   **Enable**.
3. **APIs & Services → OAuth consent screen** → External → fill in the app
   name and your email → add your Google account under **Test users**.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**
   → Application type **Android**:
   - Package name: `com.ninja.scan`
   - SHA-1: print your debug signing certificate with
     `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`
     and copy the SHA1 line.
5. Done — nothing to download or paste into the code. Reinstall the app,
   tap the cloud icon, pick your Google account, and grant access.

If you later sign a release build, add a second Android OAuth client with
the release keystore's SHA-1 — see [docs/RELEASE.md](docs/RELEASE.md#6-register-the-play-app-signing-sha-1-for-google-drive-backup)
for the exact steps once Play App Signing is involved.

## Project structure

```
app/src/main/java/com/ninja/scan/
├── MainActivity.kt          # Scanner launch, share/export/open intents
├── DocScannerApp.kt         # Application, dependency wiring
├── data/
│   ├── ScanDocument.kt      # Room entity
│   ├── ScanDao.kt           # Queries
│   ├── ScanDatabase.kt      # Database singleton
│   └── ScanRepository.kt    # Save/optimize/export/delete logic
├── drive/
│   ├── DriveBackup.kt        # Backup/restore settings, OAuth request, scheduling
│   ├── DriveRestClient.kt    # Drive v3 REST client (upload/list/download/delete)
│   ├── DriveManifest.kt      # JSON manifest: titles, folders, watermark, OCR, cards
│   ├── DriveBackupWorker.kt  # Background uploads + manifest refresh
│   └── DriveRestoreWorker.kt # Downloads the Drive backup back into the library
├── viewer/
│   └── PdfViewerActivity.kt # In-app PDF viewer (PdfRenderer + Compose)
├── ui/
│   ├── ScanListScreen.kt    # Compose library UI
│   ├── ScanViewModel.kt     # State + one-shot events
│   └── theme/Theme.kt       # Material 3 theme
└── util/
    └── ImageOptimizer.kt    # Bounded decode, PDF rebuild, thumbnails
```
