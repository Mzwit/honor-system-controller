# Feature / privilege matrix

✅ works · ⚠️ partial or alternative · ❌ not possible at this level

| Feature | Standard | ADB (PC) | Shizuku | Root | MagicOS notes |
|---|---|---|---|---|---|
| List all apps (icon, version, install type, system/user) | ✅ | ✅ | ✅ | ✅ | Needs `QUERY_ALL_PACKAGES`, which this sideloaded app declares |
| Storage per app, foreground time, last used | ✅ with usage access | ✅ | ✅ | ✅ | |
| Running state, RSS memory, CPU, process list | ❌ (Android 7+ hides other processes) | ✅ via `ps -A` | ✅ | ✅ | |
| Battery use per app | ⚠️ opens Settings › Battery | ⚠️ | ⚠️ | ⚠️ | No public per-app battery API exists |
| Force stop | ⚠️ opens App info | ✅ `am force-stop` | ✅ | ✅ | Power Genie may restart HONOR services; prefer Disable |
| Disable / enable package | ⚠️ App info (user apps only) | ✅ `pm disable-user` | ✅ | ✅ | Some `com.hihonor.*` packages refuse; the error is shown |
| Block launching | ❌ | ✅ `pm suspend` | ✅ | ✅ | Icon greys out and an "app paused" dialog appears |
| Restrict background | ⚠️ battery settings | ✅ appops + standby bucket | ✅ | ✅ | MagicOS "App launch" is a separate layer; a shortcut is provided |
| Disable components (services, receivers, activities) | ❌ | ✅ `pm disable pkg/cls` | ✅ | ✅ | |
| **Uninstall user app** | ✅ system dialog | ✅ `pm uninstall` | ✅ | ✅ | Permanent; data is deleted |
| **Uninstall system app (user 0)** | ❌ | ✅ `pm uninstall -k --user 0` | ✅ | ✅ | APK stays on the system image; Reinstall uses `cmd package install-existing` |
| **Delete system APK from partition** | ❌ | ❌ | ❌ | ✅ systemless module | EROFS + dm-verity make `/system` unwritable; the module hides the folder at boot |
| Blocklist + verification | ⚠️ verification only | ✅ | ✅ | ✅ | Non-root Shizuku stops after reboot; restrictions persist anyway |
| Automation (WorkManager, boot) | ✅ checks | – | ✅ re-apply | ✅ re-apply | Allow this app in App launch › Manage manually |
| Backup, restore, ADB scripts | ✅ export | ✅ run scripts | ✅ | ✅ | |
| Shell console (allowlisted) | ⚠️ copies `adb shell` | ✅ | ✅ | ✅ | |

## What a normal app cannot do

- **Intercept every app launch.** Android gives no app that hook. The strongest legitimate options are `pm suspend` (launch blocked by the system) and `pm disable-user`. Both are used here.
- **On-device ADB client with wireless pairing.** Shizuku already implements this securely (wireless debugging pairing, then a shell-uid service). Mode B therefore runs commands through Shizuku on the phone, or exports them for a PC.
- **Physically delete files from `/system`.** The partitions are read-only EROFS images. See RECOVERY.md for the systemless method used instead.
