# Building a signed release bundle

Ninja Scan's `release` build type has no signing key configured out of the box —
only the `debug` build (signed with the fixed `debug.keystore` committed to this
repo) builds and installs with zero setup. To upload a build to Google Play you need
a real signing key and a way to build a signed `.aab` with it. This repo does that
through a manual GitHub Actions workflow, so the private key never has to leave your
own machine.

## 1. Generate an upload keystore, on your own machine

Run this locally — not in any shared or cloud environment:

```
keytool -genkeypair -v -keystore upload-keystore.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

`keytool` will prompt for a keystore password, a key password, and your
name/organization details. **Save the `.jks` file and both passwords somewhere
durable** (a password manager, encrypted backup) — losing them means losing the
ability to publish updates through this upload key.

## 2. Base64-encode it

```
base64 -w0 upload-keystore.jks > upload-keystore.b64          # Linux
base64 -i upload-keystore.jks | tr -d '\n' > upload-keystore.b64   # macOS
```

## 3. Add four GitHub repo secrets

In your repo: **Settings → Secrets and variables → Actions → New repository secret**.
Do this straight from your browser — never paste the `.jks` file itself into a chat
or a session; only the base64 text goes into `RELEASE_KEYSTORE_BASE64` below.

| Secret name                  | Value                                      |
|-------------------------------|---------------------------------------------|
| `RELEASE_KEYSTORE_BASE64`    | contents of `upload-keystore.b64`           |
| `RELEASE_KEYSTORE_PASSWORD`  | the keystore password you set in step 1     |
| `RELEASE_KEY_ALIAS`          | `upload` (or whatever alias you chose)      |
| `RELEASE_KEY_PASSWORD`       | the key password you set in step 1          |

## 4. Run the release workflow

Actions tab → **Android Release** → **Run workflow**. When it finishes, download the
`app-release-aab` artifact — that's your signed `app-release.aab`.

The workflow only builds on manual trigger (`workflow_dispatch`), never on a push or
PR, so nothing signs automatically on every commit. It decodes the keystore into a
git-ignored `app/keystore.properties` + `app/release.keystore` for the duration of the
build only, and deletes both immediately after (`app/build.gradle.kts` picks these up
automatically when present — see the `hasReleaseSigning` check near the top of the
`android {}` block; without them, `bundleRelease` still builds, just unsigned, exactly
as it does today).

## 5. Upload to Play Console and turn on Play App Signing

Upload that first `.aab` in Play Console, and opt into **Play App Signing** when
prompted — Google then holds the real distribution key and re-signs your app for
users, while you keep using your upload key for every future release.

## 6. Register the Play App Signing SHA-1 for Google Drive backup

Ninja Scan's Google Drive backup authorizes through an Android-type OAuth client in
Google Cloud Console, matched by package name (`com.ninja.scan`) + the SHA-1 of
whatever certificate signs the installed app. Once Play re-signs your app, that's a
**different** certificate than your upload key or the debug key.

1. Play Console → your app → **Setup → App signing** → copy the **App signing key
   certificate**'s SHA-1.
2. Google Cloud Console → your project's OAuth **Android** client for
   `com.ninja.scan` → add that SHA-1 (alongside the existing debug one).

Skip this and Drive backup will silently fail to authorize on the Play-distributed
build, even though everything else works.
