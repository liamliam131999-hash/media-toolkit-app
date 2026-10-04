package com.mytranscriber.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.CanvasOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.AudioEncoderSettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Media Toolkit
 *
 * Functions:
 * 1. WebView
 * 2. File picker for HTML
 * 3. Audio compressor
 * 4. SRT video picker
 * 5. SRT file picker
 * 6. Myanmar UTF-8 / UTF-16 SRT reader
 * 7. Burn SRT subtitles into video
 * 8. Text color / outline / background / position / effects
 * 9. Auto-save output
 */
public class MainActivity extends AppCompatActivity {

    // ============================================================
    // REQUEST CODES
    // ============================================================

    private static final int FILE_PICKER_REQUEST = 1001;
    private static final int WEB_FILE_PICKER_REQUEST = 2001;
    private static final int SAVE_FILE_REQUEST = 3001;

    private static final int SRT_VIDEO_REQUEST = 5001;
    private static final int SRT_FILE_REQUEST = 5002;

    // ============================================================
    // WEBVIEW
    // ============================================================

    private WebView webView;

    private ValueCallback<Uri[]> webFilePathCallback;

    // ============================================================
    // NORMAL COMPRESSOR
    // ============================================================

    private Transformer compressionTransformer;

    private String compressionOutputPath;

    private Uri compressionInputUri;

    // ============================================================
    // SRT BURNER
    // ============================================================

    private Uri srtVideoUri;
    private Uri srtFileUri;

    private String srtVideoName = "";
    private String srtFileName = "";

    private Transformer srtTransformer;

    private String srtOutputPath;

    private volatile boolean srtExportRunning = false;

    // ============================================================
    // PROGRESS
    // ============================================================

    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final ProgressHolder progressHolder = new ProgressHolder();

    private final Runnable compressionProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (compressionTransformer == null) {
                return;
            }

            try {
                int state = compressionTransformer.getProgress(progressHolder);

                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    int progress = Math.max(
                            0,
                            Math.min(100, progressHolder.progress)
                    );

                    sendJs(
                            "window.compressionProgress && " +
                            "window.compressionProgress(" +
                            progress + ");"
                    );
                }

