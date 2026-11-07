package com.nareshjagriya.tvtown;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A search line at the top right of My Music or My Movies, like a web search: the TV's keyboard comes up (its mic
 * works too), the list on the screen updates with every letter, and names that start with what is typed drop down
 * under the line (songs, films, singers, folders). The keyboard's 🔍 key closes it and puts the focus on those
 * suggestions (▲ ▼ to choose, OK to take one); ▼ past the last, or Back, goes to the results.
 */
final class SearchBox {

    private SearchBox() {
    }

    private static Runnable openClose;   // closes the line that is open, if any

    /** Back while the line is open: closes it (true), else false. */
    static boolean back() {
        if (openClose == null) return false;
        openClose.run();
        return true;
    }

    /**
     * Opens the line with `initial` in it. `typed` gets the text on every change; `suggest` gives the names to offer
     * for it (at most six are shown); `closed` runs when the line goes away.
     */
    static void open(Activity a, String hint, String initial, Consumer<String> typed, Function<String, List<String>> suggest,
                     Runnable closed) {
        float k = a.getResources().getDisplayMetrics().widthPixels / 1920f;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setHint("🔍   " + hint);
        input.setHintTextColor(0x99FFFFFF);
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, 32 * k);
        input.setPadding(Math.round(36 * k), 0, Math.round(36 * k), 0);
        input.setBackground(round(0xF0161A2C, 38 * k, Color.WHITE, k));
        input.setText(initial);
        input.setSelection(input.length());
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(78 * k)));
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Math.round(10 * k), Math.round(10 * k), Math.round(10 * k), Math.round(10 * k));
        list.setBackground(round(0xF0161A2C, 26 * k, 0x55FFFFFF, k));
        list.setVisibility(View.GONE);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Math.round(8 * k);
        box.addView(list, llp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(980 * k), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.topMargin = Math.round(40 * k);
        lp.rightMargin = Math.round(96 * k);
        a.addContentView(box, lp);

        boolean[] open = {true};
        Runnable close = () -> {
            if (!open[0]) return;
            open[0] = false;
            openClose = null;
            hideKeyboard(a, input);
            if (box.getParent() instanceof ViewGroup) ((ViewGroup) box.getParent()).removeView(box);
            closed.run();
        };
        openClose = close;
        Consumer<String> fill = text -> {
            list.removeAllViews();
            List<String> names = text.trim().isEmpty() ? java.util.Collections.emptyList() : suggest.apply(text.trim());
            for (int i = 0; i < names.size() && i < 6; i++) {
                String name = names.get(i);
                TextView t = new TextView(a);
                t.setText("🔍   " + name);
                t.setTextColor(Color.WHITE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * k);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(Math.round(26 * k), 0, Math.round(26 * k), 0);
                t.setFocusable(true);
                t.setBackground(null);
                t.setOnFocusChangeListener((v, has) -> v.setBackground(has ? round(0xFF6A4BE0, 30 * k, Color.WHITE, k) : null));
                final int index = i;
                t.setOnKeyListener((v, code, ev) -> {
                    if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                        input.setText(name);   // the list updates through the watcher
                        close.run();
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_UP && index == 0) {
                        input.requestFocus();
                        showKeyboard(a, input);
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN && index == list.getChildCount() - 1 || code == KeyEvent.KEYCODE_BACK) {
                        close.run();
                        return true;
                    }
                    return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
                });
                list.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(64 * k)));
            }
            list.setVisibility(list.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                typed.accept(e.toString());
                if (open[0]) fill.accept(e.toString());
            }
        });
        // The keyboard's 🔍 key: the keyboard goes; the suggestions take the focus (or the results, without any).
        input.setOnEditorActionListener((v, id, ev) -> {
            hideKeyboard(a, input);
            if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
            else close.run();
            return true;
        });
        input.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                hideKeyboard(a, input);
                if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
                else close.run();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK) {
                close.run();
                return true;
            }
            return false;
        });
        fill.accept(initial);
        input.requestFocus();
        input.post(() -> showKeyboard(a, input));
    }

    private static GradientDrawable round(int colour, float radius, int stroke, float k) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(colour);
        d.setCornerRadius(radius);
        d.setStroke(Math.max(2, Math.round(3 * k)), stroke);
        return d;
    }

    private static void showKeyboard(Activity a, View v) {
        InputMethodManager imm = a.getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
    }

    private static void hideKeyboard(Activity a, View v) {
        InputMethodManager imm = a.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /**
     * Names to offer for what is typed, from the given names: whole names starting with it first, then names with a
     * word starting with it (each word typed must start a word of the name); the most common first within each.
     */
    static List<String> suggest(String typed, Iterable<String> names) {
        String q = typed.toLowerCase(java.util.Locale.ROOT).trim();
        String[] words = q.split("\\s+");
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        java.util.Map<String, String> shown = new java.util.HashMap<>();
        for (String n : names) {
            if (n == null || n.trim().length() < 2) continue;
            String low = n.toLowerCase(java.util.Locale.ROOT).trim();
            boolean every = true;
            for (String w : words) if (!(low.startsWith(w) || low.contains(" " + w) || low.contains("(" + w))) {
                every = false;
                break;
            }
            if (!every || low.equals(q)) continue;
            seen.merge(low, 1, Integer::sum);
            shown.putIfAbsent(low, n.trim());
        }
        List<String> out = new java.util.ArrayList<>(seen.keySet());
        java.util.Collections.sort(out, (x, y) -> {
            boolean px = x.startsWith(q), py = y.startsWith(q);
            if (px != py) return px ? -1 : 1;
            int c = Integer.compare(seen.get(y), seen.get(x));
            return c != 0 ? c : Integer.compare(x.length(), y.length());
        });
        List<String> res = new java.util.ArrayList<>();
        for (String s : out) if (res.size() < 6) res.add(shown.get(s));
        return res;
    }
}
