# Testing checklist

Use a harmless user app (e.g. a calculator from the Play Store) as the target unless a step says otherwise. After every step, confirm that the result banner and audit log say **Confirmed by the system** and that the state chip changed.

## Standard mode
- [ ] The app list loads; filters (System / User / Disabled / Uninstalled / Recently installed) and search by name and package both work.
- [ ] Action buttons explain that elevated access is needed and open App info.
- [ ] Uninstall on a user app opens Android's uninstall dialog.
- [ ] After granting usage access, storage and foreground time appear.

## Shizuku mode
- [ ] Dashboard shows "Connected as adb shell (uid 2000)".
- [ ] Force stop → `pidof` is empty → confirmed.
- [ ] Disable → the icon disappears from the launcher → Enable brings it back.
- [ ] Block launching → the icon is greyed out and tapping it shows the system "paused" dialog → Unblock.
- [ ] Restrict background → `adb shell cmd appops get <pkg> RUN_ANY_IN_BACKGROUND` shows `ignore`.
- [ ] Toggling a component's service shows a confirmed state.
- [ ] Uninstall a **non-essential system app** (e.g. HONOR Tips) → it appears under the *Uninstalled* filter → Reinstall → it's back.
- [ ] Critical packages (`com.android.systemui`, `com.android.phone`, `com.hihonor.android.launcher`) have their Disable/Uninstall buttons disabled with a reason.
- [ ] The console rejects `rm -rf /`, and rejects `pm disable-user com.android.systemui` with the guard message.
- [ ] The Running Processes list refreshes every 5 s and memory graph updates.

## Blocklist & automation
- [ ] Add a test app with all options, then Verify all → Active.
- [ ] `adb shell pm enable <pkg>` from a PC → Verify all → Violation, plus a notification.
- [ ] With *Re-apply when violated* on, Check & re-apply → Active again.
- [ ] Reboot, start Shizuku, wait 5 minutes → the boot re-apply pass runs (check the audit log).

## Backup & recovery
- [ ] Modified packages lists every changed app with its capture time.
- [ ] The exported `.sh` runs from a PC with `adb shell sh` and restores everything.
- [ ] Restore all returns every app to its original state; the snapshot list empties.

## Root mode (rooted test device only)
- [ ] Verify root shows the Magisk prompt, and the mode then reports Root verified.
- [ ] Root removal of a SAFE system app → backup folder exists → reboot → the app is gone from `pm list packages -s`.
- [ ] Undo root removal → reboot → Restore → the app is back.
- [ ] Magisk safe mode (Volume Down during boot) disables the module.
