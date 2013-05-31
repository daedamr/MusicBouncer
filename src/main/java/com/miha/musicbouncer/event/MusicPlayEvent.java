package com.miha.musicbouncer.event;

public class MusicPlayEvent {

    private boolean playing;

    public MusicPlayEvent(boolean playing) {
        this.playing = playing;
    }

    public boolean isPlaying() {
        return playing;
    }
}
