# AndroidFileWifi

An Android gallery for photos and videos that sends selected items or whole folders to your Windows PC over Wi-Fi,
plus a small PC receiver app. See [PLAN.md](PLAN.md) for the design.

```
shared/        protocol DTOs, path sanitizer, QR pairing link, and PcClient (the phone's HTTPS client)
android-app/   the gallery + sender (Kotlin, Jetpack Compose, WorkManager)
desktop-app/   the receiver (Kotlin, Compose Desktop, Ktor HTTPS server, mDNS, system tray)
```

## Using it

1. Install and start the PC app. It shows a **QR code**, a 6-digit **code** and a **Security ID**.
   If Windows Firewall blocks the phone, press **Allow** in the yellow banner (one UAC prompt).
2. On the phone open FileWifi → PC icon → **Scan QR code**. (Or pick the PC under "Found on this Wi-Fi" and type the
   code, or "Enter address manually" and compare the Security ID.)
3. Browse folders, long-press to select. Then tap the send icon.

Selecting:

| Gesture | Result |
|---|---|
| Long-press an item | start selecting (sets the anchor) |
| Tap | toggle an item |
| **Long-press a second item** | selects everything between the anchor and it |
| **Range** chip, then tap first and last | same, using plain taps |
| Long-press + drag | drag-select, with auto-scroll at the screen edges |
| Tap a date header | select / deselect that whole day |
| Long-press a folder | multi-select folders, then send them whole |

"Send to PC" also appears in Android's share sheet.

Files arrive in `<receive folder>\<phone name>\<album>\` with their original dates. Files already there are skipped,
different files with the same name get `name (1).jpg`, and interrupted transfers resume from where they stopped.

## Building

Requires JDK 17+ (Android Studio's bundled JBR works) and the Android SDK (`local.properties` with `sdk.dir`).

```bash
./gradlew :android-app:assembleDebug     # android-app/build/outputs/apk/debug/android-app-debug.apk
./gradlew :desktop-app:run               # run the PC app
./gradlew :shared:test :desktop-app:test :android-app:testDebugUnitTest
```

Windows installer. `jpackage` needs a full JDK (not the Android Studio JBR), e.g.:

```bash
JAVA_HOME="C:/Program Files/Android/openjdk/jdk-21.0.8" ./gradlew :desktop-app:packageMsi
# -> desktop-app/build/compose/binaries/main/msi/AndroidFileWifi-1.0.0.msi
# ./gradlew :desktop-app:createDistributable gives a portable folder instead
```

The Gradle plugin downloads WiX by itself. "Start with Windows" works from the installed app only.
`./gradlew :desktop-app:generateIcon` regenerates `desktop-app/packaging/icon.ico`.

## Security model

* The PC creates a self-signed certificate on first run; the phone **pins its SHA-256 fingerprint**, taken from the QR
  code or from mDNS. No CA, and no unencrypted traffic.
* Pairing needs the 6-digit code. It is single-use, and five wrong guesses replace it. The phone then gets a random
  bearer token; the PC stores only its hash. "Remove" on the PC revokes a phone.
* All paths from the phone are sanitized (no `..`, drive letters or reserved names) and re-checked against the
  receive folder.
* Only paired phones can send; the HTTPS server is reachable by anyone on your LAN, so keep the PC on a private network.

## Status and known limits

* Everything is implemented and unit/integration tested on the JVM: selection logic, protocol, the real HTTPS server
  driven by the phone's own client (pairing, pinning, resume, parallel uploads, path safety, revocation).
* The Android app was run on an Android 35 emulator: browsing, range select, manual pairing (Security ID check),
  sending a folder and the photo viewer all work, and received files were byte-identical to the originals.
  **Not yet tested on a real phone or emulator:** QR scanning, automatic discovery (mDNS; the emulator can't see the
  PC's network), videos, drag-select with auto-scroll, and the progress notification.
  To pair from the emulator choose "Enter address manually" and use `10.0.2.2`.
* Shares from other apps (share sheet) are read via temporary URI grants, so keep FileWifi open until the transfer ends.
* The whole media library is loaded into memory (fine for tens of thousands of items; paging is future work).
* Phone → PC only. Browsing the phone from the PC is not implemented.

## License

MIT, see [LICENSE](LICENSE).
