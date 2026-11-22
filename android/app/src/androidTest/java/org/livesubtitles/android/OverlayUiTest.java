package org.livesubtitles.android;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OverlayUiTest {
    @Test(timeout = 60000) public void controlsHideCaptionMovesAndIdleWindowDisappears() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        UiDevice device = UiDevice.getInstance(instrumentation);
        device.executeShellCommand("appops set org.livesubtitles.android SYSTEM_ALERT_WINDOW allow");
        Intent fixture = context.getPackageManager().getLaunchIntentForPackage("org.livesubtitles.fixture");
        assertNotNull(fixture); context.startActivity(fixture.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue(device.wait(Until.hasObject(By.textContains("Тестовый источник\n")), 10000));
        Overlay[] overlay = new Overlay[1]; AtomicInteger resets = new AtomicInteger();
        try {
            instrumentation.runOnMainSync(() -> {
                overlay[0] = new Overlay(context, false);
                overlay[0].show(24, () -> overlay[0].close(), resets::incrementAndGet);
                overlay[0].subtitle("Белый текст с чёрной обводкой на прозрачном фоне", "");
            });
            assertTrue(device.wait(Until.hasObject(By.desc("Остановить перевод")), 3000));
            assertTrue(device.wait(Until.gone(By.desc("Остановить перевод")), 5000));
            instrumentation.runOnMainSync(() -> overlay[0].subtitle("Белый текст с чёрной обводкой на прозрачном фоне", ""));
            UiObject2 caption = device.wait(Until.findObject(By.desc("Субтитры. Нажмите для управления")), 3000);
            assertNotNull(caption);
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "transparent-overlay.png"));
            int initialY = caption.getVisibleCenter().y;
            var center = caption.getVisibleCenter();
            device.drag(center.x, center.y, center.x, center.y - 150, 20);
            caption = device.findObject(By.desc("Субтитры. Нажмите для управления"));
            assertNotNull(caption); assertTrue("Dragging text must move the caption", caption.getVisibleCenter().y < initialY - 80);
            caption.click();
            UiObject2 reset = device.wait(Until.findObject(By.desc("Очистить очередь после перемотки")), 2000);
            assertNotNull(reset); reset.click();
            long resetDeadline = android.os.SystemClock.elapsedRealtime() + 2000;
            while (resets.get() == 0 && android.os.SystemClock.elapsedRealtime() < resetDeadline) Thread.sleep(50);
            assertEquals(1, resets.get());
            device.setOrientationLeft(); device.waitForIdle();
            instrumentation.runOnMainSync(() -> {
                overlay[0].configurationChanged();
                overlay[0].subtitle("Субтитры в горизонтальном режиме", "");
            });
            assertNotNull(device.wait(Until.findObject(By.text("Субтитры в горизонтальном режиме")), 3000));
            assertTrue(device.wait(Until.gone(By.desc("Остановить перевод")), 5000));
            instrumentation.runOnMainSync(() -> overlay[0].subtitle("Субтитры в горизонтальном режиме", ""));
            instrumentation.waitForIdleSync();
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "landscape-overlay.png"));
            instrumentation.runOnMainSync(() -> overlay[0].status("Очередь очищена"));
            assertNotNull(device.wait(Until.findObject(By.text("Очередь очищена")), 2000));
            assertTrue("Idle overlay must disappear", device.wait(Until.gone(By.desc("Субтитры. Нажмите для управления")), 5000));
            assertFalse(device.hasObject(By.desc("Остановить перевод")));
        } finally {
            instrumentation.runOnMainSync(() -> { if (overlay[0] != null) overlay[0].close(); });
            device.setOrientationNatural(); device.unfreezeRotation();
        }
    }
}
