package com.mytranscriber.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.CanvasOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.AudioEncoderSettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Size;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@UnstableApi
public class MainActivity extends AppCompatActivity {

    /* =========================================================
       REQUEST CODES
    ========================================================= */

    private static final int PICK_COMPRESS_FILE = 2001;
    private static final int SAVE_COMPRESSED_FILE = 3001;
    private static final int WEB_FILE_PICKER_REQUEST = 4001;

    private static final int PICK_SUBTITLE_VIDEO = 5001;
    private static final int PICK_SRT_FILE = 5002;


    /* =========================================================
       WEBVIEW
    ========================================================= */

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;


    /* =========================================================
       COMPRESSOR
    ========================================================= */

    private Uri selectedUri;
    private File compressedFile;
    private long originalFileSize;

    private Transformer transformer;

    private final ProgressHolder progressHolder =
            new ProgressHolder();

    private final Handler handler =
            new Handler();

    private boolean compressionRunning = false;


    /* =========================================================
       SUBTITLE EDITOR
    ========================================================= */

    private Uri subtitleVideoUri;
    private Uri subtitleSrtUri;

    private File subtitleTempOutput;

    private Transformer subtitleTransformer;

    private boolean subtitleExportRunning = false;

    private final ProgressHolder subtitleProgressHolder =
            new ProgressHolder();


    /* =========================================================
       SRT MODEL
    ========================================================= */

    private static class SubtitleCue {

        long startUs;
        long endUs;
        String text;

        SubtitleCue(
                long startUs,
                long endUs,
                String text
        ) {
            this.startUs = startUs;
            this.endUs = endUs;
            this.text = text;
        }
    }


    /* =========================================================
       SUBTITLE SETTINGS
    ========================================================= */

    private String subtitleTextColor = "#FFFFFF";
    private String subtitleOutlineColor = "#000000";
    private String subtitleBackgroundColor = "#000000";

    private int subtitleFontSize = 52;

    private int subtitleOutlineWidth = 4;

    private boolean subtitleBold = true;

    private boolean subtitleShadow = true;

    private boolean subtitleBackground = false;

    private String subtitlePosition = "bottom";

    private String subtitleEffect = "none";


    /* =========================================================
       ON CREATE
    ========================================================= */

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {

        super.onCreate(savedInstanceState);

        webView = new WebView(this);

        WebSettings settings =
                webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(
                new WebViewClient()
        );

        webView.setWebChromeClient(
                new WebChromeClient() {

                    @Override
                    public boolean onShowFileChooser(
                            WebView webView,
                            ValueCallback<Uri[]> callback,
                            FileChooserParams params
                    ) {

                        if (
                                MainActivity.this
                                        .filePathCallback != null
                        ) {

                            MainActivity.this
                                    .filePathCallback
                                    .onReceiveValue(null);
                        }

                        MainActivity.this
                                .filePathCallback =
                                callback;

                        try {

                            Intent intent =
                                    params.createIntent();

                            startActivityForResult(
                                    intent,
                                    WEB_FILE_PICKER_REQUEST
                            );

                        } catch (Exception e) {

                            MainActivity.this
                                    .filePathCallback =
                                    null;

                            return false;
                        }

                        return true;
                    }
                }
        );

        webView.addJavascriptInterface(
                new AndroidBridge(),
                "Android"
        );

        setContentView(webView);

        webView.loadUrl(
                "file:///android_asset/index.html"
        );
    }


    /* =========================================================
       JAVASCRIPT BRIDGE
    ========================================================= */

    public class AndroidBridge {


        /* =====================================================
           COMPRESS
        ===================================================== */

        @JavascriptInterface
        public void compressMedia(
                String fileName,
                String mimeType,
                long fileSize
        ) {

            if (compressionRunning) {
                return;
            }

            openFilePicker();
        }


        @JavascriptInterface
        public void saveCompressed() {

            if (
                    compressedFile == null ||
                    !compressedFile.exists()
            ) {

                runJavascript(
                        "window.compressionFailed(" +
                        "'Compressed file မရှိသေးပါ။'" +
                        ");"
                );

                return;
            }

            Intent intent =
                    new Intent(
                            Intent.ACTION_CREATE_DOCUMENT
                    );

            intent.addCategory(
                    Intent.CATEGORY_OPENABLE
            );

            intent.setType("audio/mp4");

            intent.putExtra(
                    Intent.EXTRA_TITLE,
                    "compressed_audio.mp4"
            );

            startActivityForResult(
                    intent,
                    SAVE_COMPRESSED_FILE
            );
        }


