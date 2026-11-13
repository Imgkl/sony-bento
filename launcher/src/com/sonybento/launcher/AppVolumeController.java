package com.sonybento.launcher;

import android.content.Context;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** AudioService holds this app-owned Binder, so losing the ADB helper cannot remove the HUD. */
final class AppVolumeController extends Binder implements AutoCloseable {
    static final String DESCRIPTOR = "android.media.IVolumeController";
    static final String[] CALLBACKS = {"displaySafeVolumeWarning", "volumeChanged", "masterMuteChanged",
            "setLayoutDirection", "dismiss", "setA11yMode"};
    interface Listener { void onEvent(AppVolumeController source, JSONObject event); }

    private final AudioManager audio;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("BentoVolumeCallbacks");
    private final Handler worker;
    private volatile Configuration configuration;
    private volatile boolean closed, visible, warningPending;

    AppVolumeController(Context context, Listener listener) {
        this.audio = context.getSystemService(AudioManager.class);
        this.listener = listener;
        attachInterface(null, DESCRIPTOR);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    // Called only through the shell-only provider. Hidden transaction IDs are resolved on this
    // firmware by the privileged helper, never reflected from an ordinary app process.
    void configure(Bundle values) throws RemoteException {
        if (closed || values == null || values.getInt("v") != 1) throw new IllegalArgumentException("Invalid handshake");
        Map<Integer, String> callbacks = new HashMap<>();
        for (String name : CALLBACKS) {
            int code = values.getInt(name, -1);
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION
                    || callbacks.put(code, name) != null) throw new IllegalArgumentException("Invalid callbacks");
        }
        IBinder service = values.getBinder("audio");
        int masterMute = values.getInt("isMasterMute", -1);
        if (service == null || !"android.media.IAudioService".equals(service.getInterfaceDescriptor())
                || masterMute < FIRST_CALL_TRANSACTION || masterMute > LAST_CALL_TRANSACTION) {
            throw new IllegalArgumentException("Invalid audio service");
        }
        configuration = new Configuration(callbacks, service, masterMute);
    }

    Bundle state() {
        Bundle result = new Bundle();
        result.putInt("v", 1);
        result.putBoolean("active", true);
        result.putBinder("controller", this);
        result.putBoolean("visible", visible);
        result.putBoolean("warningPending", warningPending);
        return result;
    }

    void setVisible(boolean value) { visible = value; }
    void clearWarning() { warningPending = false; }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(DESCRIPTOR);
            return true;
        }
        if (Binder.getCallingUid() != 1000) throw new SecurityException("Only AudioService may send callbacks");
        Configuration current = configuration;
        String name = current == null ? null : current.callbacks.get(code);
        if (name == null || closed) return false;
        data.enforceInterface(DESCRIPTOR);
        int first = name.equals("dismiss") ? 0 : data.readInt();
        int second = name.equals("volumeChanged") ? data.readInt() : 0;
        if (data.dataAvail() != 0) return false;
        worker.post(() -> callback(current, name, first, second));
        return true;
    }

    private void callback(Configuration current, String name, int first, int second) {
        if (closed) return;
        try {
            JSONObject event = new JSONObject().put("v", 1);
            switch (name) {
                case "volumeChanged":
                case "masterMuteChanged":
                    int stream = name.equals("volumeChanged") ? first : AudioManager.STREAM_MUSIC;
                    int flags = name.equals("volumeChanged") ? second : first;
                    event.put("type", "volume").put("stream", stream).put("flags", flags)
                            .put("level", audio.getStreamVolume(stream)).put("min", audio.getStreamMinVolume(stream))
                            .put("max", audio.getStreamMaxVolume(stream)).put("muted", audio.isStreamMute(stream))
                            .put("masterMuted", current.isMasterMute());
                    break;
                case "displaySafeVolumeWarning":
                    warningPending = true;
                    event.put("type", "safe_warning").put("flags", first);
                    break;
                case "dismiss":
                    warningPending = false;
                    visible = false;
                    event.put("type", "dismiss");
                    break;
                case "setLayoutDirection": event.put("type", "layout").put("direction", first); break;
                case "setA11yMode": event.put("type", "a11y").put("mode", first); break;
                default: return;
            }
            listener.onEvent(this, event);
        } catch (Exception e) {
            try {
                listener.onEvent(this, new JSONObject().put("v", 1).put("type", "error")
                        .put("code", "controller").put("fatal", true)
                        .put("detail", "The volume callback could not be read."));
            } catch (Exception ignored) { }
        }
    }

    @Override public void close() {
        closed = true;
        worker.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private static final class Configuration {
        final Map<Integer, String> callbacks;
        final IBinder audio;
        final int masterMuteCode;
        Configuration(Map<Integer, String> callbacks, IBinder audio, int masterMuteCode) {
            this.callbacks = callbacks; this.audio = audio; this.masterMuteCode = masterMuteCode;
        }
        boolean isMasterMute() throws RemoteException {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("android.media.IAudioService");
                if (!audio.transact(masterMuteCode, data, reply, 0)) throw new RemoteException("Audio query unavailable");
                reply.readException();
                return reply.readInt() != 0;
            } finally { data.recycle(); reply.recycle(); }
        }
    }
}
