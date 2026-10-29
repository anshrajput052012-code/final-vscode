# VS Code Desktop for Android (Standalone APK — No Termux Required)

This Android project packages the **genuine Microsoft Visual Studio Code (`linux-arm64`) desktop application** inside a single, self-contained Android APK (`com.vscode.desktop.standalone`) **without requiring Termux or Termux:X11**.

## How It Works Without Termux
1. **Bundled Native Execution Engine (`jniLibs/arm64-v8a/libproot.so`)**:
   - Packaged with `useLegacyPackaging = true` and `android:extractNativeLibs="true"` so Android extracts `libproot.so` into `/data/app/.../lib/arm64/libproot.so` with executable SELinux permissions (`W^X` compliant on Android 10–15).
2. **Self-Contained ARM64 Debian Rootfs & Official VS Code (`StandaloneProvisioner.kt`)**:
   - Directly downloads and extracts the Debian Bookworm `aarch64` rootfs and the official Microsoft VS Code `linux-arm64` build into `context.filesDir/rootfs/opt/vscode-arm64`.
3. **Built-In Display System Inside the APK (`MainActivity.kt`)**:
   - **Mode 1 (Full Electron X11 Desktop)**: Starts `Xtigervnc :1` + `openbox` + `/opt/vscode-arm64/bin/code --no-sandbox` inside the APK and streams the desktop window directly to the in-app surface.
   - **Mode 2 (High-FPS Direct Workbench Host)**: Runs `/opt/vscode-arm64/bin/code serve-web` directly from the official VS Code installation.

## Build the Installable APK (2 Options)

### Option A: Zero-Setup Cloud Build via GitHub Actions (No PC / Android Studio Needed)
1. Upload/push this project folder to a GitHub repository.
2. Go to the **Actions** tab -> **Build Standalone VS Code Android APK (Zero Termux)** -> click **Run workflow**.
3. Download `VSCode-Android-Standalone-arm64-v8a-APK.zip` -> install `app-debug.apk` on your Android phone or tablet.

### Option B: Local Command-Line Build
```bash
chmod +x fetch-native-proot.sh
./fetch-native-proot.sh
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```
The installable APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.
