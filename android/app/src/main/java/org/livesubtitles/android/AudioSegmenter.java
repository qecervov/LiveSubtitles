package org.livesubtitles.android;

import java.util.ArrayList;
import java.util.Arrays;

/** 16 kHz mono, 200 ms frames, bounded utterances. No Android dependency. */
final class AudioSegmenter {
    static final int FRAME_SAMPLES = 3200;
    private static final int MAX_FRAMES = 12; // 2.4 seconds, including pre-roll and trailing silence.
    private static final int PAUSE_FRAMES = 2; // Submit after a 400 ms pause.
    private final ArrayList<short[]> frames = new ArrayList<>();
    private short[] preroll;
    private int silent, voiced;

    short[] push(short[] input) {
        if (input.length != FRAME_SAMPLES) throw new IllegalArgumentException("Expected 200 ms PCM");
        double energy = 0;
        for (short value : input) energy += (double) value * value;
        boolean voice = Math.sqrt(energy / input.length) / 32768.0 >= .006;
        short[] frame = Arrays.copyOf(input, input.length);
        if (frames.isEmpty() && !voice) { preroll = frame; return null; }
        if (frames.isEmpty() && preroll != null) { frames.add(preroll); preroll = null; }
        frames.add(frame);
        if (voice) voiced++;
        silent = voice ? 0 : silent + 1;
        if (frames.size() < MAX_FRAMES && silent < PAUSE_FRAMES) return null;
        short[] output = null;
        if (voiced >= 2) {
            output = new short[frames.size() * FRAME_SAMPLES];
            for (int i = 0; i < frames.size(); i++) System.arraycopy(frames.get(i), 0, output, i * FRAME_SAMPLES, FRAME_SAMPLES);
        }
        reset();
        return output;
    }
    void reset() { frames.clear(); preroll = null; silent = 0; voiced = 0; }
}
