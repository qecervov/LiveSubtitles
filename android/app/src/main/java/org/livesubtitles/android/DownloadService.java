package org.livesubtitles.android;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DownloadService extends Service {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Thread worker;
    @Override public void onCreate() { super.onCreate(); Notifications.channel(this); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { cancelled.set(true); State.status = "Отменяю загрузку…"; return START_NOT_STICKY; }
        if (worker != null) return START_NOT_STICKY;
        startForeground(2, Notifications.make(this, "Подготавливаю модели…", DownloadService.class, "STOP", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        State.downloading = true;
        worker = new Thread(() -> {
            try {
                Models.install(this, cancelled, (text, progress) -> {
                    State.status = text; State.downloadProgress = progress;
                    getSystemService(NotificationManager.class).notify(2, Notifications.make(this, text, DownloadService.class, "STOP", progress));
                });
                State.status = "Модели готовы. Выберите приложение и нажмите «Начать перевод».";
            } catch (Exception error) {
                State.status = cancelled.get() ? "Загрузка отменена. Можно повторить позже." : "Загрузка не завершилась: " + error.getMessage();
            } finally {
                State.downloading = false; State.downloadProgress = -1;
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            }
        }, "model-download");
        worker.start();
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() { cancelled.set(true); super.onDestroy(); }
    @Override public void onTimeout(int startId, int fgsType) { cancelled.set(true); stopSelf(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
