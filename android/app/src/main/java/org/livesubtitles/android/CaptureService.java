package org.livesubtitles.android;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.provider.Settings;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class CaptureService extends Service {
    private static final ExecutorService INFERENCE = Executors.newSingleThreadExecutor(r -> new Thread(r, "local-inference"));
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicInteger generation = new AtomicInteger();
    // At most one pending utterance: slow inference must not accumulate a delayed transcript.
    private final ArrayBlockingQueue<Block> queue = new ArrayBlockingQueue<>(1);
    private final AudioSegmenter segmenter = new AudioSegmenter();
    private final Object engineGuard = new Object();
    private long engine;
    private Future<?> inference;
    private Thread reader;
    private AudioRecord recorder;
    private MediaProjection projection;
    private Overlay overlay;
    private String language = "en";
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean screenReceiverRegistered;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { if (active.get()) stopSession("Перевод остановлен при выключении экрана."); }
    };
    private record Block(short[] pcm, long started, long completed, int generation) { }

    @Override public void onCreate() { super.onCreate(); Notifications.channel(this); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "STOP".equals(intent.getAction())) { stopSession("Перевод остановлен."); return START_NOT_STICKY; }
        if (active.get()) return START_NOT_STICKY;
        try {
            if (!Models.ready(this)) throw new IllegalStateException("Сначала установите модели.");
            if (!Settings.canDrawOverlays(this)) throw new IllegalStateException("Разрешите показ субтитров поверх других окон.");
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("Нет разрешения на захват звука.");
            String target = intent.getStringExtra("package");
            int uid = intent.getIntExtra("uid", -1);
            if (target == null || uid == android.os.Process.myUid() || getPackageManager().getApplicationInfo(target, 0).uid != uid) throw new IllegalArgumentException("Выберите приложение с видео.");
            language = intent.getStringExtra("language");
            if (language == null || !java.util.Arrays.asList("en", "auto", "ru", "de", "fr", "es", "it", "pt", "ja", "ko", "zh", "uk").contains(language)) language = "en";
            Intent consent = intent.getParcelableExtra("consent");
            int result = intent.getIntExtra("result", 0);
            if (consent == null || result != android.app.Activity.RESULT_OK) throw new SecurityException("Захват звука не разрешён.");
            startForeground(1, Notifications.make(this, "Локальный перевод звука выбранного приложения", CaptureService.class, "STOP", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            active.set(true); State.capturing = true; State.status = "Подготавливаю локальные модели…";
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            screenReceiverRegistered = true;
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(result, consent);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { if (active.get()) stopSession("Система завершила захват. Для продолжения запустите перевод заново."); }
            }, main);
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUid(uid).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum < 0) throw new IllegalStateException("Устройство не поддерживает нужный формат звука.");
            recorder = new AudioRecord.Builder().setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(minimum * 2, 32000)).setAudioPlaybackCaptureConfig(config).build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Не удалось включить захват звука.");
            overlay = new Overlay(this, intent.getBooleanExtra("original", false));
            overlay.show(Math.max(18, Math.min(36, intent.getIntExtra("font", 24))), () -> stopSession("Перевод остановлен."), this::resetQueue);
            inference = INFERENCE.submit(this::infer);
        } catch (Exception error) { stopSession(error.getMessage() == null ? "Не удалось запустить захват звука." : error.getMessage()); }
        return START_NOT_STICKY;
    }

    private void infer() {
        long local = 0;
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("Захват звука не разрешён.");
            local = NativeEngine.create(Models.whisper(this).getAbsolutePath(), Models.translation(this).getAbsolutePath(), Models.tokenizer(this).getAbsolutePath(), Math.min(4, Runtime.getRuntime().availableProcessors()));
            synchronized (engineGuard) { if (!active.get()) return; engine = local; }
            recorder.startRecording();
            reader = new Thread(this::readAudio, "playback-audio"); reader.start();
            State.status = "Перевод включён. Обработка выполняется на телефоне.";
            if (overlay != null) overlay.status("Слушаю звук выбранного приложения…");
            while (active.get()) {
                Block block = queue.take();
                if (block.generation != generation.get() || SystemClock.elapsedRealtime() - block.completed > 1500) continue;
                String[] output = NativeEngine.process(local, block.pcm, language);
                if (!active.get() || block.generation != generation.get()) continue;
                if (SystemClock.elapsedRealtime() - block.started > 6000) {
                    State.status = "Обработка не успевает за видео. Старый фрагмент пропущен.";
                } else if (output != null && output.length == 2 && overlay != null) overlay.subtitle(output[0], output[1]);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            if (active.get()) main.post(() -> stopSession("Локальная обработка не запустилась. Проверьте модели и свободную память."));
        } finally {
            synchronized (engineGuard) { engine = 0; }
            if (local != 0) NativeEngine.destroy(local);
        }
    }

    private void readAudio() {
        short[] frame = new short[AudioSegmenter.FRAME_SAMPLES]; int offset = 0;
        long lastSound = SystemClock.elapsedRealtime(); boolean silenceShown = false;
        try {
            while (active.get()) {
                int count = recorder.read(frame, offset, frame.length - offset, AudioRecord.READ_BLOCKING);
                if (!active.get()) break;
                if (count < 0) throw new IllegalStateException("Захват звука завершился.");
                if (count == 0) continue;
                offset += count;
                if (offset != frame.length) continue;
                offset = 0;
                long peak = 0; for (short sample : frame) peak = Math.max(peak, Math.abs((int) sample));
                if (peak > 250) { lastSound = SystemClock.elapsedRealtime(); silenceShown = false; }
                if (!silenceShown && SystemClock.elapsedRealtime() - lastSound > 12000) {
                    silenceShown = true;
                    if (overlay != null) overlay.status("Нет звука. Включите видео; приложение может запрещать захват.");
                }
                short[] phrase; int blockGeneration;
                synchronized (segmenter) { blockGeneration = generation.get(); phrase = segmenter.push(frame); }
                if (phrase != null) {
                    long completed = SystemClock.elapsedRealtime();
                    Block block = new Block(phrase, completed - phrase.length * 1000L / 16000, completed, blockGeneration);
                    if (!queue.offer(block)) { queue.poll(); queue.offer(block); State.status = "Старый фрагмент пропущен, чтобы сократить задержку."; }
                }
            }
        } catch (Exception ignored) { if (active.get()) main.post(() -> stopSession("Не удалось продолжить захват звука.")); }
    }
    private void resetQueue() {
        synchronized (segmenter) { generation.incrementAndGet(); segmenter.reset(); queue.clear(); }
        if (overlay != null) overlay.status("Очередь очищена. Слушаю новый фрагмент…");
    }
    private void stopSession(String message) {
        active.set(false); State.capturing = false; State.status = message; generation.incrementAndGet(); queue.clear();
        synchronized (engineGuard) { if (engine != 0) NativeEngine.cancel(engine); }
        if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) { } }
        if (inference != null) inference.cancel(true);
        if (reader != null) reader.interrupt();
        if (projection != null) { MediaProjection old = projection; projection = null; old.stop(); }
        if (overlay != null) { overlay.close(); overlay = null; }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() {
        if (active.get()) stopSession("Перевод завершён.");
        if (screenReceiverRegistered) { unregisterReceiver(screenOff); screenReceiverRegistered = false; }
        if (recorder != null) { recorder.release(); recorder = null; }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); if (overlay != null) overlay.configurationChanged(); }
}
