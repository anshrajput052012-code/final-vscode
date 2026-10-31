# Pocket VS Code (`com.pocket.vscode`)

Genuine **Microsoft VS Code (linux-arm64)** ek standalone Android APK mein — Termux ki zaroorat nahi.
Debian ARM64 rootfs + VS Code APK ke andar `assets/` mein bundle hote hain (GitHub Actions build ke waqt) aur
PRoot (`libproot.so`, `jniLibs/arm64-v8a`) ke andar chalte hain.

## Flow
1. App kholo -> **1. Select Android Project Folder**.
2. Folder select hote hi **automatically** background mein Debian rootfs + VS Code extract + environment setup shuru
   hota hai (loading screen, real **0% -> 100%** progress). Koi extra button nahi.
3. 100% ke baad mode chuno aur **Launch**:
   - **Mode 1** — Full Electron Desktop (`Xtigervnc` + `openbox` + `/opt/vscode-arm64/code`, noVNC). Koi throttling nahi.
   - **Mode 2** — `code serve-web` (port `8080`). Server live hote hi app khud `WorkbenchActivity` (in-app WebView,
     `http://127.0.0.1:8080/?folder=/root/Workspace`) mein open kar deta hai; WebView fail ho to browser (`ACTION_VIEW`).

## Mode 2 extras (sirf Mode 2)
- `files.autoSave: afterDelay` (500 ms), `files.hotExit: onExitAndWindowClose`, `window.restoreWindows: all`
  (`/root/.vscode-server-data/data/{User,Machine}/settings.json` mein merge hota hai, tumhari baaki settings safe rehti hain).
- 10 minute tak koi connection / app heartbeat na ho to server `SIGTERM` se hibernate ho jata hai; service aur wakelock bhi
  band. Dobara Mode 2 kholne par auto-resume.
- WebView background mein pause (`onPause`) — Mode 1 par kuch nahi.

## Package ID & updates
- `applicationId` hamesha `com.pocket.vscode` (`app/build.gradle.kts`).
- `versionCode` = `GITHUB_RUN_NUMBER` (auto-increment).
- Signing keystore: `app/keystore.jks` (repo) / release asset `pocket-vscode-permanent.keystore` — workflow isi ko restore karta hai,
  isliye naya APK purane ke upar seedha update hota hai.

## Repo layout
Files flat naam se hain (`app__src__main__java__...`); workflow unko asli folders mein restore karta hai.

## Build
GitHub -> **Actions** -> *Build Standalone VS Code APK* -> **Run workflow** (ya `main` par push). APK **Releases** mein milega
(`PocketVSCode-arm64.apk`). Local build officially supported nahi hai (native binaries CI mein patchelf se patch hote hain).

## Internet kab chahiye
- Rootfs + VS Code APK ke andar hain, par `init-rootfs.sh` pehli baar `apt-get` se X11/VS Code dependencies + dev tools install karta hai
  (internet chahiye, sirf ek baar).
- Mode 2 (`code serve-web`) pehli baar VS Code server component download kar sakta hai.
