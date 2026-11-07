package com.nareshjagriya.tvtown;

import android.app.Activity;

import java.util.function.Consumer;

/** A line of text, typed on TV Town's own keyboard (TvKeyboard) or on a phone from its QR code. Back gives null. */
final class TextBox {

    private TextBox() {
    }

    /** Exactly as typed: keys, codes, places. */
    static void ask(Activity a, String title, String hint, String initial, String okLabel, Consumer<String> done) {
        TvKeyboard.ask(a, title, hint, initial, okLabel, false, done);
    }

    /** Names: each word starts with a capital. */
    static void askName(Activity a, String title, String hint, String initial, String okLabel, Consumer<String> done) {
        TvKeyboard.ask(a, title, hint, initial, okLabel, true, done);
    }
}
