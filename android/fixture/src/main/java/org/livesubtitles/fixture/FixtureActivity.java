package org.livesubtitles.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.media.*;
import android.widget.TextView;

/** Synthetic speech fixture for opt-in playback-capture tests. Not part of the user APK. */
public final class FixtureActivity extends Activity {
    private MediaPlayer player;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView text = new TextView(this); text.setText("Тестовый источник\nСинтезированная английская речь\n\nHello everyone. Today we are testing real-time subtitles."); text.setTextSize(24); text.setPadding(24, 120, 24, 24); setContentView(text);
        getSystemService(AudioManager.class).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL);
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build();
        player = MediaPlayer.create(this, R.raw.speech, attrs, 0); player.setLooping(true); player.start();
    }
    @Override protected void onDestroy() { if (player != null) player.release(); super.onDestroy(); }
}
