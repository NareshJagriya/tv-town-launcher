package com.nareshjagriya.tvtown;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Presses the remote's buttons for the phone remote. Started by RemoteKeys through the TV's debugging connection, so it
 * runs as the shell user, outside TV Town, and may press buttons in any app. It reads only these lines from that
 * connection: "d 19" press, "u 19" release, "t <base64>" type text; for the game controller, "gd 96" / "gu 96" a
 * gamepad button (several may be held at once) and "j 40,-100" the thumb stick. When the connection closes, it ends.
 */
public final class KeyHelper {

    private static final long FIRST_REPEAT_MS = 400, REPEAT_MS = 90, LONGEST_HOLD_MS = 20_000;
    private static Method inject;
    private static Object manager;
    private static volatile int held = -1;
    private static volatile long heldSince;

    private KeyHelper() {
    }

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("android.hardware.input.InputManager");
        manager = c.getMethod("getInstance").invoke(null);
        inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            String[] p = line.trim().split(" ");
            if (p.length != 2) continue;
            try {
                if (p[0].equals("d")) down(Integer.parseInt(p[1]));
                else if (p[0].equals("u")) up(Integer.parseInt(p[1]));
                else if (p[0].equals("t")) type(new String(java.util.Base64.getDecoder().decode(p[1]), StandardCharsets.UTF_8));
                else if (p[0].equals("gd")) pad(Integer.parseInt(p[1]), KeyEvent.ACTION_DOWN);
                else if (p[0].equals("gu")) pad(Integer.parseInt(p[1]), KeyEvent.ACTION_UP);
                else if (p[0].equals("grant") && p[1].matches("[a-z][a-z0-9_.]*"))   // lets TV Town switch the screensaver
                    Runtime.getRuntime().exec(new String[]{"pm", "grant", p[1], "android.permission.WRITE_SECURE_SETTINGS"}).waitFor();
                else if (p[0].equals("j")) {
                    String[] xy = p[1].split(",");
                    stick(Math.max(-100, Math.min(100, Integer.parseInt(xy[0]))) / 100f, Math.max(-100, Math.min(100, Integer.parseInt(xy[1]))) / 100f);
                }
            } catch (Exception ignored) {
                // a bad line: nothing to press
            }
        }
        System.exit(0);
    }

    // ---- the game controller: gamepad buttons held together, and a thumb stick ----

    private static final java.util.Map<Integer, Long> padDown = new java.util.HashMap<>();

    private static void pad(int code, int action) throws Exception {
        long now = SystemClock.uptimeMillis();
        Long at = padDown.get(code);
        if (action == KeyEvent.ACTION_DOWN) {
            if (at != null) return;
            padDown.put(code, now);
            at = now;
        } else {
            if (at == null) return;
            padDown.remove(code);
        }
        boolean dpad = code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_CENTER;
        KeyEvent e = new KeyEvent(at, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                dpad ? InputDevice.SOURCE_DPAD | InputDevice.SOURCE_GAMEPAD : InputDevice.SOURCE_GAMEPAD);
        inject.invoke(manager, e, 0);
    }

    private static void stick(float x, float y) throws Exception {
        long now = SystemClock.uptimeMillis();
        android.view.MotionEvent.PointerProperties[] props = {new android.view.MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = android.view.MotionEvent.TOOL_TYPE_UNKNOWN;
        android.view.MotionEvent.PointerCoords[] coords = {new android.view.MotionEvent.PointerCoords()};
        coords[0].setAxisValue(android.view.MotionEvent.AXIS_X, x);
        coords[0].setAxisValue(android.view.MotionEvent.AXIS_Y, y);
        android.view.MotionEvent m = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_MOVE, 1, props, coords, 0, 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_JOYSTICK, 0);
        inject.invoke(manager, m, 0);
        m.recycle();
        // Android itself turns stick pushes into arrow presses for menus, so nothing more is needed here
    }

    private static void send(int action, int code, long downAt, int repeat) throws Exception {
        long now = SystemClock.uptimeMillis();
        KeyEvent e = new KeyEvent(downAt, now, action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        inject.invoke(manager, e, 0);
    }

    private static void down(int code) throws Exception {
        if (held >= 0) up(held);
        long at = SystemClock.uptimeMillis();
        held = code;
        heldSince = at;
        send(KeyEvent.ACTION_DOWN, code, at, 0);
        Thread t = new Thread(() -> {   // nothing repeats pressed keys for us here
            try {
                Thread.sleep(FIRST_REPEAT_MS);
                for (int repeat = 1; held == code && heldSince == at; repeat++) {
                    if (SystemClock.uptimeMillis() - at > LONGEST_HOLD_MS) {   // the phone went away mid-press
                        up(code);
                        return;
                    }
                    send(KeyEvent.ACTION_DOWN, code, at, repeat);
                    Thread.sleep(REPEAT_MS);
                }
            } catch (Exception ignored) {
                // ended
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void up(int code) throws Exception {
        long at = heldSince;
        if (held == code) held = -1;
        send(KeyEvent.ACTION_UP, code, at > 0 ? at : SystemClock.uptimeMillis(), 0);
    }

    private static void type(String text) throws Exception {
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if (events == null) return;   // letters this keyboard can't make (Hindi, for one)
        for (KeyEvent e : events) inject.invoke(manager, e, 0);
    }
}
