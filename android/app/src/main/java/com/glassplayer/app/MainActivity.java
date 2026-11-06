package com.glassplayer.app;

import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private static final int REQ_PICK_AUDIO = 1001;

    private WebView web;
    private boolean webDestroyed;
    private ValueCallback<Uri[]> pendingFileCallback;
    private AudioFocus focus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        web.setBackgroundColor(Color.parseColor("#0b0b1a"));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // The file input in the page only works if we handle the chooser ourselves.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;

                Intent intent = null;
                try {
                    intent = params.createIntent();
                } catch (Exception ignored) {
                }
                if (intent == null) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("audio/*");
                }
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(
                            Intent.createChooser(intent, "Select songs"), REQ_PICK_AUDIO);
                } catch (Exception e) {
                    pendingFileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient());
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        /* Audio focus follows real playback, not the activity lifecycle: the page calls
           GP.setPlaying() from its own play/pause handlers, so other apps are only interrupted
           when something is actually coming out of the speaker. */
        focus = new AudioFocus((AudioManager) getSystemService(Context.AUDIO_SERVICE),
                new Runnable() {
                    @Override
                    public void run() {
                        pausePlayback();
                    }
                });
        web.addJavascriptInterface(new FocusBridge(), "GP");

        web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor("#0b0b1a"));
            getWindow().setNavigationBarColor(Color.parseColor("#0b0b1a"));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_AUDIO) {
            if (pendingFileCallback == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            pendingFileCallback.onReceiveValue(results);
            pendingFileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** Receives the page's play/pause state. Called off the UI thread, so hop back to it. */
    private final class FocusBridge {
        @JavascriptInterface
        public void setPlaying(final boolean playing) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (focus != null) focus.onPlaybackState(playing);
                }
            });
        }
    }

    /** Pause through the page's own transport button, so its state and artwork stay in sync. */
    private void pausePlayback() {
        if (web == null) return;
        web.evaluateJavascript(
                "(function(){try{var a=document.getElementById('au');"
                        + "if(a&&!a.paused){document.getElementById('pp').click()}}catch(e){}})()",
                null);
    }

    /**
     * Hardware back: let the page close its sheet or collapse the player first.
     * Only leave the app when the page says it has nothing left to dismiss.
     */
    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript(
                "(function(){try{return window.__onBack?window.__onBack():false}catch(e){return false}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        boolean handled = "true".equals(value);
                        if (!handled) {
                            if (web.canGoBack()) {
                                web.goBack();
                            } else {
                                finish();
                            }
                        }
                    }
                });
    }

    /**
     * The system tells us it is short of memory. The page is a single local asset with no
     * network traffic, so the WebView's cache holds nothing worth keeping and is the cheapest
     * thing to give back: dropping it frees the decoded resource cache without touching DOM
     * storage, the loaded page, the playlist or the audio element, so nothing the listener can
     * see changes. Only levels at or above RUNNING_LOW are acted on, which covers the hidden
     * and background callbacks an activity actually receives as well as genuine pressure.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return;
        releaseWebCache();
    }

    /** Clear the WebView cache, on the UI thread, without ever touching a destroyed view. */
    private void releaseWebCache() {
        if (web == null || webDestroyed) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            web.post(new Runnable() {
                @Override
                public void run() {
                    releaseWebCache();
                }
            });
            return;
        }
        try {
            web.clearCache(true);
        } catch (Exception ignored) {
            // A WebView torn down underneath us must not take the process with it.
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = null;
        }
        if (focus != null) focus.abandon();
        if (web != null) {
            webDestroyed = true;
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }
}
