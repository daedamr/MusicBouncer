package com.miha.musicbouncer.service;

import com.miha.musicbouncer.service.IMusicListener;

interface IMusicService {
void playFile(String filename);
void stopPlaying();
void pausePlaying();
void restartPlaying(String filename);
void setOnCompleteMusicListener(IMusicListener musicListener);
boolean isPlaying();
}
