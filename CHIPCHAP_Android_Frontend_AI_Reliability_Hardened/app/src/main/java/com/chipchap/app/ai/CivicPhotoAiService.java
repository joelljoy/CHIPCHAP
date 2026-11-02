package com.chipchap.app.ai;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.ai.FirebaseAI;
import com.google.firebase.ai.GenerativeModel;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.Content;
import com.google.firebase.ai.type.GenerateContentResponse;
import com.google.firebase.ai.type.GenerationConfig;
import com.google.firebase.ai.type.GenerativeBackend;
import com.google.firebase.ai.type.Schema;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * CHIPCHAP's single boundary to Firebase AI Logic (Gemini Developer API backend).
 *
 * AI CHECKPOINT 2: checks whether a captured report photo can serve as evidence of a civic issue
 * and suggests one of CHIPCHAP's existing categories. It is a suitability check only - not fraud,
 * truth or duplicate detection.
 *
 * What is sent: a downscaled JPEG copy of the photo (longest edge 1024 px, made in memory; the
 * original file is only read, never modified) plus the fixed instruction below. Nothing about the
 * citizen - no name, uid, email, location, address, report id or Cloudinary URL - ever reaches
 * this class. No API key exists in the app: Firebase AI Logic holds the Gemini key server-side and
 * App Check (AppCheckSetup) decides which app instances may call it. Results are never stored.
 *
 * The model's JSON is never trusted: {@link #interpret(String)} validates every field natively.
 *
 * REPORT DRAFT: the SAME request also asks for a draft title and description of what is visible, so
 * drafting costs no extra Gemini request. The draft is optional extra output: it is parsed
 * separately and leniently, and a missing or unusable draft can never change the validation result
 * (an ACCEPTED photo stays ACCEPTED). Nothing is stored; the citizen edits and owns the final text.
 */
public class CivicPhotoAiService {

    /** Stable Gemini Flash model served by Firebase AI Logic with the Gemini Developer API. */
    public static final String MODEL_NAME = "gemini-3.8-flash";

    /** Below this the answer is treated as uncertainty - never as approval or as rejection. */
    public static final double MIN_CONFIDENCE = 0.60;
    /** Longest edge of the temporary copy sent for analysis. */
    public static final int AI_IMAGE_MAX_EDGE_PX = 1024;
    public static final int AI_IMAGE_JPEG_QUALITY = 80;
    /** A request with no answer after this long is abandoned and reported as unavailable. */
    public static final long REQUEST_TIMEOUT_MS = 25000L;

    // ---- Bounded automatic retry (AI reliability fix). Only TEMPORARY failures are retried, all
    // ---- inside the same REQUEST_TIMEOUT_MS deadline (it is not extended) and on the same request,
    // ---- so there is still exactly one callback and at most one upload per photo.
    /** Retries for one photo check, whatever the reason. */
    static final int MAX_RETRIES = 2;
    /** A retry is only started when at least this much of the deadline would remain after the wait. */
    static final long MIN_TIME_LEFT_FOR_RETRY_MS = 6000L;
    /** Waits before retry 1 and 2 after a busy/overloaded/5xx answer. */
    static final long[] SERVICE_RETRY_DELAYS_MS = { 1000L, 2500L };
    /** One retry after a dropped connection. */
    static final long NETWORK_RETRY_DELAY_MS = 1500L;
    /** One retry after a 429 that does NOT say the quota is exhausted (Firebase: 429 can mean model capacity). */
    static final long CAPACITY_RETRY_DELAY_MS = 3000L;

    /** CHIPCHAP's existing report categories (details-screen picker; map chips use the first three). */
    public static final List<String> CATEGORIES = Collections.unmodifiableList(
            Arrays.asList("Infrastructure", "Cleanliness", "Safety", "Other"));
    public static final String UNKNOWN = "UNKNOWN";

    /** The details screen's title input allows 120 characters; a "title" longer than this is not a title. */
    static final int MAX_TITLE_CHARS = 100;
    static final int MIN_TITLE_CHARS = 3;
    static final int MAX_DESCRIPTION_CHARS = 500;
    static final int MIN_DESCRIPTION_CHARS = 10;

    private static final int MAX_REASON_CHARS = 200;
    private static final String TAG = "CHIPCHAP-AI";

