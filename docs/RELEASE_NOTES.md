# Release notes — versionCode 13 (versionName 1.5)

Covers every change since the versionCode 11 bump (`740f1c0`).

## Play Store "What's new" (short version)

- Documents now use tags instead of folders, just like Business Cards
- Combining a card's front and back now adds it as a new page in the same document instead of a separate one
- Delete just the pages you select from inside a document
- Fixed Drive restore not working correctly after reinstalling the app
- More reliable restore and unlock when a duplicate backup folder exists in Drive
- Smaller Drive backups, with no extra loss in quality

## Full changelog

### New features
- **Document tags replace folders**: Documents now use a tag model exactly
  like Business Cards, including its own separate tag catalog. Existing
  folders are migrated automatically to identically-colored tags on first
  upgrade, applied to the same documents, so no organization is lost.
- **Combine-to-ID-card now appends in place**: selecting a card's front and
  back pages and combining them adds the composited card as a new final
  page of that same document instead of creating a separate document. The
  original two pages are kept untouched and can be removed afterward.
- **Delete selected pages**: the document viewer's long-press page
  selection gained a Delete action next to Share, for removing just the
  pages you've selected.

### Improvements
- Backup files are now noticeably smaller: generated PDFs embed each
  page's already-JPEG-compressed bytes directly instead of Android's
  lossless re-encode, shrinking scans 10-30x with no additional quality
  loss (a composited ID card page alone dropped from ~15 MB).
- Backup image quality was retuned: scanned pages raised slightly to
  quality 88, and business card photos now get their own dedicated
  1280px/quality-85 capture instead of reusing the small list thumbnail.
- Removed the tag description field from the create/edit tag dialog (both
  Documents and Business Cards) for a simpler flow, and unified the two
  screens onto one shared dialog implementation.
- The business card row's tag submenu gained a "Create Tag" entry and is
  no longer disabled when no tags exist yet.
- Now targets Android 16 (API 36), keeping the app compliant with the
  Play Store's minimum target API requirement.

### Bug fixes
- Fixed Drive restore silently failing to bring your data back after an
  uninstall/reinstall: Android's own OS-level Auto Backup was restoring a
  stale, unusable copy of this app's local encryption key ahead of its own
  Drive restore. That OS-level backup path is now excluded entirely — this
  app's encrypted Drive backup is the sole source of truth.
- Fixed restore and recovery-code unlock only checking one Drive backup
  folder when a duplicate "Ninja Scan" folder existed, which could pick a
  stale copy with no cards, folders, or tags, or reject a correct recovery
  code. Both now check every duplicate folder.
- Fixed the business card detail screen's "Add to contacts" button label
  being truncated to "Add to".
- Fixed business card address parsing occasionally doubling up commas.

---

# Release notes — versionCode 11 (versionName 1.3)

Covers every change since the versionCode 10 bump (`6029802`).

## Play Store "What's new" (short version)

Everything from 1.2 below, plus:

- Fixed backups occasionally restoring without business cards, folders, and tags
- Restoring again now also repairs a library restored while that bug was present
- Folder colors are now kept through backup and restore
- Fixed the status bar clock/icons being invisible in light mode
- Fixed the business card editor's bottom buttons overlapping the phone's navigation bar

## Full changelog

### Bug fixes
- **Restore reliability**: repeated backups could leave duplicate manifest
  files in the Drive folder; a restore that happened to read a stale one
  silently came back with documents only — no business cards, folders,
  folder assignments, or tags. Restore now tries every manifest copy
  (newest first) until one decrypts and decodes, backup sweeps stray
  duplicates after each successful upload, and re-running restore heals a
  library previously restored without its metadata (fill-only, so local
  edits are never overwritten).
- Fixed the system status bar and navigation bar icons being invisible in
  light mode — the icon color never adapted to the in-app theme toggle.
- Fixed the business card editor's bottom Save/contacts buttons drawing
  behind the phone's system navigation bar, and shortened the wrapping
  "Save to phone contacts" label to "Add to contacts".

### Improvements
- Folder chip colors are now carried in the backup manifest, so restored
  folders keep the colors you knew them by instead of being re-assigned
  arbitrary ones.

---

# Release notes — versionCode 10 (versionName 1.2)

Covers every change on `claude/android-camera-scanner-ni4bo7` since the last
version bump (`da43187`, "Bump versionCode to 9 for the next Play Console
upload").

## Play Store "What's new" (short version)

- Google Drive backups are now encrypted with a private recovery key only you hold
- Unlock Ninja Scan with your fingerprint or face
- Combine a card's front and back pages into one ID-card-formatted page, right from the document viewer
- Add tags to a business card straight from its menu
- Business card editor: Save and Save to contacts now also at the bottom of the screen
- Fixed the Documents tab in About/Cards sometimes landing on the wrong screen
- Fixed business cards not showing their Drive backup status

## Full changelog

### New features
- **Encrypted Drive backups**: every scan, business card photo, and manifest
  uploaded to Google Drive is now encrypted (AES-256-GCM) before it leaves
  the device. There's no password to remember — a random recovery key is
  generated on first setup instead, shown once with one-tap Copy and Share,
  and re-viewable anytime from the Drive menu ("View recovery key") as long
  as this device still has it cached. If the key is ever lost with no saved
  copy, that backup can't be decrypted again — nobody, including us, holds
  a spare copy.
- **Biometric app lock**: Ninja Scan now asks for a fingerprint or face
  unlock when it opens, on any device that already has biometrics set up
  (skipped automatically otherwise). It only asks once per app launch — not
  again just from switching apps or backgrounding mid-session — and can be
  turned off from a new toggle in the About screen.
- **Combine into an ID card**: open any document, long-press to select two
  pages (a card's front and back scanned as separate pages), and a new
  action splices them into one ID-card-formatted page — front on top, back
  below, each at true card size with rounded corners — right in place of
  those two pages. Every other page of the document is kept untouched.
- **Add tag from the card list**: a business card's "⋮" menu gained an
  "Add tag" option, so tags can be applied without opening the full card
  editor.

### Improvements
- The business card editor's Save and Save-to-contacts actions are now also
  available as full-width buttons at the bottom of the screen, in addition
  to (not instead of) the existing header icon/button.
- The document viewer's Save button is gone — Save as PDF/images are now
  extra options at the bottom of the Share sheet, so Share is the one place
  to get a document out of the app in any form.
- Recovery-key dialogs (view/generate/enter) now use the full screen width
  instead of Material's narrower default, since the long code reads better
  with more room.
- The recovery key's Copy and Share buttons now sit below the code box
  instead of beside it, and sharing uses a generic chooser so it reliably
  works with WhatsApp, email, or any other app — not just email clients.
- The Drive menu gained a one-tap "Finish backup setup" item, shown whenever
  backup is turned on but this device hasn't generated or entered a
  recovery key yet.

### Bug fixes
- Fixed the About and Cards screens' "Documents" tab sometimes landing back
  on whichever screen you'd come from instead of Documents itself.
- Fixed backup silently doing nothing — including new folders never
  syncing — when Drive backup was already on but no recovery key had been
  set up on that device. This is now surfaced as a clear message instead of
  failing invisibly.
- Fixed business cards with a long, two-line company/address subtitle
  hiding their Drive sync (cloud) icon entirely.
- Fixed the recovery key's Share/Copy text occasionally looking different
  from what was shown on screen (a real character in the key could collide
  with the cosmetic grouping separator).
- Fixed ID card scans/combines showing a raw, sharp-cornered photo in the
  Documents list preview instead of the actual rounded card layout.
