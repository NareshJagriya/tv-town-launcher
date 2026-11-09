package com.nareshjagriya.tvtown;

import android.content.Context;

import androidx.annotation.Nullable;

import java.net.ConnectException;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;

/**
 * Runs fixed commands on the TV through its debugging connection, the same one the phone remote uses (the first time,
 * the TV asks on screen whether to allow TV Town). One connection is kept while it's in use and closed after two quiet
 * minutes, so the question comes at most once even on a TV that doesn't remember "Always allow". Used by "Free up this
 * TV" only, with commands it builds itself from checked package names. Never on the main thread.
 */
final class TvShell {

    /** Why the last run failed: debugging is off, or the TV's question wasn't answered "Allow". */
    static final int OK = 0, DEBUGGING_OFF = 1, NOT_ALLOWED = 2;
    static volatile int last = OK;
    private static final long IDLE_MS = 120_000;
    private static AdbLink link;
    private static long lastUse;

    private TvShell() {
    }

    /** What the command printed, or null when the TV said no (see last). waitForYesMs: how long the question may wait. */
    @Nullable
    static synchronized String run(Context c, String command, int waitForYesMs) {
        try {
            if (link == null || link.isClosed() || System.currentTimeMillis() - lastUse > IDLE_MS) {
                if (link != null) link.close();
                KeyPair k = RemoteKeys.keys(c.getApplicationContext());
                AdbLink l = new AdbLink(k.getPrivate(), (RSAPublicKey) k.getPublic());
                try {
                    l.connect("127.0.0.1", 5555, waitForYesMs);
                } catch (ConnectException e) {
                    last = DEBUGGING_OFF;
                    return null;
                }
                link = l;
            }
            String out = link.exec(command, 60_000);
            lastUse = System.currentTimeMillis();
            last = OK;
            return out;
        } catch (Exception e) {
            android.util.Log.w("TvShell", "run", e);
            if (link != null) link.close();
            link = null;
            last = NOT_ALLOWED;
            return null;
        }
    }

    /** Done with it: the connection goes now. */
    static synchronized void close() {
        if (link != null) link.close();
        link = null;
    }
}
