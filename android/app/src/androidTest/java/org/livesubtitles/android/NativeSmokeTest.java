package org.livesubtitles.android;

import android.content.Context;
import android.os.StrictMode;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeSmokeTest {
    @Test(timeout = 600000) public void realModelsRecognizeAndTranslateOnAndroid() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Models.install(context, new AtomicBoolean(), (text, progress) -> {
            android.os.Bundle status = new android.os.Bundle(); status.putString("stream", text + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
        });
        assertTrue(Models.ready(context));
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        short[] samples;
        try (InputStream input = tests.getAssets().open("speech.wav")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            ByteBuffer wav = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            int offset = 12, dataStart = -1, dataLength = 0;
            while (offset + 8 <= wav.limit()) {
                int size = wav.getInt(offset + 4);
                if (wav.getInt(offset) == 0x61746164) { dataStart = offset + 8; dataLength = size; break; }
                offset += 8 + size + (size & 1);
            }
            assertTrue(dataStart >= 0);
            samples = new short[Math.min(16000 * 4, dataLength / 2)];
            for (int i = 0; i < samples.length; i++) samples[i] = wav.getShort(dataStart + i * 2);
        }
        StrictMode.ThreadPolicy previous = StrictMode.getThreadPolicy();
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build());
        long engine = 0;
        try {
            engine = NativeEngine.create(Models.whisper(context).getPath(), Models.translation(context).getPath(), Models.tokenizer(context).getPath(), 4);
            assertTrue(engine != 0);
            String[] result = NativeEngine.process(engine, samples, "en");
            assertNotNull(result); assertEquals(2, result.length);
            assertTrue("Expected English recognition", result[1].toLowerCase().contains("hello"));
            assertTrue("Expected Russian translation", result[0].matches("(?s).*[А-Яа-я].*"));
        } finally { if (engine != 0) NativeEngine.destroy(engine); StrictMode.setThreadPolicy(previous); }
    }
}
