package org.livesubtitles.android;

/** In-memory UI state only. No recording, history, identifiers or persistent event log. */
final class State {
    static volatile boolean capturing, downloading;
    static volatile int downloadProgress = -1;
    static volatile String status = "Подготовьте модели и выберите приложение с видео.";
    private State() { }
}
