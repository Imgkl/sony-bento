package com.sonybento.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Shows the edge island for every media volume and mute change. On a TV, real volume keys never reach an
 * accessibility service: the window manager raises the stock volume bar first. res/raw/volume_keys.kcm,
 * assigned to a remote as its keyboard layout, turns its volume buttons into F10 to F12, which this service
 * turns back into silent volume changes. HDMI-CEC volume (Apple TV remote) is injected as real volume keys
 * and still shows the stock bar.
 */
public class VolumeService extends AccessibilityService {

    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private IslandView island;
    private AudioManager audio;
    private int lastLevel;
    private boolean lastMuted;

    /** Changes made elsewhere (another app, a phone). Our own key presses already updated the island. */
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(STREAM, -1) != AudioManager.STREAM_MUSIC) return;
            int level = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
            if (muted != lastMuted) island.pressMute();
            else if (level != lastLevel) island.press(level > lastLevel ? 1 : -1);
            lastLevel = level;
            lastMuted = muted;
        }
    };

    @Override
    protected void onServiceConnected() {
        audio = getSystemService(AudioManager.class);
        lastLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        lastMuted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        island = new IslandView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.round(120 * getResources().getDisplayMetrics().density),
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        getSystemService(WindowManager.class).addView(island, lp);
        IntentFilter filter = new IntentFilter(VOLUME_CHANGED);
        filter.addAction(MUTE_CHANGED);
        registerReceiver(changes, filter);
    }

    /** res/raw/volume_keys.kcm turns the remotes' volume buttons into F10 to F12, which only arrive here. */
    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int direction;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_F12: direction = AudioManager.ADJUST_RAISE; break;
            case KeyEvent.KEYCODE_F11: direction = AudioManager.ADJUST_LOWER; break;
            case KeyEvent.KEYCODE_F10:
                if (event.getRepeatCount() > 0) return true;
                direction = AudioManager.ADJUST_TOGGLE_MUTE;
                break;
            default: return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        // Flags 0: no stock volume bar. The adjust is synchronous, so the island can read the new level now.
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        if (direction == AudioManager.ADJUST_TOGGLE_MUTE) island.pressMute();
        else island.press(direction == AudioManager.ADJUST_RAISE ? 1 : -1);
        lastLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        lastMuted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        removeIsland();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        removeIsland();
        super.onDestroy();
    }

    private void removeIsland() {
        if (island == null) return;
        unregisterReceiver(changes);
        getSystemService(WindowManager.class).removeView(island);
        island = null;
    }
}
