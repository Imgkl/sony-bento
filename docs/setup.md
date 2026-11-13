# Setup and recovery

[← Sony Bento](../README.md)

## Build and install

You need the Android SDK (build-tools 36.1.0 and platform `android-36`), a JDK 17 or newer, and adb access to the TV. To get it, open Settings > System > About, select "Android TV OS build" 7 times, then turn on USB debugging in Developer options. On my TV, adb then also listens on the network at port 5555.

1. Download [Satoshi](https://www.fontshare.com/fonts/satoshi) and copy `Satoshi-Regular.ttf`, `Satoshi-Bold.ttf` and `Satoshi-Black.ttf` into `launcher/assets/fonts/`. The font licence does not allow the files in this repository.
2. Build: `./launcher/build.sh`. The APK is signed with your Android debug key and written to `launcher/build/sony-bento.apk`.
3. Install and grant the permissions. Android only shows the Wi-Fi name to apps with location access, and the remote status needs Bluetooth access.

   ```bash
   adb connect <tv-ip>:5555
   adb install -r launcher/build/sony-bento.apk
   adb shell pm grant com.sonybento.launcher android.permission.ACCESS_FINE_LOCATION
   adb shell pm grant com.sonybento.launcher android.permission.BLUETOOTH_CONNECT
   ```

4. Give each HDMI input a name in the TV settings (for example "Apple TV"). Inputs without a name do not get a tile.
5. Review the [debloat guide](../debloat/README.md) and run its script if you want this setup. It also makes Sony Bento the Home app.

## Volume island

An optional dial on the left edge replaces Sony's bottom volume display. Its outline stays steady while the ticks and counter move. Both the Sony remote and Apple TV HDMI-CEC keep their normal audio-control path.

The enabled accessibility service connects to the TV's own ADB daemon at `127.0.0.1:5555`. A fixed helper running as Android's shell user registers Bento's volume controller. Android then sends volume/mute callbacks directly to the app, so restarting ADB or the helper does not remove the island. The helper reconnects to the same controller and handles privileged visibility and safe-volume confirmation commands. Its Binder handoff accepts only the authorized shell identity.

No root, bootloader unlock or firmware modification is used. After one-time provisioning, normal boot and reconnect happen on the TV without a computer.

### Setup requirements and limits

- Validated on a Sony K-65S30 running Android 12/API 31. This uses internal Android APIs; firmware updates and other models need compatibility testing.
- A fresh APK install does **not** provision the volume helper. The current installation was provisioned separately: the TV must already authorize a device-specific ADB key, and its PKCS#8 private key must be in the app's private `files/adb-private.pk8` storage. There is currently no first-run pairing flow. The app never enrolls a new key automatically.
- Keep debugging and the Sony Bento accessibility service enabled. On the provisioned TV, `adb_allowed_connection_time` is set to `0` to prevent authorization expiring during long downtime. This is a device-wide debugging setting, not part of the APK. Network ADB still requires an authorized key.
- Private keys, signing keystores and device captures must stay outside Git and the APK. App backup is disabled. Updating with `adb install -r` preserves app data; clearing data, uninstalling or revoking debugging authorization requires provisioning again.
- Disabling the service or losing the app process attempts to restore Sony's display by restarting the verified SystemUI process. This can briefly interrupt system UI and depends on ADB remaining available. A helper disconnect alone preserves the app controller. Stock restoration has a cooldown and a persistent retry limit; repeated recovery attempts pause the island and offer an explicit Retry action. Interrupted restoration is recorded separately from verified restoration.
- Safe-volume warnings require explicit confirmation. While the helper is disconnected, the warning stays visible and Continue is unavailable until reconnection. The safe-volume limit remains enforced.

Host regression checks: `./launcher/tests/run-host-tests.sh` (set `JAVA_HOME` and `ANDROID_SDK` if needed). These check transport framing/deadlines and durable recovery state; physical-remote and standby checks still need a TV.

## HDMI wake and power recovery

**Black screen after the Apple TV wakes the TV.** After a long standby, the Apple TV wakes the TV over HDMI-CEC, but the screen stays black. The MediaTek TV agent asks the TV player to tune to the CEC device input (`HDMI200008`), and the TV player ignores that request when it comes from behind the Home app. The launcher listens for Sony's `com.sony.dtv.intent.action.ONE_TOUCH_PLAY` broadcast. When it arrives while Home is in front, the launcher opens the HDMI hardware input for that port itself (`TvContract.buildChannelUriForPassthroughInput`), and the picture comes back.

**Power cut.** After a power cut, the TV boots to Home and not to the last input. On the first start after a boot (`Settings.Global.BOOT_COUNT`), within 3 minutes of boot, the launcher opens the last used input once the HDMI inputs register. First its tile fills from left to right for 3 seconds, so you can see the switch coming. Press any remote button except volume to cancel it and stay on Home.
