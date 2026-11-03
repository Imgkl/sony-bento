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
 * Takes the remote's volume keys, changes the volume without asking for the system volume bar, and
 * shows the edge island instead.
 */
public class VolumeService extends AccessibilityService {

    private AudioManager audio;
    private IslandView island;

    /** Any volume or mute change, ours (the adjust lands asynchronously) or another app's. */
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            island.sync();
        }
    };

    @Override
    protected void onServiceConnected() {
        audio = getSystemService(AudioManager.class);
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
        IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        filter.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
        registerReceiver(changes, filter);
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (down) {
                    int dir = event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1;
                    adjust(dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER);
                    island.press(dir);
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (down && event.getRepeatCount() == 0) {
                    adjust(AudioManager.ADJUST_TOGGLE_MUTE);
                    island.pressMute();
                }
                return true;
            default:
                return false;
        }
    }

    /** Flags 0: no FLAG_SHOW_UI, so the system bar stays away, and no click sound. */
    private void adjust(int direction) {
        audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, 0);
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
