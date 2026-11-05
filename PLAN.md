# AndroidFileWifi: Plan

> **Status:** M0–M6 implemented (see [README.md](README.md) for usage, build steps and known limits).
> Tested on an emulator (gallery, selection, pairing, sending); QR scan, discovery and video still need a real phone.

Two apps that work together:

1. **Android app**: a gallery-style browser for photos and videos. Lets you multi-select items (including range select), browse folders, and send items or whole folders to a PC over Wi-Fi.
2. **PC app (Windows)**: a small receiver. It runs in the tray, shows a pairing code, receives files into a folder you choose, and shows progress.

---

## 1. Tech stack (recommended)

| Part | Choice | Why |
|---|---|---|
| Language (both apps) | **Kotlin** | One language. Protocol code is shared between both apps. |
| Android UI | Jetpack Compose + Material 3 | Modern standard. Lazy grids handle large galleries well. |
| Android media access | `MediaStore` (images + video) | Fast, already indexed, no broad storage permission needed |
| Thumbnails | Coil 3 (+ `coil-video` for video frames) | Caches, decodes efficiently, Compose-native |
| Video playback | Media3 ExoPlayer | Standard player |
| Background transfer | WorkManager foreground worker + notification | Survives app backgrounding and shows progress |
| HTTP client (Android) | OkHttp | Supports streaming uploads |
| PC app UI | Compose Multiplatform (Desktop) | Same UI toolkit as Android. Packaged as MSI with `jpackage`. |
| PC server | Ktor (Netty) | Light embedded HTTP server with streaming request bodies |
| Discovery | mDNS/NSD (`_afw._tcp`) + QR code + manual IP fallback | mDNS is automatic. QR/IP covers routers that block multicast. |
| Serialization | kotlinx.serialization | Shared DTOs in a common module |

**Alternative for the PC app:** C# / WinUI 3 gives a smaller, more native Windows installer. You'd lose the shared Kotlin protocol module, so the API contract would be defined twice. Only worth it if installer size or native look matters a lot.

### Repository layout (single Gradle build, open in Android Studio)

```
AndroidFileWifi/
├─ settings.gradle.kts
├─ shared/            # KMP module: protocol DTOs, constants, path sanitizing
├─ android-app/       # Compose gallery + sender
└─ desktop-app/       # Compose Desktop receiver + Ktor server
```

---

## 2. Android app

### 2.1 Screens

1. **Folders (Albums) screen**: the home screen.
   - Grid of folders built from `MediaStore` `BUCKET_ID` / `BUCKET_DISPLAY_NAME` (Camera, Screenshots, WhatsApp Images, …).
   - Each tile shows a cover thumbnail, the name, and the item count.
   - Tapping a folder opens it.
   - Each folder tile has a **"Send to PC"** action (overflow icon or long-press menu). It sends the entire folder.
   - You can also multi-select folders (long-press, then tap others) and send several at once.
2. **Media grid screen** (inside a folder, plus an "All photos & videos" view).
   - 3–5 column grid (pinch to change). Grouped by date headers (Today, Yesterday, month).
   - Video tiles show duration and a play badge.
   - Filter chip: All / Photos / Videos. Sort: date taken (default), name, size.
3. **Viewer**: swipe between items. Photos support pinch-zoom. Videos play in ExoPlayer. Has Share / Send to PC / Info actions.
4. **Send / Transfers screen**: pick the target PC, then see a live progress list (per file and overall), with retry and cancel.
5. **Settings**: paired PCs, "send over Wi-Fi only", skip duplicates, keep folder structure.

### 2.2 Selection behavior (the core interaction)

The selection state is a `LinkedHashSet<Long>` of MediaStore IDs plus an **anchor index**, kept in a ViewModel so it survives rotation.

| Gesture | Result |
|---|---|
| Long-press an item | Enters selection mode, selects it, and sets it as the **anchor** |
| Tap (in selection mode) | Toggles that item and moves the anchor to it |
| **Long-press a second item** | **Selects everything between the anchor and this item (inclusive)** |
| **"Range" toggle in the top bar** | When on, *tap first, then tap last* selects everything between them. This gives you range select with plain taps, exactly as requested. |
| Long-press + drag | Drag-select across rows. The grid auto-scrolls near the edges. |
| Tap a date header | Selects or deselects that whole day |
| Top bar | Shows "N selected". Has Select all, Clear, Send to PC, Share, and Delete (with system confirmation). |

Range rules:
- The range is calculated on the **currently displayed order** (after filter and sort), using list indices. If the anchor is at 12 and you pick 40, items 12..40 are added. Ranges work in both directions.
- A range **adds** to the existing selection; it never removes items. If every item in the range is already selected, the range deselects them instead (this matches common gallery apps).
- The logic lives in a pure Kotlin `SelectionController` class so it can be unit-tested without the UI.

### 2.3 Permissions

- Android 13+: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`.
- Android 14+: also handle **partial access** (`READ_MEDIA_VISUAL_USER_SELECTED`). Show a banner that says "You gave access to some photos only. Allow all?"
- Android 12 and lower: `READ_EXTERNAL_STORAGE`.
- `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (for mDNS), `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`.
- Android 13+: `NEARBY_WIFI_DEVICES` is **not** needed. NSD works without it.
- `minSdk 26`, `targetSdk 35`.

### 2.4 Extra: share-sheet target

