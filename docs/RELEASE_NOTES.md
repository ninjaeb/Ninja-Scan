# Release notes — since versionCode 8

Covers every change on `claude/android-camera-scanner-ni4bo7` since the last
version bump (`19adffd`, "Bump versionCode to 8 for the next Play Console
upload"). versionCode is still 8 / versionName 1.1 — no new version bump is
included here, since this batch hasn't been uploaded to Play Console yet.

## Play Store "What's new" (short version)

- Scan ID cards — front and back, true size, rounded corners
- Print documents straight from the viewer
- Import an existing PDF, or photos, into your library instead of only scanning
- New About screen: share the app, join our WhatsApp community, visit our website
- Business cards now show clearly whether they're backed up to Google Drive or still waiting

## Full changelog

### New features
- **ID card scanning**: scan the front and back of an ID card onto a single
  printable page, rendered at true physical card size (ISO/IEC 7810) with
  rounded corners, and with watermarks sized to the card itself instead of
  the whole page.
- **Import PDF/images**: add an existing PDF or photos straight into
  Documents, or a photo into Business Cards, instead of only camera-scanning.
  Documents gained a single "Add to library" button covering every way to
  add something (scan or import).
- **Print**: a Print button in the document viewer sends the document to
  Android's native print dialog.
- **About screen**: a new third tab with a short app intro, a button to
  share Ninja Scan via any app, a link to join the WhatsApp community, and
  a link to the website, plus a short changelog.

### Improvements
- Business card rows now show a muted "pending" cloud icon while Drive
  backup is on but that card's photo hasn't synced yet, instead of showing
  nothing at all — so "no icon" no longer means "not sure if it's synced".

### Bug fixes
- Fixed the ID card watermark rendering far larger than the card itself,
  spilling into the blank margin around it.
