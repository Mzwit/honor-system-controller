# Build and install

## 1. Download the APK built by CI
Actions › **Build APK** › latest run › artifact **HonorSystemController-apk**. The `app-debug.apk` inside is signed with the debug key and can be installed directly. Its application ID is `com.honor.systemcontroller.debug`.

## 2. Build it yourself
- **Android Studio** (Ladybug or newer): *File › Open*, choose this folder, wait for Gradle sync, then press **Run** with the phone connected.
- **Command line** (JDK 17 + Android SDK 35): `./gradlew assembleDebug`. The APK is written to `app/build/outputs/apk/debug/`.

### Release build
```bash
keytool -genkeypair -v -keystore hsc-release.jks -alias hsc -keyalg RSA -keysize 4096 -validity 10000
export HSC_KEYSTORE=$PWD/hsc-release.jks HSC_KEYSTORE_PASSWORD=*** HSC_KEY_ALIAS=hsc HSC_KEY_PASSWORD=***
./gradlew assembleRelease
```
For signed release builds on CI, add the repository secrets `HSC_KEYSTORE_B64` (the output of `base64 -w0 hsc-release.jks`), `HSC_KEYSTORE_PASSWORD`, `HSC_KEY_ALIAS` and `HSC_KEY_PASSWORD`.

## 3. First run on the HONOR 400
1. Install the APK. Allow "Install unknown apps" for your file manager.
2. **Usage access**: go to Dashboard › Grant usage access and turn the app on.
3. **Notifications**: go to Settings › Allow notifications.
4. **Keep the app alive**: open Settings › Battery › App launch, find HONOR System Controller, choose *Manage manually* and turn on all three toggles.

## 4. Shizuku (recommended, no root)
1. Settings › About phone › tap *Build number* 7 times.
2. Settings › System & updates › Developer options › turn on **Wireless debugging**. MagicOS turns it off whenever the Wi-Fi network changes.
3. Install Shizuku (from Play Store or GitHub) › *Start via Wireless debugging* › pair using the notification code.
4. In this app: Settings › Mode **Shizuku** › Dashboard › **Connect Shizuku** › Allow.
5. After every reboot, start Shizuku again. Restrictions you've applied survive reboots anyway.

From a PC you can start Shizuku over USB instead: `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`

## 5. ADB from a PC (mode B)
Install Google platform-tools, enable USB debugging, and confirm the RSA prompt on the phone. Every action in mode B copies an `adb shell …` command. Backup & Recovery exports complete `.sh` / `.bat` scripts.

## 6. Root (optional, mode D)
Only if the phone is **already** rooted with Magisk or KernelSU. Unlocking the bootloader on HONOR devices is generally not officially supported, wipes the phone and can void the warranty. This app never unlocks or roots anything. Go to Settings › Root › **Verify root** and grant the prompt in your root manager.
