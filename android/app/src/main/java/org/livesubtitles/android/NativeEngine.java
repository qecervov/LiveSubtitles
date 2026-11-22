package org.livesubtitles.android;

final class NativeEngine {
    static { System.loadLibrary("livesubtitles"); }
    static native long create(String whisper, String modelDirectory, String tokenizer, int threads);
    static native String[] process(long handle, short[] pcm, String sourceLanguage);
    static native void cancel(long handle);
    static native void destroy(long handle);
    private NativeEngine() { }
}
