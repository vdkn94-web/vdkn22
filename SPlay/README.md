# SPlay SMB

Android TV media player for browsing an SMB2/SMB3 share and playing audio/video with LibVLC.

## Features in this first implementation

- SMB server connection using host/IP, share name, username, password and optional domain
- Guest-style connection by leaving username/password blank (server configuration permitting)
- Browse the share root and nested directories
- Play common audio and video formats through LibVLC
- TV-friendly dark interface with clickable/focusable file rows and Back navigation

## Open and build

1. Open the `SPlay` directory in Android Studio.
2. Use JDK 17 or the JDK recommended by the installed Android Gradle Plugin.
3. Allow Gradle sync to finish.
4. Run the `app` configuration on an Android TV device or emulator.

The project uses SMBJ for directory browsing and LibVLC's SMB network input for direct playback. No local media library is created.

## Connect

- **Server:** NAS IP address or hostname, without `smb://`
- **Share:** the published SMB share name (not a folder inside the share)
- **Username/password:** an account allowed to read the share
- **Domain:** normally blank for OpenMediaVault / local NAS accounts

The SMB service must be reachable from the TV on TCP port 445. Configure the share permissions on the NAS before connecting.

## Notes

This is the initial implementation and still needs a real-device build and playback check against the target OpenMediaVault server. LibVLC playback depends on SMB support being present in the selected LibVLC Android build.
