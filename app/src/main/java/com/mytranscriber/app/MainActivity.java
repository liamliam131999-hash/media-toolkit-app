package com.mytranscriber.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
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
import android.text.TextPaint;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;

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

public class MainActivity extends Activity {

    // ============================================================
    // REQUEST CODES
    // ============================================================

    private static final int FILE_PICKER_REQUEST = 1001;
    private static final int WEB_FILE_PICKER_REQUEST = 2001;

    private static final int SRT_VIDEO_REQUEST = 4001;
    private static final int SRT_FILE_REQUEST = 4002;

    // ============================================================
    // WEBVIEW
    // ============================================================

    private WebView webView;

    private ValueCallback<Uri[]> webFileCallback;

    // ============================================================
    // NORMAL COMPRESSOR
    // ============================================================

    private Uri lastSelectedUri;

    private Transformer compressionTransformer;

    private Thread compressionProgressThread;

    private boolean compressionRunning = false;

    // ============================================================
    // SRT BURNER
    // ============================================================

    private Uri selectedSrtVideoUri;
    private Uri selectedSrtFileUri;

    private Transformer srtTransformer;

    private Thread srtProgressThread;

    private boolean srtBurning = false;

    private File currentSrtTempOutput;

    // ============================================================
    // SRT DATA
    // ============================================================

    private final List<SrtCue> srtCues = new ArrayList<>();

    // ============================================================
    // ACTIVITY
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupWebView();

