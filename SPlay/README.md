# SPlay 2.0 — clean rebuild

A deliberately small Android TV SMB media player.

## Principles
- Android TV UI built with Jetpack Compose for TV.
- SMB2/SMB3 access through SMBJ.
- Playback through AndroidX Media3.
- No FFmpeg, no generated video thumbnails, no thumbnail work in file lists.
- Video and music libraries are scanned independently from their configured folders.
- Music artwork is handled separately from the list UI.
- Persistent bounded diagnostic log with user-triggered TXT export.
- Password storage uses Android Keystore AES-GCM.

The old implementation remains available on the `feature/smb-tv-player` branch. This branch is a clean rebuild.

## Build
From this directory run:

```bash
./gradlew assembleDebug
```