                mainHandler.postDelayed(
                        this,
                        300
                );

            } catch (Exception ignored) {
            }
        }
    };

    private final Runnable srtProgressRunnable = new Runnable() {
        @Override
        public void run() {

            if (!srtExportRunning || srtTransformer == null) {
                return;
            }

            try {

                int state =
                        srtTransformer.getProgress(progressHolder);

                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {

                    int progress =
                            Math.max(
                                    0,
                                    Math.min(
                                            100,
                                            progressHolder.progress
                                    )
                            );

                    sendJs(
                            "window.srtBurnProgress && " +
                            "window.srtBurnProgress(" +
                            progress +
                            ",'Burning subtitle...');"
                    );
                }

            } catch (Exception ignored) {
            }

            mainHandler.postDelayed(
                    this,
                    300
            );
        }
    };


    // ============================================================
    // ACTIVITY
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupWebView();
    }


    // ============================================================
    // WEBVIEW SETUP
    // ============================================================

    private void setupWebView() {

        webView = new WebView(this);

        setContentView(webView);

        webView.setBackgroundColor(Color.BLACK);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);

        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(
                new WebViewClient() {

                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView view,
                            WebResourceRequest request
                    ) {
                        return false;
                    }
                }
        );


        webView.setWebChromeClient(
                new WebChromeClient() {

                    @Override
                    public boolean onShowFileChooser(
                            WebView webView,
                            ValueCallback<Uri[]> filePathCallback,
                            FileChooserParams fileChooserParams
                    ) {

                        if (webFilePathCallback != null) {
                            webFilePathCallback.onReceiveValue(null);
                        }

                        webFilePathCallback = filePathCallback;

                        try {

                            Intent intent =
                                    fileChooserParams.createIntent();

                            startActivityForResult(
                                    intent,
                                    WEB_FILE_PICKER_REQUEST
                            );

                        } catch (Exception e) {

                            webFilePathCallback = null;

                            Toast.makeText(
                                    MainActivity.this,
                                    "File picker မဖွင့်နိုင်ပါ",
                                    Toast.LENGTH_SHORT
                            ).show();

                        }

                        return true;
                    }
                }
        );


        webView.addJavascriptInterface(
                new AndroidBridge(),
                "Android"
        );


        webView.loadUrl(
                "file:///android_asset/index.html"
        );
    }


    // ============================================================
    // JAVASCRIPT BRIDGE
    // ============================================================

    public class AndroidBridge {

        // --------------------------------------------------------
        // NORMAL COMPRESSOR
        // --------------------------------------------------------

        @JavascriptInterface
        public void pickFile() {

            runOnUiThread(
                    () -> openCompressionPicker()
            );
        }


        // --------------------------------------------------------
        // SRT VIDEO
        // --------------------------------------------------------

        @JavascriptInterface
        public void pickSrtVideo() {

            runOnUiThread(
                    () -> openSrtVideoPicker()
            );
        }


        // --------------------------------------------------------
        // SRT FILE
        // --------------------------------------------------------

        @JavascriptInterface
        public void pickSrtFile() {

            runOnUiThread(
                    () -> openSrtFilePicker()
            );
        }


        // --------------------------------------------------------
        // BURN SRT
        // --------------------------------------------------------

        @JavascriptInterface
        public void burnSrt(
                String textColor,
                String outlineColor,
                String backgroundColor,
                String effect,
                String position,
                int fontSize,
                int outlineWidth,
                boolean backgroundEnabled,
                boolean bold
        ) {

            runOnUiThread(
                    () -> startSrtBurn(
                            textColor,
                            outlineColor,
                            backgroundColor,
                            effect,
                            position,
                            fontSize,
                            outlineWidth,
                            backgroundEnabled,
                            bold
                    )
            );
        }


        // --------------------------------------------------------
        // OPTIONAL CANCEL
        // --------------------------------------------------------

        @JavascriptInterface
        public void cancelSrtBurn() {

            runOnUiThread(
                    () -> {

                        try {

                            if (srtTransformer != null) {
                                srtTransformer.cancel();
                            }

                            srtExportRunning = false;

                            sendJs(
                                    "window.srtBurnFailed && " +
                                    "window.srtBurnFailed(" +
                                    jsQuote("Cancelled") +
                                    ");"
                            );

                        } catch (Exception ignored) {
                        }
                    }
            );
        }
    }


    // ============================================================
    // NORMAL COMPRESSOR FILE PICKER
    // ============================================================

    private void openCompressionPicker() {

        Intent intent = new Intent(
                Intent.ACTION_OPEN_DOCUMENT
        );

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType("*/*");

        startActivityForResult(
                intent,
                FILE_PICKER_REQUEST
        );
    }


    // ============================================================
    // SRT VIDEO PICKER
    // ============================================================

    private void openSrtVideoPicker() {

        Intent intent = new Intent(
                Intent.ACTION_OPEN_DOCUMENT
        );

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType("video/*");

        startActivityForResult(
                intent,
                SRT_VIDEO_REQUEST
        );
    }


    // ============================================================
    // SRT FILE PICKER
    // ============================================================

    private void openSrtFilePicker() {

        Intent intent = new Intent(
                Intent.ACTION_OPEN_DOCUMENT
        );

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType("*/*");

        startActivityForResult(
                intent,
                SRT_FILE_REQUEST
        );
    }


    // ============================================================
    // ACTIVITY RESULT
    // ============================================================

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            @Nullable Intent data
    ) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );


        // --------------------------------------------------------
        // WEBVIEW FILE PICKER
        // --------------------------------------------------------

        if (requestCode == WEB_FILE_PICKER_REQUEST) {

            if (webFilePathCallback == null) {
                return;
            }

            Uri[] results = null;

            if (
                    resultCode == Activity.RESULT_OK
                    && data != null
            ) {

                if (data.getClipData() != null) {

                    int count =
                            data.getClipData().getItemCount();

                    results =
                            new Uri[count];

                    for (int i = 0; i < count; i++) {

                        results[i] =
                                data.getClipData()
                                        .getItemAt(i)
                                        .getUri();
                    }

                } else if (data.getData() != null) {

                    results =
                            new Uri[]{
                                    data.getData()
                            };
                }
            }

            webFilePathCallback.onReceiveValue(
                    results
            );

            webFilePathCallback = null;

            return;
        }


        // --------------------------------------------------------
        // NORMAL COMPRESSOR
        // --------------------------------------------------------

        if (
                requestCode == FILE_PICKER_REQUEST
                && resultCode == Activity.RESULT_OK
                && data != null
                && data.getData() != null
        ) {

            compressionInputUri =
                    data.getData();

            try {

                getContentResolver()
                        .takePersistableUriPermission(
                                compressionInputUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );

            } catch (Exception ignored) {
            }


            String name =
                    getFileName(
                            compressionInputUri
                    );

            sendJs(
                    "window.compressionFileSelected && " +
                    "window.compressionFileSelected(" +
                    jsQuote(name) +
                    ");"
            );

            startCompression(
                    compressionInputUri
            );

            return;
        }


        // --------------------------------------------------------
        // SRT VIDEO
        // --------------------------------------------------------

        if (
                requestCode == SRT_VIDEO_REQUEST
                && resultCode == Activity.RESULT_OK
                && data != null
                && data.getData() != null
        ) {

            srtVideoUri =
                    data.getData();

            try {

                getContentResolver()
                        .takePersistableUriPermission(
                                srtVideoUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );

            } catch (Exception ignored) {
            }


            srtVideoName =
                    getFileName(
                            srtVideoUri
                    );

            sendJs(
                    "window.srtVideoSelected && " +
                    "window.srtVideoSelected(" +
                    jsQuote(srtVideoName) +
                    ");"
            );

            return;
        }


        // --------------------------------------------------------
        // SRT FILE
        // --------------------------------------------------------

        if (
                requestCode == SRT_FILE_REQUEST
                && resultCode == Activity.RESULT_OK
                && data != null
                && data.getData() != null
        ) {

            srtFileUri =
                    data.getData();

            try {

                getContentResolver()
                        .takePersistableUriPermission(
                                srtFileUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );

            } catch (Exception ignored) {
            }


            srtFileName =
                    getFileName(
                            srtFileUri
                    );

            sendJs(
                    "window.srtFileSelected && " +
                    "window.srtFileSelected(" +
                    jsQuote(srtFileName) +
                    ");"
            );

            return;
        }


        // --------------------------------------------------------
        // SAVE FILE
        // --------------------------------------------------------

        if (
                requestCode == SAVE_FILE_REQUEST
                && resultCode == Activity.RESULT_OK
                && data != null
                && data.getData() != null
        ) {

            // Kept for compatibility.
            // Current compressor/SRT burner auto-save.
            return;
        }
    }


    // ============================================================
    // NORMAL AUDIO COMPRESSOR
    // ============================================================

    private void startCompression(
            Uri inputUri
    ) {

        if (inputUri == null) {
            return;
        }

        try {

            String outputName =
                    "compressed_audio_" +
                    new SimpleDateFormat(
                            "yyyyMMdd_HHmmss",
                            Locale.US
                    ).format(
                            new Date()
                    ) +
                    ".mp4";


            File outputDir =
                    new File(
                            getExternalFilesDir(
                                    Environment.DIRECTORY_MUSIC
                            ),
                            "Media Toolkit"
                    );

            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }


            File outputFile =
                    new File(
                            outputDir,
                            outputName
                    );

            compressionOutputPath =
                    outputFile.getAbsolutePath();


            // ----------------------------------------------------
            // 64 kbps AAC
            // ----------------------------------------------------

            AudioEncoderSettings audioSettings =
                    new AudioEncoderSettings.Builder()
                            .setBitrate(64_000)
                            .build();


            DefaultEncoderFactory encoderFactory =
                    new DefaultEncoderFactory.Builder(
                            this
                    )
                    .setAudioEncoderSettings(
                            audioSettings
                    )
                    .build();


            compressionTransformer =
                    new Transformer.Builder(this)
                            .setEncoderFactory(
                                    encoderFactory
                            )
                            .setAudioMimeType(
                                    MimeTypes.AUDIO_AAC
                            )
                            .addListener(
                                    new Transformer.Listener() {

                                        @Override
                                        public void onCompleted(
                                                Composition composition,
                                                ExportResult result
                                        ) {

                                            mainHandler.removeCallbacks(
                                                    compressionProgressRunnable
                                            );

                                            long size =
                                                    outputFile.exists()
                                                            ? outputFile.length()
                                                            : 0;


                                            if (size <= 0) {

                                                sendJs(
                                                        "window.compressionFailed && " +
                                                        "window.compressionFailed(" +
                                                        jsQuote(
                                                                "Output file မရပါ"
                                                        ) +
                                                        ");"
                                                );

                                                return;
                                            }


                                            sendJs(
                                                    "window.compressionFinished && " +
                                                    "window.compressionFinished(" +
                                                    jsQuote(
                                                            outputFile.getAbsolutePath()
                                                    ) +
                                                    "," +
                                                    size +
                                                    ");"
                                            );

                                            Toast.makeText(
                                                    MainActivity.this,
                                                    "Compression ပြီးပါပြီ",
                                                    Toast.LENGTH_SHORT
                                            ).show();
                                        }


                                        @Override
                                        public void onError(
                                                Composition composition,
                                                ExportResult result,
                                                ExportException exception
                                        ) {

                                            mainHandler.removeCallbacks(
                                                    compressionProgressRunnable
                                            );

                                            sendJs(
                                                    "window.compressionFailed && " +
                                                    "window.compressionFailed(" +
                                                    jsQuote(
                                                            getErrorMessage(
                                                                    exception
                                                            )
                                                    ) +
                                                    ");"
                                            );
                                        }
                                    }
                            )
                            .build();


            MediaItem mediaItem =
                    MediaItem.fromUri(
                            inputUri
                    );


            EditedMediaItem editedItem =
                    new EditedMediaItem.Builder(
                            mediaItem
                    )
                    .setRemoveVideo(true)
                    .build();


            Composition composition =
                    new Composition.Builder(
                            EditedMediaItemSequence.withAudioFrom(
                                    Collections.singletonList(
                                            editedItem
                                    )
                            )
                    )
                    .build();


            sendJs(
                    "window.compressionStarted && " +
                    "window.compressionStarted();"
            );


            mainHandler.post(
                    compressionProgressRunnable
            );


            compressionTransformer.start(
                    composition,
                    compressionOutputPath
            );


        } catch (Exception e) {

            sendJs(
                    "window.compressionFailed && " +
                    "window.compressionFailed(" +
                    jsQuote(
                            getErrorMessage(e)
                    ) +
                    ");"
            );
        }
    }


    // ============================================================
    // SRT BURN START
    // ============================================================

    private void startSrtBurn(
            String textColor,
            String outlineColor,
            String backgroundColor,
            String effect,
            String position,
            int fontSize,
            int outlineWidth,
            boolean backgroundEnabled,
            boolean bold
    ) {

        if (srtExportRunning) {

            Toast.makeText(
                    this,
                    "SRT export လုပ်နေပါတယ်",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }


        if (srtVideoUri == null) {

            sendJs(
                    "window.srtBurnFailed && " +
                    "window.srtBurnFailed(" +
                    jsQuote(
                            "Video ဖိုင်ကို အရင်ရွေးပါ"
                    ) +
                    ");"
            );

            return;
        }


        if (srtFileUri == null) {

            sendJs(
                    "window.srtBurnFailed && " +
                    "window.srtBurnFailed(" +
                    jsQuote(
                            "SRT ဖိုင်ကို အရင်ရွေးပါ"
                    ) +
                    ");"
            );

            return;
        }


        try {

            // ----------------------------------------------------
            // READ SRT
            // ----------------------------------------------------

            String srtText =
                    readSrtFile(
                            srtFileUri
                    );


            if (
                    srtText == null
                    || srtText.trim().isEmpty()
            ) {

                throw new IOException(
                        "SRT ဖိုင်ထဲမှာ subtitle မရှိပါ"
                );
            }


            List<SrtCue> cues =
                    parseSrt(
                            srtText
                    );


            if (cues.isEmpty()) {

                throw new IOException(
                        "SRT timestamp မတွေ့ပါ"
                );
            }


            // ----------------------------------------------------
            // NORMALIZE SETTINGS
            // ----------------------------------------------------

            textColor =
                    safeColor(
                            textColor,
                            "#FFFFFF"
                    );

            outlineColor =
                    safeColor(
                            outlineColor,
                            "#000000"
                    );

            backgroundColor =
                    safeColor(
                            backgroundColor,
                            "#000000"
                    );


            fontSize =
                    Math.max(
                            16,
                            Math.min(
                                    160,
                                    fontSize
                            )
                    );


            outlineWidth =
                    Math.max(
                            0,
                            Math.min(
                                    30,
                                    outlineWidth
                            )
                    );


            if (effect == null) {
                effect = "none";
            }

            if (position == null) {
                position = "bottom";
            }


            // ----------------------------------------------------
            // OUTPUT FILE
            // ----------------------------------------------------

            String outputName =
                    "subtitle_video_" +
                    new SimpleDateFormat(
                            "yyyyMMdd_HHmmss",
                            Locale.US
                    ).format(
                            new Date()
                    ) +
                    ".mp4";


            File outputDir =
                    new File(
                            getExternalFilesDir(
                                    Environment.DIRECTORY_MOVIES
                            ),
                            "Media Toolkit"
                    );


            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }


            File outputFile =
                    new File(
                            outputDir,
                            outputName
                    );


            srtOutputPath =
                    outputFile.getAbsolutePath();


            // ----------------------------------------------------
            // CANVAS OVERLAY
            // ----------------------------------------------------

            SrtCanvasOverlay overlay =
                    new SrtCanvasOverlay(
                            cues,
                            textColor,
                            outlineColor,
                            backgroundColor,
                            effect,
                            position,
                            fontSize,
                            outlineWidth,
                            backgroundEnabled,
                            bold
                    );


            OverlayEffect overlayEffect =
                    new OverlayEffect(
                            Collections.singletonList(
                                    (TextureOverlay) overlay
                            )
                    );


            // ----------------------------------------------------
            // IMPORTANT:
            // Effects is in transformer package
            // ----------------------------------------------------

            Effects effects =
                    new Effects(
                            Collections.emptyList(),
                            Collections.singletonList(
                                    overlayEffect
                            )
                    );


            MediaItem mediaItem =
                    MediaItem.fromUri(
                            srtVideoUri
                    );


            EditedMediaItem editedMediaItem =
                    new EditedMediaItem.Builder(
                            mediaItem
                    )
                    .setEffects(
                            effects
                    )
                    .build();


            // ----------------------------------------------------
            // COMPOSITION
            // ----------------------------------------------------

            Composition composition =
                    new Composition.Builder(
                            EditedMediaItemSequence
                                    .withAudioAndVideoFrom(
                                            Collections.singletonList(
                                                    editedMediaItem
                                            )
                                    )
                    )
                    .build();


            // ----------------------------------------------------
            // TRANSFORMER
            // ----------------------------------------------------

            srtTransformer =
                    new Transformer.Builder(this)
                            .setVideoMimeType(
                                    MimeTypes.VIDEO_H264
                            )
                            .setAudioMimeType(
                                    MimeTypes.AUDIO_AAC
                            )
                            .addListener(
                                    new Transformer.Listener() {

                                        @Override
                                        public void onCompleted(
                                                Composition composition,
                                                ExportResult result
                                        ) {

                                            srtExportRunning = false;

                                            mainHandler.removeCallbacks(
                                                    srtProgressRunnable
                                            );


                                            try {

                                                saveSrtOutputToGallery(
                                                        outputFile,
                                                        outputName
                                                );


                                                sendJs(
                                                        "window.srtBurnFinished && " +
                                                        "window.srtBurnFinished(" +
                                                        jsQuote(
                                                                outputName
                                                        ) +
                                                        ");"
                                                );


                                                Toast.makeText(
                                                        MainActivity.this,
                                                        "Subtitle video သိမ်းပြီးပါပြီ",
                                                        Toast.LENGTH_LONG
                                                ).show();


                                            } catch (Exception e) {

                                                sendJs(
                                                        "window.srtBurnFailed && " +
                                                        "window.srtBurnFailed(" +
                                                        jsQuote(
                                                                getErrorMessage(e)
                                                        ) +
                                                        ");"
                                                );
                                            }
                                        }


                                        @Override
                                        public void onError(
                                                Composition composition,
                                                ExportResult result,
                                                ExportException exception
                                        ) {

                                            srtExportRunning = false;

                                            mainHandler.removeCallbacks(
                                                    srtProgressRunnable
                                            );


                                            sendJs(
                                                    "window.srtBurnFailed && " +
                                                    "window.srtBurnFailed(" +
                                                    jsQuote(
                                                            getErrorMessage(
                                                                    exception
                                                            )
                                                    ) +
                                                    ");"
                                            );
                                        }
                                    }
                            )
                            .build();


            srtExportRunning = true;


            sendJs(
                    "window.srtBurnProgress && " +
                    "window.srtBurnProgress(0,'Preparing...');"
            );


            mainHandler.post(
                    srtProgressRunnable
            );


            srtTransformer.start(
                    composition,
                    srtOutputPath
            );


        } catch (Exception e) {

            srtExportRunning = false;

            mainHandler.removeCallbacks(
                    srtProgressRunnable
            );


            sendJs(
                    "window.srtBurnFailed && " +
                    "window.srtBurnFailed(" +
                    jsQuote(
                            getErrorMessage(e)
                    ) +
                    ");"
            );
        }
    }


    // ============================================================
    // SRT CANVAS OVERLAY
    // ============================================================

    private static class SrtCanvasOverlay
            extends CanvasOverlay {

        private final List<SrtCue> cues;

        private final int textColor;
        private final int outlineColor;
        private final int backgroundColor;

        private final String effect;
        private final String position;

        private final int fontSize;
        private final int outlineWidth;

        private final boolean backgroundEnabled;
        private final boolean bold;


        SrtCanvasOverlay(
                List<SrtCue> cues,
                String textColor,
                String outlineColor,
                String backgroundColor,
                String effect,
                String position,
                int fontSize,
                int outlineWidth,
                boolean backgroundEnabled,
                boolean bold
        ) {

            // true = use input video frame size
            super(true);


            this.cues =
                    cues != null
                            ? cues
                            : Collections.emptyList();


            this.textColor =
                    Color.parseColor(
                            safeColor(
                                    textColor,
                                    "#FFFFFF"
                            )
                    );


            this.outlineColor =
                    Color.parseColor(
                            safeColor(
                                    outlineColor,
                                    "#000000"
                            )
                    );


            this.backgroundColor =
                    Color.parseColor(
                            safeColor(
                                    backgroundColor,
                                    "#000000"
                            )
                    );


            this.effect =
                    effect != null
                            ? effect
                            : "none";


            this.position =
                    position != null
                            ? position
                            : "bottom";


            this.fontSize =
                    Math.max(
                            16,
                            fontSize
                    );


            this.outlineWidth =
                    Math.max(
                            0,
                            outlineWidth
                    );


            this.backgroundEnabled =
                    backgroundEnabled;


            this.bold =
                    bold;
        }


        @Override
        public void onDraw(
                Canvas canvas,
                long presentationTimeUs
        ) {

            if (
                    canvas == null
                    || cues.isEmpty()
            ) {
                return;
            }


            long timeMs =
                    presentationTimeUs / 1000L;


            SrtCue activeCue =
                    findCue(
                            timeMs
                    );


            if (activeCue == null) {
                return;
            }


            String text =
                    activeCue.text;


            if (
                    text == null
                    || text.trim().isEmpty()
            ) {
                return;
            }


            int width =
                    canvas.getWidth();

            int height =
                    canvas.getHeight();


            if (
                    width <= 0
                    || height <= 0
            ) {
                return;
            }


            // ----------------------------------------------------
            // EFFECT
            // ----------------------------------------------------

            float alpha =
                    calculateAlpha(
                            activeCue,
                            timeMs
                    );


            float scale =
                    calculateScale(
                            activeCue,
                            timeMs
                    );


            float translateX =
                    calculateTranslateX(
                            activeCue,
                            timeMs,
                            width
                    );


            canvas.save();


            canvas.translate(
                    translateX,
                    0
            );


            if (scale != 1f) {

                canvas.scale(
                        scale,
                        scale,
                        width / 2f,
                        height / 2f
                );
            }


            // ----------------------------------------------------
            // TEXT PAINT
            // ----------------------------------------------------

            TextPaintEx paint =
                    new TextPaintEx();


            paint.setAntiAlias(true);

            paint.setSubpixelText(true);

            paint.setDither(true);

            paint.setColor(
                    textColor
            );

            paint.setTextSize(
                    fontSize
            );


            paint.setTypeface(
                    Typeface.create(
                            "sans-serif",
                            bold
                                    ? Typeface.BOLD
                                    : Typeface.NORMAL
                    )
            );


            paint.setTextAlign(
                    Paint.Align.CENTER
            );


            paint.setAlpha(
                    Math.max(
                            0,
                            Math.min(
                                    255,
                                    (int)(alpha * 255f)
                            )
                    )
            );


            // ----------------------------------------------------
            // WORD WRAP
            // ----------------------------------------------------

            float maxTextWidth =
                    width * 0.90f;


            List<String> lines =
                    wrapText(
                            text,
                            paint,
                            maxTextWidth
                    );


            float lineHeight =
                    paint.getTextSize() * 1.20f;


            float totalHeight =
                    lines.size() *
                    lineHeight;


            float x =
                    width / 2f;


            float y;


            if ("top".equalsIgnoreCase(position)) {

                y =
                        height * 0.12f;

            } else if (
                    "center".equalsIgnoreCase(position)
            ) {

                y =
                        (height - totalHeight) / 2f;

            } else {

                // bottom
                y =
                        height
                        - totalHeight
                        - height * 0.10f;
            }


            // ----------------------------------------------------
            // BACKGROUND
            // ----------------------------------------------------

            if (backgroundEnabled) {

                Paint bg =
                        new Paint(
                                Paint.ANTI_ALIAS_FLAG
                        );

                bg.setColor(
                        backgroundColor
                );

                bg.setAlpha(
                        Math.max(
                                0,
                                Math.min(
                                        255,
                                        (int)(
                                                190f *
                                                alpha
                                        )
                                )
                        )
                );


                float paddingX =
                        fontSize * 0.55f;

                float paddingY =
                        fontSize * 0.30f;


                float maxWidth =
                        getMaxLineWidth(
                                lines,
                                paint
                        );


                float left =
                        x
                        - maxWidth / 2f
                        - paddingX;


                float right =
                        x
                        + maxWidth / 2f
                        + paddingX;


                float top =
                        y
                        - paddingY;


                float bottom =
                        y
                        + totalHeight
                        + paddingY;


                canvas.drawRoundRect(
                        left,
                        top,
                        right,
                        bottom,
                        fontSize * 0.25f,
                        fontSize * 0.25f,
                        bg
                );
            }


            // ----------------------------------------------------
            // OUTLINE
            // ----------------------------------------------------

            if (outlineWidth > 0) {

                TextPaintEx outlinePaint =
                        new TextPaintEx();


                outlinePaint.setAntiAlias(
                        true
                );

                outlinePaint.setSubpixelText(
                        true
                );

                outlinePaint.setTextSize(
                        fontSize
                );

                outlinePaint.setTypeface(
                        Typeface.create(
                                "sans-serif",
                                bold
                                        ? Typeface.BOLD
                                        : Typeface.NORMAL
                        )
                );

                outlinePaint.setTextAlign(
                        Paint.Align.CENTER
                );

                outlinePaint.setStyle(
                        Paint.Style.STROKE
                );

                outlinePaint.setStrokeWidth(
                        outlineWidth * 2f
                );

                outlinePaint.setStrokeJoin(
                        Paint.Join.ROUND
                );

                outlinePaint.setColor(
                        outlineColor
                );

                outlinePaint.setAlpha(
                        Math.max(
                                0,
                                Math.min(
                                        255,
                                        (int)(alpha * 255f)
                                )
                        )
                );


                drawLines(
                        canvas,
                        lines,
                        outlinePaint,
                        x,
                        y,
                        lineHeight
                );
            }


            // ----------------------------------------------------
            // FILL
            // ----------------------------------------------------

            paint.setStyle(
                    Paint.Style.FILL
            );


            drawLines(
                    canvas,
                    lines,
                    paint,
                    x,
                    y,
                    lineHeight
            );


            canvas.restore();
        }


        // --------------------------------------------------------
        // DRAW LINES
        // --------------------------------------------------------

        private void drawLines(
                Canvas canvas,
                List<String> lines,
                Paint paint,
                float x,
                float y,
                float lineHeight
        ) {

            for (int i = 0; i < lines.size(); i++) {

                String line =
                        lines.get(i);


                canvas.drawText(
                        line,
                        x,
                        y + (i + 1) * lineHeight
                                - (lineHeight - paint.getTextSize()) / 2f,
                        paint
                );
            }
        }


        // --------------------------------------------------------
        // FIND ACTIVE CUE
        // --------------------------------------------------------

        private SrtCue findCue(
                long timeMs
        ) {

            for (SrtCue cue : cues) {

                if (
                        timeMs >= cue.startMs
                        && timeMs <= cue.endMs
                ) {

                    return cue;
                }
            }

            return null;
        }


        // --------------------------------------------------------
        // FADE EFFECT
        // --------------------------------------------------------

        private float calculateAlpha(
                SrtCue cue,
                long timeMs
        ) {

            if (
                    !"fade".equalsIgnoreCase(
                            effect
                    )
            ) {
                return 1f;
            }


            long duration =
                    cue.endMs -
                    cue.startMs;


            if (duration <= 0) {
                return 1f;
            }


            long elapsed =
                    timeMs -
                    cue.startMs;


            long remaining =
                    cue.endMs -
                    timeMs;


            long fade =
                    Math.min(
                            350,
                            duration / 3
                    );


            if (elapsed < fade) {

                return Math.max(
                        0f,
                        Math.min(
                                1f,
                                elapsed /
                                        (float)fade
                        )
                );
            }


            if (remaining < fade) {

                return Math.max(
                        0f,
                        Math.min(
                                1f,
                                remaining /
                                        (float)fade
                        )
                );
            }


            return 1f;
        }


        // --------------------------------------------------------
        // POP EFFECT
        // --------------------------------------------------------

        private float calculateScale(
                SrtCue cue,
                long timeMs
        ) {

            if (
                    !"pop".equalsIgnoreCase(
                            effect
                    )
            ) {
                return 1f;
            }


            long elapsed =
                    timeMs -
                    cue.startMs;


            float p =
                    Math.max(
                            0f,
                            Math.min(
                                    1f,
                                    elapsed / 220f
                            )
                    );


            // Ease-out
            float eased =
                    1f -
                    (1f - p) *
                    (1f - p);


            return 0.75f +
                    eased * 0.25f;
        }


        // --------------------------------------------------------
        // SLIDE EFFECT
        // --------------------------------------------------------

        private float calculateTranslateX(
                SrtCue cue,
                long timeMs,
                int width
        ) {

            if (
                    !"slide".equalsIgnoreCase(
                            effect
                    )
            ) {
                return 0f;
            }


            long elapsed =
                    timeMs -
                    cue.startMs;


            float p =
                    Math.max(
                            0f,
                            Math.min(
                                    1f,
                                    elapsed / 350f
                            )
                    );


            float eased =
                    1f -
                    (1f - p) *
                    (1f - p);


            return -width * 0.30f *
                    (1f - eased);
        }


        // --------------------------------------------------------
        // WRAP TEXT
        // --------------------------------------------------------

        private List<String> wrapText(
                String text,
                Paint paint,
                float maxWidth
        ) {

            List<String> result =
                    new ArrayList<>();


            String[] paragraphs =
                    text.replace(
                            "\r",
                            ""
                    ).split(
                            "\n"
                    );


            for (
                    String paragraph :
                    paragraphs
            ) {

                paragraph =
                        paragraph.trim();


                if (paragraph.isEmpty()) {

                    result.add("");

                    continue;
                }


                String[] words =
                        paragraph.split(
                                "\\s+"
                        );


                String current =
                        "";


                for (String word : words) {

                    String candidate =
                            current.isEmpty()
                                    ? word
                                    : current
                                    + " "
                                    + word;


                    if (
                            paint.measureText(
                                    candidate
                            ) <= maxWidth
                    ) {

                        current =
                                candidate;

                    } else {

                        if (!current.isEmpty()) {

                            result.add(
                                    current
                            );
                        }

                        current =
                                word;
                    }
                }


                if (!current.isEmpty()) {

                    result.add(
                            current
                    );
                }
            }


            if (result.isEmpty()) {
                result.add(text);
            }


            return result;
        }


        // --------------------------------------------------------
        // MAX LINE WIDTH
        // --------------------------------------------------------

        private float getMaxLineWidth(
                List<String> lines,
                Paint paint
        ) {

            float max = 0f;


            for (String line : lines) {

                max =
                        Math.max(
                                max,
                                paint.measureText(
                                        line
                                )
                        );
            }


            return max;
        }
    }


    // ============================================================
    // TEXT PAINT
    // ============================================================

    private static class TextPaintEx
            extends TextPaint {

        TextPaintEx() {
            super(Paint.ANTI_ALIAS_FLAG);
        }
    }


    // ============================================================
    // SRT CUE
    // ============================================================

    private static class SrtCue {

        long startMs;
        long endMs;
        String text;


        SrtCue(
                long startMs,
                long endMs,
                String text
        ) {

            this.startMs =
                    startMs;

            this.endMs =
                    endMs;

            this.text =
                    text;
        }
    }


    // ============================================================
    // SRT READER
    // ============================================================

    private String readSrtFile(
            Uri uri
    ) throws IOException {

        ContentResolver resolver =
                getContentResolver();


        byte[] bytes;


        try (
                InputStream input =
                        resolver.openInputStream(uri)
        ) {

            if (input == null) {

                throw new IOException(
                        "SRT file မဖွင့်နိုင်ပါ"
                );
            }


            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();


            byte[] buffer =
                    new byte[8192];


            int count;


            while (
                    (count =
                            input.read(buffer))
                            != -1
            ) {

                output.write(
                        buffer,
                        0,
                        count
                );
            }


            bytes =
                    output.toByteArray();
        }


        if (bytes.length == 0) {
            return "";
        }


        // --------------------------------------------------------
        // UTF-8 BOM
        // --------------------------------------------------------

        if (
                bytes.length >= 3
                && (
                        bytes[0] & 0xFF
                ) == 0xEF
                && (
                        bytes[1] & 0xFF
                ) == 0xBB
                && (
                        bytes[2] & 0xFF
                ) == 0xBF
        ) {

            return new String(
                    bytes,
                    3,
                    bytes.length - 3,
                    Charset.forName("UTF-8")
            );
        }


        // --------------------------------------------------------
        // UTF-16 LE
        // --------------------------------------------------------

        if (
                bytes.length >= 2
                && (
                        bytes[0] & 0xFF
                ) == 0xFF
                && (
                        bytes[1] & 0xFF
                ) == 0xFE
        ) {

            return new String(
                    bytes,
                    2,
                    bytes.length - 2,
                    Charset.forName("UTF-16LE")
            );
        }


        // --------------------------------------------------------
        // UTF-16 BE
        // --------------------------------------------------------

        if (
                bytes.length >= 2
                && (
                        bytes[0] & 0xFF
                ) == 0xFE
                && (
                        bytes[1] & 0xFF
                ) == 0xFF
        ) {

            return new String(
                    bytes,
                    2,
                    bytes.length - 2,
                    Charset.forName("UTF-16BE")
            );
        }


        // --------------------------------------------------------
        // Detect UTF-16 without BOM
        // --------------------------------------------------------

        int zeroEven = 0;
        int zeroOdd = 0;

        int sample =
                Math.min(
                        bytes.length,
                        2000
                );


        for (int i = 0; i < sample; i++) {

            if (bytes[i] == 0) {

                if (i % 2 == 0) {
                    zeroEven++;
                } else {
                    zeroOdd++;
                }
            }
        }


        if (zeroOdd > 20 && zeroOdd > zeroEven * 2) {

            return new String(
                    bytes,
                    Charset.forName("UTF-16LE")
            );
        }


        if (zeroEven > 20 && zeroEven > zeroOdd * 2) {

            return new String(
                    bytes,
                    Charset.forName("UTF-16BE")
            );
        }


        // --------------------------------------------------------
        // Default UTF-8
        // --------------------------------------------------------

        return new String(
                bytes,
                Charset.forName("UTF-8")
        );
    }


    // ============================================================
    // SRT PARSER
    // ============================================================

    private List<SrtCue> parseSrt(
            String srt
    ) {

        List<SrtCue> result =
                new ArrayList<>();


        if (srt == null) {
            return result;
        }


        srt =
                srt.replace(
                        "\uFEFF",
                        ""
                );


        srt =
                srt.replace(
                        "\r\n",
                        "\n"
                )
                .replace(
                        "\r",
                        "\n"
                );


        String[] blocks =
                srt.split(
                        "\\n\\s*\\n"
                );


        Pattern timestampPattern =
                Pattern.compile(
                        "(\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3})\\s*-->\\s*(\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3})"
                );


        for (String block : blocks) {

            block =
                    block.trim();


            if (block.isEmpty()) {
                continue;
            }


            String[] lines =
                    block.split(
                            "\\n"
                    );


            if (lines.length < 2) {
                continue;
            }


            int timestampLine =
                    -1;

            Matcher matcher =
                    null;


            for (
                    int i = 0;
                    i < lines.length;
                    i++
            ) {

                Matcher m =
                        timestampPattern.matcher(
                                lines[i]
                        );


                if (m.find()) {

                    timestampLine =
                            i;

                    matcher =
                            m;

                    break;
                }
            }


            if (
                    timestampLine < 0
                    || matcher == null
            ) {
                continue;
            }


            long start =
                    parseSrtTime(
                            matcher.group(1)
                    );


            long end =
                    parseSrtTime(
                            matcher.group(2)
                    );


            StringBuilder text =
                    new StringBuilder();


            for (
                    int i =
                            timestampLine + 1;
                    i < lines.length;
                    i++
            ) {

                String line =
                        lines[i].trim();


                // Remove SRT positioning / styling tags
                line =
                        line.replaceAll(
                                "<[^>]*>",
                                ""
                        );


                if (!line.isEmpty()) {

                    if (text.length() > 0) {
                        text.append("\n");
                    }

                    text.append(line);
                }
            }


            if (
                    end > start
                    && text.length() > 0
            ) {

                result.add(
                        new SrtCue(
                                start,
                                end,
                                text.toString()
                        )
                );
            }
        }


        return result;
    }


    // ============================================================
    // PARSE SRT TIME
    // ============================================================

    private long parseSrtTime(
            String time
    ) {

        try {

            time =
                    time.replace(
                            ',',
                            '.'
                    );


            String[] parts =
                    time.split(
                            ":"
                    );


            if (parts.length != 3) {
                return 0;
            }


            long hours =
                    Long.parseLong(
                            parts[0]
                    );


            long minutes =
                    Long.parseLong(
                            parts[1]
                    );


            String[] sec =
                    parts[2].split(
                            "\\."
                    );


            long seconds =
                    Long.parseLong(
                            sec[0]
                    );


            long millis = 0;


            if (sec.length > 1) {

                String ms =
                        sec[1];


                if (ms.length() == 1) {
                    ms += "00";
                } else if (ms.length() == 2) {
                    ms += "0";
                } else if (ms.length() > 3) {
                    ms =
                            ms.substring(
                                    0,
                                    3
                            );
                }


                millis =
                        Long.parseLong(
                                ms
                        );
            }


            return
                    hours * 3600000L
                    +
                    minutes * 60000L
                    +
                    seconds * 1000L
                    +
                    millis;

        } catch (Exception e) {

            return 0;
        }
    }


    // ============================================================
    // SAVE SRT OUTPUT
    // ============================================================

    private void saveSrtOutputToGallery(
            File sourceFile,
            String displayName
    ) throws IOException {

        if (
                sourceFile == null
                || !sourceFile.exists()
        ) {

            throw new IOException(
                    "Output video မတွေ့ပါ"
            );
        }


        // --------------------------------------------------------
        // Android 10+
        // --------------------------------------------------------

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            ContentValues values =
                    new ContentValues();


            values.put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    displayName
            );


            values.put(
                    MediaStore.Video.Media.MIME_TYPE,
                    "video/mp4"
            );


            values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES
                            + "/Media Toolkit"
            );


            values.put(
                    MediaStore.Video.Media.IS_PENDING,
                    1
            );


            Uri collection =
                    MediaStore.Video.Media.getContentUri(
                            MediaStore.VOLUME_EXTERNAL_PRIMARY
                    );


            Uri outputUri =
                    getContentResolver().insert(
                            collection,
                            values
                    );


            if (outputUri == null) {

                throw new IOException(
                        "Gallery file create မလုပ်နိုင်ပါ"
                );
            }


            try {

                try (
                        InputStream input =
                                new FileInputStream(
                                        sourceFile
                                );

                        OutputStream output =
                                getContentResolver()
                                        .openOutputStream(
                                                outputUri
                                        )
                ) {

                    if (output == null) {

                        throw new IOException(
                                "Output stream မဖွင့်နိုင်ပါ"
                        );
                    }


                    copyStream(
                            input,
                            output
                    );
                }


                ContentValues done =
                        new ContentValues();


                done.put(
                        MediaStore.Video.Media.IS_PENDING,
                        0
                );


                getContentResolver().update(
                        outputUri,
                        done,
                        null,
                        null
                );


            } catch (Exception e) {

                getContentResolver().delete(
                        outputUri,
                        null,
                        null
                );

                throw e;
            }


        } else {

            // ----------------------------------------------------
            // Android 9 and below
            // ----------------------------------------------------

            File movies =
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_MOVIES
                    );


            File folder =
                    new File(
                            movies,
                            "Media Toolkit"
                    );


            if (!folder.exists()) {
                folder.mkdirs();
            }


            File destination =
                    new File(
                            folder,
                            displayName
                    );


            try (
                    InputStream input =
                            new FileInputStream(
                                    sourceFile
                            );

                    OutputStream output =
                            new FileOutputStream(
                                    destination
                            )
            ) {

                copyStream(
                        input,
                        output
                );
            }
        }


        // --------------------------------------------------------
        // Delete temporary file
        // --------------------------------------------------------

        try {
            sourceFile.delete();
        } catch (Exception ignored) {
        }
    }


    // ============================================================
    // COPY STREAM
    // ============================================================

    private static void copyStream(
            InputStream input,
            OutputStream output
    ) throws IOException {

        byte[] buffer =
                new byte[1024 * 64];


        int length;


        while (
                (length =
                        input.read(buffer))
                        != -1
        ) {

            output.write(
                    buffer,
                    0,
                    length
            );
        }


        output.flush();
    }


    // ============================================================
    // FILE NAME
    // ============================================================

    private String getFileName(
            Uri uri
    ) {

        if (uri == null) {
            return "Unknown";
        }


        String result =
                null;


        if (
                "content".equals(
                        uri.getScheme()
                )
        ) {

            Cursor cursor =
                    null;


            try {

                cursor =
                        getContentResolver()
                                .query(
                                        uri,
                                        new String[]{
                                                OpenableColumns.DISPLAY_NAME
                                        },
                                        null,
                                        null,
                                        null
                                );


                if (
                        cursor != null
                        && cursor.moveToFirst()
                ) {

                    int index =
                            cursor.getColumnIndex(
                                    OpenableColumns.DISPLAY_NAME
                            );


                    if (index >= 0) {

                        result =
                                cursor.getString(
                                        index
                                );
                    }
                }

            } catch (Exception ignored) {

            } finally {

                if (cursor != null) {
                    cursor.close();
                }
            }
        }


        if (
                result == null
                || result.trim().isEmpty()
        ) {

            result =
                    uri.getLastPathSegment();
        }


        if (
                result == null
                || result.trim().isEmpty()
        ) {

            result =
                    "Unknown";
        }


        return result;
    }


    // ============================================================
    // SAFE COLOR
    // ============================================================

    private static String safeColor(
            String color,
            String fallback
    ) {

        if (
                color == null
                || color.trim().isEmpty()
        ) {
            return fallback;
        }


        try {

            Color.parseColor(
                    color
            );

            return color;

        } catch (Exception e) {

            return fallback;
        }
    }


    // ============================================================
    // JS ESCAPE
    // ============================================================

    private static String jsQuote(
            String value
    ) {

        if (value == null) {
            value = "";
        }


        return "'"
                + value
                .replace(
                        "\\",
                        "\\\\"
                )
                .replace(
                        "'",
                        "\\'"
                )
                .replace(
                        "\r",
                        "\\r"
                )
                .replace(
                        "\n",
                        "\\n"
                )
                .replace(
                        "</",
                        "<\\/"
                )
                + "'";
    }


    // ============================================================
    // SEND JS
    // ============================================================

    private void sendJs(
            String javascript
    ) {

        if (webView == null) {
            return;
        }


        runOnUiThread(
                () -> {

                    try {

                        webView.evaluateJavascript(
                                javascript,
                                null
                        );

                    } catch (Exception ignored) {
                    }
                }
        );
    }


    // ============================================================
    // ERROR MESSAGE
    // ============================================================

    private String getErrorMessage(
            Throwable throwable
    ) {

        if (throwable == null) {
            return "Unknown error";
        }


        String message =
                throwable.getMessage();


        if (
                message == null
                || message.trim().isEmpty()
        ) {

            message =
                    throwable.toString();
        }


        return message;
    }


    // ============================================================
    // ON DESTROY
    // ============================================================

    @Override
    protected void onDestroy() {

        try {

            mainHandler.removeCallbacks(
                    compressionProgressRunnable
            );

            mainHandler.removeCallbacks(
                    srtProgressRunnable
            );

        } catch (Exception ignored) {
        }


        try {

            if (webView != null) {

                webView.stopLoading();

                webView.destroy();

                webView = null;
            }

        } catch (Exception ignored) {
        }


        super.onDestroy();
    }
            }
