#!/bin/bash
# Debloat a Sony BRAVIA (Google TV) and make Sony Bento the home screen.
# Usage: BRAVIA_IP=<tv ip> ./debloat.sh    or undo everything with: BRAVIA_IP=<tv ip> ./debloat.sh restore
# Removal is per user profile (pm uninstall --user 0): the original APKs stay on the system partition.
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
TV="${BRAVIA_IP:?Set BRAVIA_IP to the TV IP address}:5555"

# Must stay installed: Sony's system service and the MediaTek TV agent read their content
# providers at boot and crash-loop without them (found on a cold boot, 2026-10-19):
#   com.sony.dtv.m2.seeds.iot, com.sony.dtv.m2.seeds.auth, com.sony.dtv.demosupport, com.sony.dtv.demosystemsupport
PACKAGES=(
  # Tier 1: ads, promos, trackers
  tv.samba.ssm
  com.sony.dtv.promos
  com.sony.dtv.sonyselect
  com.sony.dtv.notificationcenter
  com.sony.dtv.sonytab
  com.sony.dtv.interactivetvutil
  com.google.android.feedback
  com.google.android.play.games
  com.google.android.videos
  com.google.android.youtube.tvmusic
  # Tier 2: extras not used on a TV that only shows Apple TV and PS5
  com.sony.dtv.multiscreendemo
  com.sony.dtv.smarthelp
  com.sony.dtv.helpguidecontents
  com.sony.dtv.voicesearch
  com.sony.dtv.ecodashboard
  com.sony.dtv.sktvfb
  com.sony.dtv.operatorapputil.service
  com.mediatek.tv.oneworld.logdump
  com.mediatek.tv.oneworld.logdumpservice
  com.mediatek.tv.diagnosis
  com.mediatek.wwtv.mediaplayer
  com.sony.dtv.all4launcher
  com.sony.dtv.iplayerlauncher
  com.sony.dtv.itvlauncher
  com.sony.dtv.my5launcher
  com.sony.dtv.soundslauncher
  com.youview.poa
  com.amazon.amazonvideo.livingroom
  com.netflix.ninja
  com.netflix.tokenmanager
  com.google.android.apps.tv.dreamx
  com.google.android.katniss
  com.google.android.syncadapters.calendar
)

# Disabled, not removed: the launcher swap needs these off, and Sony's device-control
# service crashes if Sony Pictures Core is missing entirely.
DISABLED=(
  com.sonypicturescore
  com.google.android.apps.tv.launcherx
  com.google.android.tungsten.setupwraith
)

sh() { "$ADB" -s "$TV" shell "$@"; }

if [ "${1:-}" = "restore" ]; then
  for p in "${PACKAGES[@]}"; do echo "$p: $(sh cmd package install-existing "$p" 2>&1 | tr -d '\r')"; done
  for p in "${DISABLED[@]}"; do echo "$p: $(sh pm enable "$p" 2>&1 | tr -d '\r')"; done
  sh settings put secure screensaver_enabled 1
  sh settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
  echo "Google TV home restored. Press Home on the Sony remote and choose Google TV if asked."
  exit 0
fi

for p in "${PACKAGES[@]}"; do echo "$p: $(sh pm uninstall -k --user 0 "$p" 2>&1 | tr -d '\r')"; done
for p in "${DISABLED[@]}"; do echo "$p: $(sh pm disable-user --user 0 "$p" 2>&1 | tr -d '\r')"; done
# Set Home only after Google's launcher and setup app are off, or the preference does not stick.
sh cmd package set-home-activity com.sonybento.launcher/.MainActivity
# Screensaver pointed at the removed Ambient Mode; turn it off instead.
sh settings put secure screensaver_enabled 0
sh settings delete secure screensaver_components >/dev/null
