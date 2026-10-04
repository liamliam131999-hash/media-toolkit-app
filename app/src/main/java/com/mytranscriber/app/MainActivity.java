package com.mytranscriber.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.AudioEncoderSettings;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import java.text.SimpleDateFormat;

import java.util.Date;
import java.util.Locale;


public class MainActivity extends Activity {

    private WebView webView;

    private static final int PICK_COMPRESS_FILE = 5001;
    private static final int SAVE_OUTPUT_FILE = 5002;

    private Uri selectedUri;

    private long originalFileSize = 0;

    private File compressedFile;

    private Transformer transformer;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private boolean compressionRunning = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setupWebView();
    }


    private void setupWebView() {

        webView = new WebView(this);

        setContentView(webView);

        WebSettings settings =
                webView.getSettings();

        settings.setJavaScriptEnabled(true);

        settings.setDomStorageEnabled(true);

        settings.setAllowFileAccess(true);

        settings.setAllowContentAccess(true);

        settings.setMediaPlaybackRequiresUserGesture(false);

        settings.setBuiltInZoomControls(false);

        settings.setDisplayZoomControls(false);

        webView.setWebViewClient(
                new WebViewClient()
        );

        webView.setWebChromeClient(
                new WebChromeClient()
        );


        webView.addJavascriptInterface(
                new AndroidBridge(),
                "Android"
        );


        webView.loadUrl(
                "file:///android_asset/index.html"
        );
    }


    /*
     * ==========================================
     * ANDROID BRIDGE
     * ==========================================
     */

    public class AndroidBridge {


        @JavascriptInterface
        public void compressMedia(
                String fileName,
                String mimeType,
                long fileSize
        ) {

            runOnUiThread(() -> {

                if (compressionRunning) {

                    sendFailure(
                            "Compression တစ်ခု အလုပ်လုပ်နေပြီးသားပါ။"
                    );

                    return;
                }


                /*
                 * WebView File object ကို Native Android
                 * က တိုက်ရိုက်မရနိုင်တဲ့အတွက် Native picker
                 * ကို ဖွင့်ပေးမယ်။
                 *
                 * User က Compress နှိပ်တဲ့အချိန်
                 * file တစ်ခါပြန်ရွေးရပါမယ်။
                 */

                openNativeFilePicker();

            });
        }


        @JavascriptInterface
        public void saveCompressed() {

            runOnUiThread(() -> {

                if (
                        compressedFile == null ||
                        !compressedFile.exists()
                ) {

                    sendFailure(
                            "Save လုပ်စရာ compressed output မရှိသေးပါ။"
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

                intent.setType(
                        "video/mp4"
                );


                String fileName =
                        compressedFile.getName();


                intent.putExtra(
                        Intent.EXTRA_TITLE,
                        fileName
                );


                startActivityForResult(
                        intent,
                        SAVE_OUTPUT_FILE
                );

            });
        }
    }


    /*
     * ==========================================
     * NATIVE FILE PICKER
     * ==========================================
     */

    private void openNativeFilePicker() {

        Intent intent =
                new Intent(
                        Intent.ACTION_OPEN_DOCUMENT
                );

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType(
                "*/*"
        );

        intent.putExtra(
                Intent.EXTRA_MIME_TYPES,
                new String[] {
                        "audio/*",
                        "video/*"
                }
        );


        startActivityForResult(
                intent,
                PICK_COMPRESS_FILE
        );
    }


    /*
     * ==========================================
     * ACTIVITY RESULT
     * ==========================================
     */

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


        if (
                requestCode ==
                PICK_COMPRESS_FILE
        ) {

            if (
                    resultCode != RESULT_OK ||
                    data == null ||
                    data.getData() == null
            ) {

                sendFailure(
                        "File မရွေးရသေးပါ။"
                );

                return;
            }


            selectedUri =
                    data.getData();


            try {

                getContentResolver()
                        .takePersistableUriPermission(
                                selectedUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );

            } catch (Exception ignored) {
            }


            originalFileSize =
                    getFileSize(selectedUri);


            startCompression();

            return;
        }


        if (
                requestCode ==
                SAVE_OUTPUT_FILE
        ) {

            if (
                    resultCode != RESULT_OK ||
                    data == null ||
                    data.getData() == null
            ) {

                return;
            }


            saveOutputToUri(
                    data.getData()
            );
        }
    }


    /*
     * ==========================================
     * START COMPRESSION
     * ==========================================
     */

    private void startCompression() {

        if (selectedUri == null) {

            sendFailure(
                    "Input file မရှိပါ။"
            );

            return;
        }


        compressionRunning = true;


        sendProgress(
                5,
                "Preparing..."
        );


        File outputDir =
                new File(
                        getExternalFilesDir(null),
                        "compressed"
                );


        if (!outputDir.exists()) {

            outputDir.mkdirs();
        }


        String time =
                new SimpleDateFormat(
                        "yyyyMMdd_HHmmss",
                        Locale.US
                ).format(
                        new Date()
                );


        compressedFile =
                new File(
                        outputDir,
                        "compressed_" +
                                time +
                                ".mp4"
                );


        /*
         * AAC 64 kbps is a good general target
         * for size reduction.
         */

        AudioEncoderSettings audioSettings =
                new AudioEncoderSettings.Builder()
                        .setBitrate(64_000)
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
                                            @NonNull
                                            CompositionDummy composition,
                                            @NonNull
                                            ExportResult result
                                    ) {
                                    }

                                    @Override
                                    public void onError(
                                            @NonNull
                                            androidx.media3.transformer.Composition composition,
                                            @NonNull
                                            ExportResult result,
                                            @NonNull
                                            ExportException exception
                                    ) {

                                        compressionRunning =
                                                false;

                                        sendFailure(
                                                exception.getMessage()
                                        );
                                    }
                                }
                        )

                        .build();


        /*
         * Input MediaItem
         */

        MediaItem mediaItem =
                MediaItem.fromUri(
                        selectedUri
                );


        /*
         * Remove video.
         *
         * This means the compressor produces
         * audio-only MP4.
         *
         * This is intentionally fast and gives
         * much smaller files for video inputs.
         */

        EditedMediaItem editedMediaItem =
                new EditedMediaItem.Builder(
                        mediaItem
                )
                        .setRemoveVideo(true)
                        .build();


        try {

            transformer.start(
                    editedMediaItem,
                    compressedFile.getAbsolutePath()
            );

            monitorProgress();

        } catch (Exception e) {

            compressionRunning =
                    false;

            sendFailure(
                    e.getMessage()
            );
        }
    }


    /*
     * ==========================================
     * PROGRESS
     * ==========================================
     */

    private void monitorProgress() {

        if (
                transformer == null ||
                !compressionRunning
        ) {

            return;
        }


        ProgressHolder holder =
                new ProgressHolder();


        int state;


        try {

            state =
                    transformer.getProgress(
                            holder
                    );

        } catch (Exception e) {

            sendFailure(
                    e.getMessage()
            );

            return;
        }


        if (
                state ==
                        Transformer
                                .PROGRESS_STATE_AVAILABLE
        ) {

            int progress =
                    holder.progress;


            sendProgress(
                    progress,
                    "Compressing..."
            );

        } else {

            sendProgress(
                    10,
                    "Processing..."
            );
        }


        handler.postDelayed(
                this::monitorProgress,
                500
        );
    }


    /*
     * ==========================================
     * SEND PROGRESS TO JAVASCRIPT
     * ==========================================
     */

    private void sendProgress(
            int percent,
            String text
    ) {

        if (webView == null) {
            return;
        }


        final int safePercent =
                Math.max(
                        0,
                        Math.min(
                                100,
                                percent
                        )
                );


        runOnUiThread(() -> {

            String safeText =
                    text
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
                                    " "
                            );


            webView.evaluateJavascript(
                    "window.compressionProgress(" +
                            safePercent +
                            ",'" +
                            safeText +
                            "')",
                    null
            );

        });
    }


    /*
     * ==========================================
     * COMPRESSION SUCCESS
     * ==========================================
     */

    private void compressionCompleted() {

        compressionRunning =
                false;


        if (
                compressedFile == null ||
                !compressedFile.exists()
        ) {

            sendFailure(
                    "Output file မတွေ့ပါ။"
            );

            return;
        }


        long outputSize =
                compressedFile.length();


        sendProgress(
                100,
                "Completed"
        );


        runOnUiThread(() -> {

            String outputPath =
                    compressedFile
                            .getAbsolutePath();


            String js =
                    "window.compressionFinished(" +
                            "'" +
                            escapeJs(
                                    outputPath
                            ) +
                            "'," +
                            originalFileSize +
                            "," +
                            outputSize +
                            ")";


            webView.evaluateJavascript(
                    js,
                    null
            );
        });
    }


    /*
     * ==========================================
     * FAILURE
     * ==========================================
     */

    private void sendFailure(
            String message
    ) {

        compressionRunning =
                false;


        if (message == null ||
                message.trim().isEmpty()) {

            message =
                    "Compression failed.";
        }


        final String safe =
                escapeJs(message);


        runOnUiThread(() -> {

            if (webView != null) {

                webView.evaluateJavascript(
                        "window.compressionFailed('" +
                                safe +
                                "')",
                        null
                );
            }

            Toast.makeText(
                    MainActivity.this,
                    message,
                    Toast.LENGTH_LONG
            ).show();

        });
    }


    /*
     * ==========================================
     * FILE SIZE
     * ==========================================
     */

    private long getFileSize(
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
                    cursor != null &&
                    cursor.moveToFirst()
            ) {

                int index =
                        cursor.getColumnIndex(
                                OpenableColumns.SIZE
                        );


                if (index >= 0 &&
                        !cursor.isNull(index)
                ) {

                    return cursor.getLong(index);
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


    /*
     * ==========================================
     * SAVE OUTPUT
     * ==========================================
     */

    private void saveOutputToUri(
            Uri destination
    ) {

        if (
                compressedFile == null ||
                !compressedFile.exists()
        ) {

            sendFailure(
                    "Compressed output မရှိပါ။"
            );

            return;
        }


        new Thread(() -> {

            try {

                InputStream input =
                        new FileInputStream(
                                compressedFile
                        );


                OutputStream output =
                        getContentResolver()
                                .openOutputStream(
                                        destination
                                );


                if (output == null) {

                    throw new Exception(
                            "Output stream မဖွင့်နိုင်ပါ။"
                    );
                }


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


                output.flush();

                output.close();

                input.close();


                runOnUiThread(() -> {

                    Toast.makeText(
                            MainActivity.this,
                            "Output ကို save လုပ်ပြီးပါပြီ။",
                            Toast.LENGTH_LONG
                    ).show();

                });


            } catch (Exception e) {

                sendFailure(
                        "Save failed: " +
                                e.getMessage()
                );
            }

        }).start();
    }


    /*
     * ==========================================
     * JAVASCRIPT ESCAPE
     * ==========================================
     */

    private String escapeJs(
            String text
    ) {

        if (text == null) {
            return "";
        }


        return text
                .replace(
                        "\\",
                        "\\\\"
                )
                .replace(
                        "'",
                        "\\'"
                )
                .replace(
                        "\"",
                        "\\\""
                )
                .replace(
                        "\n",
                        " "
                )
                .replace(
                        "\r",
                        " "
                );
    }


    /*
     * ==========================================
     * WEBVIEW BACK BUTTON
     * ==========================================
     */

    @Override
    public void onBackPressed() {

        if (
                webView != null &&
                webView.canGoBack()
        ) {

            webView.goBack();

        } else {

            super.onBackPressed();
        }
    }
                                    }
