package com.chipchap.app.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.graphics.Matrix;
import android.util.Base64;

import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Local, app-owned storage for complaint evidence photos.
 *
 * Photos are written to the app's own external-files directory (or internal files as a fallback),
 * so no storage permission is needed and nothing lands in the user's shared gallery. Firebase
 * Storage is deliberately not used in this phase, and no image bytes ever reach Firestore — only
 * the absolute local path is stored on the report document.
 *
 * The WebView cannot reliably render a file:// image from a file:///android_asset page, so a
 * bounded, downscaled JPEG data URI is generated for preview purposes only. That preview is never
 * persisted anywhere.
 */
public class LocalPhotoRepository {

    private static final String PHOTO_DIRECTORY = "complaint_photos";
    private static final String FILE_PROVIDER_SUFFIX = ".fileprovider";

    /** Preview is display-only; bounded so a large capture cannot exhaust memory. */
    private static final int PREVIEW_MAX_EDGE_PX = 1200;
    private static final int PREVIEW_JPEG_QUALITY = 75;

    private final Context context;

    public LocalPhotoRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Creates an empty, uniquely named file for the camera to write into. */
    public File createPhotoFile() throws IOException {
        File directory = photoDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create a photo folder in the app's storage.");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File file = new File(directory, "chipchap_" + stamp + ".jpg");
        if (!file.createNewFile()) {
            throw new IOException("Could not create a photo file in the app's storage.");
        }
        return file;
    }

    /** Content URI handed to the camera app, so it can write the full-resolution image. */
    public Uri contentUriFor(File file) {
        return FileProvider.getUriForFile(context, context.getPackageName() + FILE_PROVIDER_SUFFIX, file);
    }

    /** Longest edge kept when a rotation rewrite is needed; bounded so a large capture cannot OOM. */
    private static final int ROTATED_MAX_EDGE_PX = 3000;
    private static final int ROTATED_JPEG_QUALITY = 92;

    /**
     * Rewrites the captured file so the pixels themselves are upright.
     *
     * Most camera apps write the sensor image plus an EXIF orientation tag rather than rotating the
     * pixels. The WebView preview and any later consumer that ignores EXIF then shows a portrait
     * photo on its side. Rotating only the preview would leave the stored evidence wrong, so the
     * file on disk is what gets corrected here, and the EXIF tag is reset to NORMAL afterwards so
     * nothing rotates it a second time.
     *
     * Returns true when the file was rewritten. A failure is non-fatal: the original file is left
     * untouched, with its EXIF tag intact.
     */
    public boolean normalizeOrientation(File file) {
        if (!isUsable(file)) return false;
        int degrees;
        boolean flipHorizontal = false;
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: degrees = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: degrees = 0; flipHorizontal = true; break;
                case ExifInterface.ORIENTATION_TRANSPOSE: degrees = 90; flipHorizontal = true; break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: degrees = 180; flipHorizontal = true; break;
                case ExifInterface.ORIENTATION_TRANSVERSE: degrees = 270; flipHorizontal = true; break;
                default: return false;
            }
        } catch (Exception error) {
            return false;
        }

        Bitmap source = null;
        Bitmap rotated = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = rotationSampleSize(bounds.outWidth, bounds.outHeight);
            source = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (source == null) return false;

            Matrix matrix = new Matrix();
            if (degrees != 0) matrix.postRotate(degrees);
            if (flipHorizontal) matrix.postScale(-1f, 1f);
            rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
            if (rotated == null) return false;

            FileOutputStream out = new FileOutputStream(file);
            try {
                if (!rotated.compress(Bitmap.CompressFormat.JPEG, ROTATED_JPEG_QUALITY, out)) return false;
            } finally {
                out.close();
            }

            // The pixels are upright now, so the tag must not rotate them again.
            ExifInterface written = new ExifInterface(file.getAbsolutePath());
            written.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            written.saveAttributes();
            return true;
        } catch (Exception | OutOfMemoryError error) {
            return false;
        } finally {
            if (rotated != null && !rotated.isRecycled() && rotated != source) rotated.recycle();
            if (source != null && !source.isRecycled()) source.recycle();
        }
    }

    private static int rotationSampleSize(int width, int height) {
        int sampleSize = 1;
        while (Math.max(width, height) / sampleSize > ROTATED_MAX_EDGE_PX) sampleSize *= 2;
        return sampleSize;
    }

    /** A capture only counts if the camera actually wrote bytes into the file. */
    public boolean isUsable(File file) {
        return file != null && file.exists() && file.isFile() && file.length() > 0L;
    }

    public void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /**
     * Builds a downscaled JPEG data URI for the existing Stitch preview.
     * Returns an empty string when the photo cannot be decoded; callers must not fake a preview.
     */
    public String buildPreviewDataUri(File file) {
        return buildPreviewDataUri(file, PREVIEW_MAX_EDGE_PX);
    }

    /**
     * Same as {@link #buildPreviewDataUri(File)} with a caller-chosen bound, so list cards can use
     * a small thumbnail instead of a full-size preview.
     */
    public String buildPreviewDataUri(File file, int maxEdgePx) {
        if (!isUsable(file)) return "";
        final int maxEdge = maxEdgePx > 0 ? maxEdgePx : PREVIEW_MAX_EDGE_PX;
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "";

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge);
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null) return "";

            int longestEdge = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (longestEdge > maxEdge) {
                float scale = maxEdge / (float) longestEdge;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, out);
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception | OutOfMemoryError error) {
            return "";
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    /**
     * True only for a usable file inside this app's own evidence folder. A report document carries
     * a device path as plain text, so a path read back from Firestore is never trusted to point
     * anywhere else on the device.
     */
    public boolean isAppPhoto(File file) {
        if (!isUsable(file)) return false;
        try {
            String path = file.getCanonicalPath();
            File external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            File[] roots = {
                    external == null ? null : new File(external, PHOTO_DIRECTORY),
                    new File(context.getFilesDir(), PHOTO_DIRECTORY)
            };
            for (File root : roots) {
                if (root != null && path.startsWith(root.getCanonicalPath() + File.separator)) return true;
            }
        } catch (IOException error) {
            return false;
        }
        return false;
    }

    private File photoDirectory() {
        File external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File base = external != null ? external : context.getFilesDir();
        return new File(base, PHOTO_DIRECTORY);
    }

    private static int sampleSizeFor(int width, int height, int maxEdge) {
        int sampleSize = 1;
        while (Math.max(width, height) / sampleSize > maxEdge * 2) {
            sampleSize *= 2;
        }
        return sampleSize;
    }
}
