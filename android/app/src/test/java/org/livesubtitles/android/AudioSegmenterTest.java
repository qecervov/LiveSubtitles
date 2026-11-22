package org.livesubtitles.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioSegmenterTest {
    private short[] voice() { short[] frame = new short[3200]; for (int i = 0; i < frame.length; i++) frame[i] = (short) (i % 2 == 0 ? 4000 : -4000); return frame; }
    @Test public void silenceDoesNotGenerateWork() {
        AudioSegmenter s = new AudioSegmenter();
        for (int i = 0; i < 500; i++) assertNull(s.push(new short[3200]));
    }
    @Test public void flushesWithinFourHundredMillisecondsOfPause() {
        AudioSegmenter s = new AudioSegmenter();
        s.push(new short[3200]); s.push(voice()); s.push(voice());
        assertNull(s.push(new short[3200]));
        short[] result = s.push(new short[3200]);
        assertNotNull(result); assertEquals(5 * 3200, result.length);
        assertEquals(0, result[0]); assertEquals(4000, result[3200]);
    }
    @Test public void continuousSpeechIsBounded() {
        AudioSegmenter s = new AudioSegmenter(); int results = 0;
        for (int i = 0; i < 72; i++) {
            short[] result = s.push(voice());
            if (result != null) { results++; assertEquals(12 * 3200, result.length); }
        }
        assertEquals(6, results);
    }
    @Test public void resetDiscardsOldSpeech() {
        AudioSegmenter s = new AudioSegmenter(); s.push(voice()); s.push(voice()); s.reset();
        for (int i = 0; i < 4; i++) assertNull(s.push(new short[3200]));
    }
    @Test public void shortNoiseIsIgnored() {
        AudioSegmenter s = new AudioSegmenter(); s.push(voice());
        for (int i = 0; i < 3; i++) assertNull(s.push(new short[3200]));
    }
    @Test public void inputBufferCanBeReused() {
        AudioSegmenter s = new AudioSegmenter(); short[] frame = voice(); s.push(frame); s.push(frame);
        java.util.Arrays.fill(frame, (short) 0); s.push(frame);
        short[] result = s.push(frame); assertNotNull(result); assertEquals(4000, result[0]);
    }
    @Test(expected = IllegalArgumentException.class) public void malformedFrameIsRejected() { new AudioSegmenter().push(new short[2]); }
}
