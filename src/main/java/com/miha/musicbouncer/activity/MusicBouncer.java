package com.miha.musicbouncer.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.os.*;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.github.rtyley.android.sherlock.roboguice.activity.RoboSherlockFragmentActivity;
import com.google.inject.Inject;
import com.miha.musicbouncer.R;
import com.miha.musicbouncer.cache.ImageCache;
import com.miha.musicbouncer.cache.ImageFetcher;
import com.miha.musicbouncer.event.MusicPlayEvent;
import com.miha.musicbouncer.service.IMusicListener;
import com.miha.musicbouncer.service.IMusicService;
import com.miha.musicbouncer.service.MusicBouncerService;
import roboguice.event.EventListener;
import roboguice.event.EventManager;
import roboguice.inject.InjectView;
import roboguice.util.Ln;
import roboguice.util.Strings;

public class MusicBouncer extends RoboSherlockFragmentActivity {
    private static final String IMAGE_CACHE_DIR = "thumbs";

    @InjectView(R.id.music_list) protected ListView musiclist;
    @InjectView(R.id.play) protected ImageButton playButton;

    @Inject protected EventManager eventManager;
    @Inject protected LayoutInflater inflater;
    protected ImageFetcher imageFetcher;
    private Cursor musiccursor;
    private int count;
    private int music_column_index;
    private int lastKnownPosition;
    private boolean isPlaying;
    private IMusicService musicService;
    private boolean bound;
    private boolean shouldShuffle;
    private boolean shouldRepeat;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        eventManager.registerObserver(MusicPlayEvent.class, new EventListener<MusicPlayEvent>() {
            @Override
            public void onEvent(MusicPlayEvent event) {
                isPlaying = event.isPlaying();
                playButton.setImageDrawable(getResources()
                        .getDrawable(isPlaying ? R.drawable.pause_normal : R.drawable.play_normal));
            }
        });

        initImageCache();

        setListScrollListener();

