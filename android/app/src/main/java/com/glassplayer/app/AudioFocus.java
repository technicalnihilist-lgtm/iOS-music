package com.glassplayer.app;

import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

/**
 * Audio focus for the WebView player.
 *
 * The page tells us when it starts and stops playing (through the GP bridge), and we hold
 * {@link AudioManager#AUDIOFOCUS_GAIN} for exactly that span. Requesting focus when the activity
 * resumes would be simpler, but it would pause whatever the user was listening to before they had
 * played anything here.
 *
 * When focus is taken away we ask the page to pause: a phone call or another player must not be
 * talked over. A duck request (a notification) leaves playback alone - a WebView has no per-stream
 * volume, and moving the page's own volume would desync its slider.
 */
final class AudioFocus {

    private final AudioManager am;
    private final Runnable onPauseRequested;

    /** Only used on API 26+, where the request is an object that must be reused to abandon it. */
    private AudioFocusRequest request;

    /** True only while the system has actually granted focus. */
    private boolean held;

    AudioFocus(AudioManager am, Runnable onPauseRequested) {
        this.am = am;
        this.onPauseRequested = onPauseRequested;
    }

    private final AudioManager.OnAudioFocusChangeListener listener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    if (change == AudioManager.AUDIOFOCUS_LOSS
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        /* Ignore a repeat callback once the focus is already gone, and never
                           pause for focus we never held. Give it up before pausing: the page's
                           pause event reports the stop back to us, and that path must not try to
                           abandon twice. */
                        if (!held) return;
                        abandon();
                        if (onPauseRequested != null) onPauseRequested.run();
                    }
                }
            };

    /** Called from the page whenever playback starts (true) or stops (false). */
    void onPlaybackState(boolean playing) {
        if (playing) {
            request();
        } else {
            abandon();
        }
    }

    void request() {
        if (held || am == null) return;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (request == null) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(listener)
                        .build();
            }
            result = am.requestAudioFocus(request);
        } else {
            result = am.requestAudioFocus(
                    listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    void abandon() {
        if (!held || am == null) return;
        held = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (request != null) am.abandonAudioFocusRequest(request);
        } else {
            am.abandonAudioFocus(listener);
        }
    }
}
