package com.sonybento.volume;

import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/** Shell-UID entrypoint. Registers the app-owned controller; never adjusts native volume input. */
public final class ShellVolumeHelper {
    private static final String DESCRIPTOR = "android.media.IVolumeController";
    private static final File DIRECTORY = new File("/data/local/tmp/sony-bento-volume");
    private final OutputStream output;
    private final RecoveryGuard guard;
    private final Audio audio;
    private final Handler worker;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final ArrayDeque<Long> replacements = new ArrayDeque<>();
    private final IBinder controller;
    private final Object volumeInterface;
    private boolean stopping, tookController, ownsController, warningPending, visible;

    private ShellVolumeHelper(OutputStream output, RecoveryGuard guard, Handler worker, boolean restoreOnly) throws Exception {
        this.output = output;
        this.guard = guard;
        this.worker = worker;
        this.tookController = guard.unclean();
        this.audio = new Audio();
        if (restoreOnly) {
            controller = null;
            volumeInterface = null;
        } else {
            Bundle configuration = new Bundle();
            configuration.putInt("v", 1);
            Class<?> stub = Class.forName(DESCRIPTOR + "$Stub");
            for (String name : new String[]{"displaySafeVolumeWarning", "volumeChanged", "masterMuteChanged",
                    "setLayoutDirection", "dismiss", "setA11yMode"}) {
                configuration.putInt(name, transaction(stub, name));
            }
            configuration.putBinder("audio", audio.binder);
            configuration.putInt("isMasterMute", transaction(Class.forName("android.media.IAudioService$Stub"), "isMasterMute"));
            Bundle session = appController("attach", configuration);
            controller = session == null ? null : session.getBinder("controller");
            if (controller == null || !DESCRIPTOR.equals(controller.getInterfaceDescriptor())) {
                throw new GuardException("ui_unavailable", false, "The volume overlay is not ready yet.");
            }
            visible = session.getBoolean("visible");
            warningPending = session.getBoolean("warningPending");
            volumeInterface = stub.getMethod("asInterface", IBinder.class).invoke(null, controller);
            controller.linkToDeath(() -> worker.post(this::stop), 0);
        }
    }

    public static void main(String[] args) {
        OutputStream output = new FileOutputStream(FileDescriptor.out);
        if (Process.myUid() != 2000) {
            error(output, "wrong_uid", true, "Volume helper requires the authorized shell identity.", 0);
            return;
        }
        boolean reset = args.length == 1 && args[0].equals("--reset-recovery");
        boolean restoreOnly = args.length == 1 && args[0].equals("--restore-only");
        if (args.length != 0 && !reset && !restoreOnly) {
            error(output, "unsupported", true, "Unknown volume helper option.", 0);
            return;
        }
        HandlerThread thread = null;
        try {
            secureDirectory();
            if (restoreOnly) emit(output, event("restoring"));
            try (RecoveryGuard guard = acquireGuard(reset, restoreOnly)) {
                if (restoreOnly && !guard.unclean()) {
                    if (audioControllerToken().equals("null")) {
                        guard.disable();
                        throw new GuardException("recovery_unverified", true,
                                "Sony's volume display needs explicit recovery.");
                    }
                    emit(output, event("restored").put("verified", true));
                    return;
                }
                long wait = guard.waitMillis(SystemClock.elapsedRealtime());
                if (wait > 0) {
                    error(output, "cooldown", false, "Sony volume is in its recovery cooldown.", wait);
                    return;
                }
                thread = new HandlerThread("SonyBentoVolume");
                thread.start();
                boolean needsRestoration = restoreOnly || guard.needsRestoration();
                ShellVolumeHelper helper = new ShellVolumeHelper(output, guard, new Handler(thread.getLooper()), needsRestoration);
                if (needsRestoration) {
                    // Resume an interrupted explicit restoration. A plain helper disconnect does
                    // not require restoration: AudioService still holds the app-owned Binder.
                    helper.tookController = true;
                    helper.worker.post(helper::stop);
                } else {
                    emit(output, event("hello").put("uid", Process.myUid()));
                    helper.worker.postDelayed(() -> { if (!helper.ownsController) helper.disconnected(); }, 30_000);
                    Thread reader = new Thread(() -> helper.readCommands(new FileInputStream(FileDescriptor.in)),
                            "SonyBentoVolumeInput");
                    reader.setDaemon(true);
                    reader.start();
                }
                helper.finished.await();
            }
        } catch (GuardException e) {
            error(output, e.code, e.fatal, e.getMessage(), 0);
        } catch (Throwable e) {
            error(output, "unsupported", true, "The volume helper could not initialize on this firmware.", 0);
        } finally {
            if (thread != null) thread.quitSafely();
        }
        // app_process also owns Binder threads. Do not leave an orphan after the session ends.
        System.exit(0);
    }