    /** Fixed instruction. Only the image and this text are sent. */
    static final String PROMPT =
            "You check photos submitted as evidence for CHIPCHAP, a civic issue reporting app in India.\n"
            + "Decide whether this photo can reasonably serve as evidence of a civic or public issue.\n"
            + "VALID examples: potholes, damaged roads, broken footpaths or sidewalks, garbage piles, "
            + "overflowing bins, littering, drainage or sewage problems, waterlogging, damaged public "
            + "infrastructure, broken or missing streetlights, fallen trees or wires, unsafe public "
            + "conditions, other visible civic or environmental problems.\n"
            + "INVALID examples: selfies or portraits where a person is the main subject, food, pets, "
            + "ordinary household objects, screenshots, memes, random unrelated photos, blank, black or "
            + "extremely dark or blurry images, or scenes with no recognizable civic issue.\n"
            + "Do not require a good or professional photo: an ordinary phone photo that shows the issue "
            + "is valid. Do not judge whether the report is true, legal or a duplicate.\n"
            + "If valid, choose the category: Infrastructure (roads, potholes, footpaths, bridges, "
            + "drainage, public structures), Cleanliness (garbage, waste, litter, dirty or unhygienic "
            + "public areas), Safety (broken streetlights, exposed wires, open manholes, fallen trees, "
            + "other public hazards), Other (a civic issue that fits none of these). Use UNKNOWN if the "
            + "photo is invalid or the category cannot be determined.\n"
            + "confidence is how sure you are of your OWN valid decision, whichever way it points, from \n"
            + "0.0 to 1.0. Use a high value (0.8 to 1.0) when you are clearly sure the photo is valid, and an \n"
            + "equally high value when you are clearly sure it is NOT valid. Use a low value only when you \n"
            + "genuinely cannot tell. It is NOT the probability that the photo is valid.\n"
            + "reason is one short plain sentence describing what the photo shows. Do not describe "
            + "or identify any person.\n"
            + "ONLY if the photo is valid, also write a DRAFT report for the citizen to review and edit:\n"
            + "title is a short, specific, plain-language issue title of about 3 to 10 words, with no full stop, "
            + "no quotation marks and no location.\n"
            + "description is 1 to 3 plain sentences, as a citizen could submit in a complaint, saying what is "
            + "visibly wrong, using cautious wording such as \"appears\" or \"may\" when something is not certain.\n"
            + "Use ONLY what can actually be seen in the photo. Do NOT invent or guess street or place names, "
            + "addresses, landmarks, exact locations, measurements or sizes, causes, dates or times, names, people, "
            + "vehicle registration numbers, government departments, or how severe or dangerous the issue is unless "
            + "the photo clearly shows it. Do not describe or identify any person. Do not mention the photo, image "
            + "or camera. No speculation and no exaggeration. Write in English.\n"
            + "If the photo is not valid, leave title and description out.";

    /** How a photo check ended. */
    public enum Outcome {
        /** Suitable civic evidence, confidence >= MIN_CONFIDENCE. */
        ACCEPTED,
        /** Confidently NOT suitable evidence. The photo must not be used. */
        REJECTED,
        /** Technical failure or uncertainty. Never an approval and never a rejection. */
        UNAVAILABLE
    }

    /** Technical reason behind an UNAVAILABLE result. For Logcat and debugging only. */
    public enum FailureKind {
        NETWORK, TIMEOUT, QUOTA_OR_RATE_LIMIT, APP_CHECK_OR_AUTH, INVALID_REQUEST,
        MODEL_OR_SDK, MALFORMED_RESPONSE, UNKNOWN,
        /** The Gemini service answered "busy / overloaded / 5xx": temporary, retried automatically. */
        SERVICE_UNAVAILABLE
    }

    /** A validated result. Nothing here is stored anywhere. */
    public static final class Result {
        public final Outcome outcome;
        /** One of CATEGORIES, or "" when there is no suggestion. */
        public final String suggestedCategory;
        /** Validated model confidence, or -1 when there was no usable answer. */
        public final double confidence;
        /** Short text that is safe to show to the citizen. */
        public final String message;
        /** Developer-only reason code for Logcat. */
        public final String code;
        /** Technical failure category for UNAVAILABLE results; null for ACCEPTED and REJECTED. */
        public final FailureKind kind;
        /** Time from the start of the request to this result, or -1 when not measured. */
        public final long durationMs;
        /** Validated, cleaned DRAFT title; "" unless ACCEPTED and the model gave a usable one. Never stored. */
        public final String draftTitle;
        /** Validated, cleaned DRAFT description; "" unless ACCEPTED and the model gave a usable one. Never stored. */
        public final String draftDescription;

        Result(Outcome outcome, String suggestedCategory, double confidence, String message, String code) {
            this(outcome, suggestedCategory, confidence, message, code, null, -1L);
        }

        Result(Outcome outcome, String suggestedCategory, double confidence, String message, String code,
               FailureKind kind, long durationMs) {
            this(outcome, suggestedCategory, confidence, message, code, kind, durationMs, "", "");
        }

        Result(Outcome outcome, String suggestedCategory, double confidence, String message, String code,
               FailureKind kind, long durationMs, String draftTitle, String draftDescription) {
            this.draftTitle = outcome == Outcome.ACCEPTED && draftTitle != null ? draftTitle : "";
            this.draftDescription = outcome == Outcome.ACCEPTED && draftDescription != null ? draftDescription : "";
            this.outcome = outcome;
            this.suggestedCategory = suggestedCategory == null ? "" : suggestedCategory;
            this.confidence = confidence;
            this.message = message == null ? "" : message;
            this.code = code == null ? "" : code;
            this.kind = kind;
            this.durationMs = durationMs;
        }

        static Result unavailable(String message, String code, FailureKind kind) {
            return new Result(Outcome.UNAVAILABLE, "", -1, message, code, kind, -1L);
        }

        Result withDuration(long ms) {
            return new Result(outcome, suggestedCategory, confidence, message, code, kind, ms, draftTitle, draftDescription);
        }
    }