        /* =====================================================
           SUBTITLE VIDEO
        ===================================================== */

        @JavascriptInterface
        public void pickSubtitleVideo() {

            Intent intent =
                    new Intent(
                            Intent.ACTION_OPEN_DOCUMENT
                    );

            intent.addCategory(
                    Intent.CATEGORY_OPENABLE
            );

            intent.setType("video/*");

            startActivityForResult(
                    intent,
                    PICK_SUBTITLE_VIDEO
            );
        }


        /* =====================================================
           SRT
        ===================================================== */

        @JavascriptInterface
        public void pickSrtFile() {

            Intent intent =
                    new Intent(
                            Intent.ACTION_OPEN_DOCUMENT
                    );

            intent.addCategory(
                    Intent.CATEGORY_OPENABLE
            );

            intent.setType("text/*");

            startActivityForResult(
                    intent,
                    PICK_SRT_FILE
            );
        }


        /* =====================================================
           SUBTITLE EXPORT
        ===================================================== */

        @JavascriptInterface
        public void exportSubtitle(
                String textColor,
                String outlineColor,
                String backgroundColor,
                int fontSize,
                int outlineWidth,
                boolean bold,
                boolean shadow,
                boolean background,
                String position,
                String effect
        ) {

            if (subtitleExportRunning) {
                return;
            }

            subtitleTextColor =
                    safeColor(
                            textColor,
                            "#FFFFFF"
                    );

            subtitleOutlineColor =
                    safeColor(
                            outlineColor,
                            "#000000"
                    );

            subtitleBackgroundColor =
                    safeColor(
                            backgroundColor,
                            "#000000"
                    );

            subtitleFontSize =
                    Math.max(
                            20,
                            Math.min(
                                    120,
                                    fontSize
                            )
                    );

            subtitleOutlineWidth =
                    Math.max(
                            0,
                            Math.min(
                                    15,
                                    outlineWidth
                            )
                    );

            subtitleBold =
                    bold;

            subtitleShadow =
                    shadow;

            subtitleBackground =
                    background;

            subtitlePosition =
                    position == null
                            ? "bottom"
                            : position;

            subtitleEffect =
                    effect == null
                            ? "none"
                            : effect;

            startSubtitleExport();
        }
    }


    /* =========================================================
       FILE PICKER
    ========================================================= */

    private void openFilePicker() {

        Intent intent =
                new Intent(
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
    }


    /* =========================================================
       ACTIVITY RESULT
    ========================================================= */

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );


        /* =====================================================
           WEB FILE PICKER
        ===================================================== */

        if (
                requestCode ==
                WEB_FILE_PICKER_REQUEST
        ) {

            if (
                    filePathCallback ==
                    null
            ) {
                return;
            }

            Uri[] results = null;

            if (
                    resultCode ==
                            Activity.RESULT_OK &&
                    data != null
            ) {

                Uri uri =
                        data.getData();

                if (uri != null) {

                    results =
                            new Uri[]{
                                    uri
                            };
                }
            }

            filePathCallback
                    .onReceiveValue(
                            results
                    );

            filePathCallback = null;

            return;
        }


        /* =====================================================
           CANCEL
        ===================================================== */

        if (
                resultCode !=
                        Activity.RESULT_OK ||
                data == null
        ) {

            return;
        }


        /* =====================================================
           COMPRESS FILE
        ===================================================== */

        if (
                requestCode ==
                PICK_COMPRESS_FILE
        ) {

            selectedUri =
                    data.getData();

            if (
                    selectedUri ==
                            null
            ) {
                return;
            }

            startCompression();

            return;
        }


        /* =====================================================
           SAVE COMPRESSED
        ===================================================== */

        if (
                requestCode ==
                SAVE_COMPRESSED_FILE
        ) {

            Uri destinationUri =
                    data.getData();

            if (
                    destinationUri != null
            ) {

                saveFile(
                        destinationUri
                );
            }

            return;
        }


        /* =====================================================
           SUBTITLE VIDEO
        ===================================================== */

        if (
                requestCode ==
                PICK_SUBTITLE_VIDEO
        ) {

            subtitleVideoUri =
                    data.getData();

            if (
                    subtitleVideoUri !=
                            null
            ) {

                runJavascript(
                        "window.subtitleVideoSelected(" +
                        "'" +
                        escapeJs(
                                getFileName(
                                        subtitleVideoUri
                                )
                        ) +
                        "');"
                );
            }

            return;
        }


        /* =====================================================
           SRT
        ===================================================== */

