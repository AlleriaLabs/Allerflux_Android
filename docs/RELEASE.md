# Allerflux Android release

The repository includes a signed release workflow at `.github/workflows/android-release.yml`. It builds version 0.6.0 and publishes a GitHub Release when a `v*` tag is pushed.

## One-time signing setup

Create and securely store a release keystore. Keep a private backup; losing the key prevents signing compatible future updates.

Example command (run locally on a trusted computer):

```bash
keytool -genkeypair -v -keystore allerflux-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias allerflux
```

On Windows PowerShell, create the base64 value without adding line breaks:

```powershell
$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("allerflux-release.jks"))
Set-Clipboard $base64
```

Paste that clipboard value into `ALLERFLUX_KEYSTORE_BASE64` in GitHub Actions secrets. Add these repository Actions secrets:

- `ALLERFLUX_KEYSTORE_BASE64` — base64-encoded contents of the keystore file.
- `ALLERFLUX_KEYSTORE_PASSWORD` — keystore password.
- `ALLERFLUX_KEY_ALIAS` — key alias (for the example above, `allerflux`).
- `ALLERFLUX_KEY_PASSWORD` — key password.

Do not commit the keystore or passwords. Do not change signing keys between releases.

## Build and publish

- Run the **Android signed release** workflow manually to build and download the `allerflux-release-apk` artifact.
- Push a tag such as `v0.6.0` to build the signed APK and publish a GitHub Release with generated notes.

The workflow intentionally fails if signing secrets are missing. The existing Android debug workflow remains available for development builds. A debug APK and a signed release APK use different signing identities; Android generally will not install one over the other without uninstalling first, which removes app-local data. Back up connection settings before switching installation channels.

## Release validation

Before calling a release production-ready, install it on a physical Android device, verify encrypted credential migration, connect to the companion, test report CSV/PDF Save As, download a database backup, and confirm update installation using the same signing key.
