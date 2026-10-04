package com.mytranscriber.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Layout;
import android.text.StaticLayout;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.Effects;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.CanvasOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.AudioEncoderSettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

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
 * 2. Web file chooser
 * 3. Audio compressor
 * 4. SRT -> Video subtitle burner
 * 5. Auto-save burned video
 */
public class MainActivity extends AppCompatActivity {

    // ============================================================
    // WEBVIEW
    // ============================================================

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ValueCallback<Uri[]> webFilePathCallback;

    private static final int WEB_FILE_PICKER_REQUEST = 4001;


    // ============================================================
    // NORMAL COMPRESSOR
    // ============================================================

    private static final int PICK_COMPRESS_FILE = 2001;

    private Uri compressInputUri;

    private Transformer compressorTransformer;

    private boolean compressorRunning = false;

    private static final int DEFAULT_AUDIO_BITRATE = 64000;


    // ============================================================
    // SRT VIDEO BURNER
    // ============================================================

    private static final int PICK_SRT_VIDEO = 5001;
    private static final int PICK_SRT_FILE = 5002;

    private Uri srtVideoUri;
    private Uri srtFileUri;

    private String selectedSrtVideoName = "";
    private String selectedSrtFileName = "";

    private Transformer srtTransformer;

    private boolean srtBurnRunning = false;


    // ============================================================
    // ON CREATE
    // ============================================================

    @SuppressLint({
            "SetJavaScriptEnabled",
            "JavascriptInterface"
    })
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        settings.setMediaPlaybackRequiresUserGesture(false);

        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.setBackgroundColor(Color.TRANSPARENT);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
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

