package com.chipchap.app.ai;

import android.content.Context;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;

/**
 * DEBUG BUILDS ONLY (this file lives in src/debug, so it is compiled into debug APKs and never
 * into release APKs; the release variant has its own AppCheckSetup in src/release).
 *
 * Installs the Firebase App Check debug provider so a sideloaded test build can pass the App Check
 * enforcement that protects Firebase AI Logic. The provider generates its own debug secret on the
 * device and prints it to Logcat once; that secret has to be registered in the Firebase console
 * (App Check -> Apps -> Manage debug tokens). No token is, or may ever be, written in this source.
 */
public final class AppCheckSetup {

    public static final String PROVIDER = "debug";
    private static final String TAG = "CHIPCHAP-AI";

    private AppCheckSetup() { }

    /** Call once, before any Firebase AI Logic request. Safe to call again. */
    public static void install(Context context) {
        Context app = context.getApplicationContext();
        if (FirebaseApp.getApps(app).isEmpty()) FirebaseApp.initializeApp(app);
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance());
        Log.i(TAG, "App Check: DEBUG provider installed (debug build). Look in Logcat for the line "
                + "'Enter this debug secret into the allow list' from DebugAppCheckProvider.");
    }

    /**
     * Asks App Check for a token once. This is not an AI request. It makes the debug provider print
     * its secret (if it has not already) and reports whether the Firebase console accepts it: a
     * failure here before the secret is registered is expected. The token itself is never logged.
     */
    public static void logTokenStatus() {
        FirebaseAppCheck.getInstance().getAppCheckToken(false)
                .addOnSuccessListener(token -> Log.i(TAG,
                        "App Check: token issued - the debug secret on this device is registered."))
                .addOnFailureListener(error -> Log.w(TAG,
                        "App Check: no token yet (" + error.getClass().getSimpleName() + ": " + error.getMessage()
                                + "). Register this device's debug secret in the Firebase console, then restart the app."));
    }
}
