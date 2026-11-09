package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps KeyHelper running while the phone remote is in use. The first time, the TV asks on screen whether to allow
 * this; after ten idle minutes it's closed, to leave the TV's small memory free. Only these fixed lines ever go through
 * it, never a command from the phone.
 */
final class RemoteKeys {

    static final String READY = "ready", ASKING = "asking", OFF = "off";
    private static final int PORT = 5555, WAIT_FOR_YES_MS = 60_000;
    private static final long IDLE_MS = 10 * 60_000;

    private static final ExecutorService work = Executors.newSingleThreadExecutor();
    private static volatile AdbLink link;
    private static volatile boolean connecting;
    private static volatile long lastUse;

    private RemoteKeys() {
    }

    static String state() {
        return link != null && !link.isClosed() ? READY : connecting ? ASKING : OFF;
    }

    /** Starts it if it isn't running. Presses made before it's ready are dropped, not delayed: a late press is worse. */
    static void wake(Context c) {
        lastUse = System.currentTimeMillis();
        if (link != null && !link.isClosed() || connecting) return;
        connecting = true;
        Context app = c.getApplicationContext();
        work.execute(() -> {
            try {
                KeyPair k = keys(app);
                AdbLink l = new AdbLink(k.getPrivate(), (RSAPublicKey) k.getPublic());
                l.connect("127.0.0.1", PORT, WAIT_FOR_YES_MS);
                l.shell("CLASSPATH='" + app.getPackageCodePath() + "' app_process / " + KeyHelper.class.getName(), null, null);
                link = l;
                watchIdle();
            } catch (Exception e) {
                android.util.Log.w("RemoteKeys", "connect", e);
            } finally {
                connecting = false;
            }
        });
    }

    private static void watchIdle() {
        Thread t = new Thread(() -> {
            try {
                while (link != null && !link.isClosed()) {
                    Thread.sleep(30_000);
                    if (System.currentTimeMillis() - lastUse > IDLE_MS) {
                        AdbLink l = link;
                        link = null;
                        if (l != null) l.close();
                    }
                }
            } catch (InterruptedException ignored) {
                // ended
            }
        }, "remote-idle");
        t.setDaemon(true);
        t.start();
    }

    /** False when it isn't running yet. */
    static boolean send(Context c, String line) {
        wake(c);
        AdbLink l = link;
        if (l == null || l.isClosed()) return false;
        work.execute(() -> {
            try {
                l.write(line + "\n");
            } catch (Exception e) {
                l.close();
            }
        });
        return true;
    }

    static KeyPair keys(Context c) throws Exception {
        SharedPreferences p = c.getSharedPreferences("remote_key", Context.MODE_PRIVATE);
        String priv = p.getString("private", null), pub = p.getString("public", null);
        KeyFactory f = KeyFactory.getInstance("RSA");
        if (priv != null && pub != null) {
            PrivateKey pk = f.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP)));
            return new KeyPair(f.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))), pk);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair k = g.generateKeyPair();
        p.edit().putString("private", Base64.encodeToString(k.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString("public", Base64.encodeToString(k.getPublic().getEncoded(), Base64.NO_WRAP)).apply();
        return k;
    }
}