    /**
     * Result channel. Called exactly once, on the main thread, for every request that runs to an
     * end. It is NEVER called for a request that was superseded by a newer one, cancelled with
     * {@link #cancelActive()}, or still in flight when {@link #shutdown()} was called.
     */
    public interface AnalysisCallback {
        void onResult(Result result);
    }

    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private GenerativeModelFutures model;
    private String initializationError = "";
    /** The one request that may be in flight. Guarded by this. */
    private Request active;
    private boolean shutDown = false;

    /**
     * Creates the Firebase AI Logic model instance, configured for the JSON answer below. Makes no
     * network request. Any failure is recorded and logged, never thrown into the app.
     */
    public synchronized boolean initialize() {
        if (model != null) return true;
        try {
            GenerationConfig.Builder config = new GenerationConfig.Builder();
            config.responseMimeType = "application/json";
            config.responseSchema = responseSchema();
            GenerativeModel generativeModel = FirebaseAI.getInstance(GenerativeBackend.googleAI())
                    .generativeModel(MODEL_NAME, config.build());
            model = GenerativeModelFutures.from(generativeModel);
            initializationError = "";
            Log.i(TAG, "Firebase AI Logic ready: Gemini Developer API, model " + MODEL_NAME
                    + ", App Check provider: " + AppCheckSetup.PROVIDER + ". No request sent.");
            return true;
        } catch (RuntimeException error) {
            model = null;
            initializationError = error.getClass().getSimpleName() + ": " + error.getMessage();
            Log.e(TAG, "Firebase AI Logic could not be initialised: " + initializationError);
            return false;
        }
    }

    /**
     * {valid, category, confidence, reason} are required, exactly as before. {title, description} are
     * OPTIONAL: the model may leave them out (it is told to when the photo is not valid), so a draft
     * can never be what decides whether the answer is usable. Listed after the validity fields so the
     * model decides validity first.
     */
    static Schema responseSchema() {
        List<String> categories = new ArrayList<>(CATEGORIES);
        categories.add(UNKNOWN);
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("valid", booleanSchema());
        properties.put("category", Schema.enumeration(categories));
        properties.put("confidence", Schema.numDouble());
        properties.put("reason", Schema.str());
        properties.put("title", Schema.str());
        properties.put("description", Schema.str());
        return Schema.obj(properties, Arrays.asList("title", "description"));
    }