        setContentView(webView);
    }

    // ============================================================
    // WEBVIEW SETUP
    // ============================================================

    private void setupWebView() {

        webView = new WebView(this);

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            );
        }

        webView.setBackgroundColor(Color.TRANSPARENT);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    String url
            ) {
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {

                if (webFileCallback != null) {
                    webFileCallback.onReceiveValue(null);
                }

                webFileCallback = filePathCallback;

                Intent intent;

                try {
                    intent = fileChooserParams.createIntent();
                } catch (Exception e) {

                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);

                    intent.addCategory(
                            Intent.CATEGORY_OPENABLE
                    );

                    intent.setType("*/*");
                }

                try {

                    startActivityForResult(
                            intent,
                            WEB_FILE_PICKER_REQUEST
                    );

                } catch (Exception e) {

                    if (webFileCallback != null) {
                        webFileCallback.onReceiveValue(null);
                        webFileCallback = null;
                    }

                    Toast.makeText(
                            MainActivity.this,
                            "File picker မဖွင့်နိုင်ပါ",
                            Toast.LENGTH_SHORT
                    ).show();
                }

                return true;
            }
        });

        webView.addJavascriptInterface(
                new AndroidBridge(),
                "AndroidBridge"
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
        // NORMAL FILE PICKER
        // --------------------------------------------------------

        @JavascriptInterface
        public void pickFile() {

            runOnUiThread(() -> {

                Intent intent =
                        new Intent(Intent.ACTION_OPEN_DOCUMENT);

                intent.addCategory(
                        Intent.CATEGORY_OPENABLE
                );

                intent.setType("*/*");

                try {

                    startActivityForResult(
                            intent,
                            FILE_PICKER_REQUEST
                    );

                } catch (Exception e) {

                    showToast(
                            "File picker မဖွင့်နိုင်ပါ"
                    );
                }
            });
        }

        // --------------------------------------------------------
        // SRT VIDEO PICKER
        // --------------------------------------------------------

        @JavascriptInterface
        public void pickSrtVideo() {

            runOnUiThread(() -> {

                Intent intent =
                        new Intent(Intent.ACTION_OPEN_DOCUMENT);

                intent.addCategory(
                        Intent.CATEGORY_OPENABLE
                );

                intent.setType("video/*");

                try {

                    startActivityForResult(
                            intent,
                            SRT_VIDEO_REQUEST
                    );

                } catch (Exception e) {

                    showToast(
                            "Video picker မဖွင့်နိုင်ပါ"
                    );
                }
            });
        }

        // --------------------------------------------------------
        // SRT FILE PICKER
        // --------------------------------------------------------

        @JavascriptInterface
        public void pickSrtFile() {

            runOnUiThread(() -> {

                Intent intent =
                        new Intent(Intent.ACTION_OPEN_DOCUMENT);

                intent.addCategory(
                        Intent.CATEGORY_OPENABLE
                );

                intent.setType("*/*");

                try {

                    startActivityForResult(
                            intent,
                            SRT_FILE_REQUEST
                    );

                } catch (Exception e) {

                    showToast(
                            "SRT picker မဖွင့်နိုင်ပါ"
                    );
                }
            });
        }

        // --------------------------------------------------------
        // START SRT BURN
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

            runOnUiThread(() -> {

                if (srtBurning) {

                    showToast(
                            "Subtitle burn လုပ်နေဆဲပါ"
                    );

                    return;
                }

                if (selectedSrtVideoUri == null) {

                    callJs(
                            "srtBurnFailed",
                            "'Video ဖိုင်ကို အရင်ရွေးပါ'"
                    );

                    return;
                }

                if (selectedSrtFileUri == null) {

                    callJs(
                            "srtBurnFailed",
                            "'SRT ဖိုင်ကို အရင်ရွေးပါ'"
                    );

                    return;
                }

                try {

                    String srtText =
                            readSrtFile(
                                    selectedSrtFileUri
                            );

                    srtCues.clear();

                    srtCues.addAll(
                            parseSrt(srtText)
                    );

                    if (srtCues.isEmpty()) {

                        callJs(
                                "srtBurnFailed",
                                "'SRT subtitle မတွေ့ပါ'"
                        );

                        return;
                    }

                    startSrtBurn(
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

                } catch (Exception e) {

                    callJs(
                            "srtBurnFailed",
                            jsString(
                                    e.getMessage()
                            )
                    );
                }
            });
        }

        // --------------------------------------------------------
        // CANCEL SRT
        // --------------------------------------------------------

        @JavascriptInterface
        public void cancelSrtBurn() {

            runOnUiThread(() -> {

                try {

                    if (srtTransformer != null) {
                        srtTransformer.cancel();
                    }

                } catch (Exception ignored) {
                }

                srtBurning = false;

                callJs(
                        "srtBurnFailed",
                        "'Subtitle burn cancelled'"
                );
            });
        }
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
        // WEBVIEW FILE CHOOSER
        // --------------------------------------------------------

        if (requestCode == WEB_FILE_PICKER_REQUEST) {

            if (webFileCallback == null) {
                return;
            }

            Uri[] results = null;

            if (
                    resultCode == RESULT_OK
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

            webFileCallback.onReceiveValue(
                    results
            );

            webFileCallback = null;

            return;
        }

        // --------------------------------------------------------
        // NORMAL COMPRESSOR
        // --------------------------------------------------------

        if (requestCode == FILE_PICKER_REQUEST) {

            if (
                    resultCode == RESULT_OK
                    && data != null
                    && data.getData() != null
            ) {

                Uri uri = data.getData();

                lastSelectedUri = uri;

                takePersistablePermission(
                        uri,
                        data
                );

                String name =
                        getFileName(uri);

                callJs(
                        "compressionFileSelected",
                        jsString(name)
                );
            }

            return;
        }

        // --------------------------------------------------------
        // SRT VIDEO
        // --------------------------------------------------------

        if (requestCode == SRT_VIDEO_REQUEST) {

            if (
                    resultCode == RESULT_OK
                    && data != null
                    && data.getData() != null
            ) {

                Uri uri =
                        data.getData();

                selectedSrtVideoUri =
                        uri;

                takePersistablePermission(
                        uri,
                        data
                );

                String name =
                        getFileName(uri);

                callJs(
                        "srtVideoSelected",
                        jsString(name)
                );
            }

            return;
        }

        // --------------------------------------------------------
        // SRT FILE
        // --------------------------------------------------------

        if (requestCode == SRT_FILE_REQUEST) {

            if (
                    resultCode == RESULT_OK
                    && data != null
                    && data.getData() != null
            ) {

                Uri uri =
                        data.getData();

                selectedSrtFileUri =
                        uri;

                takePersistablePermission(
                        uri,
                        data
                );

                String name =
                        getFileName(uri);

                callJs(
                        "srtFileSelected",
                        jsString(name)
                );
            }
        }
    }

    // ============================================================
    // COMPRESSOR
    // ============================================================

    private void startCompression() {

        if (lastSelectedUri == null) {

            callJs(
                    "compressionFailed",
                    "'File မရွေးရသေးပါ'"
            );

            return;
        }

        if (compressionRunning) {

            callJs(
                    "compressionFailed",
                    "'Compression လုပ်နေဆဲပါ'"
            );

            return;
        }

        compressAudio(64);
    }

    private void compressAudio(
            final int bitrateKbps
    ) {

        if (lastSelectedUri == null) {

            callJs(
                    "compressionFailed",
                    "'File မရွေးရသေးပါ'"
            );

            return;
        }

        compressionRunning = true;

        callJs(
                "compressionStarted"
        );

        try {

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

            String timestamp =
                    new SimpleDateFormat(
                            "yyyyMMdd_HHmmss",
                            Locale.US
                    ).format(
                            new Date()
                    );

            File outputFile =
                    new File(
                            outputDir,
                            "compressed_audio_"
                                    + timestamp
                                    + ".mp4"
                    );

            MediaItem mediaItem =
                    MediaItem.fromUri(
                            lastSelectedUri
                    );

            EditedMediaItem editedItem =
                    new EditedMediaItem.Builder(
                            mediaItem
                    )
                            .setRemoveVideo(true)
                            .build();

            AudioEncoderSettings audioSettings =
                    new AudioEncoderSettings.Builder()
                            .setBitrate(
                                    bitrateKbps * 1000
                            )
                            .build();

            // IMPORTANT:
            // Media3 API uses this method.
            DefaultEncoderFactory encoderFactory =
                    new DefaultEncoderFactory.Builder(
                            this
                    )
                            .setRequestedAudioEncoderSettings(
                                    audioSettings
                            )
                            .build();

            Composition composition =
                    new Composition.Builder(
                            EditedMediaItemSequence
                                    .withAudioFrom(
                                            Collections.singletonList(
                                                    editedItem
                                            )
                                    )
                    )
                            .build();

            compressionTransformer =
                    new Transformer.Builder(
                            this
                    )
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

                                            compressionRunning =
                                                    false;

                                            callJs(
                                                    "compressionProgress",
                                                    "100"
                                            );

                                            long size =
                                                    outputFile.exists()
                                                            ? outputFile.length()
                                                            : 0;

                                            callJs(
                                                    "compressionFinished",
                                                    jsString(
                                                            outputFile.getAbsolutePath()
                                                    ),
                                                    String.valueOf(
                                                            size
                                                    )
                                            );
                                        }

                                        @Override
                                        public void onError(
                                                Composition composition,
                                                ExportResult result,
                                                ExportException exception
                                        ) {

                                            compressionRunning =
                                                    false;

                                            String message =
                                                    exception.getMessage();

                                            if (
                                                    message == null
                                                    || message.isEmpty()
                                            ) {

                                                message =
                                                        "Compression failed";
                                            }

                                            callJs(
                                                    "compressionFailed",
                                                    jsString(
                                                            message
                                                    )
                                            );
                                        }
                                    }
                            )
                            .build();

            compressionTransformer.start(
                    composition,
                    outputFile.getAbsolutePath()
            );

            startCompressionProgressMonitor();

        } catch (Exception e) {

            compressionRunning =
                    false;

            callJs(
                    "compressionFailed",
                    jsString(
                            e.getMessage()
                    )
            );
        }
    }

    // ============================================================
    // COMPRESSION PROGRESS
    // ============================================================

    private void startCompressionProgressMonitor() {

        if (compressionProgressThread != null) {

            try {
                compressionProgressThread.interrupt();
            } catch (Exception ignored) {
            }
        }

        compressionProgressThread =
                new Thread(() -> {

                    ProgressHolder holder =
                            new ProgressHolder();

                    while (
                            compressionRunning
                            && compressionTransformer != null
                    ) {

                        try {

                            int state =
                                    compressionTransformer
                                            .getProgress(
                                                    holder
                                            );

                            if (
                                    holder.progress >= 0
                            ) {

                                final int progress =
                                        holder.progress;

                                runOnUiThread(() ->
                                        callJs(
                                                "compressionProgress",
                                                String.valueOf(
                                                        progress
                                                )
                                        )
                                );
                            }

                            if (
                                    state
                                            == Transformer.PROGRESS_STATE_NOT_STARTED
                            ) {

                                Thread.sleep(300);

                            } else {

                                Thread.sleep(500);
                            }

                        } catch (
                                InterruptedException e
                        ) {

                            break;

                        } catch (Exception e) {

                            break;
                        }
                    }
                });

        compressionProgressThread.start();
    }

    // ============================================================
    // SRT BURN
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

        srtBurning = true;

        callJs(
                "srtBurnProgress",
                "0",
                "'Preparing...'"
        );

        try {

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

            String timestamp =
                    new SimpleDateFormat(
                            "yyyyMMdd_HHmmss",
                            Locale.US
                    ).format(
                            new Date()
                    );

            currentSrtTempOutput =
                    new File(
                            outputDir,
                            "subtitle_video_"
                                    + timestamp
                                    + ".mp4"
                    );

            MediaItem mediaItem =
                    MediaItem.fromUri(
                            selectedSrtVideoUri
                    );

            SrtCanvasOverlay overlay =
                    new SrtCanvasOverlay(
                            srtCues,
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

            Effects effects =
                    new Effects(
                            Collections.emptyList(),
                            Collections.singletonList(
                                    overlayEffect
                            )
                    );

            EditedMediaItem editedMediaItem =
                    new EditedMediaItem.Builder(
                            mediaItem
                    )
                            .setEffects(
                                    effects
                            )
                            .build();

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

            srtTransformer =
                    new Transformer.Builder(
                            this
                    )
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

                                            srtBurning =
                                                    false;

                                            runOnUiThread(() -> {

                                                try {

                                                    String fileName =
                                                            currentSrtTempOutput
                                                                    .getName();

                                                    saveSrtVideoToMediaStore(
                                                            currentSrtTempOutput,
                                                            fileName
                                                    );

                                                    callJs(
                                                            "srtBurnProgress",
                                                            "100",
                                                            "'Completed'"
                                                    );

                                                    callJs(
                                                            "srtBurnFinished",
                                                            jsString(
                                                                    fileName
                                                            )
                                                    );

                                                } catch (
                                                        Exception e
                                                ) {

                                                    callJs(
                                                            "srtBurnFailed",
                                                            jsString(
                                                                    e.getMessage()
                                                            )
                                                    );
                                                }
                                            });
                                        }

                                        @Override
                                        public void onError(
                                                Composition composition,
                                                ExportResult result,
                                                ExportException exception
                                        ) {

                                            srtBurning =
                                                    false;

                                            String message =
                                                    exception.getMessage();

                                            if (
                                                    message == null
                                                    || message.isEmpty()
                                            ) {

                                                message =
                                                        "Subtitle burn failed";
                                            }

                                            callJs(
                                                    "srtBurnFailed",
                                                    jsString(
                                                            message
                                                    )
                                            );
                                        }
                                    }
                            )
                            .build();

            srtTransformer.start(
                    composition,
                    currentSrtTempOutput.getAbsolutePath()
            );

            startSrtProgressMonitor();

        } catch (Exception e) {

            srtBurning =
                    false;

            callJs(
                    "srtBurnFailed",
                    jsString(
                            e.getMessage()
                    )
            );
        }
    }

    // ============================================================
    // SRT PROGRESS
    // ============================================================

    private void startSrtProgressMonitor() {

        if (srtProgressThread != null) {

            try {
                srtProgressThread.interrupt();
            } catch (Exception ignored) {
            }
        }

        srtProgressThread =
                new Thread(() -> {

                    ProgressHolder holder =
                            new ProgressHolder();

                    while (
                            srtBurning
                            && srtTransformer != null
                    ) {

                        try {

                            srtTransformer.getProgress(
                                    holder
                            );

                            int progress =
                                    holder.progress;

                            if (progress >= 0) {

                                runOnUiThread(() ->
                                        callJs(
                                                "srtBurnProgress",
                                                String.valueOf(
                                                        progress
                                                ),
                                                "'Burning subtitle...'"
                                        )
                                );
                            }

                            Thread.sleep(500);

                        } catch (
                                InterruptedException e
                        ) {

                            break;

                        } catch (Exception e) {

                            break;
                        }
                    }
                });

        srtProgressThread.start();
    }

    // ============================================================
    // SAVE SRT VIDEO
    // ============================================================

    private void saveSrtVideoToMediaStore(
            File sourceFile,
            String fileName
    ) throws IOException {

        if (
                sourceFile == null
                || !sourceFile.exists()
        ) {

            throw new IOException(
                    "Output video မတွေ့ပါ"
            );
        }

        if (Build.VERSION.SDK_INT >= 29) {

            ContentResolver resolver =
                    getContentResolver();

            ContentValues values =
                    new ContentValues();

            values.put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    fileName
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
                    MediaStore.Video.Media
                            .getContentUri(
                                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                            );

            Uri uri =
                    resolver.insert(
                            collection,
                            values
                    );

            if (uri == null) {

                throw new IOException(
                        "MediaStore file create မရပါ"
                );
            }

            try {

                OutputStream outputStream =
                        resolver.openOutputStream(
                                uri
                        );

                if (outputStream == null) {

                    throw new IOException(
                            "Output stream မရပါ"
                    );
                }

                copyFile(
                        sourceFile,
                        outputStream
                );

                outputStream.close();

                ContentValues done =
                        new ContentValues();

                done.put(
                        MediaStore.Video.Media.IS_PENDING,
                        0
                );

                resolver.update(
                        uri,
                        done,
                        null,
                        null
                );

            } catch (Exception e) {

                resolver.delete(
                        uri,
                        null,
                        null
                );

                throw e;
            }

        } else {

            File moviesDir =
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_MOVIES
                    );

            File appDir =
                    new File(
                            moviesDir,
                            "Media Toolkit"
                    );

            if (!appDir.exists()) {
                appDir.mkdirs();
            }

            File destination =
                    new File(
                            appDir,
                            fileName
                    );

            copyFile(
                    sourceFile,
                    destination
            );
        }
    }

    // ============================================================
    // FILE COPY
    // ============================================================

    private void copyFile(
            File source,
            OutputStream outputStream
    ) throws IOException {

        FileInputStream input =
                new FileInputStream(
                        source
                );

        try {

            byte[] buffer =
                    new byte[1024 * 64];

            int length;

            while (
                    (length = input.read(buffer))
                            != -1
            ) {

                outputStream.write(
                        buffer,
                        0,
                        length
                );
            }

            outputStream.flush();

        } finally {

            input.close();
        }
    }

    private void copyFile(
            File source,
            File destination
    ) throws IOException {

        FileInputStream input =
                new FileInputStream(
                        source
                );

        FileOutputStream output =
                new FileOutputStream(
                        destination
                );

        try {

            byte[] buffer =
                    new byte[1024 * 64];

            int length;

            while (
                    (length = input.read(buffer))
                            != -1
            ) {

                output.write(
                        buffer,
                        0,
                        length
                );
            }

            output.flush();

        } finally {

            try {
                input.close();
            } catch (Exception ignored) {
            }

            try {
                output.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ============================================================
    // SRT FILE READER
    // ============================================================

    private String readSrtFile(
            Uri uri
    ) throws IOException {

        InputStream input =
                getContentResolver()
                        .openInputStream(uri);

        if (input == null) {

            throw new IOException(
                    "SRT file ဖတ်မရပါ"
            );
        }

        byte[] bytes;

        try {

            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();

            byte[] buffer =
                    new byte[8192];

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

            bytes =
                    output.toByteArray();

        } finally {

            input.close();
        }

        if (bytes.length >= 2) {

            // UTF-16 LE BOM
            if (
                    (bytes[0] & 0xFF) == 0xFF
                    && (bytes[1] & 0xFF) == 0xFE
            ) {

                return new String(
                        bytes,
                        2,
                        bytes.length - 2,
                        Charset.forName(
                                "UTF-16LE"
                        )
                );
            }

            // UTF-16 BE BOM
            if (
                    (bytes[0] & 0xFF) == 0xFE
                    && (bytes[1] & 0xFF) == 0xFF
            ) {

                return new String(
                        bytes,
                        2,
                        bytes.length - 2,
                        Charset.forName(
                                "UTF-16BE"
                        )
                );
            }
        }

        // UTF-8 BOM
        if (
                bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF
        ) {

            return new String(
                    bytes,
                    3,
                    bytes.length - 3,
                    Charset.forName(
                            "UTF-8"
                    )
            );
        }

        // Detect UTF-16 without BOM
        if (looksLikeUtf16LE(bytes)) {

            return new String(
                    bytes,
                    Charset.forName(
                            "UTF-16LE"
                    )
            );
        }

        if (looksLikeUtf16BE(bytes)) {

            return new String(
                    bytes,
                    Charset.forName(
                            "UTF-16BE"
                    )
            );
        }

        return new String(
                bytes,
                Charset.forName(
                        "UTF-8"
                )
        );
    }

    private boolean looksLikeUtf16LE(
            byte[] data
    ) {

        if (data.length < 10) {
            return false;
        }

        int zeroCount = 0;

        int limit =
                Math.min(
                        data.length,
                        200
                );

        for (
                int i = 1;
                i < limit;
                i += 2
        ) {

            if (data[i] == 0) {
                zeroCount++;
            }
        }

        return zeroCount > 5;
    }

    private boolean looksLikeUtf16BE(
            byte[] data
    ) {

        if (data.length < 10) {
            return false;
        }

        int zeroCount = 0;

        int limit =
                Math.min(
                        data.length,
                        200
                );

        for (
                int i = 0;
                i < limit;
                i += 2
        ) {

            if (data[i] == 0) {
                zeroCount++;
            }
        }

        return zeroCount > 5;
    }

    // ============================================================
    // SRT PARSER
    // ============================================================

    private List<SrtCue> parseSrt(
            String text
    ) {

        List<SrtCue> result =
                new ArrayList<>();

        if (text == null) {
            return result;
        }

        text =
                text.replace(
                        "\r\n",
                        "\n"
                );

        text =
                text.replace(
                        "\r",
                        "\n"
                );

        String[] blocks =
                text.split(
                        "\\n\\s*\\n"
                );

        Pattern timePattern =
                Pattern.compile(
                        "(\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3})\\s*-->\\s*(\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3})"
                );

        for (String block : blocks) {

            if (
                    block == null
                    || block.trim().isEmpty()
            ) {
                continue;
            }

            String[] lines =
                    block.split(
                            "\\n"
                    );

            if (lines.length < 2) {
                continue;
            }

            Matcher matcher =
                    timePattern.matcher(
                            block
                    );

            if (!matcher.find()) {
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

            StringBuilder subtitle =
                    new StringBuilder();

            boolean afterTime =
                    false;

            for (String line : lines) {

                if (
                        line.contains("-->")
                ) {

                    afterTime = true;

                    continue;
                }

                if (!afterTime) {
                    continue;
                }

                String cleaned =
                        line.trim();

                if (cleaned.isEmpty()) {
                    continue;
                }

                if (
                        subtitle.length() > 0
                ) {

                    subtitle.append("\n");
                }

                subtitle.append(
                        cleaned
                );
            }

            String subtitleText =
                    subtitle.toString()
                            .trim();

            if (
                    !subtitleText.isEmpty()
                    && end > start
            ) {

                result.add(
                        new SrtCue(
                                start,
                                end,
                                subtitleText
                        )
                );
            }
        }

        return result;
    }

    private long parseSrtTime(
            String time
    ) {

        try {

            String normalized =
                    time.replace(
                            ',',
                            '.'
                    );

            String[] parts =
                    normalized.split(
                            ":"
                    );

            int hours =
                    Integer.parseInt(
                            parts[0]
                    );

            int minutes =
                    Integer.parseInt(
                            parts[1]
                    );

            String[] seconds =
                    parts[2].split(
                            "\\."
                    );

            int sec =
                    Integer.parseInt(
                            seconds[0]
                    );

            int millis = 0;

            if (
                    seconds.length > 1
            ) {

                String ms =
                        seconds[1];

                if (ms.length() == 1) {

                    ms += "00";

                } else if (
                        ms.length() == 2
                ) {

                    ms += "0";
                }

                if (ms.length() > 3) {
                    ms = ms.substring(0, 3);
                }

                millis =
                        Integer.parseInt(
                                ms
                        );
            }

            return
                    hours * 3600000L
                            + minutes * 60000L
                            + sec * 1000L
                            + millis;

        } catch (Exception e) {

            return 0;
        }
    }

    // ============================================================
    // SRT CUE
    // ============================================================

    private static class SrtCue {

        final long startMs;
        final long endMs;
        final String text;

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
    // CANVAS SUBTITLE OVERLAY
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

        private final TextPaint textPaint =
                new TextPaint(
                        Paint.ANTI_ALIAS_FLAG
                                | Paint.SUBPIXEL_TEXT_FLAG
                );

        private final Paint backgroundPaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

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

            // false = SDR canvas
            super(false);

            this.cues =
                    cues != null
                            ? cues
                            : Collections.emptyList();

            this.textColor =
                    parseColorSafe(
                            textColor,
                            Color.WHITE
                    );

            this.outlineColor =
                    parseColorSafe(
                            outlineColor,
                            Color.BLACK
                    );

            this.backgroundColor =
                    parseColorSafe(
                            backgroundColor,
                            Color.argb(
                                    160,
                                    0,
                                    0,
                                    0
                            )
                    );

            this.effect =
                    effect != null
                            ? effect.toLowerCase(
                                    Locale.US
                            )
                            : "none";

            this.position =
                    position != null
                            ? position.toLowerCase(
                                    Locale.US
                            )
                            : "bottom";

            this.fontSize =
                    Math.max(
                            18,
                            Math.min(
                                    fontSize,
                                    120
                            )
                    );

            this.outlineWidth =
                    Math.max(
                            0,
                            Math.min(
                                    outlineWidth,
                                    20
                            )
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

            SrtCue active =
                    findCue(
                            timeMs
                    );

            if (active == null) {
                return;
            }

            String text =
                    active.text;

            if (
                    text == null
                    || text.trim().isEmpty()
            ) {
                return;
            }

            float width =
                    canvas.getWidth();

            float height =
                    canvas.getHeight();

            if (
                    width <= 0
                    || height <= 0
            ) {
                return;
            }

            float size =
                    calculateFontSize(
                            height
                    );

            textPaint.setTextSize(
                    size
            );

            textPaint.setTypeface(
                    Typeface.create(
                            "sans-serif",
                            bold
                                    ? Typeface.BOLD
                                    : Typeface.NORMAL
                    )
            );

            textPaint.setTextAlign(
                    Paint.Align.CENTER
            );

            textPaint.setStrokeJoin(
                    Paint.Join.ROUND
            );

            textPaint.setStrokeCap(
                    Paint.Cap.ROUND
            );

            List<String> lines =
                    wrapText(
                            text,
                            width * 0.88f
                    );

            float lineHeight =
                    size * 1.18f;

            float totalHeight =
                    lines.size()
                            * lineHeight;

            float centerY =
                    calculatePositionY(
                            height,
                            totalHeight
                    );

            float alpha =
                    calculateAlpha(
                            active,
                            timeMs
                    );

            float scale =
                    calculateScale(
                            active,
                            timeMs
                    );

            float slide =
                    calculateSlide(
                            active,
                            timeMs,
                            height
                    );

            canvas.save();

            canvas.translate(
                    width / 2f,
                    centerY + slide
            );

            canvas.scale(
                    scale,
                    scale
            );

            int originalTextColor =
                    textPaint.getColor();

            Paint.Style originalStyle =
                    textPaint.getStyle();

            float originalStrokeWidth =
                    textPaint.getStrokeWidth();

            textPaint.setAlpha(
                    (int)
                            (255f * alpha)
            );

            // ----------------------------------------------------
            // BACKGROUND
            // ----------------------------------------------------

            if (backgroundEnabled) {

                float maxWidth =
                        0f;

                for (String line : lines) {

                    maxWidth =
                            Math.max(
                                    maxWidth,
                                    textPaint.measureText(
                                            line
                                    )
                            );
                }

                float paddingX =
                        size * 0.45f;

                float paddingY =
                        size * 0.25f;

                float left =
                        -maxWidth / 2f
                                - paddingX;

                float right =
                        maxWidth / 2f
                                + paddingX;

                float top =
                        -totalHeight / 2f
                                - paddingY;

                float bottom =
                        totalHeight / 2f
                                + paddingY;

                backgroundPaint.setColor(
                        backgroundColor
                );

                backgroundPaint.setAlpha(
                        (int)
                                (
                                        Color.alpha(
                                                backgroundColor
                                        )
                                                * alpha
                                )
                );

                float radius =
                        size * 0.18f;

                canvas.drawRoundRect(
                        left,
                        top,
                        right,
                        bottom,
                        radius,
                        radius,
                        backgroundPaint
                );
            }

            // ----------------------------------------------------
            // TEXT
            // ----------------------------------------------------

            float firstBaseline =
                    -totalHeight / 2f
                            - textPaint.ascent()
                            + (
                            lineHeight
                                    - (
                                    -textPaint.ascent()
                                            + textPaint.descent()
                            )
                    ) / 2f;

            float y =
                    firstBaseline;

            for (String line : lines) {

                // Outline
                if (outlineWidth > 0) {

                    textPaint.setStyle(
                            Paint.Style.STROKE
                    );

                    textPaint.setStrokeWidth(
                            outlineWidth
                                    * 2f
                    );

                    textPaint.setColor(
                            outlineColor
                    );

                    textPaint.setAlpha(
                            (int)
                                    (255f * alpha)
                    );

                    canvas.drawText(
                            line,
                            0,
                            y,
                            textPaint
                    );
                }

                // Main text
                textPaint.setStyle(
                        Paint.Style.FILL
                );

                textPaint.setStrokeWidth(
                        0f
                );

                textPaint.setColor(
                        textColor
                );

                textPaint.setAlpha(
                        (int)
                                (255f * alpha)
                );

                canvas.drawText(
                        line,
                        0,
                        y,
                        textPaint
                );

                y += lineHeight;
            }

            textPaint.setColor(
                    originalTextColor
            );

            textPaint.setStyle(
                    originalStyle
            );

            textPaint.setStrokeWidth(
                    originalStrokeWidth
            );

            canvas.restore();
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
        // FONT SIZE
        // --------------------------------------------------------

        private float calculateFontSize(
                float height
        ) {

            float scale =
                    height / 1080f;

            return Math.max(
                    18f,
                    fontSize * scale
            );
        }

        // --------------------------------------------------------
        // POSITION
        // --------------------------------------------------------

        private float calculatePositionY(
                float height,
                float totalHeight
        ) {

            if (
                    "top".equals(position)
            ) {

                return
                        height * 0.16f
                                + totalHeight / 2f;
            }

            if (
                    "center".equals(position)
                    || "middle".equals(position)
            ) {

                return
                        height / 2f;
            }

            // bottom
            return
                    height * 0.86f
                            - totalHeight / 2f;
        }

        // --------------------------------------------------------
        // FADE EFFECT
        // --------------------------------------------------------

        private float calculateAlpha(
                SrtCue cue,
                long timeMs
        ) {

            if (
                    !"fade".equals(effect)
            ) {

                return 1f;
            }

            long elapsed =
                    timeMs - cue.startMs;

            long remaining =
                    cue.endMs - timeMs;

            float fadeDuration =
                    350f;

            float alphaIn =
                    Math.min(
                            1f,
                            elapsed
                                    / fadeDuration
                    );

            float alphaOut =
                    Math.min(
                            1f,
                            remaining
                                    / fadeDuration
                    );

            return Math.max(
                    0.05f,
                    Math.min(
                            alphaIn,
                            alphaOut
                    )
            );
        }

        // --------------------------------------------------------
        // POP EFFECT
        // --------------------------------------------------------

        private float calculateScale(
                SrtCue cue,
                long timeMs
        ) {

            if (
                    !"pop".equals(effect)
            ) {

                return 1f;
            }

            long elapsed =
                    timeMs - cue.startMs;

            if (elapsed < 350) {

                float p =
                        elapsed / 350f;

                // Ease-out
                p =
                        1f
                                - (
                                1f - p
                        )
                                * (
                                1f - p
                        );

                return
                        0.80f
                                + 0.20f * p;
            }

            return 1f;
        }

        // --------------------------------------------------------
        // SLIDE EFFECT
        // --------------------------------------------------------

        private float calculateSlide(
                SrtCue cue,
                long timeMs,
                float height
        ) {

            if (
                    !"slide".equals(effect)
            ) {

                return 0f;
            }

            long elapsed =
                    timeMs - cue.startMs;

            if (elapsed >= 350) {
                return 0f;
            }

            float p =
                    elapsed / 350f;

            p =
                    1f
                            - (
                            1f - p
                    )
                            * (
                            1f - p
                    );

            return
                    (1f - p)
                            * height
                            * 0.10f;
        }

        // --------------------------------------------------------
        // TEXT WRAPPING
        // --------------------------------------------------------

        private List<String> wrapText(
                String text,
                float maxWidth
        ) {

            List<String> result =
                    new ArrayList<>();

            String[] originalLines =
                    text.split(
                            "\\n"
                    );

            for (
                    String originalLine
                    : originalLines
            ) {

                if (
                        originalLine.isEmpty()
                ) {

                    result.add("");

                    continue;
                }

                String[] words =
                        originalLine.split(
                                "\\s+"
                        );

                StringBuilder line =
                        new StringBuilder();

                for (String word : words) {

                    String candidate;

                    if (
                            line.length() == 0
                    ) {

                        candidate =
                                word;

                    } else {

                        candidate =
                                line
                                        .toString()
                                        + " "
                                        + word;
                    }

                    float width =
                            textPaint.measureText(
                                    candidate
                            );

                    if (
                            width <= maxWidth
                            || line.length() == 0
                    ) {

                        line.setLength(0);

                        line.append(
                                candidate
                        );

                    } else {

                        result.add(
                                line.toString()
                        );

                        line.setLength(0);

                        line.append(
                                word
                        );
                    }
                }

                if (
                        line.length() > 0
                ) {

                    result.add(
                            line.toString()
                    );
                }
            }

            return result;
        }

        // --------------------------------------------------------
        // COLOR PARSER
        // --------------------------------------------------------

        private static int parseColorSafe(
                String value,
                int fallback
        ) {

            if (
                    value == null
                    || value.trim().isEmpty()
            ) {

                return fallback;
            }

            try {

                String color =
                        value.trim();

                if (
                        !color.startsWith("#")
                ) {

                    color =
                            "#"
                                    + color;
                }

                return Color.parseColor(
                        color
                );

            } catch (Exception e) {

                return fallback;
            }
        }
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

        String result = null;

        if (
                "content".equals(
                        uri.getScheme()
                )
        ) {

            Cursor cursor = null;

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

        if (result == null) {

            result =
                    uri.getLastPathSegment();
        }

        if (result == null) {

            result =
                    "Unknown";
        }

        return result;
    }

    // ============================================================
    // PERSIST URI PERMISSION
    // ============================================================

    private void takePersistablePermission(
            Uri uri,
            Intent data
    ) {

        if (uri == null) {
            return;
        }

        try {

            int flags =
                    data.getFlags()
                            & (
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );

            getContentResolver()
                    .takePersistableUriPermission(
                            uri,
                            flags
                    );

        } catch (Exception ignored) {
        }
    }

    // ============================================================
    // JAVASCRIPT CALL
    // ============================================================

    private void callJs(
            String function,
            String... args
    ) {

        if (webView == null) {
            return;
        }

        StringBuilder script =
                new StringBuilder();

        script.append(
                "javascript:(function(){"
        );

        script.append(
                "if(typeof "
        );

        script.append(
                function
        );

        script.append(
                "==='function'){"
        );

        script.append(
                function
        );

        script.append("("
        );

        for (
                int i = 0;
                i < args.length;
                i++
        ) {

            if (i > 0) {
                script.append(",");
            }

            script.append(
                    args[i]
            );
        }

        script.append(
                ");}"
        );

        script.append(
                "})()"
        );

        runOnUiThread(() ->
                webView.evaluateJavascript(
                        script.toString(),
                        null
                )
        );
    }

    // ============================================================
    // JS STRING ESCAPE
    // ============================================================

    private String jsString(
            String value
    ) {

        if (value == null) {
            value = "";
        }

        String escaped =
                value
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
                        );

        return "'" + escaped + "'";
    }

    // ============================================================
    // TOAST
    // ============================================================

    private void showToast(
            String message
    ) {

        runOnUiThread(() ->
                Toast.makeText(
                        MainActivity.this,
                        message,
                        Toast.LENGTH_SHORT
                ).show()
        );
    }

    // ============================================================
    // ACTIVITY DESTROY
    // ============================================================

    @Override
    protected void onDestroy() {

        try {

            if (compressionTransformer != null) {
                compressionTransformer.cancel();
            }

        } catch (Exception ignored) {
        }

        try {

            if (srtTransformer != null) {
                srtTransformer.cancel();
            }

        } catch (Exception ignored) {
        }

        try {

            if (compressionProgressThread != null) {
                compressionProgressThread.interrupt();
            }

        } catch (Exception ignored) {
        }

        try {

            if (srtProgressThread != null) {
                srtProgressThread.interrupt();
            }

        } catch (Exception ignored) {
        }

        if (webView != null) {

            webView.stopLoading();

            webView.removeJavascriptInterface(
                    "AndroidBridge"
            );

            webView.destroy();

            webView = null;
        }

        super.onDestroy();
    }
            }
