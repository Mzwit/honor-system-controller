# HONOR System Controller

An Android app manager, process terminator and system package controller for your own phone. It targets the **HONOR 400 (MagicOS 9.0, Android 15, Snapdragon 7 Gen 3)** and works on any Android 9+ device.

Kotlin · Jetpack Compose · Material 3 · Room · WorkManager · Shizuku · optional root.

## Get the APK

Every push to this repository builds the app on GitHub Actions.

1. Open the **Actions** tab, then the latest **Build APK** run.
2. Download the **HonorSystemController-apk** artifact (a zip file).
3. Unzip it and install `app-debug.apk` on the phone. You'll need to allow "Install unknown apps" for your browser or file manager.

See [docs/BUILD_AND_INSTALL.md](docs/BUILD_AND_INSTALL.md) for Android Studio builds, release signing, and the Shizuku and root setup.

## Modes

| Mode | What it can do | Setup |
|---|---|---|
| **A · Standard** | Lists all apps with storage, usage and memory. Opens App info, battery and MagicOS App launch screens. Opens the uninstall dialog for user apps. | None. Usage access is optional. |
| **B · ADB** | Generates the exact `adb shell` commands and restore scripts for a PC. Runs them on the phone when Shizuku is connected. | USB or wireless debugging |
| **C · Shizuku** | Runs force-stop, disable, enable, suspend (block launch), background restriction, component toggles, uninstall/reinstall and the process list as the ADB shell user (uid 2000). This is **not** root. | Shizuku app |
| **D · Root** | Everything in Shizuku, plus **removal of non-essential system APKs** through a systemless Magisk/KernelSU module. | Phone already rooted. This app never roots it. |

The full matrix of which feature needs which privilege is in [docs/FEATURES.md](docs/FEATURES.md).

## Safety guarantees

- System UI, Settings, telephony, networking, security, biometrics, boot/launcher, storage providers, OTA updates, Shizuku and this app itself are **never** disabled, blocked or uninstalled. The guard runs on every code path, including the console.
- The original state of each package is recorded before the first change. You can restore one app, a selection or everything, and export `.sh`/`.bat` ADB restore scripts.
- An operation is reported successful **only after the system confirms the new state**. Shell output is shown verbatim when something fails.
- Uninstalling or root-removing a system app, or acting on any package that isn't positively known to be non-essential, requires typing the package name.
- Every action goes into a full audit log.

Read **[docs/RECOVERY.md](docs/RECOVERY.md)** before you remove system apps.
