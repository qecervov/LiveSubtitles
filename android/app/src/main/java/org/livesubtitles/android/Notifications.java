package org.livesubtitles.android;

import android.app.*;
import android.content.*;

final class Notifications {
    static final String CHANNEL = "local-processing";
    static void channel(Context c) {
        c.getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Локальная обработка", NotificationManager.IMPORTANCE_LOW));
    }
    static Notification make(Context c, String text, Class<?> service, String stopAction, int progress) {
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(c, service == CaptureService.class ? 1 : 2,
            new Intent(c, service).setAction(stopAction), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("LiveSubtitles").setContentText(text).setContentIntent(open).setOngoing(true)
            .setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null, "Остановить", stop).build());
        if (progress >= 0) builder.setProgress(100, progress, false);
        return builder.build();
    }
    private Notifications() { }
}
