package com.nareshjagriya.tvtown;

import android.service.notification.NotificationListenerService;

/**
 * Needed only so TV Town may see what's playing on the TV (Settings → Now playing): Android shows apps' media sessions
 * to an app whose notification listener has notification access. It reads no notifications.
 */
public class MediaWatch extends NotificationListenerService {
}
