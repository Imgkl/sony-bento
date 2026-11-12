# Debloat and restore

[← Sony Bento](../README.md)


[`debloat.sh`](debloat.sh) removes apps for user 0 with `pm uninstall -k --user 0`. The original APKs stay on the read-only system partition, so nothing is deleted for good. No root is needed.

```bash
BRAVIA_IP=<tv-ip> ./debloat/debloat.sh            # debloat and set Sony Bento as Home
BRAVIA_IP=<tv-ip> ./debloat/debloat.sh restore    # put everything back
```

A factory reset also restores everything. A Sony firmware update can bring some apps back. If that happens, run the script again.

### Removed (33 packages)

**Ads, promos and trackers**

| Package | What it is |
|---|---|
| `tv.samba.ssm` | Samba TV. Recognises what is on screen for ad targeting. |
| `com.sony.dtv.promos` | "My BRAVIA" promo content |
| `com.sony.dtv.sonyselect` | Sony Select, the promoted apps row |
| `com.sony.dtv.notificationcenter` | BRAVIA notifications (promo pop-ups) |
| `com.sony.dtv.sonytab` | Sony content tab |
| `com.sony.dtv.interactivetvutil` | Uploads Sony usage, viewing and voice logs |
| `com.google.android.feedback` | Play Store feedback agent |
| `com.google.android.play.games` | Play Games |
| `com.google.android.videos` | Google Play Movies |
| `com.google.android.youtube.tvmusic` | YouTube Music |

**Apps this setup does not use**

| Package | What it is |
|---|---|
| `com.sony.dtv.multiscreendemo` | Store demo mode |
| `com.sony.dtv.smarthelp`, `com.sony.dtv.helpguidecontents` | Built-in help (99 MB) |
| `com.sony.dtv.voicesearch` | Sony voice search |
| `com.sony.dtv.ecodashboard` | Eco dashboard |
| `com.sony.dtv.sktvfb`, `com.sony.dtv.operatorapputil.service` | Channel editor and cable operator apps |
| `com.mediatek.tv.oneworld.logdump`, `com.mediatek.tv.oneworld.logdumpservice`, `com.mediatek.tv.diagnosis` | MediaTek debug log tools |
| `com.mediatek.wwtv.mediaplayer` | USB media player |
| `com.sony.dtv.all4launcher`, `com.sony.dtv.iplayerlauncher`, `com.sony.dtv.itvlauncher`, `com.sony.dtv.my5launcher`, `com.sony.dtv.soundslauncher`, `com.youview.poa` | UK TV app shortcuts |
| `com.amazon.amazonvideo.livingroom`, `com.netflix.ninja`, `com.netflix.tokenmanager` | Prime Video and Netflix (watched on the Apple TV instead) |
| `com.google.android.apps.tv.dreamx` | Google Ambient Mode, the screensaver with suggestions |
| `com.google.android.katniss` | Google Assistant and voice. This also removes the always-on mic permission. |
| `com.google.android.syncadapters.calendar` | Google Calendar sync |

### Disabled (3 packages)

These are disabled with `pm disable-user --user 0`, not removed:

| Package | Why disabled and not removed |
|---|---|
| `com.google.android.apps.tv.launcherx` | The Google TV home. If it stays on, it takes Home back after its 30-second boot timeout. |
| `com.google.android.tungsten.setupwraith` | Google setup. Its recovery screen claims Home with a higher priority. |
| `com.sonypicturescore` | Sony Pictures Core store. Sony's device control service crashes if this app is removed fully. |

### Settings changed

- Home app: `cmd package set-home-activity com.sonybento.launcher/.MainActivity`
- Screensaver off (it pointed at the removed Ambient Mode).

### Do not remove

These broke the TV when I removed them. Sony's system service and the MediaTek TV agent read their content providers at boot, and the TV crash-looped after a cold boot:

- `com.sony.dtv.m2.seeds.iot`, `com.sony.dtv.m2.seeds.auth`
- `com.sony.dtv.demosupport`, `com.sony.dtv.demosystemsupport`

Also leave these alone, because they run HDMI, CEC and the TV itself:

- `com.mediatek.tv.oneworld.tvcenter` (the player that shows HDMI)
- `com.mediatek.tis` (HDMI inputs)
- `com.mediatek.tv.agent` (CEC One Touch Play)
- `com.mediatek.dtv.tvinput.framework.server`
- `com.sony.dtv.sonysystemservice`
- `com.sony.dtv.connecteddevicesettings` (CEC settings)
- Google Play services

I also uninstalled 9 apps that I had installed myself. They are not part of the script.
