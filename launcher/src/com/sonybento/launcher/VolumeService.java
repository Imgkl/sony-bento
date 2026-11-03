package com.sonybento.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Shows the edge island for every media volume and mute change. Sony's firmware hands the volume keys
 * straight to AudioService, which always raises the stock volume bar, so this closes that bar as soon as
 * the volume moves. The close is only allowed because this service owns a visible accessibility overlay.
 */
public class VolumeService extends AccessibilityService {

    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private IslandView island;
    private AudioManager audio;
    private int lastLevel, direction = 1;

    /** AudioService sends one broadcast per stream that follows the media volume; only STREAM_MUSIC's own counts. */
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context context, Intent intent) {
            long received = SystemClock.uptimeMillis();
            if (intent.getIntExtra(STREAM, -1) != AudioManager.STREAM_MUSIC) return;
            if (MUTE_CHANGED.equals(intent.getAction())) {
                island.pressMute();
                return;
            }
            // Foreground queue, like the system's own close, so queued background broadcasts cannot delay it.
            sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
            long closed = SystemClock.uptimeMillis();
            // Sony's broadcast carries the same old and new value, so the direction comes from our last read.
            int level = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (level != lastLevel) direction = level > lastLevel ? 1 : -1;
            Log.i("BentoVol", "volume " + lastLevel + " -> " + level + " received at " + received);
            Log.i("BentoVol", "close sent at " + closed + " (" + (closed - received) + " ms after receipt)");
            lastLevel = level;
            island.press(direction);
        }
    };

    @Override
    protected void onServiceConnected() {
        audio = getSystemService(AudioManager.class);
        lastLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
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
