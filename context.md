# Ninja Scan — Project Context

A quick-reference brief on what this app is, why it exists, and what it does —
for onboarding a new contributor, an AI agent, or anyone who needs the whole
picture without reading the codebase.

## What it does

Ninja Scan is an Android app (Kotlin + Jetpack Compose, Material 3) that turns
a phone camera into two tools in one:

1. **A document scanner.** Point the camera at a paper document, and Google
   ML Kit's Document Scanner auto-detects the edges, corrects perspective,
   crops it, and cleans it up (shadow/stain removal, grayscale,
   auto-enhance). Multi-page documents are combined into a single,
   cloud-optimized, OCR'd, searchable PDF stored in an on-device library.
2. **A business card scanner and contact manager.** Point the camera at a
   business card, and on-device OCR extracts name, company, job title,
   phone, email, website, and address into an editable contact — searchable,
   taggable, exportable to the phone's native Contacts app or to CSV/Excel.

Both halves share the same underlying app: a library screen (Documents /
Cards, swipeable between the two), an in-app PDF viewer, folders and tags for
organizing, and automatic Google Drive backup for both scans and cards.

## What problem it solves

- **Paper piles up, and phones already have a camera good enough to digitize
  it** — but doing that well (auto-crop, perspective correction, a real
  searchable PDF, not just a photo) needs more than the stock camera app.
- **Popular scanner apps monetize aggressively** — ads, forced watermarks
  on free output, and export formats locked behind a subscription. Ninja
  Scan has no ads and no forced watermark; the diagonal watermark is
  entirely optional and user-controlled, meant for the user's own branding,
  not a paywall gate.
- **Business cards get collected, then lost or never entered anywhere** —
  most document scanners don't handle cards as a distinct, structured data
  type. Ninja Scan extracts real contact fields (not just an image) and can
  drop a scanned card straight into the phone's Contacts app.
- **Scanned data is easy to lose** — a lost/wiped/replaced phone shouldn't
  mean losing every scanned document and business card. Automatic,
  duplicate-safe Google Drive backup and restore covers both, using the
  narrowest OAuth scope Google offers (`drive.file` — the app can only ever
  see files it created itself).
- **Scanned documents are usually not searchable** — a photo of a document
  is opaque to search. On-device OCR makes both the document text and the
  card's fields searchable from the library's search bar.

## Unique selling points

- **Free, no ads, no forced watermark** — the core differentiator from
  CamScanner-style competitors.
- **Two scanners in one app** sharing one polished UI, rather than a
  document scanner that treats business cards as just another photo.
- **Layout-aware card field extraction** — ML Kit's per-line bounding boxes
  are used to split side-by-side columns (e.g. a name and job title printed
  next to each other) before heuristics run, so cards with unusual layouts
  parse more accurately than a naive top-to-bottom text read would.
- **Privacy-conscious cloud backup** — Drive backup uses the `drive.file`
  scope specifically so the app is structurally unable to see any Drive file
  it didn't create.
- **Granular export** — share or export any subset of pages (not just the
  whole document) as a PDF, per-page images, one tall long image, or
  separate PDFs per page.
- **Fast bulk workflows** — long-press multi-select with checkboxes on both
  Documents and Cards, for batch share/delete instead of one-at-a-time.
- **Small UX details that add up**: swipe navigation between Documents and
  Cards with a continuing-motion transition, colored action icons for fast
  visual scanning, folders and reusable colored tags, and discoverability
  hints that teach gestures instead of hiding them in a menu.

## Functions / features

**Document scanning & library**
- Camera capture with live auto-crop, auto-capture, manual adjustment, and
  cleanup filters; multi-page (up to 50 pages) plus gallery import
- Full camera-resolution originals kept alongside an optimized PDF (~300 DPI,
  JPEG 85) so later edits never work from lossy data
- On-device library: thumbnail, page count, size, date; rename/open/delete
- Full-text search across titles and OCR'd document text
- In-app PDF viewer (no external app needed), with "Open with…" as a fallback
- Folders (create/rename/merge/delete, filter by chip) and per-document
  folder tag shown inline
- Page editor: reorder, rotate, remove pages, add more scanned pages, and set
  an optional diagonal watermark (stamped on render, editable/removable
  anytime, never baked into the stored file)
- Long-press multi-select with checkboxes for batch share/delete
- Long-press a page in the viewer to select a subset for export
- Share/export as PDF, per-page images, one long image, or separate PDFs —
  for the whole document or a selected page subset
- Swipe left/right to move between Documents and Cards

**Business cards**
- Camera capture → OCR extraction of name, company, job title, phone, email,
  website, address into an editable card
- Full-page card editor with a scanned-card thumbnail and all fields
- Reusable colored tags (create/rename/recolor/delete), filterable chip row
- Search across every field, including tag names
- Tappable contact actions: call, WhatsApp, email, open website, open in Maps
- Save a card straight to the phone's Contacts app (tags included in notes)
- Export the whole card list as CSV or a real Excel (.xlsx) workbook
- Long-press multi-select with checkboxes for batch delete

**Cloud & sync**
- Automatic Google Drive backup (scans, business cards + card photos, and a
  JSON manifest of titles/folders/watermark/OCR text/tags) via a background
  WorkManager job, on every change and a 12-hour recurring schedule
- Duplicate-safe restore after uninstall/data clear — safe to re-run or let
  the periodic job repeat
- Per-item "backed up" cloud indicator in both lists
- Manual "Back up now" and "Restore from Drive" actions
- Save-to-cloud via Android's Storage Access Framework (Drive, OneDrive,
  Dropbox, or any registered provider) as an alternative to Drive backup
- Android's automatic cloud backup covers the local database as a fallback

**Polish**
- Material 3 theming with light/dark toggle (light by default), synced
  across screens
- Colored, consistent action icons (delete/share/edit/add/etc.) instead of
  uniform gray, including in overflow menus
- Dismissible long-press discoverability hints per screen

## Tech stack

Kotlin, Jetpack Compose (Material 3), Room (local database), Google ML Kit
(Document Scanner + on-device text recognition), Google Drive REST v3,
WorkManager (background backup/restore), Coil (image loading).

See `README.md` for build instructions and the full Drive OAuth setup, and
`docs/RELEASE.md` / `docs/RELEASE_NOTES.md` for release process and
changelog.
