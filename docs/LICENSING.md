# Licensing (Phase 2, Step 43)

JARVIS uses **offline signed-key** licensing: license keys are signed with the vendor's private
key and verified in-app with an embedded public key (`java.security.Signature`, `SHA256withRSA`) —
no license server. The activated key is stored **encrypted** in `~/.jarvis/license.dat`
(AES-256/GCM).

**Honest limits (by design):** client-side licensing keeps honest buyers honest and blocks casual
forgery — a signed key can't be faked without the private key, and the license file can't be
casually read or edited. It is **not** unbreakable against someone patching the bytecode. True
revocation and seat limits need online activation, which is deliberately deferred to a later
version.

## Dormant by default
With no embedded public key, the app runs in **DEV** mode: licensing is not enforced and the app
is unlocked. This is the owner's own build. You only turn on enforcement when you ship.

## The locked state (graceful, never crashes)
When enforcement is on and there's no valid license (`UNLICENSED`), or the license has lapsed
(`EXPIRED`), the dashboard shows a full-screen **"Activation required"** overlay with a field to
paste a license key. The agent loop itself is never disrupted — locking is a presentation state, in
keeping with the "keep honest buyers honest" goal.

## Enabling licensing (one-time vendor setup)
1. **Generate a key pair** (keep the private key secret; never commit it):
   ```
   keytool -genkeypair -alias jarvis-license -keyalg RSA -keysize 2048 \
           -keystore jarvis-license.p12 -storetype PKCS12
   ```
   Export the public key as Base64 X.509 and save it to
   `java/app/src/main/resources/license-public-key.b64` (single Base64 line, no PEM header). When
   this resource is present the app enforces licensing automatically.
2. **Issue a key per customer** with `com.jarvis.licensing.LicenseSigner.sign(license, privateKey)`,
   where the `License` is `{licensee, email, edition, issuedAt, expiresAt (null = perpetual)}`. The
   signer returns a compact token (`base64url(payload).base64url(signature)`) — that's the license
   key the customer pastes into the activation overlay.

## Endpoints
- `GET /license` — current status `{state, locked, message, licensee?, edition?, expiresAt?}`.
- `POST /license/activate {key}` — verify + store the key; returns the new status (`INVALID` if the
  key doesn't verify — nothing is stored).
- `POST /license/deactivate` — remove the stored license.

`state` is one of `DEV`, `LICENSED`, `UNLICENSED`, `EXPIRED`, `INVALID`.
