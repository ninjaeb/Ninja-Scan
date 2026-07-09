# Release notes — since versionCode 7

Covers every change merged on `claude/android-camera-scanner-ni4bo7` since the
last version bump (`854fffe`, "Bump versionCode to 7 for the next Play Console
upload"). versionCode is still 7 / versionName 1.1 — no new version bump is
included here, since this batch hasn't been uploaded to Play Console yet.

## Play Store "What's new" (short version)

- Long-press a document or card to select multiple for sharing or deleting
- Swipe between Documents and Cards
- Select individual pages in the viewer to share or export separately
- Save a business card straight to phone Contacts, tags included
- Colored icons throughout the app for faster visual scanning
- Fixed Google Drive backup sometimes getting stuck with no success/failure message
- General UI polish and bug fixes

## Full changelog

### New features
- **Multi-select + batch actions**: long-press a document or business card to
  enter selection mode, pick several, and share or delete them together.
  Cards gained the same checkbox multi-select and batch-delete flow Documents
  already had.
- **Page-level selection in the viewer**: long-press a page to select a
  subset, then export/share just those pages as a PDF, images, one long
  image, or separate PDFs — the same four options as sharing a whole
  document.
- **Swipe navigation**: swipe between the Documents and Cards screens
  (either direction toggles between them), with the screen transition
  continuing the swipe motion instead of cutting abruptly.
- **Tag filter chips on Cards**: filter the card list by tag, with
  create/rename/delete supported directly from the chip row.
- **Save to Contacts, with tags**: a "save to phone contacts" button now
  lives directly on the card detail screen, and tags are written into the
  saved contact's notes as a labeled "Tags: …" line.
- **Long-press discoverability hints**: a small dismissible hint teaches the
  long-press-to-select gesture on Documents, Cards, and the page viewer.
- **Colored action icons app-wide**: delete, share, edit, add, move-to-folder
  and related actions now use consistent colors (red/blue/amber/green/indigo)
  instead of uniform gray, including in popup/overflow menus, which now show
  icons next to each action.

### Improvements
- Light theme is now the default, and the theme toggle stays in sync between
  Documents and Cards.
- Folder is now shown as a colored tag under each document row.
- Business card contact fields (call, WhatsApp, email, website, maps) are
  tappable with color-coded icons; phone numbers are normalized before
  dialing.
- "Share as PDF" and "Export as separate PDFs" filenames are now prefixed
  with `By-Ninja-Scan-App-`.
- The "Save to cloud" menu action is now labeled just "Save".
- Increased list bottom padding so the last row isn't hidden behind the
  floating action buttons.

### Bug fixes
- **Drive backup could silently retry forever**: several failure paths in
  the backup worker had no retry cap, so a persistent failure (auth, folder
  resolution, uploads) could retry for hours with no success or failure
  message ever shown. Every failure path now shares one capped retry helper,
  so a real failure surfaces within about one backoff cycle instead of
  never.
- **Watermark dialog**: the instructional text was rendering as placeholder
  text inside the input field (filling it with a wall of text); it now shows
  as a caption above the empty field.
- Fixed a Drive "cards" subfolder id going silently stale if the parent
  backup folder was ever recreated (now validated by real parentage, not
  just trashed-status).
- Fixed a compile error from importing `matchParentSize()`, which is a
  `BoxScope` member function and not importable at the top level.