    private static RecoveryGuard acquireGuard(boolean reset, boolean restoreOnly) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        for (;;) {
            try { return RecoveryGuard.open(DIRECTORY, reset); }
            catch (GuardException e) {
                if (!restoreOnly || !e.code.equals("already_running") || System.nanoTime() >= deadline) throw e;
                // The old helper may still be completing its EOF restoration.
                // Waiting for its lock cannot issue another SystemUI crash.
                Thread.sleep(100);
            }
        }
    }

    private static void secureDirectory() throws Exception {
        Path path = DIRECTORY.toPath();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path);
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || Os.lstat(path.toString()).st_uid != 2000) {
            throw new IOException("Unsafe helper directory");
        }
        Os.chmod(path.toString(), 0700);
    }

    private void readCommands(InputStream input) {
        try {
            for (;;) {
                String command = readLine(input);
                if (command == null) break;
                worker.post(() -> command(command));
                if (command.equals("STOP")) return;
            }
        } catch (IOException ignored) { }
        worker.post(this::disconnected);
    }

    private void disconnected() {
        if (stopping) return;
        if (tookController && controller != null && controller.isBinderAlive()) {
            Bundle session = null;
            try { session = appController("state", null); }
            catch (Exception ignored) { }
            if (session == null || (session.getBoolean("active")
                    && controller.equals(session.getBinder("controller")))) {
                // The overlay is still attached. Release the helper lock without restarting Sony;
                // callbacks remain live and the next helper can re-register this exact Binder.
                // A timed-out provider query is unknown, not proof that the overlay was stopped.
                stopping = true;
                worker.removeCallbacksAndMessages(null);
                finished.countDown();
                return;
            }
        }
        stop();
    }

    private void command(String command) {
        if (stopping) return;
        try {
            switch (command) {
                case "READY":
                    if (!ownsController) {
                        guard.begin();
                        tookController = true; // The setter may succeed remotely before a local exception.
                        register();
                    }
                    break;
                case "VISIBILITY 1":
                case "VISIBILITY 0":
                    visible = command.endsWith("1");
                    if (ownsController) audio.visible(volumeInterface, visible);
                    break;
                case "CONFIRM_SAFE":
                    if (ownsController && warningPending) {
                        warningPending = false;
                        audio.call("disableSafeMediaVolume", new Class<?>[]{String.class}, "com.android.shell");
                        volume(3, 0);
                    }
                    break;
                case "CANCEL_SAFE": warningPending = false; break;
                case "SAFE_WARNING": if (ownsController) warningPending = true; break;
                case "DISMISSED": controllerDismissed(); break;
                case "STOP": stop(); break;
                default: fail("protocol", "The volume helper received an invalid control message.");
            }
        } catch (EventWriteException e) { disconnected(); }
        catch (Exception e) { fail("controller", "The volume controller could not complete the request."); }
    }

    private void register() throws Exception {
        if (stopping) return;
        audio.call("setVolumeController", new Class<?>[]{audio.volumeClass}, volumeInterface);
        ownsController = true;
        audio.visible(volumeInterface, visible);
        emit(output, event("ready").put("uid", Process.myUid()));
        volume(3, 0);
    }

    private void controllerDismissed() {
        if (stopping) return;
        // Direct app callbacks report replacement; the privileged registration alone
        // remains here. Never send dismiss back to the app and create a relay loop.
        ownsController = false;
        warningPending = false;
        visible = false;
        long now = System.nanoTime();
        while (!replacements.isEmpty() && now - replacements.peekFirst() > 60_000_000_000L) {
            replacements.removeFirst();
        }
        if (replacements.size() >= 2) {
            fail("controller", "Another volume controller repeatedly replaced the island.");
        } else {
            replacements.addLast(now);
            worker.removeCallbacks(reacquire);
            worker.postDelayed(reacquire, 750);
        }
    }

    private final Runnable reacquire = () -> {
        if (stopping || ownsController) return;
        try { register(); }
        catch (EventWriteException e) { disconnected(); }
        catch (Exception e) { fail("controller", "The volume controller could not reconnect."); }
    };

    private void volume(int stream, int flags) throws Exception {
        int level = (Integer) audio.call("getStreamVolume", new Class<?>[]{int.class}, stream);
        int min = (Integer) audio.call("getStreamMinVolume", new Class<?>[]{int.class}, stream);
        int max = (Integer) audio.call("getStreamMaxVolume", new Class<?>[]{int.class}, stream);
        boolean muted = (Boolean) audio.call("isStreamMute", new Class<?>[]{int.class}, stream);
        boolean masterMuted = (Boolean) audio.call("isMasterMute", new Class<?>[0]);
        emit(output, event("volume").put("stream", stream).put("flags", flags).put("level", level)
                .put("min", min).put("max", max).put("muted", muted).put("masterMuted", masterMuted));
    }

    private void fail(String code, String detail) {
        error(output, code, true, detail, 0);
        stop();
    }

    private void stop() {
        if (stopping) return;
        stopping = true;
        warningPending = false;
        worker.removeCallbacksAndMessages(null);
        try {
            if (tookController) {
                try { emit(output, event("restoring")); } catch (IOException ignored) { }
                try { audio.visible(volumeInterface, false); } catch (Exception ignored) { }
                // Reserve on disk BEFORE the one crash attempt, including any failing attempt.
                guard.reserveRecovery(SystemClock.elapsedRealtime());
                try { restoreSony(); guard.completeRecovery(); }
                catch (Exception e) {
                    guard.disable();
                    error(output, "recovery_disabled", true,
                            "Sony volume restoration could not be verified; automatic takeover is disabled.", 0);
                    return;
                }
            }
            try { emit(output, event("restored").put("verified", true)); }
            catch (IOException | JSONException ignored) { }
        } catch (Exception e) {
            // Never crash SystemUI if the durable guard cannot be written.
            error(output, "recovery_disabled", true,
                    "The restoration guard could not be saved; automatic takeover must remain disabled.", 0);
        } finally { finished.countDown(); }
    }

    private static void restoreSony() throws Exception {
        ApplicationInfo info = (ApplicationInfo) service("package", "android.content.pm.IPackageManager")
                .call("getApplicationInfo", new Class<?>[]{String.class, int.class, int.class},
                        "com.android.systemui", 0, 0);
        if (info == null || !"com.android.systemui".equals(info.packageName) || info.uid < 0) {
            throw new IOException("SystemUI identity unavailable");
        }
        int pid = parsePid(runCommand(2000, "/system/bin/pidof", "com.android.systemui"));
        if (pid == Process.myPid()) throw new IOException("Invalid SystemUI PID");
        String oldController = audioControllerToken();
        String identity = processIdentity(pid, info.uid);
        if (!identity.equals(processIdentity(pid, info.uid))) throw new IOException("SystemUI changed");
        runCommand(5000, "/system/bin/am", "crash", "--user", "0", Integer.toString(pid));
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                int replacement = parsePid(runCommand(1000, "/system/bin/pidof", "com.android.systemui"));
                if (replacement != pid) {
                    processIdentity(replacement, info.uid);
                    String current = audioControllerToken();
                    if (!current.equals("null") && !current.equals(oldController)) return;
                }
            } catch (IOException ignored) { }
            Thread.sleep(250);
        }
        throw new IOException("SystemUI volume restoration was not observed");
    }

    private static String audioControllerToken() throws Exception {
        String dump = runCommandBounded(2000, 524288, "/system/bin/dumpsys", "audio");
        for (String line : dump.split("\n")) {
            String value = line.trim();
            String prefix = "mVolumeController=VolumeController(";
            if (value.startsWith(prefix)) {
                int end = value.indexOf(",mVisible=", prefix.length());
                if (end > prefix.length()) return value.substring(prefix.length(), end);
            }
        }
        throw new IOException("Volume controller state unavailable");
    }

    static int parsePid(String output) throws IOException {
        String value = output.trim();
        if (!value.matches("[1-9][0-9]{0,8}")) throw new IOException("Ambiguous SystemUI PID");
        try {
            int pid = Integer.parseInt(value);
            if (pid <= 1) throw new IOException("Invalid SystemUI PID");
            return pid;
        } catch (NumberFormatException e) { throw new IOException("Invalid SystemUI PID"); }
    }

    private static String processIdentity(int pid, int uid) throws IOException {
        Path directory = new File("/proc/" + pid).toPath();
        byte[] cmdline = Files.readAllBytes(directory.resolve("cmdline"));
        if (!isSystemUiCommand(cmdline)) throw new IOException("Wrong SystemUI process");
        String status = new String(Files.readAllBytes(directory.resolve("status")), StandardCharsets.US_ASCII);
        boolean matched = false;
        for (String line : status.split("\n")) if (line.startsWith("Uid:")) {
            String[] ids = line.substring(4).trim().split("\\s+");
            if (ids.length != 4) throw new IOException("Invalid process UID");
            for (String id : ids) if (!id.equals(Integer.toString(uid))) throw new IOException("Wrong process UID");
            matched = true;
        }
        if (!matched) throw new IOException("Missing process UID");
        String stat = new String(Files.readAllBytes(directory.resolve("stat")), StandardCharsets.US_ASCII);
        int end = stat.lastIndexOf(')');
        String[] fields = end >= 0 ? stat.substring(end + 1).trim().split("\\s+") : new String[0];
        if (fields.length < 20 || !fields[19].matches("[0-9]+")) throw new IOException("Invalid process start time");
        return fields[19];
    }

    private static String runCommand(int timeoutMs, String... argv) throws Exception {
        return runCommandBounded(timeoutMs, 4096, argv);
    }

    static boolean isSystemUiCommand(byte[] cmdline) {
        byte[] expected = "com.android.systemui\0".getBytes(StandardCharsets.US_ASCII);
        if (cmdline.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (cmdline[i] != expected[i]) return false;
        for (int i = expected.length; i < cmdline.length; i++) if (cmdline[i] != 0) return false;
        return true;
    }

    private static String runCommandBounded(int timeoutMs, int maxBytes, String... argv) throws Exception {
        java.lang.Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[256];
                int n;
                while ((n = input.read(buffer)) >= 0) {
                    if (bytes.size() + n > maxBytes) { process.destroy(); return; }
                    bytes.write(buffer, 0, n);
                }
            } catch (IOException ignored) { }
        }, "SonyVolumeRecoveryOutput");
        drain.setDaemon(true);
        drain.start();
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroy();
            throw new IOException("Recovery command timed out");
        }
        drain.join(500);
        if (drain.isAlive() || process.exitValue() != 0) throw new IOException("Recovery command failed");
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int transaction(Class<?> stub, String name) throws Exception {
        Field field = stub.getDeclaredField("TRANSACTION_" + name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static Bundle appController(String method, Bundle configuration) throws Exception {
        String authority = "com.sonybento.launcher.volume";
        Service activity = service("activity", "android.app.IActivityManager");
        IBinder token = new Binder();
        Object holder = activity.call("getContentProviderExternal",
                new Class<?>[]{String.class, int.class, IBinder.class, String.class}, authority, 0, token, "BentoVolume");
        if (holder == null) return null;
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            Class<?> attribution = Class.forName("android.content.AttributionSource");
            Object caller = attribution.getConstructor(int.class, String.class, String.class)
                    .newInstance(2000, "com.android.shell", null);
            return (Bundle) Class.forName("android.content.IContentProvider").getMethod("call",
                    attribution, String.class, String.class, String.class, Bundle.class)
                    .invoke(provider, caller, authority, method, null, configuration);
        } finally {
            activity.call("removeContentProviderExternal", new Class<?>[]{String.class, IBinder.class}, authority, token);
        }
    }

    private static final class Audio extends Service {
        final Class<?> volumeClass = Class.forName(DESCRIPTOR);
        Audio() throws Exception { super("audio", "android.media.IAudioService"); }
        void visible(Object controller, boolean visible) throws Exception {
            call("notifyVolumeControllerVisible", new Class<?>[]{volumeClass, boolean.class}, controller, visible);
        }
    }
    private static Service service(String name, String type) throws Exception { return new Service(name, type); }
    private static class Service {
        final Class<?> type;
        final Object proxy;
        final IBinder binder;
        Service(String name, String className) throws Exception {
            type = Class.forName(className);
            binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, name);
            if (binder == null) throw new IOException("System service unavailable");
            proxy = Class.forName(className + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        }
        Object call(String method, Class<?>[] parameters, Object... values) throws Exception {
            Method target = type.getMethod(method, parameters);
            return target.invoke(proxy, values);
        }
    }

    static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (;;) {
            int next = input.read();
            if (next < 0) { if (bytes.size() != 0) throw new EOFExceptionCompat(); return null; }
            if (next == '\n') return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
            if (next < 32 || next > 126 || bytes.size() >= 64) throw new IOException("Invalid command line");
            bytes.write(next);
        }
    }
    private static JSONObject event(String type) {
        try { return new JSONObject().put("v", 1).put("type", type); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }
    private static synchronized void emit(OutputStream output, JSONObject event) throws IOException {
        byte[] bytes = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) throw new IOException("Oversized event");
        try { output.write(bytes); output.flush(); }
        catch (IOException e) { throw new EventWriteException(e); }
    }
    private static void error(OutputStream output, String code, boolean fatal, String detail, long retry) {
        try { emit(output, event("error").put("code", code).put("fatal", fatal).put("detail", detail)
                .put("retryAfterMs", retry)); }
        catch (Exception ignored) { }
    }
    private static final class EOFExceptionCompat extends IOException {
        private static final long serialVersionUID = 1L;
    }
    private static final class EventWriteException extends IOException {
        private static final long serialVersionUID = 1L;
        EventWriteException(IOException cause) { super(cause); }
    }

    /** Durable single-owner guard; pure Java so failure/restart cases can be tested without a TV. */
    static final class RecoveryGuard implements AutoCloseable {
        static final long COOLDOWN_MS = 60_000, FAILURE_WINDOW_MS = 600_000;
        final Path directory, stateFile;
        final FileChannel channel;
        final FileLock lock;
        final String bootId;
        String phase = "idle";
        int failures;
        long lastRecovery, until;
        private RecoveryGuard(Path directory, FileChannel channel, FileLock lock, String bootId) {
            this.directory = directory; this.stateFile = directory.resolve("recovery.state");
            this.channel = channel; this.lock = lock;
            this.bootId = bootId;
        }
        static RecoveryGuard open(File directory, boolean reset) throws IOException {
            String boot = new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()),
                    StandardCharsets.US_ASCII).trim();
            return open(directory, reset, boot);
        }
        static RecoveryGuard open(File directory, boolean reset, String bootId) throws IOException {
            if (!bootId.matches("[0-9a-fA-F-]{36}")) throw new IOException("Boot identity unavailable");
            FileChannel channel = FileChannel.open(directory.toPath().resolve("helper.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException e) { lock = null; }
            if (lock == null) { channel.close(); throw new GuardException("already_running", false, "A volume helper already owns this session."); }
            RecoveryGuard guard = new RecoveryGuard(directory.toPath(), channel, lock, bootId);
            try { if (reset) guard.reset(); else guard.load(); return guard; }
            catch (IOException e) { guard.close(); throw e; }
        }
        private void reset() throws IOException {
            try { load(); }
            catch (IOException e) { phase = "active"; }
            // Reset the budget, not the obligation to restore an existing or unknown controller.
            phase = phase.equals("idle") || phase.equals("cooldown") ? "idle" : "active";
            failures = 0;
            lastRecovery = until = 0;
            save();
        }
        private void load() throws IOException {
            if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) return;
            if (Files.isSymbolicLink(stateFile) || Files.size(stateFile) > 1024) throw corrupt();
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(stateFile, LinkOption.NOFOLLOW_LINKS)) { properties.load(input); }
            catch (IllegalArgumentException e) { throw corrupt(); }
            try {
                int version = Integer.parseInt(properties.getProperty("version", "1"));
                if (version < 1 || version > 2) throw corrupt();
                phase = properties.getProperty("phase", "");
                failures = Integer.parseInt(properties.getProperty("failures", "-1"));
                lastRecovery = Long.parseLong(properties.getProperty("lastRecovery", "-1"));
                until = Long.parseLong(properties.getProperty("until", "-1"));
                if (!phase.matches("idle|active|restoring|cooldown|disabled") || failures < 0 || failures > 2
                        || lastRecovery < 0 || until < 0) throw corrupt();
                if (!properties.getProperty("bootId", "").matches("[0-9a-fA-F-]{36}")) throw corrupt();
                boolean changed = version == 1;
                // The old format wrote cooldown before restoration was even attempted.
                if (version == 1 && phase.equals("cooldown")) phase = "restoring";
                if (!bootId.equals(properties.getProperty("bootId")) && !phase.equals("disabled")) {
                    // A Binder from an earlier kernel boot cannot survive. Normal power-on
                    // must not crash fresh SystemUI or count as an abnormal helper failure.
                    phase = "idle";
                    failures = 0;
                    lastRecovery = until = 0;
                    changed = true;
                }
                if (changed) save();
            } catch (IllegalArgumentException e) { throw corrupt(); }
        }
        long waitMillis(long now) throws IOException {
            if (phase.equals("disabled") || now < lastRecovery) throw new GuardException("recovery_disabled", true,
                    "Automatic volume takeover is disabled; use explicit recovery to retry.");
            return phase.equals("cooldown") || phase.equals("restoring") ? Math.max(0, until - now) : 0;
        }
        boolean unclean() { return phase.equals("active") || phase.equals("restoring"); }
        boolean needsRestoration() { return phase.equals("restoring"); }
        void begin() throws IOException { phase = "active"; save(); }
        void reserveRecovery(long now) throws IOException {
            failures = lastRecovery > 0 && now >= lastRecovery && now - lastRecovery < FAILURE_WINDOW_MS
                    ? Math.min(2, failures + 1) : 1;
            lastRecovery = now;
            until = now + COOLDOWN_MS;
            phase = failures >= 2 ? "disabled" : "restoring";
            save();
        }
        void completeRecovery() throws IOException {
            if (phase.equals("disabled")) return;
            if (!phase.equals("restoring")) throw new IOException("No volume restoration was reserved");
            phase = "cooldown";
            save();
        }
        void disable() throws IOException { phase = "disabled"; save(); }
        private void save() throws IOException {
            byte[] bytes = ("version=2\nphase=" + phase + "\nfailures=" + failures + "\nlastRecovery=" + lastRecovery
                    + "\nuntil=" + until + "\nbootId=" + bootId + "\n").getBytes(StandardCharsets.US_ASCII);
            Path temporary = Files.createTempFile(directory, ".recovery-", ".state");
            try {
                try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer data = ByteBuffer.wrap(bytes);
                    while (data.hasRemaining()) file.write(data);
                    file.force(true);
                }
                Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                // Sync the directory entry as well as file contents before allowing a recovery crash.
                try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
            } finally { Files.deleteIfExists(temporary); }
        }
        private static GuardException corrupt() {
            return new GuardException("recovery_disabled", true, "The recovery marker is invalid; explicit recovery is required.");
        }
        @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }
    private static final class GuardException extends IOException {
        private static final long serialVersionUID = 1L;
        final String code;
        final boolean fatal;
        GuardException(String code, boolean fatal, String message) { super(message); this.code = code; this.fatal = fatal; }
    }
}