Register `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for `image/*` and `video/*`. That makes "Send to PC" show up in the share menu of any app (for example, Google Photos).

---

## 3. PC app (Windows)

### 3.1 Features

- **Tray app**: starts with Windows (optional) and keeps the server running.
- **Main window**:
  - Pairing panel: a **QR code** plus a **6-digit code**. The QR contains IP, port, certificate fingerprint, and a one-time pairing token.
  - Paired devices list (rename or revoke).
  - **Receive folder** picker. Default: `Pictures\AndroidFileWifi\<PhoneName>\<FolderName>\`.
  - Active and recent transfers with progress bars, speed, ETA, and an "Open folder" button.
- Windows toast notification when a transfer completes.
- On first launch, explain the Windows Firewall prompt (allow on **Private** networks).

### 3.2 File handling

- Keeps the phone's folder structure: `<root>/<phone>/<album>/<file>`.
- Sets the file's modified time to the photo's date taken, so files sort correctly in Explorer.
- Duplicates: if the name, size, and timestamp match, it **skips** the file. If the name matches but the content differs, it saves `name (1).jpg`.
- Writes to `file.part` first and renames it when finished, so a half-received file never looks complete.
- Sanitizes all incoming paths: no `..`, no absolute paths, and no characters Windows rejects.

---

## 4. Protocol (phone → PC)

Transport: **HTTPS** with a self-signed certificate generated by the PC on first run. The phone **pins** the fingerprint it got from the QR code or pairing, so no certificate authority is needed. Every request after pairing carries a `Bearer <deviceToken>` header.

```
Discovery
  PC advertises mDNS service  _afw._tcp  (TXT: name, id, port, fp)
  Phone fallback: scan QR  →  afw://192.168.1.20:47800?fp=…&pair=…
  Phone fallback: type IP manually

Pairing
  POST /api/v1/pair            { deviceName, deviceId, code }       → { token, pcName }
       (PC shows "Pixel 8 wants to pair. Accept?" if no code/QR was used)

Transfer
  POST /api/v1/sessions        { files: [ { id, relPath, size, modified, mime } ] }
                               → { sessionId, files: [ { id, status: new|skip|partial, offset } ] }
  PUT  /api/v1/sessions/{sid}/files/{id}?offset=N    (raw streamed body)
                               → { received }
  POST /api/v1/sessions/{sid}/complete               → summary
  DELETE /api/v1/sessions/{sid}                      (cancel)

Status
  GET  /api/v1/ping            → { pcName, version }   (health check / reachability)
```

- **Resume:** if Wi-Fi drops, the phone asks the session for each file's `offset` and continues from there.
- **Integrity:** the server checks the received byte count against `size`. An optional SHA-256 check is a later milestone.
- **Parallelism:** 2–3 files upload at once to keep the link busy with small photos. Large videos stream one chunk at a time without loading into memory.
- The DTOs live in `shared/`, so both sides compile against the same contract.

---

## 5. Milestones

| # | Milestone | Done when |
|---|---|---|
| **M0** | Scaffold the Gradle multi-module project (`shared`, `android-app`, `desktop-app`) | Both apps build and launch an empty window |
| **M1** | Android gallery: permissions, folders grid, media grid, viewer (photo zoom + video) | You can browse every photo and video on the phone smoothly |
| **M2** | Selection: tap, long-press, **range (long-press and Range toggle)**, drag-select, date-header select, folder multi-select, plus `SelectionController` unit tests | All gestures in §2.2 work and the tests pass |
| **M3** | PC receiver: Ktor server, receive-folder setting, transfer list UI, `.part` writing, duplicate skip | `curl` uploads land correctly in the folder |
| **M4** | Discovery and pairing: mDNS on both sides, QR code + 6-digit code, manual IP, token storage | The phone finds the PC and pairs once |
| **M5** | Transfer engine on Android: WorkManager foreground worker, progress notification, parallel uploads, resume, cancel | Sending 500 photos plus a 2 GB video survives screen-off and a Wi-Fi blip |
| **M6** | Polish: HTTPS with certificate pinning, share-sheet target, tray + autostart, Windows toasts, MSI installer, app icons | Ready for real daily use |

Suggested order: M0 → M1 → M2 (the gallery is useful alone) → M3 → M4 → M5 → M6.

---

## 6. Risks and gotchas

- **Windows Firewall** blocks incoming connections until the user allows them. The PC app must explain this. As a fallback, the installer can add a rule for Private networks.
- **Guest Wi-Fi or "AP isolation"** blocks phone↔PC traffic entirely. The PC app should detect it (ping fails) and show a clear message.
- **mDNS is flaky** on some routers and on VPNs. That's why QR and manual IP exist.
- **Android 15 limits `dataSync` foreground services** to about 6 h per day. That's fine for this use, but very large transfers should split into chunks of work.
- **Partial media access** on Android 14+ means a folder may look incomplete. Make this obvious in the UI.
- **Huge libraries** (50k+ items): query MediaStore with paging, and never load full bitmaps for the grid.

---

## 7. Open decisions

1. **PC app tech:** Kotlin / Compose Desktop (recommended, shared code) or C# / WinUI (smaller, native)?
2. **Direction:** phone → PC only, or also **browse and download from the phone on the PC**? The protocol can grow to support that later.
3. **Other file types:** stay photos/videos only, or add an "All files" tab later? That needs `MANAGE_EXTERNAL_STORAGE`, which is restricted on the Play Store.
4. **Distribution:** Play Store or sideloaded APK? This affects which permissions are allowed.
