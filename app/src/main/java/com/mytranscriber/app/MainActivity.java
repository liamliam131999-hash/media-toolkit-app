package com.mytranscriber.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.AudioEncoderSettings;
import androidx.media3.transformer.Composition;
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

@UnstableApi
public class MainActivity extends AppCompatActivity {

    private static final int PICK_COMPRESS_FILE = 2001;
    private static final int SAVE_COMPRESSED_FILE = 3001;

    private WebView webView;

    private Uri selectedUri;
    private File compressedFile;
    private long originalFileSize;

    private Transformer transformer;
    private final ProgressHolder progressHolder = new ProgressHolder();
    private final Handler handler = new Handler();

    private boolean compressionRunning = false;

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

        webView.setWebViewClient(new WebViewClient());

        webView.addJavascriptInterface(
                new AndroidBridge(),
                "Android"
        );

        setContentView(webView);

        webView.loadUrl(
                "file:///android_asset/index.html"
        );
    }

    public class AndroidBridge {

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

            if (compressedFile == null ||
                    !compressedFile.exists()) {

                runJavascript(
                        "window.compressionFailed(" +
                        "'Compressed file မရှိသေးပါ။'"
                        + ");"
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
    }

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

        if (resultCode != Activity.RESULT_OK ||
                data == null) {

            return;
        }

        if (requestCode == PICK_COMPRESS_FILE) {

            selectedUri = data.getData();

            if (selectedUri == null) {
                return;
            }

            startCompression();

        } else if (
                requestCode == SAVE_COMPRESSED_FILE
        ) {

            Uri destinationUri = data.getData();

            if (destinationUri != null) {
                saveFile(destinationUri);
            }
        }
    }

    private void startCompression() {

        try {

            originalFileSize =
                    getFileSize(selectedUri);

            if (originalFileSize <= 0) {

                runJavascript(
                        "window.compressionFailed(" +
                        "'File size မဖတ်နိုင်ပါ။'"
                        + ");"
                );

                return;
            }

            compressionRunning = true;

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

            if (!outputDirectory.exists()) {
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
                            .setBitrate(64_000)
                            .build();

            DefaultEncoderFactory encoderFactory =
                    new DefaultEncoderFactory.Builder(this)
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
                    .setRemoveVideo(true)
                    .build();

            transformer.start(
                    editedMediaItem,
                    compressedFile.getAbsolutePath()
            );

            startProgressMonitor();

        } catch (Exception e) {

            compressionFailed(
                    e.getMessage()
            );
        }
    }

    private void startProgressMonitor() {

        handler.postDelayed(
                new Runnable() {

                    @Override
                    public void run() {

                        if (!compressionRunning ||
                                transformer == null) {

                            return;
                        }

                        try {

                            int state =
                                    transformer.getProgress(
                                            progressHolder
                                    );

                            int percent =
                                    progressHolder.progress;

                            if (state ==
                                    Transformer.PROGRESS_STATE_AVAILABLE) {

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

                        } catch (Exception ignored) {
                        }
                    }
                },
                500
        );
    }

    private void compressionCompleted() {

        compressionRunning = false;

        long outputSize = 0;

        if (compressedFile != null &&
                compressedFile.exists()) {

            outputSize =
                    compressedFile.length();
        }

        final long finalOutputSize = outputSize;

        runJavascript(
                "window.compressionFinished(" +
                "'" +
                escapeJs(
                        compressedFile.getAbsolutePath()
                ) +
                "'," +
                originalFileSize +
                "," +
                finalOutputSize +
                ");"
        );
    }

    private void compressionFailed(
            String message
    ) {

        compressionRunning = false;

        if (message == null ||
                message.isEmpty()) {

            message =
                    "Compression failed";
        }

        runJavascript(
                "window.compressionFailed('" +
                escapeJs(message) +
                "');"
        );
    }

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

            if (output == null) {

                input.close();

                return;
            }

            byte[] buffer =
                    new byte[8192];

            int length;

            while (
                    (length =
                            input.read(buffer))
                            > 0
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

        } catch (Exception e) {

            runJavascript(
                    "window.compressionFailed('" +
                    escapeJs(
                            e.getMessage()
                    ) +
                    "');"
            );
        }
    }

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

            if (cursor != null) {

                int sizeIndex =
                        cursor.getColumnIndex(
                                android.provider.OpenableColumns.SIZE
                        );

                if (cursor.moveToFirst() &&
                        sizeIndex >= 0) {

                    long size =
                            cursor.getLong(
                                    sizeIndex
                            );

                    cursor.close();

                    return size;
                }

                cursor.close();
            }

        } catch (Exception ignored) {
        }

        return 0;
    }

    private void runJavascript(
            String javascript
    ) {

        runOnUiThread(
                () -> {

                    if (webView != null) {

                        webView.evaluateJavascript(
                                javascript,
                                null
                        );
                    }
                }
        );
    }

    private String escapeJs(
            String value
    ) {

        if (value == null) {
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
