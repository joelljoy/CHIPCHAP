package com.chipchap.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Log;
import android.widget.Toast;
import com.chipchap.app.ai.AppCheckSetup;
import com.chipchap.app.ai.CivicPhotoAiService;
import com.chipchap.app.data.ComplaintDraft;
import com.chipchap.app.data.CloudEvidenceRepository;
import com.chipchap.app.data.CommentRepository;
import com.chipchap.app.data.ComplaintRepository;
import com.chipchap.app.data.CivicAnalytics;
import com.chipchap.app.data.DuplicateReportFinder;
import com.chipchap.app.data.FirestoreErrors;
import com.chipchap.app.data.GeoapifyRouteService;
import com.chipchap.app.data.LocalPhotoRepository;
import com.chipchap.app.data.LocationProvider;
import com.chipchap.app.data.NotificationRepository;
import com.chipchap.app.data.TimelineRepository;
import com.chipchap.app.data.VoteRepository;
import com.chipchap.app.models.Comment;
import com.chipchap.app.models.Complaint;
import com.chipchap.app.models.TimelineEvent;
import com.chipchap.app.data.RepositoryCallback;
import com.chipchap.app.data.UserRepository;
import com.chipchap.app.models.UserProfile;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.google.firebase.auth.UserProfileChangeRequest;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 7001;
    /** Separate request code so a government after-photo can never overwrite a citizen draft. */
    private static final int REQ_GOVERNMENT_CAMERA = 7006;
    private static final int REQ_PERMISSIONS = 7003;
    private static final int REQ_CAMERA_PERMISSION = 7004;
    private static final int REQ_LOCATION_PERMISSION = 7005;
    private static final long LOCATION_TIMEOUT_MS = 20000L;
    /**
     * My Location on the map starts from a position the device already has if it is at most this
     * old, then refines it with a fresh fix. Report submission keeps its own stricter rule.
     */
    private static final long MAP_RECENT_LOCATION_MAX_AGE_MS = 15 * 60 * 1000L;
    private WebView web;
    private final Deque<String> history = new ArrayDeque<>();
    private String currentScreen = "";
    private String verificationState = "pending";
    private String verificationMessage = "Check your email for a verification link.";
    private FirebaseAuth auth;
    private UserRepository userRepository;
    private ComplaintRepository complaintRepository;
    private TimelineRepository timelineRepository;
    private VoteRepository voteRepository;
    private CommentRepository commentRepository;
    private NotificationRepository notificationRepository;
    /** Last profile read from users/{uid}; the WebView reads it through the bridge. */
    private volatile UserProfile citizenProfile;
    private LocalPhotoRepository localPhotoRepository;
    private CloudEvidenceRepository cloudEvidenceRepository;
    private LocationProvider locationProvider;
    /** Firebase AI Logic boundary: checks the citizen's report photo before it is uploaded. */
    private CivicPhotoAiService civicPhotoAiService;
    private static final String PHOTO_CHECK_NONE = "";
    private static final String PHOTO_CHECK_CHECKING = "checking";
    private static final String PHOTO_CHECK_ACCEPTED = "accepted";
    private static final String PHOTO_CHECK_REJECTED = "rejected";
    private static final String PHOTO_CHECK_UNAVAILABLE = "unavailable";
    /** The citizen chose "Continue without AI check" after a technical AI failure. */
    private static final String PHOTO_CHECK_SKIPPED = "skipped";
    /** Draft-only AI check state (UI thread). Never stored in Firestore. */
    private volatile String photoCheckState = PHOTO_CHECK_NONE;
    private volatile String photoCheckMessage = "";
    /** AI-suggested category for the current photo, or "". A suggestion only - never applied natively. */
    private volatile String photoCheckCategory = "";
    private File photoCheckFile;
    private int photoCheckRequest = 0;

    // ---- AI report-text DRAFT. It arrives inside the SAME Gemini answer as the photo check, so it passes
    // ---- the same request/photo identity guards. Memory only: never stored, never sent anywhere.
    private static final String DRAFT_READY = "ready";
    private static final String DRAFT_UNAVAILABLE = "unavailable";
    private volatile String draftState = "";
    private volatile String draftTitle = "";
    private volatile String draftDescription = "";
    /** True once the details screen has applied (or deliberately skipped) this draft: it is applied at most once. */
    private volatile boolean draftHandled = false;
    private volatile int draftRequest = 0;

    /** Survives the capture -> details -> review page loads that a JS variable would not. */
    private final ComplaintDraft complaintDraft = new ComplaintDraft();
    /** The file handed to the camera app, held until the result comes back. */
    private File pendingPhotoFile;
    /** Government after-photo capture, kept entirely apart from the citizen draft. */
    private File pendingGovernmentPhotoFile;
    private volatile String governmentAfterPhotoPath = "";
    private volatile String governmentAfterPhotoUrl = "";
    private volatile boolean governmentAfterPhotoUploading = false;
    private volatile String governmentAfterPhotoError = "";
    /** The report the after-photo was captured for; a photo is never reused on another report. */
    private volatile String governmentAfterPhotoReportId = "";
    /** Display-only downscaled JPEG for the Stitch preview. Never persisted. */
    private volatile String photoPreviewDataUri = "";
    /** Real Firestore document id of the last successful submission, for the success screen. */
    private String lastSubmittedReportId = "";
    /** Report the citizen opened from the community feed, for votes and comments. */
    private volatile String activeReportId = "";
    /**
     * uid whose Firebase ID token was read and carried the custom claim role == "GOVERNMENT".
     * In memory only: never persisted, cleared on every sign-out and re-derived from the token on
     * every launch. It only decides which screens to show; Firestore security rules check the same
     * claim on the server for every read and write.
     */
    private volatile String governmentVerifiedUid = "";
    /** Latest map My Location request; answers to an older tap are ignored. UI thread only. */
    private int mapLocationRequest = 0;
    /**
     * Latest location change for the citizen draft (device fix or a pin placed on the incident map).
     * An older device fix or address lookup never overwrites a newer choice. UI thread only.
     */
    private int complaintLocationRequest = 0;
    // ---- Checkpoint 2: possible-duplicate check for the citizen draft. Memory only, UI thread only.
    /** Bumped by every new check and by sign-out; an older Firestore answer is dropped. */
    private int similarCheckGeneration = 0;
    /** Draft fingerprint (photo + location + category) the running or finished check belongs to. */
    private String similarCheckKey = "";
    private boolean similarCheckRunning = false;
    /** Finished result for similarCheckKey, reused when the citizen returns to the review step. */
    private String similarCheckPayload = "";
    /** Latest review-page sequence that asked; the page ignores answers for an older one. */
    private int similarCheckSeq = 0;
    // ---- Checkpoint F1: government route preview (Geoapify). Temporary UI state, in memory only:
    // ---- never stored in Firestore, never persisted. Every field below is used on the UI thread.
    private static final String ROUTE_TAG = "CHIPCHAP-ROUTE";
    private static final int REQ_ROUTE_LOCATION_PERMISSION = 7007;
    /** A position the device already has is used as the route origin only if it is this recent. */
    private static final long ROUTE_RECENT_LOCATION_MAX_AGE_MS = 60 * 1000L;
    private static final String ROUTE_NONE = "none";
    private static final String ROUTE_LOADING = "loading";
    private static final String ROUTE_READY = "ready";
    private static final String ROUTE_ERROR = "error";
    private GeoapifyRouteService routeService;
    /**
     * Bumped by every new route request, Clear Route, sign-out and Activity destruction. An answer
     * (report read, location fix or Geoapify response) for an older generation is dropped, so a
     * late reply can never replace a newer route or recreate a cleared one.
     */
    private int routeGeneration = 0;
    private String routeState = ROUTE_NONE;
    private String routeReportId = "";
    private String routeTitle = "";
    /** Last route payload for the map; re-sent when the map screen is opened (reloaded) again. */
    private String routePayload = "";
    /** Route waiting for the location-permission answer: a report id or a place destination id. */
    private String pendingRouteReportId = "";
    private String pendingRouteDestPlaceId = "";
    // ---- Checkpoint F2: editable starting point + place search (government only, UI thread, memory only).
    /** "" = the device's own location (the default); otherwise the id of a searched place. */
    private String routeOriginPlaceId = "";
    private String routeOriginLabel = "";
    /** "" = the destination is the report routeReportId; otherwise the id of a searched place. */
    private String routeDestPlaceId = "";
    // ---- Checkpoint F3: endpoint text + the values of the CALCULATED route, for Copy/Share only.
    private String routeOriginAddress = "";
    private String routeDestAddress = "";
    /** {lat, lng} actually sent to Geoapify for the current READY route; null otherwise. */
    private double[] routeOriginCoords = null;
    private double[] routeDestCoords = null;
    private double routeDistanceMeters = Double.NaN;
    private double routeTimeSeconds = Double.NaN;
    /**
     * Places returned by Geoapify geocoding, by the id handed to the WebView. Routes are requested
     * by id, so their coordinates are always the real ones Geoapify returned - never values
     * supplied by the page. Bounded; cleared on sign-out.
     */
    private static final int PLACE_CACHE_MAX = 100;
    private final Map<String, GeoapifyRouteService.Place> placeCache =
            new java.util.LinkedHashMap<String, GeoapifyRouteService.Place>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, GeoapifyRouteService.Place> eldest) {
                    return size() > PLACE_CACHE_MAX;
                }
            };
    private int placeSequence = 0;
    /** Bumped by every search and cancel; an older search answer is dropped. */
    private int placeSearchGeneration = 0;
    /** Prevents a double tap from committing the same government action twice. */
    private volatile boolean governmentActionInProgress = false;
    private static final String CLAIM_ROLE = "role";
    private static final String ROLE_GOVERNMENT = "GOVERNMENT";
    /** Most recent reports the government screens read for metrics and the queue. */
    private static final int GOVERNMENT_REPORT_LIMIT = 500;
    /** Citizen map marker budget: one bounded query, never the whole collection. */
    private static final int MAP_REPORT_LIMIT = 100;
    /** The one screen both portals share: citizen markers, or markers + the government heatmap. */
    private static final String SHARED_MAP_SCREEN = "map_explorer";
    /** Safety net: the screen is revealed even if a page never reports that it finished. */
    private static final long SCREEN_REVEAL_FALLBACK_MS = 2500L;
    /** After the fallback fires, the parsed screen is wired and shown; this is the hard limit. */
    private static final long SCREEN_FORCE_REVEAL_MS = 2500L;
    private static final long WIRE_POLL_INTERVAL_MS = 150L;
    private static final int WIRE_POLL_ATTEMPTS = 16;
    /** Screen being wired by the fallback path, and how many readiness checks remain. UI thread only. */
    private String wirePollScreen = "";
    private int wirePollAttemptsLeft = 0;
    private static final String GOVERNMENT_ACCOUNT_ON_CITIZEN_LOGIN = "Government accounts must use Government Login.";
    private boolean submissionInProgress = false;
    /** True when submit was tapped while the evidence upload was still running. */
    private volatile boolean submissionWaitingForEvidence = false;
    /**
     * reportId -> localImagePath for reports this screen has rendered. Lets the WebView ask for a
     * report's own evidence photo by id, without ever handing a file path to JavaScript.
     */
    private final Map<String, String> reportPhotoPaths = new ConcurrentHashMap<>();
    /** Longest edge of the evidence thumbnail on list cards. */
    private static final int CARD_THUMBNAIL_EDGE_PX = 480;
    /** Message to show once the next screen has finished loading, so it is not lost. */
    private String pendingWebMessage = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(FLOW_TAG, "ACTIVITY_CREATED recreated=" + (state != null) + " pid=" + android.os.Process.myPid()
                + " act=" + Integer.toHexString(System.identityHashCode(this)));
        // App Check before anything else Firebase: debug builds install the debug provider here
        // (src/debug), release builds install nothing yet (src/release). Then the AI model object is
        // created - no AI request is made in this checkpoint.
        AppCheckSetup.install(this);
        civicPhotoAiService = new CivicPhotoAiService();
        civicPhotoAiService.initialize();
        AppCheckSetup.logTokenStatus();
        auth = FirebaseAuth.getInstance();
        userRepository = new UserRepository();
        complaintRepository = new ComplaintRepository();
        timelineRepository = new TimelineRepository();
        voteRepository = new VoteRepository();
        commentRepository = new CommentRepository();
        notificationRepository = new NotificationRepository();
        localPhotoRepository = new LocalPhotoRepository(this);
        cloudEvidenceRepository = new CloudEvidenceRepository(this);
        locationProvider = new LocationProvider(this);
        // Checkpoint F1: key from local.properties via BuildConfig; empty when not configured.
        routeService = new GeoapifyRouteService(BuildConfig.GEOAPIFY_API_KEY);
        web = new WebView(this);
        web.setBackgroundColor(0xfffaf9ff);
        // Same colour behind the WebView, so the brief gap during a screen swap is the app's own
        // background rather than a white or black window flash.
        getWindow().getDecorView().setBackgroundColor(0xfffaf9ff);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowContentAccess(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new NativeBridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                return handleUrl(r.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String u) { return handleUrl(u); }
            // TEMPORARY diagnostic only. Returning false is exactly the default behaviour (the app
            // is ended by the system); this just records that the WebView renderer was the cause.
            @Override public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail detail) {
                Log.e(FLOW_TAG, "WEBVIEW_RENDERER_GONE didCrash=" + (detail != null && detail.didCrash()) + " " + diagCtx());
                return false;
            }
            @Override public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                v.removeCallbacks(wireWhenParsed);
                v.removeCallbacks(forceReveal);
                wirePollScreen = "";
                injectScreenScripts();
                if (url != null && url.contains("/otp_verification/")) {
                    setVerificationState(verificationState, verificationMessage);
                }
                if (url != null && url.contains("/success/")) {
                    applyRealTrackingId();
                }
                if (!pendingWebMessage.isEmpty()) {
                    String message = pendingWebMessage;
                    pendingWebMessage = "";
                    notifyWeb(message);
                }
                // The rewired screen is complete: show it in one step instead of letting the user
                // watch the previous screen, then raw Stitch markup, then the corrected content.
                v.removeCallbacks(revealScreen);
                v.setVisibility(android.view.View.VISIBLE);
            }
        });
        setContentView(web);
        restorePendingCapture(state);
        restoreSession();
    }

    // ---------- camera capture across Activity recreation ----------
    // The camera is a separate app. While it is open Android may destroy this Activity (low memory,
    // or a configuration change) and create a new one for the result. Every field is then empty:
    // the photo file handed to the camera was forgotten, the result was discarded, and the session
    // restore sent the citizen to the Citizen Dashboard instead of back to the report photo.
    private static final String STATE_PENDING_PHOTO = "chipchap.pendingPhotoPath";
    private static final String FLOW_TAG = "CHIPCHAP-FLOW";
    /** A camera result is expected by this new Activity: reopen the capture screen, not the dashboard. */
    private boolean resumeCaptureAfterRecreation = false;

    /**
     * TEMPORARY diagnostic context for the "accepted photo -> Home" investigation. Process id and
     * Activity instance id show whether the Activity/process changed between the camera result and
     * the accepted result. No paths, tokens, image data or personal data.
     */
    private String diagCtx() {
        return "pid=" + android.os.Process.myPid()
                + " act=" + Integer.toHexString(System.identityHashCode(this))
                + " screen=" + currentScreen
                + " photoCheck=" + photoCheckState
                + " draftPhoto=" + complaintDraft.hasPhoto()
                + " evidenceUploading=" + complaintDraft.isEvidenceUploading();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingPhotoFile != null) out.putString(STATE_PENDING_PHOTO, pendingPhotoFile.getAbsolutePath());
        Log.i(FLOW_TAG, "ACTIVITY_STATE_SAVED pendingPhoto=" + (pendingPhotoFile != null ? "present" : "none") + " " + diagCtx());
    }

    @Override protected void onStop() {
        Log.i(FLOW_TAG, "ACTIVITY_STOPPED " + diagCtx());
        super.onStop();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.i(FLOW_TAG, "TRIM_MEMORY level=" + level + " " + diagCtx());
    }

    private void restorePendingCapture(Bundle state) {
        if (state == null) return;
        String path = state.getString(STATE_PENDING_PHOTO);
        if (path == null || path.isEmpty()) return;
        // The citizen was on the capture screen: return there whatever the camera did.
        resumeCaptureAfterRecreation = true;
        // Only a photo the camera actually wrote into this app's own photo folder is accepted back.
        File file = new File(path);
        if (localPhotoRepository.isAppPhoto(file)) pendingPhotoFile = file;
        Log.i(FLOW_TAG, "CAMERA_CAPTURE_RESTORED after Activity recreation, photo="
                + (pendingPhotoFile != null ? "present" : "none"));
    }

    @Override protected void onDestroy() {
        Log.i(FLOW_TAG, "ACTIVITY_DESTROYED finishing=" + isFinishing() + " changingConfig=" + isChangingConfigurations() + " " + diagCtx());
        // Any AI request this Activity started is cancelled with no callback and the worker thread
        // stops, so a destroyed Activity never leaves Gemini work (and quota use) running.
        if (civicPhotoAiService != null) civicPhotoAiService.shutdown();
        // Route preview: any route answer still on its way is dropped, never delivered to a dead screen.
        routeGeneration++;
        if (routeService != null) routeService.shutdown();
        super.onDestroy();
    }

    private boolean handleUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("screen://")) {
            loadScreen(url.substring("screen://".length()), true);
            return true;
        }
        return false;
    }

    public void loadScreen(String name) { loadScreen(name, true); }
    private void loadScreen(String name, boolean addHistory) {
        if (name == null || name.trim().isEmpty()) return;
        if (isAdminScreen(name)) {
            // Government screens need a signed-in user whose ID token carries role == GOVERNMENT.
            if (!hasGovernmentAccess()) {
                denyAdminAccess();
                return;
            }
            loadScreenUnchecked(name, addHistory);
            return;
        }
        if (isProtectedScreen(name)) {
            // Two-way separation: a session verified as GOVERNMENT stays in the government portal.
            // The one exception is the map screen, which IS the government Live Map surface
            // (Checkpoint E): the same Stitch screen serves both sides and the bridge decides what
            // it may show. Without this a government tap on Live Map bounced straight back to the
            // dashboard, reloading it - the "dashboard flash" seen on the device.
            if (hasGovernmentAccess() && !SHARED_MAP_SCREEN.equals(name)) {
                pendingWebMessage = "Government accounts use the government portal.";
                loadScreenUnchecked("dashboard", false);
                return;
            }
            if (hasGovernmentAccess()) {
                loadScreenUnchecked(name, addHistory);
                return;
            }
            requireSignedInUser(() -> loadScreenUnchecked(name, addHistory));
            return;
        }
        loadScreenUnchecked(name, addHistory);
    }

    private void loadScreenUnchecked(String name, boolean addHistory) {
        // Diagnostic: every screen change, and WHO sent the citizen to the dashboard.
        if ("home_screen".equals(name) || "dashboard".equals(name)) Log.i(FLOW_TAG, "NAV -> " + name + " from " + currentScreen + " " + diagCtx(), new Throwable("navigation origin (diagnostic)"));
        else Log.i(FLOW_TAG, "NAV -> " + name + " from " + currentScreen);
        if (addHistory && currentScreen != null && !currentScreen.isEmpty() && !currentScreen.equals(name)) history.push(currentScreen);
        currentScreen = name;
        // A WebView keeps painting the OLD document until the new one is ready, so the screen the
        // user just left stayed visible while the next one loaded and was rewired. Hiding the view
        // for that interval shows the app background instead of stale content; onPageFinished
        // reveals it again once the injected scripts have run, and the timer guarantees the screen
        // can never stay hidden if a page fails to report that it finished.
        web.setVisibility(android.view.View.INVISIBLE);
        web.removeCallbacks(revealScreen);
        web.removeCallbacks(wireWhenParsed);
        web.removeCallbacks(forceReveal);
        wirePollScreen = "";
        web.postDelayed(revealScreen, SCREEN_REVEAL_FALLBACK_MS);
        web.loadUrl("file:///android_asset/screens/" + name + "/code.html");
    }

    /** The four injected scripts. Each one guards itself, so running them twice is harmless. */
    private void injectScreenScripts() {
        injectUniversalUX();
        injectComplaintFlow();
        injectCitizenScreens();
        injectGovernmentScreens();
    }

    /**
     * The page has not reported "finished" in time. That event waits for every web font and image,
     * which on a slow connection takes longer than the timer, and the screen used to be revealed
     * at this point WITHOUT its injected scripts - so its buttons did nothing until loading ended.
     * That is why Government Dashboard -> Quick Actions -> Live Map (a plain Stitch button that
     * only the injected government script wires) sometimes did not open the map. Now the screen is
     * wired as soon as its document is parsed and only then revealed; forceReveal still
     * guarantees the WebView can never stay hidden.
     */
    private final Runnable revealScreen = new Runnable() {
        @Override public void run() {
            if (web == null) return;
            wirePollScreen = currentScreen;
            wirePollAttemptsLeft = WIRE_POLL_ATTEMPTS;
            web.removeCallbacks(forceReveal);
            web.postDelayed(forceReveal, SCREEN_FORCE_REVEAL_MS);
            wireWhenParsed.run();
        }
    };

    /** Shows the WebView again after a screen swap. Safe to run more than once. */
    private final Runnable forceReveal = new Runnable() {
        @Override public void run() {
            if (web != null) web.setVisibility(android.view.View.VISIBLE);
        }
    };

    /** Injects the scripts into the NEW screen once its DOM is parsed, then reveals it. */
    private final Runnable wireWhenParsed = new Runnable() {
        @Override public void run() {
            if (web == null) return;
            final String screen = wirePollScreen;
            if (screen == null || screen.isEmpty() || !screen.equals(currentScreen)) return;
            String check = "(function(){return document.readyState!=='loading'&&location.pathname.indexOf("
                    + JSONObject.quote("/" + screen + "/") + ")>=0;})()";
            web.evaluateJavascript(check, value -> {
                if (!screen.equals(currentScreen) || !screen.equals(wirePollScreen)) return;
                if ("true".equals(value)) {
                    wirePollScreen = "";
                    injectScreenScripts();
                    web.removeCallbacks(forceReveal);
                    web.setVisibility(android.view.View.VISIBLE);
                    return;
                }
                if (--wirePollAttemptsLeft > 0) web.postDelayed(wireWhenParsed, WIRE_POLL_INTERVAL_MS);
            });
        }
    };

    private boolean isAdminScreen(String name) {
        // admin_login is the public government sign-in screen; every other admin screen is gated.
        return (name.startsWith("admin_") && !name.equals("admin_login")) || name.equals("dashboard") || name.equals("complaint_queue")
                || name.equals("complaint_details_2") || name.equals("assign_complaint")
                || name.equals("analytics") || name.equals("notifications_2") || name.equals("update_status");
    }

    private boolean isProtectedScreen(String name) {
        return !(name.equals("splash_screen_1") || name.equals("splash_screen_2")
                || name.equals("language_selection") || name.equals("onboarding_report_easy")
                || name.equals("onboarding_community_impact") || name.equals("onboarding_real_time_tracking")
                || name.equals("login_screen") || name.equals("signup_screen") || name.equals("otp_verification")
                || name.equals("admin_login"));
    }

    /**
     * An already-authenticated citizen reopening the app goes straight to the dashboard.
     * FirebaseAuth.getCurrentUser() is the only source of authentication state; nothing here
     * marks a user as authenticated on its own.
     */
    private void restoreSession() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            loadScreenUnchecked("splash_screen_1", false);
            return;
        }
        // The role comes from a FRESH Firebase ID token, never from local storage and never from a
        // cached token. Routing is authorization-sensitive, so it fails closed: if a fresh token
        // cannot be obtained, the session is cleared and the user signs in again. A stale cached
        // token can therefore never route a government account into the citizen portal.
        user.getIdToken(true).addOnCompleteListener(this, this::routeRestoredSession);
    }

    /** Opens the portal a freshly issued ID token allows: government dashboard or citizen home. */
    private void routeRestoredSession(com.google.android.gms.tasks.Task<GetTokenResult> task) {
        FirebaseUser current = auth.getCurrentUser();
        if (current == null) {
            clearSessionState();
            loadScreenUnchecked("splash_screen_1", false);
            return;
        }
        if (!task.isSuccessful() || task.getResult() == null) {
            auth.signOut();
            clearSessionState();
            history.clear();
            pendingWebMessage = "Your account could not be verified, so you were signed out. "
                    + "Check your connection and sign in again.";
            loadScreenUnchecked("login_screen", false);
            return;
        }
        if (isGovernmentToken(task.getResult())) {
            governmentVerifiedUid = current.getUid();
            loadScreenUnchecked("dashboard", false);
            return;
        }
        governmentVerifiedUid = "";
        ensureFirestoreProfile();
        if (resumeCaptureAfterRecreation) {
            // A camera result belongs to this Activity: go back to the report photo, with the
            // dashboard underneath so Back behaves as before.
            resumeCaptureAfterRecreation = false;
            history.clear();
            history.push("home_screen");
            loadScreenUnchecked("capture_photo", false);
        } else {
            loadScreenUnchecked("home_screen", false);
        }
        refreshVerificationState();
    }

    /**
     * Clears everything held in memory for the previous signed-in person, so nothing of one account
     * (profile, open report, draft, photo preview) is shown to the next.
     */
    private void clearSessionState() {
        governmentVerifiedUid = "";
        citizenProfile = null;
        activeReportId = "";
        lastSubmittedReportId = "";
        reportPhotoPaths.clear();
        clearGovernmentAfterPhotoState();
        complaintDraft.clear();
        resetPhotoCheck();
        photoPreviewDataUri = "";
        pendingPhotoFile = null;
        resetRouteState();
        resetSimilarCheck();
        placeCache.clear();
        placeSearchGeneration++;
        if (routeService != null) routeService.cancelSearches();
    }

    /** True only when the given ID token result carries the custom claim role == "GOVERNMENT". */
    private static boolean isGovernmentToken(GetTokenResult result) {
        if (result == null || result.getClaims() == null) return false;
        Object role = result.getClaims().get(CLAIM_ROLE);
        return ROLE_GOVERNMENT.equals(role);
    }

    /**
     * Checkpoint F3: place search and route preview are map tools for any signed-in session -
     * the same rule that opens the map screen itself. They read nothing privileged: a report
     * route reads the report through the normal Firestore rules, and the heatmap and every
     * government action keep their own hasGovernmentAccess() checks.
     */
    private boolean hasMapRouteAccess() {
        return hasGovernmentAccess() || auth.getCurrentUser() != null;
    }

    /** UI routing only: the current Firebase user is the one whose token was verified as GOVERNMENT. */
    private boolean hasGovernmentAccess() {
        FirebaseUser user = auth.getCurrentUser();
        String verified = governmentVerifiedUid;
        return user != null && verified != null && !verified.isEmpty() && verified.equals(user.getUid());
    }

    /**
     * Refreshes the cached FirebaseUser so isEmailVerified() is current for the submission check.
     * Navigation never waits on this and never depends on its result.
     */
    private void refreshVerificationState() {
        FirebaseUser user = auth.getCurrentUser();
        if (user != null) user.reload();
    }

    /**
     * Entry gate for protected screens: a live Firebase session is required, nothing else.
     * Email verification is no longer a navigation gate. It is still enforced where it matters -
     * at report submission, and server-side by the Firestore security rules.
     */
    private void requireSignedInUser(Runnable onSignedIn) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            history.clear();
            loadScreenUnchecked("login_screen", false);
            notifyWeb("Please sign in to continue.");
            return;
        }
        onSignedIn.run();
    }

    private void denyAdminAccess() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            pendingWebMessage = "Sign in with a government account to open the government portal.";
            loadScreenUnchecked("admin_login", false);
            return;
        }
        pendingWebMessage = "This area is only for authorised government accounts.";
        loadScreen("home_screen", false);
    }

    private void notifyWeb(String message) {
        String quoted = JSONObject.quote(message == null ? "" : message);
        runOnUiThread(() -> web.evaluateJavascript("window.ccNativeToast && window.ccNativeToast(" + quoted + ");", null));
    }

    private void setVerificationState(String state, String message) {
        verificationState = state == null ? "" : state;
        verificationMessage = message == null ? "" : message;
        String safeState = JSONObject.quote(state == null ? "" : state);
        String safeMessage = JSONObject.quote(message == null ? "" : message);
        runOnUiThread(() -> web.evaluateJavascript("window.ccVerificationState && window.ccVerificationState(" + safeState + "," + safeMessage + ");", null));
    }

    private void goBackScreen() {
        if (!history.isEmpty()) loadScreen(history.pop(), false);
        else if (!currentScreen.equals("splash_screen_1")) loadScreen("login_screen", false);
        else finish();
    }

    private void injectUniversalUX() {
        String js = """
        (function(){
          if(window.__ccInjected){return;} window.__ccInjected=true;
          const native=window.Android;
          const sym={arrow_back:'←',arrow_forward:'→',menu:'☰',search:'⌕',tune:'☷',help:'?',help_outline:'?',more_vert:'⋮',home:'⌂',add_circle:'＋',map:'▧',notifications:'♧',person:'●',settings:'⚙',logout:'↪',assignment:'▤',leaderboard:'▥',analytics:'▥',dashboard:'▦',language:'文',policy:'▱',lock:'●',visibility_off:'◉',visibility:'◉',account_balance:'▤',badge:'▣',call:'☎',location_on:'●',photo_camera:'▣',center_focus_strong:'⊙',edit:'✎',check_circle:'✓',warning:'!',delete:'⌫',lightbulb:'✦',my_location:'◎',close:'×',thumb_up:'↑',comment:'◌',share:'↗',content_copy:'⧉',calendar_today:'□',schedule:'◷',verified:'✓',shield_lock:'◈',visibility:'◉',expand_more:'⌄',expand_less:'⌃',refresh:'↻',filter_list:'☷',directions_walk:'→',group:'●',local_shipping:'▰',support_agent:'◉',auto_awesome:'✦',construction:'⚒',sync:'↻',local_fire_department:'♨',water_drop:'▼',park:'♣',electric_bolt:'ϟ',directions_car:'→',directions_walk:'→',local_shipping:'▰',campaign:'◈',trending_up:'↗',priority_high:'!',info:'i',close:'×',upload_file:'↑',audiotrack:'♪',content_paste:'▤',pending_actions:'◷',done_all:'✓',check:'✓',more_horiz:'…',admin_panel_settings:'▣',photo:'▧',upload:'↑',send:'➤',build:'⚒',star:'★',emoji_events:'★',help:'?'};
          function iconFontReady(){ try{ return !!(document.fonts && document.fonts.check('24px \"Material Symbols Outlined\"')); }catch(err){ return false; } }
          function icons(){
            var useFont=iconFontReady();
            document.querySelectorAll('.material-symbols-outlined').forEach(function(e){
              var k=(e.dataset.ccIcon||e.dataset.icon||e.textContent||'').trim();
              if(!k) return;
              e.dataset.ccIcon=k;
              if(useFont){ e.textContent=k; e.style.fontFamily=''; e.style.fontWeight=''; e.style.visibility=''; return; }
              if(sym[k]){ e.textContent=sym[k]; e.style.fontFamily='system-ui, sans-serif'; e.style.fontWeight='600'; e.style.fontVariant='normal'; e.style.fontFeatureSettings='normal'; e.style.visibility=''; }
              else { e.textContent=''; e.style.visibility='hidden'; }
            });
          }
          function css(){if(document.getElementById('ccNativeStyle'))return; const s=document.createElement('style');s.id='ccNativeStyle';s.textContent=`
            body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif!important} .material-symbols-outlined{line-height:1;}
            input,select,button{font-size:16px;} button,a{touch-action:manipulation;}
            .cc-modal-backdrop{position:fixed;inset:0;background:rgba(5,26,62,.45);z-index:9998;display:flex;align-items:center;justify-content:center;padding:20px}
            .cc-modal{width:100%;max-width:420px;background:#faf9ff;border-radius:20px;padding:22px;box-shadow:0 20px 60px rgba(0,0,0,.25);color:#051a3e}
            .cc-modal h3{font-size:22px;margin:0 0 12px;font-weight:700}.cc-modal p{line-height:1.5}.cc-modal input,.cc-modal textarea{width:100%;box-sizing:border-box;border:1px solid #dfe1e6;border-radius:10px;padding:12px;margin:6px 0 10px;background:white}.cc-primary{background:#003d9b;color:white;border:0;border-radius:10px;padding:12px 16px;font-weight:700}.cc-secondary{background:#e9edff;color:#003d9b;border:0;border-radius:10px;padding:12px 16px;font-weight:700;margin-left:8px}
            .cc-toast{position:fixed;left:20px;right:20px;bottom:90px;background:#051a3e;color:#fff;border-radius:12px;padding:13px 16px;z-index:10000;text-align:center;box-shadow:0 8px 30px rgba(0,0,0,.25)}
            .cc-searchbar{display:flex;gap:8px;margin:8px 0 14px}.cc-searchbar input{flex:1;border:1px solid #c3c6d6;border-radius:10px;padding:10px 12px;background:#fff}.input-civic{border:1px solid #dfe1e6!important;background:#fff!important;min-height:48px;box-sizing:border-box}.btn-primary{background:#003d9b!important;color:#fff!important;min-height:48px}.cc-drawer-link{cursor:pointer}
          `;document.head.appendChild(s)}
          function toast(t){let old=document.querySelector('.cc-toast');if(old)old.remove();let d=document.createElement('div');d.className='cc-toast';d.textContent=t;document.body.appendChild(d);setTimeout(()=>d.remove(),1800)}
          function modal(title,body,buttons){let b=document.createElement('div');b.className='cc-modal-backdrop';b.innerHTML='<div class="cc-modal"><h3>'+title+'</h3>'+body+'<div style="display:flex;justify-content:flex-end;margin-top:12px">'+buttons+'</div></div>';document.body.appendChild(b);b.addEventListener('click',e=>{if(e.target===b)b.remove()});return b}
          var govScreen=/\\/(dashboard|complaint_queue|complaint_details_2|assign_complaint|update_status|analytics|notifications_2|admin_[a-z_]+)\\//.test(location.pathname);
          function route(name){if(native&&native.openScreen)native.openScreen(name);else location.href='screen://'+name}
          function text(el){return ((el.innerText||el.textContent||'').replace(/\\s+/g,' ').trim()).toLowerCase()}
          // Label text WITHOUT icon glyphs. Every exact-match rule below compares against this,
          // because an icon span inside a control made text() read "<glyph> update password" and
          // broke Update Password, Language Preferences, Data Privacy Policy and Sign Out.
          function label(el){
            if(!el) return '';
            var clone=el.cloneNode(true);
            clone.querySelectorAll('.material-symbols-outlined').forEach(function(x){ x.remove(); });
            return ((clone.textContent||'').replace(/\\s+/g,' ').trim()).toLowerCase();
          }
          function findRoute(el){let a=el.closest('a'); if(a){let h=a.getAttribute('href')||'';if(h.indexOf('screen://')===0)return h.slice(9)} let o=el.getAttribute('onclick')||'';let p=o.indexOf('screen://');if(p>=0){let m=o.slice(p+9).match(/^([a-zA-Z0-9_]+)/);if(m)return m[1]}return null}
          function search(){let existing=document.querySelector('.cc-searchbar');if(existing){existing.remove();return}let host=document.querySelector('main')||document.body;let d=document.createElement('div');d.className='cc-searchbar';d.innerHTML='<input id="ccSearchInput" placeholder="Search…"><button class="cc-primary">Search</button>';host.prepend(d);d.querySelector('button').onclick=()=>{let q=d.querySelector('input').value.toLowerCase();document.querySelectorAll('article,section,[data-searchable]').forEach(x=>{if(x.closest('.cc-drawer'))return;x.style.display=!q||text(x).includes(q)?'':'none'});toast(q?'Showing results for “'+q+'”':'Showing all results')};d.querySelector('input').focus()}
          function filters(){let p=document.querySelector('[data-cc-filters]');if(p){p.remove();return}let d=document.createElement('div');d.dataset.ccFilters='1';d.style.cssText='display:flex;gap:8px;overflow:auto;margin:8px 0 14px';['All','Infrastructure','Cleanliness','Safety','Pending','Resolved'].forEach(x=>{let b=document.createElement('button');b.className='cc-secondary';b.textContent=x;b.onclick=()=>{document.querySelectorAll('article').forEach(a=>a.style.display=x==='All'||text(a).includes(x.toLowerCase())?'':'none')};d.appendChild(b)});(document.querySelector('main')||document.body).prepend(d)}
          function passwordModal(){modal('Update Password','<label>Current password</label><input type="password" id="ccOld"><label>New password</label><input type="password" id="ccNew"><label>Confirm new password</label><input type="password" id="ccConfirm">','<button class="cc-secondary" onclick="this.closest(\\'.cc-modal-backdrop\\').remove()">Cancel</button><button class="cc-primary" id="ccSavePw">Update Password</button>');setTimeout(()=>{let b=document.getElementById('ccSavePw');if(b)b.onclick=()=>{let o=document.getElementById('ccOld').value,n=document.getElementById('ccNew').value,c=document.getElementById('ccConfirm').value;if(!o||!n||n.length<6||n!==c){toast('Enter your current password and matching new passwords (6+ characters)');return}if(native&&native.updatePassword){native.updatePassword(o,n);document.querySelector('.cc-modal-backdrop').remove()}else toast('Password updates are unavailable')}},0)}
          function esc(v){return String(v==null?'':v).replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;')} function editProfile(){var p={};try{p=JSON.parse(native.getCitizenProfile())}catch(err){}modal('Edit Profile','<label>Full name</label><input id="ccName" value="'+esc(p.fullName||p.displayName||'')+'"><label>Neighborhood</label><input id="ccArea" value="'+esc(p.neighborhood||'')+'"><p style="font-size:12px;color:#434654;margin:4px 0 0">Saved to your CHIPCHAP profile.</p>','<button class="cc-secondary" onclick="this.closest(&quot;.cc-modal-backdrop&quot;).remove()">Cancel</button><button class="cc-primary" id="ccSaveProfile">Save Changes</button>');setTimeout(function(){var b=document.getElementById('ccSaveProfile');if(!b)return;b.onclick=function(){var n=document.getElementById('ccName').value.trim();if(!n){toast('Enter your full name');return}if(!native||!native.saveCitizenProfile){toast('Profile saving is unavailable in this build');return}native.saveCitizenProfile(n,document.getElementById('ccArea').value.trim());var back=document.querySelector('.cc-modal-backdrop');if(back)back.remove()}},0)}
          function privacy(){modal('Data privacy','<p>CHIPCHAP stores your profile, the reports you submit and the evidence attached to them in order to run civic reporting. Reports and comments you post are visible to other signed-in CHIPCHAP users.</p><p><strong>A full published privacy policy is not part of this build.</strong> This summary describes current behaviour only.</p>','<button class="cc-primary" onclick="this.closest(&quot;.cc-modal-backdrop&quot;).remove()">Close</button>')}
          function forgot(){modal('Reset Password','<p>Enter your CHIPCHAP email address and we will send Firebase password-reset instructions.</p><input id="ccReset" placeholder="Email" type="email" value="">','<button class="cc-secondary" onclick="this.closest(\\'.cc-modal-backdrop\\').remove()">Cancel</button><button class="cc-primary" id="ccResetSend">Send Link</button>');setTimeout(()=>{let b=document.getElementById('ccResetSend');if(b)b.onclick=()=>{let e=document.getElementById('ccReset').value.trim();if(!e){toast('Enter your email address');return}if(native&&native.sendPasswordReset){native.sendPasswordReset(e);document.querySelector('.cc-modal-backdrop').remove()}else toast('Password reset is unavailable')}},0)}
          function detailsMenu(){var rid=(native&&native.getActiveReportId)?native.getActiveReportId():'';var body=rid?('<p>Report reference: <b>'+rid+'</b></p>'):'<p>Open a stored report first - demo items have no reference to copy or share.</p>';modal('Report options',body,'<button class="cc-secondary" data-cc-copyref="1">Copy reference</button><button class="cc-primary" data-cc-shareref="1">Share</button>')}
          function comments(){var rid=(native&&native.getActiveReportId)?native.getActiveReportId():'';if(!rid){toast('Open a report from the community feed to comment on it.');return}modal('Add Comment','<textarea id="ccComment" rows="4" placeholder="Share a helpful update..."></textarea>','<button class="cc-secondary" onclick="this.closest(&quot;.cc-modal-backdrop&quot;).remove()">Cancel</button><button class="cc-primary" id="ccPostComment">Post Comment</button>');setTimeout(function(){var b=document.getElementById('ccPostComment');if(!b)return;b.onclick=function(){var v=document.getElementById('ccComment').value.trim();if(!v){toast('Write a comment first');return}if(!native||!native.addReportComment){toast('Commenting is unavailable in this build');return}native.addReportComment(rid,v);var back=document.querySelector('.cc-modal-backdrop');if(back)back.remove()}},0)}
          function help(){modal('CHIPCHAP help','<div style="max-height:62vh;overflow:auto;font-size:14px;line-height:1.5;color:#434654"><h4 style="color:#051a3e;margin:0 0 6px">How CHIPCHAP works</h4><ol style="margin:0 0 14px;padding-left:18px"><li>Sign up or log in</li><li>Tap Report</li><li>Capture a photo of the issue</li><li>Choose a category</li><li>Choose a severity</li><li>Add a title and description</li><li>Review the report</li><li>Submit</li><li>Track it in My Reports and Notifications</li><li>Government staff verify, assign and resolve the issue</li></ol><h4 style="color:#051a3e;margin:0 0 6px">Reporting well</h4><ul style="margin:0 0 14px;padding-left:18px"><li>Take one clear photo of the problem itself</li><li>Pick the category that matches best</li><li>Set severity honestly - it affects prioritisation</li><li>Describe what, where and how bad</li><li>Capture location if you can</li><li>Check the review screen before submitting</li></ul><h4 style="color:#051a3e;margin:0 0 6px">Common questions</h4><p><b>Location is turned off.</b> You can still submit. The report is stored without coordinates, so describe the place in the description.</p><p><b>I have no photo.</b> A photo is required - it is the evidence the city works from.</p><p><b>I chose the wrong category.</b> Tap Edit on the review screen before submitting. After submitting, the city can reclassify it.</p><p><b>What does severity mean?</b> Low is cosmetic, Medium needs attention soon, High is disruptive or unsafe, Critical is an immediate danger.</p><p><b>Where are my reports?</b> Profile then My Reports, or the My Reports tile on the dashboard.</p><p><b>What happens after I submit?</b> You get a tracking ID and the report is queued for review. Progress appears in the status timeline.</p><p><b>What do the stages mean?</b> Submitted, then verified by staff, then assigned to a team, then resolved.</p><p><b>How does upvoting work?</b> One vote per person per report. Voting again removes your vote. Votes show how many neighbours are affected.</p><p><b>Already reported by someone else?</b> Upvote it instead of filing a duplicate - that carries more weight.</p><p><b>When it is resolved</b> the status changes to Resolved and the timeline records who closed it and when.</p></div>','<button class="cc-primary" onclick="this.closest(&quot;.cc-modal-backdrop&quot;).remove()">Close</button>')}
          function ccVote(el){var card=el.closest('[data-cc-report]');var id=card?card.getAttribute('data-cc-report'):'';if(!id&&native&&native.getActiveReportId) id=native.getActiveReportId();if(!id||!native||!native.toggleReportVote){toast('This item is not backed by a stored report yet, so it cannot be voted on.');return}var anchor=card||document.querySelector('[data-cc-report="'+id+'"]');var state=anchor?anchor.getAttribute('data-cc-voted'):null;if(state!=='0'&&state!=='1'){toast('Loading votes - try again in a moment.');return}anchor.setAttribute('data-cc-voted','pending');native.toggleReportVote(id,state!=='1')}
          function nativePhotoPreview(data){let img=document.querySelector('[data-alt*="photo"], .group img, img');if(img){img.src=data;img.style.objectFit='cover'}toast('Photo selected')}
          window.nativePhotoSelected=nativePhotoPreview;
          window.ccNativeToast=toast;
          css(); icons(); window.ccIcons=icons;
          try{ if(document.fonts&&document.fonts.ready) document.fonts.ready.then(function(){ icons(); }); }catch(err){}
          document.addEventListener('click',function(e){
            let el=e.target.closest('a,button,article,[role=button]'); if(!el)return; let t=label(el), aria=(el.getAttribute('aria-label')||'').toLowerCase(), icon=el.querySelector('.material-symbols-outlined'); let it=icon?(icon.dataset.ccIcon||'').trim():'';
            if(aria.includes('back')||it==='arrow_back'||t==='arrow_back'){e.preventDefault();e.stopPropagation();if(native)native.goBack();return}
            // Sign-out is checked before link routing: the drawer's "Sign Out" / "Switch Account"
            // links point at screen://login_screen and previously only navigated, leaving the
            // Firebase session signed in.
            if(t==='sign out'||t==='logout'||t==='switch account'||aria.includes('sign out')||aria.includes('logout')||aria.includes('log out')||aria.includes('switch account')){e.preventDefault();e.stopPropagation();native&&native.logout&&native.logout();return}
            let r=findRoute(el);
            if(r){e.preventDefault();e.stopPropagation();route(r);return}
            if(el.tagName==='ARTICLE'&&!el.closest('[data-cc-report]')){if(t.includes('success')||t.includes('impact')){e.preventDefault();route('success_story_detail');return} if(t.includes('pothole')||t.includes('streetlight')||t.includes('graffiti')||t.includes('fallen tree')){e.preventDefault();route(location.pathname.includes('complaint_queue')||location.pathname.includes('dashboard')?'complaint_details_2':'complaint_details_1');return}}
            if(aria.includes('help')||it==='help'||it==='help_outline'||t==='help_outline'){e.preventDefault();help();return}
            if(it==='more_vert'||it==='more_horiz'){e.preventDefault();detailsMenu();return}
            if(aria.includes('menu')||it==='menu'){e.preventDefault(); if(window.ccOpenDrawer)ccOpenDrawer(); else if(window.govOpenDrawer)govOpenDrawer();return}
            if(aria.includes('search')||it==='search'){e.preventDefault();search();return}
            if(aria.includes('filter')||it==='tune'||t==='filter'){e.preventDefault();filters();return}
            if(aria.includes('take photo')||t==='take photo'){e.preventDefault();native&&native.takePhoto();return}
            if(t==='edit profile'){e.preventDefault();editProfile();return}
            if(t==='update password'){e.preventDefault();passwordModal();return}
            if(t==='language preferences'){e.preventDefault();route('language_selection');return}
            if(t==='data privacy policy'||t==='privacy policy'){e.preventDefault();privacy();return}
            if(t==='sign out'||t==='logout'||t==='switch account'||aria.includes('sign out')||aria.includes('logout')||aria.includes('log out')||aria.includes('switch account')){e.preventDefault();e.stopPropagation();native&&native.logout&&native.logout();return}
            if(t==='forgot password?'){e.preventDefault();forgot();return}
            if(t==='more_vert'||it==='more_vert'){e.preventDefault();detailsMenu();return}
            if(t==='add comment'||it==='comment'){e.preventDefault();comments();return}
            if((t==='updates'||t==='messages')&&!location.pathname.includes('/notifications_1/')){e.preventDefault();document.querySelectorAll('[data-tab]').forEach(x=>x.classList.remove('bg-primary','text-on-primary'));el.classList.add('bg-primary','text-on-primary');toast(t==='updates'?'Showing updates':'Showing messages');return}
            if(t==='upvote'||t.includes('upvote')||it==='thumb_up'){e.preventDefault();e.stopPropagation();ccVote(el);return}
            if(t==='city resolved'||t==='my reports'||t==='view map'||t==='map explorer'){e.preventDefault();route(t==='city resolved'?'community_feed':t==='my reports'?'my_reports':'map_explorer');return}
            if(t==='reports'||t==='complaints'||t==='complaint queue'){e.preventDefault();route(govScreen?'complaint_queue':'my_reports');return}
            if(t==='analytics'){e.preventDefault();route('analytics');return}
            if(t==='alerts'||t==='notifications'){e.preventDefault();route(govScreen?'notifications_2':'notifications_1');return}
            if(t==='profile'||t==='admin profile'){e.preventDefault();route(govScreen?'admin_profile':'user_profile');return}
            if(t==='dashboard'||t==='home'){e.preventDefault();route(govScreen?'dashboard':'home_screen');return}
            if(t==='dispatch crew'){e.preventDefault();toast('Crew dispatch is not available in this build.');return}
            if(t==='assign complaint'||t==='assign'||t==='reassign'){e.preventDefault();route('assign_complaint');return}
            if(t.includes('view availability')){e.preventDefault();toast('Worker availability is not tracked in this build.');return}
            if(t==='mark as completed'||t==='mark as final resolution'){e.preventDefault();toast('Status updates are not available yet in this build. Nothing was changed.');return}
            if(t==='performance report'){e.preventDefault();toast('Performance reports are not available in this build.');return}
            if(t==='help & support'){e.preventDefault();help();return}
            if(t==='settings'){e.preventDefault();modal('Settings','<p>Account, notifications and privacy controls are available here.</p>','<button class="cc-primary" onclick="this.closest(&quot;.cc-modal-backdrop&quot;).remove()">Done</button>');return}
            if(t==='ai analysis'||t==='ai analysis complete'){e.preventDefault();route('analytics');return}
            if(t==='view details'||t==='view cluster'||t==='other issues'){e.preventDefault();route('complaint_details_1');return}
            if(t==='continue to government login'){e.preventDefault();route('admin_login');return}
            if(t.includes('create account')){e.preventDefault();signup();return}
            if(t==='login'){e.preventDefault();citizenLogin();return}
            if(t==='resend code'||t.startsWith('resend in')){e.preventDefault();return}
            if(t==='enable all & continue'){e.preventDefault();native&&native.requestAppPermissions();route('onboarding_community_impact');return}
            if(t==='skip for now'){e.preventDefault();route('home_screen');return}
            if(t==='get started'){e.preventDefault();route('app_permissions');return}
            if(t==='skip'||t==='next'||t==='continue'){e.preventDefault();let s=location.pathname.split('/').slice(-2,-1)[0]; if(s==='onboarding_report_easy')route('onboarding_community_impact'); else if(s==='onboarding_community_impact')route('onboarding_real_time_tracking'); else if(s==='onboarding_real_time_tracking')route('app_permissions'); else if(s==='details')route('review_report'); else if(s==='review_report')route('uploading'); else if(s==='ai_analysis')route('details'); else if(s==='app_permissions')route('home_screen'); return}
          },true);
          function signup(){let f=document.getElementById('fullName'),em=document.getElementById('email'),p=document.getElementById('password'),cp=document.getElementById('confirmPassword'),n=document.getElementById('neighborhood');if(!f||!em||!p||!cp||!n)return;[f,em,p,cp,n].forEach(x=>x.style.borderColor='');let emailRe=/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/;if(!f.value.trim()){f.focus();f.style.borderColor='#ba1a1a';toast('Full name is required');return}if(!em.value.trim()||!emailRe.test(em.value.trim())){em.focus();em.style.borderColor='#ba1a1a';toast('Enter a valid email address');return}if(!p.value||p.value.length<6){p.focus();p.style.borderColor='#ba1a1a';toast('Password must be at least 6 characters');return}if(p.value!==cp.value){cp.focus();cp.style.borderColor='#ba1a1a';toast('Passwords do not match');return}if(!n.value){n.focus();n.style.borderColor='#ba1a1a';toast('Select your neighborhood');return}if(native&&native.citizenRegister){toast('Creating account...');native.citizenRegister(f.value.trim(),em.value.trim(),p.value,n.value);}else{toast('Registration is unavailable');}}
          function citizenLogin(){let em=document.getElementById('email'),p=document.getElementById('password');if(em)em.style.borderColor='';if(p)p.style.borderColor='';let emailRe=/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/;if(!em||!em.value.trim()){if(em){em.focus();em.style.borderColor='#ba1a1a';}toast('Enter your email address');return}if(!emailRe.test(em.value.trim())){em.focus();em.style.borderColor='#ba1a1a';toast('Enter a valid email address');return}if(!p||!p.value.trim()){if(p){p.focus();p.style.borderColor='#ba1a1a';}toast('Enter your password');return}if(p.value.length<6){p.focus();p.style.borderColor='#ba1a1a';toast('Password must be at least 6 characters');return}if(native&&native.citizenLogin){toast('Signing in...');native.citizenLogin(em.value.trim(),p.value);}else{route('app_permissions');}}
          if(location.pathname.includes('/signup_screen/')){
            let form=document.querySelector('form');if(form)form.addEventListener('submit',e=>e.preventDefault());
            let action=document.querySelector('main > div:last-child'); if(action){action.style.paddingBottom='18px';action.style.marginTop='24px';action.style.position='static';}
            let fields=['fullName','email','password','confirmPassword','neighborhood'];fields.forEach(id=>{let x=document.getElementById(id);if(x){x.style.border='1px solid #dfe1e6';x.style.borderRadius='10px';x.style.background='#fff';x.style.height='48px';}});
          }
          if(location.pathname.includes('/login_screen/')){let p=document.getElementById('governmentPanel');if(p){p.style.marginBottom='16px';p.style.order='0'} }

          if(location.pathname.includes('/capture_photo/')){document.querySelectorAll('button').forEach(b=>b.style.cursor='pointer')}
          // The mock map preview that used to be drawn here (fake roads, two fake pins,
          // "Civic map - 5 nearby issues" and the geo: intent button) is replaced by the real
          // MapLibre/OpenFreeMap map mounted on the Stitch map surface in injectCitizenScreens.
          icons();
        })();
        """;
        web.evaluateJavascript(js, null);
    }


    /**
     * Wires the existing capture / details / review / success Stitch screens to the native
     * complaint draft. No Stitch asset is modified: this runs after injectUniversalUX on each page
     * load, the same pattern the app already uses for behaviour the static screens do not have.
     *
     * The listener is registered on window with capture=true so it runs before the universal
     * document listener, which would otherwise follow the screens' hard-coded navigation.
     */
    private void injectComplaintFlow() {
        String js = """
        (function(){
          if(window.__ccFlow){return;} window.__ccFlow=true;
          var native=window.Android;
          function here(n){return location.pathname.indexOf('/'+n+'/')>=0;}
          function txt(el){return el?((el.innerText||el.textContent||'').replace(/\\s+/g,' ').trim()):'';}
          function low(el){return txt(el).toLowerCase();}
          function toast(t){ if(t&&window.ccNativeToast) window.ccNativeToast(t); }
          function stop(e){ e.preventDefault(); e.stopPropagation(); if(e.stopImmediatePropagation) e.stopImmediatePropagation(); }
          function draft(){ try{ return JSON.parse(native.getComplaintDraft()); }catch(e){ return {}; } }
          function go(n){ if(native&&native.openScreen) native.openScreen(n); }
          function setTail(el,value){
            if(!el) return;
            for(var i=el.childNodes.length-1;i>=0;i--){
              var node=el.childNodes[i];
              if(node.nodeType===3 && node.nodeValue.trim()!==''){ node.nodeValue=' '+value; return; }
            }
            el.appendChild(document.createTextNode(' '+value));
          }
          function articleBy(word){
            var list=document.querySelectorAll('article');
            for(var i=0;i<list.length;i++){
              var h=list[i].querySelector('h2');
              if(h&&low(h).indexOf(word)>=0) return list[i];
            }
            return null;
          }

          // ---------------- native -> web ----------------
          window.chipchapPhotoCaptured=function(silent){
            var preview=(native&&native.getPhotoPreview)?native.getPhotoPreview():'';
            var img=document.querySelector('main img');
            if(img&&preview){ img.src=preview; img.style.objectFit='cover'; img.style.opacity=''; img.removeAttribute('data-alt'); }
            // Tap the captured photo for a full-size view. The viewfinder box is tagged because its
            // decorative overlay sits on top of the image and receives the tap.
            if(img&&preview) markPhotoOpen(img.parentElement||img);
            var bar=document.querySelector('[data-cc-continue]');
            if(bar) bar.style.display='';
            if(!silent) toast('Photo captured. Checking it...');
          };

          // ---------------- AI photo check (AI checkpoint 2) ----------------
          // The native side decides everything; this only renders its state. The AI reason is
          // inserted with textContent, never as HTML.
          function photoCheck(){
            try{ return JSON.parse((native&&native.getPhotoCheck)?native.getPhotoCheck():'{}')||{}; }catch(err){ return {}; }
          }
          function aiButton(id,label,primary){
            var b=document.createElement('button');
            b.id=id; b.type='button'; b.textContent=label;
            b.style.cssText='flex:1;border:0;border-radius:12px;padding:12px;font-weight:700;font-size:15px;'
              +(primary?'background:#003d9b;color:#fff':'background:#e9edff;color:#003d9b');
            return b;
          }
          function renderPhotoCheck(d){
            d=d||{};
            if(here('details')){ renderAiCategory(); return; }
            var bar=document.querySelector('[data-cc-continue]');
            var box=document.querySelector('[data-cc-aistatus]');
            if(!bar||!box) return;
            var use=document.getElementById('ccUsePhoto');
            var img=document.querySelector('main img');
            var st=d.state||'';
            box.innerHTML='';
            if(!st){ box.style.display='none'; if(use) use.style.display=''; return; }
            bar.style.display='';
            box.style.display='';
            var title=document.createElement('div');
            title.style.cssText='font-weight:700;font-size:15px;color:#051a3e;margin-bottom:4px';
            var line=document.createElement('div');
            line.style.cssText='font-size:14px;line-height:1.45;color:#434654';
            box.appendChild(title); box.appendChild(line);
            var canUse=(st==='accepted'||st==='skipped');
            if(use){
              use.style.display=(st==='rejected'||st==='unavailable')?'none':'';
              use.style.opacity=canUse?'':'0.45';
              use.setAttribute('aria-disabled',canUse?'false':'true');
            }
            if(img) img.style.opacity=(st==='rejected')?'0.35':'';
            if(st==='checking'){
              title.textContent='Checking photo...';
              line.textContent='This takes a few seconds.';
            } else if(st==='accepted'){
              title.textContent='Photo looks suitable';
              line.textContent=d.category?('AI-suggested category: '+d.category):'Choose the category on the next screen.';
            } else if(st==='rejected'){
              title.textContent='Photo cannot be used';
              title.style.color='#ba1a1a';
              line.textContent=(d.message||'This photo does not appear to show a recognizable civic issue.')+' Take another photo with the camera button.';
            } else if(st==='unavailable'){
              title.textContent='Photo could not be checked';
              line.textContent=d.message||'The AI photo check is not available right now.';
              var row=document.createElement('div');
              row.style.cssText='display:flex;gap:8px;margin-top:10px';
              row.appendChild(aiButton('ccAiRetry','Retry',true));
              row.appendChild(aiButton('ccAiSkip','Continue without AI check',false));
              box.appendChild(row);
            } else if(st==='skipped'){
              title.textContent='Continuing without AI check';
              line.textContent='This photo was not checked by AI. You will choose the category yourself.';
            }
          }
          window.chipchapPhotoCheck=function(json){
            var d={}; try{d=JSON.parse(json)}catch(err){}
            renderPhotoCheck(d);
          };
          // Details screen: the Stitch "AI ANALYSIS" category card shows the AI suggestion, but
          // the citizen's own choice is never replaced.
          function renderAiCategory(){
            var pc=photoCheck();
            var cat=categoryValueEl();
            var note=document.querySelector('[data-cc-catnote]');
            var badge=document.querySelector('[data-cc-catbadge]');
            var current=cat?txt(cat):'';
            var suggestion=(pc.state==='accepted'&&pc.category)?pc.category:'';
            var badgeText='CATEGORY', noteText='Choose the category that best matches this issue.';
            if(suggestion&&current===suggestion){
              badgeText='AI SUGGESTED';
              noteText='AI-suggested from your photo. Tap Edit to change it.';
            } else if(suggestion){
              noteText='AI suggests: '+suggestion+'. Your selected category: '+(current||'none')+'. Your choice is kept; tap Edit to change it.';
            } else if(pc.state==='accepted'){
              noteText='Photo looks suitable. Choose the category that best matches this issue.';
            } else if(pc.state==='skipped'){
              noteText='This photo was not checked by AI. Choose the category yourself.';
            }
            if(badge) badge.textContent=badgeText;
            if(note) note.textContent=noteText;
          }
          window.chipchapLocation=function(status){
            var el=document.querySelector('[data-cc-location]');
            if(el) el.textContent=status||'';
            // The incident map (details) follows a new device fix; a pin the citizen placed stays.
            if(window.ccPinMapSync) window.ccPinMapSync();
          };

          // ---- Full-size photo viewer (capture + review). Display only: it never captures,
          // ---- uploads, re-checks with AI, saves or navigates. Closing returns to the same state.
          function closePhotoViewer(){
            var v=document.querySelector('[data-cc-photoviewer]');
            if(!v) return false;
            v.remove();
            return true;
          }
          window.ccClosePhotoViewer=closePhotoViewer;
          function openPhotoViewer(){
            var uri=(native&&native.getPhotoPreview)?native.getPhotoPreview():'';
            if(!uri||uri.indexOf('data:image/')!==0){ toast('No photo is available to preview.'); return; }
            closePhotoViewer();
            var v=document.createElement('div');
            v.setAttribute('data-cc-photoviewer','1');
            v.setAttribute('role','dialog'); v.setAttribute('aria-label','Photo preview');
            v.style.cssText='position:fixed;inset:0;z-index:10000;background:rgba(5,26,62,.92);display:flex;align-items:center;justify-content:center;padding:16px';
            var img=document.createElement('img');
            img.alt='Report photo'; img.src=uri;
            img.style.cssText='max-width:100%;max-height:100%;object-fit:contain;border-radius:12px';
            img.onerror=function(){ closePhotoViewer(); toast('The photo could not be displayed.'); };
            var x=document.createElement('button');
            x.type='button'; x.setAttribute('aria-label','Close photo preview'); x.textContent='×';
            x.style.cssText='position:absolute;top:16px;right:16px;width:44px;height:44px;border-radius:50%;border:0;background:rgba(255,255,255,.92);color:#051a3e;font-size:26px;line-height:1';
            v.appendChild(img); v.appendChild(x);
            document.body.appendChild(v);
          }
          function markPhotoOpen(el){ if(!el) return; el.setAttribute('data-cc-photoopen','1'); el.style.cursor='zoom-in'; }
          window.chipchapSetTrackingId=function(id){
            var nodes=document.querySelectorAll('span,div,p,h3');
            for(var i=0;i<nodes.length;i++){
              if(nodes[i].children.length===0 && txt(nodes[i]).indexOf('#CCP-')===0){
                nodes[i].textContent='#'+id; return;
              }
            }
          };

          // ---------------- capture_photo ----------------
          function setupCapture(){
            var host=document.querySelector('main');
            if(!host) return;
            var bar=document.createElement('div');
            bar.setAttribute('data-cc-continue','1');
            bar.style.cssText='padding:0 16px 20px;display:none';
            bar.innerHTML='<button id="ccUsePhoto" style="width:100%;background:#003d9b;color:#fff;border:0;border-radius:12px;padding:15px;font-weight:700;font-size:16px">Use this photo</button>';
            // AI photo check status, above the existing button and in the same style.
            var status=document.createElement('div');
            status.setAttribute('data-cc-aistatus','1');
            status.style.cssText='display:none;background:#f2f3fb;border:1px solid #dfe1e6;border-radius:12px;padding:12px 14px;margin-bottom:12px';
            bar.insertBefore(status,bar.firstChild);
            host.appendChild(bar);
            if(draft().hasPhoto) window.chipchapPhotoCaptured(true);
            renderPhotoCheck(photoCheck());
          }

          // ---------------- details ----------------
          function categoryValueEl(){ return document.querySelector('[data-cc-category]'); }

          // ---- AI report-text draft: fills ONLY empty, untouched fields, once; the citizen stays in control ----
          function draftState(){
            try{ return JSON.parse((native&&native.getDraftText)?native.getDraftText():'{}')||{}; }catch(err){ return {}; }
          }
          function draftNote(text){
            var t=document.getElementById('ccTitle'); var host=t?t.parentElement:null; if(!host) return;
            var n=host.querySelector('[data-cc-aidraft]');
            if(!text){ if(n) n.remove(); return; }
            if(!n){ n=document.createElement('p'); n.setAttribute('data-cc-aidraft','1');
              n.style.cssText='font-size:12px;line-height:1.4;color:#434654;margin:6px 0 0'; host.appendChild(n); }
            n.textContent=text;
          }
          function watchDraftEdits(){
            ['ccTitle','desc'].forEach(function(id){
              var el=document.getElementById(id);
              if(el&&!el.getAttribute('data-cc-watch')){
                el.setAttribute('data-cc-watch','1');
                el.addEventListener('input',function(){ el.setAttribute('data-cc-edited','1'); draftNote(''); });
              }
            });
          }
          function applyDraftText(){
            var t=document.getElementById('ccTitle'); var d=document.getElementById('desc');
            if(!t||!d) return;
            var s=draftState();
            var tOpen=!t.value.trim() && !t.getAttribute('data-cc-edited');
            var dOpen=!d.value.trim() && !d.getAttribute('data-cc-edited');
            if(s.state==='unavailable'){ draftNote((tOpen||dOpen)?"AI couldn't generate the report text. You can enter it manually.":''); return; }
            if(s.state!=='ready'||s.handled){ draftNote(''); return; }
            var filled=false;
            if(tOpen&&s.title){ t.value=s.title; filled=true; }
            if(dOpen&&s.description){ d.value=s.description; filled=true; }
            if(native&&native.markDraftTextHandled) native.markDraftTextHandled(s.request);
            draftNote(filled?'Drafted by AI from your photo. Please check it and edit anything that is not right.':'');
          }

          function setupDetails(){
            var d=draft();

            // The AI phase is not implemented, so remove the claim that a photo was analysed.
            var paras=document.querySelectorAll('p');
            for(var i=0;i<paras.length;i++){
              var t=txt(paras[i]);
              if(t.indexOf('AI-suggested')>=0) paras[i].textContent='Verify the location and confirm the category.';
              if(t.indexOf('categorized this automatically')>=0){ paras[i].setAttribute('data-cc-catnote','1'); paras[i].textContent='Choose the category that best matches this issue.'; }
              if(t.indexOf('Pan map')>=0){ paras[i].setAttribute('data-cc-location','1'); paras[i].textContent=d.locationStatus||'Location has not been captured yet.'; }
            }

            // Category card: tag the value and its Edit button, drop the AI badge wording.
            var labels=document.querySelectorAll('label');
            for(var j=0;j<labels.length;j++){
              if(low(labels[j])!=='category') continue;
              var wrap=labels[j].parentElement;
              var edit=wrap?wrap.querySelector('button'):null;
              if(edit){
                edit.setAttribute('data-cc-catedit','1');
                var value=edit.parentElement?edit.parentElement.querySelector('span'):null;
                if(value) value.setAttribute('data-cc-category','1');
              }
              var badges=wrap?wrap.querySelectorAll('span'):[];
              for(var k=0;k<badges.length;k++){ if(txt(badges[k])==='AI ANALYSIS'){ badges[k].setAttribute('data-cc-catbadge','1'); badges[k].textContent='CATEGORY'; } }
            }

            // Title input: the Stitch screen has no title field, and the report model requires one.
            var descLabel=document.querySelector('label[for=desc]');
            if(descLabel && !document.getElementById('ccTitle')){
              var headerRow=descLabel.parentElement;
              var section=headerRow?headerRow.parentElement:null;
              var marks=headerRow?headerRow.querySelectorAll('span'):[];
              for(var m=0;m<marks.length;m++){ if(txt(marks[m])==='Optional') marks[m].textContent='Required'; }
              if(section&&section.parentElement){
                var block=document.createElement('div');
                block.style.cssText='margin-bottom:24px';
                block.innerHTML='<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">'
                  +'<label for="ccTitle" style="font-weight:600;color:#051a3e">Title</label>'
                  +'<span style="font-size:12px;color:#ba1a1a;font-weight:600">Required</span></div>'
                  +'<input id="ccTitle" class="input-civic" maxlength="120" placeholder="Short summary of the issue" '
                  +'style="width:100%;box-sizing:border-box;border-radius:10px;padding:12px">';
                section.parentElement.insertBefore(block, section);
              }
            }

            // Restore anything already in the draft.
            var title=document.getElementById('ccTitle');
            if(title&&d.title) title.value=d.title;
            var desc=document.getElementById('desc');
            if(desc&&d.description) desc.value=d.description;
            watchDraftEdits();
            applyDraftText();
            var cat=categoryValueEl();
            if(cat&&d.category) cat.textContent=d.category;
            // AI suggestion: shown only when the citizen has not chosen a category yet, labelled as
            // AI-suggested and editable. A category the citizen already chose is never replaced.
            var pcheck=photoCheck();
            if(cat&&!d.category&&pcheck.state==='accepted'&&pcheck.category) cat.textContent=pcheck.category;
            renderAiCategory();
            if(d.severity){
              var radio=document.querySelector('input[name=severity][value="'+d.severity+'"]');
              if(radio) radio.checked=true;
            }

            // Severity reaches the native draft the moment it changes, so a later re-render or a
            // navigation cannot silently drop the citizen's choice back to the default.
            document.querySelectorAll('input[name=severity]').forEach(function(radio){
              radio.addEventListener('change',function(){
                if(radio.checked && native && native.setDraftSeverity) native.setDraftSeverity(radio.value);
              });
            });
            var chosen=document.querySelector('input[name=severity]:checked');
            if(chosen && native && native.setDraftSeverity) native.setDraftSeverity(chosen.value);

            // Location is requested here because this is the screen that shows it.
            if(!d.hasCoordinates && native && native.requestComplaintLocation) native.requestComplaintLocation();
          }

          function categoryPicker(){
            var options=['Infrastructure','Cleanliness','Safety','Other'];
            var back=document.createElement('div');
            back.setAttribute('data-cc-picker','1');
            back.style.cssText='position:fixed;inset:0;background:rgba(5,26,62,.45);z-index:9998;display:flex;align-items:flex-end;justify-content:center';
            var html='<div style="width:100%;max-width:480px;background:#faf9ff;border-radius:20px 20px 0 0;padding:20px;color:#051a3e">'
              +'<h3 style="font-size:20px;font-weight:700;margin:0 0 14px">Choose a category</h3>';
            for(var i=0;i<options.length;i++){
              html+='<button data-cc-cat="'+options[i]+'" style="display:block;width:100%;text-align:left;background:#e9edff;border:0;border-radius:12px;padding:14px;margin-bottom:10px;font-weight:600;font-size:16px;color:#003d9b">'+options[i]+'</button>';
            }
            back.innerHTML=html+'</div>';
            document.body.appendChild(back);
          }

          function saveDetails(){
            if(!native||!native.saveComplaintDetails){ toast('This build cannot save report details.'); return; }
            var title=document.getElementById('ccTitle');
            var desc=document.getElementById('desc');
            var cat=categoryValueEl();
            var sev=document.querySelector('input[name=severity]:checked');
            var result={};
            try{
              // sev is only sent when a radio is actually checked, so a missed read can never
              // clobber the value the change listener already pushed to the native draft.
              result=JSON.parse(native.saveComplaintDetails(
                title?title.value:'', desc?desc.value:'', cat?txt(cat):'', sev?sev.value:null));
            }catch(e){ result={ok:false,message:'Could not save the report details.'}; }
            if(result.ok) go('review_report');
            else toast(result.message||'Please complete the required fields.');
          }

          // ---------------- review_report ----------------
          function setupReview(){
            var d=draft();
            var evidence=articleBy('location');
            if(evidence){
              var img=evidence.querySelector('img');
              var preview=(native&&native.getPhotoPreview)?native.getPhotoPreview():'';
              if(img){
                if(preview){ img.src=preview; img.style.objectFit='cover'; img.removeAttribute('data-alt'); markPhotoOpen(img); }
                else { img.style.display='none'; }
              }
              var spans=evidence.querySelectorAll('span');
              for(var i=0;i<spans.length;i++){
                var t=txt(spans[i]);
                if(t.indexOf('Coordinates')===0) spans[i].textContent=d.hasCoordinates
                  ? ('Coordinates: '+Number(d.latitude).toFixed(5)+', '+Number(d.longitude).toFixed(5))
                  : 'Coordinates: not captured';
                else if(t.indexOf('Civic Plaza')>=0) spans[i].textContent=d.address?d.address:'Address not captured';
                else if(t.indexOf('Metropolis')>=0) spans[i].textContent=d.locationStatus||'';
              }
              // The caption is the text node beside the photo_camera icon. Matching the first
              // ancestor div that merely CONTAINS the phrase wrote into a wrapper instead, which
              // is what produced the duplicated text under the badge.
              var badge=null;
              var icons=evidence.querySelectorAll('.material-symbols-outlined');
              for(var b=0;b<icons.length;b++){
                if((icons[b].dataset.ccIcon||'')==='photo_camera'){ badge=icons[b].parentElement; break; }
              }
              if(badge) setTail(badge, preview?'1 Photo attached':'No photo attached');
            }
            var classification=articleBy('classification');
            if(classification){
              var rows=classification.querySelectorAll('span');
              for(var j=0;j<rows.length;j++){
                // Compared lower-case on purpose: these two labels carry Tailwind's "uppercase"
                // class, and innerText returns the CSS-transformed text, so an exact match against
                // 'Category'/'Severity' never fired and both values stayed at their Stitch defaults.
                var label=txt(rows[j]).toLowerCase();
                if(label!=='category'&&label!=='severity') continue;
                var holder=rows[j].parentElement?rows[j].parentElement.querySelector('div'):null;
                if(!holder) continue;
                holder.setAttribute(label==='category'?'data-cc-category-value':'data-cc-severity-value','1');
                if(label==='category') setTail(holder, d.category||'Not set');
                else setTail(holder, d.severity?(d.severity.charAt(0).toUpperCase()+d.severity.slice(1)):'Not set');
              }
            }
            var description=articleBy('description');
            if(description){
              var body=description.querySelector('p');
              if(body) body.textContent=d.description||'No description was provided.';
            }
            checkSimilar();
          }

          // ---------------- Checkpoint 2: possible similar reports (review step) ----------------
          // Read-only and advisory: the native side checks the stored draft's location + category
          // against nearby open reports. Submission is never blocked or changed by it.
          var similarSeq=0;
          var SIMILAR_STATUS={SUBMITTED:'Submitted',UNDER_REVIEW:'Under review',ASSIGNED:'Assigned',IN_PROGRESS:'In progress'};
          function checkSimilar(){
            if(!native||!native.checkSimilarReports) return;
            similarSeq++;
            renderSimilar({state:'checking'});
            native.checkSimilarReports(similarSeq);
          }
          function similarCard(){
            var card=document.querySelector('[data-cc-similar]');
            if(card) return card;
            var after=articleBy('description');
            if(!after||!after.parentElement) return null;
            // Same Stitch card as the other review sections.
            card=document.createElement('article');
            card.setAttribute('data-cc-similar','1');
            card.className='bg-surface rounded-xl shadow-[0px_4px_12px_rgba(9,30,66,0.08)] p-md flex flex-col gap-sm';
            after.parentElement.insertBefore(card, after.nextSibling);
            return card;
          }
          function similarEl(tag,cls,text){ var e=document.createElement(tag); if(cls) e.className=cls; if(text!=null) e.textContent=text; return e; }
          function similarMeta(c){
            var parts=[c.category||'', SIMILAR_STATUS[String(c.status||'').toUpperCase()]||'Open'];
            var m=Number(c.distanceMeters);
            if(isFinite(m)) parts.push(m<1?'less than 1 m away':('about '+Math.round(m)+' m away'));
            if(c.createdAt){ try{ parts.push('reported '+new Date(c.createdAt).toLocaleDateString('en-IN',{day:'numeric',month:'short',year:'numeric'})); }catch(err){} }
            return parts.filter(function(x){ return !!x; }).join(' · ');
          }
          function renderSimilar(d){
            d=d||{};
            var card=similarCard(); if(!card) return;
            card.innerHTML='';
            var h=similarEl('h2','font-headline-sm text-headline-sm text-on-surface flex items-center gap-xs');
            var icon=similarEl('span','material-symbols-outlined text-[20px] text-civic-blue-dark','manage_search');
            h.appendChild(icon); h.appendChild(document.createTextNode(' Possible similar reports'));
            card.appendChild(h);
            var radius=Number(d.radiusMeters)||100;
            var note=similarEl('p','font-body-sm text-body-sm text-on-surface-variant');
            var list=d.candidates||[];
            if(d.state==='checking') note.textContent='Checking for open reports of the same category nearby...';
            else if(d.state==='found') note.textContent='These open reports have the same category within about '+radius+' m. They may or may not be the same issue. You can open one to support it, or submit your own report.';
            else if(d.state==='none') note.textContent='No open reports of the same category were found within about '+radius+' m.';
            else note.textContent=d.message||"Couldn't check for similar reports. You can still submit your report.";
            card.appendChild(note);
            if(d.state==='found'){
              list.forEach(function(c){
                var item=similarEl('div','bg-surface-gray p-sm rounded-lg border border-border-subtle/50 flex flex-col gap-xs');
                item.appendChild(similarEl('p','font-body-md text-body-md text-on-surface font-semibold',c.title||'Untitled report'));
                item.appendChild(similarEl('p','font-body-sm text-body-sm text-on-surface-variant',similarMeta(c)));
                var open=similarEl('button','font-label-md text-label-md text-primary flex items-center gap-xs bg-transparent border-0 self-start','View report');
                open.type='button'; open.setAttribute('data-cc-similaropen',String(c.reportId||''));
                item.appendChild(open);
                card.appendChild(item);
              });
            }
            if((d.state==='found'||d.state==='none')&&d.limitReached){
              card.appendChild(similarEl('p','font-body-sm text-body-sm text-on-surface-variant',
                'This area has many reports and only the first '+(d.checked||'')+' found were checked, so other similar reports may exist.'));
            }
            if(window.ccIcons) window.ccIcons();
          }
          window.chipchapSimilarReports=function(seq,json){
            if(!here('review_report')||seq!==similarSeq) return;   // left the step, or an older request
            var d={}; try{ d=JSON.parse(json)||{}; }catch(err){ d={state:'error'}; }
            renderSimilar(d);
          };

          // ---------------- one capture-phase listener ----------------
          window.addEventListener('click',function(e){
            // Photo viewer first: open from a tagged photo, close on any tap inside the viewer.
            var pv=(e.target&&e.target.closest)?e.target.closest('[data-cc-photoviewer],[data-cc-photoopen]'):null;
            if(pv){ stop(e); if(pv.hasAttribute('data-cc-photoviewer')) closePhotoViewer(); else openPhotoViewer(); return; }
            var target=(e.target&&e.target.closest)?e.target.closest('a,button,[role=button]'):null;
            if(!target) return;

            var chosen=target.getAttribute('data-cc-cat');
            if(chosen){
              stop(e);
              var value=categoryValueEl();
              if(value) value.textContent=chosen;
              var picker=document.querySelector('[data-cc-picker]');
              if(picker) picker.remove();
              renderAiCategory();
              return;
            }
            if(target.getAttribute('data-cc-catedit')){ stop(e); categoryPicker(); return; }
            if(target.id==='ccUsePhoto'){
              stop(e);
              var pc=photoCheck();
              if(pc.state==='accepted'||pc.state==='skipped') go('details');
              else toast(pc.state==='checking'?'Wait for the photo check to finish.':'This photo cannot be used yet.');
              return;
            }
            if(target.id==='ccAiRetry'){ stop(e); if(native&&native.retryPhotoCheck) native.retryPhotoCheck(); return; }
            if(target.id==='ccAiSkip'){ stop(e); if(native&&native.continueWithoutPhotoCheck) native.continueWithoutPhotoCheck(); return; }

            var t=low(target);
            var aria=(target.getAttribute('aria-label')||'').toLowerCase();

            if(here('details')){
              if(aria.indexOf('current location')>=0){ stop(e); if(native&&native.requestComplaintLocation) native.requestComplaintLocation(); return; }
              if(t.indexOf('continue')===0){ stop(e); saveDetails(); return; }
            }
            var similarId=target.getAttribute('data-cc-similaropen');
            if(similarId){
              stop(e);
              // Opens the stored report on the existing citizen report screen (where it can be
              // supported with the existing vote). Back returns here with the draft untouched.
              if(native&&native.setActiveReport){ native.setActiveReport(similarId); go('complaint_details_1'); }
              return;
            }
            if(here('review_report')){
              if(t.indexOf('submit report')>=0){ stop(e); if(native&&native.submitComplaint) native.submitComplaint(); return; }
              if(t.indexOf('edit')>=0){
                stop(e);
                var card=target.closest('article');
                var heading=card?low(card.querySelector('h2')):'';
                go(heading.indexOf('location')>=0?'capture_photo':'details');
                return;
              }
            }
          },true);

          if(here('capture_photo')) setupCapture();
          if(here('details')) setupDetails();
          if(here('review_report')) setupReview();
        })();
        """;
        web.evaluateJavascript(js, null);
    }


    /**
     * Wires the citizen dashboard, community feed, report details and language screens to real
     * Firebase data. Runs after injectUniversalUX and injectComplaintFlow on every page load.
     * No Stitch asset is modified.
     */
    private void injectCitizenScreens() {
        // One IIFE, held in two text blocks joined at run time: a single Java string constant is
        // limited to 65,535 bytes, and this script is now longer than that. The joined text is
        // exactly the script the two parts spell out, in order.
        String js = String.join("", """
        (function(){
          if(window.__ccCitizen){return;} window.__ccCitizen=true;
          var native=window.Android;
          function here(n){return location.pathname.indexOf('/'+n+'/')>=0;}
          function txt(el){return el?((el.textContent||'').replace(/\\s+/g,' ').trim()):'';}
          function toast(t){ if(t&&window.ccNativeToast) window.ccNativeToast(t); }
          function esc(v){return String(v==null?'':v).replace(/&/g,'&amp;').replace(/</g,'&lt;');}
          function cap(v){ v=String(v||''); return v?v.charAt(0).toUpperCase()+v.slice(1):''; }
          function setTail(el,value){
            if(!el) return;
            for(var i=el.childNodes.length-1;i>=0;i--){
              var n=el.childNodes[i];
              if(n.nodeType===3 && n.nodeValue.trim()!==''){ n.nodeValue=' '+value; return; }
            }
            el.appendChild(document.createTextNode(' '+value));
          }
          // Navigation helper for this script. It previously lived only inside injectComplaintFlow,
          // so Track Status and the map's New Report button threw a ReferenceError here.
          function go(n){ if(native&&native.openScreen) native.openScreen(n); }
          var STATUS_LABELS={SUBMITTED:'Submitted',UNDER_REVIEW:'Under review',ASSIGNED:'Assigned',IN_PROGRESS:'In progress',RESOLVED:'Resolved'};
          function statusLabel(s){ s=String(s||'').trim(); if(!s) return 'Submitted'; return STATUS_LABELS[s.toUpperCase()]||cap(s.toLowerCase().replace(/_/g,' ')); }
          function fmtDate(ms){
            if(!ms) return 'Date unavailable';
            try{ return new Date(ms).toLocaleDateString('en-IN',{day:'numeric',month:'short',year:'numeric'}); }
            catch(err){ return new Date(ms).toDateString(); }
          }
          function fmtDateTime(ms){
            if(!ms) return '';
            try{ return new Date(ms).toLocaleString('en-IN',{day:'numeric',month:'short',year:'numeric',hour:'numeric',minute:'2-digit'}); }
            catch(err){ return new Date(ms).toString(); }
          }
          // Stored address, else the stored coordinates, else an honest fallback. Never a demo place.
          function locationText(r){
            var a=String((r&&r.address)||'').trim();
            if(a) return a;
            if(r&&typeof r.latitude==='number'&&typeof r.longitude==='number') return r.latitude.toFixed(5)+', '+r.longitude.toFixed(5);
            return 'Location unavailable';
          }
          // Text that belongs to the element itself, ignoring child elements such as icons and dots.
          function ownText(el){
            var t='';
            for(var i=0;i<el.childNodes.length;i++){ if(el.childNodes[i].nodeType===3) t+=el.childNodes[i].nodeValue; }
            return t.replace(/\\s+/g,' ').trim();
          }
          function directIcon(el){
            for(var i=0;i<el.children.length;i++){ if(el.children[i].classList.contains('material-symbols-outlined')) return el.children[i]; }
            return null;
          }
          function iconKey(icon){ return icon?String(icon.dataset.ccIcon||icon.dataset.icon||icon.textContent||'').trim():''; }
          // Replaces the element's own text but keeps its icon/dot children intact.
          function setOwnText(el,value){ if(el.children.length) setTail(el,value); else el.textContent=value; }

          // A drawn placeholder map. Not live data, and it carries no issue markers.
          function mapPlaceholder(caption){
            var d=document.createElement('div');
            d.setAttribute('data-cc-map','1');
            d.style.cssText='width:100%;height:100%;min-height:170px;position:relative;overflow:hidden;border-radius:inherit;'
              +'background:linear-gradient(90deg,#dfe8dc 1px,transparent 1px),linear-gradient(#dfe8dc 1px,transparent 1px),#eef3ea;background-size:40px 40px';
            d.innerHTML='<div style="position:absolute;left:5%;top:24%;width:94%;height:13px;background:#fff;transform:rotate(11deg);box-shadow:0 0 0 1px #d1d6c8"></div>'
              +'<div style="position:absolute;left:0;top:60%;width:100%;height:11px;background:#fff;transform:rotate(-7deg);box-shadow:0 0 0 1px #d1d6c8"></div>'
              +'<div style="position:absolute;left:42%;top:0;width:11px;height:100%;background:#fff;transform:rotate(5deg);box-shadow:0 0 0 1px #d1d6c8"></div>'
              +'<div style="position:absolute;left:8px;bottom:8px;right:8px;background:rgba(255,255,255,.92);padding:6px 9px;border-radius:9px;font-size:11px;color:#434654">'+esc(caption)+'</div>';
            return d;
          }

          // ---------------- native -> web ----------------
          window.chipchapProfile=function(json){
            var p={}; try{p=JSON.parse(json)}catch(err){}
            var el=document.querySelector('[data-cc-greeting]');
            if(el){
              var hour=new Date().getHours();
              var part=hour<12?'Good morning':(hour<18?'Good afternoon':'Good evening');
              var name=(p.displayName||'').trim();
              el.textContent=name?(part+', '+name+'.'):(part+'.');
            }
            var nameEl=document.querySelector('[data-cc-profilename]');
            if(nameEl) nameEl.textContent=(p.displayName||'').trim()||'Your profile';
            var mailEl=document.querySelector('[data-cc-profileemail]');
            if(mailEl) mailEl.textContent=(p.email||'').trim();
            var avatar=document.querySelector('[data-cc-avatar]');
            if(avatar){
              var who=((p.displayName||'').trim()||(p.email||'').trim());
              avatar.textContent=who?who.charAt(0).toUpperCase():'';
            }
          };

          // n = the citizen's own reports, resolved = how many of THOSE have status RESOLVED.
          // -1 means the reports could not be read: nothing is guessed, the tiles stay "--".
          window.chipchapMyReportCount=function(n,resolved){
            var known=(n!=null&&n>=0);
            var el=document.querySelector('[data-cc-myreports]');
            if(el) el.textContent=known?String(n):'--';
            var cr=document.querySelector('[data-cc-cityresolved]');
            if(cr) cr.textContent=(known&&resolved!=null&&resolved>=0)?String(resolved):'--';
            // First Report is earned only by a real stored report of this citizen.
            setAchievement('first', known&&n>=1, known?'Submit your first report to earn this':'Could not be checked right now');
          };
          // Achievement tiles keep their Stitch design; a tile that is not earned is shown dimmed
          // with an honest line instead of the "earned" text.
          function setAchievement(key,earned,lockedText){
            var tile=document.querySelector('[data-cc-achievement="'+key+'"]');
            if(!tile) return;
            var sub=tile.querySelector('[data-cc-achievementtext]');
            if(earned){
              tile.style.opacity=''; tile.style.filter='';
              if(sub) sub.textContent=sub.getAttribute('data-cc-achievementtext');
              tile.setAttribute('data-cc-earned','1');
            } else {
              tile.style.opacity='0.5'; tile.style.filter='grayscale(1)';
              if(sub) sub.textContent=lockedText;
              tile.removeAttribute('data-cc-earned');
            }
          }

          window.chipchapCommunityFeed=function(json){
            var d={}; try{d=JSON.parse(json)}catch(err){}
            var host=document.querySelector('[data-cc-feed]');
            if(!host) return;
            var tpl=host.__ccTemplate;
            host.innerHTML='';
            if(d.error){ host.appendChild(note('Could not load reports. '+d.error)); return; }
            var list=d.reports||[];
            if(!list.length){ host.appendChild(note('No reports have been submitted yet.')); return; }
            for(var i=0;i<list.length;i++) host.appendChild(card(tpl,list[i]));
          };

          window.chipchapInteractions=function(json){
            var d={}; try{d=JSON.parse(json)}catch(err){}
            var cards=d.reportId?document.querySelectorAll('[data-cc-report="'+d.reportId+'"]'):[];
            var known=(d.voteCount!=null&&d.voteCount>=0);
            for(var i=0;i<cards.length;i++){
              var card=cards[i];
              // '' means "state unknown": ccVote refuses to guess instead of sending a wrong toggle.
              card.setAttribute('data-cc-voted', known?(d.voted?'1':'0'):'');
              var counters=card.querySelectorAll('[data-cc-votecount]');
              for(var c=0;c<counters.length;c++){
                var word=counters[c].getAttribute('data-cc-votelabel')||'neighbour';
                setOwnText(counters[c], known?(d.voteCount+' '+word+(d.voteCount===1?'':'s')):'votes unavailable');
              }
              var buttons=card.querySelectorAll('button');
              for(var b=0;b<buttons.length;b++){
                var l=ownText(buttons[b]).toLowerCase();
                if(known&&(l==='upvote'||l==='upvoted')) setOwnText(buttons[b], d.voted?'Upvoted':'Upvote');
              }
            }
            renderComments(d);
            if(d.error) toast(d.error);
          };

          function note(text){
            var p=document.createElement('p');
            p.style.cssText='font-size:14px;color:#434654;padding:12px 0';
            p.textContent=text;
            return p;
          }

          function card(tpl,report){
            var el;
            if(tpl){ el=tpl.cloneNode(true); } else { el=document.createElement('article'); el.innerHTML='<h4></h4><p></p>'; }
            el.setAttribute('data-cc-report', report.reportId||'');
            // No vote state until the stored one arrives (see chipchapInteractions / ccVote).
            el.removeAttribute('data-cc-voted');

            // Evidence: this report's own photo when this device holds it, never a stock image.
            var img=el.querySelector('img');
            if(img){
              var thumb=(report.hasLocalPhoto&&report.reportId&&native&&native.getReportPhotoPreview)
                ? native.getReportPhotoPreview(report.reportId,true) : '';
              if(thumb){ img.src=thumb; img.style.objectFit='cover'; img.removeAttribute('data-alt'); img.removeAttribute('srcset'); }
              else {
                var box=img.parentElement; img.remove();
                if(box){
                  var ph=document.createElement('div');
                  ph.style.cssText='width:100%;height:100%;min-height:48px;display:flex;align-items:center;justify-content:center;text-align:center;padding:4px;font-size:10px;line-height:1.2;color:#737685;background:#eef0f7';
                  ph.textContent='Evidence photo not on this device';
                  box.appendChild(ph);
                }
              }
            }

            // Title: Home and Community use h4, My Reports uses h3.
            var h=el.querySelector('h4,h3'); if(h) h.textContent=report.title||'Untitled report';
            var body=el.querySelector('p');
            if(body){
              if(/^Reported .* by /.test(txt(body))) body.textContent='Reported '+fmtDate(report.createdAt)+(report.citizenName?(' by '+report.citizenName):'');
              else body.textContent=report.description||'';
            }

            // Labelled chips. Most carry an icon or a status dot, so match on the element's own text
            // and replace only that text, keeping the Stitch icon.
            var nodes=el.querySelectorAll('span,div');
            for(var i=0;i<nodes.length;i++){
              var n=nodes[i];
              if(n.classList.contains('material-symbols-outlined')) continue;
              var t=ownText(n);
              if(!t) continue;
              var k=iconKey(directIcon(n));
              if(/(Neighbou?rs|Backers)$/i.test(t)){
                n.setAttribute('data-cc-votecount','1');
                n.setAttribute('data-cc-votelabel', /Backers/i.test(t)?'backer':'neighbour');
                setOwnText(n,'...');
              }
              else if(k==='location_on'||k==='map'||k==='place') setOwnText(n, locationText(report));
              else if(k==='calendar_today') setOwnText(n, fmtDate(report.createdAt));
              else if(/ago$/.test(t)) setOwnText(n, statusLabel(report.status)+' - '+fmtDate(report.createdAt));
              else if(/^(In Progress|Resolved|Pending|Urgent|Open|Closed|Submitted)$/i.test(t)) setOwnText(n, statusLabel(report.status));
              else if(t==='High Priority'||t==='Safety') setOwnText(n, report.severity?cap(report.severity)+' severity':(report.category||'Report'));
            }
            if(report.reportId && native && native.loadReportInteractions) native.loadReportInteractions(report.reportId);
            return el;
          }

          function renderComments(d){
            var host=document.querySelector('[data-cc-comments]');
            if(!host) return;
            host.innerHTML='';
            if(!d.commentsLoaded){ host.appendChild(note('Comments could not be loaded.')); return; }
            var list=d.comments||[];
            if(!list.length){ host.appendChild(note('No comments yet. Be the first to discuss.')); return; }
            for(var i=0;i<list.length;i++){
              var row=document.createElement('div');
              row.style.cssText='padding:10px 0;border-top:1px solid #dfe1e6';
              row.innerHTML='<div style="font-weight:600;font-size:13px;color:#051a3e">'+esc(list[i].authorName||'Citizen')+'</div>'
                +'<div style="font-size:14px;color:#434654;margin-top:2px">'+esc(list[i].text||'')+'</div>';
              host.appendChild(row);
            }
          }

          // ---------------- home_screen ----------------
          function setupHome(){
            var heads=document.querySelectorAll('h2');
            for(var i=0;i<heads.length;i++){
              if(txt(heads[i]).toLowerCase().indexOf('good morning')===0){
                heads[i].setAttribute('data-cc-greeting','1');
                heads[i].textContent='';
              }
            }
            var spans=document.querySelectorAll('span');
            for(var j=0;j<spans.length;j++){
              var t=txt(spans[j]);
              var value=spans[j].previousElementSibling;
              if(t==='MY REPORTS'&&value){ value.setAttribute('data-cc-myreports','1'); value.textContent='--'; }
              if(t==='CITY RESOLVED'&&value){ value.setAttribute('data-cc-cityresolved','1'); value.textContent='--'; }
              if(t.indexOf('issues within')>=0) spans[j].textContent='Map preview';
            }
            // Replace the stock photograph with a drawn map and drop the decorative pins.
            var photo=document.querySelector('img[data-location]');
            if(photo&&photo.parentElement){
              var holder=photo.parentElement;
              holder.querySelectorAll('.material-symbols-outlined').forEach(function(icon){
                var k=icon.dataset.ccIcon||'';
                if(k==='maps_ar'||k==='lightbulb'){ var wrap=icon.parentElement; if(wrap&&wrap!==holder) wrap.remove(); }
              });
              photo.replaceWith(mapPlaceholder('Static map preview - not live data'));
            }
            // Recent Activity: real reports, and remove the fabricated AI insight card.
            var sections=document.querySelectorAll('section');
            for(var k=0;k<sections.length;k++){
              var heading=sections[k].querySelector('h3');
              if(!heading||txt(heading).toLowerCase().indexOf('recent activity')<0) continue;
              var articles=sections[k].querySelectorAll('article');
              if(!articles.length) continue;
              var host=articles[0].parentElement;
              host.__ccTemplate=articles[0].cloneNode(true);
              host.setAttribute('data-cc-feed','1');
              host.innerHTML='';
              host.appendChild(note('Loading recent reports...'));
            }
            if(native){
              if(native.getCitizenProfile){ try{ window.chipchapProfile(native.getCitizenProfile()); }catch(err){} }
              if(native.loadMyReportCount) native.loadMyReportCount();
              if(native.loadCommunityFeed) native.loadCommunityFeed();
            }
          }

          // ---------------- community_feed ----------------
          function setupCommunity(){
            var sections=document.querySelectorAll('section');
            for(var k=0;k<sections.length;k++){
              var heading=sections[k].querySelector('h3');
              if(!heading||txt(heading).toLowerCase().indexOf('trending issues')<0) continue;
              var articles=sections[k].querySelectorAll('article');
              if(!articles.length) continue;
              var host=articles[0].parentElement;
              host.__ccTemplate=articles[0].cloneNode(true);
              host.setAttribute('data-cc-feed','1');
              host.innerHTML='';
              host.appendChild(note('Loading community reports...'));
            }
            if(native&&native.loadCommunityFeed) native.loadCommunityFeed();
          }

          // ---------------- complaint_details_1 ----------------
          function setupDetailsView(){
            var rid=(native&&native.getActiveReportId)?native.getActiveReportId():'';
            // The report id is known now, so tag the screen synchronously. Votes and comments that
            // arrive later always find it; bindReportDetails no longer has to set it.
            var main0=document.querySelector('main');
            if(main0&&rid) main0.setAttribute('data-cc-report', rid);
            var placeholder=null;
            var paras=document.querySelectorAll('p');
            for(var i=0;i<paras.length;i++){ if(txt(paras[i]).indexOf('No comments yet')===0) placeholder=paras[i]; }
            if(placeholder){
              var host=document.createElement('div');
              host.setAttribute('data-cc-comments','1');
              placeholder.replaceWith(host);
              host.appendChild(note(rid?'Loading comments...':'Open a report from the community feed to see its comments.'));
            }
            var input=document.querySelector('input[placeholder^="Add a comment"]');
            if(input){
              input.setAttribute('data-cc-commentinput','1');
              // The large Send button below is the real control; the small arrow is redundant.
              var row=input.parentElement;
              if(row){
                row.querySelectorAll('.material-symbols-outlined').forEach(function(icon){
                  var k=icon.dataset.ccIcon||'';
                  if(k==='send'||k==='arrow_forward'||k==='arrow_upward'){
                    var host=icon.closest('button')||icon;
                    host.style.display='none';
                  }
                });
              }
              if(!document.querySelector('[data-cc-send]')){
                var send=document.createElement('button');
                send.setAttribute('data-cc-send','1');
                send.textContent='Send';
                send.style.cssText='margin-top:8px;width:100%;background:#003d9b;color:#fff;border:0;border-radius:10px;padding:12px;font-weight:700';
                input.parentElement.appendChild(send);
              }
            }
            setupReportDetails();
            if(rid&&native&&native.loadReportInteractions) native.loadReportInteractions(rid);
          }

          // ---------------- language_selection ----------------
          function setupLanguage(){
            var main=document.querySelector('main')||document.body;
            if(document.querySelector('[data-cc-langnote]')) return;
            var n=document.createElement('p');
            n.setAttribute('data-cc-langnote','1');
            n.style.cssText='margin:12px 16px;padding:12px;border-radius:10px;background:#fff4e5;color:#6e3900;font-size:13px';
            n.textContent='Language selection is not saved yet in this build. The app is English only for now.';
            main.prepend(n);
          }

          // ---------------- user_profile ----------------
          function setupProfile(){
            var head=document.querySelector('section h2');
            if(head && txt(head)==='Citizen User') head.setAttribute('data-cc-profilename','1');
            // The stock headshot is not this citizen: replace it with a neutral initial avatar.
            var photo=document.querySelector('section img');
            if(photo&&photo.parentElement){
              var av=document.createElement('div');
              av.setAttribute('data-cc-avatar','1');
              av.style.cssText='width:100%;height:100%;display:flex;align-items:center;justify-content:center;'
                +'background:#e9edff;color:#003d9b;font-weight:700;font-size:34px';
              av.textContent='';
              photo.replaceWith(av);
            }
            // "Level 5 Contributor" / "Joined April 2023 - Verified Resident" are invented.
            var paras=document.querySelectorAll('section p');
            for(var i=0;i<paras.length;i++){
              var t=txt(paras[i]);
              if(t.indexOf('Level')===0){ paras[i].setAttribute('data-cc-profileemail','1'); paras[i].textContent=''; }
              else if(t.indexOf('Joined')===0) paras[i].textContent='CHIPCHAP citizen account';
            }
            // My Achievements: nothing is shown as earned until real data says so.
            document.querySelectorAll('main h4').forEach(function(h){
              var name=txt(h), key=(name==='First Report')?'first':(name==='Top Backer')?'backer':'';
              if(!key) return;
              var tile=h.closest('.rounded-xl'); var sub=h.parentElement?h.parentElement.querySelector('p'):null;
              if(!tile||!sub) return;
              tile.setAttribute('data-cc-achievement',key);
              sub.setAttribute('data-cc-achievementtext',txt(sub));
            });
            setAchievement('first',false,'Checking...');
            // Top Backer (50+ upvotes) cannot be counted yet without an extra read per report or a
            // Firestore rules/index change, so it is never shown as earned.
            setAchievement('backer',false,'50+ upvotes - not tracked yet');
            if(native&&native.getCitizenProfile){ try{ window.chipchapProfile(native.getCitizenProfile()); }catch(err){} }
            if(native&&native.loadMyReportCount) native.loadMyReportCount();
          }

          // ---------------- my_reports ----------------
          window.chipchapMyReports=function(json){
            var d={}; try{d=JSON.parse(json)}catch(err){}
            var host=document.querySelector('[data-cc-myreportlist]');
            if(!host) return;
            host.innerHTML='';
            if(d.error){ host.appendChild(note('Could not load your reports. '+d.error)); return; }
            var list=d.reports||[];
            if(!list.length){ host.appendChild(note('You have not submitted any reports yet. Tap Report to create your first one.')); return; }
            var tpl=host.__ccTemplate;
            for(var i=0;i<list.length;i++) host.appendChild(card(tpl,list[i]));
          };

          function setupMyReports(){
            var cards=document.querySelectorAll('main article, main li, main a[href*="complaint_details"]');
            var host=null;
            var articles=document.querySelectorAll('main article');
            if(articles.length){ host=articles[0].parentElement; host.__ccTemplate=articles[0].cloneNode(true); }
            if(!host){
              var main=document.querySelector('main');
              if(!main) return;
              host=document.createElement('div'); main.appendChild(host);
            }
            host.setAttribute('data-cc-myreportlist','1');
            host.innerHTML='';
            host.appendChild(note('Loading your reports...'));
            if(native&&native.loadMyReports) native.loadMyReports();
          }

          // ---------------- notifications (users/{authUid}/notifications) ----------------
          var notifFilter='all', notifList=null;
          var NOTIF_ICON={STATUS_CHANGED:'sync',RESOLVED:'check_circle',ASSIGNED:'assignment_ind'};
          function relTime(ms){
            if(!ms) return '';
            var sec=Math.max(0,Math.round((Date.now()-ms)/1000));
            if(sec<60) return 'Just now';
            var m=Math.round(sec/60); if(m<60) return m+'m ago';
            var h=Math.round(m/60); if(h<24) return h+'h ago';
            var dd=Math.round(h/24); if(dd<7) return dd+'d ago';
            return fmtDate(ms);
          }
          function setupNotifications(){
            var list=document.getElementById('notification-list');
            if(!list) return;
            var arts=list.querySelectorAll('article');
            list.__ccTemplate=arts.length?arts[0].cloneNode(true):null;
            list.innerHTML='';
            list.appendChild(note('Loading notifications...'));
            var empty=document.getElementById('empty-state');
            if(empty){
              // Reuse the designed empty state with honest wording; drop its remote stock illustration.
              var art=empty.querySelector('[style*="background-image"]'); if(art) art.remove();
              var h=empty.querySelector('h3'); if(h) h.textContent='No notifications yet.';
              var p=empty.querySelector('p'); if(p) p.textContent='Updates about your reports will appear here.';
            }
            document.querySelectorAll('main section button').forEach(function(b){
              var l=txt(b).toLowerCase();
              if(l==='all'||l==='updates'||l==='messages') b.setAttribute('data-cc-notifchip',l);
            });
            var chips=document.querySelectorAll('[data-cc-notifchip]');
            if(chips.length>1){ list.__ccOn=chips[0].className; list.__ccOff=chips[1].className; }
            if(native&&native.loadMyNotifications) native.loadMyNotifications();
          }
          function showEmpty(show,text){
            var empty=document.getElementById('empty-state');
            var list=document.getElementById('notification-list');
            if(empty){
              empty.classList.toggle('hidden',!show); empty.classList.toggle('flex',show);
              if(show&&text){ var p=empty.querySelector('p'); if(p) p.textContent=text; }
            }
            if(list) list.style.display=show?'none':'';
          }
          function renderNotifications(){
            var list=document.getElementById('notification-list');
            if(!list) return;
            document.querySelectorAll('[data-cc-notifchip]').forEach(function(b){
              if(list.__ccOn) b.className=(b.getAttribute('data-cc-notifchip')===notifFilter)?list.__ccOn:list.__ccOff;
            });
            list.innerHTML='';
            if(!notifList){ showEmpty(false); return; }
            // Every notification this build creates is a report update; there are no direct messages.
            var shown=notifFilter==='messages'?[]:notifList;
            if(!shown.length){
              showEmpty(true, notifFilter==='messages'?'There are no messages. Report updates appear under All and Updates.':'Updates about your reports will appear here.');
              return;
            }
            showEmpty(false);
            var tpl=list.__ccTemplate;
            shown.forEach(function(n){
              var el;
              if(tpl){ el=tpl.cloneNode(true); el.removeAttribute('onclick'); }
              else { el=document.createElement('article'); el.innerHTML='<span></span><span></span><p></p>'; }
              el.setAttribute('data-cc-notif', n.id||'');
              el.setAttribute('data-cc-notifreport', n.reportId||'');
              el.style.cursor='pointer';
              var dot=el.querySelector('[aria-label="Unread"]');
              if(dot) dot.style.display=n.read?'none':'';
              var icon=el.querySelector('.material-symbols-outlined');
              if(icon){ var k=NOTIF_ICON[n.type]||'notifications'; icon.dataset.ccIcon=k; icon.setAttribute('data-icon',k); icon.textContent=k; }
              var spans=el.querySelectorAll('span');
              var labelSpan=null, timeSpan=null;
              spans.forEach(function(sp){ if(sp.classList.contains('material-symbols-outlined')) return; if(!labelSpan) labelSpan=sp; else if(!timeSpan) timeSpan=sp; });
              if(labelSpan) labelSpan.textContent=n.title||'Report update';
              if(timeSpan) timeSpan.textContent=relTime(n.createdAt);
              var p=el.querySelector('p');
              if(p){ p.textContent=n.body||''; p.style.fontWeight=n.read?'400':''; }
              list.appendChild(el);
            });
            if(window.ccIcons) window.ccIcons();
          }
          window.chipchapNotifications=function(json){
            var d={}; try{d=JSON.parse(json)}catch(err){}
            var list=document.getElementById('notification-list');
            if(!d.loaded){
              notifList=null;
              if(list){ list.innerHTML=''; list.style.display=''; list.appendChild(note('Notifications could not be loaded. '+(d.error||''))); }
              showEmptyOff();
              return;
            }
            notifList=d.notifications||[];
            renderNotifications();
            updateUnreadDots(notifList.filter(function(n){ return !n.read; }).length);
          };
          function showEmptyOff(){ var e=document.getElementById('empty-state'); if(e){ e.classList.add('hidden'); e.classList.remove('flex'); } }

          // Real unread indicator on the notification icon/tab: shown only when unread > 0.
          function tagUnreadDots(){
            document.querySelectorAll('.material-symbols-outlined').forEach(function(icon){
              var k=String(icon.dataset.ccIcon||icon.dataset.icon||icon.textContent||'').trim();
              if(k!=='notifications') return;
              var box=icon.parentElement; if(!box) return;
              for(var i=0;i<box.children.length;i++){
                var c=box.children[i];
                if(c===icon||c.children.length||txt(c)) continue;
                var cls=c.className||'';
                if(/rounded-full/.test(cls)&&/\\bw-2\\b/.test(cls)&&/\\bh-2\\b/.test(cls)){ c.setAttribute('data-cc-unreaddot','1'); c.style.display='none'; }
              }
            });
          }
          function updateUnreadDots(n){
            document.querySelectorAll('[data-cc-unreaddot]').forEach(function(dot){
              dot.style.display=(n>0)?'':'none';
              dot.setAttribute('aria-label', n>0?(n+' unread notification'+(n===1?'':'s')):'');
            });
          }
          window.chipchapUnreadCount=function(n){ updateUnreadDots(n); };

        """, """
          // ---------------- map_explorer (real map) ----------------
          // The Stitch screen is not redesigned: its own full-bleed map layer (the element the
          // design marks with data-location) becomes the live map canvas, and the designed search
          // bar, category chips, floating buttons and issue card keep their positions on top of it.
          // MapLibre GL JS is bundled in the APK (assets/vendor); only the vector style and tiles
          // come from OpenFreeMap over HTTPS. No Google Maps, no API key, no paid provider.
          var MAP_LIB_JS='file:///android_asset/vendor/maplibre-gl.js';
          var MAP_LIB_CSS='file:///android_asset/vendor/maplibre-gl.css';
          var MAP_STYLE_URL='https://tiles.openfreemap.org/styles/liberty';
          var MAP_FALLBACK_CENTER=[78.9629,20.5937];   // geographic centre of India
          var MAP_FALLBACK_ZOOM=3.8;
          var CATEGORY_COLORS={Infrastructure:'#de350b',Cleanliness:'#914d00',Safety:'#003d9b'};
          var ccMap=null, ccMapLoaded=false, ccMapPoints=[], ccMapMode='CITIZEN';
          var mapFilter='All', mapQuery='', mapQueryText='', mapSelected='';
          var ccMapFitted=false, ccMapHere=null, ccMapHereMarker=null;
          // Clustering is a DRAWING technique only. MapLibre groups nearby points of the one
          // bounded report query client-side; every report stays its own Firestore document, and
          // a cluster never means the reports describe the same issue. A 40 px radius is about
          // 2.9 km at zoom 10, 700 m at zoom 12 and 180 m at zoom 14 in India, so reports in the
          // same street or block group together while separate localities stay separate; from
          // zoom 15 up every report is drawn as its own marker.
          var CLUSTER_RADIUS=40, CLUSTER_MAX_ZOOM=14;

          function categoryColor(c){ return CATEGORY_COLORS[String(c||'')]||'#737685'; }
          function mapPoint(id){
            for(var i=0;i<ccMapPoints.length;i++){ if(ccMapPoints[i].reportId===id) return ccMapPoints[i]; }
            return null;
          }
          // Report search. It looks only through the reports already loaded for this map (the one
          // bounded query): title, description, category, the stored address (the place text saved
          // with the report, e.g. "Kakkanad, Kochi, Kerala"), status and severity. Case-insensitive,
          // partial words, and every word typed must appear somewhere. No geocoder, no extra reads.
          function mapSearchText(p){
            if(p.__ccText==null){
              p.__ccText=[p.title,p.description,p.category,p.address,statusLabel(p.status),p.severity]
                .map(function(v){ return String(v==null?'':v); }).join(' ').toLowerCase();
            }
            return p.__ccText;
          }
          function mapVisible(p){
            if(!p) return false;
            if(mapFilter!=='All' && String(p.category||'')!==mapFilter) return false;
            if(!mapQuery) return true;
            var hay=mapSearchText(p), words=mapQuery.split(' ');
            for(var i=0;i<words.length;i++){ if(words[i]&&hay.indexOf(words[i])<0) return false; }
            return true;
          }
          function mapMatches(){
            var out=[];
            for(var i=0;i<ccMapPoints.length;i++){ if(mapVisible(ccMapPoints[i])) out.push(ccMapPoints[i]); }
            return out;
          }
          // Search result state, shown in the map's own honest-state note. Never invents a place.
          function mapSearchStatus(){
            // Government map: report and place results are listed under the search bar instead.
            if(placeSearchOn()){ var sn=document.querySelector('[data-cc-searchnote]'); if(sn) sn.remove(); return; }
            var box=document.querySelector('[data-cc-mapnote]');
            var mine=!!(box&&box.getAttribute('data-cc-searchnote'));
            if(!mapQuery||!ccMapPoints.length||mapMatches().length){ if(mine) box.remove(); return; }
            mapNote('No CHIPCHAP reports match "'+mapQueryText+'"'+(mapFilter!=='All'?(' in '+mapFilter):'')
              +'. Search looks through the reports on this map; a place is found only when a report mentions it.');
            var nb=document.querySelector('[data-cc-mapnote]'); if(nb) nb.setAttribute('data-cc-searchnote','1');
          }
          // Take the user to the result: one match is centred (zoomed in far enough to be its own
          // marker, and its card opens); several matches are all fitted into view.
          function mapFocusMatches(announce){
            if(!mapQuery) return;
            var m=mapMatches();
            if(!m.length||!ccMap||!ccMapLoaded||!window.maplibregl) return;
            ccMapFitted=true;
            if(m.length===1){
              mapSelected=m[0].reportId;
              mapRefresh();
              ccMap.flyTo({center:[m[0].lng,m[0].lat],zoom:Math.max(ccMap.getZoom(),CLUSTER_MAX_ZOOM+1),duration:700,essential:true});
            } else {
              var b=new maplibregl.LngLatBounds([m[0].lng,m[0].lat],[m[0].lng,m[0].lat]);
              for(var j=1;j<m.length;j++) b.extend([m[j].lng,m[j].lat]);
              ccMap.fitBounds(b,{padding:70,maxZoom:CLUSTER_MAX_ZOOM+1,duration:700});
            }
            if(announce) toast(m.length===1?'1 report found.':(m.length+' reports found.'));
          }
          // Honest state inside the map surface: never a fake map, never a silent blank screen.
          function mapNote(message){
            var host=document.querySelector('[data-cc-mapsurface]');
            if(!host){ toast(message); return; }
            var box=host.querySelector('[data-cc-mapnote]');
            if(!box){
              box=document.createElement('div');
              box.setAttribute('data-cc-mapnote','1');
              box.style.cssText='position:absolute;left:14px;right:14px;top:50%;transform:translateY(-50%);z-index:5;background:rgba(255,255,255,.94);border:1px solid #c3c6d6;border-radius:12px;padding:12px 14px;font-size:13px;line-height:1.4;color:#434654;text-align:center';
              host.appendChild(box);
            }
            box.textContent=message;
          }
          function mapNoteClear(){ var b=document.querySelector('[data-cc-mapnote]'); if(b) b.remove(); }

          function mapDistanceText(p){
            if(!ccMapHere||typeof p.lat!=='number'||typeof p.lng!=='number') return '';
            var toRad=Math.PI/180, R=6371;
            var dLat=(p.lat-ccMapHere[1])*toRad, dLng=(p.lng-ccMapHere[0])*toRad;
            var a=Math.sin(dLat/2)*Math.sin(dLat/2)
                 +Math.cos(ccMapHere[1]*toRad)*Math.cos(p.lat*toRad)*Math.sin(dLng/2)*Math.sin(dLng/2);
            var km=2*R*Math.asin(Math.min(1,Math.sqrt(a)));
            return km<1 ? (Math.round(km*1000)+' m away') : (km.toFixed(km<10?1:0)+' km away');
          }

          function mapFeatures(){
            var feats=[];
            for(var i=0;i<ccMapPoints.length;i++){
              var p=ccMapPoints[i];
              if(!mapVisible(p)) continue;
              feats.push({type:'Feature',
                geometry:{type:'Point',coordinates:[p.lng,p.lat]},
                properties:{reportId:p.reportId,color:categoryColor(p.category),sel:(p.reportId===mapSelected)?1:0}});
            }
            return {type:'FeatureCollection',features:feats};
          }
          // Font for the cluster count: taken from the loaded OpenFreeMap style itself, so the
          // label only ever asks the style's own glyph server for a font stack it really serves.
          function mapLabelFont(){
            try{
              var ls=(ccMap.getStyle()||{}).layers||[], first='';
              for(var i=0;i<ls.length;i++){
                var f=ls[i].layout&&ls[i].layout['text-font'];
                if(Array.isArray(f)&&f[0]==='literal') f=f[1];
                if(!Array.isArray(f)||typeof f[0]!=='string'||f[0].indexOf(' ')<0) continue;
                if(!first) first=f[0];
                if(/bold/i.test(f[0])) return [f[0]];
              }
              if(first) return [first];
            }catch(err){}
            return ['Noto Sans Bold'];
          }
          function mapFirstLayer(ids){
            for(var i=0;i<ids.length;i++){ if(ccMap.getLayer(ids[i])) return ids[i]; }
            return undefined;
          }
          // MapLibre 4 returns a Promise here; older builds used a callback. Both are accepted.
          function mapClusterZoom(src,clusterId,done){
            var settled=false;
            function once(z){ if(settled) return; settled=true; done(z); }
            try{
              var r=src.getClusterExpansionZoom(clusterId,function(err,z){ once(err?null:z); });
              if(r&&typeof r.then==='function') r.then(function(z){ once(z); },function(){ once(null); });
            }catch(err){ once(null); }
          }
          function mapEnsureLayers(){
            if(!ccMap||!ccMapLoaded) return;
            // Markers and clusters: ONE clustered GeoJSON source built from the one bounded report
            // query. Clustering happens here, on the device, for drawing only.
            if(!ccMap.getSource('ccreports')) ccMap.addSource('ccreports',{type:'geojson',data:mapFeatures(),
              cluster:true,clusterRadius:CLUSTER_RADIUS,clusterMaxZoom:CLUSTER_MAX_ZOOM});
            // GOVERNMENT ONLY. The mode comes from the native bridge, which decides it with the
            // existing hasGovernmentAccess() check, so a citizen session never reaches this branch.
            // The heat layer reads the same features WITHOUT clustering, so density is still
            // counted report by report (a cluster circle would otherwise count as one report).
            if(ccMapMode==='GOVERNMENT' && !ccMap.getSource('ccreportsheat')) ccMap.addSource('ccreportsheat',{type:'geojson',data:mapFeatures()});
            if(ccMapMode==='GOVERNMENT' && !ccMap.getLayer('ccheat')){
              // Weight and intensity are deliberately low so density keeps climbing well past a
              // handful of reports: a few nearby reports glow faintly, dozens burn red, and the
              // ramp does not saturate as soon as two pins overlap.
              ccMap.addLayer({id:'ccheat',type:'heatmap',source:'ccreportsheat',paint:{
                // Density only - every report counts exactly the same, no severity, category or
                // vote weighting. The radius stays tight so separate clusters stay separate
                // hotspots instead of merging into one glow, and opacity drops as you zoom in so
                // the individual markers above the heat stay readable.
                'heatmap-weight':0.25,
                'heatmap-intensity':['interpolate',['linear'],['zoom'],8,0.45,11,0.8,14,1.1,17,1.4],
                'heatmap-radius':['interpolate',['linear'],['zoom'],8,10,11,18,14,28,17,42],
                'heatmap-opacity':['interpolate',['linear'],['zoom'],11,0.85,16,0.6],
                'heatmap-color':['interpolate',['linear'],['heatmap-density'],
                  0,'rgba(0,61,155,0)',
                  0.08,'rgba(0,102,204,0.30)',
                  0.25,'rgba(0,158,115,0.50)',
                  0.45,'rgba(240,196,0,0.66)',
                  0.68,'rgba(255,126,0,0.78)',
                  0.85,'rgba(232,57,15,0.86)',
                  1,'rgba(165,25,0,0.94)']}},
                // Always underneath the cluster circles and the report markers.
                mapFirstLayer(['ccclusters','ccclustercount','ccpins']));
            }
            // Cluster circle: CHIPCHAP primary blue with the same white ring as a report marker,
            // a little larger as the number of nearby reports grows.
            if(!ccMap.getLayer('ccclusters')){
              ccMap.addLayer({id:'ccclusters',type:'circle',source:'ccreports',filter:['has','point_count'],paint:{
                'circle-color':'#003d9b',
                'circle-opacity':0.92,
                'circle-radius':['step',['get','point_count'],15,10,19,50,24],
                'circle-stroke-width':3,
                'circle-stroke-color':'#ffffff'}},mapFirstLayer(['ccpins']));
              // Tap a cluster: zoom in to the level where it splits apart. Nothing is selected
              // or merged; the individual markers appear as the map zooms in.
              ccMap.on('click','ccclusters',function(ev){
                var f=ev.features&&ev.features[0];
                if(!f||!f.properties||!f.geometry) return;
                var center=f.geometry.coordinates;
                var src=ccMap.getSource('ccreports');
                if(!src) return;
                mapClusterZoom(src,f.properties.cluster_id,function(z){
                  var target=(typeof z==='number'&&isFinite(z))?z:ccMap.getZoom()+2;
                  target=Math.min(Math.max(target,ccMap.getZoom()+1),CLUSTER_MAX_ZOOM+1);
                  ccMap.easeTo({center:center,zoom:target,duration:450});
                });
              });
            }
            if(!ccMap.getLayer('ccclustercount')){
              ccMap.addLayer({id:'ccclustercount',type:'symbol',source:'ccreports',filter:['has','point_count'],
                layout:{'text-field':['get','point_count_abbreviated'],'text-font':mapLabelFont(),'text-size':13,
                  'text-allow-overlap':true,'text-ignore-placement':true},
                paint:{'text-color':'#ffffff'}},mapFirstLayer(['ccpins']));
            }
            // Individual reports: exactly the existing marker, now only for points that are not
            // inside a cluster at the current zoom. The selected report is drawn on top.
            if(!ccMap.getLayer('ccpins')){
              ccMap.addLayer({id:'ccpins',type:'circle',source:'ccreports',filter:['!',['has','point_count']],
                layout:{'circle-sort-key':['get','sel']},paint:{
                'circle-radius':['case',['==',['get','sel'],1],11,7],
                'circle-color':['get','color'],
                'circle-stroke-width':3,
                'circle-stroke-color':'#ffffff'}});
              ccMap.on('click','ccpins',function(ev){
                // Several separate reports can share almost the same spot. Each tap there shows
                // the next one in the existing card, so every report stays reachable.
                var ids=[];
                (ev.features||[]).forEach(function(f){
                  var r=f&&f.properties&&f.properties.reportId;
                  if(r&&ids.indexOf(r)<0) ids.push(r);
                });
                if(!ids.length) return;
                mapSelected=ids[(ids.indexOf(mapSelected)+1)%ids.length];
                if(ids.length>1) toast(ids.length+' separate reports at this spot ('+(ids.indexOf(mapSelected)+1)+' of '+ids.length+'). Tap again to see the next one.');
                mapRefresh();
              });
            }
          }
          function mapRefresh(){
            if(ccMap&&ccMapLoaded){
              mapEnsureLayers();
              var data=mapFeatures();
              var src=ccMap.getSource('ccreports');
              if(src) src.setData(data);
              var heat=ccMap.getSource('ccreportsheat');
              if(heat) heat.setData(data);
              routeSync();
            }
            renderMapCard();
          }
          function mapFitToPoints(){
            if(!ccMap||!ccMapLoaded||ccMapFitted||!window.maplibregl) return;
            var pts=[];
            for(var i=0;i<ccMapPoints.length;i++){ if(mapVisible(ccMapPoints[i])) pts.push(ccMapPoints[i]); }
            if(!pts.length) return;
            var b=new maplibregl.LngLatBounds([pts[0].lng,pts[0].lat],[pts[0].lng,pts[0].lat]);
            for(var j=1;j<pts.length;j++) b.extend([pts[j].lng,pts[j].lat]);
            ccMap.fitBounds(b,{padding:70,maxZoom:15,duration:0});
            ccMapFitted=true;
          }
          // The designed issue card, filled from the selected real report.
          function renderMapCard(){
            placeCardRender();
            var card=document.querySelector('[data-cc-mapcard]');
            if(!card) return;
            var p=mapPoint(mapSelected);
            if(!p||!mapVisible(p)){ card.style.display='none'; return; }
            var t=card.querySelector('[data-cc-mapcardtitle]'); if(t) t.textContent=p.title||'Untitled report';
            var st=card.querySelector('[data-cc-mapcardstatus]'); if(st) setTail(st,statusLabel(p.status));
            var dd=card.querySelector('[data-cc-mapcarddistance]');
            if(dd){
              var d=mapDistanceText(p);
              dd.textContent=d||(p.category?String(p.category):'Location saved');
            }
            // Vote counts are deliberately not read for markers - one extra Firestore read per pin
            // would be wasteful - so the backers line is hidden instead of showing an invented number.
            var bb=card.querySelector('[data-cc-mapcardbackers]'); if(bb) bb.style.display='none';
            card.style.display='';
          }

          window.chipchapMapReports=function(json){
            var d={}; try{d=JSON.parse(json)}catch(err){}
            ccMapMode=(d.mode==='GOVERNMENT')?'GOVERNMENT':'CITIZEN';
            if(ccMapMode==='GOVERNMENT'){ var nrg=document.querySelector('[data-cc-newreport]'); if(nrg) nrg.remove(); mapUseGovNav(); }
            ccMapPoints=[];
            var list=d.points||[];
            for(var i=0;i<list.length;i++){
              var p=list[i];
              if(!p||typeof p.lat!=='number'||typeof p.lng!=='number') continue;
              ccMapPoints.push(p);
            }
            if(d.error) mapNote(d.error);
            else if(!ccMapPoints.length) mapNote('No reports with a saved location yet.');
            else mapNoteClear();
            if(mapSelected&&!mapPoint(mapSelected)) mapSelected='';
            mapRefresh();
            // A search typed before the reports arrived is applied as soon as they do.
            if(mapQuery&&ccMapPoints.length){ mapSearchStatus(); mapFocusMatches(false); }
            mapFitToPoints();
          };
          function mapMetersBetween(a,b){
            var toRad=Math.PI/180, R=6371000;
            var dLat=(b[1]-a[1])*toRad, dLng=(b[0]-a[0])*toRad;
            var h=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(a[1]*toRad)*Math.cos(b[1]*toRad)*Math.sin(dLng/2)*Math.sin(dLng/2);
            return 2*R*Math.asin(Math.min(1,Math.sqrt(h)));
          }
          function mapShowHere(){
            if(!ccMap||!window.maplibregl||!ccMapHere) return;
            if(!ccMapHereMarker){
              var dot=document.createElement('div');
              dot.style.cssText='width:16px;height:16px;border-radius:50%;background:#003d9b;border:3px solid #fff;box-shadow:0 2px 8px rgba(9,30,66,.35)';
              ccMapHereMarker=new maplibregl.Marker({element:dot}).setLngLat(ccMapHere).addTo(ccMap);
            } else ccMapHereMarker.setLngLat(ccMapHere);
          }
          function mapCenterOnHere(){
            if(!ccMap||!ccMapHere) return;
            ccMap.flyTo({center:ccMapHere,zoom:Math.max(ccMap.getZoom(),14),duration:800,essential:true});
          }
          // A real device position only. The native side may answer twice for one tap: first with
          // a recent position the phone already had (instant), then with a fresh fix. A refinement
          // only moves the camera if the user has not panned away in the meantime.
          window.chipchapMapLocation=function(json){
            var d={}; try{d=JSON.parse(json)}catch(err){}
            if(!d.ok){ if(!d.quiet) toast(d.message||'Your location is not available right now.'); return; }
            if(typeof d.lat!=='number'||typeof d.lng!=='number') return;
            var prev=ccMapHere;
            var stillThere=!!(prev&&ccMap&&mapMetersBetween(prev,[ccMap.getCenter().lng,ccMap.getCenter().lat])<60);
            ccMapHere=[d.lng,d.lat];
            // The user asked to be centred: a report list that arrives later must not pull the
            // camera back to fit all markers.
            ccMapFitted=true;
            if(ccMap&&window.maplibregl){
              mapShowHere();
              if(!d.refine||(stillThere&&mapMetersBetween(prev,ccMapHere)>25)) mapCenterOnHere();
            }
            renderMapCard();
          };

          function mapLoadLibrary(done){
            if(window.maplibregl){ done(); return; }
            var existing=document.getElementById('ccMapLib');
            if(existing){ existing.addEventListener('load',done); return; }
            var css=document.createElement('link');
            css.rel='stylesheet'; css.href=MAP_LIB_CSS;
            document.head.appendChild(css);
            var js=document.createElement('script');
            js.id='ccMapLib'; js.src=MAP_LIB_JS;
            js.onload=function(){ done(); };
            js.onerror=function(){ mapNote('The bundled map library could not be loaded.'); };
            document.head.appendChild(js);
          }
          // Compact, non-interactive location preview for the report detail screens. It uses the
          // coordinates already delivered with the detail payload - no extra Firestore read - and
          // the same bundled MapLibre + OpenFreeMap style as the full map. Exposed on window so the
          // government script, which is a separate IIFE with its own scope, can call it too.
          function miniMapNote(host,text){
            if(!host) return;
            var n=host.querySelector('[data-cc-locmapnote]');
            if(!n){
              n=document.createElement('div');
              n.setAttribute('data-cc-locmapnote','1');
              n.style.cssText='position:absolute;left:0;right:0;top:50%;transform:translateY(-50%);z-index:2;text-align:center;font-size:12px;color:#434654';
              host.appendChild(n);
            }
            n.textContent=text;
          }
          window.ccReportMiniMapPending=function(host){
            if(!host) return;
            if(host.__ccMini){ try{host.__ccMini.remove();}catch(e){} host.__ccMini=null; }
            host.__ccMiniAt='';
            host.innerHTML='';
            host.style.position='relative';
            host.style.backgroundImage='none';
            host.style.backgroundColor='#eef3ea';
            host.style.opacity='1';
            miniMapNote(host,'Loading location map...');
          };
          window.ccReportMiniMap=function(host,lat,lng){
            if(!host) return;
            var la=Number(lat), lo=Number(lng);
            var valid=isFinite(la)&&isFinite(lo)&&la>=-90&&la<=90&&lo>=-180&&lo<=180&&!(la===0&&lo===0);
            if(!valid){
              if(host.__ccMini){ try{host.__ccMini.remove();}catch(e){} host.__ccMini=null; }
              host.__ccMiniAt='';
              host.innerHTML='';
              miniMapNote(host,'Location map unavailable');
              return;
            }
            if(host.__ccMini && host.__ccMiniAt===la+','+lo) return;
            mapLoadLibrary(function(){
              if(!window.maplibregl){ miniMapNote(host,'Location map unavailable'); return; }
              try{
                if(host.__ccMini){ try{host.__ccMini.remove();}catch(e){} host.__ccMini=null; }
                host.innerHTML='';
                host.style.position='relative';
                var mini=new maplibregl.Map({container:host,style:MAP_STYLE_URL,center:[lo,la],zoom:15.5,
                  interactive:false,attributionControl:{compact:true}});
                host.__ccMini=mini; host.__ccMiniAt=la+','+lo;
                mini.on('error',function(){ miniMapNote(host,'Location map could not be loaded.'); });
                mini.on('load',function(){
                  var pin=document.createElement('div');
                  pin.style.cssText='width:18px;height:18px;border-radius:50%;background:#de350b;border:3px solid #fff;box-shadow:0 2px 8px rgba(9,30,66,.35)';
                  new maplibregl.Marker({element:pin}).setLngLat([lo,la]).addTo(mini);
                  var done=host.querySelector('[data-cc-locmapnote]'); if(done) done.remove();
                });
              }catch(err){ miniMapNote(host,'Location map unavailable'); }
            });
          };
          function bindLocationPreviews(report){
            var r=report||{};
            document.querySelectorAll('[data-cc-locmap]').forEach(function(el){
              window.ccReportMiniMap(el, r.latitude, r.longitude);
            });
          }

        """, """
          // ---------------- Checkpoint F1/F2: government route preview + place search ----------------
          // Government only (the native bridge decides the role). Routes come from Geoapify Routing,
          // places from Geoapify Geocoding; both are requested natively and ONLY after an explicit
          // action (a debounced search pause, Navigate to Report, Route here, Show/Update Route).
          // The route is its own GeoJSON source + line layers and the place is a separate DOM marker:
          // the report source, clusters, markers and heatmap are never modified. Native code owns
          // the route state (it survives this page being reloaded); generation and sequence numbers
          // stop an older answer from replacing a newer one or reviving something already cleared.
          // Temporary UI state only - nothing here is stored anywhere.
          var ROUTE_SRC='ccroute-source', ROUTE_LAYERS=['ccroute-casing','ccroute-line','ccroute-ends'];
          var ROUTE_COLOR='#5b3fd6', PLACE_COLOR='#0b7a5c';
          var ccRoute={state:'none',gen:-1}, ccRouteGen=-1, ccRouteDrawnGen=-1, ccRouteFittedGen=-1;
          // The route the user is composing. origin: {type:'device'} (default) or a chosen place;
          // dest: null, {type:'report',reportId,title} or a place. Editing it never requests anything.
          var ccPlan={origin:{type:'device'},dest:null}, ccPlanGen=-1, ccRouteAskedAt=0;
          var ccPlace=null, ccPlaceMarker=null, ccPickOrigin=false;
          var ccPlaceSeq=0, ccPlaceQuery='', ccPlaceState='idle', ccPlaceMsg='', ccPlaceResults=[], ccPlaceTimer=null;
          var PLACE_MIN_CHARS=3, PLACE_MAX_CHARS=120, PLACE_DEBOUNCE_MS=650;
          var PLACE_PLACEHOLDER='Search reports, places or addresses';
          // F3: place search and route preview serve citizens and government alike. Access is
          // still decided natively (signed-in session); the heatmap and government actions are not.
          function placeSearchOn(){ return !!(native&&native.searchPlaces); }
          function iconFontOk(){ try{ return !!(document.fonts&&document.fonts.check('24px "Material Symbols Outlined"')); }catch(err){ return false; } }
          function ccIcon(name,fallback,color,size){
            var ok=iconFontOk(), i=document.createElement('span');
            if(ok){ i.className='material-symbols-outlined'; i.setAttribute('data-cc-icon',name); }
            i.textContent=ok?name:fallback;
            i.style.cssText='flex:none;line-height:1;color:'+(color||'#434654')+';font-size:'+(size||20)+'px'+(ok?'':';font-family:system-ui,sans-serif;font-weight:700');
            return i;
          }
          function ccEl(tag,css,text){ var e=document.createElement(tag); if(css) e.style.cssText=css; if(text!=null) e.textContent=text; return e; }
          function ccBtn(attr,label,primary){
            var b=document.createElement('button'); b.type='button'; b.setAttribute(attr,'1'); b.textContent=label;
            b.className='font-label-md text-label-md';
            b.style.cssText='flex:none;border-radius:8px;padding:7px 12px;border:1px solid #003d9b;'+(primary?'background:#003d9b;color:#ffffff':'background:#ffffff;color:#003d9b');
            return b;
          }

          // ---- route geometry on the existing map (unchanged from F1) ----
          function routeLngLat(p){
            return Array.isArray(p)&&p.length>=2&&typeof p[0]==='number'&&typeof p[1]==='number'
              &&isFinite(p[0])&&isFinite(p[1])&&p[0]>=-180&&p[0]<=180&&p[1]>=-90&&p[1]<=90;
          }
          function routeLines(){
            var g=ccRoute.geometry;
            if(!g||!Array.isArray(g.coordinates)) return [];
            return g.type==='LineString'?[g.coordinates]:(g.type==='MultiLineString'?g.coordinates:[]);
          }
          function routeData(){
            var feats=[];
            if(ccRoute.state==='ready'){
              feats.push({type:'Feature',geometry:{type:'MultiLineString',coordinates:routeLines()},properties:{part:'line'}});
              if(routeLngLat(ccRoute.origin)) feats.push({type:'Feature',geometry:{type:'Point',coordinates:ccRoute.origin},properties:{part:'origin'}});
              if(routeLngLat(ccRoute.dest)) feats.push({type:'Feature',geometry:{type:'Point',coordinates:ccRoute.dest},properties:{part:'dest'}});
            }
            return {type:'FeatureCollection',features:feats};
          }
          // Removes ONLY the route's own layers and source.
          function routeRemoveLayers(){
            ccRouteDrawnGen=-1;
            if(!ccMap) return;
            try{
              for(var i=ROUTE_LAYERS.length-1;i>=0;i--){ if(ccMap.getLayer(ROUTE_LAYERS[i])) ccMap.removeLayer(ROUTE_LAYERS[i]); }
              if(ccMap.getSource(ROUTE_SRC)) ccMap.removeSource(ROUTE_SRC);
            }catch(err){}
          }
          // Draws the active route, or removes it. Safe to repeat: after a style reload, and from
          // mapRefresh, where it also keeps the route below the clusters/markers and above the
          // government heat layer whichever of them was added last.
          function routeSync(){
            if(!ccMap||!ccMapLoaded) return;
            if(ccRoute.state!=='ready'){ routeRemoveLayers(); return; }
            try{
              var src=ccMap.getSource(ROUTE_SRC);
              if(!src){ ccMap.addSource(ROUTE_SRC,{type:'geojson',data:routeData()}); ccRouteDrawnGen=ccRoute.gen; }
              else if(ccRouteDrawnGen!==ccRoute.gen){ src.setData(routeData()); ccRouteDrawnGen=ccRoute.gen; }
              var before=mapFirstLayer(['ccclusters','ccclustercount','ccpins']);
              if(!ccMap.getLayer('ccroute-casing')) ccMap.addLayer({id:'ccroute-casing',type:'line',source:ROUTE_SRC,
                filter:['==',['get','part'],'line'],layout:{'line-join':'round','line-cap':'round'},
                paint:{'line-color':'#ffffff','line-opacity':0.95,
                  'line-width':['interpolate',['linear'],['zoom'],8,6,13,9,17,14]}},before);
              if(!ccMap.getLayer('ccroute-line')) ccMap.addLayer({id:'ccroute-line',type:'line',source:ROUTE_SRC,
                filter:['==',['get','part'],'line'],layout:{'line-join':'round','line-cap':'round'},
                paint:{'line-color':ROUTE_COLOR,'line-opacity':0.95,
                  'line-width':['interpolate',['linear'],['zoom'],8,3,13,5,17,9]}},before);
              // Origin: a white dot ringed in the route colour. Destination: a ring around the
              // report's own marker (or the place), so the report itself stays the visible pin.
              if(!ccMap.getLayer('ccroute-ends')) ccMap.addLayer({id:'ccroute-ends',type:'circle',source:ROUTE_SRC,
                filter:['!=',['get','part'],'line'],paint:{
                  'circle-radius':['case',['==',['get','part'],'dest'],15,6],
                  'circle-color':'#ffffff',
                  'circle-opacity':['case',['==',['get','part'],'dest'],0,1],
                  'circle-stroke-width':3,
                  'circle-stroke-color':ROUTE_COLOR}},before);
              if(before){ for(var i=0;i<ROUTE_LAYERS.length;i++){ if(ccMap.getLayer(ROUTE_LAYERS[i])) ccMap.moveLayer(ROUTE_LAYERS[i],before); } }
            }catch(err){}
          }
          function routeRect(el){
            if(!el||el.style.display==='none') return null;
            var r=el.getBoundingClientRect();
            return (r.width>0&&r.height>0)?r:null;
          }
          // Just below the designed search bar and category chips.
          function routeTopOffset(){
            var hdr=document.querySelector('main header');
            var r=routeRect(hdr?hdr.parentElement:null);
            return r?Math.round(r.bottom)+8:76;
          }
          // Camera padding that keeps the route clear of the panels, the top controls, the report or
          // place card and the bottom navigation.
          function routePadding(){
            var h=window.innerHeight||640, w=window.innerWidth||360;
            var top=routeTopOffset()+16;
            var pr=routeRect(document.querySelector('[data-cc-routepanel]'));
            if(pr) top=Math.max(top,Math.round(pr.bottom)+24);
            var lowest=h;
            var cr=routeRect(document.querySelector('[data-cc-mapcard]')); if(cr) lowest=Math.min(lowest,cr.top);
            var pc=routeRect(document.querySelector('[data-cc-placecard]')); if(pc) lowest=Math.min(lowest,pc.top);
            var nr=routeRect(document.querySelector('nav')); if(nr) lowest=Math.min(lowest,nr.top);
            var bottom=Math.max(24,Math.round(h-lowest)+24);
            var room=h-140;
            if(room<60) return 20;
            if(top+bottom>room){ var k=room/(top+bottom); top=Math.max(10,Math.floor(top*k)); bottom=Math.max(10,Math.floor(bottom*k)); }
            var side=Math.max(24,Math.min(64,Math.floor(w/7)));
            return {top:top,bottom:bottom,left:side,right:side};
          }
          function routeFit(){
            if(!ccMap||!ccMapLoaded||!window.maplibregl||ccRoute.state!=='ready'||ccRouteFittedGen===ccRoute.gen) return;
            var b=null;
            function add(p){ if(!routeLngLat(p)) return; if(!b) b=new maplibregl.LngLatBounds(p,p); else b.extend(p); }
            var lines=routeLines();
            for(var i=0;i<lines.length;i++){ var l=lines[i]||[]; for(var j=0;j<l.length;j++) add(l[j]); }
            add(ccRoute.origin); add(ccRoute.dest);
            if(!b) return;
            ccRouteFittedGen=ccRoute.gen;
            // The route now owns the camera: a later report reload must not re-fit to the markers.
            ccMapFitted=true;
            try{ ccMap.fitBounds(b,{padding:routePadding(),maxZoom:16,duration:700,essential:true}); }
            catch(err){ try{ ccMap.fitBounds(b,{padding:20,maxZoom:16,duration:0}); }catch(e2){} }
          }
          function routeFmtDistance(m){
            if(m<950) return Math.round(m)+' m';
            var km=m/1000;
            return (km<10?km.toFixed(1):String(Math.round(km)))+' km';
          }
          function routeFmtTime(sec){
            var min=Math.round(sec/60);
            if(min<1) return 'Under 1 min';
            if(min<60) return 'Approx. '+min+' min';
            var hr=Math.floor(min/60), rest=min%60;
            return 'Approx. '+hr+' h'+(rest?(' '+rest+' min'):'');
          }

          // ---- the plan (what the user chose) versus the route on the map ----
          function planKey(){
            if(!ccPlan.dest) return '';
            return (ccPlan.origin.type==='place'?('P'+ccPlan.origin.id):'D')+'>'
              +(ccPlan.dest.type==='report'?('R'+ccPlan.dest.reportId):('P'+ccPlan.dest.id));
          }
          function routeKey(){
            if(ccRoute.state==='none') return '';
            return (ccRoute.originKind==='place'?('P'+ccRoute.originPlaceId):'D')+'>'
              +(ccRoute.destKind==='place'?('P'+ccRoute.destPlaceId):('R'+ccRoute.reportId));
          }
          function routePanelEl(){
            var p=document.querySelector('[data-cc-routepanel]');
            if(p) return p;
            var main=document.querySelector('main'); if(!main) return null;
            // Above the report/place cards (z-index 30) so its actions are never covered on short screens.
            p=ccEl('div','position:absolute;left:20px;right:20px;top:76px;z-index:35;display:none;overflow-y:auto;border-radius:12px');
            p.setAttribute('data-cc-routepanel','1');
            main.appendChild(p);
            return p;
          }
          function routeRow(label,iconName,fallback,text,sub){
            var row=ccEl('div','display:flex;align-items:center;gap:8px;margin-top:8px;min-width:0');
            row.appendChild(ccEl('span','flex:none;width:36px;font-size:11px;font-weight:700;color:#737685;text-transform:uppercase;letter-spacing:.04em',label));
            row.appendChild(ccIcon(iconName,fallback,ROUTE_COLOR,18));
            var t=ccEl('div','flex:1;min-width:0');
            t.appendChild(ccEl('p','margin:0;color:#191c22;font-size:14px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis',text));
            if(sub) t.appendChild(ccEl('p','margin:0;color:#737685;font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis',sub));
            row.appendChild(t);
            return row;
          }
          // Stitch card style: white surface, 12 px radius, the design's shadow, primary-blue actions.
          function routeRenderPanel(){
            var p=document.querySelector('[data-cc-routepanel]');
            var d=ccPlan.dest, o=ccPlan.origin, dev=o.type!=='place';
            if(!(ccRoute.state!=='none'||d||!dev||ccPickOrigin)){ if(p) p.style.display='none'; placeCardRender(); return; }
            p=routePanelEl(); if(!p) return;
            var pTop=routeTopOffset();
            p.style.top=pTop+'px';
            p.style.maxHeight=Math.max(160,(window.innerHeight||640)-pTop-110)+'px';
            p.innerHTML='';
            var card=ccEl('div','background:#ffffff;border:1px solid rgba(223,225,230,.7);border-radius:12px;box-shadow:0 8px 24px rgba(9,30,66,.12);padding:12px 14px');
            var head=ccEl('div','display:flex;align-items:center;gap:8px');
            head.appendChild(ccIcon('directions_car','→',ROUTE_COLOR,22));
            var hl=ccEl('p','margin:0;flex:1;color:#434654;text-transform:uppercase;letter-spacing:.06em','Route preview');
            hl.className='font-label-sm text-label-sm';
            head.appendChild(hl);
            head.appendChild(ccBtn('data-cc-routeclear','Clear Route',false));
            card.appendChild(head);
            var from=routeRow('From',dev?'my_location':'location_on',dev?'◎':'●',
              dev?'My location':(o.name||'Chosen place'),
              dev?'Device location (default)':('Chosen starting point'+(o.address?(' · '+o.address):'')));
            var fb=ccEl('div','display:flex;gap:6px;flex:none');
            if(placeSearchOn()) fb.appendChild(ccBtn('data-cc-routepickorigin',ccPickOrigin?'Searching':'Change',false));
            if(!dev) fb.appendChild(ccBtn('data-cc-routeusehere','My location',false));
            from.appendChild(fb);
            card.appendChild(from);
            card.appendChild(d
              ? routeRow('To',d.type==='report'?'flag':'location_on',d.type==='report'?'⚑':'●',
                  d.type==='report'?(d.title||'Selected report'):(d.name||'Chosen place'),
                  d.type==='report'?'CHIPCHAP report · its stored location':('Place'+(d.address?(' · '+d.address):'')))
              : routeRow('To','flag','⚑','No destination yet','Search a place and tap Route here, or open a report.'));
            var sum=ccEl('p','margin:10px 0 0;font-size:14px;color:#434654');
            var key=planKey(), same=!!key&&key===routeKey();
            if(ccPickOrigin) sum.textContent='Search above for a starting point, then choose it from the list.';
            else if(same&&ccRoute.state==='ready'){
              sum.textContent=routeFmtDistance(ccRoute.distance)+' · '+routeFmtTime(ccRoute.time)+' by car';
              sum.style.color='#191c22'; sum.style.fontWeight='700';
            }
            else if(same&&ccRoute.state==='loading') sum.textContent='Calculating route...';
            else if(same&&ccRoute.state==='error'){ sum.textContent=ccRoute.message||'Unable to calculate a route right now.'; sum.style.color='#ba1a1a'; }
            else if(d&&ccRoute.state==='ready') sum.textContent='The line ('+routeFmtDistance(ccRoute.distance)+') belongs to the previous start/destination. Tap Update Route to recalculate.';
            else if(d&&ccRoute.state==='loading') sum.textContent='A previous request is still running. Tap Update Route to use this choice instead.';
            else if(d) sum.textContent='Tap Show Route to calculate a driving route.';
            else sum.textContent='Starting point chosen. Now choose a destination.';
            card.appendChild(sum);
            // Route tools and the explicit action. Nothing is recalculated automatically, and Share
            // is offered only for a route calculated for exactly these endpoints (F3).
            if(!ccPickOrigin){
              var act=ccEl('div','display:flex;align-items:center;justify-content:space-between;gap:8px;margin-top:8px;flex-wrap:wrap');
              var tools=ccEl('div','display:flex;flex-wrap:wrap;gap:2px 14px;min-width:0');
              tools.appendChild(ccLink('data-cc-routecopystart','Copy start'));
              if(d) tools.appendChild(ccLink('data-cc-routecopydest','Copy destination'));
              if(same&&ccRoute.state==='ready') tools.appendChild(ccLink('data-cc-routeshare','Share route'));
              act.appendChild(tools);
              if(d&&!(same&&(ccRoute.state==='loading'||ccRoute.state==='ready')))
                act.appendChild(ccBtn('data-cc-routego',!routeKey()?'Show Route':(same?'Try Again':'Update Route'),true));
              card.appendChild(act);
            }
            p.appendChild(card);
            p.style.display='';
            placeCardRender();
          }
          function routeRender(){ routeRenderPanel(); routeSync(); routeFit(); }
          function routeGeometryOk(g){
            return !!(g&&(g.type==='MultiLineString'||g.type==='LineString')&&Array.isArray(g.coordinates)&&g.coordinates.length);
          }
          window.chipchapRoute=function(json){
            if(!here('map_explorer')) return;
            var d={}; try{ d=JSON.parse(json); }catch(err){ return; }
            var gen=(typeof d.gen==='number'&&isFinite(d.gen))?d.gen:-1;
            if(gen<ccRouteGen) return;   // an older answer never replaces a newer or cleared state
            ccRouteGen=gen;
            var st=(d.state==='loading'||d.state==='ready'||d.state==='error')?d.state:'none';
            ccRoute={state:st,gen:gen,reportId:String(d.reportId||''),title:String(d.title||''),
              message:String(d.message||''),distance:Number(d.distance),time:Number(d.time),
              geometry:d.geometry||null,origin:d.origin,dest:d.dest,
              destKind:d.destKind==='place'?'place':'report',destPlaceId:String(d.destPlaceId||''),
              originKind:d.originKind==='place'?'place':'device',originPlaceId:String(d.originPlaceId||''),
              originLabel:String(d.originLabel||'')};
            if(st==='ready'&&!(routeGeometryOk(ccRoute.geometry)&&isFinite(ccRoute.distance)&&isFinite(ccRoute.time))){
              ccRoute.state='error'; ccRoute.geometry=null; ccRoute.message='Unable to calculate a route right now.';
            }
            // A NEW request defines the plan (it is what the user just asked for). Edits made after
            // it are kept until the user asks again, so a chosen start is never silently replaced.
            if(st!=='none'&&gen>ccPlanGen){
              ccPlanGen=gen; ccPickOrigin=false;
              var po=ccPlan.origin;
              ccPlan.origin=(ccRoute.originKind==='place')
                ? ((po.type==='place'&&po.id===ccRoute.originPlaceId)?po:{type:'place',id:ccRoute.originPlaceId,name:ccRoute.originLabel,address:''})
                : {type:'device'};
              var pd=ccPlan.dest;
              ccPlan.dest=(ccRoute.destKind==='place')
                ? ((pd&&pd.type==='place'&&pd.id===ccRoute.destPlaceId)?pd:{type:'place',id:ccRoute.destPlaceId,name:ccRoute.title,address:''})
                : {type:'report',reportId:ccRoute.reportId,title:ccRoute.title};
            }
            if(ccPlan.dest&&ccPlan.dest.type==='report'&&ccPlan.dest.reportId===ccRoute.reportId&&ccRoute.title) ccPlan.dest.title=ccRoute.title;
            routeRender();
          };
          function routeGo(){
            var d=ccPlan.dest;
            if(!d){ toast('Choose a destination first.'); return; }
            if(Date.now()-ccRouteAskedAt<1500) return;   // double tap; native also refuses duplicates
            ccRouteAskedAt=Date.now();
            var oid=ccPlan.origin.type==='place'?ccPlan.origin.id:'';
            if(d.type==='report'){ if(native&&native.requestReportRouteFrom) native.requestReportRouteFrom(d.reportId,oid); }
            else if(native&&native.requestPlaceRoute) native.requestPlaceRoute(oid,d.id);
          }
          // Clear Route: the route, its layers, its panel and the chosen start/destination only. Any
          // reply still on its way is older than the cleared state and is ignored, here and natively.
          function routeClear(){
            ccRouteGen=Math.max(ccRouteGen,ccRoute.gen)+1;
            ccRoute={state:'none',gen:ccRouteGen};
            ccPlan={origin:{type:'device'},dest:null};
            if(ccPickOrigin){ ccPickOrigin=false; placeResetSearchBox(); }
            routeRender();
            if(native&&native.clearReportRoute) native.clearReportRoute();
          }
          function routePickOrigin(){
            if(!placeSearchOn()) return;
            ccPickOrigin=true;
            var s=placeInput();
            if(s){
              if(s.value){ s.value=''; s.dispatchEvent(new Event('input')); }
              s.setAttribute('placeholder','Search a starting point (place or address)');
              try{ s.focus(); }catch(err){}
            }
            routeRenderPanel(); placeRenderList();
          }
          // "Use my location": back to the device position. The position itself is read natively,
          // through the existing LocationProvider, when the user taps Show/Update Route.
          function routeUseMyLocation(){
            ccPlan.origin={type:'device'};
            if(ccPickOrigin){ ccPickOrigin=false; placeResetSearchBox(); }
            routeRenderPanel();
          }

          // ---- place / address search (Geoapify Geocoding), government map only ----
          function placeInput(){ return document.querySelector('[data-cc-mapsearch]'); }
          function placeInputText(){
            var s=placeInput(), v=s?String(s.value||'').replace(/ +/g,' ').trim():'';
            return v.length>PLACE_MAX_CHARS?v.slice(0,PLACE_MAX_CHARS):v;
          }
          function placeRestorePlaceholder(){ var s=placeInput(); if(s&&placeSearchOn()) s.setAttribute('placeholder',PLACE_PLACEHOLDER); }
          function placeWireSearch(search){
            if(!search||!placeSearchOn()) return;
            search.setAttribute('data-cc-mapsearch','1');
            search.setAttribute('placeholder',PLACE_PLACEHOLDER);
            search.setAttribute('maxlength',String(PLACE_MAX_CHARS));
            // Added next to the existing report-search listeners, which keep filtering markers.
            search.addEventListener('input',function(){ placeQueryChanged(false); });
            search.addEventListener('keydown',function(ev){ if(ev.key==='Enter') placeQueryChanged(true); });
            search.addEventListener('focus',function(){ if(placeInputText()||ccPickOrigin) placeRenderList(); });
          }
          // Debounced: one request per pause in typing, never per keystroke, never for <3 chars,
          // never twice for the same text. Searching never requests a route.
          function placeQueryChanged(now){
            if(!placeSearchOn()) return;
            var q=placeInputText();
            clearTimeout(ccPlaceTimer);
            if(q.length<PLACE_MIN_CHARS){ placeCancel(); placeRenderList(); return; }
            if(!(q===ccPlaceQuery&&(ccPlaceState==='ok'||ccPlaceState==='searching'))){
              ccPlaceTimer=setTimeout(function(){ placeSearchNow(q); },now?0:PLACE_DEBOUNCE_MS);
            }
            placeRenderList();
          }
          function placeSearchNow(q){
            if(q!==placeInputText()) return;   // typing moved on: no request for stale text
            if(q===ccPlaceQuery&&(ccPlaceState==='ok'||ccPlaceState==='searching')) return;
            ccPlaceSeq++; ccPlaceQuery=q; ccPlaceState='searching'; ccPlaceResults=[]; ccPlaceMsg='';
            placeRenderList();
            var c=null; try{ c=ccMap?ccMap.getCenter():null; }catch(err){}
            native.searchPlaces(q,ccPlaceSeq,c?c.lat:0,c?c.lng:0);
          }
          function placeCancel(){
            clearTimeout(ccPlaceTimer);
            var was=ccPlaceState==='searching';
            ccPlaceSeq++; ccPlaceQuery=''; ccPlaceState='idle'; ccPlaceResults=[]; ccPlaceMsg='';
            if(was&&native&&native.cancelPlaceSearch) native.cancelPlaceSearch();
          }
          window.chipchapPlaces=function(json){
            if(!here('map_explorer')) return;
            var d={}; try{ d=JSON.parse(json); }catch(err){ return; }
            if(d.seq!==ccPlaceSeq) return;   // an older or cancelled search
            if(d.state==='ok'){
              var list=[];
              (Array.isArray(d.results)?d.results:[]).forEach(function(r){
                if(r&&typeof r.id==='string'&&typeof r.lat==='number'&&typeof r.lng==='number'&&isFinite(r.lat)&&isFinite(r.lng))
                  list.push({id:r.id,name:String(r.name||'Unnamed place'),address:String(r.address||''),lat:r.lat,lng:r.lng});
              });
              ccPlaceState='ok'; ccPlaceResults=list;
            } else { ccPlaceState='error'; ccPlaceMsg=String(d.message||'Place search is unavailable right now.'); }
            placeRenderList();
          };
          function placeListEl(){
            var l=document.querySelector('[data-cc-placelist]'); if(l) return l;
            var main=document.querySelector('main'); if(!main) return null;
            l=ccEl('div','position:absolute;left:20px;right:20px;top:120px;z-index:40;display:none;background:#ffffff;border:1px solid rgba(223,225,230,.9);border-radius:12px;box-shadow:0 8px 24px rgba(9,30,66,.16);max-height:55vh;overflow-y:auto;padding:6px 0');
            l.setAttribute('data-cc-placelist','1');
            main.appendChild(l);
            return l;
          }
          function placeListTop(){
            var s=placeInput(), box=s?(s.closest('.rounded-full')||s):null;
            var r=routeRect(box);
            return r?Math.round(r.bottom)+6:120;
          }
          function placeHideList(){ var l=document.querySelector('[data-cc-placelist]'); if(l) l.style.display='none'; }
          function placeSection(l,title){ l.appendChild(ccEl('p','margin:8px 14px 4px;font-size:11px;font-weight:700;color:#737685;text-transform:uppercase;letter-spacing:.06em',title)); }
          function placeLine(l,text,color){ l.appendChild(ccEl('p','margin:2px 14px 8px;font-size:13px;color:'+(color||'#434654'),text)); }
          function placeItem(l,attr,value,iconName,fallback,color,title,sub,badge){
            var b=document.createElement('button'); b.type='button'; b.setAttribute(attr,value);
            b.style.cssText='display:flex;align-items:center;gap:10px;width:100%;text-align:left;background:#ffffff;border:0;padding:8px 14px';
            b.appendChild(ccIcon(iconName,fallback,color,20));
            var t=ccEl('span','flex:1;min-width:0;display:block');
            t.appendChild(ccEl('span','display:block;font-size:14px;font-weight:600;color:#191c22;white-space:nowrap;overflow:hidden;text-overflow:ellipsis',title));
            if(sub) t.appendChild(ccEl('span','display:block;font-size:12px;color:#737685;white-space:nowrap;overflow:hidden;text-overflow:ellipsis',sub));
            b.appendChild(t);
            if(badge) b.appendChild(ccEl('span','flex:none;font-size:11px;font-weight:700;color:'+color+';border:1px solid '+color+';border-radius:999px;padding:1px 8px',badge));
            l.appendChild(b);
          }
          // Reports and real-world places are listed in separate, labelled sections.
          function placeRenderList(){
            if(!placeSearchOn()) return;
            var q=placeInputText();
            if(!q&&!ccPickOrigin){ placeHideList(); return; }
            var l=placeListEl(); if(!l) return;
            l.style.top=placeListTop()+'px';
            l.innerHTML='';
            var head=ccEl('div','display:flex;align-items:center;justify-content:space-between;padding:2px 8px 2px 14px');
            head.appendChild(ccEl('p','margin:0;font-size:13px;font-weight:700;color:#191c22',ccPickOrigin?'Choose a starting point':'Search results'));
            var close=ccEl('button','background:none;border:0;color:#003d9b;font-size:13px;font-weight:600;padding:6px',ccPickOrigin?'Cancel':'Close');
            close.type='button'; close.setAttribute(ccPickOrigin?'data-cc-pickcancel':'data-cc-placelistclose','1');
            head.appendChild(close);
            l.appendChild(head);
            if(ccPickOrigin){
              placeItem(l,'data-cc-routeusehere','1','my_location','◎','#003d9b','Use my location','Device location (default)','');
            } else {
              placeSection(l,'CHIPCHAP reports');
              var reps=mapQuery?mapMatches().slice(0,4):[];
              if(!reps.length) placeLine(l,'No CHIPCHAP reports match "'+q+'".');
              reps.forEach(function(r){
                placeItem(l,'data-cc-placereport',r.reportId,'flag','⚑',categoryColor(r.category),r.title||'Untitled report',
                  statusLabel(r.status)+(r.address?(' · '+r.address):''),'Report');
              });
            }
            placeSection(l,'Places and addresses');
            if(q.length<PLACE_MIN_CHARS) placeLine(l,'Type at least '+PLACE_MIN_CHARS+' characters to search places.');
            else if(ccPlaceState==='error'&&q===ccPlaceQuery) placeLine(l,ccPlaceMsg,'#ba1a1a');
            else if(ccPlaceState==='ok'&&q===ccPlaceQuery){
              if(!ccPlaceResults.length) placeLine(l,'No places found for "'+q+'".');
              ccPlaceResults.forEach(function(pl){ placeItem(l,'data-cc-placepick',pl.id,'location_on','●',PLACE_COLOR,pl.name,pl.address,'Place'); });
            }
            else placeLine(l,'Searching places...');
            l.style.display='';
          }
          function placeResetSearchBox(){
            var s=placeInput();
            placeCancel(); placeHideList();
            if(s&&s.value){ s.value=''; s.dispatchEvent(new Event('input')); }
            placeRestorePlaceholder();
          }
          function placeById(id){ for(var i=0;i<ccPlaceResults.length;i++){ if(ccPlaceResults[i].id===id) return ccPlaceResults[i]; } return null; }
          function placeShowMarker(pl){
            if(!ccMap||!window.maplibregl||!pl) return;
            try{
              if(!ccPlaceMarker){
                var dot=ccEl('div','width:22px;height:22px;border-radius:50%;background:'+PLACE_COLOR+';border:4px solid #ffffff;box-shadow:0 2px 10px rgba(9,30,66,.4)');
                ccPlaceMarker=new maplibregl.Marker({element:dot}).setLngLat([pl.lng,pl.lat]).addTo(ccMap);
              } else ccPlaceMarker.setLngLat([pl.lng,pl.lat]);
            }catch(err){}
          }
          function placeRemoveMarker(){ if(ccPlaceMarker){ try{ ccPlaceMarker.remove(); }catch(err){} ccPlaceMarker=null; } }
          function placeFly(pl){
            if(!ccMap) return;
            ccMapFitted=true;   // a report reload must not pull the camera away from the chosen place
            try{ ccMap.flyTo({center:[pl.lng,pl.lat],zoom:Math.max(ccMap.getZoom(),15),duration:800,essential:true}); }catch(err){}
          }
          // A chosen result: its real coordinates (as returned natively), a temporary marker, the
          // camera, and either the starting point (pick mode) or the place card with route actions.
          function placePick(id){
            var found=placeById(id);
            if(!found){ toast('That place is no longer listed. Search again.'); return; }
            var pl={id:found.id,name:found.name,address:found.address,lat:found.lat,lng:found.lng};
            var forOrigin=ccPickOrigin;
            ccPickOrigin=false;
            placeResetSearchBox();
            placeShowMarker(pl);
            placeFly(pl);
            if(forOrigin){ ccPlan.origin={type:'place',id:pl.id,name:pl.name,address:pl.address,lat:pl.lat,lng:pl.lng}; renderMapCard(); }
            else { ccPlace=pl; mapSelected=''; mapRefresh(); }
            routeRenderPanel();
          }
          function placeReport(id){
            var r=mapPoint(id); if(!r) return;
            placeHideList();
            mapSelected=id; ccMapFitted=true;
            mapRefresh();
            if(ccMap) ccMap.flyTo({center:[r.lng,r.lat],zoom:Math.max(ccMap.getZoom(),CLUSTER_MAX_ZOOM+1),duration:700,essential:true});
          }
          // The place card shares the report card's spot; an open report card takes precedence.
          function placeCardRender(){
            var c=document.querySelector('[data-cc-placecard]');
            var rp=mapSelected?mapPoint(mapSelected):null;
            // Hidden while the place is already the route's start or destination: the route panel
            // shows it, and the card would otherwise cover the panel on short screens.
            var inPlan=!!ccPlace&&((ccPlan.origin.type==='place'&&ccPlan.origin.id===ccPlace.id)||(!!ccPlan.dest&&ccPlan.dest.type==='place'&&ccPlan.dest.id===ccPlace.id));
            if(!ccPlace||inPlan||(rp&&mapVisible(rp))){ if(c) c.style.display='none'; return; }
            if(!c){
              var main=document.querySelector('main'); if(!main) return;
              c=ccEl('div','position:absolute;left:20px;right:20px;bottom:96px;z-index:30');
              c.setAttribute('data-cc-placecard','1');
              main.appendChild(c);
            }
            c.innerHTML='';
            var card=ccEl('div','background:#ffffff;border:1px solid rgba(223,225,230,.7);border-radius:12px;box-shadow:0 8px 24px rgba(9,30,66,.12);padding:14px 16px');
            var top=ccEl('div','display:flex;align-items:flex-start;gap:8px');
            top.appendChild(ccIcon('location_on','●',PLACE_COLOR,22));
            var t=ccEl('div','flex:1;min-width:0');
            t.appendChild(ccEl('p','margin:0;font-size:11px;font-weight:700;color:'+PLACE_COLOR+';text-transform:uppercase;letter-spacing:.06em','Place · not a CHIPCHAP report'));
            t.appendChild(ccEl('p','margin:2px 0 0;font-size:16px;font-weight:700;color:#191c22',ccPlace.name));
            if(ccPlace.address) t.appendChild(ccEl('p','margin:2px 0 0;font-size:13px;color:#434654',ccPlace.address));
            top.appendChild(t);
            var x=ccEl('button','flex:none;background:none;border:0;font-size:22px;line-height:1;color:#434654;padding:0 4px','×');
            x.type='button'; x.setAttribute('data-cc-placeclose','1'); x.setAttribute('aria-label','Clear place');
            top.appendChild(x);
            card.appendChild(top);
            var act=ccEl('div','display:flex;gap:8px;margin-top:12px');
            var go=ccBtn('data-cc-placeroute','Route here',true); go.style.flex='1';
            var st=ccBtn('data-cc-placestart','Start from here',false); st.style.flex='1';
            act.appendChild(go); act.appendChild(st);
            card.appendChild(act);
            c.appendChild(card);
            c.style.display='';
          }
          function placeRouteHere(){
            if(!ccPlace) return;
            ccPlan.dest={type:'place',id:ccPlace.id,name:ccPlace.name,address:ccPlace.address,lat:ccPlace.lat,lng:ccPlace.lng};
            routeRenderPanel();
            routeGo();
          }
          function placeStartHere(){
            if(!ccPlace) return;
            ccPlan.origin={type:'place',id:ccPlace.id,name:ccPlace.name,address:ccPlace.address,lat:ccPlace.lat,lng:ccPlace.lng};
            routeRenderPanel();
          }
          // Clear place: only the temporary marker and its card. Reports, route and plan untouched.
          function placeClose(){ ccPlace=null; placeRemoveMarker(); placeCardRender(); }
          function ccLink(attr,label){
            var b=ccEl('button','background:none;border:0;padding:6px 0;color:#003d9b;font-size:13px;font-weight:600',label);
            b.type='button'; b.setAttribute(attr,'1');
            return b;
          }
          // ---- F3: copy / share (composed natively from real stored or returned values) ----
          function routeOriginId(){ return ccPlan.origin.type==='place'?ccPlan.origin.id:''; }
          function routeCopyStart(){ if(native&&native.copyRouteStart) native.copyRouteStart(routeOriginId()); }
          function routeCopyDest(){
            var d=ccPlan.dest;
            if(!d){ toast('Choose a destination first.'); return; }
            if(native&&native.copyRouteDestination) native.copyRouteDestination(d.type==='report'?d.reportId:'',d.type==='place'?d.id:'');
          }
          function routeShare(){
            var d=ccPlan.dest;
            // Never share the previous route's distance/time for new endpoints (native re-checks).
            if(!d||ccRoute.state!=='ready'||planKey()!==routeKey()){ toast('Calculate the route for the current start and destination first.'); return; }
            if(native&&native.shareRoute) native.shareRoute(routeOriginId(),d.type==='report'?d.reportId:'',d.type==='place'?d.id:'');
          }
          // ---- F3: a route to the report shown in the map's report card (stored coordinates, native) ----
          function mapCardRouteButton(){
            var b=document.createElement('button'); b.type='button';
            b.setAttribute('data-cc-mapcardroute','1');
            b.className='flex-1 bg-surface text-primary border border-primary font-label-md text-label-md h-12 rounded-lg flex items-center justify-center active:scale-95';
            b.textContent='Route';
            return b;
          }
          function routeToSelectedReport(){
            var r=mapPoint(mapSelected);
            if(!r){ toast('Tap a report on the map first.'); return; }
            ccPlan.dest={type:'report',reportId:r.reportId,title:r.title||'Untitled report'};
            // The route panel now names the report; its card is closed so it cannot cover the panel.
            mapSelected=''; mapRefresh();
            routeRenderPanel();
            routeGo();
          }
          // ---- F3: the government portal's own bottom navigation on the shared map ----
          // Same items, icons, labels and Stitch classes as the government screens (dashboard,
          // complaint_queue, analytics, admin_profile; Alerts is not part of it). It has no Map item, so
          // nothing is highlighted here. Shown only when the NATIVE session check says GOVERNMENT;
          // every destination is still gated natively by loadScreen().
          var GOV_NAV=[['dashboard','dashboard','▦','Dashboard'],['complaint_queue','assignment','▤','Complaints'],
            ['analytics','leaderboard','▥','Analytics'],['admin_profile','person','●','Profile']];
          function mapUseGovNav(){
            var nav=document.querySelector('nav');
            if(!nav||nav.getAttribute('data-cc-govmapnav')) return;
            var fontOk=iconFontOk();
            var g=document.createElement('nav');
            g.setAttribute('data-cc-govmapnav','1');
            g.className='bg-surface shadow-[0_-4px_12px_rgba(9,30,66,0.08)] docked full-width bottom-0 rounded-t-xl fixed bottom-0 left-0 w-full z-50 flex justify-around items-center px-gutter py-2 pb-safe';
            GOV_NAV.forEach(function(it){
              var a=document.createElement('a'); a.href='#'; a.setAttribute('data-cc-govnavto',it[0]);
              a.className='flex flex-col items-center justify-center text-on-surface-variant hover:text-primary transition-all duration-300 active:scale-95 px-3 py-1 group cursor-pointer';
              var ic=document.createElement('span');
              ic.className='material-symbols-outlined group-hover:scale-110 transition-transform';
              ic.setAttribute('data-cc-icon',it[1]);
              ic.textContent=fontOk?it[1]:it[2];
              if(!fontOk){ ic.style.fontFamily='system-ui, sans-serif'; ic.style.fontWeight='600'; }
              var lb=document.createElement('span'); lb.className='font-label-sm text-label-sm mt-1'; lb.textContent=it[3];
              a.appendChild(ic); a.appendChild(lb); g.appendChild(a);
            });
            nav.parentNode.replaceChild(g,nav);
          }
          function routeHandleClick(t){
            if(t.getAttribute('data-cc-routecopystart')){ routeCopyStart(); return true; }
            if(t.getAttribute('data-cc-routecopydest')){ routeCopyDest(); return true; }
            if(t.getAttribute('data-cc-routeshare')){ routeShare(); return true; }
            if(t.getAttribute('data-cc-mapcardroute')){ routeToSelectedReport(); return true; }
            var gnav=t.getAttribute('data-cc-govnavto'); if(gnav){ if(native&&native.openScreen) native.openScreen(gnav); return true; }
            if(t.getAttribute('data-cc-routeclear')){ routeClear(); return true; }
            if(t.getAttribute('data-cc-routego')){ routeGo(); return true; }
            if(t.getAttribute('data-cc-routepickorigin')){ routePickOrigin(); return true; }
            if(t.getAttribute('data-cc-routeusehere')){ routeUseMyLocation(); return true; }
            if(t.getAttribute('data-cc-pickcancel')){ ccPickOrigin=false; placeResetSearchBox(); routeRenderPanel(); return true; }
            if(t.getAttribute('data-cc-placelistclose')){ placeHideList(); return true; }
            var pid=t.getAttribute('data-cc-placepick'); if(pid){ placePick(pid); return true; }
            var rid=t.getAttribute('data-cc-placereport'); if(rid){ placeReport(rid); return true; }
            if(t.getAttribute('data-cc-placeroute')){ placeRouteHere(); return true; }
            if(t.getAttribute('data-cc-placestart')){ placeStartHere(); return true; }
            if(t.getAttribute('data-cc-placeclose')){ placeClose(); return true; }
            return false;
          }

        """, """
          // ---------------- details: incident-location map (Post-checkpoint-1 fix) ----------------
          // The Stitch box was a static stock picture with a decorative centre pin, so it could not
          // pan, zoom or be tapped. The same bundled MapLibre + OpenFreeMap style as the map screen
          // is mounted in the same box. The pin stays in the centre: panning or tapping moves the
          // map, and the centre becomes the incident location in the existing draft fields.
          var ccPinMap=null, ccPinLast=null;
          function pinDraft(){ try{ return JSON.parse((native&&native.getComplaintDraft)?native.getComplaintDraft():'{}')||{}; }catch(err){ return {}; } }
          function pinDraftPoint(d){
            var la=Number(d.latitude), lo=Number(d.longitude);
            return (d.hasCoordinates&&isFinite(la)&&isFinite(lo)&&la>=-90&&la<=90&&lo>=-180&&lo<=180&&!(la===0&&lo===0))?[lo,la]:null;
          }
          function pinSame(a,b){ return !!(a&&b&&Math.abs(a[0]-b[0])<1e-7&&Math.abs(a[1]-b[1])<1e-7); }
          function pinNote(text){ var el=document.querySelector('[data-cc-location]'); if(el&&text) el.textContent=text; }
          function setupIncidentMap(){
            var btn=document.querySelector('button[aria-label="Use current location"]');
            var box=btn?btn.parentElement:null;
            if(!box||box.querySelector('[data-cc-pinmap]')) return;
            // The stock picture (and its press-to-zoom transform, which would distort map touches) is removed.
            var bg=box.querySelector('.bg-cover');
            if(bg){ bg.style.backgroundImage='none'; bg.removeAttribute('data-alt'); bg.style.display='none'; }
            box.classList.remove('cursor-pointer');
            var host=document.createElement('div');
            host.setAttribute('data-cc-pinmap','1');
            host.style.cssText='position:absolute;top:0;left:0;right:0;bottom:0;z-index:0;background-color:#eef3ea';
            box.insertBefore(host, box.firstChild);
            // The designed centre pin and the current-location button always stay above the map.
            btn.style.zIndex='2';
            Array.prototype.forEach.call(box.children,function(c){ if(c!==host&&c!==btn&&c!==bg) c.style.zIndex='1'; });
            mapLoadLibrary(function(){
              if(!window.maplibregl){ miniMapNote(host,'The map could not be loaded. Your device location is still used.'); return; }
              var start=pinDraftPoint(pinDraft());
              try{
                ccPinMap=new maplibregl.Map({container:host,style:MAP_STYLE_URL,center:start||MAP_FALLBACK_CENTER,
                  zoom:start?16:MAP_FALLBACK_ZOOM,attributionControl:{compact:true},dragRotate:false,pitchWithRotate:false});
              }catch(err){ ccPinMap=null; miniMapNote(host,'The map could not be loaded. Your device location is still used.'); return; }
              if(ccPinMap.touchZoomRotate&&ccPinMap.touchZoomRotate.disableRotation) ccPinMap.touchZoomRotate.disableRotation();
              ccPinLast=start;
              ccPinMap.on('error',function(){ if(!ccPinMap.loaded()) miniMapNote(host,'Map tiles could not be loaded. Check the connection.'); });
              ccPinMap.on('load',function(){ var n=host.querySelector('[data-cc-locmapnote]'); if(n) n.remove(); });
              // Tap: the tapped point moves under the centre pin.
              ccPinMap.on('click',function(ev){ if(ev&&ev.lngLat) ccPinMap.easeTo({center:ev.lngLat,duration:300}); });
              ccPinMap.on('moveend',function(){ pinMoved(); });
            });
          }
          // The citizen moved the map: the centre becomes the incident location (validated natively).
          function pinMoved(){
            if(!ccPinMap) return;
            var c=ccPinMap.getCenter(), p=[c.lng,c.lat];
            if(pinSame(p,ccPinLast)) return;   // our own recentre, or no real move
            if(ccPinMap.getZoom()<13){ pinNote('Zoom in to place the pin precisely.'); return; }
            ccPinLast=p;
            if(native&&native.setComplaintPinLocation) native.setComplaintPinLocation(c.lat,c.lng);
          }
          // A new device fix (Use current location) recentres the map; the pin the citizen placed is
          // already the draft location, so it does not move the map.
          window.ccPinMapSync=function(){
            if(!ccPinMap) return;
            var p=pinDraftPoint(pinDraft());
            if(!p||pinSame(p,ccPinLast)) return;
            ccPinLast=p;
            ccPinMap.jumpTo({center:p,zoom:Math.max(ccPinMap.getZoom(),16)});
          };

          function mapInit(){
            var host=document.querySelector('[data-cc-mapsurface]');
            if(!host||!window.maplibregl){ mapNote('The map could not start on this device.'); return; }
            try{
              // If a real position already arrived while the library was loading, start there.
              ccMap=new maplibregl.Map({container:host,style:MAP_STYLE_URL,
                center:ccMapHere||MAP_FALLBACK_CENTER,zoom:ccMapHere?14:MAP_FALLBACK_ZOOM});
            }catch(err){ mapNote('The map could not start on this device.'); return; }
            if(ccMapHere) mapShowHere();
            // Only a failure BEFORE the map has loaded means the map itself is unavailable. After
            // that, MapLibre also reports single tiles or glyphs that failed and retries them on
            // the next pan; covering a working map with a failure note for those was misleading.
            ccMap.on('error',function(){
              if(ccMapLoaded) return;
              mapNote('Map tiles could not be loaded. Check the connection and open the map again.');
            });
            ccMap.on('load',function(){
              ccMapLoaded=true;
              mapNoteClear();
              mapEnsureLayers();
              mapRefresh();
              mapFitToPoints();
              routeRender();
            });
            // F1: a reloaded style loses every runtime layer; the route is redrawn (or stays cleared).
            ccMap.on('style.load',function(){ if(ccMapLoaded){ ccRouteDrawnGen=-1; routeSync(); } });
            if(native&&native.loadMapReports) native.loadMapReports();
            if(native&&native.loadRouteState) native.loadRouteState();
            // F2: touching the map closes the search result list.
            ccMap.on('click',function(){ placeHideList(); });
          }

          function setupMap(){
            var main=document.querySelector('main');
            if(!main) return;
            // The role is decided natively (hasGovernmentAccess); the page only learns which map to show.
            if(native&&native.getMapMode&&native.getMapMode()==='GOVERNMENT') ccMapMode='GOVERNMENT';
            // The Stitch full-bleed map layer becomes the live map surface: same element, same place.
            var surface=main.querySelector('[data-location]');
            if(surface){
              surface.removeAttribute('data-location');
              surface.removeAttribute('data-alt');
              surface.innerHTML='';
              surface.style.backgroundImage='none';
              surface.style.backgroundColor='#eef3ea';
              surface.setAttribute('data-cc-mapsurface','1');
            }
            // The decorative Stitch pins that were drawn on the picture.
            main.querySelectorAll('button').forEach(function(b){
              var cls=b.className||'';
              if(!b.getAttribute('aria-label') && cls.indexOf('absolute')>=0 && cls.indexOf('top-[')>=0) b.remove();
            });
            // Original issue card: tag it, its X and its View Details link.
            main.querySelectorAll('h3').forEach(function(h){
              if(txt(h)!=='Severe Pothole') return;
              var card=h.closest('.absolute');
              if(!card||card===main) return;
              card.setAttribute('data-cc-mapcard','1');
              h.setAttribute('data-cc-mapcardtitle','1');
              card.querySelectorAll('span').forEach(function(sp){
                var t=txt(sp);
                if(/Pending$/.test(t)&&sp.children.length) sp.setAttribute('data-cc-mapcardstatus','1');
                else if(!sp.children.length&&/miles away$/.test(t)) sp.setAttribute('data-cc-mapcarddistance','1');
                else if(!sp.children.length&&/Backers$/.test(t)) sp.setAttribute('data-cc-mapcardbackers','1');
              });
              var close=card.querySelector('button[aria-label]'); if(close) close.setAttribute('data-cc-mapcardclose','1');
              var view=card.querySelector('a'); if(view) view.setAttribute('data-cc-mapcarddetails','1');
              // F3: an explicit Route action beside View Details (the report's stored coordinates).
              if(view&&view.parentElement&&native&&native.requestReportRouteFrom&&!card.querySelector('[data-cc-mapcardroute]')) view.parentElement.appendChild(mapCardRouteButton());
              card.style.display='none';
            });
            document.querySelectorAll('button').forEach(function(btn){
              var aria=(btn.getAttribute('aria-label')||'').toLowerCase();
              if(aria.indexOf('report new')>=0) btn.setAttribute('data-cc-newreport','1');
              if(aria.indexOf('my location')>=0) btn.setAttribute('data-cc-mylocation','1');
              var ic=btn.querySelector('.material-symbols-outlined');
              if(ic&&String(ic.dataset.ccIcon||ic.textContent||'').trim()==='tune') btn.setAttribute('data-cc-mapfilters','1');
              var label=txt(btn);
              if(label==='All'||label==='Infrastructure'||label==='Cleanliness'||label==='Safety') btn.setAttribute('data-cc-mapchip',label);
            });
            // Government sessions do not file citizen reports: the floating Create Report button is
            // removed for them only. Citizens keep it exactly as before.
            if(ccMapMode==='GOVERNMENT'){ var nrb=document.querySelector('[data-cc-newreport]'); if(nrb) nrb.remove(); mapUseGovNav(); }
            var allChip=document.querySelector('[data-cc-mapchip="All"]'), otherChip=document.querySelector('[data-cc-mapchip="Safety"]');
            if(allChip&&otherChip){ window.__ccChipOn=allChip.className; window.__ccChipOff=otherChip.className; }
            var search=document.querySelector('input[placeholder*="Search"]');
            if(search){
              var searchTimer=null;
              // Markers filter as you type; the map moves to the result once typing pauses, or at
              // once on the keyboard's Search/Enter key.
              search.addEventListener('input',function(){
                mapQueryText=(search.value||'').trim();
                mapQuery=mapQueryText.toLowerCase();
                if(mapSelected&&!mapVisible(mapPoint(mapSelected))) mapSelected='';
                mapRefresh();
                mapSearchStatus();
                clearTimeout(searchTimer);
                searchTimer=setTimeout(function(){ mapFocusMatches(false); },700);
              });
              search.addEventListener('keydown',function(ev){
                if(ev.key!=='Enter') return;
                ev.preventDefault();
                clearTimeout(searchTimer);
                mapFocusMatches(true);
                search.blur();
              });
            }
            placeWireSearch(search);
            renderMapCard();
            mapLoadLibrary(mapInit);
          }

          // ---------------- success screen ----------------
          function setupSuccess(){
            var id=(native&&native.getLastReportId)?native.getLastReportId():'';
            var nodes=document.querySelectorAll('span,div,p,h3');
            for(var i=0;i<nodes.length;i++){
              if(nodes[i].children.length===0 && txt(nodes[i]).indexOf('#CCP-')===0){
                nodes[i].setAttribute('data-cc-trackid','1');
                if(id) nodes[i].textContent='#'+id;
              }
            }
            var copy=document.querySelector('button[aria-label]');
            var buttons=document.querySelectorAll('button,a');
            for(var b=0;b<buttons.length;b++){
              var aria=(buttons[b].getAttribute('aria-label')||'').toLowerCase();
              if(aria.indexOf('copy')>=0) buttons[b].setAttribute('data-cc-copytrack','1');
              if(txt(buttons[b]).toLowerCase().indexOf('track status')>=0) buttons[b].setAttribute('data-cc-trackstatus','1');
            }
          }

          // ---------------- live_tracking_timeline ----------------
          window.chipchapReportDetail=function(json){
            var d={}; try{d=JSON.parse(json)}catch(err){}
            if(here('complaint_details_1')){ bindReportDetails(d); return; }
            var host=document.querySelector('[data-cc-timeline]');
            var headline=document.querySelector('[data-cc-trackstatusline]');
            if(!d.found){
              if(headline) headline.textContent=d.error||'Report not found.';
              if(host){ host.innerHTML=''; host.appendChild(note(d.error||'This report could not be loaded.')); }
              return;
            }
            var r=d.report||{};
            try{ bindLocationPreviews(r); }catch(mapErr){}
            if(headline) headline.textContent=(r.title||'Report')+' - '+statusLabel(r.status);
            var sub=document.querySelector('[data-cc-trackmeta]');
            if(sub) sub.textContent=[r.category,r.severity?(cap(r.severity)+' severity'):'',locationText(r),r.reportId?('#'+r.reportId):''].filter(Boolean).join(' | ');
            if(!host) return;
            host.innerHTML='';
            if(!d.timelineLoaded){ host.appendChild(note(d.error||'The history could not be loaded.')); return; }
            var events=d.timeline||[];
            if(!events.length){ host.appendChild(note('No status events have been recorded yet.')); return; }
            for(var i=0;i<events.length;i++){
              var row=document.createElement('div');
              row.style.cssText='padding:12px 0;border-bottom:1px solid #dfe1e6';
              var when=fmtDateTime(events[i].createdAt);
              row.innerHTML='<div style="font-weight:700;color:#051a3e">'+esc(statusLabel(events[i].status))+'</div>'
                +'<div style="font-size:14px;color:#434654;margin-top:2px">'+esc(events[i].message||'')+'</div>'
                +((events[i].actorName||when)?'<div style="font-size:12px;color:#737685;margin-top:2px">'
                  +(events[i].actorName?'by '+esc(events[i].actorName):'')+(events[i].actorName&&when?' - ':'')+esc(when)+'</div>':'');
              host.appendChild(row);
            }
            var pending=document.createElement('p');
            pending.style.cssText='font-size:12px;color:#737685;padding:10px 0 0';
            pending.textContent='Later stages appear here once city staff act on the report.';
            host.appendChild(pending);
          };

          /** Paints a remote HTTPS Cloudinary image into an existing Stitch tile. */
          function showRemoteEvidence(el,url){
            if(!el||!url||url.indexOf('https://res.cloudinary.com/')!==0) return false;
            el.textContent='';
            el.style.backgroundImage='url("'+url+'")';
            el.style.backgroundSize='cover';
            el.style.backgroundPosition='center';
            el.style.minHeight=el.style.minHeight||'160px';
            return true;
          }
          window.chipchapEvidenceUpload=function(json){
            var d={}; try{d=JSON.parse(json)}catch(err){}
            if(d.state==='failed') toast('Photo saved on this device, but the upload failed. '+(d.message||''));
          };
          window.chipchapAfterPhoto=function(json){
            var d={}; try{d=JSON.parse(json)}catch(err){}
            var tile=document.querySelector('[data-cc-aftertile]');
            if(!tile) return;
            var cap=tile.querySelector('[data-cc-afterstate]');
            var saved=tile.getAttribute('data-cc-aftersavedurl')||'';
            if(d.hasPhoto && native && native.getGovernmentAfterPhotoPreview){
              tile.setAttribute('data-cc-afterlocal','1');
              var uri=native.getGovernmentAfterPhotoPreview();
              if(uri){ tile.style.backgroundImage='url("'+uri+'")'; tile.style.backgroundSize='cover'; tile.style.backgroundPosition='center'; }
            } else {
              tile.removeAttribute('data-cc-afterlocal');
              // No new photo: keep showing the resolution photo already saved on the report.
              tile.style.backgroundImage=saved?('url("'+saved+'")'):'';
            }
            if(cap){
              cap.textContent=d.error ? ('Upload failed: '+d.error+' Tap to retake.')
                : d.uploading ? 'Uploading...'
                : d.uploaded ? 'Photo ready'
                : d.hasPhoto ? 'Photo captured'
                : saved ? 'Saved resolution photo'
                : 'AFTER (UPLOAD)';
            }
          };

          function evidenceNote(photo,text){
            var n=photo.querySelector('[data-cc-evidencenote]');
            if(!n){
              n=document.createElement('div');
              n.setAttribute('data-cc-evidencenote','1');
              n.style.cssText='position:absolute;left:0;top:0;right:0;bottom:0;display:flex;align-items:center;justify-content:center;text-align:center;padding:12px 12px 34px;font-size:12px;line-height:1.35;color:#434654';
              photo.appendChild(n);
            }
            n.textContent=text||'';
            n.style.display=text?'flex':'none';
          }

          /**
           * Binds the Complaint Details Stitch screen to the real Firestore report.
           * Every value replaced here was hard-coded Stitch placeholder content
           * ("Severe Pothole on Main St.", "#CCP-8291", "In Progress", "Reported by Citizen
           * User on Oct 24, 2023", "Sanitation & Public Works", "2-3 Bus. Days"), which is why
           * a real report still looked like the demo complaint.
           */
          function bindReportDetails(d){
            var r=(d&&d.report)||{};
            var main=document.querySelector('main');
            // Only tag when setupDetailsView could not (no active id); never reset the vote state here.
            if(main&&r.reportId&&!main.getAttribute('data-cc-report')) main.setAttribute('data-cc-report', r.reportId);

            var nodes=document.querySelectorAll('span,p,h2');
            for(var i=0;i<nodes.length;i++){
              var el=nodes[i];
              if(el.children.length) continue;
              var t=txt(el);
              if(t==='In Progress') el.textContent=statusLabel(r.status);
              else if(t.indexOf('#CCP-')===0) el.textContent='#'+(r.reportId||'');
              else if(t.indexOf('Severe Pothole')===0) el.textContent=r.title||'Untitled report';
              else if(t.indexOf('Reported by')===0) el.textContent='Reported by '+(r.citizenName||'a citizen')+(r.createdAt?(' on '+fmtDate(r.createdAt)):'');
              else if(t.indexOf('Sanitation')===0) el.textContent=r.assignedGovernmentName||'Not assigned yet';
              else if(t.indexOf('Bus. Days')>=0){
                // No resolution estimate exists in CHIPCHAP: the demo "EST. RESOLUTION" box is hidden,
                // the assigned-department card around it stays.
                var estBox=el.parentElement;
                if(estBox&&String(estBox.textContent||'').toUpperCase().indexOf('EST. RESOLUTION')>=0) estBox.style.display='none';
                else el.textContent='';
              }
              else if(t.indexOf('Awaiting Resolution')>=0){
                el.textContent='No resolution photo yet';
                var tile=el.parentElement; if(tile) tile.setAttribute('data-cc-afterphoto','1');
              }
            }

            // Labelled value chips. Compared lower-case because these labels carry Tailwind's
            // "uppercase" class and innerText returns the CSS-transformed text.
            var labels=document.querySelectorAll('span');
            for(var j=0;j<labels.length;j++){
              if(labels[j].children.length) continue;
              var key=txt(labels[j]).toLowerCase();
              if(key!=='category'&&key!=='severity'&&key!=='location'&&key!=='status') continue;
              var wrap=labels[j].parentElement;
              var holder=wrap?wrap.querySelector('div'):null;
              if(!holder) continue;
              if(key==='category') setTail(holder, r.category||'Not set');
              else if(key==='severity') setTail(holder, r.severity?cap(r.severity):'Not set');
              else if(key==='location') setTail(holder, locationText(r));
              else setTail(holder, statusLabel(r.status));
            }

            // Evidence: the report's own photo when this device holds it; otherwise say so plainly.
            var ev=document.querySelector('[data-cc-evidence]');
            if(ev){
              // The stored Cloudinary URL works on any device; the local JPEG is the same-device
              // fallback for a report whose upload did not succeed.
              if(showRemoteEvidence(ev, r.evidenceUrl)){ evidenceNote(ev,''); }
              else {
              var uri=(d&&d.found&&r.reportId&&r.hasLocalPhoto&&native&&native.getReportPhotoPreview)
                ? native.getReportPhotoPreview(r.reportId,false) : '';
              if(uri){
                ev.style.backgroundImage='url("'+uri+'")';
                ev.style.backgroundSize='cover';
                ev.style.backgroundPosition='center';
                evidenceNote(ev,'');
              } else {
                evidenceNote(ev, (d&&d.found) ? "Evidence photo unavailable." : 'Evidence unavailable.');
              }
              }
            }

            // Government resolution photo, when one has been uploaded.
            var after=document.querySelector('[data-cc-afterphoto]');
            if(after && !showRemoteEvidence(after, r.resolutionEvidenceUrl)){
              after.textContent='No resolution photo yet';
            }

            // Description paragraph, where the screen has one.
            var paras=document.querySelectorAll('p');
            for(var k=0;k<paras.length;k++){
              if(txt(paras[k]).indexOf('Large pothole')===0 || txt(paras[k]).indexOf('deep enough that cars')>=0){
                paras[k].textContent=r.description||'No description was provided.';
              }
            }

            // The inline mini-timeline was entirely invented ("AI Categorized", "Assigned to
            // Sanitation Dept", "Crew Dispatched"). Replace it with the real stored events.
            var track=document.querySelector('[data-cc-minitimeline]');
            if(track){
              track.innerHTML='';
              var events=(d&&d.timeline)||[];
              if(!d||!d.timelineLoaded){ track.appendChild(note('Status history unavailable.')); }
              else if(!events.length){ track.appendChild(note('No status events recorded yet.')); }
              else {
                for(var e=0;e<events.length;e++){
                  var row=document.createElement('div');
                  row.style.cssText='padding:8px 0;border-bottom:1px solid #dfe1e6';
                  var at=fmtDateTime(events[e].createdAt);
                  row.innerHTML='<div style="font-weight:600;color:#051a3e;font-size:14px">'+esc(statusLabel(events[e].status))+'</div>'
                    +'<div style="font-size:13px;color:#434654">'+esc(events[e].message||'')+'</div>'
                    +(at?'<div style="font-size:12px;color:#737685">'+esc(at)+'</div>':'');
                  track.appendChild(row);
                }
              }
            }
          }

          function setupReportDetails(){
            var rid=(native&&native.getActiveReportId)?native.getActiveReportId():'';
            // Tag the inline timeline container before any data arrives.
            var links=document.querySelectorAll('a');
            for(var i=0;i<links.length;i++){
              if((links[i].getAttribute('href')||'').indexOf('live_tracking_timeline')<0) continue;
              var holder=links[i].querySelector('div');
              if(holder){ holder.setAttribute('data-cc-minitimeline','1'); holder.innerHTML=''; holder.appendChild(note(rid?'Loading status history...':'No report selected.')); }
            }
            // Tag the vote counter so chipchapInteractions can write the real stored count here.
            var counters=document.querySelectorAll('span,div');
            for(var c=0;c<counters.length;c++){
              if(counters[c].children.length) continue;
              var ct=txt(counters[c]);
              if(/backed this|Backers|Neighbou?rs/i.test(ct)){
                counters[c].setAttribute('data-cc-votecount','1');
                counters[c].setAttribute('data-cc-votelabel','neighbour');
                counters[c].textContent='...';
                break;
              }
            }
            // "JD", "AK", "+10" are decorative Stitch avatars, not backers - hide the stack.
            document.querySelectorAll('main div').forEach(function(x){
              if(!x.children.length && /^(JD|AK|\\+10)$/.test(txt(x)) && x.parentElement) x.parentElement.style.display='none';
            });
            // The decorative AI sparkle on the department card implies an AI step that does not exist.
            document.querySelectorAll('main .material-symbols-outlined').forEach(function(icon){
              if(iconKey(icon)==='auto_awesome') icon.style.display='none';
            });

            // Location row, cloned from the Category block so it keeps the Stitch styling.
            if(!document.querySelector('[data-cc-locationrow]')){
              var catLabel=null;
              document.querySelectorAll('main span').forEach(function(x){ if(!x.children.length&&txt(x).toLowerCase()==='category') catLabel=x; });
              var catBlock=catLabel?catLabel.parentElement:null;
              var grid=catBlock?catBlock.parentElement:null;
              if(catBlock&&grid){
                var locRow=catBlock.cloneNode(true);
                locRow.setAttribute('data-cc-locationrow','1');
                locRow.style.gridColumn='1 / -1';
                var locLabel=locRow.querySelector('span'); if(locLabel) locLabel.textContent='LOCATION';
                var locHolder=locRow.querySelector('div');
                if(locHolder){
                  var locIcon=locHolder.querySelector('.material-symbols-outlined');
                  if(locIcon){ locIcon.dataset.ccIcon='location_on'; locIcon.setAttribute('data-icon','location_on'); locIcon.textContent='location_on'; }
                  setTail(locHolder, rid?'Loading...':'Not available');
                }
                grid.appendChild(locRow);
                if(window.ccIcons) window.ccIcons();
              }
            }

            // Evidence: remove the Stitch stock photograph before any data arrives.
            document.querySelectorAll('main section h3').forEach(function(hd){
              if(txt(hd)!=='Evidence') return;
              var photo=hd.parentElement?hd.parentElement.querySelector('[style*="background-image"]'):null;
              if(!photo) return;
              photo.setAttribute('data-cc-evidence','1');
              photo.removeAttribute('data-alt');
              photo.style.backgroundImage='none';
              photo.style.backgroundColor='#eef0f7';
              evidenceNote(photo, rid?'Loading evidence...':'No report selected.');
            });
            if(!rid){
              var main=document.querySelector('main');
              if(main) main.insertBefore(note('Open a report from Home, Community or My Reports to see its details.'), main.firstChild);
              return;
            }
            if(native&&native.loadReportDetail) native.loadReportDetail(rid);
          }

          function setupTracking(){
            var rid=(native&&native.getActiveReportId)?native.getActiveReportId():'';
            // The status card and timeline were invented Stitch copy ("Crew is on the way",
            // "Est. arrival: 15 mins", "Downtown Maintenance Crew"). Strip them before any render.
            var heads=document.querySelectorAll('h2');
            for(var i=0;i<heads.length;i++){
              if(txt(heads[i]).indexOf('Crew is on the way')>=0){
                heads[i].setAttribute('data-cc-trackstatusline','1');
                heads[i].textContent=rid?'Loading report...':'No report selected';
                var card=heads[i].parentElement;
                if(card){
                  var meta=card.querySelector('p');
                  if(meta){ meta.setAttribute('data-cc-trackmeta','1'); meta.textContent=''; }
                }
              }
            }
            // The location card was a stock photo of another city. It becomes a real preview of
            // this report's coordinates once the detail payload arrives.
            var track=document.querySelector('[data-location]');
            if(track){
              track.removeAttribute('data-location');
              track.removeAttribute('data-alt');
              track.setAttribute('data-cc-locmap','1');
              window.ccReportMiniMapPending(track);
            }
            // Remove the animated "service crew" marker and its path from the demo map.
            document.querySelectorAll('svg').forEach(function(x){ x.remove(); });
            document.querySelectorAll('.material-symbols-outlined').forEach(function(icon){
              var k=icon.dataset.ccIcon||'';
              var wrap=icon.parentElement;
              // The crew marker's grandparent is the marker itself. The car icon's grandparent is the
              // status card holding the real headline, so only the icon's own wrapper is removed.
              if(k==='local_shipping'){ if(wrap&&wrap.parentElement) wrap.parentElement.remove(); }
              else if(k==='directions_car'){ if(wrap) wrap.remove(); }
            });
            var sections=document.querySelectorAll('section');
            for(var k=0;k<sections.length;k++){
              var h=sections[k].querySelector('h3');
              if(!h||txt(h).indexOf('Status Timeline')<0) continue;
              var holder=h.nextElementSibling;
              if(!holder) continue;
              holder.setAttribute('data-cc-timeline','1');
              holder.innerHTML='';
              holder.appendChild(note(rid?'Loading status history...':'Open a report from My Reports to track it.'));
            }
            var buttons=document.querySelectorAll('button');
            for(var b=0;b<buttons.length;b++){
              if(txt(buttons[b]).toLowerCase().indexOf('contact support')>=0) buttons[b].setAttribute('data-cc-support','1');
            }
            if(rid&&native&&native.loadReportDetail) native.loadReportDetail(rid);
          }

          function supportDialog(){
            var back=document.createElement('div');
            back.setAttribute('data-cc-picker','1');
            back.style.cssText='position:fixed;inset:0;background:rgba(5,26,62,.45);z-index:9998;display:flex;align-items:flex-end;justify-content:center';
            back.innerHTML='<div style="width:100%;max-width:480px;background:#faf9ff;border-radius:20px 20px 0 0;padding:20px;color:#051a3e">'
              +'<h3 style="font-size:20px;font-weight:700;margin:0 0 4px">Government support</h3>'
              +'<p style="font-size:12px;color:#914d00;margin:0 0 14px">Demo contact details for this MVP - not a real government helpline.</p>'
              +'<button data-cc-supportmail="1" style="display:block;width:100%;text-align:left;background:#e9edff;border:0;border-radius:12px;padding:14px;margin-bottom:10px;font-weight:600;font-size:15px;color:#003d9b">Email<br><span style="font-weight:400;font-size:14px">support@chipchap.gov.in</span></button>'
              +'<button data-cc-supportcall="1" style="display:block;width:100%;text-align:left;background:#e9edff;border:0;border-radius:12px;padding:14px;margin-bottom:10px;font-weight:600;font-size:15px;color:#003d9b">Phone<br><span style="font-weight:400;font-size:14px">+91 98765 43210</span></button>'
              +'<button data-cc-supportclose="1" style="display:block;width:100%;background:#003d9b;color:#fff;border:0;border-radius:12px;padding:13px;font-weight:700">Close</button>'
              +'</div>';
            document.body.appendChild(back);
          }

          // ---------------- clicks ----------------
          window.addEventListener('click',function(e){
            var target=(e.target&&e.target.closest)?e.target.closest('a,button,article,[role=button],[data-cc-notif],[data-cc-afterpick]'):null;
            if(!target) return;

            if(target.getAttribute('data-cc-send')){
              e.preventDefault(); e.stopPropagation();
              var input=document.querySelector('[data-cc-commentinput]');
              var rid=(native&&native.getActiveReportId)?native.getActiveReportId():'';
              if(!rid){ toast('Open a report from the community feed to comment on it.'); return; }
              var value=input?input.value.trim():'';
              if(!value){ toast('Write a comment first'); return; }
              if(!native||!native.addReportComment){ toast('Commenting is unavailable in this build'); return; }
              native.addReportComment(rid,value);
              if(input) input.value='';
              return;
            }

            if(target.getAttribute('data-cc-copytrack')){
              e.preventDefault(); e.stopPropagation();
              var idEl=document.querySelector('[data-cc-trackid]');
              var value=idEl?txt(idEl).replace(/^#/,''):'';
              if(!value){ toast('There is no tracking ID to copy.'); return; }
              if(native&&native.copyToClipboard) native.copyToClipboard('Tracking ID', value);
              return;
            }
            if(target.getAttribute('data-cc-trackstatus')){
              e.preventDefault(); e.stopPropagation();
              var last=(native&&native.getLastReportId)?native.getLastReportId():'';
              if(!last){ toast('No submitted report to track yet.'); return; }
              if(native&&native.setActiveReport) native.setActiveReport(last);
              go('live_tracking_timeline');
              return;
            }
            if(target.getAttribute('data-cc-copyref')||target.getAttribute('data-cc-shareref')){
              e.preventDefault(); e.stopPropagation();
              var rid=(native&&native.getActiveReportId)?native.getActiveReportId():'';
              if(!rid){ toast('Open a stored report first.'); return; }
              if(target.getAttribute('data-cc-copyref')){
                if(native&&native.copyToClipboard) native.copyToClipboard('Report reference', rid);
              } else if(native&&native.shareText){
                var NL=String.fromCharCode(10);
                native.shareText('CHIPCHAP report',
                  'CHIPCHAP civic report'+NL+'Reference: '+rid+NL+NL+'Shared from the CHIPCHAP app.');
              }
              var back2=document.querySelector('.cc-modal-backdrop'); if(back2) back2.remove();
              return;
            }
            if(target.closest&&target.closest('[data-cc-afterpick]')){
              e.preventDefault(); e.stopPropagation();
              if(native&&native.governmentCaptureAfterPhoto) native.governmentCaptureAfterPhoto();
              return;
            }
            if(target.getAttribute('data-cc-support')){ e.preventDefault(); e.stopPropagation(); supportDialog(); return; }
            if(target.getAttribute('data-cc-supportmail')){ e.preventDefault(); e.stopPropagation();
              if(native&&native.openContact) native.openContact('email','support@chipchap.gov.in'); return; }
            if(target.getAttribute('data-cc-supportcall')){ e.preventDefault(); e.stopPropagation();
              if(native&&native.openContact) native.openContact('phone','+91 98765 43210'); return; }
            if(target.getAttribute('data-cc-supportclose')){ e.preventDefault(); e.stopPropagation();
              var pick=document.querySelector('[data-cc-picker]'); if(pick) pick.remove(); return; }
            if(target.getAttribute('data-cc-newreport')){ e.preventDefault(); e.stopPropagation(); go('capture_photo'); return; }

            // Map controls: the chips keep their designed selected state; nothing is plotted.
            if(target.getAttribute('data-cc-mapcardclose')){
              e.preventDefault(); e.stopPropagation();
              mapSelected=''; mapRefresh();
              return;
            }
            if(target.getAttribute('data-cc-mapcarddetails')){
              e.preventDefault(); e.stopPropagation();
              var picked=mapPoint(mapSelected);
              if(!picked){ toast('Tap a report on the map first.'); return; }
              if(native&&native.setActiveReport){
                native.setActiveReport(picked.reportId);
                go(ccMapMode==='GOVERNMENT'?'complaint_details_2':'complaint_details_1');
              }
              return;
            }
            var mapChip=target.getAttribute('data-cc-mapchip');
            if(mapChip){
              e.preventDefault(); e.stopPropagation();
              var chipsAll=document.querySelectorAll('[data-cc-mapchip]');
              chipsAll.forEach(function(c){ c.className=(c===target)?(window.__ccChipOn||c.className):(window.__ccChipOff||c.className); });
              mapFilter=mapChip;
              if(mapSelected&&!mapVisible(mapPoint(mapSelected))) mapSelected='';
              mapRefresh();
              mapSearchStatus();
              return;
            }
            if(routeHandleClick(target)){
              e.preventDefault(); e.stopPropagation();
              return;
            }
            if(target.getAttribute('data-cc-mylocation')){
              e.preventDefault(); e.stopPropagation();
              // Instant response: re-centre on the real position this screen already received,
              // then ask the device again (the answer never waits for the report markers).
              if(ccMapHere&&ccMap) mapCenterOnHere();
              else toast('Finding your location...');
              if(native&&native.requestMapLocation) native.requestMapLocation();
              return;
            }
            if(target.getAttribute('data-cc-mapfilters')){
              e.preventDefault(); e.stopPropagation();
              // Show / hide the existing category chip row. The active filter is left as it is.
              var firstChip=document.querySelector('[data-cc-mapchip]');
              var chipRow=firstChip?firstChip.parentElement:null;
              if(chipRow) chipRow.style.display=(chipRow.style.display==='none')?'':'none';
              return;
            }

            // Notifications: tapping marks read (in Firestore) and opens the related report.
            var nchip=target.getAttribute('data-cc-notifchip');
            if(nchip){ e.preventDefault(); e.stopPropagation(); notifFilter=nchip; renderNotifications(); return; }
            var nid=target.getAttribute('data-cc-notif');
            if(nid!==null&&nid!==undefined&&target.hasAttribute('data-cc-notif')){
              e.preventDefault(); e.stopPropagation();
              var item=null; (notifList||[]).forEach(function(n){ if(n.id===nid) item=n; });
              if(item&&!item.read){
                item.read=true;
                if(native&&native.markNotificationRead) native.markNotificationRead(nid);
                renderNotifications();
                updateUnreadDots((notifList||[]).filter(function(n){ return !n.read; }).length);
              }
              var rep=target.getAttribute('data-cc-notifreport');
              if(rep&&native&&native.setActiveReport){ native.setActiveReport(rep); if(native.openScreen) native.openScreen('complaint_details_1'); }
              return;
            }

            var card=target.closest('[data-cc-report]');
            if(card && !target.closest('[data-cc-votecount]') && target.tagName!=='BUTTON'){
              var rid2=card.getAttribute('data-cc-report');
              if(rid2 && native && native.setActiveReport){
                e.preventDefault(); e.stopPropagation();
                native.setActiveReport(rid2);
                if(native.openScreen) native.openScreen('complaint_details_1');
              }
            }
          },true);

          if(here('home_screen')) setupHome();
          if(here('community_feed')) setupCommunity();
          if(here('complaint_details_1')) setupDetailsView();
          if(here('language_selection')) setupLanguage();
          if(here('user_profile')) setupProfile();
          if(here('my_reports')) setupMyReports();
          if(here('notifications_1')) setupNotifications();
          if(here('map_explorer')) setupMap();
          if(here('details')) setupIncidentMap();
          // Real unread indicator on every citizen screen that has one (not on government screens).
          if(!/\\/(dashboard|complaint_queue|complaint_details_2|assign_complaint|update_status|analytics|notifications_2|admin_[a-z_]+)\\//.test(location.pathname)){
            tagUnreadDots();
            if(document.querySelector('[data-cc-unreaddot]')&&!here('notifications_1')&&native&&native.loadUnreadNotificationCount) native.loadUnreadNotificationCount();
          }
          if(here('success')) setupSuccess();
          if(here('live_tracking_timeline')) setupTracking();
        })();
        """);
        web.evaluateJavascript(js, null);
    }

    /**
     * Government screens: admin_login sign-in (Checkpoint A), and real Firestore data for the
     * dashboard, complaint queue, report details, analytics and profile. Features without a
     * backend in this build (status changes, assignment, notes, crews, broadcasts, alerts, live
     * map, performance reports) are shown as inactive with an honest message - never a fake
     * success. No Stitch asset is modified.
     */
    private void injectGovernmentScreens() {
        // One IIFE in two text blocks joined at run time (a Java string constant is limited to
        // 65,535 bytes). The joined text is exactly the script the two parts spell out, in order.
        String js = String.join("", """
        (function(){
          if(window.__ccGov){return;} window.__ccGov=true;
          var native=window.Android;
          function here(n){return location.pathname.indexOf('/'+n+'/')>=0;}
          var GOV=/\\/(dashboard|complaint_queue|complaint_details_2|assign_complaint|update_status|analytics|notifications_2|admin_profile|admin_splash_screen)\\//.test(location.pathname);
          function toast(t){ if(t&&window.ccNativeToast) window.ccNativeToast(t); }
          function stop(e){ e.preventDefault(); e.stopPropagation(); if(e.stopImmediatePropagation) e.stopImmediatePropagation(); }
          function txt(el){ return el?((el.textContent||'').replace(/\\s+/g,' ').trim()):''; }
          function label(el){
            if(!el) return '';
            var c=el.cloneNode(true);
            c.querySelectorAll('.material-symbols-outlined').forEach(function(x){ x.remove(); });
            return ((c.textContent||'').replace(/\\s+/g,' ').trim()).toLowerCase();
          }
          function iconKey(el){
            var icon=el?(el.classList&&el.classList.contains('material-symbols-outlined')?el:el.querySelector('.material-symbols-outlined')):null;
            return icon?String(icon.dataset.ccIcon||icon.dataset.icon||icon.textContent||'').trim():'';
          }
          function esc(v){ return String(v==null?'':v).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/"/g,'&quot;'); }
          function cap(v){ v=String(v||''); return v?v.charAt(0).toUpperCase()+v.slice(1):''; }
          /**
           * Paints a remote HTTPS Cloudinary image into an existing Stitch tile.
           * Each inject*() payload is its own IIFE with its own scope, so this government
           * block cannot reach the identically named helper in injectCitizenScreens.
           */
          function showRemoteEvidence(el,url){
            if(!el||!url||url.indexOf('https://res.cloudinary.com/')!==0) return false;
            el.textContent='';
            el.style.backgroundImage='url("'+url+'")';
            el.style.backgroundSize='cover';
            el.style.backgroundPosition='center';
            el.style.minHeight=el.style.minHeight||'160px';
            return true;
          }
          function setTail(el,value){
            if(!el) return;
            for(var i=el.childNodes.length-1;i>=0;i--){
              var n=el.childNodes[i];
              if(n.nodeType===3 && n.nodeValue.trim()!==''){ n.nodeValue=' '+value; return; }
            }
            el.appendChild(document.createTextNode(' '+value));
          }
          function setText(el,value){ if(!el) return; if(el.children.length) setTail(el,value); else el.textContent=value; }
          function note(text){
            var p=document.createElement('p');
            p.setAttribute('data-cc-govnote','1');
            p.style.cssText='font-size:14px;line-height:1.45;color:#434654;padding:12px 2px';
            p.textContent=text;
            return p;
          }
          function inactive(el){ if(!el) return; el.setAttribute('data-cc-inactive','1'); el.setAttribute('aria-disabled','true'); el.style.opacity='0.5'; }

          // ---------------- shared formatting (same wording as the citizen screens) ----------------
          var STATUS_LABELS={SUBMITTED:'Submitted',UNDER_REVIEW:'Under review',ASSIGNED:'Assigned',IN_PROGRESS:'In progress',RESOLVED:'Resolved'};
          function statusKey(s){ return String(s||'').trim().toUpperCase(); }
          function statusLabel(s){ var k=statusKey(s); if(!k) return 'Submitted'; return STATUS_LABELS[k]||cap(k.toLowerCase().replace(/_/g,' ')); }
          var SEVERITY_RANK={critical:4,high:3,medium:2,low:1};
          function sevKey(s){ return String(s||'').trim().toLowerCase(); }
          function sevLabel(s){ var k=sevKey(s); return k?cap(k):'Not set'; }
          function fmtDate(ms){
            if(!ms) return 'Date unavailable';
            try{ return new Date(ms).toLocaleDateString('en-IN',{day:'numeric',month:'short',year:'numeric'}); }catch(err){ return new Date(ms).toDateString(); }
          }
          function fmtDateTime(ms){
            if(!ms) return '';
            try{ return new Date(ms).toLocaleString('en-IN',{day:'numeric',month:'short',year:'numeric',hour:'numeric',minute:'2-digit'}); }catch(err){ return new Date(ms).toString(); }
          }
          function relTime(ms){
            if(!ms) return 'Date unavailable';
            var s=Math.max(0,Math.round((Date.now()-ms)/1000));
            if(s<60) return 'Just now';
            var m=Math.round(s/60); if(m<60) return m+'m ago';
            var h=Math.round(m/60); if(h<24) return h+'h ago';
            var d=Math.round(h/24); if(d<7) return d+'d ago';
            return fmtDate(ms);
          }
          function locationText(r){
            var a=String((r&&r.address)||'').trim();
            if(a) return a;
            if(r&&typeof r.latitude==='number'&&typeof r.longitude==='number') return r.latitude.toFixed(5)+', '+r.longitude.toFixed(5);
            return 'Location unavailable';
          }
          function initials(name){
            var parts=String(name||'').trim().split(/\\s+/).filter(Boolean);
            if(!parts.length) return '?';
            return (parts[0].charAt(0)+(parts.length>1?parts[parts.length-1].charAt(0):'')).toUpperCase();
          }
          function openReport(id){
            if(!id||!native) return;
            if(native.setActiveReport) native.setActiveReport(id);
            if(native.openScreen) native.openScreen('complaint_details_2');
          }

          // Bottom sheet in the same style as the citizen pickers.
          function sheet(title,bodyHtml,buttons){
            var old=document.querySelector('[data-cc-govsheet]'); if(old) old.remove();
            var back=document.createElement('div');
            back.setAttribute('data-cc-govsheet','1');
            back.style.cssText='position:fixed;inset:0;background:rgba(5,26,62,.45);z-index:9998;display:flex;align-items:flex-end;justify-content:center';
            var html='<div style="width:100%;max-width:480px;max-height:80vh;overflow:auto;background:#faf9ff;border-radius:20px 20px 0 0;padding:20px;color:#051a3e">'
              +'<h3 style="font-size:20px;font-weight:700;margin:0 0 10px">'+esc(title)+'</h3>'
              +'<div style="font-size:14px;line-height:1.5;color:#434654">'+bodyHtml+'</div>';
            (buttons||[]).forEach(function(b){
              html+='<button data-cc-sheetbtn="'+esc(b.id)+'" style="display:block;width:100%;margin-top:10px;border:0;border-radius:12px;padding:13px;font-weight:700;font-size:15px;'
                +(b.primary?'background:#003d9b;color:#fff':'background:#e9edff;color:#003d9b')+'">'+esc(b.label)+'</button>';
            });
            html+='<button data-cc-sheetbtn="close" style="display:block;width:100%;margin-top:10px;border:0;border-radius:12px;padding:13px;font-weight:700;font-size:15px;background:#e9edff;color:#003d9b">Close</button></div>';
            back.innerHTML=html;
            back.addEventListener('click',function(e){ if(e.target===back) back.remove(); });
            document.body.appendChild(back);
            return back;
          }
          var sheetActions={};

          // ---------------- admin_login (Checkpoint A, unchanged behaviour) ----------------
          function govLogin(){
            var em=document.getElementById('admin-id'), pw=document.getElementById('password');
            var email=em?em.value.trim():'', password=pw?pw.value:'';
            if(!/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(email)){ if(em) em.focus(); toast('Enter your government email address.'); return; }
            if(!password){ if(pw) pw.focus(); toast('Enter your password.'); return; }
            if(!native||!native.governmentLogin){ toast('Government sign-in is unavailable in this build.'); return; }
            toast('Verifying government access...');
            native.governmentLogin(email,password);
          }
          if(here('admin_login')){
            var form=document.querySelector('form');
            if(form){
              form.removeAttribute('action');
              form.addEventListener('submit',function(e){ stop(e); govLogin(); },true);
            }
            var idField=document.getElementById('admin-id');
            if(idField){ idField.setAttribute('type','email'); idField.setAttribute('autocomplete','email'); }
          }

          // ---------------- every government screen: real identity in the header ----------------
          var govProfile=null;
          function setupHeaderAvatar(){
            document.querySelectorAll('header img').forEach(function(img){
              var av=document.createElement('div');
              av.setAttribute('data-cc-govavatar','1');
              av.style.cssText='width:100%;height:100%;display:flex;align-items:center;justify-content:center;background:#e9edff;color:#003d9b;font-weight:700;font-size:13px';
              av.textContent='';
              img.replaceWith(av);
            });
          }
          window.chipchapGovProfile=function(json){
            var p={}; try{p=JSON.parse(json)}catch(err){}
            govProfile=p;
            document.querySelectorAll('[data-cc-govavatar]').forEach(function(a){ a.textContent=initials(p.name||p.email||''); });
            var g=document.querySelector('[data-cc-govgreeting]');
            if(g){
              var hour=new Date().getHours();
              var part=hour<12?'Good morning':(hour<18?'Good afternoon':'Good evening');
              g.textContent=p.name?(part+', '+p.name+'.'):(part+'.');
            }
            var n=document.querySelector('[data-cc-govname]'); if(n) n.textContent=p.name||'Government account';
            var m=document.querySelector('[data-cc-govemail]'); if(m) m.textContent=p.email||'';
            if(lastReports) renderProfileStats(lastReports);
          };

          // ---------------- reports: one load, many screens ----------------
          var lastReports=null;
          window.chipchapGovReports=function(json){
            var d={}; try{d=JSON.parse(json)}catch(err){}
            var list=d.loaded?(d.reports||[]):null;
            lastReports=list;
            if(here('dashboard')) renderDashboard(list,d);
            if(here('complaint_queue')) renderQueue(list,d);
            if(here('analytics')) renderAnalytics(list,d);
            if(here('admin_profile')) renderProfileStats(list,d);
          };
          function counts(list){
            var c={total:0,SUBMITTED:0,UNDER_REVIEW:0,ASSIGNED:0,IN_PROGRESS:0,RESOLVED:0,other:0,resolvedWeek:0};
            var weekAgo=Date.now()-7*24*3600*1000;
            (list||[]).forEach(function(r){
              c.total++;
              var k=statusKey(r.status)||'SUBMITTED';
              if(c.hasOwnProperty(k)&&k!=='total'&&k!=='other'&&k!=='resolvedWeek') c[k]++; else c.other++;
              // updatedAt is written by every status change; for a RESOLVED report it is the resolution time.
              if(k==='RESOLVED' && r.updatedAt && r.updatedAt>=weekAgo) c.resolvedWeek++;
            });
            return c;
          }
          function capNote(list,d){
            return (list&&d&&d.limit&&list.length>=d.limit)?(' Showing the '+d.limit+' most recent reports.'):'';
          }

          // ---------------- dashboard ----------------
          function metricNumber(labelText){
            var lab=null;
            document.querySelectorAll('main section span').forEach(function(s){ if(!s.children.length&&txt(s).toLowerCase()===labelText) lab=s; });
            if(!lab) return null;
            var tile=lab.closest('.glass-card');
            if(!tile) return null;
            var nums=tile.querySelectorAll('.font-headline-lg,.font-headline-sm');
            for(var i=0;i<nums.length;i++){ if(nums[i]!==lab && !nums[i].children.length) return nums[i]; }
            return null;
          }
          function setupDashboard(){
            document.querySelectorAll('main h2').forEach(function(h){
              if(txt(h).indexOf('Good morning')===0){ h.setAttribute('data-cc-govgreeting','1'); h.textContent='Good morning.'; }
            });
            [['total','total'],['pending','pending'],['in progress','inprogress'],['resolved this week','resolvedweek']].forEach(function(pair){
              var n=metricNumber(pair[0]); if(n){ n.setAttribute('data-cc-metric',pair[1]); n.textContent='...'; }
            });
            document.querySelectorAll('main span').forEach(function(s){
              if(!s.children.length && /since yesterday/.test(txt(s))){ s.setAttribute('data-cc-metricnote','inprogress'); s.textContent=''; }
            });
            var bar=document.querySelector('main [class*="w-[45%]"]');
            if(bar){ bar.setAttribute('data-cc-metricbar','inprogress'); bar.style.width='0%'; }
            // "8%" chip beside Resolved This Week: replaced by the real all-time resolved count.
            document.querySelectorAll('main .material-symbols-outlined').forEach(function(icon){
              if(iconKey(icon)==='trending_up'){ var chip=icon.parentElement; icon.remove(); if(chip){ chip.setAttribute('data-cc-metricnote','resolvedtotal'); chip.textContent='...'; } }
            });
            // Status breakdown line under the metric grid (real counts for every workflow status).
            var grid=document.querySelector('main section.grid');
            if(grid && !document.querySelector('[data-cc-statusline]')){
              var line=document.createElement('p');
              line.setAttribute('data-cc-statusline','1');
              line.className='font-body-sm text-body-sm text-on-surface-variant';
              line.style.marginTop='-4px';
              line.textContent='Loading report counts...';
              grid.parentElement.insertBefore(line, grid.nextSibling);
            }
            // Action Required: remove the invented AI card; the standard alert card becomes the template.
            var section=null;
            document.querySelectorAll('main section h3').forEach(function(h){ if(txt(h).indexOf('Action Required')===0) section=h.parentElement; });
            if(section){
              var tpl=null;
              section.querySelectorAll('h4').forEach(function(h){ if(txt(h)==='Traffic Light Outage') tpl=h.closest('.glass-card'); });
              var host=document.createElement('div');
              host.setAttribute('data-cc-actionlist','1');
              host.style.cssText='display:flex;flex-direction:column;gap:12px';
              host.__ccTemplate=tpl?tpl.cloneNode(true):null;
              Array.prototype.slice.call(section.children).forEach(function(ch){ if(ch.tagName!=='H3') ch.remove(); });
              host.appendChild(note('Loading reports...'));
              section.appendChild(host);
            }
            // Quick actions (Live Map and Reports only): Live Map opens the real report map.
            document.querySelectorAll('main section button').forEach(function(b){
              if(label(b)==='live map') b.setAttribute('data-cc-govgo','map_explorer');
            });
          }
          function renderDashboard(list,d){
            var set=function(k,v){ var el=document.querySelector('[data-cc-metric="'+k+'"]'); if(el) el.textContent=v; };
            if(!list){
              ['total','pending','inprogress','resolvedweek'].forEach(function(k){ set(k,'--'); });
              var sl=document.querySelector('[data-cc-statusline]'); if(sl) sl.textContent='Report counts could not be loaded. '+(d.error||'');
              var h=document.querySelector('[data-cc-actionlist]'); if(h){ h.innerHTML=''; h.appendChild(note('Reports could not be loaded. '+(d.error||''))); }
              var rt=document.querySelector('[data-cc-metricnote="resolvedtotal"]'); if(rt) rt.textContent='';
              return;
            }
            var c=counts(list);
            set('total',String(c.total));
            set('pending',String(c.SUBMITTED+c.UNDER_REVIEW+c.ASSIGNED));
            set('inprogress',String(c.IN_PROGRESS));
            set('resolvedweek',String(c.resolvedWeek));
            var rt2=document.querySelector('[data-cc-metricnote="resolvedtotal"]'); if(rt2) rt2.textContent=c.RESOLVED+' resolved in total';
            var bar=document.querySelector('[data-cc-metricbar="inprogress"]');
            if(bar) bar.style.width=(c.total?Math.round(c.IN_PROGRESS*100/c.total):0)+'%';
            var ip=document.querySelector('[data-cc-metricnote="inprogress"]');
            if(ip) ip.textContent=c.total?(Math.round(c.IN_PROGRESS*100/c.total)+'% of all reports'):'';
            var line=document.querySelector('[data-cc-statusline]');
            if(line) line.textContent=c.total
              ? ('Submitted '+c.SUBMITTED+' · Under review '+c.UNDER_REVIEW+' · Assigned '+c.ASSIGNED+' · In progress '+c.IN_PROGRESS+' · Resolved '+c.RESOLVED+'. Pending = submitted + under review + assigned.'+capNote(list,d))
              : 'No reports have been submitted yet.';
            var host=document.querySelector('[data-cc-actionlist]');
            if(!host) return;
            host.innerHTML='';
            var open=list.filter(function(r){ return statusKey(r.status)!=='RESOLVED'; });
            open.sort(function(a,b){
              var s=(SEVERITY_RANK[sevKey(b.severity)]||0)-(SEVERITY_RANK[sevKey(a.severity)]||0);
              return s!==0?s:((b.createdAt||0)-(a.createdAt||0));
            });
            if(!open.length){ host.appendChild(note(list.length?'No open reports need action right now.':'No reports have been submitted yet.')); return; }
            open.slice(0,3).forEach(function(r){ host.appendChild(actionCard(host.__ccTemplate,r)); });
          }
          function actionCard(tpl,r){
            var el=tpl?tpl.cloneNode(true):document.createElement('div');
            if(!tpl) el.innerHTML='<span></span><span></span><h4></h4><p></p>';
            el.setAttribute('data-cc-govreport', r.reportId||'');
            el.style.cursor='pointer';
            el.querySelectorAll('span').forEach(function(s){
              if(s.classList.contains('material-symbols-outlined')||s.children.length) return;
              var t=txt(s);
              if(t==='HIGH PRIORITY') s.textContent=(r.severity?sevLabel(r.severity).toUpperCase()+' SEVERITY':'SEVERITY NOT SET')+' · '+statusLabel(r.status).toUpperCase();
              else if(t==='10m ago') s.textContent=relTime(r.createdAt);
            });
            var h=el.querySelector('h4'); if(h) h.textContent=r.title||'Untitled report';
            var p=el.querySelector('p'); if(p) p.textContent=locationText(r)+(r.category?(' · '+r.category):'');
            return el;
          }

          // ---------------- complaint queue ----------------
          var queueFilter={kind:'chip',value:'all'}, queueQuery='';
          var CHIP_ACTIVE='', CHIP_IDLE='';
          function setupQueue(){
            var first=null;
            document.querySelectorAll('main h3').forEach(function(h){ if(txt(h)==='Severe Pothole on Main St.') first=h.parentElement&&h.parentElement.parentElement; });
            var list=first?first.parentElement:null;
            if(list){
              list.setAttribute('data-cc-queue','1');
              list.__ccTemplate=first.cloneNode(true);
              list.innerHTML='';
              list.appendChild(note('Loading reports...'));
            }
            var chips=[];
            document.querySelectorAll('main button').forEach(function(b){
              var l=label(b);
              if(l==='all reports'||l==='high priority'||l==='pending'||l==='assigned'){ b.setAttribute('data-cc-queuechip',l); chips.push(b); }
            });
            if(chips.length){ CHIP_ACTIVE=chips[0].className; CHIP_IDLE=(chips[1]||chips[0]).className; }
            var search=document.querySelector('main input[placeholder^="Search"]');
            if(search){
              search.setAttribute('data-cc-queuesearch','1');
              search.value='';
              search.addEventListener('input',function(){ queueQuery=(search.value||'').trim().toLowerCase(); renderQueue(lastReports,{}); });
            }
            // No floating "+": reports are created only by citizens through the citizen report flow.
            document.querySelectorAll('button').forEach(function(b){ if(iconKey(b)==='add'&&label(b)==='') b.remove(); });
          }
          function paintQueueChips(){
            document.querySelectorAll('[data-cc-queuechip]').forEach(function(b){
              var on=queueFilter.kind==='chip'&&b.getAttribute('data-cc-queuechip')===({all:'all reports',high:'high priority',pending:'pending',assigned:'assigned'})[queueFilter.value];
              if(CHIP_ACTIVE) b.className=on?CHIP_ACTIVE:CHIP_IDLE;
            });
          }
          function queueMatch(r){
            var k=statusKey(r.status)||'SUBMITTED', sv=sevKey(r.severity);
            if(queueFilter.kind==='chip'){
              if(queueFilter.value==='high'&&!(sv==='high'||sv==='critical')) return false;
              if(queueFilter.value==='pending'&&!(k==='SUBMITTED'||k==='UNDER_REVIEW')) return false;
              if(queueFilter.value==='assigned'&&k!=='ASSIGNED') return false;
            } else if(queueFilter.kind==='status' && k!==queueFilter.value) return false;
            if(queueQuery){
              var hay=[r.reportId,r.title,r.description,r.address,r.category,r.citizenName,statusLabel(r.status),r.severity].join(' ').toLowerCase();
              if(hay.indexOf(queueQuery)<0) return false;
            }
            return true;
          }
          var SEV_BORDER={critical:'#ba1a1a',high:'#de350b',medium:'#914d00',low:'#8993a4'};
          function queueCard(tpl,r){
            var el=tpl.cloneNode(true);
            el.setAttribute('data-cc-govreport', r.reportId||'');
            el.style.cursor='pointer';
            el.style.borderLeftColor=SEV_BORDER[sevKey(r.severity)]||'#c3c6d6';
            var chips=el.querySelectorAll('.flex-wrap span');
            if(chips[0]) chips[0].textContent=r.severity?sevLabel(r.severity):'Severity not set';
            if(chips[1]){
              chips[1].textContent=statusLabel(r.status);
              if(r.category){ var cat=chips[1].cloneNode(true); cat.textContent=r.category; chips[1].parentElement.appendChild(cat); }
            }
            el.querySelectorAll('span').forEach(function(s){ if(!s.children.length && /ago$/.test(txt(s))) s.textContent=relTime(r.createdAt); });
            var h=el.querySelector('h3'); if(h) h.textContent=r.title||'Untitled report';
            var p=el.querySelector('p'); if(p) p.textContent=r.description||'No description provided.';
            var loc=null;
            el.querySelectorAll('.material-symbols-outlined').forEach(function(i){ if(iconKey(i)==='location_on') loc=i.nextElementSibling; });
            if(loc) loc.textContent=locationText(r);
            var meta=document.createElement('p');
            meta.className='font-label-sm text-label-sm text-outline';
            meta.textContent='#'+(r.reportId||'')+' · Reported by '+(r.citizenName||'a citizen')+' · '+fmtDate(r.createdAt);
            el.appendChild(meta);
            return el;
          }
          function renderQueue(list,d){
            var host=document.querySelector('[data-cc-queue]');
            if(!host) return;
            paintQueueChips();
            host.innerHTML='';
            if(!list){ host.appendChild(note('Reports could not be loaded. '+((d&&d.error)||''))); return; }
            if(!list.length){ host.appendChild(note('No reports have been submitted yet.')); return; }
            var shown=list.filter(queueMatch);
            if(!shown.length){ host.appendChild(note('No reports match this filter.')); return; }
            shown.forEach(function(r){ host.appendChild(queueCard(host.__ccTemplate,r)); });
            if(d&&d.limit&&list.length>=d.limit) host.appendChild(note('Showing the '+d.limit+' most recent reports.'));
          }
          function statusFilterSheet(){
            var opts=[['all','All statuses']].concat(Object.keys(STATUS_LABELS).map(function(k){ return [k,STATUS_LABELS[k]]; }));
            var buttons=opts.map(function(o){ return {id:'status:'+o[0],label:o[1],primary:(queueFilter.kind==='status'?queueFilter.value:'all')===o[0]}; });
            sheet('Filter by status','<p style="margin:0">Shows reports with the selected status.</p>',buttons);
          }

          // ---------------- complaint_details_2 ----------------
          // Navigate to Report: in-app route preview on the CHIPCHAP map (Checkpoint F1). The
          // button stays inactive until real, valid coordinates have arrived.
          function navCoordsValid(la,lo){
            return typeof la==='number'&&typeof lo==='number'&&isFinite(la)&&isFinite(lo)
              &&la>=-90&&la<=90&&lo>=-180&&lo<=180&&!(la===0&&lo===0);
          }
          function setGovNav(report){
            var b=document.querySelector('[data-cc-govnav]'); if(!b) return;
            var ok=!!report&&navCoordsValid(report.latitude,report.longitude);
            if(ok){
              b.setAttribute('data-cc-navstate','ready');
              b.setAttribute('data-cc-navlat',String(report.latitude));
              b.setAttribute('data-cc-navlng',String(report.longitude));
              b.removeAttribute('data-cc-inactive'); b.removeAttribute('aria-disabled'); b.style.opacity='1';
            } else {
              b.setAttribute('data-cc-navstate','none');
              b.removeAttribute('data-cc-navlat'); b.removeAttribute('data-cc-navlng');
              inactive(b);
            }
          }
          function govNavigate(btn){
            var st=btn.getAttribute('data-cc-navstate');
            if(st==='loading'){ toast('The report is still loading.'); return; }
            var la=parseFloat(btn.getAttribute('data-cc-navlat')), lo=parseFloat(btn.getAttribute('data-cc-navlng'));
            if(st!=='ready'||!navCoordsValid(la,lo)){ toast('Navigation is unavailable because this report has no valid location.'); return; }
            var rid=(native&&native.getActiveReportId)?native.getActiveReportId():'';
            if(!rid){ toast('Open a report before requesting a route.'); return; }
            // Only the id is sent: native code reads the report's STORED coordinates itself.
            if(native&&native.requestReportRoute) native.requestReportRoute(rid);
            else toast('This build cannot calculate routes.');
          }
          function setupGovDetails(){
            var rid=(native&&native.getActiveReportId)?native.getActiveReportId():'';
            var main=document.querySelector('main'); if(main&&rid) main.setAttribute('data-cc-govdetail',rid);
            var map={};
            document.querySelectorAll('main h2,main p,main span,main h3').forEach(function(el){
              if(el.classList.contains('material-symbols-outlined')) return;
              var t=txt(el);
              if(el.tagName==='H2' && t==='Pothole on Main St.') map.title=el;
              else if(t==='In Progress' && !el.children.length) map.status=el;
              else if(t.indexOf('Ticket ID:')===0) map.ticket=el;
              else if(t.indexOf('Reported 2 hours ago')>=0 && el.tagName==='P') map.reported=el;
              else if(t==='AI ANALYSIS') map.aiLabel=el;
              else if(t==='High Severity Structural Damage') map.aiTitle=el;
              else if(t.indexOf('Visual analysis indicates')===0) map.aiText=el;
              else if(t.indexOf('Category:')===0) map.cat=el;
              else if(t.indexOf('Priority:')===0) map.sev=el;
              else if(t.indexOf('Est. Repair Time')===0) map.est=el;
              else if(t.indexOf('There is a massive pothole')===0) map.desc=el;
              else if(t==='1200 Block, Main St.') map.addr=el;
              else if(t==='Near 4th Ave Intersection') map.coords=el;
              else if(t==='Jane Doe') map.reporter=el;
              else if(t.indexOf('Verified Resident')>=0 && el.tagName==='P') map.verified=el;
              else if(t.indexOf('j.doe@example.com')>=0 && el.tagName==='P') map.contact=el.parentElement;
              else if(t==='Dept. of Public Works') map.dept=el;
              else if(t==='Road Maintenance Crew B') map.crew=el;
            });
            document.querySelectorAll('main div').forEach(function(x){ if(!x.children.length && txt(x)==='JD') map.initials=x; });
            // Scrub every Stitch demo value before any data arrives.
            var loading=rid?'Loading...':'No report selected';
            if(map.title) map.title.textContent=rid?'Loading report...':'No report selected';
            if(map.status) map.status.textContent='...';
            if(map.ticket) map.ticket.textContent=rid?('Report ID: #'+rid):'Open a report from the Complaint Queue.';
            if(map.reported) setTail(map.reported, loading);
            if(map.aiLabel){
              map.aiLabel.textContent='REPORT DETAILS';
              var ic=map.aiLabel.parentElement?map.aiLabel.parentElement.querySelector('.material-symbols-outlined'):null;
              if(ic){ ic.dataset.ccIcon='info'; ic.textContent='info'; }
            }
            if(map.aiTitle) map.aiTitle.textContent='Submitted by the citizen';
            if(map.aiText) map.aiText.textContent='Category and severity were chosen by the citizen when the report was filed. No automated analysis is used.';
            if(map.cat) map.cat.textContent='Category: '+loading;
            if(map.sev) map.sev.textContent='Severity: '+loading;
            if(map.est) map.est.textContent='Status: '+loading;
            if(map.desc) map.desc.textContent=loading;
            if(map.addr) map.addr.textContent=loading;
            if(map.coords) map.coords.textContent='';
            if(map.reporter) map.reporter.textContent=loading;
            if(map.initials) map.initials.textContent='';
            if(map.verified) map.verified.style.display='none';
            if(map.contact) map.contact.style.display='none';
            if(map.dept) map.dept.textContent=loading;
            if(map.crew) map.crew.style.display='none';
            // Evidence: the photo stays on the citizen's phone in this build.
            var hero=document.querySelector('main img');
            if(hero){
              var box=hero.parentElement;
              var ph=document.createElement('div');
              ph.setAttribute('data-cc-govevidence','1');
              ph.style.cssText='width:100%;height:160px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;text-align:center;padding:16px;background:#eef0f7;color:#434654;font-size:14px;line-height:1.4';
              ph.innerHTML='<span class="material-symbols-outlined" data-cc-icon="photo_camera" style="font-size:28px;color:#737685">photo_camera</span>'
                +'<span data-cc-govevidencenote>Loading evidence...</span>';
              hero.replaceWith(ph);
              if(box) box.querySelectorAll('button').forEach(function(b){ if(iconKey(b)==='fullscreen') b.remove(); });
            }
            // The location map was a stock picture of somewhere else. It now becomes a real
            // preview of this report's own coordinates, drawn from the detail payload.
            document.querySelectorAll('main [style*="background-image"]').forEach(function(bg){
              bg.removeAttribute('data-alt');
              bg.setAttribute('data-cc-locmap','1');
              if(window.ccReportMiniMapPending) window.ccReportMiniMapPending(bg);
            });
            // Timeline: invented entries removed now, real ones rendered when they arrive.
            var tl=null;
            document.querySelectorAll('main h3').forEach(function(h){ if(txt(h)==='Activity Timeline') tl=h.nextElementSibling; });
            if(tl){
              tl.setAttribute('data-cc-govtimeline','1');
              var entries=Array.prototype.slice.call(tl.children);
              tl.__ccTemplate=entries.length?entries[0].cloneNode(true):null;
              tl.__ccOlder=entries.length>1?entries[1].cloneNode(true):null;
              tl.innerHTML='';
              tl.appendChild(note(rid?'Loading status history...':'No report selected.'));
            }
            // Update Status and department assignment open their own screens. (Add Note was removed.)
            document.querySelectorAll('button').forEach(function(b){
              var l=label(b);
              if(l==='update status') b.setAttribute('data-cc-govgo','update_status');
              else if(l==='reassign') b.setAttribute('data-cc-govgo','assign_complaint');
            });
            // Navigate to Report lives in the existing Location card, under the address/coordinates.
            if(map.coords&&map.coords.parentElement&&!document.querySelector('[data-cc-govnav]')){
              var nav=document.createElement('button');
              nav.type='button';
              nav.setAttribute('data-cc-govnav','1');
              nav.setAttribute('data-cc-navstate','loading');
              nav.className='mt-sm px-4 py-2 rounded-lg border border-primary bg-surface text-primary font-label-md text-label-md uppercase tracking-wider inline-flex items-center gap-2 hover:bg-surface-container-low transition-colors duration-200';
              nav.innerHTML='<span class="material-symbols-outlined text-[18px]" data-cc-icon="navigation">navigation</span> Navigate to Report';
              map.coords.parentElement.appendChild(nav);
              inactive(nav);
            }
            document.__ccGovMap=map;
            if(rid&&native&&native.loadReportDetail) native.loadReportDetail(rid);
          }
          function bindGovDetails(d){
            // Government sees the citizen's evidence through the stored HTTPS Cloudinary URL.
            // It never receives, and never needs, the citizen's local filesystem path.
            try{
              var ev=(d&&d.report)||{};
              document.querySelectorAll('[data-cc-govevidence]').forEach(function(tile){
                if(showRemoteEvidence(tile, ev.evidenceUrl)) return;
                var note=tile.querySelector('[data-cc-govevidencenote]');
                if(note) note.textContent='Evidence photo unavailable';
                else tile.textContent='Evidence photo unavailable';
              });
            }catch(evErr){
              // Evidence is secondary: the report's own details must still bind.
              document.querySelectorAll('[data-cc-govevidence]').forEach(function(tile){
                tile.textContent='Evidence photo unavailable';
              });
            }
            // Location preview, same rule: it may never stop the report details from binding.
            try{
              var rLoc=(d&&d.report)||{};
              document.querySelectorAll('[data-cc-locmap]').forEach(function(el){
                if(window.ccReportMiniMap) window.ccReportMiniMap(el, rLoc.latitude, rLoc.longitude);
              });
            }catch(locErr){}
            // Resolution photo saved by the status workflow (reports.resolutionEvidenceUrl). Shown
            // under the evidence photo only when one exists; never a placeholder or a local path.
            try{
              var oldRes=document.querySelector('[data-cc-govresolution]'); if(oldRes) oldRes.remove();
              var resUrl=String(((d&&d.report)||{}).resolutionEvidenceUrl||'');
              var evTile=document.querySelector('[data-cc-govevidence]');
              var hero=evTile?evTile.parentElement:null;
              if(resUrl.indexOf('https://res.cloudinary.com/')===0&&hero&&hero.parentElement){
                var resWrap=document.createElement('div');
                resWrap.setAttribute('data-cc-govresolution','1');
                resWrap.style.cssText='margin:0 0 24px';
                var resLabel=document.createElement('p');
                resLabel.textContent='Resolution photo';
                resLabel.style.cssText='margin:0 0 8px;font-size:12px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:#434654';
                var resTile=document.createElement('div');
                resTile.style.cssText='width:100%;height:200px;border-radius:12px;background-color:#eef0f7';
                resWrap.appendChild(resLabel); resWrap.appendChild(resTile);
                hero.parentElement.insertBefore(resWrap, hero.nextSibling);
                showRemoteEvidence(resTile,resUrl);
              }
            }catch(resErr){}
            var map=document.__ccGovMap||{};
            var r=(d&&d.report)||{};
            if(!d||!d.found){
              setGovNav(null);
              if(map.title) map.title.textContent='Report not found';
              if(map.ticket) map.ticket.textContent=(d&&d.error)||'This report could not be loaded.';
              var tl0=document.querySelector('[data-cc-govtimeline]'); if(tl0){ tl0.innerHTML=''; tl0.appendChild(note((d&&d.error)||'No history available.')); }
              return;
            }
            if(map.title) map.title.textContent=r.title||'Untitled report';
            if(map.status) map.status.textContent=statusLabel(r.status);
            if(map.ticket) map.ticket.textContent='Report ID: #'+(r.reportId||'');
            if(map.reported) setTail(map.reported, 'Reported '+(r.createdAt?fmtDateTime(r.createdAt):'at an unknown time')+(r.updatedAt?(' · Updated '+fmtDateTime(r.updatedAt)):''));
            if(map.cat) map.cat.textContent='Category: '+(r.category||'Not set');
            if(map.sev) map.sev.textContent='Severity: '+sevLabel(r.severity);
            if(map.est) map.est.textContent='Status: '+statusLabel(r.status);
            if(map.desc) map.desc.textContent=r.description||'No description was provided.';
            if(map.addr) map.addr.textContent=String(r.address||'').trim()||'Address not captured';
            if(map.coords) map.coords.textContent=(typeof r.latitude==='number'&&typeof r.longitude==='number')
              ? ('Coordinates: '+r.latitude.toFixed(5)+', '+r.longitude.toFixed(5)) : 'Coordinates not captured';
            setGovNav(r);
            if(map.reporter) map.reporter.textContent=r.citizenName||'Citizen';
            if(map.initials) map.initials.textContent=initials(r.citizenName||'');
            if(map.dept) map.dept.textContent=String(r.assignedGovernmentName||'').trim()||'Not assigned yet';
            var asg=document.querySelector('[data-cc-govgo="assign_complaint"]');
            if(asg) asg.textContent=String(r.assignedGovernmentName||'').trim()?'Change':'Assign';
            var tl=document.querySelector('[data-cc-govtimeline]');
            if(!tl) return;
            tl.innerHTML='';
            if(!d.timelineLoaded){ tl.appendChild(note('Status history could not be loaded. '+(d.error||''))); return; }
            var events=(d.timeline||[]).slice().reverse();
            if(!events.length){ tl.appendChild(note('No status events recorded yet.')); return; }
            events.forEach(function(ev,i){
              var tpl=(i===0?tl.__ccTemplate:(tl.__ccOlder||tl.__ccTemplate));
              var row;
              if(tpl){
                row=tpl.cloneNode(true);
                var ps=row.querySelectorAll('p');
                if(ps[0]) ps[0].textContent=ev.createdAt?fmtDateTime(ev.createdAt):'Time unavailable';
                if(ps[1]) ps[1].textContent=statusLabel(ev.status);
                if(ps[2]) ps[2].textContent=(ev.message||'')+(ev.actorName?(' - by '+ev.actorName):'');
              } else { row=note(statusLabel(ev.status)+': '+(ev.message||'')); }
              tl.appendChild(row);
            });
          }
          if(here('complaint_details_2')){
            window.chipchapReportDetail=function(json){
              var d={}; try{d=JSON.parse(json)}catch(err){}
              bindGovDetails(d);
            };
          }

          // ---------------- notifications_2 ----------------
          function setupGovNotifications(){
            var main=document.querySelector('main'); if(!main) return;
            main.querySelectorAll('section').forEach(function(s){ s.remove(); });
            main.querySelectorAll('button').forEach(function(b){
              var l=label(b); if(l==='load more history'||l==='mark all as read') b.style.display='none';
            });
            if(!main.querySelector('[data-cc-govnote]')){
              main.appendChild(note('No notifications yet. Government alerts are not part of this build; new citizen reports appear in the Complaint Queue.'));
            }
          }

          // ---------------- analytics ----------------
          function setupAnalytics(){
            var main=document.querySelector('main'); if(!main) return;
            main.querySelectorAll('section').forEach(function(s){ if(/AI Analysis/i.test(txt(s))) s.remove(); });
            main.querySelectorAll('.material-symbols-outlined').forEach(function(i){
              if(iconKey(i)==='arrow_upward'){ var chip=i.parentElement; if(chip) chip.style.display='none'; }
            });
            main.querySelectorAll('p').forEach(function(p){
              var t=txt(p);
              if(t==='1,432'){ p.setAttribute('data-cc-an','total'); p.textContent='...'; }
              if(t==='86.4%'){ p.setAttribute('data-cc-an','rate'); p.textContent='...'; }
            });
            main.querySelectorAll('h3').forEach(function(h){
              if(txt(h)==='Weekly Trends'){ h.textContent='Reports submitted this week'; var sec=h.closest('section'); if(sec) sec.setAttribute('data-cc-an','week');
                var b=h.parentElement?h.parentElement.querySelector('button'):null;
                if(b){ b.querySelectorAll('.material-symbols-outlined').forEach(function(i){ i.remove(); }); b.style.pointerEvents='none'; b.textContent='Mon - Sun'; }
              }
              if(txt(h)==='Department Performance'){
                h.textContent='Reports by category';
                var list=h.nextElementSibling;
                if(list){ list.setAttribute('data-cc-an','categories'); list.__ccTemplate=list.children.length?list.children[0].cloneNode(true):null; list.innerHTML=''; list.appendChild(note('Loading...')); }
              }
            });
            var week=main.querySelector('[data-cc-an="week"]');
            if(week){
              week.querySelectorAll('div').forEach(function(x){ if(/^Peak:/.test(txt(x))&&!x.children.length) x.remove(); });
              var bars=week.querySelectorAll('.chart-bar');
              bars.forEach(function(b,i){ b.setAttribute('data-cc-bar',String(i)); b.style.height='2%'; b.style.cursor='default'; });
            }
            setupAnalyticsExtras();
          }
          var CAT_ICON={infrastructure:'construction',cleanliness:'cleaning_services',safety:'warning',other:'more_horiz'};
          function renderAnalytics(list,d){
            var set=function(k,v){ var el=document.querySelector('[data-cc-an="'+k+'"]'); if(el) el.textContent=v; };
            if(!list){ set('total','--'); set('rate','--'); var c0=document.querySelector('[data-cc-an="categories"]'); if(c0){ c0.innerHTML=''; c0.appendChild(note('Reports could not be loaded. '+(d.error||''))); } renderAnalyticsExtras(null,d); return; }
            var c=counts(list), an=d&&d.analytics;
            // Checkpoint 3: native analytics (same load) also count documents the app could not
            // fully read, so these totals agree with every other figure on this screen.
            var tot=an?an.total:c.total, res=an?an.resolved:c.RESOLVED;
            set('total',String(tot));
            set('rate',tot?(Math.round(res*1000/tot)/10+'%'):'--');
            // Reports created on each day of the current week (Mon-Sun, device time zone).
            var now=new Date(), day=(now.getDay()+6)%7, monday=new Date(now.getFullYear(),now.getMonth(),now.getDate()-day).getTime();
            var perDay=[0,0,0,0,0,0,0];
            list.forEach(function(r){ if(r.createdAt&&r.createdAt>=monday){ var i=Math.floor((r.createdAt-monday)/86400000); if(i>=0&&i<7) perDay[i]++; } });
            var max=Math.max.apply(null,perDay);
            document.querySelectorAll('[data-cc-bar]').forEach(function(b){
              var n=perDay[+b.getAttribute('data-cc-bar')]||0;
              b.style.height=(max?Math.max(2,Math.round(n*100/max)):2)+'%';
              b.setAttribute('title',n+' report'+(n===1?'':'s'));
              b.setAttribute('aria-label',n+' reports');
            });
            var week=document.querySelector('[data-cc-an="week"]');
            if(week){
              var cap2=week.querySelector('[data-cc-weeknote]');
              if(!cap2){ cap2=note(''); cap2.setAttribute('data-cc-weeknote','1'); cap2.style.padding='8px 0 0'; cap2.style.fontSize='12px'; week.appendChild(cap2); }
              cap2.textContent=perDay.reduce(function(a,b){return a+b;},0)+' report(s) this week: '+['Mon','Tue','Wed','Thu','Fri','Sat','Sun'].map(function(n,i){return n+' '+perDay[i];}).join(', ')+'.';
            }
            var host=document.querySelector('[data-cc-an="categories"]');
            if(!host) return;
            host.innerHTML='';
            if(!tot){ host.appendChild(note('No reports have been submitted yet.')); renderAnalyticsExtras(list,d); return; }
            var byCat={};
            if(an&&an.byCategory) an.byCategory.forEach(function(x){ if(x.count>0) byCat[x.label]=x.count; });
            else list.forEach(function(r){ var k=String(r.category||'Not set').trim()||'Not set'; byCat[k]=(byCat[k]||0)+1; });
            Object.keys(byCat).sort(function(a,b){return byCat[b]-byCat[a];}).forEach(function(k){
              var pct=Math.round(byCat[k]*100/tot);
              var el=host.__ccTemplate?host.__ccTemplate.cloneNode(true):document.createElement('div');
              if(!host.__ccTemplate){ el.textContent=k+': '+byCat[k]; host.appendChild(el); return; }
              var ic=el.querySelector('.material-symbols-outlined'); if(ic){ var key=CAT_ICON[k.toLowerCase()]||'label'; ic.dataset.ccIcon=key; ic.textContent=key; }
              var h=el.querySelector('h4'); if(h) h.textContent=k;
              el.querySelectorAll('span').forEach(function(s){ if(/SLA$/.test(txt(s))) s.textContent=byCat[k]+' report'+(byCat[k]===1?'':'s')+' · '+pct+'%'; });
              var fill=el.querySelector('[style*="width"]'); if(fill) fill.style.width=pct+'%';
              host.appendChild(el);
            });
            renderAnalyticsExtras(list,d);
            if(window.ccIcons) window.ccIcons();
          }

        """, """
          // ---------------- Checkpoint 3: civic hotspot intelligence (analytics) ----------------
          // Descriptive counts computed natively (CivicAnalytics) from the reports this portal
          // loaded - not predictions. Rendering only, reusing the screen's own KPI card and list
          // row; access is decided natively by loadGovernmentReports.
          var AN_STATUS={SUBMITTED:['Submitted','send'],UNDER_REVIEW:['Under review','visibility'],ASSIGNED:['Assigned','badge'],
            IN_PROGRESS:['In progress','build'],RESOLVED:['Resolved','check_circle'],UNRECOGNISED:['Unrecognised status','help'],MISSING:['Status not set','help']};
          function anPlural(n,w){ return n+' '+w+(n===1?'':'s'); }
          function anVerb(n){ return n===1?'is':'are'; }
          function anKpi(grid,src,key,label,icon){
            var k=src.cloneNode(true); k.setAttribute('data-cc-ankpi',key);
            k.querySelectorAll('.material-symbols-outlined').forEach(function(i){ if(iconKey(i)==='arrow_upward') return; i.dataset.ccIcon=icon; i.textContent=icon; });
            var ps=k.querySelectorAll('p');
            if(ps.length>1){ ps[0].textContent=label; ps[1].setAttribute('data-cc-an',key); ps[1].textContent='...'; }
            grid.appendChild(k);
          }
          function setupAnalyticsExtras(){
            var main=document.querySelector('main'); if(!main) return;
            main.querySelectorAll('p').forEach(function(p){ if(txt(p)==='Total Reports') p.textContent='Reports analysed'; });
            var total=main.querySelector('[data-cc-an="total"]');
            var card=total?total.closest('.card-shadow'):null, grid=card?card.parentElement:null;
            if(card&&grid&&!grid.querySelector('[data-cc-ankpi]')){
              anKpi(grid,card,'unresolved','Unresolved','pending_actions');
              anKpi(grid,card,'aging','Open over 7 days','schedule');
              var cov=note('Loading...'); cov.setAttribute('data-cc-an','coverage'); cov.style.padding='0 2px'; cov.style.fontSize='12px';
              grid.parentElement.insertBefore(cov, grid.nextSibling);
            }
            var cats=main.querySelector('[data-cc-an="categories"]'), sec=cats?cats.closest('section'):null;
            if(sec&&!main.querySelector('[data-cc-an="statuses"]')){
              var after=sec;
              [['statuses','Reports by status'],['hotspots','Areas with several open reports']].forEach(function(x){
                var s2=sec.cloneNode(true);
                var h=s2.querySelector('h3'); if(h) h.textContent=x[1];
                var l=s2.querySelector('[data-cc-an="categories"]');
                if(l){ l.setAttribute('data-cc-an',x[0]); l.__ccTemplate=cats.__ccTemplate; }
                after.parentElement.insertBefore(s2, after.nextSibling); after=s2;
              });
            }
          }
          function anRow(host,icon,title,meta,pct){
            var el=host.__ccTemplate?host.__ccTemplate.cloneNode(true):document.createElement('div');
            if(!host.__ccTemplate){ el.textContent=title+': '+meta; host.appendChild(el); return el; }
            var ic=el.querySelector('.material-symbols-outlined'); if(ic){ ic.dataset.ccIcon=icon; ic.textContent=icon; }
            var h=el.querySelector('h4'); if(h) h.textContent=title;
            el.querySelectorAll('span').forEach(function(s){ if(/SLA$/.test(txt(s))) s.textContent=meta; });
            var fill=el.querySelector('[style*="width"]'); if(fill) fill.style.width=Math.max(0,Math.min(100,pct))+'%';
            host.appendChild(el); return el;
          }
          function renderAnalyticsExtras(list,d){
            var an=(list&&d)?d.analytics:null;
            var set=function(k,v){ var el=document.querySelector('[data-cc-an="'+k+'"]'); if(el) el.textContent=v; };
            var cov=document.querySelector('[data-cc-an="coverage"]');
            var st=document.querySelector('[data-cc-an="statuses"]'), hs=document.querySelector('[data-cc-an="hotspots"]');
            [st,hs].forEach(function(h){ if(h) h.innerHTML=''; });
            if(!an){
              // A failed or refused load is never shown as zero.
              set('unresolved','--'); set('aging','--');
              if(cov) cov.textContent=list?'These figures are not available for this load.':('Reports could not be loaded. '+((d&&d.error)||''));
              [st,hs].forEach(function(h){ if(h) h.appendChild(note(list?'Not available.':'Reports could not be loaded.')); });
              return;
            }
            set('unresolved',String(an.unresolved)); set('aging',String(an.aging));
            if(cov){
              var t=an.limitReached
                ?('Based on the '+an.total+' most recently created reports (the load limit). Older reports are not included.')
                :('Based on all '+an.total+' reports returned (below the '+an.limit+'-report load limit; reports with no creation time are not returned).');
              t+=' Unresolved: Submitted, Under review, Assigned or In progress. Over 7 days: created more than 7 days ago.';
              if(an.unknownStatus) t+=' '+anPlural(an.unknownStatus,'report')+' with a missing or unrecognised status '+anVerb(an.unknownStatus)+' not counted as unresolved.';
              if(an.unresolvedNoDate) t+=' '+anPlural(an.unresolvedNoDate,'unresolved report')+' without a usable date '+anVerb(an.unresolvedNoDate)+' not counted as over 7 days.';
              if(an.unreadable) t+=' '+anPlural(an.unreadable,'report')+' could not be fully read and '+anVerb(an.unreadable)+' counted only where its fields allow.';
              cov.textContent=t;
            }
            if(st){
              if(!an.total) st.appendChild(note('No reports were returned by this load.'));
              else (an.byStatus||[]).forEach(function(x){
                if(!x.count&&(x.key==='UNRECOGNISED'||x.key==='MISSING')) return;
                var info=AN_STATUS[x.key]||[String(x.key),'help'];
                var pct=Math.round(x.count*100/an.total);
                anRow(st,info[1],info[0],anPlural(x.count,'report')+' · '+pct+'%',pct);
              });
            }
            if(hs){
              var r=an.hotspotRadius, min=an.hotspotMin, areas=an.hotspots||[];
              if(an.unresolvedLocated<min){
                hs.appendChild(note('Not enough unresolved reports with a saved location to look for areas ('+an.unresolvedLocated+' found; at least '+min+' needed).'));
              } else if(!areas.length){
                hs.appendChild(note('No area has '+min+' or more unresolved reports within '+r+' m of one of them.'));
              } else {
                hs.appendChild(note('Each area is '+min+' or more unresolved reports within '+r+' m of the report at its centre. A report is counted in one area at most. Nearby reports may not share a cause or be the same issue.'));
                var max=areas[0].count||1;
                areas.forEach(function(h){
                  var where=h.label||('Near '+Number(h.latitude).toFixed(5)+', '+Number(h.longitude).toFixed(5));
                  var mix=(h.categories||[]).map(function(c){ return c.label+' '+c.count; }).join(', ');
                  anRow(hs,'location_on',where,anPlural(h.count,'open report')+(mix?(' · '+mix):''),Math.round(h.count*100/max));
                });
                var foot=an.reportsInHotspots+' of '+an.unresolvedLocated+' unresolved reports with a location are in '+(an.hotspotCount===1?'this area.':('these '+an.hotspotCount+' areas.'));
                if(an.hotspotCount>areas.length) foot='Showing the '+areas.length+' largest of '+an.hotspotCount+' areas. '+an.reportsInHotspots+' of '+an.unresolvedLocated+' unresolved reports with a location are in one of them.';
                hs.appendChild(note(foot));
              }
              if(an.unresolvedNoLocation) hs.appendChild(note(anPlural(an.unresolvedNoLocation,'unresolved report')+' without a usable location '+anVerb(an.unresolvedNoLocation)+' not included.'));
            }
            if(window.ccIcons) window.ccIcons();
          }

          // ---------------- admin_profile ----------------
          function setupGovProfile(){
            var main=document.querySelector('main'); if(!main) return;
            main.querySelectorAll('img').forEach(function(img){
              var av=document.createElement('div');
              av.setAttribute('data-cc-govavatar','1');
              av.style.cssText='width:100%;height:100%;display:flex;align-items:center;justify-content:center;background:#e9edff;color:#003d9b;font-weight:700;font-size:34px';
              img.replaceWith(av);
            });
            main.querySelectorAll('h2,p,span').forEach(function(el){
              if(el.classList.contains('material-symbols-outlined')) return;
              var t=txt(el);
              if(t==='Eleanor Vance'){ el.setAttribute('data-cc-govname','1'); el.textContent='Loading...'; }
              else if(t==='Senior Urban Planner'){ el.setAttribute('data-cc-govemail','1'); el.textContent=''; }
              else if(t==='Department of Infrastructure'){ var row=el.parentElement; if(row) row.style.display='none'; }
              else if(t==='1,248'){ el.setAttribute('data-cc-govstat','resolved'); el.textContent='...'; }
              else if(t==='Issues closed this year') el.textContent='Resolved this year, assigned to you';
              else if(t==='34'){ el.setAttribute('data-cc-govstat','active'); el.textContent='...'; }
              else if(t==='Currently assigned') el.textContent='Open reports assigned to you';
              else if(t==='AI ANALYSIS') el.remove();
              else if(t==='Weekly efficiency metrics') el.textContent='Not available in this build';
              else if(t==='FAQs, Contact Admin IT') el.textContent='How this portal works';
              else if(t==='Account, Notifications, Privacy') el.textContent='Account and password';
            });
            main.querySelectorAll('button').forEach(function(b){
              var l=label(b);
              if(l.indexOf('performance report')===0){ b.setAttribute('data-cc-govbtn','performance'); inactive(b); }
              else if(l.indexOf('settings')===0) b.setAttribute('data-cc-govbtn','settings');
              else if(l.indexOf('help & support')===0) b.setAttribute('data-cc-govbtn','help');
            });
          }
          function renderProfileStats(list){
            var uid=govProfile&&govProfile.uid;
            var set=function(k,v){ var el=document.querySelector('[data-cc-govstat="'+k+'"]'); if(el) el.textContent=v; };
            if(!list){ set('resolved','--'); set('active','--'); return; }
            if(!uid) return;
            var year=new Date().getFullYear(), mine=list.filter(function(r){ return r.assignedGovernmentUid===uid; });
            set('resolved',String(mine.filter(function(r){ return statusKey(r.status)==='RESOLVED'&&r.updatedAt&&new Date(r.updatedAt).getFullYear()===year; }).length));
            set('active',String(mine.filter(function(r){ return statusKey(r.status)!=='RESOLVED'; }).length));
          }
          function settingsSheet(){
            var email=(govProfile&&govProfile.email)||'';
            sheetActions['reset']=function(){
              if(!email){ toast('No email is available for this account.'); return; }
              if(native&&native.sendPasswordReset) native.sendPasswordReset(email);
            };
            sheet('Settings','<p style="margin:0 0 8px">Signed in as <b>'+esc(email||'unknown')+'</b>.</p>'
              +'<p style="margin:0">Government access is granted by administrators outside the app and cannot be changed here. Notification and privacy settings are not part of this build.</p>',
              [{id:'reset',label:'Send password reset email',primary:true}]);
          }
          function helpSheet(){
            sheet('Government portal help',
              '<p style="margin:0 0 8px"><b>Dashboard</b> counts come from the reports citizens have submitted.</p>'
              +'<p style="margin:0 0 8px"><b>Complaint Queue</b> lists every report. Search, use the filter chips or the filter button, and tap a report to open it.</p>'
              +'<p style="margin:0 0 8px"><b>Report details</b> show the citizen\\'s description, category, severity, location and status history. Evidence photos stay on the citizen\\'s phone in this build.</p>'
              +'<p style="margin:0 0 8px"><b>Not available yet:</b> changing status, assignment, notes, crews, broadcasts, alerts and performance reports.</p>'
              +'<p style="margin:0">For account problems, use Forgot Password on the government sign-in screen or contact your administrator.</p>',[]);
          }

          // ---------------- clicks (window capture: runs before the shared document handler) ----------------
          if(GOV){
            window.addEventListener('click',function(e){
              var target=(e.target&&e.target.closest)?e.target.closest('a,button,[role=button],[data-cc-govreport]'):null;
              if(!target) return;
              var sb=target.getAttribute('data-cc-sheetbtn');
              if(sb){
                stop(e);
                var sh=document.querySelector('[data-cc-govsheet]');
                if(sb.indexOf('status:')===0){ var v=sb.slice(7); queueFilter=(v==='all')?{kind:'chip',value:'all'}:{kind:'status',value:v}; if(sh) sh.remove(); renderQueue(lastReports,{}); return; }
                if(sheetActions[sb]){ sheetActions[sb](); }
                if(sh) sh.remove();
                return;
              }
              var rid=target.getAttribute('data-cc-govreport');
              if(rid){ stop(e); openReport(rid); return; }
              var chip=target.getAttribute('data-cc-queuechip');
              if(chip){ stop(e); queueFilter={kind:'chip',value:({'all reports':'all','high priority':'high','pending':'pending','assigned':'assigned'})[chip]}; renderQueue(lastReports,{}); return; }
              if(here('complaint_queue') && iconKey(target)==='tune'){ stop(e); statusFilterSheet(); return; }
              var gb=target.getAttribute('data-cc-govbtn');
              if(gb==='settings'){ stop(e); settingsSheet(); return; }
              if(gb==='help'){ stop(e); helpSheet(); return; }
              if(gb==='performance'){ stop(e); toast('Performance reports are not available in this build.'); return; }
              if(target.getAttribute('data-cc-govnav')){ stop(e); govNavigate(target); return; }
              var l=label(target);
              if(l==='view map'||l==='live map'){ stop(e); if(native&&native.openScreen) native.openScreen('map_explorer'); return; }
              if(l==='dispatch crew'){ stop(e); toast('Crew dispatch is not available in this build.'); return; }
              var go=target.getAttribute('data-cc-govgo');
              if(go){ stop(e); if(native&&native.openScreen) native.openScreen(go); return; }
              var act=target.getAttribute('data-cc-govaction');
              if(act){ stop(e); submitGovAction(act,target); return; }
              var pick=target.getAttribute('data-cc-statuspick');
              if(pick){ stop(e); pickStatus(pick); return; }
              if(l==='assign complaint'||l==='assign'||l==='reassign'||l==='reassign task'){ stop(e); toast('Open a report and use Reassign to assign it.'); return; }
            },true);
          }

          // ---------------- assign_complaint / update_status: next checkpoint, never fake success ----------------
          // ---------------- update_status / assign_complaint (Checkpoint C) ----------------
          var actionReport=null, chosenStatus='';
          function scrubActionScreen(){
            var main=document.querySelector('main'); if(!main) return null;
            var rid=(native&&native.getActiveReportId)?native.getActiveReportId():'';
            main.querySelectorAll('p,span,h4').forEach(function(el){
              if(el.classList.contains('material-symbols-outlined')||el.children.length) return;
              var t=txt(el);
              if(/^Issue ID:/.test(t)||/^Ticket #/.test(t)){ el.setAttribute('data-cc-actionref','1'); el.textContent=rid?('Report #'+rid):'No report selected'; }
              else if(/Team Alpha|Crew dispatched|Pothole reported on|Oct 12|Pending completion/.test(t)) el.textContent='';
              else if(t==='View Availability') el.style.display='none';
            });
            main.querySelectorAll('span,p').forEach(function(el){
              if(!el.children.length && txt(el)==='AI Analysis'){ var card=el.closest('.relative')||el.parentElement&&el.parentElement.parentElement; if(card&&card!==main) card.style.display='none'; }
            });
            main.querySelectorAll('select option').forEach(function(o){ if(o.value&&/^w\\d$/.test(o.value)) o.remove(); });
            main.querySelectorAll('img').forEach(function(img){
              var ph=document.createElement('div');
              ph.setAttribute('data-cc-govevidence','1');
              ph.style.cssText='width:100%;height:100%;min-height:120px;display:flex;align-items:center;justify-content:center;text-align:center;padding:10px;background:#eef0f7;color:#434654;font-size:12px';
              ph.textContent='Loading evidence...';
              img.replaceWith(ph);
            });
            if(!rid){ main.insertBefore(note('Open a report from the Complaint Queue first.'), main.firstChild); return null; }
            if(native&&native.loadReportDetail) native.loadReportDetail(rid);
            return rid;
          }
          function setupUpdateStatus(){
            var main=document.querySelector('main'); if(!main) return;
            var rid=scrubActionScreen();
            // Status Progression becomes the status picker: one row per workflow status, built from the Stitch row.
            var list=null;
            main.querySelectorAll('h3').forEach(function(h){ if(txt(h)==='Status Progression'){ h.textContent='Choose the new status'; list=h.nextElementSibling; } });
            if(list){
              var tpl=list.children.length?list.children[list.children.length-1].cloneNode(true):null;
              list.innerHTML='';
              Object.keys(STATUS_LABELS).forEach(function(k){
                var row=tpl?tpl.cloneNode(true):document.createElement('div');
                row.setAttribute('data-cc-statuspick',k); row.setAttribute('role','button'); row.style.cursor='pointer';
                var h=row.querySelector('h4'); if(h) h.textContent=STATUS_LABELS[k];
                var p=row.querySelector('p'); if(p) p.textContent='';
                var body=row.querySelector('.opacity-60'); if(body) body.classList.remove('opacity-60');
                list.appendChild(row);
              });
            }
            // After-photo upload now has a backend (Cloudinary), so the Stitch tile is restored
            // and wired. The separate "Mark as Final Resolution" toggle stays hidden because the
            // chosen status is what decides resolution - a second control would be a duplicate.
            main.querySelectorAll('span').forEach(function(s){ if(txt(s)==='Mark as Final Resolution'){ var row=s.closest('.flex.items-center.justify-between')||s.parentElement&&s.parentElement.parentElement; if(row) row.style.display='none'; } });
            main.querySelectorAll('span').forEach(function(s){
              if(txt(s)!=='AFTER (UPLOAD)') return;
              var tile=s.closest('.rounded-xl');
              if(!tile) return;
              tile.style.display='';
              tile.setAttribute('data-cc-aftertile','1');
              tile.setAttribute('data-cc-afterpick','1');
              tile.style.cursor='pointer';
              s.setAttribute('data-cc-afterstate','1');
              s.textContent=rid?'AFTER (UPLOAD)':'Open a report first';
            });
            if(rid&&native&&native.clearGovernmentAfterPhoto) native.clearGovernmentAfterPhoto();
            var notes=document.getElementById('resolution-notes');
            if(notes){ notes.value=''; notes.setAttribute('maxlength','500'); notes.setAttribute('placeholder','Message for the citizen (shown in the report timeline)'); }
            main.querySelectorAll('label').forEach(function(l){ if(/Notes for Citizen/.test(txt(l))) l.textContent='Message for the citizen (optional, shown in the timeline)'; });
            main.querySelectorAll('button').forEach(function(b){
              if(label(b).indexOf('mark as completed')>=0){ b.setAttribute('data-cc-govaction','status'); setTail(b,'Update Status'); if(!rid) inactive(b); }
            });
          }
          function pickStatus(k){
            chosenStatus=k;
            document.querySelectorAll('[data-cc-statuspick]').forEach(function(r){
              var on=r.getAttribute('data-cc-statuspick')===k, cur=actionReport&&statusKey(actionReport.status)===r.getAttribute('data-cc-statuspick');
              r.style.background=on?'#e9edff':''; r.style.borderRadius='12px'; r.style.outline=on?'2px solid #003d9b':'none';
              var p=r.querySelector('p'); if(p) p.textContent=cur?'Current status':(on?'Selected':'');
            });
          }
          // Responsible civic department. Stored in the existing assignedGovernmentName field.
          var DEPARTMENTS={roads:'Roads & Public Works',sanitation:'Sanitation',electrical:'Electrical',water:'Water Supply',drainage:'Drainage'};
          function setupAssign(){
            var main=document.querySelector('main'); if(!main) return;
            var rid=scrubActionScreen();
            var dept=document.getElementById('department');
            if(dept){
              dept.innerHTML='';
              var ph=document.createElement('option'); ph.value=''; ph.disabled=true; ph.textContent='Select a department...'; dept.appendChild(ph);
              Object.keys(DEPARTMENTS).forEach(function(k){ var o=document.createElement('option'); o.value=k; o.textContent=DEPARTMENTS[k]; dept.appendChild(o); });
              dept.value='';
            }
            // Worker list, priority and estimate have no backend: hidden rather than faked.
            ['worker','estTime'].forEach(function(id){ var el=document.getElementById(id); var box=el&&el.closest('div.space-y-sm,div.flex.flex-col,div'); if(el){ var grp=el.parentElement&&el.parentElement.parentElement; if(grp&&grp!==main&&grp.tagName!=='FORM') grp.style.display='none'; else el.style.display='none'; } });
            main.querySelectorAll('label').forEach(function(l){ if(txt(l)==='Priority Level'){ var grp=l.parentElement; if(grp&&grp.tagName!=='FORM') grp.style.display='none'; } });
            var notes=document.getElementById('notes');
            if(notes){ notes.value=''; notes.setAttribute('maxlength','300'); notes.setAttribute('placeholder','Optional message for the citizen (shown in the report timeline)'); }
            main.querySelectorAll('label').forEach(function(l){ if(/Internal Notes/.test(txt(l))) l.textContent='Message for the citizen (optional, shown in the timeline)'; });
            main.querySelectorAll('button').forEach(function(b){ if(label(b).indexOf('confirm assignment')>=0){ b.setAttribute('data-cc-govaction','assign'); if(!rid) inactive(b); } });
          }
          function bindActionReport(d){
            // Same evidence rule as bindGovDetails: only a stored HTTPS Cloudinary URL is
            // painted, anything else says so honestly, and a failure here must never stop
            // the rest of the action screen from binding.
            try{
              var ev=(d&&d.report)||{};
              document.querySelectorAll('[data-cc-govevidence]').forEach(function(tile){
                if(showRemoteEvidence(tile, ev.evidenceUrl)) return;
                var note=tile.querySelector('[data-cc-govevidencenote]');
                if(note) note.textContent='Evidence photo unavailable';
                else tile.textContent='Evidence photo unavailable';
              });
            }catch(evErr){
              document.querySelectorAll('[data-cc-govevidence]').forEach(function(tile){
                tile.textContent='Evidence photo unavailable';
              });
            }
            actionReport=(d&&d.found)?d.report:null;
            var ref=document.querySelector('[data-cc-actionref]');
            if(!actionReport){ if(ref) ref.textContent=(d&&d.error)||'Report could not be loaded.'; return; }
            if(ref) ref.textContent='Report #'+actionReport.reportId+' · '+(actionReport.title||'Untitled report');
            document.querySelectorAll('main span').forEach(function(s){ if(!s.children.length&&txt(s)==='In Progress'&&!s.closest('[data-cc-statuspick]')) s.textContent=statusLabel(actionReport.status); });
            var sub=document.querySelector('main h2'); if(sub&&here('assign_complaint')&&!document.querySelector('[data-cc-assigncur]')){ var c=note('Currently: '+(String(actionReport.assignedGovernmentName||'').trim()||'Not assigned yet')+' · Status: '+statusLabel(actionReport.status)); c.setAttribute('data-cc-assigncur','1'); sub.parentElement.insertBefore(c,sub.nextSibling); }
            if(here('assign_complaint')){
              // Reopened report: the saved department is pre-selected (an older free-text name is shown above).
              var dsv=document.getElementById('department'), saved=String(actionReport.assignedGovernmentName||'').trim();
              if(dsv&&saved) Object.keys(DEPARTMENTS).forEach(function(k){ if(DEPARTMENTS[k]===saved) dsv.value=k; });
            }
            if(here('update_status')){
              // Reopened report: the resolution photo saved in Firestore (resolutionEvidenceUrl) is shown
              // in the AFTER tile unless a new photo has just been captured for it.
              var at=document.querySelector('[data-cc-aftertile]'), savedUrl=String(actionReport.resolutionEvidenceUrl||'');
              if(at&&savedUrl.indexOf('https://res.cloudinary.com/')===0){
                at.setAttribute('data-cc-aftersavedurl',savedUrl);
                if(!at.getAttribute('data-cc-afterlocal')){
                  at.style.backgroundImage='url("'+savedUrl+'")'; at.style.backgroundSize='cover'; at.style.backgroundPosition='center';
                  var cs=at.querySelector('[data-cc-afterstate]'); if(cs) cs.textContent='Saved resolution photo';
                }
              }
            }
            if(here('update_status')) pickStatus('');
          }
          function submitGovAction(kind,btn){
            if(btn.getAttribute('data-cc-inactive')) { toast('Open a report from the Complaint Queue first.'); return; }
            var rid=(native&&native.getActiveReportId)?native.getActiveReportId():'';
            if(!rid){ toast('Open a report from the Complaint Queue first.'); return; }
            if(kind==='status'){
              if(!chosenStatus){ toast('Choose the new status first.'); return; }
              var n=document.getElementById('resolution-notes');
              toast('Saving status update...');
              if(native&&native.governmentUpdateStatus) native.governmentUpdateStatus(rid,chosenStatus,n?n.value:'');
            } else {
              var dsel=document.getElementById('department');
              var dname=dsel&&dsel.value?DEPARTMENTS[dsel.value]:'';
              if(!dname){ toast('Choose a department.'); return; }
              var an=document.getElementById('notes');
              toast('Saving assignment...');
              if(native&&native.governmentAssignReport) native.governmentAssignReport(rid,dname,an?an.value:'');
            }
          }


          // ---------------- entry ----------------
          // Government screens have no notification source: the Stitch unread dot on the Alerts icon is removed.
          function removeFakeUnreadDots(){
            document.querySelectorAll('.material-symbols-outlined').forEach(function(icon){
              if(iconKey(icon)!=='notifications') return;
              var box=icon.parentElement; if(!box) return;
              Array.prototype.slice.call(box.children).forEach(function(c){
                if(c!==icon && !c.children.length && !txt(c) && /rounded-full/.test(c.className||'') && /\\bw-2\\b/.test(c.className||'')) c.remove();
              });
            });
          }
          if(GOV && !here('admin_splash_screen')){
            removeFakeUnreadDots();
            setupHeaderAvatar();
            if(native&&native.loadGovernmentProfile) native.loadGovernmentProfile();
          }
          if(here('dashboard')) setupDashboard();
          if(here('complaint_queue')) setupQueue();
          if(here('complaint_details_2')) setupGovDetails();
          if(here('notifications_2')) setupGovNotifications();
          if(here('analytics')) setupAnalytics();
          if(here('admin_profile')) setupGovProfile();
          if(here('assign_complaint')) setupAssign();
          if(here('update_status')) setupUpdateStatus();
          if(here('assign_complaint')||here('update_status')){
            window.chipchapReportDetail=function(json){ var d={}; try{d=JSON.parse(json)}catch(err){} bindActionReport(d); };
          }
          if((here('dashboard')||here('complaint_queue')||here('analytics')||here('admin_profile')) && native && native.loadGovernmentReports) native.loadGovernmentReports();

          // Keep the Checkpoint A admin_login click handling (button + password eye).
          if(here('admin_login')){
            window.addEventListener('click',function(e){
              var b=(e.target&&e.target.closest)?e.target.closest('button'):null;
              if(!b) return;
              if(label(b).indexOf('login as administrator')>=0){ stop(e); govLogin(); return; }
              var k=iconKey(b);
              if(k==='visibility_off'||k==='visibility'){
                stop(e);
                var pw=document.getElementById('password');
                if(pw) pw.type=(pw.type==='password')?'text':'password';
              }
            },true);
          }
        })();
        """);
        web.evaluateJavascript(js, null);
    }

    public class NativeBridge {
        @JavascriptInterface
        public void logout() {
            runOnUiThread(() -> {
                auth.signOut();
                clearSessionState();
                history.clear();
                currentScreen = "";
                web.clearHistory();
                web.clearCache(false);

                web.evaluateJavascript(
                        "(function(){try{localStorage.clear();sessionStorage.clear();}catch(e){}})();",
                        value -> runOnUiThread(() -> loadScreen("login_screen", false))
                );
            });
        }
        @JavascriptInterface public void openScreen(String name) {
            Log.i(FLOW_TAG, "BRIDGE_OPEN_SCREEN name=" + name + " from=" + currentScreen);
            runOnUiThread(() -> loadScreen(name, true));
        }
        @JavascriptInterface public void goBack() {
            Log.i(FLOW_TAG, "BRIDGE_GO_BACK from=" + currentScreen + " historyDepth=" + history.size());
            runOnUiThread(() -> goBackScreen());
        }
        /** Camera only. Gallery is deliberately not implemented in this MVP. */
        @JavascriptInterface public void takePhoto() {
            runOnUiThread(() -> startCameraCapture());
        }

        /**
         * Stores the values the citizen typed into the existing Stitch details screen.
         * Validation happens natively; JavaScript only renders the returned message.
         * Returns {"ok":true} or {"ok":false,"message":"..."}.
         */
        @JavascriptInterface public String saveComplaintDetails(String title, String description,
                                                               String category, String severity) {
            String cleanTitle = title == null ? "" : title.trim();
            String cleanDescription = description == null ? "" : description.trim();
            String cleanCategory = category == null ? "" : category.trim();

            if (cleanTitle.isEmpty()) return validationFailure("Enter a short title for the issue.");
            if (cleanTitle.length() > 120) return validationFailure("Keep the title under 120 characters.");
            if (cleanDescription.isEmpty()) return validationFailure("Describe the issue before continuing.");
            if (cleanCategory.isEmpty()) return validationFailure("Choose a category for the issue.");
            if (!complaintDraft.hasPhoto()) return validationFailure("Capture a photo of the issue before continuing.");
            if (!photoCheckAllowsContinue()) return validationFailure(PHOTO_CHECK_CHECKING.equals(photoCheckState)
                    ? "Wait for the photo check to finish." : "Resolve the photo check on the camera screen first.");

            complaintDraft.setTitle(cleanTitle);
            complaintDraft.setDescription(cleanDescription);
            complaintDraft.setCategory(cleanCategory);
            // null means "the UI could not read a checked radio" - keep whatever the change
            // listener already stored instead of resetting the citizen's choice.
            if (severity != null && !severity.trim().isEmpty()) complaintDraft.setSeverity(severity);
            return "{\"ok\":true}";
        }

        /** Current draft as JSON, so the details and review screens show real values. */
        /**
         * Checkpoint 2 - possible similar reports for the CURRENT draft (review step). Only a page
         * sequence number comes from JavaScript: location and category are read from the native
         * draft. Read-only; never blocks submission; never votes, merges or changes any report.
         */
        @JavascriptInterface public void checkSimilarReports(int seq) {
            runOnUiThread(() -> startSimilarCheck(seq));
        }

        @JavascriptInterface public String getComplaintDraft() {
            try {
                return complaintDraft.toJson().toString();
            } catch (JSONException error) {
                return "{}";
            }
        }

        /** Current AI photo check for the draft: {state, message, category, hasPhoto}. */
        @JavascriptInterface public String getPhotoCheck() {
            return photoCheckJson();
        }

        /** AI report-text draft for the accepted photo: {state, request, handled, title, description}. Memory only. */
        @JavascriptInterface public String getDraftText() {
            return draftTextJson();
        }

        /** The details screen applied (or skipped) the draft for this request: it is never applied again. */
        @JavascriptInterface public void markDraftTextHandled(int request) {
            if (request == draftRequest) draftHandled = true;
        }

        /** Retries the AI check. Only offered, and only honoured, after a technical AI failure. */
        @JavascriptInterface public void retryPhotoCheck() {
            runOnUiThread(() -> {
                File file = photoCheckFile;
                if (!PHOTO_CHECK_UNAVAILABLE.equals(photoCheckState) || file == null
                        || !file.getAbsolutePath().equals(complaintDraft.getLocalImagePath())) return;
                startPhotoCheck(file);
            });
        }

        /**
         * "Continue without AI check": ONLY after a technical failure or uncertainty, never after a
         * confident rejection. The photo is not AI-approved; it simply continues to the existing
         * Cloudinary upload and the citizen chooses the category by hand. Nothing is recorded.
         */
        @JavascriptInterface public void continueWithoutPhotoCheck() {
            runOnUiThread(() -> {
                File file = photoCheckFile;
                if (!PHOTO_CHECK_UNAVAILABLE.equals(photoCheckState) || file == null
                        || !file.getAbsolutePath().equals(complaintDraft.getLocalImagePath())) return;
                photoCheckState = PHOTO_CHECK_SKIPPED;
                photoCheckMessage = "";
                photoCheckCategory = "";
                publishPhotoCheck();
                startCitizenEvidenceUpload(file);
            });
        }

        /** Display-only downscaled photo. Empty string when there is no usable photo. */
        @JavascriptInterface public String getPhotoPreview() {
            return photoPreviewDataUri == null ? "" : photoPreviewDataUri;
        }

        /** Asks Android for a real position. Never returns invented coordinates. */
        @JavascriptInterface public void requestComplaintLocation() {
            runOnUiThread(() -> startLocationCapture(true));
        }

        /**
         * Incident-location map: the citizen moved the centre pin. The coordinates go into the SAME
         * draft fields a device fix uses (latitude, longitude, address), so they reach the submitted
         * report unchanged. Validated natively; the address is looked up again or left empty.
         */
        @JavascriptInterface public void setComplaintPinLocation(double latitude, double longitude) {
            runOnUiThread(() -> {
                boolean valid = !Double.isNaN(latitude) && !Double.isInfinite(latitude)
                        && !Double.isNaN(longitude) && !Double.isInfinite(longitude)
                        && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180
                        && !(latitude == 0 && longitude == 0);
                if (!valid) return;
                final int request = ++complaintLocationRequest;
                complaintDraft.setCoordinates(latitude, longitude);
                complaintDraft.setAddress("");
                complaintDraft.setLocationStatus(String.format(Locale.US, "Pin placed at %.5f, %.5f", latitude, longitude));
                publishLocationStatus();
                locationProvider.lookupAddress(latitude, longitude, address -> {
                    if (request != complaintLocationRequest || isDestroyed()) return;
                    if (address == null || address.trim().isEmpty()) return;
                    complaintDraft.setAddress(address);
                    complaintDraft.setLocationStatus(address);
                    publishLocationStatus();
                });
            });
        }

        /** Creates the Firestore report and its first timeline entry. */
        @JavascriptInterface public void submitComplaint() {
            runOnUiThread(() -> startComplaintSubmission());
        }

        /** Copies text to the Android clipboard. Used for the real tracking id and Copy Link. */
        @JavascriptInterface public void copyToClipboard(String label, String text) {
            runOnUiThread(() -> {
                String value = text == null ? "" : text.trim();
                if (value.isEmpty()) { notifyWeb("There is nothing to copy."); return; }
                try {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard == null) { notifyWeb("The clipboard is not available on this device."); return; }
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            label == null || label.trim().isEmpty() ? "CHIPCHAP" : label.trim(), value));
                    notifyWeb((label == null || label.trim().isEmpty() ? "Copied" : label.trim() + " copied") + ".");
                } catch (Exception error) {
                    notifyWeb("Could not copy to the clipboard.");
                }
            });
        }

        /** Android share sheet. No fabricated public URL is ever included. */
        @JavascriptInterface public void shareText(String subject, String body) {
            runOnUiThread(() -> {
                String value = body == null ? "" : body.trim();
                if (value.isEmpty()) { notifyWeb("There is nothing to share."); return; }
                try {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    if (subject != null && !subject.trim().isEmpty()) intent.putExtra(Intent.EXTRA_SUBJECT, subject.trim());
                    intent.putExtra(Intent.EXTRA_TEXT, value);
                    startActivity(Intent.createChooser(intent, "Share report"));
                } catch (Exception error) {
                    notifyWeb("No app is available to share this.");
                }
            });
        }

        /** Opens the dialer (never places a call) or an email composer. */
        @JavascriptInterface public void openContact(String kind, String value) {
            runOnUiThread(() -> {
                String target = value == null ? "" : value.trim();
                if (target.isEmpty()) return;
                try {
                    Intent intent;
                    if ("phone".equals(kind)) {
                        intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + target.replace(" ", "")));
                    } else {
                        intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + target));
                        intent.putExtra(Intent.EXTRA_SUBJECT, "CHIPCHAP support request");
                    }
                    startActivity(intent);
                } catch (Exception error) {
                    notifyWeb("No app on this device can open that.");
                }
            });
        }

        /** Opens the camera for the government after/resolution photo on the open report. */
        @JavascriptInterface public void governmentCaptureAfterPhoto() {
            runOnUiThread(() -> startGovernmentPhotoCapture());
        }

        /** Local preview of the captured after-photo. No file path ever crosses the bridge. */
        @JavascriptInterface public String getGovernmentAfterPhotoPreview() {
            String path = governmentAfterPhotoPath;
            if (path.isEmpty()) return "";
            File file = new File(path);
            if (!localPhotoRepository.isAppPhoto(file)) return "";
            return localPhotoRepository.buildPreviewDataUri(file, CARD_THUMBNAIL_EDGE_PX);
        }

        @JavascriptInterface public void clearGovernmentAfterPhoto() {
            runOnUiThread(() -> { clearGovernmentAfterPhotoState(); publishGovernmentAfterPhoto(); });
        }

        /** The report id of the submission that just succeeded, for the success screen. */
        @JavascriptInterface public String getLastReportId() {
            return lastSubmittedReportId == null ? "" : lastSubmittedReportId;
        }

        /** Remembers which stored report the details screen is showing. */
        @JavascriptInterface public void setActiveReport(String reportId) {
            activeReportId = reportId == null ? "" : reportId.trim();
        }

        @JavascriptInterface public String getActiveReportId() {
            return activeReportId == null ? "" : activeReportId;
        }

        /**
         * The report's own evidence photo as a downscaled data URI, only when that exact file exists
         * inside this app's evidence folder on THIS device. Returns "" otherwise - the photo was
         * taken on another phone (no remote storage in this MVP) or has been removed - so the UI can
         * say so instead of showing a stock image.
         */
        @JavascriptInterface public String getReportPhotoPreview(String reportId, boolean thumbnail) {
            if (reportId == null || reportId.trim().isEmpty()) return "";
            String path = reportPhotoPaths.get(reportId.trim());
            if (path == null || path.isEmpty()) return "";
            File file = new File(path);
            if (!localPhotoRepository.isAppPhoto(file)) return "";
            return thumbnail
                    ? localPhotoRepository.buildPreviewDataUri(file, CARD_THUMBNAIL_EDGE_PX)
                    : localPhotoRepository.buildPreviewDataUri(file);
        }

        /** The signed-in citizen's own in-app notifications (users/{authUid}/notifications). */
        @JavascriptInterface public void loadMyNotifications() {
            runOnUiThread(() -> notificationRepository.loadMine(50, new RepositoryCallback<List<NotificationRepository.Item>>() {
                @Override public void onSuccess(List<NotificationRepository.Item> result) { publishNotifications(result, ""); }
                @Override public void onFailure(Exception error) {
                    publishNotifications(null, FirestoreErrors.describe(error, "Could not load notifications."));
                }
            }));
        }

        /** Real unread count for the notification dot; -1 hides the dot when it cannot be read. */
        @JavascriptInterface public void loadUnreadNotificationCount() {
            runOnUiThread(() -> notificationRepository.countUnread(new RepositoryCallback<Integer>() {
                @Override public void onSuccess(Integer result) { publishUnreadCount(result == null ? 0 : result); }
                @Override public void onFailure(Exception error) { publishUnreadCount(-1); }
            }));
        }

        /** Marks one of the signed-in user's own notifications read. The uid comes from Firebase Auth. */
        @JavascriptInterface public void markNotificationRead(String notificationId) {
            runOnUiThread(() -> notificationRepository.markRead(notificationId, new RepositoryCallback<Void>() {
                @Override public void onSuccess(Void result) { }
                @Override public void onFailure(Exception error) {
                    notifyWeb("The notification could not be marked as read. "
                            + FirestoreErrors.describe(error, "Try again later."));
                }
            }));
        }

        /** Real counts of the signed-in citizen's own reports (all / resolved), for the dashboard tiles and achievements. */
        @JavascriptInterface public void loadMyReportCount() {
            runOnUiThread(() -> complaintRepository.loadMyComplaints(new RepositoryCallback<List<Complaint>>() {
                @Override public void onSuccess(List<Complaint> result) {
                    // Same single query: how many of this citizen's own reports are RESOLVED.
                    int resolved = 0;
                    if (result != null) {
                        for (Complaint report : result) {
                            if (report != null && "RESOLVED".equalsIgnoreCase(safe(report.getStatus()).trim())) resolved++;
                        }
                    }
                    publishMyReportCount(result == null ? 0 : result.size(), resolved);
                }
                @Override public void onFailure(Exception error) {
                    // No invented number: the tile stays blank when the count cannot be read.
                    publishMyReportCount(-1, -1);
                }
            }));
        }

        /** Severity is stored the moment it changes, so it cannot be lost on re-render. */
        @JavascriptInterface public void setDraftSeverity(String severity) {
            complaintDraft.setSeverity(severity);
        }

        /** The signed-in citizen's profile, for the greeting and the Edit Profile modal. */
        @JavascriptInterface public String getCitizenProfile() {
            return buildCitizenProfileJson();
        }

        /**
         * Persists Full name and Neighborhood to users/{uid}. The uid and email are never taken
         * from JavaScript - UserRepository derives them from FirebaseAuth - and updatedAt is
         * refreshed by that repository.
         */
        @JavascriptInterface public void saveCitizenProfile(String fullName, String neighborhood) {
            runOnUiThread(() -> {
                String cleanName = fullName == null ? "" : fullName.trim();
                if (cleanName.isEmpty()) {
                    notifyWeb("Enter your full name.");
                    return;
                }
                userRepository.saveProfile(cleanName, neighborhood, new RepositoryCallback<UserProfile>() {
                    @Override public void onSuccess(UserProfile result) {
                        citizenProfile = result;
                        publishCitizenProfile();
                        notifyWeb("Profile updated.");
                    }
                    @Override public void onFailure(Exception error) {
                        notifyWeb("Your profile was not saved. "
                                + FirestoreErrors.describe(error, "Check your connection and try again."));
                    }
                });
            });
        }

        /**
         * Marker data for the map screen. ONE bounded query, reusing the existing community-reports
         * read - no new collection, no per-marker reads. Whether this session may also see the
         * government density heatmap is decided HERE, natively, by the existing
         * hasGovernmentAccess() check: a citizen session is answered with mode CITIZEN and never
         * receives anything the heat layer could be built from beyond the public markers.
         */
        @JavascriptInterface public void loadMapReports() {
            runOnUiThread(() -> {
                final boolean government = hasGovernmentAccess();
                complaintRepository.loadCommunityReports(government ? GOVERNMENT_REPORT_LIMIT : MAP_REPORT_LIMIT,
                        new RepositoryCallback<List<Complaint>>() {
                            @Override public void onSuccess(List<Complaint> result) {
                                publishMapReports(government, result, "");
                            }
                            @Override public void onFailure(Exception error) {
                                publishMapReports(government, null,
                                        FirestoreErrors.describe(error, "Report locations could not be loaded."));
                            }
                        });
            });
        }

        /**
         * Centres the map on the device position using the existing LocationProvider. Nothing is
         * invented: without permission or a fix the map simply stays where it is and says why.
         */
        @JavascriptInterface public void requestMapLocation() {
            runOnUiThread(() -> {
                if (!locationProvider.hasPermission()) {
                    publishMapLocation(false, 0, 0,
                            "Location permission is not granted, so the map cannot centre on you.", false, false);
                    return;
                }
                // A newer tap supersedes an older request, so two answers never fight over the map.
                final int request = ++mapLocationRequest;
                // 1. Instant: a recent position the device already has (never invented).
                final double[] recent = locationProvider.recentLocation(MAP_RECENT_LOCATION_MAX_AGE_MS);
                if (recent != null) publishMapLocation(true, recent[0], recent[1], "", false, false);
                final boolean hadRecent = recent != null;
                // 2. Asynchronous: a fresh fix, reported the moment it arrives (no address lookup).
                locationProvider.requestQuickLocation(LOCATION_TIMEOUT_MS, new LocationProvider.LocationResultCallback() {
                    @Override public void onLocation(double latitude, double longitude, String address) {
                        if (request != mapLocationRequest) return;
                        publishMapLocation(true, latitude, longitude, address, hadRecent, false);
                    }
                    @Override public void onUnavailable(String reason) {
                        if (request != mapLocationRequest) return;
                        // The map is already centred on a real recent position: stay quiet.
                        publishMapLocation(false, 0, 0, reason, false, hadRecent);
                    }
                });
            });
        }

        /** The signed-in citizen's own reports, for the My Reports screen. */
        @JavascriptInterface public void loadMyReports() {
            runOnUiThread(() -> complaintRepository.loadMyComplaints(new RepositoryCallback<List<Complaint>>() {
                @Override public void onSuccess(List<Complaint> result) { publishReports("chipchapMyReports", result, ""); }
                @Override public void onFailure(Exception error) {
                    publishReports("chipchapMyReports", null, FirestoreErrors.describe(error, "Could not load your reports."));
                }
            }));
        }

        /** Real reports for the community feed. */
        @JavascriptInterface public void loadCommunityFeed() {
            runOnUiThread(() -> complaintRepository.loadCommunityReports(20, new RepositoryCallback<List<Complaint>>() {
                @Override public void onSuccess(List<Complaint> result) { publishCommunityFeed(result, ""); }
                @Override public void onFailure(Exception error) {
                    publishCommunityFeed(null, FirestoreErrors.describe(error, "Could not load community reports."));
                }
            }));
        }

        /** One stored report plus its real timeline, for the status/tracking screen. */
        @JavascriptInterface public void loadReportDetail(String reportId) {
            runOnUiThread(() -> complaintRepository.loadComplaint(reportId, new RepositoryCallback<Complaint>() {
                @Override public void onSuccess(Complaint report) {
                    if (report == null) { publishReportDetail(null, null, "That report could not be found."); return; }
                    timelineRepository.loadTimeline(reportId, new RepositoryCallback<List<TimelineEvent>>() {
                        @Override public void onSuccess(List<TimelineEvent> events) { publishReportDetail(report, events, ""); }
                        @Override public void onFailure(Exception error) {
                            publishReportDetail(report, null, FirestoreErrors.describe(error, "The history could not be loaded."));
                        }
                    });
                }
                @Override public void onFailure(Exception error) {
                    publishReportDetail(null, null, FirestoreErrors.describe(error, "That report could not be loaded."));
                }
            }));
        }

        /** Vote count, the signed-in user's own vote, and the comments for one report. */
        @JavascriptInterface public void loadReportInteractions(String reportId) {
            runOnUiThread(() -> refreshInteractions(reportId, ""));
        }

        /** Idempotent upvote: the document id is the uid, so repeat taps cannot inflate a count. */
        @JavascriptInterface public void toggleReportVote(String reportId, boolean wantsVote) {
            runOnUiThread(() -> {
                RepositoryCallback<VoteRepository.VoteState> callback = new RepositoryCallback<VoteRepository.VoteState>() {
                    @Override public void onSuccess(VoteRepository.VoteState result) { refreshInteractions(reportId, ""); }
                    @Override public void onFailure(Exception error) {
                        refreshInteractions(reportId, FirestoreErrors.describe(error, "Your vote was not saved."));
                    }
                };
                if (wantsVote) voteRepository.vote(reportId, callback);
                else voteRepository.removeVote(reportId, callback);
            });
        }

        /** Posts a comment owned by the authenticated user. Empty text is rejected natively. */
        @JavascriptInterface public void addReportComment(String reportId, String text) {
            runOnUiThread(() -> commentRepository.addComment(reportId, text, new RepositoryCallback<Comment>() {
                @Override public void onSuccess(Comment result) { refreshInteractions(reportId, ""); }
                @Override public void onFailure(Exception error) {
                    refreshInteractions(reportId, FirestoreErrors.describe(error,
                            error instanceof IllegalArgumentException && error.getMessage() != null
                                    ? error.getMessage() : "Your comment was not posted."));
                }
            }));
        }
        @JavascriptInterface public void requestAppPermissions() {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                java.util.ArrayList<String> p = new java.util.ArrayList<>();
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.CAMERA);
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION);
                if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
                if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), REQ_PERMISSIONS);
            }
        }
        @JavascriptInterface public void openMaps(String query) {
            try { String q=URLEncoder.encode(query==null?"Civic issues":query,"UTF-8"); Intent i=new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="+q)); startActivity(i); }
            catch(Exception e){ Toast.makeText(MainActivity.this,"No maps app is available",Toast.LENGTH_SHORT).show(); }
        }
        /**
         * Checkpoint F1 - Navigate to Report (government only). Replaces the former external Google
         * Maps handoff with an in-app route preview: Geoapify calculates a driving route from the
         * device's real position to the report's STORED coordinates, and the route is drawn on
         * CHIPCHAP's own MapLibre map. Only the report id comes from the WebView - the coordinates
         * are read natively. Runs only from an explicit tap, never automatically.
         */
        @JavascriptInterface public void requestReportRoute(String reportId) {
            runOnUiThread(() -> startRoute(reportId, "", "", true));
        }
        /**
         * Checkpoint F2 - the same report route with an edited starting point. originPlaceId is a
         * searched place id, or "" for the device's location. The report stays the destination.
         */
        @JavascriptInterface public void requestReportRouteFrom(String reportId, String originPlaceId) {
            runOnUiThread(() -> {
                if (reportId == null || reportId.trim().isEmpty()) { notifyWeb("Open a report before requesting a route."); return; }
                startRoute(reportId, "", originPlaceId, true);
            });
        }
        /** Checkpoint F2 - route to a searched place, from a searched place or ("") the device location. */
        @JavascriptInterface public void requestPlaceRoute(String originPlaceId, String destPlaceId) {
            runOnUiThread(() -> startRoute("", destPlaceId, originPlaceId, true));
        }
        /** Clear Route: forgets the route and its panel only. Reports, map layers and Firestore are untouched. */
        @JavascriptInterface public void clearReportRoute() {
            runOnUiThread(() -> {
                Log.i(ROUTE_TAG, "ROUTE_CLEARED gen=" + routeGeneration);
                resetRouteState();
                publishRoute();
            });
        }
        /** Checkpoint F3 - Copy start: the chosen place, or the device position as coordinates. */
        @JavascriptInterface public void copyRouteStart(String originPlaceId) {
            runOnUiThread(() -> copyRouteStartText(originPlaceId));
        }
        /** Checkpoint F3 - Copy destination: a report's stored values or the chosen place. */
        @JavascriptInterface public void copyRouteDestination(String reportId, String destPlaceId) {
            runOnUiThread(() -> copyRouteDestinationText(reportId, destPlaceId));
        }
        /** Checkpoint F3 - Share route through the Android share sheet (the user picks the app). */
        @JavascriptInterface public void shareRoute(String originPlaceId, String reportId, String destPlaceId) {
            runOnUiThread(() -> shareCurrentRoute(originPlaceId, reportId, destPlaceId));
        }
        /** The map screen asks for the current route state when it loads. Never a network call. */
        @JavascriptInterface public void loadRouteState() {
            runOnUiThread(() -> publishRoute());
        }
        /**
         * Which map this session may show, decided natively by hasGovernmentAccess(). It only
         * chooses UI (no Create Report button, place search) - every action is gated again natively
         * and Firestore rules still check the claim server-side.
         */
        @JavascriptInterface public String getMapMode() {
            return hasGovernmentAccess() ? "GOVERNMENT" : "CITIZEN";
        }
        /**
         * Checkpoint F2 - place / address search (government map only). The page debounces typing
         * and sends at most one query per pause; seq is echoed back so the page can ignore old
         * answers, and a newer search natively supersedes an older one.
         */
        @JavascriptInterface public void searchPlaces(String query, int seq, double biasLat, double biasLng) {
            runOnUiThread(() -> {
                if (!hasMapRouteAccess()) { publishPlaces(seq, "error", "Sign in to search places.", null); return; }
                if (routeService == null || !routeService.isConfigured()) {
                    Log.w(ROUTE_TAG, "PLACE_SEARCH_NOT_CONFIGURED: GEOAPIFY_API_KEY is missing from local.properties");
                    publishPlaces(seq, "error", "Place search is not configured in this build.", null);
                    return;
                }
                String q = query == null ? "" : query.trim().replaceAll("\\s+", " ");
                if (q.length() < 3) { publishPlaces(seq, "error", "Type at least 3 characters to search places.", null); return; }
                if (q.length() > GeoapifyRouteService.MAX_QUERY_CHARS) q = q.substring(0, GeoapifyRouteService.MAX_QUERY_CHARS);
                // Ranking hint only: the map's current centre, else a recent device position.
                double[] bias = validRouteCoordinate(biasLat, biasLng) ? new double[]{ biasLat, biasLng }
                        : locationProvider.recentLocation(MAP_RECENT_LOCATION_MAX_AGE_MS);
                final int generation = ++placeSearchGeneration;
                Log.i(ROUTE_TAG, "PLACE_SEARCH gen=" + generation + " chars=" + q.length());
                routeService.searchPlaces(q, bias, new GeoapifyRouteService.PlaceCallback() {
                    @Override public void onPlaces(List<GeoapifyRouteService.Place> places) {
                        if (generation != placeSearchGeneration || isDestroyed()) return;
                        publishPlaces(seq, "ok", "", places);
                    }
                    @Override public void onFailed(String message) {
                        if (generation != placeSearchGeneration || isDestroyed()) return;
                        publishPlaces(seq, "error", message, null);
                    }
                });
            });
        }
        /** The search box was emptied or a place was chosen: any search still on its way is dropped. */
        @JavascriptInterface public void cancelPlaceSearch() {
            runOnUiThread(() -> {
                placeSearchGeneration++;
                if (routeService != null) routeService.cancelSearches();
            });
        }
        @JavascriptInterface public String getVerificationEmail() {
            FirebaseUser user = auth.getCurrentUser();
            return user != null && user.getEmail() != null ? user.getEmail() : "";
        }
        @JavascriptInterface public void citizenRegister(String fullName, String email, String password, String neighborhood) {
            runOnUiThread(() -> {
                FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password)
                        .addOnCompleteListener(MainActivity.this, task -> {
                            if (task.isSuccessful()) {
                                FirebaseUser user = auth.getCurrentUser();
                                if (user == null) {
                                    notifyWeb("Account creation did not return a signed-in user. Please try again.");
                                    return;
                                }
                                UserProfileChangeRequest profile = new UserProfileChangeRequest.Builder()
                                        .setDisplayName(fullName == null ? "" : fullName.trim()).build();
                                user.updateProfile(profile).addOnCompleteListener(profileTask -> {
                                    if (!profileTask.isSuccessful()) {
                                        notifyWeb("Your account was created, but your display name could not be saved.");
                                    }
                                    // Firestore is authoritative for the application profile.
                                    // The uid is taken from the authenticated user inside UserRepository.
                                    // Signup goes straight into the app. No verification email is sent
                                    // automatically and no verification notice is shown; emailVerified
                                    // gates nothing. Password reset is untouched and still uses
                                    // Firebase's own reset email (see sendPasswordReset below).
                                    userRepository.saveProfile(fullName, neighborhood, new RepositoryCallback<UserProfile>() {
                                        @Override public void onSuccess(UserProfile result) {
                                            citizenProfile = result;
                                            routeAfterAuthentication(true);
                                        }
                                        @Override public void onFailure(Exception error) {
                                            notifyWeb("Your account was created, but your profile could not be saved. "
                                                    + FirestoreErrors.describe(error, "Try updating your profile again later."));
                                            routeAfterAuthentication(true);
                                        }
                                    });
                                });
                            } else {
                                notifyWeb(friendlyAuthError(task.getException(), "Registration failed. Please check your details."));
                            }
                        });
            });
        }
        /**
         * Recent reports for the government dashboard, queue, analytics and profile statistics.
         * Reads the same reports collection the citizen side writes; nothing is copied or invented.
         * Screen gating is UI only - Firestore rules decide what the signed-in account may read.
         */
        @JavascriptInterface public void loadGovernmentReports() {
            runOnUiThread(() -> {
                if (!hasGovernmentAccess()) {
                    publishGovernmentReports(null, "Government access is required.");
                    return;
                }
                // Checkpoint 3: the same bounded read (newest GOVERNMENT_REPORT_LIMIT reports), now
                // also feeding the analytics screen. Analytics are computed natively from this one
                // result - no extra query - and only behind this government check.
                complaintRepository.loadGovernmentReports(GOVERNMENT_REPORT_LIMIT, new RepositoryCallback<ComplaintRepository.GovernmentReports>() {
                    @Override public void onSuccess(ComplaintRepository.GovernmentReports result) {
                        if (!hasGovernmentAccess()) {   // signed out while the read was running
                            publishGovernmentReports(null, "Government access is required.");
                            return;
                        }
                        CivicAnalytics.Summary analytics =
                                CivicAnalytics.compute(result.records, result.limit, System.currentTimeMillis());
                        publishGovernmentReports(result.complaints, "", analytics);
                    }
                    @Override public void onFailure(Exception error) {
                        publishGovernmentReports(null, FirestoreErrors.describe(error, "Could not load reports."));
                    }
                });
            });
        }

        /**
         * Government status change on a stored report (update_status screen). Only the report id,
         * the requested status and a note come from JavaScript; the status is checked against the
         * allowed list natively, and the report owner and title are read from Firestore.
         */
        @JavascriptInterface public void governmentUpdateStatus(String reportId, String newStatus, String note) {
            runOnUiThread(() -> {
                String status = newStatus == null ? "" : newStatus.trim().toUpperCase(Locale.US);
                if (!ComplaintRepository.GOVERNMENT_STATUSES.contains(status)) {
                    notifyWeb("Choose a valid status."); return;
                }
                String message = note == null || note.trim().isEmpty()
                        ? "Status changed to " + NotificationRepository.statusLabel(status) + "." : note.trim();
                runGovernmentAction(reportId, status, false, null, message);
            });
        }

        /**
         * Minimum assignment (assign_complaint screen): the report is assigned to the signed-in
         * government user, labelled with the chosen department, and moved to ASSIGNED unless it is
         * already further along.
         */
        @JavascriptInterface public void governmentAssignReport(String reportId, String department, String note) {
            runOnUiThread(() -> {
                String dept = department == null ? "" : department.trim();
                if (dept.isEmpty() || dept.length() > 80) { notifyWeb("Choose a department."); return; }
                String message = "Assigned to " + dept + "." + (note == null || note.trim().isEmpty() ? "" : " " + note.trim());
                runGovernmentAction(reportId, null, true, dept, message);
            });
        }

        /** The signed-in government user's own name and email, for the portal header and profile. */
        @JavascriptInterface public void loadGovernmentProfile() {
            runOnUiThread(() -> {
                if (!hasGovernmentAccess()) { publishGovernmentProfile(null); return; }
                userRepository.loadProfile(new RepositoryCallback<UserProfile>() {
                    @Override public void onSuccess(UserProfile result) { publishGovernmentProfile(result); }
                    @Override public void onFailure(Exception error) { publishGovernmentProfile(null); }
                });
            });
        }

        /**
         * Government sign-in from the existing admin_login Stitch screen. Firebase Auth signs the
         * user in, the ID token is force-refreshed, and only a token carrying the custom claim
         * role == "GOVERNMENT" opens the government screens. Any other account is signed out again.
         * No role is ever taken from JavaScript or stored on the device.
         */
        @JavascriptInterface public void governmentLogin(String email, String password) {
            runOnUiThread(() -> {
                String cleanEmail = email == null ? "" : email.trim();
                if (cleanEmail.isEmpty() || password == null || password.isEmpty()) {
                    notifyWeb("Enter your government email and password.");
                    return;
                }
                governmentVerifiedUid = "";
                auth.signInWithEmailAndPassword(cleanEmail, password)
                        .addOnCompleteListener(MainActivity.this, task -> {
                            if (!task.isSuccessful()) {
                                notifyWeb(friendlyAuthError(task.getException(),
                                        "Sign-in failed. Check your government email and password."));
                                return;
                            }
                            FirebaseUser user = auth.getCurrentUser();
                            if (user == null) {
                                notifyWeb("Sign-in did not return a user. Please try again.");
                                return;
                            }
                            user.getIdToken(true).addOnCompleteListener(MainActivity.this, tokenTask -> {
                                FirebaseUser current = auth.getCurrentUser();
                                if (current == null) {
                                    notifyWeb("Your session ended. Please sign in again.");
                                    return;
                                }
                                if (!tokenTask.isSuccessful()) {
                                    auth.signOut();
                                    governmentVerifiedUid = "";
                                    notifyWeb("Government access could not be verified, so you were signed out. "
                                            + "Check your connection and try again.");
                                    return;
                                }
                                if (!isGovernmentToken(tokenTask.getResult())) {
                                    auth.signOut();
                                    governmentVerifiedUid = "";
                                    notifyWeb("This account does not have government access. You have been signed out.");
                                    return;
                                }
                                governmentVerifiedUid = current.getUid();
                                history.clear();
                                loadScreenUnchecked("admin_splash_screen", false);
                            });
                        });
            });
        }
        @JavascriptInterface public void citizenLogin(String email, String password) {
            runOnUiThread(() -> {
                FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
                        .addOnCompleteListener(MainActivity.this, task -> {
                            if (!task.isSuccessful()) {
                                notifyWeb(friendlyAuthError(task.getException(), "Authentication failed. Please check your credentials."));
                                return;
                            }
                            FirebaseUser signedIn = auth.getCurrentUser();
                            if (signedIn == null) {
                                notifyWeb("Sign-in did not return a user. Please try again.");
                                return;
                            }
                            // Two-way separation: an account carrying role == GOVERNMENT may only use
                            // Government Login. A fresh token is read so a newly added claim counts.
                            signedIn.getIdToken(true).addOnCompleteListener(MainActivity.this, tokenTask -> {
                                if (auth.getCurrentUser() == null) {
                                    notifyWeb("Your session ended. Please sign in again.");
                                    return;
                                }
                                if (!tokenTask.isSuccessful()) {
                                    auth.signOut();
                                    clearSessionState();
                                    notifyWeb("Your account could not be verified, so you were signed out. "
                                            + "Check your connection and try again.");
                                    return;
                                }
                                if (isGovernmentToken(tokenTask.getResult())) {
                                    auth.signOut();
                                    clearSessionState();
                                    history.clear();
                                    notifyWeb(GOVERNMENT_ACCOUNT_ON_CITIZEN_LOGIN);
                                    return;
                                }
                                routeAfterAuthentication(false);
                            });
                        });
            });
        }

        @JavascriptInterface public void resendVerification() {
            runOnUiThread(() -> {
                FirebaseUser user = auth.getCurrentUser();
                if (user == null) {
                    loadScreenUnchecked("login_screen", false);
                    notifyWeb("Please sign in to resend a verification email.");
                    return;
                }
                if (user.isEmailVerified()) {
                    loadScreenUnchecked("home_screen", false);
                    return;
                }
                sendVerificationEmail(user, false);
            });
        }

        @JavascriptInterface public void checkEmailVerification() {
            runOnUiThread(() -> {
                FirebaseUser user = auth.getCurrentUser();
                if (user == null) {
                    loadScreenUnchecked("login_screen", false);
                    notifyWeb("Please sign in to check verification.");
                    return;
                }
                setVerificationState("loading", "Checking verification status…");
                user.reload().addOnCompleteListener(MainActivity.this, task -> {
                    FirebaseUser refreshedUser = auth.getCurrentUser();
                    if (!task.isSuccessful() || refreshedUser == null) {
                        setVerificationState("error", friendlyAuthError(task.getException(), "Unable to check verification status. Try again."));
                    } else if (refreshedUser.isEmailVerified()) {
                        setVerificationState("verified", "Email verified. Continuing…");
                        loadScreenUnchecked("app_permissions", false);
                    } else {
                        setVerificationState("pending", "Your email is not verified yet. Open the link we sent, then check again.");
                    }
                });
            });
        }

        @JavascriptInterface public void sendPasswordReset(String email) {
            runOnUiThread(() -> {
                if (email == null || email.trim().isEmpty()) {
                    notifyWeb("Enter your email address to reset your password.");
                    return;
                }
                auth.sendPasswordResetEmail(email.trim()).addOnCompleteListener(MainActivity.this, task -> {
                    if (task.isSuccessful()) {
                        notifyWeb("If an account exists for that email, password-reset instructions have been sent.");
                    } else {
                        notifyWeb(friendlyAuthError(task.getException(), "Unable to send password-reset instructions. Try again."));
                    }
                });
            });
        }

        @JavascriptInterface public void updatePassword(String currentPassword, String newPassword) {
            runOnUiThread(() -> {
                FirebaseUser user = auth.getCurrentUser();
                if (user == null || user.getEmail() == null) {
                    notifyWeb("Please sign in again before updating your password.");
                    return;
                }
                if (currentPassword == null || currentPassword.isEmpty() || newPassword == null || newPassword.length() < 6) {
                    notifyWeb("Enter your current password and a new password with at least 6 characters.");
                    return;
                }
                AuthCredential credential = EmailAuthProvider.getCredential(user.getEmail(), currentPassword);
                user.reauthenticate(credential).addOnCompleteListener(MainActivity.this, reauthTask -> {
                    if (!reauthTask.isSuccessful()) {
                        notifyWeb(friendlyAuthError(reauthTask.getException(), "Your current password could not be verified."));
                        return;
                    }
                    user.updatePassword(newPassword).addOnCompleteListener(MainActivity.this, updateTask -> {
                        if (updateTask.isSuccessful()) {
                            notifyWeb("Password updated successfully.");
                        } else {
                            notifyWeb(friendlyAuthError(updateTask.getException(), "Unable to update password. Sign in again and retry."));
                        }
                    });
                });
            });
        }
    }

    /**
     * Firebase email verification is still sent - it is simply no longer a gate on entering the
     * app. After registration the citizen continues into the app and is told the email is on its
     * way, instead of being parked on the unused phone-OTP Stitch screen.
     */
    private void sendVerificationEmail(FirebaseUser user, boolean afterRegistration) {
        user.sendEmailVerification().addOnCompleteListener(this, task -> {
            String message = task.isSuccessful()
                    ? "Verification email sent. Check your inbox and spam folder."
                    : friendlyAuthError(task.getException(), "Unable to send verification email. Try again.");
            if (afterRegistration) {
                pendingWebMessage = message;
                routeAfterAuthentication(true);
            } else {
                notifyWeb(message);
            }
        });
    }

    /**
     * Where a citizen lands after Firebase email/password authentication succeeds:
     * the permissions step on first registration, otherwise the citizen dashboard.
     * There is no phone, SMS or OTP step in this flow.
     */
    private void routeAfterAuthentication(boolean afterRegistration) {
        // The citizen sign-in path never opens government screens; that needs governmentLogin.
        governmentVerifiedUid = "";
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            loadScreenUnchecked("login_screen", false);
            return;
        }
        ensureFirestoreProfile();
        loadScreenUnchecked(afterRegistration ? "app_permissions" : "home_screen", false);
        refreshVerificationState();
    }

    // ------------------------------------------------------------------
    // Phase 2 — complaint creation flow (camera, location, submission)
    // ------------------------------------------------------------------

    /** Camera only. Checks the runtime permission first; never opens a picker. */
    private void startCameraCapture() {
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.CAMERA }, REQ_CAMERA_PERMISSION);
            return;
        }
        launchCamera();
    }

    private void launchCamera() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) == null) {
            notifyWeb("No camera app is available on this device.");
            return;
        }

        File file;
        try {
            file = localPhotoRepository.createPhotoFile();
        } catch (IOException error) {
            notifyWeb("Could not create a file for the photo. " + (error.getMessage() == null ? "" : error.getMessage()));
            return;
        }

        Uri output;
        try {
            output = localPhotoRepository.contentUriFor(file);
        } catch (Exception error) {
            localPhotoRepository.deleteQuietly(file);
            notifyWeb("Could not prepare the photo file for the camera.");
            return;
        }

        intent.putExtra(MediaStore.EXTRA_OUTPUT, output);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            pendingPhotoFile = file;
            startActivityForResult(intent, REQ_CAMERA);
        } catch (Exception error) {
            pendingPhotoFile = null;
            localPhotoRepository.deleteQuietly(file);
            notifyWeb("The camera could not be opened on this device.");
        }
    }

    /**
     * Captures a real device position. Coordinates are never invented: when permission is refused
     * or no fix arrives, the draft keeps null coordinates and the citizen can still submit.
     */
    private void startLocationCapture(boolean mayRequestPermission) {
        if (!locationProvider.hasPermission()) {
            if (mayRequestPermission && android.os.Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION }, REQ_LOCATION_PERMISSION);
                return;
            }
            complaintDraft.clearCoordinates();
            complaintDraft.setLocationStatus("Location permission is not granted. The report will be submitted without coordinates.");
            publishLocationStatus();
            return;
        }

        complaintDraft.setLocationStatus("Getting your location...");
        publishLocationStatus();

        // A pin the citizen places on the map while this fix is pending wins over the late fix.
        final int request = ++complaintLocationRequest;
        locationProvider.requestCurrentLocation(LOCATION_TIMEOUT_MS, new LocationProvider.LocationResultCallback() {
            @Override public void onLocation(double latitude, double longitude, String address) {
                if (request != complaintLocationRequest) return;
                complaintDraft.setCoordinates(latitude, longitude);
                complaintDraft.setAddress(address);
                complaintDraft.setLocationStatus(address.isEmpty()
                        ? String.format(Locale.US, "Location captured: %.5f, %.5f", latitude, longitude)
                        : address);
                publishLocationStatus();
            }
            @Override public void onUnavailable(String reason) {
                if (request != complaintLocationRequest) return;
                complaintDraft.clearCoordinates();
                complaintDraft.setLocationStatus(reason + " The report will be submitted without coordinates.");
                publishLocationStatus();
            }
        });
    }

    private void publishLocationStatus() {
        String quoted = JSONObject.quote(complaintDraft.getLocationStatus());
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapLocation && window.chipchapLocation(" + quoted + ");", null));
    }

    // ---------- Checkpoint 2: possible duplicate reports ----------

    private void resetSimilarCheck() {
        similarCheckGeneration++;
        similarCheckKey = "";
        similarCheckRunning = false;
        similarCheckPayload = "";
    }

    /** Same photo + same location + same category = same check; anything else is a new draft state. */
    private String similarDraftKey() {
        Double lat = complaintDraft.getLatitude(), lng = complaintDraft.getLongitude();
        String category = DuplicateReportFinder.normalizeCategory(complaintDraft.getCategory());
        if (!DuplicateReportFinder.validCoordinate(lat, lng) || category == null) return "";
        return complaintDraft.getLocalImagePath() + "|" + String.format(Locale.US, "%.6f,%.6f", lat, lng) + "|" + category;
    }

    private static String similarJson(String state, String message) {
        try {
            return new JSONObject().put("state", state).put("message", message)
                    .put("radiusMeters", (int) DuplicateReportFinder.MATCH_RADIUS_METERS)
                    .put("candidates", new JSONArray()).toString();
        } catch (JSONException error) {
            return "{\"state\":\"error\"}";
        }
    }

    private void startSimilarCheck(int seq) {
        similarCheckSeq = seq;
        if (auth.getCurrentUser() == null) {
            publishSimilar(seq, similarJson("error", "Couldn't check for similar reports. You can still submit your report."));
            return;
        }
        Double lat = complaintDraft.getLatitude(), lng = complaintDraft.getLongitude();
        // Nothing is invented: without a real location or a real category there is nothing to match.
        if (!DuplicateReportFinder.validCoordinate(lat, lng)) {
            publishSimilar(seq, similarJson("skipped", "Similar reports were not checked because this report has no incident location."));
            return;
        }
        final String category = DuplicateReportFinder.normalizeCategory(complaintDraft.getCategory());
        if (category == null) {
            publishSimilar(seq, similarJson("skipped", "Similar reports were not checked because no category is selected."));
            return;
        }
        final String key = similarDraftKey();
        if (key.equals(similarCheckKey)) {
            // One check per draft state: a running check answers this page when it ends, and a
            // finished one is reused (returning from a candidate does not query again).
            if (similarCheckRunning) { publishSimilar(seq, similarJson("checking", "")); return; }
            if (!similarCheckPayload.isEmpty()) { publishSimilar(seq, similarCheckPayload); return; }
        }
        final int generation = ++similarCheckGeneration;
        final double latitude = lat, longitude = lng;
        similarCheckKey = key;
        similarCheckRunning = true;
        similarCheckPayload = "";
        publishSimilar(seq, similarJson("checking", ""));
        double band = DuplicateReportFinder.latitudeBandDegrees();
        Log.i(FLOW_TAG, "SIMILAR_CHECK_START gen=" + generation);
        complaintRepository.loadReportsInLatitudeBand(latitude - band, latitude + band, DuplicateReportFinder.QUERY_LIMIT,
                new RepositoryCallback<List<Complaint>>() {
                    @Override public void onSuccess(List<Complaint> reports) {
                        if (generation != similarCheckGeneration || isDestroyed()) return;
                        similarCheckRunning = false;
                        if (!key.equals(similarDraftKey())) {
                            // The draft moved on while Firestore answered: this result describes an old state.
                            similarCheckKey = "";
                            Log.i(FLOW_TAG, "SIMILAR_CHECK_STALE gen=" + generation);
                            return;
                        }
                        List<DuplicateReportFinder.Record> records = new java.util.ArrayList<>();
                        for (Complaint r : reports) {
                            if (r == null) continue;
                            records.add(new DuplicateReportFinder.Record(r.getReportId(), r.getTitle(), r.getCategory(),
                                    r.getStatus(), r.getLatitude(), r.getLongitude(), millis(r.getCreatedAt())));
                        }
                        List<DuplicateReportFinder.Candidate> found = DuplicateReportFinder.find(latitude, longitude, category, records);
                        boolean limitReached = reports.size() >= DuplicateReportFinder.QUERY_LIMIT;
                        String payload;
                        try {
                            JSONArray items = new JSONArray();
                            for (DuplicateReportFinder.Candidate c : found) {
                                // Only what the citizen may see and needs: no reporter, uid, email,
                                // evidence URL or storage path.
                                items.put(new JSONObject()
                                        .put("reportId", c.reportId)
                                        .put("title", clip(c.title, 120))
                                        .put("category", c.category)
                                        .put("status", c.status)
                                        .put("distanceMeters", Math.round(c.distanceMeters))
                                        .put("createdAt", c.createdAtMs));
                            }
                            payload = new JSONObject()
                                    .put("state", found.isEmpty() ? "none" : "found")
                                    .put("message", "")
                                    .put("radiusMeters", (int) DuplicateReportFinder.MATCH_RADIUS_METERS)
                                    .put("limitReached", limitReached)
                                    .put("checked", reports.size())
                                    .put("candidates", items).toString();
                        } catch (JSONException error) {
                            payload = similarJson("error", "Couldn't check for similar reports. You can still submit your report.");
                        }
                        similarCheckPayload = payload;
                        Log.i(FLOW_TAG, "SIMILAR_CHECK_DONE gen=" + generation + " candidates=" + found.size()
                                + " read=" + reports.size() + " limitReached=" + limitReached);
                        publishSimilar(similarCheckSeq, payload);
                    }
                    @Override public void onFailure(Exception error) {
                        if (generation != similarCheckGeneration || isDestroyed()) return;
                        similarCheckRunning = false;
                        similarCheckKey = "";   // not cached: the next visit tries again
                        Log.w(FLOW_TAG, "SIMILAR_CHECK_FAILED gen=" + generation + " " + error.getClass().getSimpleName());
                        // A failure is never reported as "no similar reports".
                        publishSimilar(similarCheckSeq, similarJson("error",
                                "Couldn't check for similar reports. You can still submit your report."));
                    }
                });
    }

    /** Answers only the review step that asked; any other screen ignores it. */
    private void publishSimilar(int seq, String payload) {
        if (web == null || isDestroyed() || !"review_report".equals(currentScreen)) return;
        web.evaluateJavascript("window.chipchapSimilarReports && window.chipchapSimilarReports("
                + seq + "," + JSONObject.quote(payload) + ");", null);
    }

    private void startComplaintSubmission() {
        if (submissionInProgress) {
            notifyWeb("Your report is already being submitted.");
            return;
        }
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            failSubmission("You must be signed in to submit a report.");
            return;
        }
        if (!complaintDraft.hasPhoto()) {
            failSubmission("Capture a photo of the issue before submitting.");
            return;
        }
        if (!photoCheckAllowsContinue()) {
            failSubmission("The photo has not passed the photo check yet.");
            return;
        }
        if (complaintDraft.getTitle().isEmpty() || complaintDraft.getDescription().isEmpty()
                || complaintDraft.getCategory().isEmpty()) {
            failSubmission("Complete the report details before submitting.");
            return;
        }

        submissionInProgress = true;
        loadScreenUnchecked("uploading", false);

        // If the Cloudinary upload started at capture time is still running, wait for it here
        // rather than losing the URL. The uploading screen is already showing. Whichever way the
        // upload ends, the report is still submitted - only the cloud URL is affected.
        if (complaintDraft.isEvidenceUploading()) {
            submissionWaitingForEvidence = true;
            return;
        }
        continueComplaintSubmission();
    }

    /** Resumes a submission that was waiting on the evidence upload. */
    private void runPendingSubmission() {
        if (!submissionWaitingForEvidence) return;
        submissionWaitingForEvidence = false;
        if (!submissionInProgress) return;
        continueComplaintSubmission();
    }

    private void continueComplaintSubmission() {
        // Email verification is no longer required to submit (product decision, 2026-09-23).
        // The profile is bootstrapped first, because an account created directly in the Firebase
        // console has an Auth user but no users/{uid} document, and the rest of the app expects one.
        userRepository.ensureProfile(new RepositoryCallback<UserProfile>() {
            @Override public void onSuccess(UserProfile result) {
                citizenProfile = result;
                publishCitizenProfile();
                writeComplaint();
            }
            @Override public void onFailure(Exception error) {
                failSubmission("Your report was not submitted. Your CHIPCHAP profile could not be prepared. "
                        + FirestoreErrors.describe(error, "Check your connection and try again."));
            }
        });
    }

    /** Only content fields are copied; ownership and status are set inside ComplaintRepository. */
    private void writeComplaint() {
        Complaint draft = new Complaint();
        draft.setTitle(complaintDraft.getTitle());
        draft.setDescription(complaintDraft.getDescription());
        draft.setCategory(complaintDraft.getCategory());
        draft.setSeverity(complaintDraft.getSeverity());
        draft.setLatitude(complaintDraft.getLatitude());
        draft.setLongitude(complaintDraft.getLongitude());
        draft.setAddress(complaintDraft.getAddress());
        draft.setLocalImagePath(complaintDraft.getLocalImagePath());
        // The Cloudinary URL produced by the upload that started at capture time has to travel
        // with the draft. Without this the repository read an unset field, cloudUrlOrEmpty("")
        // returned "", and every report was stored with no evidenceUrl however well the upload
        // had gone. The repository still validates the value; nothing here is trusted blindly.
        draft.setEvidenceUrl(complaintDraft.getEvidenceUrl());

        complaintRepository.submitComplaint(draft, new RepositoryCallback<Complaint>() {
            @Override public void onSuccess(Complaint result) {
                if (result == null || result.getReportId() == null || result.getReportId().isEmpty()) {
                    failSubmission("The report was written but no tracking ID came back. Check before resubmitting.");
                    return;
                }
                writeInitialTimeline(result.getReportId());
            }
            @Override public void onFailure(Exception error) {
                failSubmission("Your report was not submitted. "
                        + FirestoreErrors.describe(error, "Check your connection and try again."));
            }
        });
    }

    /** Written only after the report document exists, never before. */
    private void writeInitialTimeline(String reportId) {
        timelineRepository.addEvent(reportId, Complaint.STATUS_SUBMITTED,
                "Report submitted by the citizen.", new RepositoryCallback<TimelineEvent>() {
                    @Override public void onSuccess(TimelineEvent result) {
                        completeSubmission(reportId, "");
                    }
                    @Override public void onFailure(Exception error) {
                        // The report exists, so claiming total failure would be false. The missing
                        // history entry is reported explicitly instead of being swallowed.
                        completeSubmission(reportId, "Your report was created, but its history entry could not be written. "
                                + FirestoreErrors.describe(error, "Tracking may show no events until this is retried."));
                    }
                });
    }

    private void completeSubmission(String reportId, String warning) {
        submissionInProgress = false;
        submissionWaitingForEvidence = false;
        lastSubmittedReportId = reportId;
        // The draft is cleared; the photo file stays on the device for a later phase.
        complaintDraft.clear();
        resetPhotoCheck();
        photoPreviewDataUri = "";
        pendingWebMessage = warning;
        // Back from the success screen must not re-enter a flow whose draft has been cleared.
        history.clear();
        history.push("home_screen");
        loadScreenUnchecked("success", false);
    }

    /** Never navigates to success, and never pretends the report was created. */
    private void failSubmission(String message) {
        submissionInProgress = false;
        submissionWaitingForEvidence = false;
        if ("review_report".equals(currentScreen)) {
            notifyWeb(message);
        } else {
            pendingWebMessage = message;
            loadScreenUnchecked("review_report", false);
        }
    }

    /** Replaces the demo tracking ID on the existing success screen with the real document id. */
    private void applyRealTrackingId() {
        if (lastSubmittedReportId == null || lastSubmittedReportId.isEmpty()) return;
        String quoted = JSONObject.quote(lastSubmittedReportId);
        web.evaluateJavascript("window.chipchapSetTrackingId && window.chipchapSetTrackingId(" + quoted + ");", null);
    }

    // ---------- profile ----------

    /** Firestore fullName, else the Auth display name, else the email local part. Never invented. */
    private String citizenDisplayName() {
        UserProfile profile = citizenProfile;
        if (profile != null && profile.getFullName() != null && !profile.getFullName().trim().isEmpty()) {
            return profile.getFullName().trim();
        }
        FirebaseUser user = auth.getCurrentUser();
        if (user != null) {
            if (user.getDisplayName() != null && !user.getDisplayName().trim().isEmpty()) {
                return user.getDisplayName().trim();
            }
            String email = user.getEmail();
            if (email != null && email.indexOf('@') > 0) {
                String local = email.substring(0, email.indexOf('@')).trim();
                if (!local.isEmpty()) return local;
            }
        }
        return "";
    }

    private String buildCitizenProfileJson() {
        UserProfile profile = citizenProfile;
        FirebaseUser user = auth.getCurrentUser();
        try {
            JSONObject json = new JSONObject();
            json.put("signedIn", user != null);
            json.put("displayName", citizenDisplayName());
            json.put("fullName", profile == null || profile.getFullName() == null ? "" : profile.getFullName());
            json.put("neighborhood", profile == null || profile.getNeighborhood() == null ? "" : profile.getNeighborhood());
            json.put("email", user == null || user.getEmail() == null ? "" : user.getEmail());
            json.put("loaded", profile != null);
            return json.toString();
        } catch (JSONException error) {
            return "{}";
        }
    }

    private void publishCitizenProfile() {
        final String json = buildCitizenProfileJson();
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapProfile && window.chipchapProfile(" + JSONObject.quote(json) + ");", null));
    }

    // ---------- community ----------

    private void publishCommunityFeed(List<Complaint> reports, String error) {
        publishReports("chipchapCommunityFeed", reports, error);
    }

    private void publishReports(String callback, List<Complaint> reports, String error) {
        String payload;
        try {
            JSONObject json = new JSONObject();
            json.put("error", error == null ? "" : error);
            JSONArray items = new JSONArray();
            if (reports != null) {
                for (Complaint report : reports) {
                    if (report == null) continue;
                    JSONObject item = new JSONObject();
                    item.put("reportId", safe(report.getReportId()));
                    item.put("title", safe(report.getTitle()));
                    item.put("description", safe(report.getDescription()));
                    item.put("category", safe(report.getCategory()));
                    item.put("severity", safe(report.getSeverity()));
                    item.put("status", safe(report.getStatus()));
                    item.put("address", safe(report.getAddress()));
                    item.put("citizenName", safe(report.getCitizenName()));
                    item.put("evidenceUrl", safe(report.getEvidenceUrl()));
                    item.put("resolutionEvidenceUrl", safe(report.getResolutionEvidenceUrl()));
                    putReportFacts(item, report);
                    items.put(item);
                }
            }
            json.put("reports", items);
            payload = json.toString();
        } catch (JSONException jsonError) {
            payload = "{\"error\":\"Could not read community reports.\",\"reports\":[]}";
        }
        final String finalPayload = payload;
        runOnUiThread(() -> web.evaluateJavascript(
                "window." + callback + " && window." + callback + "("
                        + JSONObject.quote(finalPayload) + ");", null));
    }

    /** Reads the stored vote state and comments, then pushes both to the WebView together. */
    private void refreshInteractions(final String reportId, final String error) {
        voteRepository.loadVoteState(reportId, new RepositoryCallback<VoteRepository.VoteState>() {
            @Override public void onSuccess(VoteRepository.VoteState votes) {
                loadCommentsThenPublish(reportId, votes.count, votes.votedByCurrentUser, error);
            }
            @Override public void onFailure(Exception voteError) {
                loadCommentsThenPublish(reportId, -1, false,
                        error.isEmpty() ? FirestoreErrors.describe(voteError, "Could not read votes.") : error);
            }
        });
    }

    private void loadCommentsThenPublish(final String reportId, final int voteCount,
                                         final boolean voted, final String error) {
        commentRepository.loadComments(reportId, new RepositoryCallback<List<Comment>>() {
            @Override public void onSuccess(List<Comment> comments) {
                publishInteractions(reportId, voteCount, voted, comments, error);
            }
            @Override public void onFailure(Exception commentError) {
                publishInteractions(reportId, voteCount, voted, null,
                        error.isEmpty() ? FirestoreErrors.describe(commentError, "Could not read comments.") : error);
            }
        });
    }

    private void publishInteractions(String reportId, int voteCount, boolean voted,
                                     List<Comment> comments, String error) {
        String payload;
        try {
            JSONObject json = new JSONObject();
            json.put("reportId", safe(reportId));
            json.put("voteCount", voteCount);
            json.put("voted", voted);
            json.put("error", error == null ? "" : error);
            JSONArray items = new JSONArray();
            if (comments != null) {
                for (Comment comment : comments) {
                    if (comment == null) continue;
                    JSONObject item = new JSONObject();
                    item.put("authorName", safe(comment.getAuthorName()));
                    item.put("text", safe(comment.getText()));
                    items.put(item);
                }
            }
            json.put("comments", items);
            json.put("commentsLoaded", comments != null);
            payload = json.toString();
        } catch (JSONException jsonError) {
            payload = "{\"error\":\"Could not read this report's activity.\"}";
        }
        final String finalPayload = payload;
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapInteractions && window.chipchapInteractions("
                        + JSONObject.quote(finalPayload) + ");", null));
    }

    private void publishReportDetail(Complaint report, List<TimelineEvent> events, String error) {
        String payload;
        try {
            JSONObject json = new JSONObject();
            json.put("error", error == null ? "" : error);
            json.put("found", report != null);
            if (report != null) {
                JSONObject r = new JSONObject();
                r.put("reportId", safe(report.getReportId()));
                r.put("title", safe(report.getTitle()));
                r.put("description", safe(report.getDescription()));
                r.put("category", safe(report.getCategory()));
                r.put("severity", safe(report.getSeverity()));
                r.put("status", safe(report.getStatus()));
                r.put("address", safe(report.getAddress()));
                r.put("citizenName", safe(report.getCitizenName()));
                r.put("evidenceUrl", safe(report.getEvidenceUrl()));
                r.put("resolutionEvidenceUrl", safe(report.getResolutionEvidenceUrl()));
                r.put("assignedGovernmentName", safe(report.getAssignedGovernmentName()));
                putReportFacts(r, report);
                json.put("report", r);
            }
            JSONArray items = new JSONArray();
            if (events != null) {
                for (TimelineEvent event : events) {
                    if (event == null) continue;
                    JSONObject item = new JSONObject();
                    item.put("status", safe(event.getStatus()));
                    item.put("message", safe(event.getMessage()));
                    item.put("actorName", safe(event.getActorName()));
                    item.put("createdAt", millis(event.getCreatedAt()));
                    items.put(item);
                }
            }
            json.put("timeline", items);
            json.put("timelineLoaded", events != null);
            payload = json.toString();
        } catch (JSONException jsonError) {
            payload = "{\"error\":\"That report could not be read.\",\"found\":false}";
        }
        final String finalPayload = payload;
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapReportDetail && window.chipchapReportDetail("
                        + JSONObject.quote(finalPayload) + ");", null));
    }

    /**
     * Shared path for government actions: gate on the verified session, re-check the GOVERNMENT
     * claim on a FRESH ID token, read the report from Firestore, then commit report update +
     * timeline event + citizen notification in one batch.
     */
    private void runGovernmentAction(String reportId, String requestedStatus, boolean assign,
                                     String department, String message) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || !hasGovernmentAccess()) { notifyWeb("Government access is required."); return; }
        if (reportId == null || reportId.trim().isEmpty()) { notifyWeb("No report is selected."); return; }
        if (message.length() > 500) { notifyWeb("Keep the note under 500 characters."); return; }
        if (governmentActionInProgress) { notifyWeb("An update is already being saved."); return; }
        governmentActionInProgress = true;
        user.getIdToken(true).addOnCompleteListener(this, tokenTask -> {
            if (!tokenTask.isSuccessful() || !isGovernmentToken(tokenTask.getResult()) || auth.getCurrentUser() == null) {
                governmentActionInProgress = false;
                notifyWeb("Government access could not be verified. Nothing was changed.");
                return;
            }
            final FirebaseUser actor = auth.getCurrentUser();
            complaintRepository.loadComplaint(reportId.trim(), new RepositoryCallback<Complaint>() {
                @Override public void onSuccess(Complaint report) {
                    if (report == null) { governmentActionInProgress = false; notifyWeb("That report no longer exists."); return; }
                    String current = report.getStatus() == null ? "SUBMITTED" : report.getStatus().trim().toUpperCase(Locale.US);
                    String status = requestedStatus;
                    if (assign) {
                        status = ("SUBMITTED".equals(current) || "UNDER_REVIEW".equals(current) || "ASSIGNED".equals(current))
                                ? "ASSIGNED" : current;
                        if (!ComplaintRepository.GOVERNMENT_STATUSES.contains(status)) status = "ASSIGNED";
                    } else if (status.equals(current)) {
                        governmentActionInProgress = false;
                        notifyWeb("This report is already " + NotificationRepository.statusLabel(status) + ". Nothing was changed.");
                        return;
                    }
                    final String finalStatus = status;
                    // The after-photo counts only when it was captured for THIS report and its
                    // upload finished. A photo captured for another report is never reused.
                    boolean photoForThisReport = !governmentAfterPhotoPath.isEmpty()
                            && reportId.trim().equals(governmentAfterPhotoReportId);
                    if (photoForThisReport && governmentAfterPhotoUploading) {
                        governmentActionInProgress = false;
                        notifyWeb("The resolution photo is still uploading. Nothing was changed.");
                        return;
                    }
                    // The resolution photo is saved only with a RESOLVED transition (Firestore rules
                    // allow resolutionEvidenceUrl only then). It is never silently discarded.
                    if (photoForThisReport && !"RESOLVED".equals(finalStatus)) {
                        governmentActionInProgress = false;
                        notifyWeb("A resolution photo is saved only with the status Resolved. Choose Resolved, "
                                + "or reopen this screen to discard the photo. Nothing was changed.");
                        return;
                    }
                    final String resolutionUrl = (photoForThisReport && "RESOLVED".equals(finalStatus))
                            ? governmentAfterPhotoUrl : "";
                    // Resolving WITH an after-photo is all-or-nothing: if the Cloudinary upload did
                    // not produce a URL, nothing at all is committed - no status change, no timeline
                    // event, no citizen notification. The local file stays on this device but is
                    // never represented as uploaded. Resolving WITHOUT a photo is unaffected.
                    if (photoForThisReport && "RESOLVED".equals(finalStatus) && resolutionUrl.isEmpty()) {
                        governmentActionInProgress = false;
                        notifyWeb("The resolution photo could not be uploaded, so nothing was changed. "
                                + (governmentAfterPhotoError.isEmpty()
                                    ? "Retake the photo and try again."
                                    : governmentAfterPhotoError + " Retake the photo and try again."));
                        return;
                    }
                    final String type = assign ? NotificationRepository.TYPE_ASSIGNED
                            : "RESOLVED".equals(finalStatus) ? NotificationRepository.TYPE_RESOLVED
                            : "ASSIGNED".equals(finalStatus) ? NotificationRepository.TYPE_ASSIGNED
                            : NotificationRepository.TYPE_STATUS_CHANGED;
                    userRepository.loadProfile(new RepositoryCallback<UserProfile>() {
                        @Override public void onSuccess(UserProfile profile) { commit(profile); }
                        @Override public void onFailure(Exception error) { commit(null); }
                        private void commit(UserProfile profile) {
                            String actorName = profile != null && profile.getFullName() != null && !profile.getFullName().trim().isEmpty()
                                    ? profile.getFullName().trim()
                                    : (actor.getDisplayName() != null && !actor.getDisplayName().trim().isEmpty()
                                        ? actor.getDisplayName().trim() : (actor.getEmail() == null ? "" : actor.getEmail()));
                            complaintRepository.applyGovernmentUpdate(report, finalStatus,
                                    assign ? actor.getUid() : null, assign ? department : null,
                                    resolutionUrl,
                                    message, actorName, type, notificationRepository, new RepositoryCallback<Void>() {
                                        @Override public void onSuccess(Void ignored) {
                                            governmentActionInProgress = false;
                                            clearGovernmentAfterPhotoState();
                                            pendingWebMessage = assign
                                                    ? "Report assigned to " + department + ". The citizen has been notified."
                                                    : "Status updated to " + NotificationRepository.statusLabel(finalStatus) + ". The citizen has been notified.";
                                            goBackScreen();
                                        }
                                        @Override public void onFailure(Exception error) {
                                            governmentActionInProgress = false;
                                            notifyWeb("Nothing was changed. " + FirestoreErrors.describe(error, "The update could not be saved."));
                                        }
                                    });
                        }
                    });
                }
                @Override public void onFailure(Exception error) {
                    governmentActionInProgress = false;
                    notifyWeb("Nothing was changed. " + FirestoreErrors.describe(error, "The report could not be read."));
                }
            });
        });
    }

    /**
     * Marker payload for map_explorer. Only reports that actually carry coordinates are sent, and
     * only the fields a marker, its summary card and the map's report search need (title,
     * category, status, severity, the stored address and description) - no citizen identity,
     * no assignment, no evidence URLs and no other government-side data.
     */
    private void publishMapReports(boolean government, List<Complaint> reports, String error) {
        String payload;
        try {
            JSONObject json = new JSONObject();
            json.put("mode", government ? "GOVERNMENT" : "CITIZEN");
            json.put("error", error == null ? "" : error);
            json.put("limit", government ? GOVERNMENT_REPORT_LIMIT : MAP_REPORT_LIMIT);
            JSONArray points = new JSONArray();
            if (reports != null) {
                for (Complaint report : reports) {
                    if (report == null) continue;
                    Double latitude = report.getLatitude();
                    Double longitude = report.getLongitude();
                    if (latitude == null || longitude == null) continue;
                    double lat = latitude;
                    double lng = longitude;
                    if (lat < -90 || lat > 90 || lng < -180 || lng > 180) continue;
                    if (lat == 0 && lng == 0) continue;
                    JSONObject point = new JSONObject();
                    point.put("reportId", safe(report.getReportId()));
                    point.put("title", safe(report.getTitle()));
                    point.put("category", safe(report.getCategory()));
                    point.put("status", safe(report.getStatus()));
                    point.put("severity", safe(report.getSeverity()));
                    // Searchable text already stored on the report (no extra read): its saved
                    // address/place and description. Still no citizen identity or evidence.
                    point.put("address", safe(report.getAddress()));
                    point.put("description", clip(safe(report.getDescription()), MAP_SEARCH_TEXT_MAX));
                    point.put("lat", lat);
                    point.put("lng", lng);
                    points.put(point);
                }
            }
            json.put("points", points);
            payload = json.toString();
        } catch (JSONException jsonError) {
            payload = "{\"mode\":\"CITIZEN\",\"points\":[],\"error\":\"Report locations could not be read.\"}";
        }
        final String finalPayload = payload;
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapMapReports && window.chipchapMapReports("
                        + JSONObject.quote(finalPayload) + ");", null));
    }

    /**
     * refine = this fix follows a recent position already sent for the same tap;
     * quiet = a failure the map does not need to announce (it already shows a real position).
     */
    private void publishMapLocation(boolean ok, double latitude, double longitude, String message,
                                    boolean refine, boolean quiet) {
        String payload;
        try {
            JSONObject json = new JSONObject();
            json.put("ok", ok);
            json.put("lat", latitude);
            json.put("lng", longitude);
            json.put("message", message == null ? "" : message);
            json.put("refine", refine);
            json.put("quiet", quiet);
            payload = json.toString();
        } catch (JSONException jsonError) {
            payload = "{\"ok\":false,\"message\":\"Your location could not be read.\"}";
        }
        final String finalPayload = payload;
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapMapLocation && window.chipchapMapLocation("
                        + JSONObject.quote(finalPayload) + ");", null));
    }

    // ---------- Checkpoint F1/F2: government route preview + place search (Geoapify) ----------
    // Flow: explicit tap -> destination (a report's STORED coordinates, read here, or a searched
    // place held in placeCache) -> origin (the device position through the existing
    // LocationProvider by default, or a searched place the user chose) -> one Geoapify request ->
    // route drawn on the existing map. Nothing is ever requested automatically.

    private static boolean validRouteCoordinate(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) return false;
        double lat = latitude;
        double lng = longitude;
        return !Double.isNaN(lat) && !Double.isInfinite(lat) && !Double.isNaN(lng) && !Double.isInfinite(lng)
                && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 && !(lat == 0 && lng == 0);
    }

    /**
     * UI thread. reportId non-empty = route to that report; otherwise destPlaceId names a searched
     * place. originPlaceId "" = the device location, otherwise a searched place.
     */
    private void startRoute(String rawReportId, String rawDestPlaceId, String rawOriginPlaceId, boolean mayRequestPermission) {
        if (!hasMapRouteAccess()) { notifyWeb("Please sign in to preview routes."); return; }
        final String reportId = rawReportId == null ? "" : rawReportId.trim();
        final String destPlaceId = rawDestPlaceId == null ? "" : rawDestPlaceId.trim();
        final String originPlaceId = rawOriginPlaceId == null ? "" : rawOriginPlaceId.trim();
        if (routeService == null || !routeService.isConfigured()) {
            Log.w(ROUTE_TAG, "ROUTE_NOT_CONFIGURED: GEOAPIFY_API_KEY is missing from local.properties");
            notifyWeb(GeoapifyRouteService.MSG_NOT_CONFIGURED);
            return;
        }
        final GeoapifyRouteService.Place originPlace = originPlaceId.isEmpty() ? null : placeCache.get(originPlaceId);
        if (!originPlaceId.isEmpty() && originPlace == null) {
            notifyWeb("That starting point is no longer available. Search for it again.");
            return;
        }
        final GeoapifyRouteService.Place destPlace;
        if (!reportId.isEmpty()) {
            if (reportId.length() > 200 || reportId.indexOf('/') >= 0) { notifyWeb("Open a report before requesting a route."); return; }
            destPlace = null;
        } else {
            destPlace = destPlaceId.isEmpty() ? null : placeCache.get(destPlaceId);
            if (destPlace == null) {
                notifyWeb(destPlaceId.isEmpty() ? "Choose a destination first." : "That destination is no longer available. Search for it again.");
                return;
            }
        }
        final String destKey = destPlace == null ? "" : destPlaceId;
        // Repeated taps for the route already being calculated never start a second request.
        if (ROUTE_LOADING.equals(routeState) && reportId.equals(routeReportId)
                && destKey.equals(routeDestPlaceId) && originPlaceId.equals(routeOriginPlaceId)) {
            Log.i(ROUTE_TAG, "ROUTE_DUPLICATE_IGNORED gen=" + routeGeneration);
            if (!SHARED_MAP_SCREEN.equals(currentScreen)) loadScreen(SHARED_MAP_SCREEN, true);
            return;
        }
        // Only the device-location origin needs the permission; a chosen place never does.
        if (originPlace == null && !locationProvider.hasPermission()) {
            if (mayRequestPermission) {
                pendingRouteReportId = reportId;
                pendingRouteDestPlaceId = destKey;
                requestPermissions(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION }, REQ_ROUTE_LOCATION_PERMISSION);
            } else {
                notifyWeb("Location permission is required to calculate a route from your location. You can choose a starting point instead.");
            }
            return;
        }
        // A new request supersedes any older one, for this destination or another.
        final int generation = ++routeGeneration;
        routeState = ROUTE_LOADING;
        routeReportId = reportId;
        routeDestPlaceId = destKey;
        routeOriginPlaceId = originPlaceId;
        routeOriginLabel = originPlace == null ? "" : originPlace.name;
        routeTitle = destPlace == null ? "" : destPlace.name;
        routeOriginAddress = originPlace == null ? "" : originPlace.address;
        routeDestAddress = destPlace == null ? "" : destPlace.address;
        clearCalculatedRoute();
        final double[] originFixed = originPlace == null ? null : new double[]{ originPlace.lat, originPlace.lng };
        final double[] placeDestination = destPlace == null ? null : new double[]{ destPlace.lat, destPlace.lng };
        routePayload = routeJson(generation, ROUTE_LOADING, "", null, originFixed, placeDestination);
        Log.i(ROUTE_TAG, "ROUTE_START gen=" + generation + " dest=" + (destPlace == null ? "report" : "place")
                + " origin=" + (originPlace == null ? "device" : "place"));
        if (SHARED_MAP_SCREEN.equals(currentScreen)) publishRoute();
        else loadScreen(SHARED_MAP_SCREEN, true);
        if (placeDestination != null) {
            continueRoute(generation, originFixed, placeDestination);
            return;
        }
        // Report destination = its STORED coordinates, read here - never geocoded, never from JS.
        complaintRepository.loadComplaint(reportId, new RepositoryCallback<Complaint>() {
            @Override public void onSuccess(Complaint report) {
                if (generation != routeGeneration) return;
                if (report == null) {
                    routeFailed(generation, "That report could not be found, so no route was calculated.");
                    return;
                }
                routeTitle = safe(report.getTitle());
                routeDestAddress = safe(report.getAddress()).trim();
                if (!validRouteCoordinate(report.getLatitude(), report.getLongitude())) {
                    routeFailed(generation, "This report has no valid location, so no route can be calculated.");
                    return;
                }
                final double[] destination = { report.getLatitude(), report.getLongitude() };
                routePayload = routeJson(generation, ROUTE_LOADING, "", null, originFixed, destination);
                publishRoute();
                continueRoute(generation, originFixed, destination);
            }
            @Override public void onFailure(Exception error) {
                if (generation != routeGeneration) return;
                routeFailed(generation, FirestoreErrors.describe(error, "The report could not be loaded, so no route was calculated."));
            }
        });
    }

    /** A chosen starting place is used as-is; otherwise the device position is obtained. */
    private void continueRoute(int generation, double[] originFixed, double[] destination) {
        if (generation != routeGeneration) return;
        if (originFixed != null) fetchRoute(generation, originFixed, destination);
        else acquireRouteOrigin(generation, destination);
    }

    /** Origin = the device's real position via the existing LocationProvider. Nothing is invented. */
    private void acquireRouteOrigin(final int generation, final double[] destination) {
        if (!locationProvider.hasPermission()) {
            routeFailed(generation, "Location permission is required to calculate a route from your location. You can choose a starting point instead.");
            return;
        }
        final double[] recent = locationProvider.recentLocation(ROUTE_RECENT_LOCATION_MAX_AGE_MS);
        if (recent != null && validRouteCoordinate(recent[0], recent[1])) {
            fetchRoute(generation, recent, destination);
            return;
        }
        // A fresh one-shot fix with no reverse geocoding. On timeout the provider falls back only to
        // a cached fix within its own documented 5-minute limit; otherwise it reports unavailable.
        locationProvider.requestQuickLocation(LOCATION_TIMEOUT_MS, new LocationProvider.LocationResultCallback() {
            @Override public void onLocation(double latitude, double longitude, String address) {
                if (generation != routeGeneration) return;
                if (!validRouteCoordinate(latitude, longitude)) {
                    routeFailed(generation, "Unable to determine your current location. You can choose a starting point instead.");
                    return;
                }
                fetchRoute(generation, new double[]{ latitude, longitude }, destination);
            }
            @Override public void onUnavailable(String reason) {
                if (generation != routeGeneration) return;
                routeFailed(generation, "Unable to determine your current location."
                        + (reason == null || reason.trim().isEmpty() ? "" : " " + reason.trim())
                        + " You can choose a starting point instead.");
            }
        });
    }

    private void fetchRoute(final int generation, final double[] origin, final double[] destination) {
        if (generation != routeGeneration) return;
        Log.i(ROUTE_TAG, "ROUTE_REQUEST gen=" + generation + " mode=drive");
        routeService.fetchDrivingRoute(origin[0], origin[1], destination[0], destination[1],
                new GeoapifyRouteService.RouteCallback() {
                    @Override public void onRoute(GeoapifyRouteService.Route route) {
                        if (generation != routeGeneration || isDestroyed()) return;
                        routeState = ROUTE_READY;
                        routeOriginCoords = new double[]{ origin[0], origin[1] };
                        routeDestCoords = new double[]{ destination[0], destination[1] };
                        routeDistanceMeters = route.distanceMeters;
                        routeTimeSeconds = route.timeSeconds;
                        routePayload = routeJson(generation, ROUTE_READY, "", route, origin, destination);
                        Log.i(ROUTE_TAG, "ROUTE_READY gen=" + generation);
                        publishRoute();
                    }
                    @Override public void onFailed(String message) {
                        routeFailed(generation, message);
                    }
                });
    }

    private void routeFailed(int generation, String message) {
        if (generation != routeGeneration || isDestroyed()) return;
        routeState = ROUTE_ERROR;
        String text = message == null || message.trim().isEmpty() ? GeoapifyRouteService.MSG_UNAVAILABLE : message.trim();
        routePayload = routeJson(generation, ROUTE_ERROR, text, null, null, null);
        Log.w(ROUTE_TAG, "ROUTE_FAILED gen=" + generation + " " + text);
        publishRoute();
    }

    /** Forgets the route. Anything still on its way belongs to an older generation and is dropped. */
    private void resetRouteState() {
        routeGeneration++;
        routeState = ROUTE_NONE;
        routeReportId = "";
        routeTitle = "";
        routePayload = "";
        routeOriginPlaceId = "";
        routeOriginLabel = "";
        routeDestPlaceId = "";
        pendingRouteReportId = "";
        pendingRouteDestPlaceId = "";
        routeOriginAddress = "";
        routeDestAddress = "";
        clearCalculatedRoute();
    }

    /** Forgets the values of the last calculated route so they can never describe new endpoints. */
    private void clearCalculatedRoute() {
        routeOriginCoords = null;
        routeDestCoords = null;
        routeDistanceMeters = Double.NaN;
        routeTimeSeconds = Double.NaN;
    }

    // ---------- Checkpoint F3: Copy start / Copy destination / Share route ----------
    // Text is composed natively from data CHIPCHAP actually holds: a searched place's name,
    // formatted address and coordinates; a report's stored title, address and coordinates; the
    // coordinates and Geoapify distance/time of the CALCULATED route. Nothing is guessed, no
    // street address is invented for the device position, and route contents are never logged.

    private static String routeCoordText(double lat, double lng) {
        return String.format(Locale.US, "%.6f, %.6f", lat, lng);
    }

    /** "Name, address" (address only when it adds something) - never an invented address. */
    private static String routeEndpointName(String name, String address) {
        String n = name == null ? "" : name.trim();
        String a = address == null ? "" : address.trim();
        if (n.isEmpty()) return a;
        if (a.isEmpty() || a.toLowerCase(Locale.US).contains(n.toLowerCase(Locale.US))) return a.isEmpty() ? n : a;
        return n + ", " + a;
    }

    private static String routeDistanceText(double meters) {
        if (meters < 950) return Math.round(meters) + " m";
        double km = meters / 1000.0;
        return km < 10 ? String.format(Locale.US, "%.1f km", km) : Math.round(km) + " km";
    }

    private static String routeTimeText(double seconds) {
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 1) return "under 1 min";
        if (minutes < 60) return "about " + minutes + " min";
        long hours = minutes / 60, rest = minutes % 60;
        return "about " + hours + " h" + (rest == 0 ? "" : " " + rest + " min");
    }

    private void copyRouteText(String label, String text, String confirmation) {
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) { notifyWeb("The clipboard is not available on this device."); return; }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
            notifyWeb(confirmation);
        } catch (Exception error) {
            notifyWeb("Could not copy to the clipboard.");
        }
    }

    private static boolean validRouteReportId(String reportId) {
        return reportId != null && !reportId.isEmpty() && reportId.length() <= 200 && reportId.indexOf('/') < 0;
    }

    /** UI thread. originPlaceId "" = the device location. */
    private void copyRouteStartText(String rawOriginPlaceId) {
        if (!hasMapRouteAccess()) { notifyWeb("Please sign in to use route tools."); return; }
        String originPlaceId = rawOriginPlaceId == null ? "" : rawOriginPlaceId.trim();
        if (!originPlaceId.isEmpty()) {
            GeoapifyRouteService.Place place = placeCache.get(originPlaceId);
            if (place == null) { notifyWeb("That starting point is no longer available. Search for it again."); return; }
            copyRouteText("Route start", routeEndpointName(place.name, place.address) + "\n"
                    + routeCoordText(place.lat, place.lng), "Start address copied.");
            return;
        }
        // Device origin: no reverse geocoding is done for routes, so only coordinates are real.
        // Prefer the position the current route actually used; else a very recent device fix.
        double[] position = (routeOriginPlaceId.isEmpty() && routeOriginCoords != null && ROUTE_READY.equals(routeState))
                ? routeOriginCoords : locationProvider.recentLocation(ROUTE_RECENT_LOCATION_MAX_AGE_MS);
        if (position == null || !validRouteCoordinate(position[0], position[1])) {
            notifyWeb("Your current location is not known yet. Show the route first, or choose a starting point.");
            return;
        }
        copyRouteText("Route start", "My location: " + routeCoordText(position[0], position[1]),
                "Start copied as coordinates (no street address is available for your device location).");
    }

    /** UI thread. reportId non-empty = a report destination (stored values); otherwise a searched place. */
    private void copyRouteDestinationText(String rawReportId, String rawDestPlaceId) {
        if (!hasMapRouteAccess()) { notifyWeb("Please sign in to use route tools."); return; }
        String reportId = rawReportId == null ? "" : rawReportId.trim();
        String destPlaceId = rawDestPlaceId == null ? "" : rawDestPlaceId.trim();
        if (reportId.isEmpty()) {
            GeoapifyRouteService.Place place = destPlaceId.isEmpty() ? null : placeCache.get(destPlaceId);
            if (place == null) { notifyWeb("Choose a destination first."); return; }
            copyRouteText("Route destination", routeEndpointName(place.name, place.address) + "\n"
                    + routeCoordText(place.lat, place.lng), "Destination address copied.");
            return;
        }
        if (!validRouteReportId(reportId)) { notifyWeb("Open a report first."); return; }
        // The report's own stored title, address and coordinates, read now - never a stale copy.
        complaintRepository.loadComplaint(reportId, new RepositoryCallback<Complaint>() {
            @Override public void onSuccess(Complaint report) {
                if (isDestroyed()) return;
                if (report == null) { notifyWeb("That report could not be found."); return; }
                if (!validRouteCoordinate(report.getLatitude(), report.getLongitude())) {
                    notifyWeb("This report has no stored location to copy.");
                    return;
                }
                String title = safe(report.getTitle()).trim();
                String text = "CHIPCHAP report: " + (title.isEmpty() ? "Untitled report" : title)
                        + (safe(report.getAddress()).trim().isEmpty() ? "" : "\n" + safe(report.getAddress()).trim())
                        + "\n" + routeCoordText(report.getLatitude(), report.getLongitude());
                copyRouteText("Route destination", text,
                        safe(report.getAddress()).trim().isEmpty() ? "Destination copied (stored coordinates; the report has no saved address)."
                                : "Destination address copied.");
            }
            @Override public void onFailure(Exception error) {
                notifyWeb(FirestoreErrors.describe(error, "The report could not be read, so nothing was copied."));
            }
        });
    }

    /**
     * UI thread. Shares ONLY the route currently calculated for exactly these endpoints. If the
     * user has changed the start or destination since, nothing is shared - so the previous route's
     * distance and time can never be attached to new endpoints.
     */
    private void shareCurrentRoute(String rawOriginPlaceId, String rawReportId, String rawDestPlaceId) {
        if (!hasMapRouteAccess()) { notifyWeb("Please sign in to use route tools."); return; }
        String originPlaceId = rawOriginPlaceId == null ? "" : rawOriginPlaceId.trim();
        String reportId = rawReportId == null ? "" : rawReportId.trim();
        String destPlaceId = reportId.isEmpty() ? (rawDestPlaceId == null ? "" : rawDestPlaceId.trim()) : "";
        boolean matches = ROUTE_READY.equals(routeState)
                && originPlaceId.equals(routeOriginPlaceId) && reportId.equals(routeReportId)
                && destPlaceId.equals(routeDestPlaceId)
                && routeOriginCoords != null && routeDestCoords != null
                && !Double.isNaN(routeDistanceMeters) && !Double.isNaN(routeTimeSeconds);
        if (!matches) {
            notifyWeb("Calculate the route for the current start and destination first. Nothing was shared.");
            return;
        }
        String origin = routeOriginPlaceId.isEmpty() ? "My location" : routeEndpointName(routeOriginLabel, routeOriginAddress);
        String destination = (routeReportId.isEmpty() ? "" : "CHIPCHAP report: ")
                + routeEndpointName(routeTitle.isEmpty() ? (routeReportId.isEmpty() ? "Selected place" : "Untitled report") : routeTitle, routeDestAddress);
        String from = routeCoordText(routeOriginCoords[0], routeOriginCoords[1]);
        String to = routeCoordText(routeDestCoords[0], routeDestCoords[1]);
        String link = "https://www.google.com/maps/dir/?api=1&origin="
                + String.format(Locale.US, "%.6f,%.6f", routeOriginCoords[0], routeOriginCoords[1])
                + "&destination=" + String.format(Locale.US, "%.6f,%.6f", routeDestCoords[0], routeDestCoords[1])
                + "&travelmode=driving";
        String text = "CHIPCHAP route preview\n"
                + "From: " + origin + " (" + from + ")\n"
                + "To: " + destination + " (" + to + ")\n"
                + "Driving distance: " + routeDistanceText(routeDistanceMeters) + "\n"
                + "Estimated driving time: " + routeTimeText(routeTimeSeconds)
                + " (approximate route estimate, not live traffic)\n"
                + "Directions link: " + link + "\n"
                + "Route preview by CHIPCHAP using Geoapify. CHIPCHAP does not provide turn-by-turn navigation.";
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, "CHIPCHAP route preview");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(intent, "Share route"));
        } catch (Exception error) {
            notifyWeb("No app is available to share this route.");
        }
    }

    /** Route payload for the map: [lng, lat] positions, meters and seconds exactly as Geoapify returned them. */
    private String routeJson(int generation, String state, String message,
                             GeoapifyRouteService.Route route, double[] origin, double[] destination) {
        try {
            JSONObject json = new JSONObject();
            json.put("gen", generation);
            json.put("state", state);
            json.put("reportId", routeReportId);
            json.put("title", clip(routeTitle, 160));
            json.put("message", message == null ? "" : message);
            json.put("destKind", routeDestPlaceId.isEmpty() ? "report" : "place");
            json.put("destPlaceId", routeDestPlaceId);
            json.put("originKind", routeOriginPlaceId.isEmpty() ? "device" : "place");
            json.put("originPlaceId", routeOriginPlaceId);
            json.put("originLabel", clip(routeOriginLabel, 160));
            if (origin != null) json.put("origin", new JSONArray().put(origin[1]).put(origin[0]));
            if (destination != null) json.put("dest", new JSONArray().put(destination[1]).put(destination[0]));
            if (route != null) {
                json.put("distance", route.distanceMeters);
                json.put("time", route.timeSeconds);
                json.put("geometry", route.geometry);
            }
            return json.toString();
        } catch (JSONException error) {
            return "{\"gen\":" + generation + ",\"state\":\"error\",\"message\":\"" + GeoapifyRouteService.MSG_UNAVAILABLE + "\"}";
        }
    }

    /** Sends the current route state to the map screen, and only to it. UI thread. */
    private void publishRoute() {
        if (web == null || isDestroyed()) return;
        boolean allowed = hasMapRouteAccess();
        if (!allowed && !ROUTE_NONE.equals(routeState)) resetRouteState();
        if (!SHARED_MAP_SCREEN.equals(currentScreen)) return;
        String payload = (!allowed || routePayload.isEmpty())
                ? "{\"gen\":" + routeGeneration + ",\"state\":\"none\"}" : routePayload;
        web.evaluateJavascript("window.chipchapRoute && window.chipchapRoute(" + JSONObject.quote(payload) + ");", null);
    }

    /**
     * Search results for the map. Each place gets a native id and is kept in placeCache; the page
     * only ever sends that id back, so a route always uses the coordinates Geoapify returned.
     */
    private void publishPlaces(int seq, String state, String message, List<GeoapifyRouteService.Place> places) {
        if (web == null || isDestroyed() || !SHARED_MAP_SCREEN.equals(currentScreen)) return;
        String payload;
        try {
            JSONObject json = new JSONObject();
            json.put("seq", seq);
            json.put("state", state);
            json.put("message", message == null ? "" : message);
            JSONArray results = new JSONArray();
            if (places != null) {
                for (GeoapifyRouteService.Place place : places) {
                    if (place == null || !validRouteCoordinate(place.lat, place.lng)) continue;
                    String id = "p" + (++placeSequence);
                    placeCache.put(id, place);
                    JSONObject item = new JSONObject();
                    item.put("id", id);
                    item.put("name", place.name);
                    item.put("address", place.address);
                    item.put("type", place.type);
                    item.put("lat", place.lat);
                    item.put("lng", place.lng);
                    results.put(item);
                }
            }
            json.put("results", results);
            payload = json.toString();
        } catch (JSONException error) {
            payload = "{\"seq\":" + seq + ",\"state\":\"error\",\"message\":\"" + GeoapifyRouteService.MSG_SEARCH_UNAVAILABLE + "\",\"results\":[]}";
        }
        web.evaluateJavascript("window.chipchapPlaces && window.chipchapPlaces(" + JSONObject.quote(payload) + ");", null);
    }

    private void publishGovernmentReports(List<Complaint> reports, String error) {
        publishGovernmentReports(reports, error, null);
    }

    /** analytics is null when the load failed or was refused: the page then shows no figures. */
    private void publishGovernmentReports(List<Complaint> reports, String error, CivicAnalytics.Summary analytics) {
        String payload;
        try {
            JSONObject json = new JSONObject();
            json.put("error", error == null ? "" : error);
            json.put("limit", GOVERNMENT_REPORT_LIMIT);
            JSONArray items = new JSONArray();
            if (reports != null) {
                for (Complaint report : reports) {
                    if (report == null) continue;
                    JSONObject item = new JSONObject();
                    item.put("reportId", safe(report.getReportId()));
                    item.put("title", safe(report.getTitle()));
                    item.put("description", safe(report.getDescription()));
                    item.put("category", safe(report.getCategory()));
                    item.put("severity", safe(report.getSeverity()));
                    item.put("status", safe(report.getStatus()));
                    item.put("address", safe(report.getAddress()));
                    item.put("citizenName", safe(report.getCitizenName()));
                    item.put("evidenceUrl", safe(report.getEvidenceUrl()));
                    item.put("resolutionEvidenceUrl", safe(report.getResolutionEvidenceUrl()));
                    item.put("assignedGovernmentUid", safe(report.getAssignedGovernmentUid()));
                    item.put("assignedGovernmentName", safe(report.getAssignedGovernmentName()));
                    putReportFacts(item, report);
                    items.put(item);
                }
            }
            json.put("reports", items);
            json.put("loaded", reports != null);
            if (reports != null && analytics != null) json.put("analytics", analyticsJson(analytics));
            payload = json.toString();
        } catch (JSONException jsonError) {
            payload = "{\"error\":\"Could not read reports.\",\"reports\":[],\"loaded\":false}";
        }
        final String finalPayload = payload;
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapGovReports && window.chipchapGovReports(" + JSONObject.quote(finalPayload) + ");", null));
    }

    /**
     * Checkpoint 3 - the analytics screen's figures: counts, the load's limit and coverage, and
     * the candidate areas (count, centre report's stored coordinate and address label, category
     * mix). Member report ids are not sent; the screen does not need them.
     */
    private JSONObject analyticsJson(CivicAnalytics.Summary s) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("total", s.total).put("unreadable", s.unreadable)
                .put("limit", s.queryLimit).put("limitReached", s.limitReached)
                .put("unresolved", s.unresolved).put("resolved", s.resolved).put("unknownStatus", s.unknownStatus)
                .put("aging", s.agingUnresolved).put("unresolvedNoDate", s.unresolvedWithoutDate)
                .put("unresolvedLocated", s.unresolvedWithLocation).put("unresolvedNoLocation", s.unresolvedWithoutLocation)
                .put("hotspotRadius", (int) CivicAnalytics.HOTSPOT_RADIUS_METERS).put("hotspotMin", CivicAnalytics.HOTSPOT_MIN_REPORTS)
                .put("hotspotCount", s.hotspotCount).put("reportsInHotspots", s.reportsInHotspots);
        JSONArray statuses = new JSONArray();
        for (Map.Entry<String, Integer> e : s.byStatus.entrySet()) {
            statuses.put(new JSONObject().put("key", e.getKey()).put("count", e.getValue()));
        }
        o.put("byStatus", statuses);
        JSONArray categories = new JSONArray();
        for (Map.Entry<String, Integer> e : s.byCategory.entrySet()) {
            categories.put(new JSONObject().put("label", e.getKey()).put("count", e.getValue()));
        }
        o.put("byCategory", categories);
        JSONArray areas = new JSONArray();
        for (CivicAnalytics.Hotspot h : s.hotspots) {
            JSONArray mix = new JSONArray();
            for (Map.Entry<String, Integer> e : h.categories.entrySet()) {
                mix.put(new JSONObject().put("label", e.getKey()).put("count", e.getValue()));
            }
            areas.put(new JSONObject().put("count", h.count()).put("latitude", h.latitude).put("longitude", h.longitude)
                    .put("label", clip(h.address, 60)).put("categories", mix));
        }
        o.put("hotspots", areas);
        return o;
    }

    /** Firestore fullName, else the Auth display name, else the email local part. Never invented. */
    private void publishGovernmentProfile(UserProfile profile) {
        FirebaseUser user = auth.getCurrentUser();
        String payload;
        try {
            JSONObject json = new JSONObject();
            String email = user == null || user.getEmail() == null ? "" : user.getEmail().trim();
            String name = profile != null && profile.getFullName() != null ? profile.getFullName().trim() : "";
            if (name.isEmpty() && user != null && user.getDisplayName() != null) name = user.getDisplayName().trim();
            if (name.isEmpty() && email.indexOf('@') > 0) name = email.substring(0, email.indexOf('@'));
            json.put("signedIn", user != null && hasGovernmentAccess());
            json.put("name", name);
            json.put("email", email);
            json.put("uid", user == null ? "" : user.getUid());
            payload = json.toString();
        } catch (JSONException jsonError) {
            payload = "{}";
        }
        final String finalPayload = payload;
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapGovProfile && window.chipchapGovProfile(" + JSONObject.quote(finalPayload) + ");", null));
    }

    private void publishNotifications(List<NotificationRepository.Item> items, String error) {
        String payload;
        try {
            JSONObject json = new JSONObject();
            json.put("error", error == null ? "" : error);
            json.put("loaded", items != null);
            JSONArray list = new JSONArray();
            if (items != null) {
                for (NotificationRepository.Item item : items) {
                    JSONObject o = new JSONObject();
                    o.put("id", safe(item.id));
                    o.put("title", safe(item.title));
                    o.put("body", safe(item.body));
                    o.put("type", safe(item.type));
                    o.put("reportId", safe(item.reportId));
                    o.put("status", safe(item.status));
                    o.put("read", item.read);
                    o.put("createdAt", millis(item.createdAt));
                    list.put(o);
                }
            }
            json.put("notifications", list);
            payload = json.toString();
        } catch (JSONException jsonError) {
            payload = "{\"error\":\"Could not read notifications.\",\"loaded\":false,\"notifications\":[]}";
        }
        final String finalPayload = payload;
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapNotifications && window.chipchapNotifications(" + JSONObject.quote(finalPayload) + ");", null));
    }

    private void publishUnreadCount(int count) {
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapUnreadCount && window.chipchapUnreadCount(" + count + ");", null));
    }

    private void publishMyReportCount(int count, int resolved) {
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapMyReportCount && window.chipchapMyReportCount(" + count + "," + resolved + ");", null));
    }

    private static String safe(String value) { return value == null ? "" : value; }

    /** Longest description text sent with a map marker for search. */
    private static final int MAP_SEARCH_TEXT_MAX = 1000;

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static long millis(Timestamp value) { return value == null ? 0L : value.toDate().getTime(); }

    /**
     * Stored facts the cards and details screen need besides the text fields: real coordinates,
     * real timestamps and whether this device holds the evidence photo. The photo path itself is
     * remembered natively and never sent to JavaScript.
     */
    private void putReportFacts(JSONObject target, Complaint report) throws JSONException {
        target.put("latitude", report.getLatitude() == null ? JSONObject.NULL : report.getLatitude());
        target.put("longitude", report.getLongitude() == null ? JSONObject.NULL : report.getLongitude());
        target.put("createdAt", millis(report.getCreatedAt()));
        target.put("updatedAt", millis(report.getUpdatedAt()));
        String reportId = safe(report.getReportId());
        String path = safe(report.getLocalImagePath());
        if (!reportId.isEmpty()) {
            if (path.isEmpty()) reportPhotoPaths.remove(reportId);
            else reportPhotoPaths.put(reportId, path);
        }
        target.put("hasLocalPhoto", !path.isEmpty() && localPhotoRepository.isAppPhoto(new File(path)));
    }

    private String validationFailure(String message) {
        try {
            return new JSONObject().put("ok", false).put("message", message).toString();
        } catch (JSONException error) {
            return "{\"ok\":false}";
        }
    }

    /**
     * Creates users/{uid} from Firebase Authentication data when it is missing, for accounts that
     * were registered before Firestore existed. Never overwrites an existing profile.
     */
    private void ensureFirestoreProfile() {
        userRepository.ensureProfile(new RepositoryCallback<UserProfile>() {
            @Override public void onSuccess(UserProfile result) {
                citizenProfile = result;
                publishCitizenProfile();
            }
            @Override public void onFailure(Exception error) {
                notifyWeb("Signed in, but your profile could not be synchronised. "
                        + FirestoreErrors.describe(error, "Some profile details may be unavailable."));
            }
        });
    }

    private String friendlyAuthError(Exception exception, String fallback) {
        if (exception == null || exception.getMessage() == null) return fallback;
        String lower = exception.getMessage().toLowerCase();
        if (lower.contains("email-already-in-use") || lower.contains("already in use") || lower.contains("already exists")) return "Email address is already registered.";
        if (lower.contains("invalid-email") || lower.contains("badly formatted")) return "Enter a valid email address.";
        if (lower.contains("weak-password") || lower.contains("password should be at least")) return "Password is too weak. Use at least 6 characters.";
        if (lower.contains("invalid-credential") || lower.contains("user-not-found") || lower.contains("wrong-password") || lower.contains("invalid_password")) return "Invalid email or password.";
        if (lower.contains("user-disabled")) return "This account has been disabled.";
        if (lower.contains("requires-recent-login")) return "For security, sign in again and retry.";
        if (lower.contains("too-many-requests")) return "Too many attempts. Try again later.";
        if (lower.contains("network") || lower.contains("connection")) return "Network error. Check your connection and try again.";
        return fallback;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_GOVERNMENT_CAMERA) { onGovernmentPhotoResult(resultCode); return; }
        if (requestCode != REQ_CAMERA) return;

        File captured = pendingPhotoFile;
        pendingPhotoFile = null;
        Log.i(FLOW_TAG, "CAMERA_RESULT code=" + resultCode + " pendingFile=" + (captured != null ? "present" : "MISSING")
                + " screen=" + currentScreen);

        if (resultCode == RESULT_CANCELED) {
            localPhotoRepository.deleteQuietly(captured);
            notifyWeb("Photo capture was cancelled.");
            return;
        }
        if (resultCode != RESULT_OK) {
            localPhotoRepository.deleteQuietly(captured);
            notifyWeb("The camera did not return a photo. Please try again.");
            return;
        }
        if (!localPhotoRepository.isUsable(captured)) {
            localPhotoRepository.deleteQuietly(captured);
            notifyWeb("The camera reported success but saved no image. Please try again.");
            return;
        }

        // Correct the stored file itself, not just the preview, so the evidence is upright.
        localPhotoRepository.normalizeOrientation(captured);

        String preview = localPhotoRepository.buildPreviewDataUri(captured);
        if (preview.isEmpty()) {
            // The file exists but cannot be decoded, so it is not usable evidence.
            localPhotoRepository.deleteQuietly(captured);
            notifyWeb("The captured photo could not be read. Please try again.");
            return;
        }

        complaintDraft.setLocalImagePath(captured.getAbsolutePath());
        photoPreviewDataUri = preview;
        web.evaluateJavascript("window.chipchapPhotoCaptured && window.chipchapPhotoCaptured();", null);

        // AI checkpoint 2: the photo is checked BEFORE the existing Cloudinary upload. Only an
        // accepted photo (or one the citizen explicitly continues with after a technical AI
        // failure) reaches startCitizenEvidenceUpload(); a rejected photo is never uploaded.
        // The local JPEG stays exactly where it is.
        startPhotoCheck(captured);
    }

    // ---------- AI photo check (AI checkpoint 2) ----------
    // State for the CURRENT draft only. Nothing here is written to Firestore or kept after the
    // report is submitted, the draft is cleared, or the session ends.

    /** Starts (or restarts) the AI check of the draft photo. UI thread. */
    private void startPhotoCheck(final File captured) {
        // Never start a second request for a photo whose check is already running.
        if (PHOTO_CHECK_CHECKING.equals(photoCheckState) && captured == photoCheckFile) {
            Log.w(FLOW_TAG, "AI_CHECK_IGNORED duplicate start for a photo already being checked");
            return;
        }
        clearDraftText();   // a new check means a new photo: an older photo's draft text must never survive
        final int request = ++photoCheckRequest;
        Log.i(FLOW_TAG, "AI_CHECK_STARTED request=" + request);
        photoCheckFile = captured;
        photoCheckState = PHOTO_CHECK_CHECKING;
        photoCheckMessage = "";
        photoCheckCategory = "";
        // No evidence URL belongs to this photo until its own upload finishes.
        complaintDraft.setEvidenceUrl("");
        complaintDraft.setEvidenceUploading(false);
        publishPhotoCheck();
        if (civicPhotoAiService == null) {
            onPhotoCheckResult(request, captured, null);
            return;
        }
        civicPhotoAiService.analyzeCivicPhoto(captured, result -> onPhotoCheckResult(request, captured, result));
    }

    private void onPhotoCheckResult(int request, File captured, CivicPhotoAiService.Result result) {
        Log.i(FLOW_TAG, "AI_CHECK_RESULT request=" + request + " outcome=" + (result == null ? "none" : result.outcome)
                + " screen=" + currentScreen);
        if (isFinishing() || isDestroyed()) { Log.w(FLOW_TAG, "AI_CHECK_IGNORED activity finishing/destroyed"); return; }
        // A newer capture, a retry or a cleared draft supersedes this answer.
        if (request != photoCheckRequest || captured != photoCheckFile) { Log.w(FLOW_TAG, "AI_CHECK_IGNORED superseded"); return; }
        if (!captured.getAbsolutePath().equals(complaintDraft.getLocalImagePath())) { Log.w(FLOW_TAG, "AI_CHECK_IGNORED draft photo changed"); return; }
        // Only a check that is still running can finish: a repeated delivery can never upload twice.
        if (!PHOTO_CHECK_CHECKING.equals(photoCheckState)) { Log.w(FLOW_TAG, "AI_CHECK_IGNORED not checking (state=" + photoCheckState + ")"); return; }

        if (result != null && result.outcome == CivicPhotoAiService.Outcome.ACCEPTED) {
            Log.i(FLOW_TAG, "AI_ACCEPTED_CALLBACK " + diagCtx());
            photoCheckState = PHOTO_CHECK_ACCEPTED;
            photoCheckMessage = result.message;
            photoCheckCategory = result.suggestedCategory;
            storeDraftText(request, result);   // plain assignments; whatever the draft holds, the photo stays accepted
            Log.i(FLOW_TAG, "AI_CHECK_ACCEPTED category=" + (photoCheckCategory.isEmpty() ? "none" : photoCheckCategory));
            Log.i(FLOW_TAG, "AI_ACCEPTED_ROUTE_BEFORE " + diagCtx());
            Log.i(FLOW_TAG, "AI_ACCEPTED_PUBLISH");
            publishPhotoCheck();
            Log.i(FLOW_TAG, "STARTING_CLOUDINARY_UPLOAD");
            Log.i(FLOW_TAG, "AI_ACCEPTED_UPLOAD_START");
            startCitizenEvidenceUpload(captured);   // existing Cloudinary flow, original file
            Log.i(FLOW_TAG, "AI_ACCEPTED_ROUTE_AFTER " + diagCtx());
            return;
        }
        if (result != null && result.outcome == CivicPhotoAiService.Outcome.REJECTED) {
            // Confidently unsuitable: never uploaded, never submitted. The citizen retakes.
            Log.i(FLOW_TAG, "AI_CHECK_REJECTED");
            photoCheckState = PHOTO_CHECK_REJECTED;
            photoCheckMessage = result.message;
            photoCheckCategory = "";
            photoCheckFile = null;
            complaintDraft.setLocalImagePath("");
            photoPreviewDataUri = "";
            localPhotoRepository.deleteQuietly(captured);
            publishPhotoCheck();
            return;
        }
        // Technical failure or uncertainty: not an approval and not a rejection. The citizen may
        // retry, or explicitly continue without the AI check.
        Log.i(FLOW_TAG, "AI_CHECK_UNAVAILABLE");
        photoCheckState = PHOTO_CHECK_UNAVAILABLE;
        photoCheckMessage = result == null ? "The AI photo check is not available right now." : result.message;
        photoCheckCategory = "";
        publishPhotoCheck();
    }

    /** Clears the check for a new draft; any answer still in flight is ignored. */
    private void resetPhotoCheck() {
        photoCheckRequest++;
        clearDraftText();
        if (civicPhotoAiService != null) civicPhotoAiService.cancelActive();
        photoCheckFile = null;
        photoCheckState = PHOTO_CHECK_NONE;
        photoCheckMessage = "";
        photoCheckCategory = "";
    }

    /** Keeps the draft title/description that came with an ACCEPTED answer. Lengths only are logged. */
    private void storeDraftText(int request, CivicPhotoAiService.Result result) {
        String title = result.draftTitle == null ? "" : result.draftTitle;
        String description = result.draftDescription == null ? "" : result.draftDescription;
        draftRequest = request;
        draftTitle = title;
        draftDescription = description;
        draftHandled = false;
        draftState = (title.isEmpty() && description.isEmpty()) ? DRAFT_UNAVAILABLE : DRAFT_READY;
        Log.i(FLOW_TAG, "AI_DRAFT_" + (DRAFT_READY.equals(draftState) ? "RECEIVED" : "EMPTY")
                + " title=" + title.length() + " chars, description=" + description.length() + " chars");
    }

    private void clearDraftText() {
        draftState = "";
        draftTitle = "";
        draftDescription = "";
        draftHandled = false;
        draftRequest++;
    }

    private String draftTextJson() {
        String state = draftState;   // read first: READY guarantees the text below is the finished draft
        boolean ready = DRAFT_READY.equals(state);
        try {
            return new JSONObject()
                    .put("state", state)
                    .put("request", draftRequest)
                    .put("handled", draftHandled)
                    .put("title", ready ? draftTitle : "")
                    .put("description", ready ? draftDescription : "")
                    .toString();
        } catch (JSONException error) {
            return "{\"state\":\"\"}";
        }
    }

    /** The draft photo may continue to details/submission only when accepted or explicitly skipped. */
    private boolean photoCheckAllowsContinue() {
        String state = photoCheckState;
        return PHOTO_CHECK_ACCEPTED.equals(state) || PHOTO_CHECK_SKIPPED.equals(state);
    }

    private String photoCheckJson() {
        try {
            return new JSONObject()
                    .put("state", photoCheckState)
                    .put("message", photoCheckMessage)
                    .put("category", photoCheckCategory)
                    .put("hasPhoto", complaintDraft.hasPhoto())
                    .toString();
        } catch (JSONException error) {
            return "{\"state\":\"\"}";
        }
    }

    private void publishPhotoCheck() {
        final String payload = photoCheckJson();
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapPhotoCheck && window.chipchapPhotoCheck(" + JSONObject.quote(payload) + ");", null));
    }

    /**
     * Uploads the captured evidence to Cloudinary in the background. Failure is reported honestly
     * and leaves the local photo untouched: the report can still be submitted, just without a
     * cloud URL, and the government side then shows its "evidence unavailable" state.
     */
    private void startCitizenEvidenceUpload(final File captured) {
        complaintDraft.setEvidenceUrl("");
        if (!cloudEvidenceRepository.isConfigured()) {
            complaintDraft.setEvidenceUploading(false);
            return;
        }
        complaintDraft.setEvidenceUploading(true);
        publishEvidenceUploadState("uploading", "");
        cloudEvidenceRepository.upload(captured, new CloudEvidenceRepository.UploadCallback() {
            @Override public void onUploaded(String secureUrl) {
                // A retake started a new photo meanwhile: this URL belongs to the old one.
                if (!captured.getAbsolutePath().equals(complaintDraft.getLocalImagePath())) { Log.w(FLOW_TAG, "CLOUDINARY_UPLOAD_IGNORED older photo"); return; }
                Log.i(FLOW_TAG, "CLOUDINARY_UPLOAD_DONE");
                Log.i(FLOW_TAG, "AI_ACCEPTED_UPLOAD_CALLBACK result=uploaded " + diagCtx());
                complaintDraft.setEvidenceUrl(secureUrl);
                complaintDraft.setEvidenceUploading(false);
                publishEvidenceUploadState("uploaded", "");
                runPendingSubmission();
            }
            @Override public void onFailed(String message) {
                if (!captured.getAbsolutePath().equals(complaintDraft.getLocalImagePath())) return;
                Log.w(FLOW_TAG, "CLOUDINARY_UPLOAD_FAILED");
                Log.i(FLOW_TAG, "AI_ACCEPTED_UPLOAD_CALLBACK result=failed " + diagCtx());
                complaintDraft.setEvidenceUrl("");
                complaintDraft.setEvidenceUploading(false);
                publishEvidenceUploadState("failed", message);
                runPendingSubmission();
            }
        });
    }

    private void publishEvidenceUploadState(String state, String message) {
        String payload;
        try {
            payload = new JSONObject().put("state", state).put("message", message == null ? "" : message).toString();
        } catch (JSONException error) {
            payload = "{}";
        }
        final String finalPayload = payload;
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapEvidenceUpload && window.chipchapEvidenceUpload("
                        + JSONObject.quote(finalPayload) + ");", null));
    }

    // ---------- government after/resolution photo ----------

    private void clearGovernmentAfterPhotoState() {
        pendingGovernmentPhotoFile = null;
        governmentAfterPhotoPath = "";
        governmentAfterPhotoUrl = "";
        governmentAfterPhotoUploading = false;
        governmentAfterPhotoError = "";
        governmentAfterPhotoReportId = "";
    }

    /** Camera capture for the government after-photo, bound to the report currently open. */
    private void startGovernmentPhotoCapture() {
        if (!hasGovernmentAccess()) { notifyWeb("Government access is required."); return; }
        String reportId = activeReportId == null ? "" : activeReportId.trim();
        if (reportId.isEmpty()) { notifyWeb("Open a report first."); return; }
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.CAMERA }, REQ_CAMERA_PERMISSION);
            return;
        }
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) == null) {
            notifyWeb("No camera app is available on this device."); return;
        }
        File file;
        try { file = localPhotoRepository.createPhotoFile(); }
        catch (IOException error) { notifyWeb("Could not create a file for the photo."); return; }
        Uri output;
        try { output = localPhotoRepository.contentUriFor(file); }
        catch (Exception error) { localPhotoRepository.deleteQuietly(file); notifyWeb("Could not prepare the photo file."); return; }
        intent.putExtra(MediaStore.EXTRA_OUTPUT, output);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            pendingGovernmentPhotoFile = file;
            governmentAfterPhotoReportId = reportId;
            startActivityForResult(intent, REQ_GOVERNMENT_CAMERA);
        } catch (Exception error) {
            pendingGovernmentPhotoFile = null;
            localPhotoRepository.deleteQuietly(file);
            notifyWeb("The camera could not be opened on this device.");
        }
    }

    private void onGovernmentPhotoResult(int resultCode) {
        File captured = pendingGovernmentPhotoFile;
        pendingGovernmentPhotoFile = null;
        if (resultCode != RESULT_OK || !localPhotoRepository.isUsable(captured)) {
            localPhotoRepository.deleteQuietly(captured);
            governmentAfterPhotoPath = "";
            governmentAfterPhotoError = resultCode == RESULT_CANCELED ? "" : "The camera did not return a photo.";
            publishGovernmentAfterPhoto();
            return;
        }
        localPhotoRepository.normalizeOrientation(captured);
        governmentAfterPhotoPath = captured.getAbsolutePath();
        governmentAfterPhotoUrl = "";
        governmentAfterPhotoError = "";
        governmentAfterPhotoUploading = true;
        publishGovernmentAfterPhoto();
        // The upload answer belongs to THIS photo and report only: a newer capture, a cleared photo
        // or another report makes it stale, and a stale URL is never attached to anything.
        final String uploadPath = captured.getAbsolutePath();
        final String uploadReportId = governmentAfterPhotoReportId;
        cloudEvidenceRepository.upload(captured, new CloudEvidenceRepository.UploadCallback() {
            @Override public void onUploaded(String secureUrl) {
                if (!uploadPath.equals(governmentAfterPhotoPath) || !uploadReportId.equals(governmentAfterPhotoReportId)) return;
                governmentAfterPhotoUrl = secureUrl;
                governmentAfterPhotoUploading = false;
                governmentAfterPhotoError = "";
                publishGovernmentAfterPhoto();
            }
            @Override public void onFailed(String message) {
                if (!uploadPath.equals(governmentAfterPhotoPath) || !uploadReportId.equals(governmentAfterPhotoReportId)) return;
                governmentAfterPhotoUrl = "";
                governmentAfterPhotoUploading = false;
                governmentAfterPhotoError = message;
                publishGovernmentAfterPhoto();
            }
        });
    }

    private void publishGovernmentAfterPhoto() {
        String payload;
        try {
            payload = new JSONObject()
                    .put("hasPhoto", !governmentAfterPhotoPath.isEmpty())
                    .put("uploading", governmentAfterPhotoUploading)
                    .put("uploaded", !governmentAfterPhotoUrl.isEmpty())
                    .put("error", governmentAfterPhotoError)
                    .toString();
        } catch (JSONException error) {
            payload = "{}";
        }
        final String finalPayload = payload;
        runOnUiThread(() -> web.evaluateJavascript(
                "window.chipchapAfterPhoto && window.chipchapAfterPhoto("
                        + JSONObject.quote(finalPayload) + ");", null));
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results != null && results.length > 0;
        if (granted) {
            for (int result : results) {
                if (result != PackageManager.PERMISSION_GRANTED) { granted = false; break; }
            }
        }
        if (requestCode == REQ_CAMERA_PERMISSION) {
            if (granted) launchCamera();
            else notifyWeb("Camera permission was denied, so no photo can be taken.");
        } else if (requestCode == REQ_LOCATION_PERMISSION) {
            if (granted) {
                startLocationCapture(false);
            } else {
                complaintDraft.clearCoordinates();
                complaintDraft.setLocationStatus("Location permission denied. The report will be submitted without coordinates.");
                publishLocationStatus();
            }
        } else if (requestCode == REQ_ROUTE_LOCATION_PERMISSION) {
            // Either precision is enough for a route, so this asks the provider, not the result array.
            String pendingReport = pendingRouteReportId;
            String pendingPlace = pendingRouteDestPlaceId;
            pendingRouteReportId = "";
            pendingRouteDestPlaceId = "";
            if (pendingReport.isEmpty() && pendingPlace.isEmpty()) return;
            if (locationProvider.hasPermission()) startRoute(pendingReport, pendingPlace, "", false);
            else notifyWeb("Location permission is required to calculate a route from your location. You can choose a starting point instead.");
        }
    }

    @Override public void onBackPressed(){
        // A full-size photo preview is closed first, so Back never leaves the capture/review step
        // while the preview is open. Any other answer (including no answer) keeps the old behaviour.
        if (web == null) { goBackScreen(); return; }
        web.evaluateJavascript("(function(){return !!(window.ccClosePhotoViewer&&window.ccClosePhotoViewer());})()",
                value -> { if (!"true".equals(value)) goBackScreen(); });
    }
}
