package com.nareshjagriya.tvtown;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Must-Watch (MustWatchView), opened from Discover. Playing and trailers work as in Discover. */
public class MustWatchActivity extends Activity implements MustWatchView.Listener {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));   // the language chosen in Customize
    }

    private MustWatchView view;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_must_watch);
        view = findViewById(R.id.must_watch);
        view.setListener(this);
        view.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.setRunning(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.setRunning(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        if (!view.back()) super.onBackPressed();
    }

    @Override
    public void onTrailer(Tmdb.Title title) {
        new Thread(() -> {
            Intent i = DeepLinks.trailer(this, title);
            handler.post(() -> start(i));
        }, "trailer").start();
    }

    /** The title's page in the app, else the app, else the app's Play Store page. */
    @Override
    public void onOpen(Tmdb.Title title, Services.Service service) {
        if (service == null) return;
        Toast.makeText(this, getString(R.string.opening, title.title), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            DeepLinks.Plan plan = DeepLinks.plan(this, title, service);
            handler.post(() -> {
                if (plan.intent == null) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (plan.outcome == DeepLinks.Outcome.APP) {
                    Toast.makeText(this, getString(R.string.opened_app_search, service.name, title.title), Toast.LENGTH_LONG).show();
                } else if (plan.outcome == DeepLinks.Outcome.STORE) {
                    Toast.makeText(this, getString(R.string.get_app_first, service.name), Toast.LENGTH_LONG).show();
                }
                start(plan.intent);
            });
        }, "open-title").start();
    }

    private void start(Intent i) {
        if (i == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
