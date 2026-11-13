package com.sonybento.volume;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import org.json.JSONObject;

/** Fake-loopback and filesystem tests only. No TV, adb binary, app_process, or SystemUI execution. */
public final class VolumeBridgeTest {
    static final int CNXN=0x4e584e43, AUTH=0x48545541, OPEN=0x4e45504f, OKAY=0x59414b4f;
    static final int WRTE=0x45545257, CLSE=0x45534c43;
    static final byte[] EMPTY=new byte[0], TOKEN=new byte[20];
    static File key;
    static KeyPair pair;
    static int tests;
    static String filter = "";
    static final String BOOT = "00000000-0000-0000-0000-000000000001";
    static final String NEXT_BOOT = "00000000-0000-0000-0000-000000000002";

    public static void main(String[] args) throws Exception {
        filter = args.length == 0 ? "" : args[0];
        Path temp=Files.createTempDirectory("volume-bridge-tests-");
        key=temp.resolve("adb-key").toFile();
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        pair=generator.generateKeyPair(); Files.write(key.toPath(),pair.getPrivate().getEncoded());
        try {
            test("fragmented handshake, RSA, events, command flow control",VolumeBridgeTest::session);
            test("interrupted STOP makes one restore-only recovery",()->interruptedStop(true));
            test("failed STOP recovery is never reported successful",()->interruptedStop(false));
            test("STOP before READY makes one restore-only recovery",()->interruptedStop(true,false,0));
            test("STOP before READY rejects unverified restoration",()->interruptedStop(false,false,0));
            test("STOP before READY rejects a nonzero restoration exit",()->interruptedStop(true,false,137));
            test("rejected key never enrolls",VolumeBridgeTest::unauthorized);
            test("shell-v2 is required",VolumeBridgeTest::requiresV2);
            test("explicit recovery is a fixed argument",VolumeBridgeTest::explicitRecovery);
            test("close before run prevents connection",VolumeBridgeTest::closeBefore);
            test("close while waiting cancels promptly",VolumeBridgeTest::cancel);
            test("idle scheduling pause preserves a complete frame",VolumeBridgeTest::idleSchedulingPause);
            test("long-lived stderr does not terminate the session",VolumeBridgeTest::stderrLifetime);
            test("partial-frame deadline is bounded",VolumeBridgeTest::partialTimeout);
            test("oversized ADB packet fails before allocation",()->badFrame(Integer.MAX_VALUE,CNXN^-1));
            test("bad ADB magic rejected",()->badFrame(0,0));
            test("shell-v2 frame and JSON bounds",VolumeBridgeTest::decoderBounds);
            test("non-CRT persisted key loads",()->nonCrt(temp));
            test("missing key is not generated",()->missingKey(temp));
            test("APK shell quoting is literal",VolumeBridgeTest::quoting);
            test("recovery guard survives restart and cooldown",()->guardCooldown(temp));
            test("second recovery disables; only explicit reset clears",()->guardDisable(temp));
            test("unclean takeover persists",()->guardUnclean(temp));
            test("normal reboot clears stale ownership without recovery",()->guardNewBoot(temp));
            test("disabled guard survives reboot",()->disabledNewBoot(temp));
            test("SystemUI process name accepts NUL padding only",VolumeBridgeTest::processName);
            test("exclusive helper ownership",()->guardExclusive(temp));
            test("corrupt guard requires explicit recovery",()->guardCorrupt(temp));
            test("exact PID parser rejects ambiguity",VolumeBridgeTest::pid);
            test("helper command size and ASCII bounds",VolumeBridgeTest::commandBounds);
            System.out.println("PASS "+tests+" local tests; no TV commands");
        } finally {
            try(var paths=Files.walk(temp)) { paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{
                try {Files.deleteIfExists(p);} catch(IOException e){throw new RuntimeException(e);}
            }); }
        }
    }

    static void session() throws Exception {
        try(Server server=new Server(socket->{
            handshake(socket,true);
            Packet open=receive(socket); check(open.command==OPEN,"missing OPEN");
            check(text(open.data).equals(AdbVolumeConnection.shellCommand("/data/app/volume.apk")+"\0"),"unexpected remote command");
            send(socket,OKAY,7,1,EMPTY);
            event(socket,"{\"v\":1,\"type\":\"hello\",\"uid\":2000}\n",true);
            check(command(socket).equals("READY\n"),"missing READY");
            send(socket,OKAY,7,1,EMPTY);
            event(socket,"{\"v\":1,\"type\":\"ready\",\"uid\":2000}\n",false);
            event(socket,"{\"v\":1,\"type\":\"volume\",\"stream\":3,\"flags\":1,\"level\":100,\"min\":0,\"max\":100,\"muted\":false,\"masterMuted\":false}\n",true);
            check(command(socket).equals("VISIBILITY 1\n"),"visibility command lost");
            send(socket,OKAY,7,1,EMPTY);
            event(socket,"{\"v\":1,\"type\":\"safe_warning\",\"flags\":1}\n",false);
            check(command(socket).equals("CONFIRM_SAFE\n"),"confirmation lost");
            // The client must not send the queued cancellation before this WRTE is acknowledged.
            socket.setSoTimeout(150);
            try {check(socket.getInputStream().read()==-1,"sent unacknowledged WRTE");}
            catch(java.net.SocketTimeoutException expected) { }
            socket.setSoTimeout(3000);
            send(socket,OKAY,7,1,EMPTY);
            check(command(socket).equals("CANCEL_SAFE\n"),"cancel lost");
            send(socket,OKAY,7,1,EMPTY);
            event(socket,"{\"v\":1,\"type\":\"dismiss\"}\n",false);
            check(command(socket).equals("STOP\n"),"STOP lost");
            send(socket,OKAY,7,1,EMPTY);
            event(socket,"{\"v\":1,\"type\":\"restoring\"}\n",false);
            event(socket,"{\"v\":1,\"type\":\"restored\",\"verified\":true}\n",false);
            send(socket,WRTE,7,1,shell(3,new byte[]{0})); ack(socket);
            send(socket,CLSE,7,1,EMPTY); check(receive(socket).command==CLSE,"close ACK");
        })) {
            List<JSONObject> events=new ArrayList<>();
            AdbVolumeConnection[] ref=new AdbVolumeConnection[1];
            ref[0]=client(server,new AdbVolumeConnection.Listener(){
                public void onEvent(JSONObject e){
                    events.add(e);
                    switch(e.optString("type")) {
                        case "hello": ref[0].ready(); break;
                        case "volume": ref[0].setVisible(true); break;
                        case "safe_warning": ref[0].confirmSafeVolume(); ref[0].cancelSafeVolume(); break;
                        case "dismiss": ref[0].stopAndRestore(); ref[0].ready(); break;
                    }
                }
                public void onClosed(String reason){check(reason.equals("Volume helper stopped."),reason);}
            });
            ref[0].run(); server.await();
            check(events.size()==7 && events.get(2).getInt("level")==100,"events corrupted");
        }
    }
    static void interruptedStop(boolean verified) throws Exception {
        interruptedStop(verified,true,0);
    }
    static void interruptedStop(boolean verified,boolean readyBeforeStop,int recoveryExit) throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            CompletableFuture<Void> future=CompletableFuture.runAsync(()->{
                try {
                    try(Socket socket=server.accept()) {
                        socket.setSoTimeout(3000);handshake(socket,true);receive(socket);send(socket,OKAY,7,1,EMPTY);
                        event(socket,"{\"v\":1,\"type\":\"hello\",\"uid\":2000}\n",false);
                        if(readyBeforeStop) {
                            check(command(socket).equals("READY\n"),"missing READY");send(socket,OKAY,7,1,EMPTY);
                            event(socket,"{\"v\":1,\"type\":\"ready\",\"uid\":2000}\n",false);
                        }
                        check(command(socket).equals("STOP\n"),"missing STOP");send(socket,OKAY,7,1,EMPTY);
                        send(socket,WRTE,7,1,shell(3,new byte[]{(byte)137}));ack(socket);
                        send(socket,CLSE,7,1,EMPTY);check(receive(socket).command==CLSE,"close ACK");
                    }
                    try(Socket socket=server.accept()) {
                        socket.setSoTimeout(3000);handshake(socket,true);
                        check(text(receive(socket).data).endsWith(" --restore-only\0"),"recovery attempted takeover");
                        send(socket,OKAY,7,1,EMPTY);
                        event(socket,"{\"v\":1,\"type\":\"restoring\"}\n",false);
                        if(verified)event(socket,"{\"v\":1,\"type\":\"restored\",\"verified\":true}\n",false);
                        send(socket,WRTE,7,1,shell(3,new byte[]{(byte)recoveryExit}));ack(socket);
                        send(socket,CLSE,7,1,EMPTY);check(receive(socket).command==CLSE,"recovery close ACK");
                    }
                } catch(Exception e){throw new RuntimeException(e);}
            });
            AdbVolumeConnection[] ref=new AdbVolumeConnection[1];
            List<JSONObject> events=new ArrayList<>();String[] closed={null};
            ref[0]=new AdbVolumeConnection(key,"/data/app/volume.apk",new AdbVolumeConnection.Listener(){
                public void onEvent(JSONObject e){events.add(e);if(e.optString("type").equals("hello")) {
                        if(readyBeforeStop)ref[0].ready();else ref[0].stopAndRestore();
                    }
                    if(e.optString("type").equals("ready"))ref[0].stopAndRestore();}
                public void onClosed(String reason){closed[0]=reason;}
            },server.getLocalPort(),2000);
            ref[0].run();future.get(8,TimeUnit.SECONDS);
            String success="Sony volume restored after an interrupted stop.";
            if(verified&&recoveryExit==0)check(success.equals(closed[0]),closed[0]);
            else {
                check(!success.equals(closed[0]),"failed recovery claimed success");
                check(events.stream().anyMatch(e->e.optString("code").equals("recovery_unverified")&&e.optBoolean("fatal")),
                        "failed recovery did not report a fatal verification error");
            }
            server.setSoTimeout(100);
            try(Socket unexpected=server.accept()){throw new AssertionError("more than one restore-only recovery attempted");}
            catch(java.net.SocketTimeoutException expected) { }
        }
    }
    static void unauthorized() throws Exception {
        try(Server server=new Server(socket->{
            check(receive(socket).command==CNXN,"CNXN"); send(socket,AUTH,1,0,TOKEN);
            Packet signature=receive(socket); check(signature.command==AUTH && signature.a==2,"signature missing");
            send(socket,AUTH,1,0,TOKEN);
            check(socket.getInputStream().read()==-1,"enrollment packet sent");
        })) { Recorder r=new Recorder(); client(server,r).run(); server.await(); check(r.code("unauthorized"),"missing authorization error"); }
    }
    static void requiresV2() throws Exception {
        try(Server server=new Server(socket->{receive(socket); send(socket,CNXN,0x01000000,4096,bytes("device::\0"));
            check(socket.getInputStream().read()==-1,"opened unsupported shell");
        })) {Recorder r=new Recorder();client(server,r).run();server.await();check(r.code("unsupported"),"missing feature error");}
    }
    static void explicitRecovery() throws Exception {
        try(Server server=new Server(socket->{handshake(socket,false);Packet open=receive(socket);
            check(text(open.data).endsWith("ShellVolumeHelper --reset-recovery\0"),"recovery flag missing");
            send(socket,CLSE,0,1,EMPTY);
        })) {new AdbVolumeConnection(key,"/data/app/test.apk",new Recorder(),server.port(),1000,true).run();server.await();}
    }
    static void closeBefore() throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            Recorder r=new Recorder(); AdbVolumeConnection c=new AdbVolumeConnection(key,"/test.apk",r,server.getLocalPort(),1000);
            c.close();c.run();check(r.closed==1,"close callback missing");server.setSoTimeout(100);
            try(Socket unexpected=server.accept()){throw new AssertionError("connected after close "+unexpected.isConnected());}
            catch(java.net.SocketTimeoutException expected) { }
        }
    }
    static void cancel() throws Exception {
        CompletableFuture<Void> started=new CompletableFuture<>();
        try(Server server=new Server(socket->{receive(socket);started.complete(null);check(socket.getInputStream().read()==-1,"still connected");})) {
            Recorder r=new Recorder();AdbVolumeConnection c=client(server,r);CompletableFuture<Void> task=CompletableFuture.runAsync(c::run);
            started.get(1,TimeUnit.SECONDS);long now=System.nanoTime();c.close();task.get(1,TimeUnit.SECONDS);
            check((System.nanoTime()-now)<500_000_000L,"slow cancel");server.await();
        }
    }
    static void idleSchedulingPause() throws Exception {
        AdbVolumeConnection client = new AdbVolumeConnection(key, "/test.apk", new Recorder(), 1, 100);
        java.lang.reflect.Method read = AdbVolumeConnection.class.getDeclaredMethod(
                "read", Socket.class, InputStream.class, long.class, boolean.class);
        read.setAccessible(true);
        byte[] frame = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(OKAY).putInt(7).putInt(1).putInt(0).putInt(0).putInt(OKAY ^ -1).array();
        ByteArrayInputStream input = new ByteArrayInputStream(frame) {
            @Override public synchronized int read() {
                // Model the idle reader being descheduled while a complete packet arrives.
                try { Thread.sleep(180); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return super.read();
            }
        };
        try (Socket socket = new Socket()) {
            try {
                Object packet = read.invoke(client, socket, input, System.nanoTime() + 100_000_000L, true);
                check(packet != null && input.available() == 0, "complete idle frame was not consumed");
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new AssertionError("complete frame after an idle scheduling pause was rejected", e.getCause());
            }
        }
    }

    static void stderrLifetime() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        AdbVolumeConnection.ShellDecoder decoder = new AdbVolumeConnection.ShellDecoder(events::add);
        byte[] diagnostic = new byte[4091];
        Arrays.fill(diagnostic, (byte) 'x');
        // Independent valid diagnostic frames may accumulate for hours; no stderr is retained.
        for (int i = 0; i < 32; i++) decoder.accept(shell(2, diagnostic));
        decoder.accept(shell(1, bytes("{\"v\":1,\"type\":\"volume\"}\n")));
        decoder.accept(shell(3, new byte[]{0}));
        decoder.finish();
        check(events.size() == 1 && events.get(0).optString("type").equals("volume"),
                "valid stderr prevented the next volume event");
    }

    static void partialTimeout() throws Exception {
        try(Server server=new Server(socket->{receive(socket);socket.getOutputStream().write(new byte[]{0x43});
            check(socket.getInputStream().read()==-1,"timeout did not close");})) {
            Recorder r=new Recorder();long now=System.nanoTime();new AdbVolumeConnection(key,"/test.apk",r,server.port(),200).run();
            check(System.nanoTime()-now<1_000_000_000L,"unbounded frame wait");check(r.code("connection"),"missing timeout");server.await();
        }
    }
    static void badFrame(int length,int magic) throws Exception {
        try(Server server=new Server(socket->{receive(socket);socket.getOutputStream().write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(CNXN).putInt(0x01000000).putInt(4096).putInt(length).putInt(0).putInt(magic).array());})) {
            Recorder r=new Recorder();client(server,r).run();server.await();check(r.code("protocol"),"bad packet accepted");
        }
    }
    static void decoderBounds() throws Exception {
        List<JSONObject> events=new ArrayList<>();AdbVolumeConnection.ShellDecoder d=new AdbVolumeConnection.ShellDecoder(events::add);
        byte[] frame=shell(1,bytes("{\"v\":1,\"type\":\"dismiss\"}\n"));
        for(byte b:frame)d.accept(new byte[]{b});d.accept(shell(3,new byte[]{0}));d.finish();check(events.size()==1,"split shell frame lost");
        AdbVolumeConnection.ShellDecoder missingExit=new AdbVolumeConnection.ShellDecoder(e->{});
        missingExit.accept(frame);expectIo(missingExit::finish);
        AdbVolumeConnection.ShellDecoder badExit=new AdbVolumeConnection.ShellDecoder(e->{});
        badExit.accept(shell(3,new byte[]{(byte)137}));expectIo(badExit::finish);
        expectIo(()->new AdbVolumeConnection.ShellDecoder(e->{}).accept(shell(1,bytes("x".repeat(4097)))));
        expectIo(()->new AdbVolumeConnection.ShellDecoder(e->{}).accept(new byte[]{1,-1,-1,-1,127}));
        expectIo(()->new AdbVolumeConnection.ShellDecoder(e->{}).accept(shell(0,EMPTY)));
        expectIo(()->new AdbVolumeConnection.ShellDecoder(e->{}).accept(shell(3,new byte[]{0,0})));
        AdbVolumeConnection.ShellDecoder truncated=new AdbVolumeConnection.ShellDecoder(e->{});
        truncated.accept(new byte[]{1,2});expectIo(truncated::finish);
    }
    static void nonCrt(Path temp) throws Exception {
        RSAPrivateKey k=(RSAPrivateKey)pair.getPrivate();File file=temp.resolve("non-crt").toFile();
        Files.write(file.toPath(),KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateKeySpec(k.getModulus(),k.getPrivateExponent())).getEncoded());
        check(AdbVolumeConnection.readKey(file).getModulus().equals(k.getModulus()),"non-CRT rejected");
    }
    static void missingKey(Path temp) throws Exception {
        File absent=temp.resolve("absent").toFile();
        try {AdbVolumeConnection.readKey(absent);throw new AssertionError("missing key accepted");}
        catch(java.security.GeneralSecurityException expected) { }
        check(!absent.exists(),"production generated a new key");
    }
    static void quoting() {
        String command=AdbVolumeConnection.shellCommand("/data/app/a'$(touch nope);x.apk");
        check(command.equals("shell,v2,raw:CLASSPATH='/data/app/a'\\''$(touch nope);x.apk' exec /system/bin/app_process /system/bin com.sonybento.volume.ShellVolumeHelper"),"unsafe quote");
        try {new AdbVolumeConnection(key,"/a.apk\n",new Recorder());throw new AssertionError("newline allowed");}
        catch(IllegalArgumentException expected) { }
    }
    static void guardCooldown(Path temp) throws Exception {
        File dir=Files.createDirectory(temp.resolve("cooldown")).toFile();
        try(var g=ShellVolumeHelper.RecoveryGuard.open(dir,false,BOOT)){g.begin();g.reserveRecovery(100000);}
        try(var g=ShellVolumeHelper.RecoveryGuard.open(dir,false,BOOT)){check(g.waitMillis(100010)==59990,"cooldown not durable");check(g.waitMillis(160001)==0,"cooldown not elapsed");}
    }
    static void guardDisable(Path temp) throws Exception {
        File dir=Files.createDirectory(temp.resolve("disable")).toFile();
        try(var g=ShellVolumeHelper.RecoveryGuard.open(dir,false,BOOT)){g.begin();g.reserveRecovery(100000);g.begin();g.reserveRecovery(170000);}
        try(var g=ShellVolumeHelper.RecoveryGuard.open(dir,false,BOOT)){expectIo(()->g.waitMillis(999999));}
        try(var g=ShellVolumeHelper.RecoveryGuard.open(dir,true,BOOT)){check(g.waitMillis(999999)==0 && g.unclean(),"explicit reset must clear budget and retain controller cleanup");}
    }
    static void guardUnclean(Path temp) throws Exception {
        File dir=Files.createDirectory(temp.resolve("unclean")).toFile();
        try(var g=ShellVolumeHelper.RecoveryGuard.open(dir,false,BOOT)){g.begin();}
        try(var g=ShellVolumeHelper.RecoveryGuard.open(dir,false,BOOT)){check(g.unclean(),"lost unclean takeover");}
    }
    static void guardExclusive(Path temp) throws Exception {
        File dir=Files.createDirectory(temp.resolve("exclusive")).toFile();
        try(var owner=ShellVolumeHelper.RecoveryGuard.open(dir,false,BOOT)){
            check(!owner.unclean(),"unexpected active state");expectIo(()->ShellVolumeHelper.RecoveryGuard.open(dir,true,BOOT));
        }
    }
    static void guardNewBoot(Path temp) throws Exception {
        File dir=Files.createDirectory(temp.resolve("new-boot")).toFile();
        try(var g=ShellVolumeHelper.RecoveryGuard.open(dir,false,BOOT)){g.begin();}
        try(var g=ShellVolumeHelper.RecoveryGuard.open(dir,false,NEXT_BOOT)){
            check(!g.unclean() && g.waitMillis(100)==0 && g.failures==0,"normal boot counted as crash");
        }
    }
    static void disabledNewBoot(Path temp) throws Exception {
        File dir=Files.createDirectory(temp.resolve("disabled-new-boot")).toFile();
        try(var g=ShellVolumeHelper.RecoveryGuard.open(dir,false,BOOT)){g.disable();}
        try(var g=ShellVolumeHelper.RecoveryGuard.open(dir,false,NEXT_BOOT)){
            expectIo(()->g.waitMillis(100));
        }
    }
    static void processName() {
        byte[] exact=bytes("com.android.systemui\0");
        check(ShellVolumeHelper.isSystemUiCommand(exact),"exact name rejected");
        check(ShellVolumeHelper.isSystemUiCommand(Arrays.copyOf(exact,128)),"Android NUL padding rejected");
        check(!ShellVolumeHelper.isSystemUiCommand(bytes("com.android.systemui:screenshot\0")),"wrong process accepted");
        check(!ShellVolumeHelper.isSystemUiCommand(bytes("com.android.systemui\0arg\0")),"extra argv accepted");
    }
    static void guardCorrupt(Path temp) throws Exception {
        File dir=Files.createDirectory(temp.resolve("corrupt")).toFile();Files.writeString(dir.toPath().resolve("recovery.state"),"phase=garbage\n");
        expectIo(()->ShellVolumeHelper.RecoveryGuard.open(dir,false,BOOT));
        try(var g=ShellVolumeHelper.RecoveryGuard.open(dir,true,BOOT)){check(g.waitMillis(1)==0,"corrupt reset failed");}
    }
    static void pid() throws Exception {
        check(ShellVolumeHelper.parsePid("4849\n")==4849,"valid PID rejected");
        for(String bad:new String[]{"","1","0","-2","123 456","123;kill","999999999999"})expectIo(()->ShellVolumeHelper.parsePid(bad));
    }
    static void commandBounds() throws Exception {
        check(ShellVolumeHelper.readLine(new ByteArrayInputStream(bytes("READY\n"))).equals("READY"),"valid line rejected");
        check(ShellVolumeHelper.readLine(new ByteArrayInputStream(EMPTY))==null,"EOF ignored");
        for(String bad:new String[]{"READY","x".repeat(65)+"\n","READ\0Y\n","é\n"})
            expectIo(()->ShellVolumeHelper.readLine(new ByteArrayInputStream(bytes(bad))));
    }

    static AdbVolumeConnection client(Server s,AdbVolumeConnection.Listener l){return new AdbVolumeConnection(key,"/data/app/volume.apk",l,s.port(),2000);}
    static void handshake(Socket s,boolean authenticate) throws Exception {
        Packet connect=receive(s);check(connect.command==CNXN && text(connect.data).contains("shell_v2"),"bad CNXN");
        if(authenticate){send(s,AUTH,1,0,TOKEN);Packet signature=receive(s);check(signature.command==AUTH&&signature.a==2,"bad AUTH");
            Cipher verify=Cipher.getInstance("RSA/ECB/PKCS1Padding");verify.init(Cipher.DECRYPT_MODE,pair.getPublic());
            byte[] expected=new byte[35];System.arraycopy(java.util.HexFormat.of().parseHex("3021300906052b0e03021a05000414"),0,expected,0,15);
            check(Arrays.equals(verify.doFinal(signature.data),expected),"signature double-hashed");}
        send(s,CNXN,0x01000000,4096,bytes("device::features=shell_v2,cmd\0"));
    }
    static void event(Socket s,String json,boolean fragments) throws Exception {
        byte[] frame=shell(1,bytes(json));
        if(fragments){for(int i=0;i<frame.length;i+=3){send(s,WRTE,7,1,Arrays.copyOfRange(frame,i,Math.min(frame.length,i+3)));ack(s);}}
        else {send(s,WRTE,7,1,frame);ack(s);}
    }
    static String command(Socket s) throws Exception {
        Packet p=receive(s);check(p.command==WRTE&&p.a==1&&p.b==7,"bad command frame");
        ByteBuffer b=ByteBuffer.wrap(p.data).order(ByteOrder.LITTLE_ENDIAN);check(b.get()==0,"command not stdin");
        int length=b.getInt();check(length==b.remaining(),"stdin length mismatch");byte[] data=new byte[length];b.get(data);return text(data);
    }
    static void ack(Socket s) throws Exception {Packet p=receive(s);check(p.command==OKAY&&p.a==1&&p.b==7,"bad data ACK");}
    static byte[] shell(int channel,byte[] payload){return ByteBuffer.allocate(5+payload.length).order(ByteOrder.LITTLE_ENDIAN).put((byte)channel).putInt(payload.length).put(payload).array();}
    static void send(Socket s,int command,int a,int b,byte[] data) throws Exception {
        byte[] frame=ByteBuffer.allocate(24+data.length).order(ByteOrder.LITTLE_ENDIAN).putInt(command).putInt(a).putInt(b).putInt(data.length).putInt(sum(data)).putInt(command^-1).put(data).array();
        s.getOutputStream().write(frame);s.getOutputStream().flush();
    }
    static Packet receive(Socket s) throws Exception {
        byte[] header=s.getInputStream().readNBytes(24);check(header.length==24,"EOF receiving packet");ByteBuffer f=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int cmd=f.getInt(),a=f.getInt(),b=f.getInt(),len=f.getInt(),sum=f.getInt(),magic=f.getInt();check(len>=0&&len<=4096&&magic==(cmd^-1),"invalid packet");
        byte[] data=s.getInputStream().readNBytes(len);check(data.length==len&&sum(data)==sum,"invalid payload");return new Packet(cmd,a,b,data);
    }
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}static String text(byte[] b){return new String(b,StandardCharsets.UTF_8);}
    static int sum(byte[] data){int n=0;for(byte b:data)n+=b&255;return n;}
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    interface Test {void run() throws Exception;}interface Handler {void run(Socket s)throws Exception;}
    static void test(String name,Test action)throws Exception{if(!name.contains(filter))return;action.run();tests++;System.out.println("PASS "+name);}
    static void expectIo(Test action)throws Exception{try{action.run();throw new AssertionError("expected IOException");}catch(IOException expected){}}
    record Packet(int command,int a,int b,byte[] data){}
    static final class Recorder implements AdbVolumeConnection.Listener {
        final List<JSONObject> events=new ArrayList<>();int closed;
        public void onEvent(JSONObject event){events.add(event);}public void onClosed(String reason){closed++;}
        boolean code(String code){return events.stream().anyMatch(e->code.equals(e.optString("code")));}
    }
    static final class Server implements AutoCloseable {
        final ServerSocket server;final CompletableFuture<Void> finished=new CompletableFuture<>();
        Server(Handler handler)throws IOException{server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(5000);
            Thread t=new Thread(()->{try(Socket s=server.accept()){s.setSoTimeout(3000);s.setTcpNoDelay(true);handler.run(s);finished.complete(null);}catch(Throwable e){finished.completeExceptionally(e);}},"FakeVolumeAdb");t.setDaemon(true);t.start();}
        int port(){return server.getLocalPort();}void await()throws Exception{finished.get(5,TimeUnit.SECONDS);}
        public void close()throws IOException{server.close();}
    }
}
