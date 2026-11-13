package com.sonybento.volume;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import org.json.JSONException;
import org.json.JSONObject;

/** One authenticated loopback connection, restricted to the volume helper. Never enrolls keys. */
public final class AdbVolumeConnection implements AutoCloseable {
    public interface Listener {
        void onEvent(JSONObject event);
        void onClosed(String reason);
    }

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43;
    private static final int VERSION = 0x01000000, LIMIT = 4096;
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b,
            0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final File keyFile;
    private final String apkPath;
    private final Listener listener;
    private final int port;
    private final int timeoutMs;
    private final boolean explicitRecovery;
    private final boolean restoreOnly;
    private final Object lock = new Object();
    private final ArrayDeque<String> commands = new ArrayDeque<>();
    private volatile boolean closed;
    private volatile Socket socket;
    private volatile long writeDeadline;
    private boolean started, stopping, readyQueued;
    private Boolean lastVisibility;
    private boolean hello, helperReady, restoring, restored, cleanRestoration;
    private long readyDeadline, restorationDeadline;

    public AdbVolumeConnection(File privateKey, String apkPath, Listener listener) {
        this(privateKey, apkPath, listener, false);
    }

    public AdbVolumeConnection(File privateKey, String apkPath, Listener listener, boolean explicitRecovery) {
        this(privateKey, apkPath, listener, 5555, 10_000, explicitRecovery);
    }

    // Tests may change only the loopback port and deadline, never the remote command.
    AdbVolumeConnection(File privateKey, String apkPath, Listener listener, int port, int timeoutMs) {
        this(privateKey, apkPath, listener, port, timeoutMs, false);
    }

    AdbVolumeConnection(File privateKey, String apkPath, Listener listener, int port, int timeoutMs,
            boolean explicitRecovery) {
        this(privateKey, apkPath, listener, port, timeoutMs, explicitRecovery, false);
    }

    private AdbVolumeConnection(File privateKey, String apkPath, Listener listener, int port, int timeoutMs,
            boolean explicitRecovery, boolean restoreOnly) {
        if (privateKey == null || listener == null || apkPath == null
                || !apkPath.startsWith("/") || !apkPath.endsWith(".apk")
                || apkPath.length() > 2048 || apkPath.indexOf('\0') >= 0
                || apkPath.indexOf('\n') >= 0 || apkPath.indexOf('\r') >= 0
                || port < 1 || port > 65535 || timeoutMs < 100 || timeoutMs > 60_000) {
            throw new IllegalArgumentException("Invalid volume bridge configuration");
        }
        this.keyFile = privateKey;
        this.apkPath = apkPath;
        this.listener = listener;
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.explicitRecovery = explicitRecovery;
        this.restoreOnly = restoreOnly;
        this.stopping = restoreOnly;
    }

    /** Recovery after an interrupted stop; never sends READY or takes volume ownership. */
    public static boolean restoreStock(File key, String apkPath) {
        AdbVolumeConnection recovery = new AdbVolumeConnection(key, apkPath, new Listener() {
            public void onEvent(JSONObject event) { }
            public void onClosed(String reason) { }
        }, 5555, 10_000, false, true);
        recovery.run();
        return recovery.cleanRestoration;
    }