        if (
                requestCode ==
                PICK_SRT_FILE
        ) {

            subtitleSrtUri =
                    data.getData();

            if (
                    subtitleSrtUri !=
                            null
            ) {

                runJavascript(
                        "window.subtitleSrtSelected(" +
                        "'" +
                        escapeJs(
                                getFileName(
                                        subtitleSrtUri
                                )
                        ) +
                        "');"
                );
            }
        }
    }


    /* =========================================================
       SUBTITLE EXPORT
    ========================================================= */

    private void startSubtitleExport() {

        if (
                subtitleVideoUri ==
                        null
        ) {

            runJavascript(
                    "window.subtitleFailed(" +
                    "'Video file ရွေးပါ။'" +
                    ");"
            );

            return;
        }

        if (
                subtitleSrtUri ==
                        null
        ) {

            runJavascript(
                    "window.subtitleFailed(" +
                    "'SRT file ရွေးပါ။'" +
                    ");"
            );

            return;
        }


        try {

            final List<SubtitleCue> cues =
                    parseSrt(
                            subtitleSrtUri
                    );

            if (
                    cues.isEmpty()
            ) {

                runJavascript(
                        "window.subtitleFailed(" +
                        "'SRT ထဲမှာ subtitle မတွေ့ပါ။'" +
                        ");"
                );

                return;
            }


            subtitleExportRunning =
                    true;


            runJavascript(
                    "window.subtitleProgress(" +
                    "1," +
                    "'SRT ဖတ်ပြီးပါပြီ...'" +
                    ");"
            );


            File directory =
                    new File(
                            getExternalFilesDir(
                                    Environment.DIRECTORY_MOVIES
                            ),
                            "subtitle_temp"
                    );

            if (
                    !directory.exists()
            ) {

                directory.mkdirs();
            }


            subtitleTempOutput =
                    new File(
                            directory,
                            "subtitle_temp_" +
                            System.currentTimeMillis() +
                            ".mp4"
                    );


            MediaItem mediaItem =
                    MediaItem.fromUri(
                            subtitleVideoUri
                    );


            TextureOverlay subtitleOverlay =
                    createSubtitleOverlay(
                            cues
                    );


            OverlayEffect overlayEffect =
                    new OverlayEffect(
                            Collections.singletonList(
                                    subtitleOverlay
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


            DefaultEncoderFactory encoderFactory =
                    new DefaultEncoderFactory.Builder(
                            this
                    )
                    .setEnableFallback(true)
                    .build();


            subtitleTransformer =
                    new Transformer.Builder(
                            this
                    )
                    .setEncoderFactory(
                            encoderFactory
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
                                        @NonNull
                                        Composition composition,
                                        @NonNull
                                        ExportResult result
                                ) {

                                    subtitleExportCompleted();
                                }


                                @Override
                                public void onError(
                                        @NonNull
                                        Composition composition,
                                        @NonNull
                                        ExportResult result,
                                        @NonNull
                                        ExportException exception
                                ) {

                                    subtitleExportFailed(
                                            exception.getMessage()
                                    );
                                }
                            }
                    )
                    .build();


            subtitleTransformer.start(
                    editedMediaItem,
                    subtitleTempOutput
                            .getAbsolutePath()
            );


            startSubtitleProgressMonitor();


        } catch (Exception e) {

            subtitleExportFailed(
                    e.getMessage()
            );
        }
    }


    /* =========================================================
       CREATE SUBTITLE OVERLAY
    ========================================================= */

    private TextureOverlay createSubtitleOverlay(
            List<SubtitleCue> cues
    ) {

        return new CanvasOverlay(true) {

            private int canvasWidth = 0;
            private int canvasHeight = 0;


            @Override
            public void configure(
                    Size videoSize
            ) {

                super.configure(
                        videoSize
                );

                canvasWidth =
                        videoSize.getWidth();

                canvasHeight =
                        videoSize.getHeight();
            }


            @Override
            public void onDraw(
                    Canvas canvas,
                    long presentationTimeUs
            ) {

                SubtitleCue activeCue =
                        findActiveCue(
                                cues,
                                presentationTimeUs
                        );

                if (
                        activeCue ==
                                null
                ) {

                    return;
                }


                String text =
                        activeCue.text;

                if (
                        TextUtils.isEmpty(
                                text
                        )
                ) {

                    return;
                }


                float time =
                        presentationTimeUs /
                        1_000_000f;

                float start =
                        activeCue.startUs /
                        1_000_000f;

                float end =
                        activeCue.endUs /
                        1_000_000f;

                float relative =
                        (
                                time - start
                        ) /
                        Math.max(
                                0.001f,
                                end - start
                        );


                /* =============================================
                   EFFECT
                ============================================= */

                float alpha = 1f;
                float scale = 1f;
                float effectOffsetY = 0f;


                if (
                        "fade".equalsIgnoreCase(
                                subtitleEffect
                        )
                ) {

                    float fadeTime =
                            Math.min(
                                    0.18f,
                                    (
                                            end - start
                                    ) / 2f
                            );

                    if (
                            time - start <
                            fadeTime
                    ) {

                        alpha =
                                Math.max(
                                        0f,
                                        (
                                                time -
                                                start
                                        ) /
                                        fadeTime
                                );
                    }

                    else if (
                            end - time <
                            fadeTime
                    ) {

                        alpha =
                                Math.max(
                                        0f,
                                        (
                                                end -
                                                time
                                        ) /
                                        fadeTime
                                );
                    }
                }


                if (
                        "pop".equalsIgnoreCase(
                                subtitleEffect
                        )
                ) {

                    float p =
                            Math.min(
                                    1f,
                                    relative * 5f
                            );

                    scale =
                            0.78f +
                            (
                                    0.22f *
                                    p
                            );
                }


                if (
                        "slide".equalsIgnoreCase(
                                subtitleEffect
                        )
                ) {

                    float p =
                            Math.min(
                                    1f,
                                    relative * 5f
                            );

                    effectOffsetY =
                            70f *
                            (1f - p);
                }


                canvas.save();


                /* =============================================
                   POSITION
                ============================================= */

                float centerX =
                        canvasWidth /
                        2f;

                float baseY;


                if (
                        "top".equalsIgnoreCase(
                                subtitlePosition
                        )
                ) {

                    baseY =
                            canvasHeight *
                            0.16f;

                } else if (
                        "center".equalsIgnoreCase(
                                subtitlePosition
                        )
                ) {

                    baseY =
                            canvasHeight *
                            0.50f;

                } else {

                    baseY =
                            canvasHeight *
                            0.86f;
                }


                baseY -=
                        effectOffsetY;


                canvas.translate(
                        centerX,
                        baseY
                );


                canvas.scale(
                        scale,
                        scale
                );


                /* =============================================
                   PAINT
                ============================================= */

                TextPaint textPaint =
                        new TextPaint(
                                Paint.ANTI_ALIAS_FLAG |
                                Paint.SUBPIXEL_TEXT_FLAG
                        );


                textPaint.setTextSize(
                        subtitleFontSize
                );


                textPaint.setColor(
                        Color.parseColor(
                                subtitleTextColor
                        )
                );


                textPaint.setTypeface(
                        Typeface.create(
                                "sans-serif",
                                subtitleBold
                                        ?
                                        Typeface.BOLD
                                        :
                                        Typeface.NORMAL
                        )
                );


                textPaint.setTextAlign(
                        Paint.Align.CENTER
                );


                if (
                        subtitleShadow
                ) {

                    textPaint.setShadowLayer(
                            8f,
                            2f,
                            3f,
                            Color.BLACK
                    );
                }


                float maxWidth =
                        canvasWidth *
                        0.88f;


                StaticLayout layout =
                        new StaticLayout(
                                text,
                                textPaint,
                                (int) maxWidth,
                                Layout.Alignment.ALIGN_CENTER,
                                1.0f,
                                0f,
                                false
                        );


                float textWidth =
                        layout.getWidth();

                float textHeight =
                        layout.getHeight();


                /* =============================================
                   BACKGROUND
                ============================================= */

                if (
                        subtitleBackground
                ) {

                    Paint backgroundPaint =
                            new Paint(
                                    Paint.ANTI_ALIAS_FLAG
                            );

                    backgroundPaint.setColor(
                            Color.parseColor(
                                    subtitleBackgroundColor
                            )
                    );


                    float paddingX =
                            22f;

                    float paddingY =
                            12f;


                    RectF rect =
                            new RectF(
                                    -textWidth / 2f -
                                            paddingX,

                                    -textHeight / 2f -
                                            paddingY,

                                    textWidth / 2f +
                                            paddingX,

                                    textHeight / 2f +
                                            paddingY
                            );


                    canvas.drawRoundRect(
                            rect,
                            18f,
                            18f,
                            backgroundPaint
                    );
                }


                /* =============================================
                   OUTLINE
                ============================================= */

                if (
                        subtitleOutlineWidth >
                        0
                ) {

                    TextPaint outlinePaint =
                            new TextPaint(
                                    Paint.ANTI_ALIAS_FLAG |
                                    Paint.SUBPIXEL_TEXT_FLAG
                            );


                    outlinePaint.setTextSize(
                            subtitleFontSize
                    );


                    outlinePaint.setTypeface(
                            Typeface.create(
                                    "sans-serif",
                                    subtitleBold
                                            ?
                                            Typeface.BOLD
                                            :
                                            Typeface.NORMAL
                            )
                    );


                    outlinePaint.setColor(
                            Color.parseColor(
                                    subtitleOutlineColor
                            )
                    );


                    outlinePaint.setStyle(
                            Paint.Style.STROKE
                    );


                    outlinePaint.setStrokeWidth(
                            subtitleOutlineWidth
                    );


                    StaticLayout outlineLayout =
                            new StaticLayout(
                                    text,
                                    outlinePaint,
                                    (int) maxWidth,
                                    Layout.Alignment.ALIGN_CENTER,
                                    1.0f,
                                    0f,
                                    false
                            );


                    canvas.save();

                    canvas.translate(
                            -outlineLayout.getWidth()
                                    / 2f,
                            -outlineLayout.getHeight()
                                    / 2f
                    );

                    outlineLayout.draw(
                            canvas
                    );

                    canvas.restore();
                }


                /* =============================================
                   TEXT
                ============================================= */

                textPaint.setAlpha(
                        Math.round(
                                255f *
                                Math.max(
                                        0f,
                                        Math.min(
                                                1f,
                                                alpha
                                        )
                                )
                        )
                );


                canvas.save();

                canvas.translate(
                        -layout.getWidth()
                                / 2f,

                        -layout.getHeight()
                                / 2f
                );


                layout.draw(
                        canvas
                );


                canvas.restore();


                canvas.restore();
            }
        };
    }


    /* =========================================================
       FIND ACTIVE CUE
    ========================================================= */

    private SubtitleCue findActiveCue(
            List<SubtitleCue> cues,
            long timeUs
    ) {

        for (
                SubtitleCue cue :
                cues
        ) {

            if (
                    timeUs >= cue.startUs &&
                    timeUs <= cue.endUs
            ) {

                return cue;
            }
        }

        return null;
    }


    /* =========================================================
       PARSE SRT
    ========================================================= */

    private List<SubtitleCue> parseSrt(
            Uri uri
    ) throws Exception {

        List<SubtitleCue> result =
                new ArrayList<>();


        InputStream input =
                getContentResolver()
                        .openInputStream(
                                uri
                        );


        if (
                input == null
        ) {

            return result;
        }


        BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                input,
                                StandardCharsets.UTF_8
                        )
                );


        List<String> block =
                new ArrayList<>();


        String line;


        while (
                (line =
                        reader.readLine())
                        != null
        ) {

            if (
                    line.length() > 0 &&
                    line.charAt(0) == '\uFEFF'
            ) {

                line =
                        line.substring(1);
            }


            if (
                    line.trim().isEmpty()
            ) {

                addSrtBlock(
                        block,
                        result
                );

                block.clear();

            } else {

                block.add(line);
            }
        }


        addSrtBlock(
                block,
                result
        );


        reader.close();
        input.close();


        return result;
    }


    private void addSrtBlock(
            List<String> block,
            List<SubtitleCue> result
    ) {

        if (
                block.size() < 2
        ) {

            return;
        }


        String timing = null;
        int textStart = 1;


        for (
                int i = 0;
                i < block.size();
                i++
        ) {

            if (
                    block.get(i)
                            .contains("-->")
            ) {

                timing =
                        block.get(i);

                textStart =
                        i + 1;

                break;
            }
        }


        if (
                timing == null
        ) {

            return;
        }


        String[] parts =
                timing.split(
                        "-->"
                );


        if (
                parts.length < 2
        ) {

            return;
        }


        long startUs =
                parseSrtTime(
                        parts[0].trim()
                );


        long endUs =
                parseSrtTime(
                        parts[1].trim()
                );


        StringBuilder text =
                new StringBuilder();


        for (
                int i = textStart;
                i < block.size();
                i++
        ) {

            if (
                    text.length() > 0
            ) {

                text.append(
                        "\n"
                );
            }

            text.append(
                    block.get(i)
            );
        }


        String subtitleText =
                text.toString().trim();


        if (
                endUs > startUs &&
                !subtitleText.isEmpty()
        ) {

            result.add(
                    new SubtitleCue(
                            startUs,
                            endUs,
                            subtitleText
                    )
            );
        }
    }


    /* =========================================================
       SRT TIME
    ========================================================= */

    private long parseSrtTime(
            String value
    ) {

        try {

            String clean =
                    value.trim()
                            .replace(
                                    ',',
                                    '.'
                            );


            String[] parts =
                    clean.split(
                            ":"
                    );


            if (
                    parts.length != 3
            ) {

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


            String[] secParts =
                    parts[2].split(
                            "\\."
                    );


            long seconds =
                    Long.parseLong(
                            secParts[0]
                    );


            long millis = 0;


            if (
                    secParts.length > 1
            ) {

                String ms =
                        secParts[1];


                if (
                        ms.length() == 1
                ) {

                    ms += "00";

                } else if (
                        ms.length() == 2
                ) {

                    ms += "0";
                }


                if (
                        ms.length() > 3
                ) {

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


            return (
                    (
                            hours * 3600 +
                            minutes * 60 +
                            seconds
                    ) * 1_000_000L
            ) +
                    millis *
                    1_000L;


        } catch (Exception e) {

            return 0;
        }
    }


    /* =========================================================
       SUBTITLE PROGRESS
    ========================================================= */

    private void startSubtitleProgressMonitor() {

        handler.postDelayed(
                new Runnable() {

                    @Override
                    public void run() {

                        if (
                                !subtitleExportRunning ||
                                subtitleTransformer ==
                                        null
                        ) {

                            return;
                        }


                        try {

                            int state =
                                    subtitleTransformer
                                            .getProgress(
                                                    subtitleProgressHolder
                                            );


                            int percent =
                                    subtitleProgressHolder
                                            .progress;


                            if (
                                    state ==
                                            Transformer
                                                    .PROGRESS_STATE_AVAILABLE
                            ) {

                                runJavascript(
                                        "window.subtitleProgress(" +
                                        percent +
                                        ",'Rendering subtitle " +
                                        percent +
                                        "%...');"
                                );
                            }


                            handler.postDelayed(
                                    this,
                                    500
                            );


                        } catch (
                                Exception ignored
                        ) {
                        }
                    }
                },
                500
        );
    }


    /* =========================================================
       SUBTITLE COMPLETE
    ========================================================= */

    private void subtitleExportCompleted() {

        subtitleExportRunning =
                false;


        runJavascript(
                "window.subtitleProgress(" +
                "100," +
                "'Saving video...'" +
                ");"
        );


        new Thread(
                () -> {

                    try {

                        Uri savedUri =
                                saveSubtitleToMovies(
                                        subtitleTempOutput
                                );


                        runOnUiThread(
                                () -> {

                                    if (
                                            savedUri !=
                                                    null
                                    ) {

                                        runJavascript(
                                                "window.subtitleFinished(" +
                                                "'" +
                                                escapeJs(
                                                        savedUri.toString()
                                                ) +
                                                "');"
                                        );

                                    } else {

                                        subtitleExportFailed(
                                                "Video save failed"
                                        );
                                    }
                                }
                        );


                    } catch (
                            Exception e
                    ) {

                        runOnUiThread(
                                () ->
                                        subtitleExportFailed(
                                                e.getMessage()
                                        )
                        );
                    }

                }
        ).start();
    }


    /* =========================================================
       SAVE SUBTITLE TO MOVIES
    ========================================================= */

    private Uri saveSubtitleToMovies(
            File source
    ) throws Exception {

        if (
                source == null ||
                !source.exists()
        ) {

            return null;
        }


        String time =
                new SimpleDateFormat(
                        "yyyyMMdd_HHmmss",
                        Locale.US
                )
                .format(
                        new Date()
                );


        String fileName =
                "subtitled_" +
                time +
                ".mp4";


        if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
        ) {

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
                    Environment.DIRECTORY_MOVIES +
                    "/MediaToolkit"
            );


            values.put(
                    MediaStore.Video.Media.IS_PENDING,
                    1
            );


            Uri uri =
                    getContentResolver()
                            .insert(
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                    values
                            );


            if (
                    uri == null
            ) {

                return null;
            }


            try {

                OutputStream output =
                        getContentResolver()
                                .openOutputStream(
                                        uri
                                );


                if (
                        output == null
                ) {

                    throw new Exception(
                            "Output stream unavailable"
                    );
                }


                copyFile(
                        source,
                        output
                );


                output.close();


                ContentValues done =
                        new ContentValues();


                done.put(
                        MediaStore.Video.Media.IS_PENDING,
                        0
                );


                getContentResolver()
                        .update(
                                uri,
                                done,
                                null,
                                null
                        );


                return uri;


            } catch (
                    Exception e
            ) {

                getContentResolver()
                        .delete(
                                uri,
                                null,
                                null
                        );

                throw e;
            }

        } else {

            File movies =
                    Environment
                            .getExternalStoragePublicDirectory(
                                    Environment
                                            .DIRECTORY_MOVIES
                            );


            File directory =
                    new File(
                            movies,
                            "MediaToolkit"
                    );


            if (
                    !directory.exists()
            ) {

                directory.mkdirs();
            }


            File destination =
                    new File(
                            directory,
                            fileName
                    );


            FileOutputStream output =
                    new FileOutputStream(
                            destination
                    );


            copyFile(
                    source,
                    output
            );


            output.close();


            return Uri.fromFile(
                    destination
            );
        }
    }


    /* =========================================================
       COPY FILE
    ========================================================= */

    private void copyFile(
            File source,
            OutputStream output
    ) throws Exception {

        InputStream input =
                new FileInputStream(
                        source
                );


        byte[] buffer =
                new byte[1024 * 64];


        int length;


        while (
                (
                        length =
                                input.read(
                                        buffer
                                )
                ) > 0
        ) {

            output.write(
                    buffer,
                    0,
                    length
            );
        }


        output.flush();
        input.close();
    }


    /* =========================================================
       SUBTITLE ERROR
    ========================================================= */

    private void subtitleExportFailed(
            String message
    ) {

        subtitleExportRunning =
                false;


        if (
                message == null ||
                message.isEmpty()
        ) {

            message =
                    "Subtitle export failed";
        }


        runJavascript(
                "window.subtitleFailed('" +
                escapeJs(
                        message
                ) +
                "');"
        );
    }


    /* =========================================================
       COLOR
    ========================================================= */

    private String safeColor(
            String value,
            String fallback
    ) {

        if (
                value == null
        ) {

            return fallback;
        }


        try {

            Color.parseColor(
                    value
            );

            return value;

        } catch (
                Exception e
        ) {

            return fallback;
        }
    }


    /* =========================================================
       COMPRESSOR
    ========================================================= */

    private void startCompression() {

        try {

            originalFileSize =
                    getFileSize(
                            selectedUri
                    );

            if (
                    originalFileSize <= 0
            ) {

                runJavascript(
                        "window.compressionFailed(" +
                        "'File size မဖတ်နိုင်ပါ။'" +
                        ");"
                );

                return;
            }


            compressionRunning =
                    true;


            runJavascript(
                    "window.compressionProgress(" +
                    "1," +
                    "'Compression စတင်နေပါပြီ...'" +
                    ");"
            );


            File outputDirectory =
                    new File(
                            getExternalFilesDir(null),
                            "compressed"
                    );


            if (
                    !outputDirectory.exists()
            ) {

                outputDirectory.mkdirs();
            }


            compressedFile =
                    new File(
                            outputDirectory,
                            "compressed_" +
                            System.currentTimeMillis() +
                            ".mp4"
                    );


            AudioEncoderSettings audioSettings =
                    new AudioEncoderSettings.Builder()
                            .setBitrate(
                                    64_000
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


            transformer =
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
                                        @NonNull
                                        Composition composition,
                                        @NonNull
                                        ExportResult result
                                ) {

                                    compressionCompleted();
                                }


                                @Override
                                public void onError(
                                        @NonNull
                                        Composition composition,
                                        @NonNull
                                        ExportResult result,
                                        @NonNull
                                        ExportException exception
                                ) {

                                    compressionFailed(
                                            exception.getMessage()
                                    );
                                }
                            }
                    )
                    .build();


            MediaItem mediaItem =
                    MediaItem.fromUri(
                            selectedUri
                    );


            EditedMediaItem editedMediaItem =
                    new EditedMediaItem.Builder(
                            mediaItem
                    )
                    .setRemoveVideo(
                            true
                    )
                    .build();


            transformer.start(
                    editedMediaItem,
                    compressedFile
                            .getAbsolutePath()
            );


            startProgressMonitor();


        } catch (
                Exception e
        ) {

            compressionFailed(
                    e.getMessage()
            );
        }
    }


    /* =========================================================
       COMPRESS PROGRESS
    ========================================================= */

    private void startProgressMonitor() {

        handler.postDelayed(
                new Runnable() {

                    @Override
                    public void run() {

                        if (
                                !compressionRunning ||
                                transformer == null
                        ) {

                            return;
                        }


                        try {

                            int state =
                                    transformer.getProgress(
                                            progressHolder
                                    );


                            int percent =
                                    progressHolder
                                            .progress;


                            if (
                                    state ==
                                            Transformer
                                                    .PROGRESS_STATE_AVAILABLE
                            ) {

                                runJavascript(
                                        "window.compressionProgress(" +
                                        percent +
                                        ",'Compressing " +
                                        percent +
                                        "%...');"
                                );
                            }


                            handler.postDelayed(
                                    this,
                                    500
                            );


                        } catch (
                                Exception ignored
                        ) {
                        }
                    }
                },
                500
        );
    }


    /* =========================================================
       COMPRESS COMPLETE
    ========================================================= */

    private void compressionCompleted() {

        compressionRunning =
                false;


        long outputSize = 0;


        if (
                compressedFile != null &&
                compressedFile.exists()
        ) {

            outputSize =
                    compressedFile.length();
        }


        runJavascript(
                "window.compressionFinished(" +
                "'" +
                escapeJs(
                        compressedFile
                                .getAbsolutePath()
                ) +
                "'," +
                originalFileSize +
                "," +
                outputSize +
                ");"
        );
    }


    /* =========================================================
       COMPRESS ERROR
    ========================================================= */

    private void compressionFailed(
            String message
    ) {

        compressionRunning =
                false;


        if (
                message == null ||
                message.isEmpty()
        ) {

            message =
                    "Compression failed";
        }


        runJavascript(
                "window.compressionFailed('" +
                escapeJs(
                        message
                ) +
                "');"
        );
    }


    /* =========================================================
       SAVE COMPRESSED
    ========================================================= */

    private void saveFile(
            Uri destinationUri
    ) {

        try {

            InputStream input =
                    new FileInputStream(
                            compressedFile
                    );


            OutputStream output =
                    getContentResolver()
                            .openOutputStream(
                                    destinationUri
                            );


            if (
                    output == null
            ) {

                input.close();

                return;
            }


            byte[] buffer =
                    new byte[8192];


            int length;


            while (
                    (
                            length =
                                    input.read(
                                            buffer
                                    )
                    ) > 0
            ) {

                output.write(
                        buffer,
                        0,
                        length
                );
            }


            output.flush();

            output.close();
            input.close();


            runJavascript(
                    "window.compressionProgress(" +
                    "100," +
                    "'File သိမ်းပြီးပါပြီ ✓'" +
                    ");"
            );


        } catch (
                Exception e
        ) {

            runJavascript(
                    "window.compressionFailed('" +
                    escapeJs(
                            e.getMessage()
                    ) +
                    "');"
            );
        }
    }


    /* =========================================================
       FILE SIZE
    ========================================================= */

    private long getFileSize(
            Uri uri
    ) {

        try {

            android.database.Cursor cursor =
                    getContentResolver()
                            .query(
                                    uri,
                                    null,
                                    null,
                                    null,
                                    null
                            );


            if (
                    cursor != null
            ) {

                int sizeIndex =
                        cursor.getColumnIndex(
                                android.provider
                                        .OpenableColumns.SIZE
                        );


                if (
                        cursor.moveToFirst() &&
                        sizeIndex >= 0
                ) {

                    long size =
                            cursor.getLong(
                                    sizeIndex
                            );

                    cursor.close();

                    return size;
                }


                cursor.close();
            }

        } catch (
                Exception ignored
        ) {
        }


        return 0;
    }


    /* =========================================================
       FILE NAME
    ========================================================= */

    private String getFileName(
            Uri uri
    ) {

        if (
                uri == null
        ) {

            return "Unknown file";
        }


        android.database.Cursor cursor =
                getContentResolver()
                        .query(
                                uri,
                                null,
                                null,
                                null,
                                null
                        );


        if (
                cursor != null
        ) {

            int index =
                    cursor.getColumnIndex(
                            android.provider
                                    .OpenableColumns.DISPLAY_NAME
                    );


            if (
                    cursor.moveToFirst() &&
                    index >= 0
            ) {

                String name =
                        cursor.getString(
                                index
                        );

                cursor.close();

                return name;
            }


            cursor.close();
        }


        return uri.getLastPathSegment();
    }


    /* =========================================================
       JS
    ========================================================= */

    private void runJavascript(
            String javascript
    ) {

        runOnUiThread(
                () -> {

                    if (
                            webView != null
                    ) {

                        webView.evaluateJavascript(
                                javascript,
                                null
                        );
                    }
                }
        );
    }


    /* =========================================================
       ESCAPE JS
    ========================================================= */

    private String escapeJs(
            String value
    ) {

        if (
                value == null
        ) {

            return "";
        }


        return value
                .replace(
                        "\\",
                        "\\\\"
                )
                .replace(
                        "'",
                        "\\'"
                )
                .replace(
                        "\n",
                        "\\n"
                )
                .replace(
                        "\r",
                        "\\r"
                );
    }
                    }
