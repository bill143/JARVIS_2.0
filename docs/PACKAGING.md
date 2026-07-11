# Packaging JARVIS as a native Windows app (Phase 2, Step 41)

This is the "sellable desktop app" milestone: turn the fat‑jar into a native Windows
installer with a **bundled private JRE**, so end users never install Java.

Everything here uses the JDK's own `jpackage` (bundled with JDK 21 — **no new dependency**),
with Launch4j as a lighter fallback. Build artifacts land in `java/packaging/dist/`
(git‑ignored).

## Primary path — signed `.msi` via jpackage

**Where it runs:** a **Windows** build machine. A `.msi` can only be produced on Windows —
`jpackage --type msi` shells out to the **WiX Toolset**, which is Windows‑only. (On Linux/macOS
`jpackage` builds `.deb`/`.rpm`/`.pkg` instead — see the cross‑platform validation below.)

**Prerequisites**
- JDK 21+ on `PATH` (gives you `jpackage` + `jlink`).
- [WiX Toolset v3](https://wixtoolset.org/) with `candle.exe`/`light.exe` on `PATH`.
- To sign: `signtool.exe` (Windows SDK) on `PATH` + your OV/EV code‑signing `.pfx`.

**Build (from the `java\` directory)**
```powershell
powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1
```
Produces `packaging\dist\JARVIS-0.1.0.msi` with a bundled JRE, Start‑menu entry, and desktop
shortcut (per‑user install, no admin required).

**Build + sign** (decision **D4** — required before any public distribution)
```powershell
powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1 `
    -Sign -CertPath C:\certs\jarvis.pfx -CertPassword ******
```
The script signs and timestamps the `.msi` with SHA‑256. Without `-Sign` it prints a clear
"UNSIGNED" warning — packaging is designed around a signed artifact, the cert is the only
missing piece.

**Optional:** drop a `packaging\jarvis.ico` in place and it's picked up automatically as the
app/installer icon; otherwise jpackage uses its default.

## Fallback — single `.exe` via Launch4j

Use only if WiX can't be installed on the build machine. Config: `packaging/launch4j.xml`.
```powershell
launch4jc packaging\launch4j.xml    # -> packaging\dist\jarvis.exe
```
**Honest limitation:** the Launch4j `.exe` is **not** self‑contained. It looks for a JRE 21 in
a `runtime\` folder next to the exe (drop a `jlink` runtime there to bundle one), else falls
back to a system‑installed Java 21, else points the user at the download URL. Lighter, but not
the clean "no Java required" experience the `.msi` gives.

## Cross‑platform validation (CI / dev, any OS)

You can't build a `.msi` off Windows, but you **can** validate the whole jpackage
configuration — main class, jar, and the jlink‑bundled runtime — by building an *app‑image*:
```bash
packaging/package-appimage.sh            # -> packaging/dist/JARVIS/
packaging/dist/JARVIS/bin/JARVIS         # runs the packaged app with its bundled JRE
```
If the app‑image launches and serves the dashboard, the `.msi` build (same jpackage inputs)
will too — only the WiX wrapper differs.

## Honest notes
- **Installer size:** ~150–250 MB. The app is classpath‑based, so jpackage bundles a full JRE.
  Trimming with `--add-modules` is a future optimization (risking a missing‑module runtime error),
  deliberately not done in v1.
- **Signing is not optional for release.** An unsigned installer triggers SmartScreen warnings.
  The OV/EV certificate (~$200–400/yr) is the D4 cost; the build is already signing‑ready.
- **Auto‑update is out of scope here** (Step 42, `updater`): v1 is notify‑and‑download, because
  cleanly overwriting a running installed `.exe` on Windows is genuinely fiddly.
