package com.nareshjagriya.tvtown;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * Keeps TV Town running while a song plays (so the TV does not close it to save memory when another screen or app
 * is in front). It only holds the "playing" notice; MusicPlayer does the playing.
 */
public class MusicService extends Service {

    private static final String CHANNEL = "music";
    private static final int ID = 7;

    /** Starts the service while playing, and lets it go when paused or stopped. */
    static void update(Context ctx, boolean playing) {
        Intent i = new Intent(ctx, MusicService.class);
        try {
            if (playing) ctx.startForegroundService(i);
            else ctx.stopService(i);
        } catch (Exception ignored) {
            // not allowed right now: the music still plays while TV Town is open
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.my_music), NotificationManager.IMPORTANCE_LOW));
        }
        MyMusic.Song s = MusicPlayer.get(this).current();
        PendingIntent open = PendingIntent.getActivity(this, 0, MusicPlayer.openIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(s != null ? s.title : getString(R.string.my_music))
                .setContentText(s != null ? s.artist : "")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        startForeground(ID, n);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
