package com.sonybento.launcher;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Minimal Home app for a TV used as a display: a grid-built clock, connection status, and the
 * HDMI inputs the user has named in the TV's input settings, plus Settings.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "home";
    /** Sent by Sony's system service when an HDMI-CEC device (Apple TV, PS5) asks to be shown. */
    private static final String ONE_TOUCH_PLAY = "com.sony.dtv.intent.action.ONE_TOUCH_PLAY";
    private static final String LAST_INPUT = "last_input";
    private static final String HANDLED_BOOT = "handled_boot";
    /** Tile order, fixed once seen so the tiles never move around (muscle memory). */
    private static final String INPUT_ORDER = "input_order";
    /** Only switch automatically this soon after a boot, never as a surprise later. */
    private static final long BOOT_SWITCH_WINDOW_MS = 3 * 60 * 1000;
    /** How long the input tile fills before the boot switch, so you can see it coming and cancel it. */
    private static final long BOOT_COUNTDOWN_MS = 3000;

    private HomeView view;
    private final List<Intent> targets = new ArrayList<>();
    private final List<String> inputIds = new ArrayList<>();

    /** At a cold boot the HDMI inputs register after Home is already showing, so redraw when they change. */
    private final TvInputManager.TvInputCallback inputChanges = new TvInputManager.TvInputCallback() {
        @Override public void onInputAdded(String inputId) { updateInputs(); }
        @Override public void onInputRemoved(String inputId) { updateInputs(); }
        @Override public void onInputUpdated(String inputId) { updateInputs(); }
        @Override public void onInputStateChanged(String inputId, int state) { updateInputs(); }
        @Override public void onTvInputInfoUpdated(TvInputInfo info) { updateInputs(); }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private long pausedAt;
    private boolean switchAfterBoot;
    /** Set when Home opens (not when returning from a settings page): focus goes to the last-used input. */
    private boolean focusLastUsed = true;

    /**
     * Sony's TV app ignores the HDMI-CEC switch request when it comes back from behind this launcher,
     * leaving a black screen. Re-send the switch with the hardware input id, which it does honour.
     */
    private final BroadcastReceiver oneTouchPlay = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Sony's switch pushes this launcher off screen a few ms before the broadcast arrives here,
            // so also act when it was on screen just now. Never act while another app was in front.
            String inputId = inputForPort(intent.getIntExtra("portId", -1));
            if (inputId == null) return;
            // A device asking to be shown is what the user is watching; remember it for the next boot.
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_INPUT, inputId).apply();
            if (!resumed && SystemClock.uptimeMillis() - pausedAt > 1500) return;
            Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Let Sony's own (ignored) request land first, then send the one that starts the picture.
            handler.postDelayed(() -> {
                try {
                    startActivity(view);
                } catch (RuntimeException e) {
                    // TV app missing; nothing to recover.
                }
            }, 700);
        }
    };

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_TIME_TICK.equals(intent.getAction())
                    || Intent.ACTION_TIME_CHANGED.equals(intent.getAction())
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
                updateTime();
            } else {
                updateStatus();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new HomeView(this);
        setContentView(view);
        registerReceiver(oneTouchPlay, new IntentFilter(ONE_TOUCH_PLAY));
        // After a power cut the TV boots to Home; go straight back to the last input instead.
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (boot != prefs.getInt(HANDLED_BOOT, -2) && SystemClock.elapsedRealtime() < BOOT_SWITCH_WINDOW_MS) {
            switchAfterBoot = true;
        }
        prefs.edit().putInt(HANDLED_BOOT, boot).apply();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(oneTouchPlay);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(updates, filter);
        resumed = true;
        TvInputManager tvInputs = getSystemService(TvInputManager.class);
        if (tvInputs != null) tvInputs.registerCallback(inputChanges, new Handler(Looper.getMainLooper()));
        updateTime();
        updateStatus();
        updateInputs();
    }

    @Override
    protected void onPause() {
        view.cancelCountdown();
        unregisterReceiver(updates);
        resumed = false;
        pausedAt = SystemClock.uptimeMillis();
        TvInputManager tvInputs = getSystemService(TvInputManager.class);
        if (tvInputs != null) tvInputs.unregisterCallback(inputChanges);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        focusLastUsed = true;
        applyFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Any remote button except volume cancels the boot countdown and keeps you on Home.
        boolean volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE;
        if (!volume && view.cancelCountdown()) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
                view.move(keyCode);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                activateFocused();
                return true;
            case KeyEvent.KEYCODE_BACK:
                return true; // Home has nowhere to go back to.
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void updateTime() {
        Date now = new Date();
        String time = DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now).toString();
        view.setTime(time, DateFormat.format("EEEE", now).toString(), DateFormat.format("d MMMM", now).toString());
    }

    /** Named HDMI inputs (last used first, then live signal, then port), followed by Settings. */
    private void updateInputs() {
        TvInputManager tvInputs = getSystemService(TvInputManager.class);
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_INPUT, null);
        List<TvInputInfo> named = new ArrayList<>();
        if (tvInputs != null) {
            for (TvInputInfo info : tvInputs.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null || info.isHidden(this)) continue;
                CharSequence custom = info.loadCustomLabel(this);
                if (custom != null && !custom.toString().trim().isEmpty()) named.add(info);
            }
            // Known inputs keep their saved position; a new input is placed by last use, live signal, then port.
            List<String> order = new ArrayList<>(Arrays.asList(
                    getSharedPreferences(PREFS, MODE_PRIVATE).getString(INPUT_ORDER, "").split(",")));
            named.sort((a, b) -> {
                int pa = order.indexOf(a.getId()), pb = order.indexOf(b.getId());
                if (pa >= 0 || pb >= 0) return Integer.compare(pa < 0 ? Integer.MAX_VALUE : pa, pb < 0 ? Integer.MAX_VALUE : pb);
                if (a.getId().equals(last)) return -1;
                if (b.getId().equals(last)) return 1;
                int state = Integer.compare(tvInputs.getInputState(a.getId()), tvInputs.getInputState(b.getId()));
                return state != 0 ? state : a.loadLabel(this).toString().compareTo(b.loadLabel(this).toString());
            });
            for (TvInputInfo info : named) if (!order.contains(info.getId())) order.add(info.getId());
            order.removeIf(String::isEmpty);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(INPUT_ORDER, String.join(",", order)).apply();
        }

        List<HomeView.Item> items = new ArrayList<>();
        targets.clear();
        inputIds.clear();
        for (TvInputInfo info : named) {
            String name = info.loadCustomLabel(this).toString().trim();
            int state = tvInputs.getInputState(info.getId());
            boolean live = state == TvInputManager.INPUT_STATE_CONNECTED;
            String detail = (live ? "On" : state == TvInputManager.INPUT_STATE_DISCONNECTED ? "Not connected" : "Standby")
                    + " \u00b7 " + info.loadLabel(this);
            items.add(new HomeView.Item(name, detail, iconFor(name), true, live));
            targets.add(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId())));
            inputIds.add(info.getId());
        }
        items.add(new HomeView.Item("Settings", "Picture, sound, network", R.drawable.ic_settings, false, false));
        targets.add(new Intent(Settings.ACTION_SETTINGS));
        inputIds.add(null);
        view.setItems(items);
        applyFocus();

        // The HDMI inputs register a few seconds after Home first shows, so this runs once they appear.
        if (switchAfterBoot && last != null && inputIds.contains(last)) {
            switchAfterBoot = false;
            if (SystemClock.elapsedRealtime() < BOOT_SWITCH_WINDOW_MS) {
                int index = inputIds.indexOf(last);
                view.focusItem(index);
                view.startCountdown(BOOT_COUNTDOWN_MS, () -> open(index));
            }
        }
    }

    /** Finds the HDMI hardware input for a CEC port number by its system label ("HDMI 2"). */
    private String inputForPort(int port) {
        TvInputManager tvInputs = getSystemService(TvInputManager.class);
        if (port <= 0 || tvInputs == null) return null;
        for (TvInputInfo info : tvInputs.getTvInputList()) {
            if (info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null) continue;
            String label = info.loadLabel(this).toString().replace(" ", "");
            if (label.equalsIgnoreCase("HDMI" + port)) return info.getId();
        }
        return null;
    }

    /** Picks a logo from the name the user gave the input; unknown devices keep the square marker. */
    private static int iconFor(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace(" ", "");
        if (n.contains("appletv")) return R.drawable.ic_apple;
        if (n.contains("playstation") || n.startsWith("ps4") || n.startsWith("ps5")) return R.drawable.ic_playstation;
        return 0;
    }

    /** Puts focus on the last-used input once the inputs are known; otherwise on the first tile. */
    private void applyFocus() {
        if (!focusLastUsed || inputIds.size() <= 1) return; // Wait until the HDMI inputs have registered.
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_INPUT, null);
        int index = last == null ? -1 : inputIds.indexOf(last);
        view.focusItem(Math.max(index, 0));
        focusLastUsed = false;
    }

    /** Wi-Fi opens network settings, Remote opens Sony's remote settings, the bottom row opens its target. */
    private void activateFocused() {
        HomeView.Kind kind = view.focusedKind();
        if (kind == HomeView.Kind.ITEM) {
            open(view.focusedItem());
        } else if (kind == HomeView.Kind.WIFI) {
            launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } else if (kind == HomeView.Kind.REMOTE) {
            // The TV settings app's "Remotes & Accessories" page, opened directly as a settings slice.
            launch(new Intent("android.settings.SLICE_SETTINGS")
                    .putExtra("slice_uri", "content://com.android.tv.settings.accessories.sliceprovider/general"));
        }
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void open(int index) {
        if (index < 0 || index >= targets.size()) return;
        String inputId = inputIds.get(index);
        if (inputId != null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_INPUT, inputId).apply();
        }
        try {
            startActivity(targets.get(index));
        } catch (RuntimeException e) {
            // No handler on this TV; stay on Home.
        }
    }

    private void updateStatus() {
        HomeView.Status status = new HomeView.Status();
        readNetwork(status);
        readBluetooth(status);
        view.setStatus(status);
    }

    @SuppressWarnings("deprecation")
    private void readNetwork(HomeView.Status status) {
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = connectivity == null ? null : connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            status.wifiOn = true;
            status.wifiBars = 4;
            status.wifiName = "Ethernet";
            status.wifiDetail = "Connected";
            return;
        }
        WifiManager wifi = getSystemService(WifiManager.class);
        if (wifi == null || caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return;
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null || info.getNetworkId() == -1) return;
        status.wifiOn = true;
        int level = wifi.calculateSignalLevel(info.getRssi());
        int max = Math.max(1, wifi.getMaxSignalLevel());
        status.wifiBars = Math.max(1, Math.round(4f * level / max));
        String ssid = info.getSSID();
        if (ssid != null && !ssid.equals(WifiManager.UNKNOWN_SSID) && ssid.length() > 2) {
            status.wifiName = ssid.replaceAll("^\"|\"$", "");
        }
        String standard = standardName(info.getWifiStandard());
        String speed = info.getLinkSpeed() > 0 ? info.getLinkSpeed() + " Mbps" : "";
        status.wifiDetail = standard.isEmpty() ? (speed.isEmpty() ? "Connected" : speed)
                : speed.isEmpty() ? standard : standard + " · " + speed;
    }

    private static String standardName(int standard) {
        switch (standard) {
            case ScanResult.WIFI_STANDARD_11N: return "Wi-Fi 4";
            case ScanResult.WIFI_STANDARD_11AC: return "Wi-Fi 5";
            case ScanResult.WIFI_STANDARD_11AX: return "Wi-Fi 6";
            case 8: return "Wi-Fi 7"; // ScanResult.WIFI_STANDARD_11BE, added in API 33
            default: return "";
        }
    }

    /** Shows the first connected Bluetooth device, preferring a remote control. */
    private void readBluetooth(HomeView.Status status) {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return;
        try {
            if (!adapter.isEnabled()) {
                status.btName = "Bluetooth";
                status.btDetail = "Off";
                return;
            }
            BluetoothDevice best = null;
            BluetoothDevice pairedRemote = null;
            int others = 0;
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (pairedRemote == null && isRemote(device)) pairedRemote = device;
                if (!isConnected(device)) continue;
                if (best == null || (isRemote(device) && !isRemote(best))) {
                    if (best != null) others++;
                    best = device;
                } else {
                    others++;
                }
            }
            if (best == null) {
                // Bluetooth LE remotes drop the link to save battery and reconnect on the next key press.
                if (pairedRemote != null) status.btName = displayName(pairedRemote);
                return;
            }
            status.btOn = true;
            status.btName = displayName(best);
            status.btDetail = others > 0 ? "Bluetooth · +" + others : "Bluetooth";
        } catch (SecurityException e) {
            status.btName = "Bluetooth";
            status.btDetail = "No permission";
        }
    }

    private static boolean isConnected(BluetoothDevice device) {
        try {
            Method method = BluetoothDevice.class.getMethod("isConnected");
            return Boolean.TRUE.equals(method.invoke(device));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static boolean isRemote(BluetoothDevice device) {
        BluetoothClass type = device.getBluetoothClass();
        boolean peripheral = type != null && type.getMajorDeviceClass() == BluetoothClass.Device.Major.PERIPHERAL;
        return peripheral || device.getType() == BluetoothDevice.DEVICE_TYPE_LE;
    }

    /** "SONY TV VRC 001" reads as "Sony remote"; other devices keep their own name. */
    private static String displayName(BluetoothDevice device) {
        String name = device.getAlias() != null ? device.getAlias() : device.getName();
        if (name == null || name.trim().isEmpty()) return "Bluetooth device";
        if (isRemote(device)) {
            String brand = name.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
            return Character.toUpperCase(brand.charAt(0)) + brand.substring(1) + " remote";
        }
        return name.trim();
    }
}
