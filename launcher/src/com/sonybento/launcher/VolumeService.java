package com.sonybento.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import com.sonybento.volume.AdbVolumeConnection;

import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Renders the shell-owned volume controller's events. Both remotes retain their native input path.
 * Android binds this enabled accessibility service after boot; opening the launcher is not required.
 */
public class VolumeService extends AccessibilityService {
    private static final String TAG = "BentoVolume";
    private static final String CHANNEL = "volume_recovery";
    private static final String ACTION_RETRY = "com.sonybento.launcher.RETRY_VOLUME";
    private static final int RECOVERY_NOTIFICATION = 8611;
    private static final long MAX_RETRY_MS = 60_000;
    private static WeakReference<VolumeService> running = new WeakReference<>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private IslandView island;
    private AlertDialog warning;
    private ExecutorService worker;
    private AdbVolumeConnection connection;
    private boolean active;
    private boolean islandAdded;
    private boolean controllerReady;
    private boolean hadController;
    private boolean paused;
    private boolean explicitRecovery;
    private int generation;
    private int failures;
    private long requestedRetryMs;

    @Override
    protected void onServiceConnected() {
        if (active) return;
        active = true;
        paused = false;
        failures = 0;
        running = new WeakReference<>(this);
        windows = getSystemService(WindowManager.class);
        worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "BentoVolumeConnection"));
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Volume island recovery", NotificationManager.IMPORTANCE_LOW));
        island = new IslandView(this);
        island.setVisibilityListener(this::reportVisibility);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.round(120 * getResources().getDisplayMetrics().density),
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        try {
            windows.addView(island, lp);
            islandAdded = true;
            // post() waits for attachment, so the helper cannot take ownership before an overlay exists.
            island.post(this::connect);
        } catch (RuntimeException e) {
            pause("The volume island could not be displayed.");
            Log.e(TAG, "Overlay attachment failed", e);
        }
    }

    private void connect() {
        if (!active || paused || connection != null || island == null || !island.isAttachedToWindow()) return;
        final int attempt = ++generation;
        requestedRetryMs = 0;
        AdbVolumeConnection next = new AdbVolumeConnection(
                new File(getFilesDir(), "adb-private.pk8"), getApplicationInfo().sourceDir,
                new AdbVolumeConnection.Listener() {
                    @Override public void onEvent(JSONObject event) {
                        handler.post(() -> {
                            if (active && generation == attempt) handleEvent(event);
                            else if (!active && "recovery_unverified".equals(event.optString("code"))) {
                                showRecoveryNotification(event.optString("detail"));
                            }
                        });
                    }

                    @Override public void onClosed(String reason) {
                        handler.post(() -> {
                            if (active && generation == attempt) disconnected(reason);
                        });
                    }
                }, explicitRecovery);
        // A notification action applies to exactly this attempt, never subsequent reconnects.
        explicitRecovery = false;
        connection = next;
        worker.execute(next::run);
    }

    private void handleEvent(JSONObject event) {
        if (event.optInt("v") != 1) {
            pause("The volume helper needs an update.");
            return;
        }
        String kind = event.optString("type");
        switch (kind) {
            case "hello":
                if (connection == null) break;
                if (!paused && island != null && island.isAttachedToWindow()) connection.ready();
                else connection.close();
                break;
            case "ready":
                if (paused || connection == null) break;
                if (event.optInt("uid", -1) != 2000) {
                    pause("The volume helper could not start.");
                    break;
                }
                controllerReady = true;
                hadController = true;
                failures = 0;
                getSystemService(NotificationManager.class).cancel(RECOVERY_NOTIFICATION);
                reportVisibility(island.isFullyVisible());
                Log.i(TAG, "Controller ready");
                break;
            case "volume":
                if (!controllerReady || paused) break;
                int minimum = event.optInt("min", 0);
                int maximum = event.optInt("max", -1);
                int level = event.optInt("level", -1);
                if (minimum < 0 || maximum < minimum || maximum > 10000 || level < minimum || level > maximum) {
                    pause("The volume helper returned an invalid level.");
                    break;
                }
                island.updateVolume(level, minimum, maximum,
                        event.optBoolean("muted") || event.optBoolean("masterMuted"),
                        (event.optInt("flags") & AudioManager.FLAG_SHOW_UI) != 0);
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Volume stream=" + event.optInt("stream", -1) + " level=" + level
                            + " muted=" + (event.optBoolean("muted") || event.optBoolean("masterMuted"))
                            + " flags=" + event.optInt("flags"));
                }
                break;
            case "safe_warning":
                if (controllerReady && !paused) showSafetyWarning();
                break;
            case "dismiss":
                controllerReady = false;
                clearWarning(false);
                island.hideImmediately();
                break;
            case "layout":
                // The island stays on its designed left edge; text follows the system's direction.
                island.setLayoutDirection(event.optInt("direction") == 1 ? 1 : 0);
                break;
            case "a11y":
                // The current stream is carried by each volume event; no audio policy changes are needed.
                break;
            case "restoring":
            case "restored":
                if (kind.equals("restored")) hadController = false;
                controllerReady = false;
                clearWarning(false);
                if (island != null) island.hideImmediately();
                requestedRetryMs = MAX_RETRY_MS;
                break;
            case "error":
                String detail = event.optString("detail", "The volume helper is unavailable.");
                Log.w(TAG, "Helper error " + event.optString("code") + ": " + detail);
                if (event.optBoolean("fatal")) pause(detail);
                else requestedRetryMs = Math.max(1000, Math.min(MAX_RETRY_MS, event.optLong("retryAfterMs", 0)));
                break;
            default:
                pause("The volume helper sent an unsupported event.");
                break;
        }
    }

    private void disconnected(String reason) {
        connection = null;
        controllerReady = false;
        clearWarning(false);
        if (island != null) island.hideImmediately();
        Log.w(TAG, "Helper disconnected: " + reason);
        if (paused && active && hadController) {
            worker.execute(() -> {
                boolean restored = AdbVolumeConnection.restoreStock(new File(getFilesDir(), "adb-private.pk8"),
                        getApplicationInfo().sourceDir);
                handler.post(() -> {
                    if (restored) hadController = false;
                    else showRecoveryNotification("Sony's volume display needs recovery. Use Retry.");
                });
            });
        }
        if (paused || !active) return;
        failures = Math.min(failures + 1, 6);
        long delay = Math.max(requestedRetryMs, Math.min(MAX_RETRY_MS, 1000L << failures));
        // Transient local-daemon downtime retries quietly. A fatal recovery guard never does.
        handler.postDelayed(this::connect, delay);
    }

    private void reportVisibility(boolean fullIslandVisible) {
        if (connection != null && controllerReady) {
            connection.setVisible(fullIslandVisible || warning != null);
        }
    }

    private void showSafetyWarning() {
        if (warning != null) return;
        final int attempt = generation;
        AlertDialog dialog = new AlertDialog.Builder(new ContextThemeWrapper(
                this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
                .setTitle("Raise volume above the safe level?")
                .setMessage("Listening at high volume for long periods can damage your hearing.")
                .setPositiveButton("Continue", (which, button) -> {
                    if (active && controllerReady && generation == attempt && connection != null) {
                        connection.confirmSafeVolume();
                    }
                })
                .setNegativeButton("Cancel", (which, button) -> cancelSafetyWarning(attempt))
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(which -> cancelSafetyWarning(attempt));
        dialog.setOnDismissListener(which -> {
            if (warning == dialog) {
                warning = null;
                if (island != null) reportVisibility(island.isFullyVisible());
            }
        });
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        warning = dialog;
        try {
            dialog.show();
            // A fresh confirmation never defaults to raising the safe limit.
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
            reportVisibility(island.isFullyVisible());
        } catch (RuntimeException e) {
            clearWarning(false);
            pause("The safe-volume warning could not be displayed.");
            Log.e(TAG, "Safety warning display failed", e);
        }
    }

    private void cancelSafetyWarning(int attempt) {
        if (active && controllerReady && generation == attempt && connection != null) {
            connection.cancelSafeVolume();
        }
    }

    private void clearWarning(boolean notifyHelper) {
        if (warning == null) return;
        if (notifyHelper) cancelSafetyWarning(generation);
        AlertDialog old = warning;
        warning = null;
        old.dismiss();
    }

    private void pause(String reason) {
        paused = true;
        controllerReady = false;
        clearWarning(false);
        if (island != null) island.hideImmediately();
        if (connection != null) connection.stopAndRestore();
        showRecoveryNotification(reason);
        Log.w(TAG, "Paused: " + reason);
    }

    private void showRecoveryNotification(String reason) {
        PendingIntent openSettings = PendingIntent.getActivity(this, 0, accessibilitySettings(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent retry = PendingIntent.getBroadcast(this, 0,
                new Intent(this, RetryReceiver.class).setAction(ACTION_RETRY),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_volume)
                .setContentTitle("Volume island paused")
                .setContentText("Retry, or turn it off in accessibility settings.")
                .setStyle(new Notification.BigTextStyle().bigText(reason
                        + " Retry, or turn it off in accessibility settings."))
                .setContentIntent(openSettings)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_volume), "Retry volume island", retry).build())
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(RECOVERY_NOTIFICATION, notification);
    }

    /** Explicit user action only; automatic startup never clears the helper's recovery guard. */
    private void retryExplicitly() {
        if (!active || island == null || !island.isAttachedToWindow()) {
            startActivity(accessibilitySettings());
            return;
        }
        ++generation;
        handler.removeCallbacksAndMessages(null);
        AdbVolumeConnection old = connection;
        connection = null;
        if (old != null) old.stopAndRestore();
        controllerReady = false;
        clearWarning(false);
        island.hideImmediately();
        failures = 0;
        paused = false;
        explicitRecovery = true;
        connect();
    }

    private static Intent accessibilitySettings() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    /** Non-exported: the immutable notification action is the only external entrypoint. */
    public static final class RetryReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_RETRY.equals(intent.getAction())) return;
            VolumeService service = running.get();
            if (service != null && service.active) service.retryExplicitly();
            else context.startActivity(accessibilitySettings());
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() {
        clearWarning(true);
        if (island != null) island.hideImmediately();
    }

    @Override public boolean onUnbind(Intent intent) {
        stopSession();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        stopSession();
        super.onDestroy();
    }

    private void stopSession() {
        if (!active) return;
        active = false;
        ++generation;
        handler.removeCallbacksAndMessages(null);
        if (running.get() == this) running.clear();
        AdbVolumeConnection old = connection;
        connection = null;
        controllerReady = false;
        if (old != null) {
            old.stopAndRestore();
            // Give the helper time to restore Sony before closing a stalled transport.
            new Handler(Looper.getMainLooper()).postDelayed(old::close, 45_000);
        } else if (hadController && worker != null) {
            worker.execute(() -> {
                boolean restored = AdbVolumeConnection.restoreStock(new File(getFilesDir(), "adb-private.pk8"),
                        getApplicationInfo().sourceDir);
                if (!restored) handler.post(() -> showRecoveryNotification(
                        "Sony's volume display needs recovery. Enable the island and use Retry."));
            });
        }
        clearWarning(false);
        if (island != null) {
            island.setVisibilityListener(null);
            island.hideImmediately();
            if (islandAdded) windows.removeViewImmediate(island);
            islandAdded = false;
            island = null;
        }
        if (worker != null) worker.shutdown();
        getSystemService(NotificationManager.class).cancel(RECOVERY_NOTIFICATION);
    }
}
