package com.nareshjagriya.tvtown;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The TV's own debugging connection, spoken from TV Town to itself: the one way an ordinary app on Android 11 can press
 * arrow keys inside other apps. Plain Java, so it can be tested off the TV. The TV asks once before it trusts this key.
 */
final class AdbLink implements Closeable {

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int TOKEN = 1, SIGNATURE = 2, PUBLIC_KEY = 3, VERSION = 0x01000000, MAX = 4096, LOCAL_ID = 1;
    /** What goes before a SHA-1 in an RSA signature: adb signs its token as if the token were that hash. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    interface Output {
        void data(byte[] bytes);
    }

    private final Socket socket = new Socket();
    private final PrivateKey key;
    private final RSAPublicKey pub;
    private OutputStream out;
    private DataInputStream in;
    private int remoteId;
    private final Semaphore mayWrite = new Semaphore(0);
    private volatile boolean closed;

    AdbLink(PrivateKey key, RSAPublicKey pub) {
        this.key = key;
        this.pub = pub;
    }

    /** waitForYes: how long the TV's "allow?" question may stay unanswered. */
    void connect(String host, int port, int waitForYesMs) throws Exception {
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(10_000);
        out = socket.getOutputStream();
        in = new DataInputStream(socket.getInputStream());
        send(CNXN, VERSION, MAX, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        while (true) {
            Packet p = read();
            if (p.cmd == CNXN) return;
            if (p.cmd != AUTH || p.arg0 != TOKEN) throw new IOException("unexpected " + Integer.toHexString(p.cmd));
            if (!signed) {
                Signature s = Signature.getInstance("NONEwithRSA");
                s.initSign(key);
                s.update(SHA1_PREFIX);
                s.update(p.data);
                send(AUTH, SIGNATURE, 0, s.sign());
                signed = true;
            } else {   // not trusted yet: the TV now asks on screen
                send(AUTH, PUBLIC_KEY, 0, (publicBlob(pub) + " tvtown@tv\0").getBytes(StandardCharsets.UTF_8));
                socket.setSoTimeout(waitForYesMs);
            }
        }
    }

    /** Runs a command; what it prints goes to output, on a thread of its own. */
    void shell(String command, Output output, Runnable ended) throws Exception {
        send(OPEN, LOCAL_ID, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        while (true) {
            Packet p = read();
            if (p.cmd == OKAY) {
                remoteId = p.arg0;
                break;
            }
            if (p.cmd == CLSE) throw new IOException("refused");
        }
        mayWrite.release();
        socket.setSoTimeout(0);
        Thread t = new Thread(() -> {
            try {
                while (!closed) {
                    Packet p = read();
                    if (p.cmd == WRTE) {
                        send(OKAY, LOCAL_ID, remoteId, new byte[0]);
                        if (output != null) output.data(p.data);
                    } else if (p.cmd == OKAY) {
                        mayWrite.release();
                    } else if (p.cmd == CLSE) {
                        break;
                    }
                }
            } catch (Exception ignored) {
                // the connection went away
            }
            closed = true;
            if (ended != null) ended.run();
        }, "adb-read");
        t.setDaemon(true);
        t.start();
    }

    void write(String text) throws Exception {
        if (closed || !mayWrite.tryAcquire(5, TimeUnit.SECONDS)) throw new IOException("closed");
        send(WRTE, LOCAL_ID, remoteId, text.getBytes(StandardCharsets.UTF_8));
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static final class Packet {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private synchronized void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(h.array());
        out.flush();
    }

    private Packet read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.cmd = h.getInt();
        p.arg0 = h.getInt();
        p.arg1 = h.getInt();
        int length = h.getInt();
        if (length < 0 || length > 1024 * 1024) throw new IOException("bad length");
        p.data = new byte[length];
        in.readFully(p.data);
        return p;
    }

    /** The public key in adb's own format. */
    static String publicBlob(RSAPublicKey k) {
        int words = 2048 / 32;
        BigInteger n = k.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 4 * words + 4 * words + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        BigInteger x = n;
        for (int i = 0; i < words; i++) {
            b.putInt(x.mod(r32).intValue());
            x = x.shiftRight(32);
        }
        x = rr;
        for (int i = 0; i < words; i++) {
            b.putInt(x.mod(r32).intValue());
            x = x.shiftRight(32);
        }
        b.putInt(k.getPublicExponent().intValue());
        return Base64.getEncoder().encodeToString(b.array());
    }
}
