package com.miha.musicbouncer.service;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.os.RemoteException;
import roboguice.inject.ContextSingleton;
import roboguice.service.RoboService;

import java.io.IOException;

@ContextSingleton
public class MusicBouncerService extends RoboService implements AudioManager.OnAudioFocusChangeListener,
        MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener{
    private MediaPlayer mMediaPlayer;

    @Override
    public IBinder onBind(Intent intent) {
        return new IMusicService.Stub() {

            public IMusicListener musicListener;

            @Override
            public void playFile(String filename) {
               if (mMediaPlayer == null) {
                   initMediaPlayer();
                }

                setFileAndPrepare(filename);
            }

            @Override
            public void pausePlaying() {
                if (mMediaPlayer != null) {
                    if (mMediaPlayer.isPlaying()) mMediaPlayer.pause();
                }
            }

            @Override
            public void stopPlaying() {
                if (mMediaPlayer != null) {
                    if (mMediaPlayer.isPlaying()) mMediaPlayer.stop();
                    mMediaPlayer.release();
                    mMediaPlayer = null;
                }
                stopSelf();
            }

            @Override
            public void restartPlaying(String filename) {
                if (mMediaPlayer != null) {
                    mMediaPlayer.start();
                } else {
                    initMediaPlayer();
                    setFileAndPrepare(filename);
                }
            }

            /**
             * Define a listener to detect when finishing playing a media file
             * @param musicListener The listener to be called
             */
            @Override
            public void setOnCompleteMusicListener(final IMusicListener musicListener) {
                this.musicListener = musicListener;
                mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer mp) {
                        if (musicListener != null) {
                            try {
                                musicListener.onMusicComplete();
                            } catch (RemoteException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                });
            }

            @Override
            public boolean isPlaying() {
                return mMediaPlayer != null && mMediaPlayer.isPlaying();
            }
        };

    }

    private void setFileAndPrepare(String filename) {
        mMediaPlayer.reset();
        try {
            mMediaPlayer.setDataSource(filename);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mMediaPlayer.prepareAsync();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStart(intent, startId);
        if (mMediaPlayer == null) {
            initMediaPlayer();
        }

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        final int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stopSelf();
        }

        return START_STICKY;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                // resume playback
                if (mMediaPlayer == null) initMediaPlayer();
                else if (!mMediaPlayer.isPlaying()) mMediaPlayer.start();
                mMediaPlayer.setVolume(1.0f, 1.0f);
                break;

            case AudioManager.AUDIOFOCUS_LOSS:
                // Lost focus for an unbounded amount of time: stop playback and release media player
                if (mMediaPlayer.isPlaying()) mMediaPlayer.stop();
                mMediaPlayer.release();
                mMediaPlayer = null;
                stopSelf();
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Lost focus for a short time, but we have to stop
                // playback. We don't release the media player because playback
                // is likely to resume
                if (mMediaPlayer.isPlaying()) mMediaPlayer.pause();
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Lost focus for a short time, but it's ok to keep playing
                // at an attenuated level
                if (mMediaPlayer.isPlaying()) mMediaPlayer.setVolume(0.1f, 0.1f);
                break;
        }
    }

    protected void initMediaPlayer() {
        mMediaPlayer = new MediaPlayer();
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setOnPreparedListener(MusicBouncerService.this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseMediaPlayer();
    }

    /**
     * Release memory for media player when done or in case of error
     */
    protected void releaseMediaPlayer() {
        if (mMediaPlayer != null) {
            mMediaPlayer.setOnPreparedListener(null);
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }

    @Override
    public void onPrepared(MediaPlayer mediaPlayer) {
        mediaPlayer.start();
    }

    @Override
    public boolean onError(MediaPlayer mediaPlayer, int i, int i2) {
        releaseMediaPlayer();
        return false;
    }
}
