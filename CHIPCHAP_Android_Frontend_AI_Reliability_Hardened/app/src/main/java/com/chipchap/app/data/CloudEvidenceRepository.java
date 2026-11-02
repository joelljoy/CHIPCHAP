package com.chipchap.app.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.chipchap.app.R;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Uploads a local evidence JPEG to Cloudinary and returns its HTTPS delivery URL.
 *
 * <p><b>Why a direct HTTP call rather than the Cloudinary Android SDK.</b> This app has no
 * networking dependency at all - only firebase-auth, firebase-firestore, androidx.core and
 * androidx.exifinterface. An unsigned Cloudinary upload is a single multipart POST, which
 * {@link HttpURLConnection} performs in a few dozen lines. Adding the SDK would pull a transitive
 * networking stack into the APK for one request, so the whole upload is kept here instead. All
 * Cloudinary knowledge lives in this class; nothing else in the app knows the endpoint or the
 * request shape.
 *
 * <p><b>Security.</b> Uploads are UNSIGNED. The cloud name and upload preset are client-safe
 * public values held in res/values/cloudinary.xml. There is no api_key and no api_secret in this
 * app, and a signature is never computed on the device. The destination asset folder is configured
 * on the preset in the Cloudinary console, so no folder parameter is sent - unsigned presets
 * reject folder overrides unless explicitly allowed.
 *
 * <p>Only an {@code https://res.cloudinary.com/...} URL is ever returned. Anything else is treated
 * as a failure, so a hijacked or unexpected response cannot put an arbitrary URL into Firestore.
 */
public class CloudEvidenceRepository {

    /** One upload attempt. Exactly one method is called, always on the main thread. */
    public interface UploadCallback {
        void onUploaded(String secureUrl);
        void onFailed(String message);
    }

    private static final String ENDPOINT = "https://api.cloudinary.com/v1_1/%s/image/upload";
    private static final String REQUIRED_URL_PREFIX = "https://res.cloudinary.com/";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;
    /** Refuse absurd files rather than spending a minute uploading them. */
    private static final long MAX_UPLOAD_BYTES = 12L * 1024L * 1024L;

    private final Context context;
    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final ExecutorService uploads = Executors.newSingleThreadExecutor();
    private final String cloudName;
    private final String uploadPreset;

    public CloudEvidenceRepository(Context context) {
        this.context = context.getApplicationContext();
        this.cloudName = this.context.getString(R.string.cloudinary_cloud_name).trim();
        this.uploadPreset = this.context.getString(R.string.cloudinary_upload_preset).trim();
    }

    /** False when the build carries no Cloudinary configuration; callers then stay local-only. */
    public boolean isConfigured() {
        return !cloudName.isEmpty() && !uploadPreset.isEmpty();
    }

    /**
     * Uploads the file on a background thread. The local file is never modified or deleted, so the
     * existing local-photo fallback survives a failed upload untouched.
     */
    public void upload(final File file, final UploadCallback callback) {
        if (!isConfigured()) {
            post(callback, null, "Cloud evidence storage is not configured in this build.");
            return;
        }
        if (file == null || !file.exists() || !file.isFile() || file.length() == 0L) {
            post(callback, null, "The photo file could not be read for upload.");
            return;
        }
        if (file.length() > MAX_UPLOAD_BYTES) {
            post(callback, null, "The photo is too large to upload.");
            return;
        }
        uploads.execute(() -> {
            try {
                String url = uploadBlocking(file);
                if (url == null || !url.startsWith(REQUIRED_URL_PREFIX)) {
                    post(callback, null, "The upload did not return a usable image URL.");
                } else {
                    post(callback, url, null);
                }
            } catch (Exception error) {
                post(callback, null, describe(error));
            }
        });
    }

    private String uploadBlocking(File file) throws IOException {
        String boundary = "----chipchap" + System.currentTimeMillis();
        String lineEnd = "\r\n";
        String twoHyphens = "--";

        byte[] head = (twoHyphens + boundary + lineEnd
                + "Content-Disposition: form-data; name=\"upload_preset\"" + lineEnd + lineEnd
                + uploadPreset + lineEnd
                + twoHyphens + boundary + lineEnd
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"" + lineEnd
                + "Content-Type: image/jpeg" + lineEnd + lineEnd).getBytes(StandardCharsets.UTF_8);
        byte[] tail = (lineEnd + twoHyphens + boundary + twoHyphens + lineEnd).getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URL(
                String.format(ENDPOINT, cloudName)).openConnection();
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        // Streamed in fixed-length mode so a large photo is never buffered whole in memory.
        connection.setFixedLengthStreamingMode(head.length + file.length() + tail.length);

        try {
            DataOutputStream out = new DataOutputStream(connection.getOutputStream());
            try {
                out.write(head);
                FileInputStream in = new FileInputStream(file);
                try {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                } finally {
                    in.close();
                }
                out.write(tail);
                out.flush();
            } finally {
                out.close();
            }

            int status = connection.getResponseCode();
            String body = readAll(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new IOException("Cloudinary returned HTTP " + status + ". " + shortError(body));
            }
            return new JSONObject(body).optString("secure_url", "");
        } catch (org.json.JSONException error) {
            throw new IOException("The upload response could not be read.");
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
            return builder.toString();
        } finally {
            reader.close();
        }
    }

    /** Surfaces Cloudinary's own message when it sends one, rather than a generic failure. */
    private static String shortError(String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            String message = error == null ? "" : error.optString("message", "");
            return message.isEmpty() ? "" : message;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "The photo could not be uploaded.";
        return message.trim();
    }

    private void post(final UploadCallback callback, final String url, final String failure) {
        if (callback == null) return;
        mainThread.post(() -> {
            if (failure == null) callback.onUploaded(url);
            else callback.onFailed(failure);
        });
    }
}