    /** Blocks one worker thread. Listener callbacks run on that same thread. */
    public void run() {
        synchronized (lock) {
            if (started) return;
            started = true;
        }
        String reason = "Volume helper disconnected.";
        Timer watchdog = new Timer("VolumeAdbWriteDeadline", true);
        watchdog.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                long deadline = writeDeadline;
                Socket current = socket;
                if (deadline != 0 && expired(deadline) && current != null) {
                    try { current.close(); } catch (IOException ignored) { }
                }
            }
        }, 200, 200);
        try {
            if (closed) throw new IOException("closed");
            RSAPrivateKey key = readKey(keyFile);
            Socket connection = new Socket();
            synchronized (lock) {
                if (closed) { connection.close(); throw new IOException("closed"); }
                socket = connection;
            }
            long deadline = after(timeoutMs);
            connection.connect(new InetSocketAddress(
                    InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), remaining(deadline));
            connection.setTcpNoDelay(true);
            InputStream input = connection.getInputStream();
            OutputStream output = connection.getOutputStream();
            write(output, CNXN, VERSION, LIMIT, utf8("host::features=shell_v2\0"));
            boolean signed = false, authenticated = false;
            for (int i = 0; i < 8; i++) {
                Packet packet = read(connection, input, deadline, false);
                if (packet.command == CNXN) {
                    if ((packet.arg0 != VERSION && packet.arg0 != VERSION + 1)
                            || packet.arg1 < LIMIT || !hasShellV2(packet.data)) {
                        throw new BridgeException("unsupported", true, "ADB shell-v2 is unavailable.");
                    }
                    authenticated = true;
                    break;
                }
                if (packet.command != AUTH || packet.arg0 != 1 || packet.arg1 != 0
                        || packet.data.length != 20) throw protocol();
                if (signed) throw new BridgeException("unauthorized", true,
                        "The local ADB key needs explicit authorization.");
                write(output, AUTH, 2, 0, sign(key, packet.data));
                signed = true;
            }
            if (!authenticated) throw protocol();
            write(output, OPEN, 1, 0, utf8(shellCommand(apkPath)
                    + (restoreOnly ? " --restore-only" : explicitRecovery ? " --reset-recovery" : "") + "\0"));
            Packet opened = read(connection, input, deadline, false);
            if (opened.command != OKAY || opened.arg0 == 0 || opened.arg1 != 1
                    || opened.data.length != 0) throw protocol();
            int remote = opened.arg0;
            long helloDeadline = after(timeoutMs), ackDeadline = 0, stopDeadline = 0;
            boolean outstanding = false;
            ShellDecoder decoder = new ShellDecoder(this::event);
            while (!closed) {
                if (!hello && !restoring && expired(helloDeadline)) throw new SocketTimeoutException();
                if (restorationDeadline != 0 && expired(restorationDeadline)) throw new SocketTimeoutException();
                if (!stopping && !restoring && readyDeadline != 0 && !helperReady && expired(readyDeadline)) {
                    throw new SocketTimeoutException();
                }
                if (outstanding && expired(ackDeadline)) throw new SocketTimeoutException();
                if (stopDeadline != 0 && expired(stopDeadline)) throw new SocketTimeoutException();
                if (!outstanding) {
                    String command;
                    synchronized (lock) { command = commands.pollFirst(); }
                    if (command != null) {
                        byte[] data = utf8(command + "\n");
                        byte[] frame = ByteBuffer.allocate(data.length + 5).order(ByteOrder.LITTLE_ENDIAN)
                                .put((byte) 0).putInt(data.length).put(data).array();
                        write(output, WRTE, 1, remote, frame);
                        outstanding = true;
                        ackDeadline = after(timeoutMs);
                        if ("READY".equals(command)) readyDeadline = after(timeoutMs);
                        if ("STOP".equals(command)) stopDeadline = after(30_000);
                    }
                }
                Packet packet = read(connection, input, after(timeoutMs), true);
                if (packet == null) continue;
                if (packet.arg0 != remote || packet.arg1 != 1) throw protocol();
                if (packet.command == OKAY && packet.data.length == 0 && outstanding) {
                    outstanding = false;
                } else if (packet.command == WRTE) {
                    // ACK before dispatch: callbacks may queue commands but cannot block ADB flow control.
                    write(output, OKAY, 1, remote, EMPTY);
                    decoder.accept(packet.data);
                } else if (packet.command == CLSE && packet.data.length == 0) {
                    write(output, CLSE, 1, remote, EMPTY);
                    decoder.finish();
                    if (stopping && !restored) throw new BridgeException("recovery_unverified", true,
                            "Sony's volume display was not confirmed restored.");
                    cleanRestoration = restored;
                    reason = stopping ? "Volume helper stopped." : "Volume helper exited.";
                    break;
                } else throw protocol();
            }
        } catch (BridgeException e) {
            reason = e.getMessage();
            if (!closed && !stopping) reportError(e.code, e.fatal, reason);
        } catch (GeneralSecurityException e) {
            reason = "The saved ADB key could not be validated.";
            if (!closed && !stopping) reportError("key_invalid", true, reason);
        } catch (SocketTimeoutException e) {
            reason = "The volume bridge timed out.";
            if (!closed && !stopping) reportError("connection", false, reason);
        } catch (IOException e) {
            reason = closed ? "Volume connection closed." : "The local volume bridge is unavailable.";
            if (!closed && !stopping) reportError("connection", false, reason);
        } catch (RuntimeException e) {
            reason = "The volume bridge listener failed.";
        } finally {
            watchdog.cancel();
            close();
            if (stopping && !cleanRestoration && !restoreOnly) {
                // The UI service may already be disabled. Complete one independent,
                // restore-only attempt here; the durable guard prevents repeat crashes.
                AdbVolumeConnection recovery = new AdbVolumeConnection(keyFile, apkPath, new Listener() {
                    public void onEvent(JSONObject event) { listener.onEvent(event); }
                    public void onClosed(String ignored) { }
                }, port, timeoutMs, false, true);
                recovery.run();
                cleanRestoration = recovery.cleanRestoration;
                if (cleanRestoration) reason = "Sony volume restored after an interrupted stop.";
                else reportError("recovery_unverified", true,
                        "Sony's volume display needs recovery. Use the Retry action.");
            }
            listener.onClosed(reason);
        }
    }

    public void ready() {
        synchronized (lock) {
            if (!readyQueued && !closed && !stopping) { readyQueued = true; commands.add("READY"); }
        }
    }
    public void setVisible(boolean visible) {
        synchronized (lock) {
            if (lastVisibility != null && lastVisibility == visible) return;
            lastVisibility = visible;
            enqueueLocked("VISIBILITY " + (visible ? "1" : "0"));
        }
    }
    public void confirmSafeVolume() { enqueue("CONFIRM_SAFE"); }
    public void cancelSafeVolume() { enqueue("CANCEL_SAFE"); }
    public void controllerDismissed() { enqueue("DISMISSED"); }
    public void safeWarningShown() { enqueue("SAFE_WARNING"); }
    public void stopAndRestore() {
        synchronized (lock) {
            if (closed || stopping) return;
            stopping = true;
            commands.clear();
            commands.add("STOP");
        }
    }
    private void enqueue(String command) { synchronized (lock) { enqueueLocked(command); } }
    private void enqueueLocked(String command) {
        if (closed || stopping) return;
        if (commands.size() >= 32) { close(); return; }
        commands.add(command);
    }
    @Override public void close() {
        synchronized (lock) {
            closed = true;
            commands.clear();
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void event(JSONObject event) throws IOException {
        if (event.optInt("v", -1) != 1) throw protocol();
        String type = event.optString("type", "");
        if ("hello".equals(type)) {
            if (hello || event.optInt("uid", -1) != 2000) throw protocol();
            hello = true;
        } else if ("ready".equals(type)) {
            if (!hello || event.optInt("uid", -1) != 2000) throw protocol();
            helperReady = true;
        } else if ("dismiss".equals(type)) {
            helperReady = false;
            readyDeadline = after(timeoutMs);
        } else if ("restoring".equals(type)) {
            restoring = true;
            helperReady = false;
            restorationDeadline = after(30_000);
        } else if ("restored".equals(type)) {
            if (!event.optBoolean("verified")) throw protocol();
            restored = true;
            helperReady = false;
        } else if (!"error".equals(type) && !"volume".equals(type)
                && !"safe_warning".equals(type) && !"dismiss".equals(type)
                && !"layout".equals(type) && !"a11y".equals(type)) throw protocol();
        listener.onEvent(event);
    }

    private void reportError(String code, boolean fatal, String detail) {
        try {
            listener.onEvent(new JSONObject().put("v", 1).put("type", "error")
                    .put("code", code).put("fatal", fatal).put("detail", detail));
        } catch (JSONException | RuntimeException ignored) { }
    }

    static String shellCommand(String apk) {
        return "shell,v2,raw:CLASSPATH='" + apk.replace("'", "'\\''")
                + "' exec /system/bin/app_process /system/bin com.sonybento.volume.ShellVolumeHelper";
    }

    private static boolean hasShellV2(byte[] bytes) {
        String banner = new String(bytes, StandardCharsets.UTF_8);
        for (String part : banner.replace('\0', ';').split(";")) {
            int at = part.indexOf("features=");
            if (at >= 0) for (String feature : part.substring(at + 9).split(",")) {
                if (feature.equals("shell_v2")) return true;
            }
        }
        return false;
    }

    static RSAPrivateKey readKey(File file) throws IOException, GeneralSecurityException {
        if (!file.isFile() || file.length() <= 0 || file.length() > 8192) {
            throw new GeneralSecurityException("Missing key");
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        try {
            PrivateKey candidate = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(bytes));
            if (!(candidate instanceof RSAPrivateKey)) throw new GeneralSecurityException();
            RSAPrivateKey key = (RSAPrivateKey) candidate;
            if (key.getModulus().bitLength() != 2048) throw new GeneralSecurityException();
            Signature verifier = Signature.getInstance("NONEwithRSA");
            verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(key.getModulus(), RSAKeyGenParameterSpec.F4)));
            verifier.update(SHA1_PREFIX);
            verifier.update(new byte[20]);
            if (!verifier.verify(sign(key, new byte[20]))) throw new GeneralSecurityException();
            return key;
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
    private static byte[] sign(RSAPrivateKey key, byte[] token) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("NONEwithRSA");
        signature.initSign(key);
        signature.update(SHA1_PREFIX);
        signature.update(token);
        return signature.sign();
    }

    private void write(OutputStream output, int command, int a, int b, byte[] data) throws IOException {
        if (closed) throw new IOException("closed");
        byte[] packet = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(a).putInt(b).putInt(data.length)
                .putInt(checksum(data)).putInt(command ^ -1).put(data).array();
        writeDeadline = after(timeoutMs);
        try { output.write(packet); output.flush(); }
        finally { writeDeadline = 0; }
    }
    private Packet read(Socket connection, InputStream input, long deadline, boolean idle)
            throws IOException {
        connection.setSoTimeout(idle ? 200 : remaining(deadline));
        int first;
        try { first = input.read(); }
        catch (SocketTimeoutException e) { if (idle) return null; throw e; }
        if (first < 0) throw new EOFException();
        // Idle waiting is unbounded; only a packet that has started needs a read deadline.
        if (idle) deadline = after(timeoutMs);
        byte[] header = new byte[24];
        header[0] = (byte) first;
        readFully(connection, input, header, 1, deadline);
        ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = fields.getInt(), a = fields.getInt(), b = fields.getInt();
        int length = fields.getInt(), sum = fields.getInt(), magic = fields.getInt();
        if (magic != (command ^ -1) || length < 0 || length > LIMIT) throw protocol();
        byte[] data = new byte[length];
        readFully(connection, input, data, 0, deadline);
        if (sum != checksum(data)) throw protocol();
        return new Packet(command, a, b, data);
    }
    private void readFully(Socket connection, InputStream input, byte[] data, int offset, long deadline)
            throws IOException {
        while (offset < data.length) {
            if (closed) throw new IOException("closed");
            connection.setSoTimeout(remaining(deadline));
            int count = input.read(data, offset, data.length - offset);
            if (count < 0) throw new EOFException();
            offset += count;
        }
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static long after(int millis) { return System.nanoTime() + millis * 1_000_000L; }
    private static boolean expired(long deadline) { return System.nanoTime() >= deadline; }
    private static int remaining(long deadline) throws SocketTimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new SocketTimeoutException();
        return (int) Math.max(1, (nanos + 999_999L) / 1_000_000L);
    }
    private static int checksum(byte[] data) { int sum = 0; for (byte b : data) sum += b & 255; return sum; }
    private static BridgeException protocol() {
        return new BridgeException("protocol", true, "The volume bridge returned an invalid response.");
    }
    private static final class Packet {
        final int command, arg0, arg1;
        final byte[] data;
        Packet(int command, int arg0, int arg1, byte[] data) {
            this.command = command; this.arg0 = arg0; this.arg1 = arg1; this.data = data;
        }
    }
    private static final class BridgeException extends IOException {
        private static final long serialVersionUID = 1L;
        final String code;
        final boolean fatal;
        BridgeException(String code, boolean fatal, String message) {
            super(message); this.code = code; this.fatal = fatal;
        }
    }

    interface EventSink { void accept(JSONObject event) throws IOException; }
    /** Shell-v2 framing is independent from ADB frame boundaries. */
    static final class ShellDecoder {
        private final EventSink sink;
        private final byte[] header = new byte[5];
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private int headerBytes, remaining, channel;
        private boolean exited;
        private int exitCode = -1;
        ShellDecoder(EventSink sink) { this.sink = sink; }
        void accept(byte[] bytes) throws IOException {
            for (byte value : bytes) {
                if (exited) throw protocol();
                if (headerBytes < 5) {
                    header[headerBytes++] = value;
                    if (headerBytes == 5) {
                        channel = header[0] & 255;
                        remaining = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                        if ((channel != 1 && channel != 2 && channel != 3) || remaining < 0
                                || remaining > 16384 || (channel == 3 && remaining != 1)) throw protocol();
                        if (remaining == 0) headerBytes = 0;
                    }
                    continue;
                }
                if (channel == 1) {
                    if (value == '\n') {
                        if (line.size() == 0) throw protocol();
                        try { sink.accept(new JSONObject(new String(line.toByteArray(), StandardCharsets.UTF_8))); }
                        catch (JSONException e) { throw protocol(); }
                        line.reset();
                    } else {
                        if (line.size() >= LIMIT) throw protocol();
                        line.write(value);
                    }
                } else if (channel == 3) { exited = true; exitCode = value & 255; }
                if (--remaining == 0) headerBytes = 0;
            }
        }
        void finish() throws IOException {
            if (headerBytes != 0 || remaining != 0 || line.size() != 0) throw protocol();
            if (!exited || exitCode != 0) throw new BridgeException("helper_exit", false,
                    "The volume helper exited unexpectedly; reconnecting safely.");
        }
    }
}
