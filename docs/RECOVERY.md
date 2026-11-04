# Recovery & troubleshooting

## Golden rules
1. Before a debloat session, go to Backup & Recovery and **export the ADB script (.sh/.bat) and the JSON backup** to your PC or cloud storage.
2. Change a few packages at a time, reboot, and test calls, SMS, mobile data, Wi-Fi, camera, fingerprint and notifications.
3. Keep a second keyboard and a second launcher installed before touching keyboard or launcher packages. Critical ones are blocked anyway.

## Undo inside the app
| You did | Undo |
|---|---|
| Disable / block launch / restrict background | App › **Restore original state**, or Backup › Restore |
| Uninstall system app (user 0) | Applications › filter **Uninstalled** › app › **Re-install removed system app** |
| Root removal of a system APK | Backup › **Undo root removal** › **Reboot** › then **Restore** |
| Uninstall user app | Not reversible. Reinstall from Play Store or App Market. |

## Undo from a PC (phone boots, but the app or Shizuku is unavailable)
```bash
adb devices                      # authorise the prompt on the phone
adb shell sh /sdcard/Download/restore_honor_controller.sh   # wherever you saved the export
# or package by package:
adb shell cmd package install-existing --user 0 <package>
adb shell pm enable --user 0 <package>
adb shell pm unsuspend --user 0 <package>
adb shell cmd appops set <package> RUN_ANY_IN_BACKGROUND allow
adb shell am set-standby-bucket <package> active
```
To list everything that's currently disabled: `adb shell pm list packages -d`. To list system apps removed for user 0: `adb shell pm list packages -u` and compare it with `pm list packages`.

## Phone boot-loops after root removal
1. **Magisk safe mode.** Power on, and when the HONOR logo appears, hold **Volume Down** until boot finishes. All modules are disabled, so the phone boots normally.
2. Open Magisk › Modules › remove **HONOR System Controller – Debloat**, then reboot.
3. If ADB works during the boot loop: `adb wait-for-device shell magisk --remove-modules`
4. KernelSU: boot into safe mode the same way, or run `adb shell rm -rf /data/adb/modules/hsc_debloat` and reboot.
5. Removed APK folders are backed up at `/sdcard/HonorSystemController/apk-backup/<package>/`.

## Phone boot-loops after disable or uninstall (no root)
Disabled and user-0-uninstalled states live in `/data`. If ADB is authorised, the commands above work as soon as `adb devices` shows the phone, even mid-boot. Otherwise the last resort is **recovery › Wipe data / factory reset**, which restores every system app (and erases your data). The critical-package guard exists to prevent this.

## Common errors
| Message | Meaning |
|---|---|
| `java.lang.SecurityException: Shell cannot change component state` | MagicOS protects this package. Nothing was changed. |
| `NOT confirmed: process restarted immediately` | Another app or a system service binds to it. Use Disable or Block launching. |
| `Failure [DELETE_FAILED_INTERNAL_ERROR]` | Protected package. Nothing was changed. |
| `Shizuku execution failed` | Shizuku stopped (after reboot or a Wi-Fi change). Start it again. |
| Running state shows "—" | Process lists need Shizuku or root. |
| Automation never runs | Allow this app in MagicOS App launch (Manage manually, all toggles on). |