    /**
     * The SDK's boolean schema. In the Firebase AI SDK this factory is the Kotlin function
     * {@code Schema.boolean()} (a @JvmStatic method literally named "boolean", with no Java alias),
     * and "boolean" is a reserved word in Java, so it cannot be written as a method call here.
     * The SDK's own public static method is therefore invoked by name. If it were ever missing,
     * initialize() fails safely and every photo check reports "could not be checked".
     */
    static Schema booleanSchema() {
        try {
            return (Schema) Schema.class.getMethod("boolean").invoke(null);
        } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException error) {
            throw new IllegalStateException("Firebase AI Schema.boolean() is not available in this SDK version.", error);
        }
    }

    public synchronized boolean isReady() { return model != null; }

    public synchronized String getInitializationError() { return initializationError; }

    public String getModelName() { return MODEL_NAME; }

    /**
     * One analysis request. All state that decides "may a Gemini request still be sent?" lives here
     * and is guarded by {@link #lock}, so a timeout, a newer request, a cancel or a shutdown can
     * never race with the moment the request is handed to the SDK.
     */
    private final class Request {
        final AtomicBoolean done = new AtomicBoolean(false);
        final Object lock = new Object();
        final long startNanos = System.nanoTime();
        final File photo;
        final AnalysisCallback callback;
        volatile Runnable timeoutTask;
        /** Set once the photo copy is built, so a retry re-sends the same content without re-reading the file. */
        volatile Content content;
        volatile GenerativeModelFutures generativeModel;
        /** Automatic retries already started for this request. */
        final java.util.concurrent.atomic.AtomicInteger retries = new java.util.concurrent.atomic.AtomicInteger();
        private ListenableFuture<?> future;   // guarded by lock
        private boolean stopped;              // guarded by lock: no Gemini request may start any more

        Request(File photo, AnalysisCallback callback) {
            this.photo = photo;
            this.callback = callback;
        }

        long elapsedMs() { return (System.nanoTime() - startNanos) / 1000000L; }

        /** After this no Gemini request can start; one already sent is cancelled; the timer is removed. */
        void stop() {
            ListenableFuture<?> f;
            synchronized (lock) {
                stopped = true;
                f = future;
            }
            Runnable t = timeoutTask;
            if (t != null) mainThread.removeCallbacks(t);
            if (f != null) f.cancel(true);
        }

        /** Silent end: the callback is never called. */
        void abandon() {
            done.set(true);
            stop();
        }

        /**
         * Hands the request to Gemini unless it has already finished, timed out, been superseded,
         * cancelled or shut down. The check and the send are one atomic step, so a request that is
         * stopped can never be sent afterwards. Returns null when nothing was sent.
         */
        ListenableFuture<GenerateContentResponse> sendOnce(GenerativeModelFutures generativeModel, Content content) {
            synchronized (lock) {
                if (stopped || done.get()) return null;
                ListenableFuture<GenerateContentResponse> f = generativeModel.generateContent(content);
                future = f;
                return f;
            }
        }
    }

    /**
     * Checks one captured photo. At most ONE request is in flight per service: starting a new one
     * silently ends the previous one. The callback runs exactly once on the main thread, even on
     * timeout, network failure or an SDK exception - except for a superseded, cancelled or
     * shut-down request (see {@link AnalysisCallback}). Nothing is thrown to the caller.
     */
    public void analyzeCivicPhoto(final File photo, final AnalysisCallback callback) {
        if (callback == null) return;
        final Request req = new Request(photo, callback);
        final GenerativeModelFutures current;
        final Request previous;
        synchronized (this) {
            if (shutDown) {
                Log.w(TAG, "AI request ignored: the service was shut down.");
                return;
            }
            current = model;
            previous = active;
            active = req;
        }
        if (previous != null && !previous.done.get()) {
            Log.w(TAG, "AI request superseded: the previous request is ended without a result.");
            previous.abandon();
        }
        if (current == null) {
            deliver(req, Result.unavailable("The AI photo check is not available in this build right now.",
                    "not-initialised " + getInitializationError(), FailureKind.MODEL_OR_SDK));
            return;
        }
        req.timeoutTask = () -> deliver(req,
                Result.unavailable("The photo check took too long.", "timeout", FailureKind.TIMEOUT));
        mainThread.postDelayed(req.timeoutTask, REQUEST_TIMEOUT_MS);
        try {
            worker.execute(() -> runRequest(req, current));
        } catch (RejectedExecutionException error) {
            deliver(req, classifyFailure(error, req.elapsedMs()));
        }
    }

    /** Ends the request in flight, if any, without calling its callback (draft cleared, new session). */
    public void cancelActive() {
        Request r;
        synchronized (this) {
            r = active;
            active = null;
        }
        if (r != null && !r.done.get()) {
            Log.i(TAG, "AI request cancelled by the app.");
            r.abandon();
        }
    }

    /**
     * Ends the service for good: the request in flight is cancelled without a callback, the worker
     * thread is stopped and later requests are ignored. Call from the owning Activity's onDestroy.
     */
    public void shutdown() {
        synchronized (this) { shutDown = true; }
        cancelActive();
        worker.shutdownNow();
    }

    /** Worker thread: prepares the temporary copy, then sends it once unless the request already ended. */
    private void runRequest(final Request req, final GenerativeModelFutures current) {
        try {
            if (req.done.get()) {
                Log.i(TAG, "AI request skipped: it ended before the photo was prepared.");
                return;
            }
            byte[] jpeg = buildAnalysisCopy(req.photo);
            if (jpeg == null) {
                deliver(req, Result.unavailable("The photo could not be prepared for checking.", "prepare-failed",
                        FailureKind.UNKNOWN));
                return;
            }
            Content content = new Content.Builder()
                    .addInlineData(jpeg, "image/jpeg")
                    .addText(PROMPT)
                    .build();
            req.content = content;
            req.generativeModel = current;
            final ListenableFuture<GenerateContentResponse> future = req.sendOnce(current, content);
            if (future == null) {
                // Timed out, superseded, cancelled or shut down while the photo was being prepared.
                Log.i(TAG, "AI request not sent: it ended while the photo was being prepared (no quota used).");
                return;
            }
            Log.i(TAG, "AI request started (" + (jpeg.length / 1024) + " KB JPEG, max edge "
                    + AI_IMAGE_MAX_EDGE_PX + " px).");
            future.addListener(() -> onAnswer(req, future), mainThread::post);
        } catch (Throwable error) {
            failOrRetry(req, classifyFailure(error, req.elapsedMs()));
        }
        // The temporary JPEG copy is referenced only by this method and the request; it is never
        // written to disk and is garbage-collected with them.
    }

    /** Main thread: the SDK future completed (or was cancelled). */
    private void onAnswer(Request req, ListenableFuture<GenerateContentResponse> future) {
        if (req.done.get()) {
            Log.i(TAG, "AI answer ignored: the request had already ended.");
            return;
        }
        Result result;
        try {
            GenerateContentResponse response = future.get();
            String text = response == null ? null : response.getText();
            Log.i(TAG, "AI response received (" + (text == null ? 0 : text.length()) + " chars).");
            result = interpret(text);
        } catch (ExecutionException error) {
            failOrRetry(req, classifyFailure(error.getCause() != null ? error.getCause() : error, req.elapsedMs()));
            return;
        } catch (Throwable error) {
            failOrRetry(req, classifyFailure(error, req.elapsedMs()));
            return;
        }
        // A model ANSWER (accepted, rejected, low-confidence or malformed) is final: never retried.
        deliver(req, result);
    }

    // ---------- bounded retry of temporary failures ----------

    /**
     * Wait before the next automatic retry of this failure, or -1 when it must not be retried.
     * Retried: SERVICE_UNAVAILABLE (busy/overloaded/5xx), NETWORK (dropped connection) and a 429
     * WITHOUT explicit quota wording. Never retried: explicit quota exhaustion, App Check/auth,
     * configuration, model/SDK, invalid request, malformed answers, low confidence and the
     * REQUEST_TIMEOUT_MS timeout itself.
     */
    static long retryDelayMs(Result failure, int retriesSoFar) {
        if (failure == null || failure.kind == null || retriesSoFar >= MAX_RETRIES) return -1L;
        switch (failure.kind) {
            case SERVICE_UNAVAILABLE:
                return retriesSoFar < SERVICE_RETRY_DELAYS_MS.length ? SERVICE_RETRY_DELAYS_MS[retriesSoFar] : -1L;
            case NETWORK:
                return retriesSoFar == 0 ? NETWORK_RETRY_DELAY_MS : -1L;
            case QUOTA_OR_RATE_LIMIT:
                return retriesSoFar == 0 && failure.code.contains(":rate-limit-unconfirmed:") ? CAPACITY_RETRY_DELAY_MS : -1L;
            default:
                return -1L;
        }
    }

    /** True when a retry with this wait still leaves enough of the request's deadline. */
    static boolean retryFits(long elapsedMs, long delayMs) {
        return delayMs >= 0 && REQUEST_TIMEOUT_MS - elapsedMs - delayMs >= MIN_TIME_LEFT_FOR_RETRY_MS;
    }

    /** Schedules a bounded retry for a temporary failure, otherwise delivers the failure. */
    private void failOrRetry(final Request req, Result failure) {
        if (req.done.get()) return;
        int soFar = req.retries.get();
        long delay = retryDelayMs(failure, soFar);
        if (delay < 0 || !retryFits(req.elapsedMs(), delay) || req.content == null || req.generativeModel == null) {
            deliver(req, failure);
            return;
        }
        final int attempt = req.retries.incrementAndGet();
        Log.i(TAG, "AI retry " + attempt + "/" + MAX_RETRIES + " scheduled after " + failure.kind
                + " (" + failure.code + "), waiting " + delay + " ms, " + req.elapsedMs() + " ms elapsed");
        mainThread.postDelayed(() -> {
            if (req.done.get()) return;   // timed out, superseded, cancelled or shut down meanwhile
            try {
                worker.execute(() -> resend(req, attempt));
            } catch (RejectedExecutionException shutDown) {
                deliver(req, failure);
            }
        }, delay);
    }

    /** Worker thread: re-sends the same prepared content once, unless the request has ended. */
    private void resend(final Request req, int attempt) {
        try {
            final ListenableFuture<GenerateContentResponse> future = req.sendOnce(req.generativeModel, req.content);
            if (future == null) {
                Log.i(TAG, "AI retry " + attempt + " not sent: the request had already ended (no quota used).");
                return;
            }
            Log.i(TAG, "AI retry " + attempt + " sent.");
            future.addListener(() -> onAnswer(req, future), mainThread::post);
        } catch (Throwable error) {
            failOrRetry(req, classifyFailure(error, req.elapsedMs()));
        }
    }

    /** Exactly once per request (timeout vs late answer vs exception); no-op for an ended request. */
    private void deliver(final Request req, Result raw) {
        if (!req.done.compareAndSet(false, true)) return;
        req.stop();   // timer removed; a Gemini call still in flight (e.g. after a timeout) is cancelled
        synchronized (this) { if (active == req) active = null; }
        final Result result = raw.withDuration(req.elapsedMs());
        Log.i(TAG, "AI result: " + result.outcome
                + (result.suggestedCategory.isEmpty() ? "" : ", category " + result.suggestedCategory)
                + (result.confidence >= 0 ? ", confidence " + String.format(java.util.Locale.US, "%.2f", result.confidence) : "")
                + (result.kind == null ? "" : ", kind " + result.kind)
                + (result.code.isEmpty() ? "" : ", code " + result.code)
                + ", " + result.durationMs + " ms");
        mainThread.post(() -> {
            try {
                req.callback.onResult(result);
            } catch (RuntimeException error) {
                Log.e(TAG, "Photo check callback failed: " + error.getClass().getSimpleName());
            }
        });
    }

    // ---------- the temporary analysis copy ----------

    /**
     * Decodes a bounded copy of the photo, scales it to AI_IMAGE_MAX_EDGE_PX and compresses it to
     * JPEG in memory. The original file is opened read-only; no file is written. Bitmaps are
     * recycled before returning. Returns null when the photo cannot be decoded.
     */
    static byte[] buildAnalysisCopy(File photo) {
        if (photo == null || !photo.isFile() || photo.length() == 0) return null;
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photo.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, AI_IMAGE_MAX_EDGE_PX);
            bitmap = BitmapFactory.decodeFile(photo.getAbsolutePath(), options);
            if (bitmap == null) return null;

            int[] target = scaledSize(bitmap.getWidth(), bitmap.getHeight(), AI_IMAGE_MAX_EDGE_PX);
            if (target[0] != bitmap.getWidth() || target[1] != bitmap.getHeight()) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, target[0], target[1], true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, AI_IMAGE_JPEG_QUALITY, out)) return null;
            return out.toByteArray();
        } catch (Exception | OutOfMemoryError error) {
            Log.w(TAG, "Could not prepare the analysis copy: " + error.getClass().getSimpleName());
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    /** Largest power-of-two decode step that keeps the longest edge at or above maxEdge. */
    static int sampleSizeFor(int width, int height, int maxEdge) {
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / (sample * 2) >= maxEdge) sample *= 2;
        return sample;
    }

    /** Final size with the longest edge at most maxEdge, aspect ratio kept. */
    static int[] scaledSize(int width, int height, int maxEdge) {
        int longest = Math.max(width, height);
        if (longest <= maxEdge) return new int[] { width, height };
        double scale = maxEdge / (double) longest;
        return new int[] { Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)) };
    }

    // ---------- never trust the model's JSON ----------

    /**
     * Validates the model's answer. Anything missing, mistyped, out of range, outside the category
     * list or below MIN_CONFIDENCE becomes UNAVAILABLE - never an approval, never a rejection.
     */
    public static Result interpret(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return malformed("empty-response");
        String text = rawText.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return malformed("no-json-object");
        JSONObject json;
        try {
            json = new JSONObject(text.substring(start, end + 1));
        } catch (JSONException | RuntimeException error) {
            return malformed("invalid-json");
        }
        Object valid = json.has("valid") ? json.opt("valid") : null;
        Object category = json.has("category") ? json.opt("category") : null;
        Object confidence = json.has("confidence") ? json.opt("confidence") : null;
        Object reason = json.has("reason") ? json.opt("reason") : null;
        if (!(valid instanceof Boolean)) return malformed("valid-not-boolean");
        if (!(category instanceof String)) return malformed("category-not-string");
        if (!(confidence instanceof Number)) return malformed("confidence-not-number");
        if (reason != null && !(reason instanceof String) && reason != JSONObject.NULL) return malformed("reason-not-string");

        String canonical = canonicalCategory((String) category);
        if (canonical == null) return malformed("category-not-allowed");
        double score = ((Number) confidence).doubleValue();
        if (Double.isNaN(score) || Double.isInfinite(score) || score < 0.0 || score > 1.0) {
            return malformed("confidence-out-of-range");
        }
        String cleanReason = cleanReason(reason instanceof String ? (String) reason : "");

        if (score < MIN_CONFIDENCE) {
            // The answer's own valid flag is kept in the code so Logcat shows whether the model was
            // unsure about a VALID or an INVALID decision. Product policy is unchanged: below
            // MIN_CONFIDENCE the result is UNAVAILABLE either way.
            return new Result(Outcome.UNAVAILABLE, "", score,
                    "The AI could not decide confidently about this photo.",
                    "low-confidence:valid=" + valid, FailureKind.UNKNOWN, -1L);
        }
        if ((Boolean) valid) {
            String suggestion = UNKNOWN.equals(canonical) ? "" : canonical;
            // The draft never influences the decision: whatever it contains, the photo stays ACCEPTED.
            String draftTitle = cleanDraftTitleOrEmpty(json.opt("title"));
            String draftDescription = cleanDraftDescriptionOrEmpty(json.opt("description"));
            return new Result(Outcome.ACCEPTED, suggestion, score, "Photo looks suitable", "accepted",
                    null, -1L, draftTitle, draftDescription);
        }
        String message = cleanReason.isEmpty()
                ? "This photo does not appear to show a recognizable civic issue." : cleanReason;
        return new Result(Outcome.REJECTED, "", score, message, "rejected");
    }

    // ---------- the optional report draft (never trusted, never decisive) ----------

    /** A usable title, or "" when it is missing, not a string, or not sensible. Never throws. */
    static String cleanDraftTitleOrEmpty(Object value) {
        if (!(value instanceof String)) return "";
        String clean = cleanDraftTitle((String) value);
        return clean.length() >= MIN_TITLE_CHARS && clean.length() <= MAX_TITLE_CHARS ? clean : "";
    }

    /** A usable description, or "" when it is missing, not a string, or not sensible. Never throws. */
    static String cleanDraftDescriptionOrEmpty(Object value) {
        if (!(value instanceof String)) return "";
        String clean = cleanDraftText((String) value);
        return clean.length() >= MIN_DESCRIPTION_CHARS && clean.length() <= MAX_DESCRIPTION_CHARS ? clean : "";
    }

    /** Whitespace collapsed, control characters removed, wrapping quotes removed. No length cap. */
    static String cleanDraftText(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                if (!space && out.length() > 0) out.append(' ');
                space = true;
            } else {
                out.append(c);
                space = false;
            }
        }
        return stripWrappingQuotes(out.toString().trim());
    }

    /** A title is one short line: no wrapping quotes and no trailing full stop. */
    static String cleanDraftTitle(String value) {
        String clean = cleanDraftText(value);
        while (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1).trim();
        return clean;
    }

    private static String stripWrappingQuotes(String value) {
        String v = value;
        while (v.length() >= 2 && isQuote(v.charAt(0)) && isQuote(v.charAt(v.length() - 1))) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v;
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '\'' || c == '\u201C' || c == '\u201D' || c == '\u2018' || c == '\u2019';
    }

    private static Result malformed(String code) {
        return Result.unavailable("The AI answer could not be used.", "malformed:" + code, FailureKind.MALFORMED_RESPONSE);
    }

    /** Exact allowed names only (case-insensitive match, canonical spelling returned). */
    static String canonicalCategory(String value) {
        String v = value == null ? "" : value.trim();
        for (String allowed : CATEGORIES) if (allowed.equalsIgnoreCase(v)) return allowed;
        if (UNKNOWN.equalsIgnoreCase(v)) return UNKNOWN;
        return null;
    }

    /** One short plain line: no control characters, whitespace collapsed, length capped. */
    static String cleanReason(String reason) {
        if (reason == null) return "";
        StringBuilder out = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < reason.length(); i++) {
            char c = reason.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                if (!space && out.length() > 0) out.append(' ');
                space = true;
            } else {
                out.append(c);
                space = false;
            }
        }
        String clean = out.toString().trim();
        if (clean.length() > MAX_REASON_CHARS) clean = clean.substring(0, MAX_REASON_CHARS - 1).trim() + "…";
        return clean;
    }

    // ---------- failure classification (Logcat diagnostics + simple citizen messages) ----------

    /**
     * Maps any failure to a technical {@link FailureKind} and a short citizen-safe message.
     * Decisions use the exception classes in the whole cause chain and structured error evidence
     * (HTTP status next to a status/code/http marker, Google status names such as RESOURCE_EXHAUSTED,
     * explicit phrases). A bare number such as "429" or a loose word such as "connect" is not
     * evidence. Exception text only goes to Logcat, redacted and capped.
     */
    public static Result classifyFailure(Throwable error) {
        return classifyFailure(error, -1L);
    }

    static Result classifyFailure(Throwable error, long durationMs) {
        List<Throwable> chain = causeChain(error);
        StringBuilder namesBuilder = new StringBuilder();
        StringBuilder textBuilder = new StringBuilder();
        boolean io = false;
        boolean linkage = false;
        for (Throwable t : chain) {
            namesBuilder.append(t.getClass().getSimpleName()).append(' ').append(t.getClass().getName()).append(' ');
            if (t.getMessage() != null) textBuilder.append(t.getMessage()).append(' ');
            if (t instanceof java.io.IOException) io = true;
            if (t instanceof LinkageError || t instanceof ReflectiveOperationException || t instanceof ClassCastException) linkage = true;
        }
        String names = namesBuilder.toString().toLowerCase(java.util.Locale.US);
        String text = textBuilder.toString().toLowerCase(java.util.Locale.US);
        String top = error == null ? "Unknown" : error.getClass().getSimpleName();

        FailureKind kind;
        String sub;
        if (names.contains("quotaexceeded") || hasQuotaEvidence(text)) {
            // Firebase documents that a 429 means EITHER exhausted quota OR exhausted model capacity.
            // Only explicit quota wording counts as exhausted quota (never retried); anything else
            // is an unconfirmed rate limit / capacity answer, which may be retried once.
            kind = FailureKind.QUOTA_OR_RATE_LIMIT;
            sub = hasExplicitQuotaWording(text) ? "quota-exceeded" : "rate-limit-unconfirmed";
        } else if (names.contains("timeout") || names.contains("timedout") || text.contains("timed out")
                || text.contains("deadline exceeded")) {
            kind = FailureKind.TIMEOUT; sub = "timeout";
        } else if (hasAny(text, "app check", "appcheck", "app_check")) {
            kind = FailureKind.APP_CHECK_OR_AUTH; sub = "app-check";
        } else if (names.contains("invalidapikey") || hasAny(text, "api key not valid", "api_key_invalid",
                "unauthenticated") || httpStatus(text, 401)) {
            kind = FailureKind.APP_CHECK_OR_AUTH; sub = "auth";
        } else if (names.contains("apinotconfigured")) {
            kind = FailureKind.MODEL_OR_SDK; sub = "api-not-configured";
        } else if (names.contains("servicedisabled") || hasAny(text, "has not been used in project",
                "api has not been enabled", "service is disabled", "api is not enabled")) {
            kind = FailureKind.MODEL_OR_SDK; sub = "service-disabled";
        } else if (names.contains("unsupporteduserlocation") || text.contains("location is not supported")) {
            kind = FailureKind.MODEL_OR_SDK; sub = "location-unsupported";
        } else if (httpStatus(text, 403) || text.contains("permission_denied")) {
            // A 403 with no App Check or key wording is NOT attributed to App Check.
            kind = FailureKind.UNKNOWN; sub = "http-403-unattributed";
        } else if (names.contains("promptblocked") || names.contains("contentblocked")
                || text.contains("invalid_argument") || httpStatus(text, 400)) {
            kind = FailureKind.INVALID_REQUEST; sub = "bad-request";
        } else if (httpStatus(text, 404) || text.contains("not_found")
                || (text.contains("model") && hasAny(text, "not found", "is not supported", "no longer available"))) {
            kind = FailureKind.MODEL_OR_SDK; sub = "model-not-found";
        } else if (names.contains("serializ")) {
            kind = FailureKind.MALFORMED_RESPONSE; sub = "sdk-could-not-parse";
        } else if (httpStatus(text, 500) || httpStatus(text, 502) || httpStatus(text, 503) || httpStatus(text, 504)
                || hasAny(text, "model is overloaded", "is overloaded", "service unavailable", "currently unavailable",
                        "temporarily unavailable", "high demand", "internal error encountered", "try again later")
                || names.contains("serverexception")) {
            // A server-side "busy / overloaded / 5xx" answer (or the SDK's generic ServerException,
            // reached only after every specific 4xx/configuration rule above): temporary.
            kind = FailureKind.SERVICE_UNAVAILABLE; sub = "server-busy";
        } else if (io || hasAny(text, "unable to resolve host", "no address associated with hostname",
                "failed to connect to", "network is unreachable", "connection reset", "connection refused",
                "software caused connection abort")) {
            kind = FailureKind.NETWORK; sub = "io";
        } else if (linkage) {
            kind = FailureKind.MODEL_OR_SDK; sub = "sdk-linkage";
        } else {
            kind = FailureKind.UNKNOWN; sub = "unclassified";
        }

        Log.w(TAG, "AI failure: kind=" + kind + " (" + sub + ") top=" + top + " chain=" + describeChain(chain)
                + (durationMs >= 0 ? " after " + durationMs + " ms" : ""));
        String message = "quota-exceeded".equals(sub)
                ? "The AI service's usage limit has been reached. Try again later." : citizenMessage(kind);
        return Result.unavailable(message, kind.name().toLowerCase(java.util.Locale.US) + ":" + sub + ":" + top,
                kind).withDuration(durationMs);
    }

    static String citizenMessage(FailureKind kind) {
        switch (kind) {
            case NETWORK: return "No internet connection, or the connection is too slow.";
            case TIMEOUT: return "The photo check took too long.";
            case QUOTA_OR_RATE_LIMIT: return "The AI service is busy or its free limit has been reached. Try again later.";
            case SERVICE_UNAVAILABLE: return "The AI service is busy right now. Please try again in a moment.";
            case APP_CHECK_OR_AUTH: return "The AI service did not accept this app right now.";
            case INVALID_REQUEST: return "The AI service could not process this photo.";
            case MALFORMED_RESPONSE: return "The AI answer could not be used.";
            default: return "The AI service is not available right now.";
        }
    }

    /** Structured quota/rate-limit evidence only: Google's status name, specific phrases, or an HTTP 429 marker. */
    private static boolean hasQuotaEvidence(String text) {
        return text.contains("resource_exhausted") || httpStatus(text, 429)
                || hasAny(text, "rate limit", "rate-limit", "ratelimit", "too many requests", "quota exceeded",
                        "exceeded your current quota", "exceeded the quota")
                || QUOTA_PHRASE.matcher(text).find();
    }

    /** Wording that states the quota itself is used up (as opposed to a generic 429 / "resource exhausted"). */
    private static boolean hasExplicitQuotaWording(String text) {
        return hasAny(text, "quota exceeded", "exceeded your current quota", "exceeded the quota", "quota has been exhausted")
                || QUOTA_PHRASE.matcher(text).find();
    }

    private static final Pattern QUOTA_PHRASE = Pattern.compile("quota[^.]{0,40}exceed|exceed[^.]{0,40}quota");

    /** True when the text states this HTTP status next to an http/status/code/response marker. */
    private static boolean httpStatus(String text, int code) {
        return Pattern.compile("(?:http|status|code|response)\\D{0,16}" + code + "(?!\\d)").matcher(text).find();
    }

    private static boolean hasAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    /** The throwable and its causes (identity cycle-safe, at most 8 deep). */
    private static List<Throwable> causeChain(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        Throwable t = error;
        while (t != null && chain.size() < 8) {
            boolean seen = false;
            for (Throwable c : chain) if (c == t) { seen = true; break; }
            if (seen) break;
            chain.add(t);
            t = t.getCause();
        }
        if (chain.isEmpty()) chain.add(new IllegalStateException("no exception"));
        return chain;
    }

    private static String describeChain(List<Throwable> chain) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < chain.size(); i++) {
            Throwable t = chain.get(i);
            if (i > 0) out.append(" <- ");
            out.append(t.getClass().getSimpleName());
            if (t.getMessage() != null && !t.getMessage().isEmpty()) out.append(": ").append(sanitize(t.getMessage()));
        }
        return out.append(']').toString();
    }

    private static final Pattern REDACT_KEY = Pattern.compile("AIza[0-9A-Za-z_\\-]{10,}");
    private static final Pattern REDACT_SECRET = Pattern.compile(
            "(?i)(key|token|authorization|password|secret|credential)(\\s*[=:]\\s*|[\"']\\s*:\\s*[\"'])[^\\s\"',}]{6,}");
    private static final Pattern REDACT_BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}");
    private static final Pattern REDACT_JWT = Pattern.compile("eyJ[A-Za-z0-9_\\-]{8,}[A-Za-z0-9_.\\-]*");
    private static final Pattern REDACT_DATA = Pattern.compile("data:[a-z]+/[a-z0-9.+\\-]+;base64,[^\\s\"']*");
    private static final Pattern REDACT_LONG = Pattern.compile("[A-Za-z0-9+/=_\\-]{40,}");

    /** Message text for Logcat: keys, tokens, data URIs and long opaque strings removed; capped. */
    static String sanitize(String value) {
        if (value == null) return "";
        String s = REDACT_DATA.matcher(value).replaceAll("[image-data-removed]");
        s = REDACT_KEY.matcher(s).replaceAll("[key-removed]");
        s = REDACT_BEARER.matcher(s).replaceAll("bearer [removed]");   // before REDACT_SECRET: "Authorization: Bearer <token>"
        s = REDACT_SECRET.matcher(s).replaceAll("$1$2[removed]");
        s = REDACT_JWT.matcher(s).replaceAll("[token-removed]");
        s = REDACT_LONG.matcher(s).replaceAll("[long-string-removed]");
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= 240 ? s : s.substring(0, 240) + "...";
    }
}