        initMusicList();
        startMusicService();
    }

    protected void setListScrollListener() {
        musiclist.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                // Pause fetcher to ensure smoother scrolling when flinging
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING) {
                    imageFetcher.setPauseWork(true);
                } else {
                    imageFetcher.setPauseWork(false);
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        imageFetcher.setExitTasksEarly(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Unbind from the service
        if (bound) {
            unbindService(mConnection);
            bound = false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        imageFetcher.setPauseWork(false);
        imageFetcher.setExitTasksEarly(true);
        imageFetcher.flushCache();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        imageFetcher.closeCache();
    }

    protected void initImageCache() {
        ImageCache.ImageCacheParams cacheParams = new ImageCache.ImageCacheParams(this, IMAGE_CACHE_DIR);

        cacheParams.setMemCacheSizePercent(0.25f); // Set memory cache to 25% of app memory

        // The ImageFetcher takes care of loading images into our ImageView children asynchronously
        imageFetcher = new ImageFetcher(this);
        imageFetcher.addImageCache(getSupportFragmentManager(), cacheParams);
    }

    /**
     * Initialize a list of media files found on device
     */
    protected void initMusicList() {
        System.gc();
        String[] proj = { MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION };
        musiccursor = managedQuery(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                proj, null, null, null);
        count = musiccursor.getCount();
        musiclist.setAdapter(new MusicAdapter());

        musiclist.setOnItemClickListener(musicgridlistener);
    }

    private AdapterView.OnItemClickListener musicgridlistener = new AdapterView.OnItemClickListener() {
        public void onItemClick(AdapterView parent, View v, int position,
                                long id) {
            lastKnownPosition = position;
            try {
                musicService.playFile(getFileNameByLastPosition());
            } catch (RemoteException e) {
                Ln.e("Service exception: " + e);
            }

            eventManager.fire(new MusicPlayEvent(true));
        }
    };

    protected String getFileNameByLastPosition() {
        music_column_index = musiccursor
                .getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
        musiccursor.moveToPosition(lastKnownPosition);
        return musiccursor.getString(music_column_index);
    }

    public void onPlayClick(View view) {
        try {
            if (isPlaying) {
                musicService.pausePlaying();
            } else {
                musicService.restartPlaying(getFileNameByLastPosition());
            }
        } catch (RemoteException e) {
            Ln.e("Service exception: " + e);
        }

        eventManager.fire(new MusicPlayEvent(!isPlaying));
    }

    public void onStopClick(View view) {
        try {
            musicService.stopPlaying();
        } catch (RemoteException e) {
            Ln.e("Service exception: " + e);
        }

        eventManager.fire(new MusicPlayEvent(false));
    }

    public void onNextClick(View view) {
        handleNextPosition();
        try {
            musicService.playFile(getFileNameByLastPosition());
        } catch (RemoteException e) {
            Ln.e("Service exception: " + e);
        }
        eventManager.fire(new MusicPlayEvent(true));
    }

    protected void handleNextPosition() {
        if (lastKnownPosition == count - 1) {
            if (shouldRepeat) {
                lastKnownPosition = 0;
            } else {
                try {
                    musicService.stopPlaying();
                } catch (RemoteException e) {
                    Ln.e("Service exception: " + e);
                }
            }
        } else if (shouldShuffle) {
            lastKnownPosition = (int)(Math.random() * count);
        } else {
            lastKnownPosition++;
        }
    }

    private void handlePreviousPosition() {
        if (lastKnownPosition == 0) {
            if (shouldRepeat) {
                lastKnownPosition = 0;
            } else {
                try {
                    musicService.stopPlaying();
                } catch (RemoteException e) {
                    Ln.e("Service exception: " + e);
                }
            }
        } else if (shouldShuffle) {
            lastKnownPosition = (int)(Math.random() * count);
        } else {
            lastKnownPosition--;
        }
    }

    public void onPreviousClick(View view) {
        handlePreviousPosition();
        try {
            musicService.playFile(getFileNameByLastPosition());
        } catch (RemoteException e) {
            Ln.e("Service exception: " + e);
        }

        eventManager.fire(new MusicPlayEvent(true));
    }

    /**
     * Start service to keep in background
     */
    protected void startMusicService() {
        final Intent intent = new Intent(this, MusicBouncerService.class);
        // Bind to Music Service
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        stopService(intent);
        startService(intent);
    }

    public class MusicAdapter extends BaseAdapter {

        public int getCount() {
            return count;
        }

        public Object getItem(int position) {
            return position;
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            System.gc();
            final View layout = convertView != null ? convertView :
                    inflater.inflate(R.layout.music_list_item, null);
            final TextView title = (TextView)layout.findViewById(R.id.music_title);
            final TextView duration = (TextView)layout.findViewById(R.id.music_duration);
            final ImageView image = (ImageView)layout.findViewById(R.id.music_image);
            music_column_index = musiccursor
                    .getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            musiccursor.moveToPosition(position);
            title.setText(musiccursor.getString(music_column_index));

            music_column_index = musiccursor
                    .getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            musiccursor.moveToPosition(position);
            duration.setText(getDuration(musiccursor.getLong(music_column_index)));

            music_column_index = musiccursor
                    .getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            musiccursor.moveToPosition(position);
            final long albumId = musiccursor.getLong(music_column_index);

            imageFetcher.loadImage(albumId, image);

            return layout;
        }

        private CharSequence getDuration(long duration) {
            final String remainder = Strings.toString(duration % 60000 * 100);
            return duration / 60000 + ":" + remainder.substring(0,Math.min(2, remainder.length()));
        }
    }

    /** Defines callbacks for service binding, passed to bindService() */
    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            musicService = IMusicService.Stub.asInterface(service);
            try {
                musicService.setOnCompleteMusicListener(new IMusicListener.Stub() {
                    @Override
                    public void onMusicComplete() throws RemoteException {
                        handleNextPosition();
                        musicService.playFile(getFileNameByLastPosition());
                    }
                });
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            bound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            bound = false;
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getSupportMenuInflater().inflate(R.menu.music_menu, menu);
        final MenuItem shuffle = menu.findItem(R.id.shuffle);
        shuffle.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        shuffle.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                shouldShuffle = !item.isChecked();
                shuffle.setIcon(getResources().getDrawable(shouldShuffle ?
                        R.drawable.shuffle : R.drawable.shuffle_disabled));
                item.setChecked(shouldShuffle);
                return true;
            }
        });
        final MenuItem repeat = menu.findItem(R.id.repeat);
        repeat.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        repeat.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                shouldRepeat = !item.isChecked();
                repeat.setIcon(getResources().getDrawable(shouldRepeat ?
                        R.drawable.repeat : R.drawable.repeat_disabled));
                item.setChecked(shouldRepeat);
                return true;
            }
        });
        return super.onCreateOptionsMenu(menu);
    }
}
