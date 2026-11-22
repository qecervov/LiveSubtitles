package org.livesubtitles.android;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** Runs only on an isolated emulator with the synthetic fixture app installed. */
@RunWith(AndroidJUnit4.class)
public class CaptureSmokeTest {
    @Test(timeout = 180000) public void capturesChosenAppAndShowsTranslatedOverlay() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Run NativeSmokeTest to install verified models first", Models.ready(context));
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("pm grant org.livesubtitles.android android.permission.RECORD_AUDIO");
        device.executeShellCommand("appops set org.livesubtitles.android SYSTEM_ALERT_WINDOW allow");
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("package", "org.livesubtitles.fixture").putString("language", "en").putBoolean("original", true).putInt("font", 24).commit();
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue(device.wait(Until.hasObject(By.text("CC  LiveSubtitles")), 15000));
        new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text("Начать перевод"));
        device.findObject(By.text("Начать перевод")).click();
        UiObject2 grant = device.wait(Until.findObject(By.text(Pattern.compile("(?i)(Start now|Start recording|Start|Начать запись|Начать)"))), 10000);
        assertNotNull("System consent dialog must be visible", grant);
        grant.click();
        assertTrue(device.wait(Until.hasObject(By.textContains("Тестовый источник\n")), 20000));
        assertTrue(State.capturing);
        long deadline = SystemClock.elapsedRealtime() + 120000;
        UiObject2 caption = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            caption = device.findObject(By.pkg("org.livesubtitles.android").text(Pattern.compile("(?s).*(привет|Всем|субтитр|Сегодня|сегодня).*")));
            if (caption != null && !caption.getText().contains("Показ")) break;
            Thread.sleep(500);
        }
        assertNotNull("Expected Russian subtitle from captured synthetic speech", caption);
        android.os.Bundle result = new android.os.Bundle(); result.putString("stream", "CAPTURED_SUBTITLE: " + caption.getText() + "\n"); InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
        File screenshot = new File(context.getExternalFilesDir(null), "capture-smoke.png");
        device.takeScreenshot(screenshot);
        assertTrue("Controls must hide during playback", device.wait(Until.gone(By.desc("Остановить перевод")), 5000));
        UiObject2 currentCaption = device.wait(Until.findObject(By.desc("Субтитры. Нажмите для управления")), 15000);
        assertNotNull(currentCaption); currentCaption.click();
        UiObject2 stop = device.wait(Until.findObject(By.desc("Остановить перевод")), 3000);
        assertNotNull(stop); stop.click();
        long stopDeadline = SystemClock.elapsedRealtime() + 10000;
        while (State.capturing && SystemClock.elapsedRealtime() < stopDeadline) Thread.sleep(100);
        assertFalse(State.capturing);
        assertFalse(device.hasObject(By.desc("Остановить перевод")));
    }
}
