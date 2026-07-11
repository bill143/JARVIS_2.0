# Update checking (Phase 2, Step 42)

JARVIS checks for updates on startup against a **static, signed JSON manifest** and, if a newer
version is available, shows a notice in the HUD status bar with a download link. It is
**notify-only** — nothing downloads or installs itself (cleanly replacing a running installed
`.exe` on Windows is genuinely fiddly; that's deferred).

**Dormant by default.** Out of the box there is no manifest URL and no public key, so the check
reports `DISABLED` and never touches the network. Turning it on is a vendor action.

## How the trust works
- The vendor holds an RSA key pair. The **public** key is embedded in the app; the **private**
  key stays secret and is used to sign each release manifest.
- The app fetches the manifest, verifies its `SHA256withRSA` signature with the embedded public
  key, and only reports an update if the manifest is **both** signature-valid **and** strictly
  newer than the running version. A tampered or wrongly-signed manifest is ignored (`UNVERIFIED`).

## Enabling updates (one-time vendor setup)
1. **Generate a key pair** (keep `private.pem` secret; never commit it):
   ```
   keytool -genkeypair -alias jarvis-updates -keyalg RSA -keysize 2048 \
           -keystore jarvis-updates.p12 -storetype PKCS12
   ```
   Export the public key as Base64 X.509 and save it to
   `java/app/src/main/resources/update-public-key.b64` (single Base64 line, no PEM header). When
   this resource is present the app builds a verifier from it automatically.
2. **Sign each release manifest** with `com.jarvis.updater.ManifestSigner.sign(manifest, privateKey)`.
   The manifest is `{version, downloadUrl, notes}`; the signer emits the hosted JSON with a
   `signature` field. Example produced JSON:
   ```json
   {
     "version": "0.2.0",
     "downloadUrl": "https://downloads.example.com/JARVIS-0.2.0.msi",
     "notes": "What's new in 0.2.0…",
     "signature": "<base64>"
   }
   ```
3. **Host** that JSON at a stable HTTPS URL.
4. **Point the app at it** by setting the environment variable before launch:
   ```
   setx JARVIS_UPDATE_URL "https://downloads.example.com/jarvis-latest.json"
   ```

## What the user sees
- Up to date → nothing (quiet).
- Update available → a status-bar link: `⬆ Update v0.2.0 available` → opens the download URL.
- Check failed / unverified → nothing intrusive; the state is visible at `GET /update`.

## Bumping the app's own version
`AppWiring.APP_VERSION` is the running version the check compares against. Keep it in sync with
the packaging `--app-version` (`packaging/package-windows.ps1`) on each release.