                    return true;

                } catch (Exception e) {

                    webFilePathCallback = null;

                    Toast.makeText(
                            MainActivity.this,
                            "File picker error",
                            Toast.LENGTH_SHORT
                    ).show();

                    return false;
                }
            }
        });

        webView.addJavascriptInterface(
                new AndroidBridge(),
                "Android"
        );

        setContentView(webView);

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
        public void pickCompressFile() {

            runOnUiThread(() -> {

                try {

                    Intent intent = new Intent(
                            Intent.ACTION_OPEN_DOCUMENT
                    );

                    intent.addCategory(
                            Intent.CATEGORY_OPENABLE
                    );

                    intent.setType("*/*");

                    intent.putExtra(
                            Intent.EXTRA_MIME_TYPES,
                            new String[]{
                                    "audio/*",
                                    "video/*"
                            }
                    );

                    startActivityForResult(
                            intent,
                            PICK_COMPRESS_FILE
                    );

                } catch (Exception e) {

                    sendJs(
                            "window.compressionFailed(" +
                                    jsString(
                                            "Cannot open file picker"
                                    ) +
                                    ");"
                    );
                }
            });
        }


        @JavascriptInterface
        public void startCompression() {

            if (compressInputUri == null) {

                sendJs(
                        "window.compressionFailed(" +
                                jsString(
                                        "Please choose a file first."
                                ) +
                                ");"
                );

                return;
            }

            if (compressorRunning) {

                sendJs(
                        "window.compressionFailed(" +
                                jsString(
                                        "Compression is already running."
                                ) +
                                ");"
                );

                return;
            }

            startNativeCompression(
                    compressInputUri
            );
        }


        // --------------------------------------------------------
        // SRT VIDEO PICKER
        // --------------------------------------------------------

        @JavascriptInterface
        public void pickSrtVideo() {

            runOnUiThread(() -> {

                try {

                    Intent intent = new Intent(
                            Intent.ACTION_OPEN_DOCUMENT
                    );

                    intent.addCategory(
                            Intent.CATEGORY_OPENABLE
                    );

                    intent.setType("video/*");

                    startActivityForResult(
                            intent,
                            PICK_SRT_VIDEO
                    );

                } catch (Exception e) {

                    sendJs(
                            "window.srtBurnFailed(" +
                                    jsString(
                                            "Cannot open video picker."
                                    ) +
                                    ");"
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

                try {

                    Intent intent = new Intent(
                            Intent.ACTION_OPEN_DOCUMENT
                    );

                    intent.addCategory(
                            Intent.CATEGORY_OPENABLE
                    );

                    intent.setType("text/plain");

                    intent.putExtra(
                            Intent.EXTRA_MIME_TYPES,
                            new String[]{
                                    "text/plain",
                                    "application/x-subrip",
                                    "application/octet-stream"
                            }
                    );

                    startActivityForResult(
                            intent,
                            PICK_SRT_FILE
                    );

                } catch (Exception e) {

                    sendJs(
                            "window.srtBurnFailed(" +
                                    jsString(
                                            "Cannot open SRT picker."
                                    ) +
                                    ");"
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

            if (srtBurnRunning) {

                sendJs(
                        "window.srtBurnFailed(" +
                                jsString(
                                        "Subtitle processing is already running."
                                ) +
                                ");"
                );

                return;
            }

            if (srtVideoUri == null) {

                sendJs(
                        "window.srtBurnFailed(" +
                                jsString(
                                        "Please choose a video first."
                                ) +
                                ");"
                );

                return;
            }

            if (srtFileUri == null) {

                sendJs(
                        "window.srtBurnFailed(" +
                                jsString(
                                        "Please choose an SRT file first."
                                ) +
                                ");"
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
        }


        // --------------------------------------------------------
        // GET SELECTED FILE NAMES
        // --------------------------------------------------------

        @JavascriptInterface
        public String getSelectedSrtVideoName() {
            return selectedSrtVideoName;
        }


        @JavascriptInterface
        public String getSelectedSrtFileName() {
            return selectedSrtFileName;
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

                    results = new Uri[count];

                    for (int i = 0; i < count; i++) {

                        results[i] =
                                data.getClipData()
                                        .getItemAt(i)
                                        .getUri();
                    }

                } else if (data.getData() != null) {

                    results = new Uri[]{
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
        // COMPRESS FILE
        // --------------------------------------------------------

        if (requestCode == PICK_COMPRESS_FILE) {

            if (
                    resultCode == Activity.RESULT_OK
                    && data != null
                    && data.getData() != null
            ) {

                compressInputUri =
                        data.getData();

                tryTakePersistablePermission(
                        data.getData(),
                        data
                );

                String name =
                        getFileName(data.getData());

                sendJs(
                        "window.compressionFileSelected(" +
                                jsString(name) +
                                ");"
                );

            } else {

                compressInputUri = null;
            }

            return;
        }


        // --------------------------------------------------------
        // SRT VIDEO
        // --------------------------------------------------------

        if (requestCode == PICK_SRT_VIDEO) {

            if (
                    resultCode == Activity.RESULT_OK
                    && data != null
                    && data.getData() != null
            ) {

                srtVideoUri =
                        data.getData();

                tryTakePersistablePermission(
                        data.getData(),
                        data
                );

                selectedSrtVideoName =
                        getFileName(
                                data.getData()
                        );

                sendJs(
                        "window.srtVideoSelected(" +
                                jsString(
                                        selectedSrtVideoName
                                ) +
                                ");"
                );

            } else {

                srtVideoUri = null;
                selectedSrtVideoName = "";
            }

            return;
        }


        // --------------------------------------------------------
        // SRT FILE
        // --------------------------------------------------------

        if (requestCode == PICK_SRT_FILE) {

            if (
                    resultCode == Activity.RESULT_OK
                    && data != null
                    && data.getData() != null
            ) {

                srtFileUri =
                        data.getData();

                tryTakePersistablePermission(
                        data.getData(),
                        data
                );

                selectedSrtFileName =
                        getFileName(
                                data.getData()
                        );

                sendJs(
                        "window.srtFileSelected(" +
                                jsString(
                                        selectedSrtFileName
                                ) +
                                ");"
                );

            } else {

                srtFileUri = null;
                selectedSrtFileName = "";
            }
        }
    }


    // ============================================================
    // PERSIST URI PERMISSION
    // ============================================================

    private void tryTakePersistablePermission(
            Uri uri,
            Intent data
    ) {

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
    // NORMAL AUDIO COMPRESSOR
    // ============================================================

    private void startNativeCompression(
            Uri inputUri
    ) {

        compressorRunning = true;

        sendJs(
                "window.compressionProgress(1," +
                        jsString(
                                "Preparing..."
                        ) +
                        ");"
        );

        long originalSize =
                getUriSize(inputUri);

        File outputDir =
                new File(
                        getExternalFilesDir(
                                Environment.DIRECTORY_MUSIC
                        ),
                        "MyTranscriber"
                );

        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        String fileName =
                "compressed_audio_" +
                        new SimpleDateFormat(
                                "yyyyMMdd_HHmmss",
                                Locale.US
                        ).format(new Date()) +
                        ".mp4";

        File outputFile =
                new File(
                        outputDir,
                        fileName
                );

        if (outputFile.exists()) {
            outputFile.delete();
        }

        try {

            MediaItem mediaItem =
                    MediaItem.fromUri(inputUri);

            EditedMediaItem editedMediaItem =
                    new EditedMediaItem.Builder(
                            mediaItem
                    )
                            .setRemoveVideo(true)
                            .build();


            AudioEncoderSettings audioSettings =
                    new AudioEncoderSettings.Builder()
                            .setBitrate(
                                    DEFAULT_AUDIO_BITRATE
                            )
                            .build();


            DefaultEncoderFactory encoderFactory =
                    new DefaultEncoderFactory.Builder(
                            this
                    )
                            .setEnableFallback(true)
                            .setRequestedAudioEncoderSettings(
                                    audioSettings
                            )
                            .build();


            compressorTransformer =
                    new Transformer.Builder(this)
                            .setAudioMimeType(
                                    MimeTypes.AUDIO_AAC
                            )
                            .setEncoderFactory(
                                    encoderFactory
                            )
                            .addListener(
                                    new Transformer.Listener() {

                                        @Override
                                        public void onCompleted(
                                                Composition composition,
                                                ExportResult result
                                        ) {

                                            compressorRunning =
                                                    false;

                                            long outputSize =
                                                    outputFile.exists()
                                                            ? outputFile.length()
                                                            : 0;

                                            sendJs(
                                                    "window.compressionFinished(" +
                                                            jsString(
                                                                    outputFile.getAbsolutePath()
                                                            ) +
                                                            "," +
                                                            originalSize +
                                                            "," +
                                                            outputSize +
                                                            ");"
                                            );
                                        }


                                        @Override
                                        public void onError(
                                                Composition composition,
                                                ExportResult result,
                                                ExportException exception
                                        ) {

                                            compressorRunning =
                                                    false;

                                            if (outputFile.exists()) {
                                                outputFile.delete();
                                            }

                                            sendJs(
                                                    "window.compressionFailed(" +
                                                            jsString(
                                                                    getSafeError(
                                                                            exception
                                                                    )
                                                            ) +
                                                            ");"
                                            );
                                        }
                                    }
                            )
                            .build();


            compressorTransformer.start(
                    editedMediaItem,
                    outputFile.getAbsolutePath()
            );

            startCompressionProgressLoop(
                    compressorTransformer
            );

        } catch (Exception e) {

            compressorRunning = false;

            if (outputFile.exists()) {
                outputFile.delete();
            }

            sendJs(
                    "window.compressionFailed(" +
                            jsString(
                                    getSafeError(e)
                            ) +
                            ");"
            );
        }
    }


    // ============================================================
    // COMPRESSOR PROGRESS
    // ============================================================

    private void startCompressionProgressLoop(
            Transformer transformer
    ) {

        ProgressHolder holder =
                new ProgressHolder();

        Runnable runnable =
                new Runnable() {

                    @Override
                    public void run() {

                        if (!compressorRunning) {
                            return;
                        }

                        try {

                            int state =
                                    transformer.getProgress(
                                            holder
                                    );

                            if (
                                    state ==
                                            Transformer.PROGRESS_STATE_AVAILABLE
                            ) {

                                int progress =
                                        Math.max(
                                                1,
                                                Math.min(
                                                        99,
                                                        holder.progress
                                                )
                                        );

                                sendJs(
                                        "window.compressionProgress(" +
                                                progress +
                                                "," +
                                                jsString(
                                                        "Compressing " +
                                                                progress +
                                                                "%"
                                                ) +
                                                ");"
                                );
                            }

                        } catch (Exception ignored) {
                        }

                        mainHandler.postDelayed(
                                this,
                                500
                        );
                    }
                };

        mainHandler.post(runnable);
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

        srtBurnRunning = true;

        sendJs(
                "window.srtBurnProgress(1," +
                        jsString(
                                "Reading SRT..."
                        ) +
                        ");"
        );


        new Thread(() -> {

            try {

                String srtText =
                        readTextFile(
                                srtFileUri
                        );

                List<SrtCue> cues =
                        parseSrt(
                                srtText
                        );

                if (cues.isEmpty()) {

                    throw new Exception(
                            "No valid subtitle entries found."
                    );
                }


                File tempDir =
                        new File(
                                getExternalFilesDir(
                                        Environment.DIRECTORY_MOVIES
                                ),
                                "MediaToolkitTemp"
                        );

                if (!tempDir.exists()) {
                    tempDir.mkdirs();
                }


                String tempName =
                        "subtitle_temp_" +
                                System.currentTimeMillis() +
                                ".mp4";

                File tempOutput =
                        new File(
                                tempDir,
                                tempName
                        );

                if (tempOutput.exists()) {
                    tempOutput.delete();
                }


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


                List<TextureOverlay> overlays =
                        new ArrayList<>();

                overlays.add(overlay);


                OverlayEffect overlayEffect =
                        new OverlayEffect(
                                overlays
                        );


                Effects effects =
                        new Effects(
                                Collections.emptyList(),
                                Collections.singletonList(
                                        overlayEffect
                                )
                        );


                MediaItem input =
                        MediaItem.fromUri(
                                srtVideoUri
                        );


                EditedMediaItem edited =
                        new EditedMediaItem.Builder(
                                input
                        )
                                .setEffects(effects)
                                .build();


                DefaultEncoderFactory encoderFactory =
                        new DefaultEncoderFactory.Builder(
                                MainActivity.this
                        )
                                .setEnableFallback(true)
                                .build();


                runOnUiThread(() -> {

                    try {

                        srtTransformer =
                                new Transformer.Builder(
                                        MainActivity.this
                                )
                                        .setVideoMimeType(
                                                MimeTypes.VIDEO_H264
                                        )
                                        .setAudioMimeType(
                                                MimeTypes.AUDIO_AAC
                                        )
                                        .setEncoderFactory(
                                                encoderFactory
                                        )
                                        .addListener(
                                                new Transformer.Listener() {

                                                    @Override
                                                    public void onCompleted(
                                                            Composition composition,
                                                            ExportResult result
                                                    ) {

                                                        sendJs(
                                                                "window.srtBurnProgress(95," +
                                                                        jsString(
                                                                                "Saving video..."
                                                                        ) +
                                                                        ");"
                                                        );

                                                        new Thread(() -> {

                                                            try {

                                                                String finalName =
                                                                        "subtitle_" +
                                                                                new SimpleDateFormat(
                                                                                        "yyyyMMdd_HHmmss",
                                                                                        Locale.US
                                                                                ).format(
                                                                                        new Date()
                                                                                ) +
                                                                                ".mp4";


                                                                Uri savedUri =
                                                                        autoSaveVideo(
                                                                                tempOutput,
                                                                                finalName
                                                                        );


                                                                if (
                                                                        savedUri ==
                                                                                null
                                                                ) {

                                                                    throw new Exception(
                                                                            "Could not save output video."
                                                                    );
                                                                }


                                                                if (tempOutput.exists()) {
                                                                    tempOutput.delete();
                                                                }


                                                                srtBurnRunning =
                                                                        false;


                                                                runOnUiThread(() -> {

                                                                    sendJs(
                                                                            "window.srtBurnFinished(" +
                                                                                    jsString(
                                                                                            finalName
                                                                                    ) +
                                                                                    ");"
                                                                    );

                                                                });

                                                            } catch (Exception e) {

                                                                srtBurnRunning =
                                                                        false;

                                                                if (tempOutput.exists()) {
                                                                    tempOutput.delete();
                                                                }

                                                                runOnUiThread(() -> {

                                                                    sendJs(
                                                                            "window.srtBurnFailed(" +
                                                                                    jsString(
                                                                                            getSafeError(
                                                                                                    e
                                                                                            )
                                                                                    ) +
                                                                                    ");"
                                                                    );
                                                                });
                                                            }

                                                        }).start();
                                                    }


                                                    @Override
                                                    public void onError(
                                                            Composition composition,
                                                            ExportResult result,
                                                            ExportException exception
                                                    ) {

                                                        srtBurnRunning =
                                                                false;

                                                        if (tempOutput.exists()) {
                                                            tempOutput.delete();
                                                        }

                                                        sendJs(
                                                                "window.srtBurnFailed(" +
                                                                        jsString(
                                                                                getSafeError(
                                                                                        exception
                                                                                )
                                                                        ) +
                                                                        ");"
                                                        );
                                                    }
                                                }
                                        )
                                        .build();


                        srtTransformer.start(
                                edited,
                                tempOutput.getAbsolutePath()
                        );


                        startSrtProgressLoop(
                                srtTransformer
                        );

                    } catch (Exception e) {

                        srtBurnRunning =
                                false;

                        sendJs(
                                "window.srtBurnFailed(" +
                                        jsString(
                                                getSafeError(e)
                                        ) +
                                        ");"
                        );
                    }
                });


            } catch (Exception e) {

                srtBurnRunning =
                        false;

                runOnUiThread(() -> {

                    sendJs(
                            "window.srtBurnFailed(" +
                                    jsString(
                                            getSafeError(e)
                                    ) +
                                    ");"
                    );
                });
            }

        }).start();
    }


    // ============================================================
    // SRT PROGRESS
    // ============================================================

    private void startSrtProgressLoop(
            Transformer transformer
    ) {

        ProgressHolder holder =
                new ProgressHolder();

        Runnable runnable =
                new Runnable() {

                    @Override
                    public void run() {

                        if (!srtBurnRunning) {
                            return;
                        }

                        try {

                            int state =
                                    transformer.getProgress(
                                            holder
                                    );

                            if (
                                    state ==
                                            Transformer.PROGRESS_STATE_AVAILABLE
                            ) {

                                int progress =
                                        Math.max(
                                                1,
                                                Math.min(
                                                        94,
                                                        holder.progress
                                                )
                                        );

                                sendJs(
                                        "window.srtBurnProgress(" +
                                                progress +
                                                "," +
                                                jsString(
                                                        "Burning subtitles " +
                                                                progress +
                                                                "%"
                                                ) +
                                                ");"
                                );
                            }

                        } catch (Exception ignored) {
                        }

                        mainHandler.postDelayed(
                                this,
                                500
                        );
                    }
                };

        mainHandler.post(runnable);
    }


    // ============================================================
    // SRT PARSER
    // ============================================================

    private List<SrtCue> parseSrt(
            String source
    ) throws Exception {

        List<SrtCue> result =
                new ArrayList<>();

        if (source == null) {
            return result;
        }

        source =
                source.replace(
                        "\r\n",
                        "\n"
                ).replace(
                        "\r",
                        "\n"
                );

        source =
                source.replace(
                        "\uFEFF",
                        ""
                );


        String[] blocks =
                source.split(
                        "\\n\\s*\\n"
                );


        Pattern timePattern =
                Pattern.compile(
                        "(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})"
                );


        for (String block : blocks) {

            if (block == null) {
                continue;
            }

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


            int timeLineIndex = -1;

            for (int i = 0; i < lines.length; i++) {

                if (
                        timePattern.matcher(
                                lines[i]
                        ).find()
                ) {

                    timeLineIndex = i;
                    break;
                }
            }


            if (timeLineIndex < 0) {
                continue;
            }


            Matcher matcher =
                    timePattern.matcher(
                            lines[timeLineIndex]
                    );


            if (!matcher.find()) {
                continue;
            }


            long startMs =
                    parseSrtTime(
                            matcher.group(1)
                    );

            long endMs =
                    parseSrtTime(
                            matcher.group(2)
                    );


            if (endMs <= startMs) {
                continue;
            }


            StringBuilder text =
                    new StringBuilder();


            for (
                    int i = timeLineIndex + 1;
                    i < lines.length;
                    i++
            ) {

                String line =
                        lines[i].trim();

                if (line.isEmpty()) {
                    continue;
                }

                if (text.length() > 0) {
                    text.append("\n");
                }

                text.append(line);
            }


            String subtitle =
                    text.toString().trim();


            if (subtitle.isEmpty()) {
                continue;
            }


            result.add(
                    new SrtCue(
                            startMs,
                            endMs,
                            subtitle
                    )
            );
        }


        return result;
    }


    // ============================================================
    // SRT TIME
    // ============================================================

    private long parseSrtTime(
            String value
    ) throws Exception {

        String normalized =
                value.replace(
                        ',',
                        '.'
                );


        String[] parts =
                normalized.split(
                        ":"
                );


        if (parts.length != 3) {

            throw new Exception(
                    "Invalid SRT timestamp: " +
                            value
            );
        }


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


        int secondsValue =
                Integer.parseInt(
                        seconds[0]
                );

        int milliseconds =
                Integer.parseInt(
                        seconds[1]
                );


        return
                hours * 3600000L
                        +
                        minutes * 60000L
                        +
                        secondsValue * 1000L
                        +
                        milliseconds;
    }


    // ============================================================
    // UTF-8 / UTF-16 SRT READER
    // ============================================================

    private String readTextFile(
            Uri uri
    ) throws Exception {

        ContentResolver resolver =
                getContentResolver();


        try (
                InputStream input =
                        resolver.openInputStream(uri)
        ) {

            if (input == null) {

                throw new Exception(
                        "Cannot open SRT file."
                );
            }


            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();


            byte[] buffer =
                    new byte[8192];


            int read;


            while (
                    (read = input.read(buffer))
                            != -1
            ) {

                output.write(
                        buffer,
                        0,
                        read
                );
            }


            byte[] bytes =
                    output.toByteArray();


            if (
                    bytes.length >= 3
                            &&
                            (
                                    bytes[0] & 0xFF
                            ) == 0xEF
                            &&
                            (
                                    bytes[1] & 0xFF
                            ) == 0xBB
                            &&
                            (
                                    bytes[2] & 0xFF
                            ) == 0xBF
            ) {

                return new String(
                        bytes,
                        StandardCharsets.UTF_8
                );
            }


            if (
                    bytes.length >= 2
                            &&
                            (
                                    bytes[0] & 0xFF
                            ) == 0xFF
                            &&
                            (
                                    bytes[1] & 0xFF
                            ) == 0xFE
            ) {

                return new String(
                        bytes,
                        Charset.forName(
                                "UTF-16LE"
                        )
                );
            }


            if (
                    bytes.length >= 2
                            &&
                            (
                                    bytes[0] & 0xFF
                            ) == 0xFE
                            &&
                            (
                                    bytes[1] & 0xFF
                            ) == 0xFF
            ) {

                return new String(
                        bytes,
                        Charset.forName(
                                "UTF-16BE"
                        )
                );
            }


            return new String(
                    bytes,
                    StandardCharsets.UTF_8
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
                String textColorString,
                String outlineColorString,
                String backgroundColorString,
                String effect,
                String position,
                int fontSize,
                int outlineWidth,
                boolean backgroundEnabled,
                boolean bold
        ) {

            super(true);

            this.cues =
                    cues;

            this.textColor =
                    safeColor(
                            textColorString,
                            Color.WHITE
                    );

            this.outlineColor =
                    safeColor(
                            outlineColorString,
                            Color.BLACK
                    );

            this.backgroundColor =
                    safeColor(
                            backgroundColorString,
                            Color.BLACK
                    );

            this.effect =
                    effect == null
                            ? "none"
                            : effect;

            this.position =
                    position == null
                            ? "bottom"
                            : position;

            this.fontSize =
                    Math.max(
                            18,
                            Math.min(
                                    120,
                                    fontSize
                            )
                    );

            this.outlineWidth =
                    Math.max(
                            0,
                            Math.min(
                                    20,
                                    outlineWidth
                            )
                    );

            this.backgroundEnabled =
                    backgroundEnabled;

            this.bold =
                    bold;
        }


        // --------------------------------------------------------
        // IMPORTANT:
        // DO NOT override configure(Size)
        // DO NOT override setCanvasSize(Size)
        //
        // Media3 CanvasOverlay automatically uses input frame size
        // because super(true) is used.
        // --------------------------------------------------------

        @Override
        public void onDraw(
                Canvas canvas,
                long presentationTimeUs
        ) {

            if (canvas == null) {
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


            float slideOffset =
                    calculateSlideOffset(
                            activeCue,
                            timeMs
                    );


            int width =
                    canvas.getWidth();

            int height =
                    canvas.getHeight();


            if (
                    width <= 0
                            ||
                            height <= 0
            ) {
                return;
            }


            float actualFontSize =
                    fontSize;


            // Scale text for large video frames.
            float scaleFactor =
                    Math.max(
                            1f,
                            width / 720f
                    );


            actualFontSize *=
                    scaleFactor;


            TextPaint fillPaint =
                    createTextPaint(
                            actualFontSize,
                            textColor,
                            alpha,
                            bold
                    );


            TextPaint strokePaint =
                    createTextPaint(
                            actualFontSize,
                            outlineColor,
                            alpha,
                            bold
                    );


            strokePaint.setStyle(
                    Paint.Style.STROKE
            );

            strokePaint.setStrokeWidth(
                    outlineWidth * scaleFactor
            );

            strokePaint.setStrokeJoin(
                    Paint.Join.ROUND
            );


            fillPaint.setStyle(
                    Paint.Style.FILL
            );


            int layoutWidth =
                    Math.max(
                            200,
                            (int) (
                                    width * 0.86f
                            )
                    );


            StaticLayout fillLayout =
                    createLayout(
                            activeCue.text,
                            fillPaint,
                            layoutWidth
                    );


            StaticLayout strokeLayout =
                    createLayout(
                            activeCue.text,
                            strokePaint,
                            layoutWidth
                    );


            float x =
                    (
                            width
                                    -
                                    layoutWidth
                    ) / 2f;


            float y;


            if (
                    "top".equalsIgnoreCase(
                            position
                    )
            ) {

                y =
                        height * 0.08f;

            } else if (
                    "center".equalsIgnoreCase(
                            position
                    )
            ) {

                y =
                        (
                                height
                                        -
                                        fillLayout.getHeight()
                        ) / 2f;

            } else {

                y =
                        height
                                -
                                fillLayout.getHeight()
                                -
                                height * 0.08f;
            }


            y += slideOffset;


            float padding =
                    16f * scaleFactor;


            canvas.save();


            float centerX =
                    width / 2f;


            float centerY =
                    y +
                            fillLayout.getHeight()
                                    /
                                    2f;


            canvas.translate(
                    centerX,
                    centerY
            );


            canvas.scale(
                    scale,
                    scale
            );


            canvas.translate(
                    -centerX,
                    -centerY
            );


            if (backgroundEnabled) {

                float boxLeft =
                        x - padding;

                float boxTop =
                        y - padding * 0.65f;

                float boxRight =
                        x
                                +
                                layoutWidth
                                +
                                padding;

                float boxBottom =
                        y
                                +
                                fillLayout.getHeight()
                                +
                                padding * 0.65f;


                Paint bgPaint =
                        new Paint(
                                Paint.ANTI_ALIAS_FLAG
                        );


                bgPaint.setStyle(
                        Paint.Style.FILL
                );


                bgPaint.setColor(
                        withAlpha(
                                backgroundColor,
                                alpha * 0.72f
                        )
                );


                RectF rect =
                        new RectF(
                                boxLeft,
                                boxTop,
                                boxRight,
                                boxBottom
                        );


                canvas.drawRoundRect(
                        rect,
                        18f * scaleFactor,
                        18f * scaleFactor,
                        bgPaint
                );
            }


            canvas.save();

            canvas.translate(
                    x,
                    y
            );


            // Outline first.
            if (outlineWidth > 0) {

                strokeLayout.draw(
                        canvas
                );
            }


            // Main text.
            fillLayout.draw(
                    canvas
            );


            canvas.restore();


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
                                &&
                                timeMs < cue.endMs
                ) {

                    return cue;
                }


                if (cue.startMs > timeMs) {
                    break;
                }
            }

            return null;
        }


        // --------------------------------------------------------
        // EFFECT ALPHA
        // --------------------------------------------------------

        private float calculateAlpha(
                SrtCue cue,
                long timeMs
        ) {

            if (
                    "none".equalsIgnoreCase(
                            effect
                    )
            ) {
                return 1f;
            }


            float duration =
                    cue.endMs
                            -
                            cue.startMs;


            float elapsed =
                    timeMs
                            -
                            cue.startMs;


            float remaining =
                    cue.endMs
                            -
                            timeMs;


            float fadeDuration =
                    Math.min(
                            180f,
                            duration / 4f
                    );


            float in =
                    elapsed / fadeDuration;


            float out =
                    remaining / fadeDuration;


            return clamp(
                    Math.min(
                            in,
                            out
                    ),
                    0f,
                    1f
            );
        }


        // --------------------------------------------------------
        // EFFECT SCALE
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


            float elapsed =
                    timeMs
                            -
                            cue.startMs;


            float duration =
                    cue.endMs
                            -
                            cue.startMs;


            float t =
                    clamp(
                            elapsed / 220f,
                            0f,
                            1f
                    );


            // Ease out.
            float eased =
                    1f -
                            (1f - t)
                                    *
                                    (1f - t);


            return
                    0.82f
                            +
                            0.18f * eased;
        }


        // --------------------------------------------------------
        // SLIDE EFFECT
        // --------------------------------------------------------

        private float calculateSlideOffset(
                SrtCue cue,
                long timeMs
        ) {

            if (
                    !"slide".equalsIgnoreCase(
                            effect
                    )
            ) {
                return 0f;
            }


            float elapsed =
                    timeMs
                            -
                            cue.startMs;


            float t =
                    clamp(
                            elapsed / 240f,
                            0f,
                            1f
                    );


            float eased =
                    1f -
                            (1f - t)
                                    *
                                    (1f - t);


            return
                    40f
                            * (1f - eased);
        }


        // --------------------------------------------------------
        // TEXT PAINT
        // --------------------------------------------------------

        private TextPaint createTextPaint(
                float size,
                int color,
                float alpha,
                boolean bold
        ) {

            TextPaint paint =
                    new TextPaint(
                            Paint.ANTI_ALIAS_FLAG
                                    |
                                    Paint.SUBPIXEL_TEXT_FLAG
                                    |
                                    Paint.LINEAR_TEXT_FLAG
                    );


            paint.setTextSize(
                    size
            );


            paint.setColor(
                    withAlpha(
                            color,
                            alpha
                    )
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


            paint.setAntiAlias(
                    true
            );


            return paint;
        }


        // --------------------------------------------------------
        // STATIC LAYOUT
        // --------------------------------------------------------

        private StaticLayout createLayout(
                String text,
                TextPaint paint,
                int width
        ) {

            return new StaticLayout(
                    text,
                    paint,
                    width,
                    Layout.Alignment.ALIGN_CENTER,
                    1.0f,
                    8f,
                    false
            );
        }


        // --------------------------------------------------------
        // SAFE COLOR
        // --------------------------------------------------------

        private static int safeColor(
                String value,
                int fallback
        ) {

            try {

                if (
                        value == null
                                ||
                                value.trim().isEmpty()
                ) {

                    return fallback;
                }


                return Color.parseColor(
                        value
                );

            } catch (Exception e) {

                return fallback;
            }
        }


        // --------------------------------------------------------
        // COLOR ALPHA
        // --------------------------------------------------------

        private static int withAlpha(
                int color,
                float alpha
        ) {

            int a =
                    Math.round(
                            Color.alpha(color)
                                    *
                                    clamp(
                                            alpha,
                                            0f,
                                            1f
                                    )
                    );


            return Color.argb(
                    a,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
            );
        }


        // --------------------------------------------------------
        // CLAMP
        // --------------------------------------------------------

        private static float clamp(
                float value,
                float min,
                float max
        ) {

            return Math.max(
                    min,
                    Math.min(
                            max,
                            value
                    )
            );
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
    // AUTO SAVE VIDEO
    // ============================================================

    private Uri autoSaveVideo(
            File source,
            String fileName
    ) throws Exception {

        if (
                source == null
                        ||
                        !source.exists()
        ) {

            throw new Exception(
                    "Output file does not exist."
            );
        }


        // --------------------------------------------------------
        // Android 10+
        // --------------------------------------------------------

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q) {


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
                            +
                            "/Media Toolkit"
            );


            values.put(
                    MediaStore.Video.Media.IS_PENDING,
                    1
            );


            Uri uri =
                    resolver.insert(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            values
                    );


            if (uri == null) {

                throw new Exception(
                        "MediaStore insert failed."
                );
            }


            boolean success =
                    false;


            try {

                OutputStream output =
                        resolver.openOutputStream(
                                uri
                        );


                if (output == null) {

                    throw new Exception(
                            "Cannot open output stream."
                    );
                }


                try (
                        InputStream input =
                                new FileInputStream(
                                        source
                                );
                        OutputStream out =
                                output
                ) {

                    copyStream(
                            input,
                            out
                    );
                }


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


                success = true;


                return uri;

            } finally {

                if (!success) {

                    resolver.delete(
                            uri,
                            null,
                            null
                    );
                }
            }
        }


        // --------------------------------------------------------
        // Android 9 and below
        // --------------------------------------------------------

        File moviesDir =
                new File(
                        getExternalFilesDir(
                                Environment.DIRECTORY_MOVIES
                        ),
                        "Media Toolkit"
                );


        if (!moviesDir.exists()) {
            moviesDir.mkdirs();
        }


        File destination =
                new File(
                        moviesDir,
                        fileName
                );


        try (
                InputStream input =
                        new FileInputStream(
                                source
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


        return Uri.fromFile(
                destination
        );
    }


    // ============================================================
    // COPY STREAM
    // ============================================================

    private void copyStream(
            InputStream input,
            OutputStream output
    ) throws Exception {

        byte[] buffer =
                new byte[1024 * 64];


        int read;


        while (
                (read = input.read(buffer))
                        != -1
        ) {

            output.write(
                    buffer,
                    0,
                    read
            );
        }


        output.flush();
    }


    // ============================================================
    // GET URI SIZE
    // ============================================================

    private long getUriSize(
            Uri uri
    ) {

        Cursor cursor = null;


        try {

            cursor =
                    getContentResolver()
                            .query(
                                    uri,
                                    new String[]{
                                            OpenableColumns.SIZE
                                    },
                                    null,
                                    null,
                                    null
                            );


            if (
                    cursor != null
                            &&
                            cursor.moveToFirst()
            ) {

                int index =
                        cursor.getColumnIndex(
                                OpenableColumns.SIZE
                        );


                if (index >= 0) {

                    return cursor.getLong(
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


        return 0;
    }


    // ============================================================
    // GET FILE NAME
    // ============================================================

    private String getFileName(
            Uri uri
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
                            &&
                            cursor.moveToFirst()
            ) {

                int index =
                        cursor.getColumnIndex(
                                OpenableColumns.DISPLAY_NAME
                        );


                if (index >= 0) {

                    String name =
                            cursor.getString(
                                    index
                            );


                    if (
                            name != null
                                    &&
                                    !name.isEmpty()
                    ) {

                        return name;
                    }
                }
            }

        } catch (Exception ignored) {

        } finally {

            if (cursor != null) {
                cursor.close();
            }
        }


        String fallback =
                uri.getLastPathSegment();


        return fallback != null
                ? fallback
                : "Selected file";
    }


    // ============================================================
    // JAVASCRIPT CALL
    // ============================================================

    private void sendJs(
            String javascript
    ) {

        runOnUiThread(() -> {

            if (
                    webView != null
                            &&
                            javascript != null
            ) {

                webView.evaluateJavascript(
                        javascript,
                        null
                );
            }
        });
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


        return "\"" +
                value
                        .replace(
                                "\\",
                                "\\\\"
                        )
                        .replace(
                                "\"",
                                "\\\""
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
                                "\u2028",
                                "\\u2028"
                        )
                        .replace(
                                "\u2029",
                                "\\u2029"
                        ) +
                "\"";
    }


    // ============================================================
    // ERROR MESSAGE
    // ============================================================

    private String getSafeError(
            Throwable throwable
    ) {

        if (throwable == null) {
            return "Unknown error";
        }


        String message =
                throwable.getMessage();


        if (
                message == null
                        ||
                        message.trim().isEmpty()
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

            if (webView != null) {

                webView.stopLoading();

                webView.removeJavascriptInterface(
                        "Android"
                );

                webView.destroy();

                webView = null;
            }

        } catch (Exception ignored) {
        }


        super.onDestroy();
    }
    }
