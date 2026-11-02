package com.chipchap.app.ai;

import android.content.Context;
import android.util.Log;

/**
 * RELEASE BUILDS. The App Check debug provider is deliberately absent: it is a debugImplementation
 * dependency and this file never references it.
 *
 * No production provider is installed yet, so a release build cannot pass the App Check enforcement
 * on Firebase AI Logic until one is added. The production path is Play Integrity: add
 * 'com.google.firebase:firebase-appcheck-playintegrity' (release) and install
 * PlayIntegrityAppCheckProviderFactory.getInstance() here, after registering the app's SHA-256
 * signing fingerprint for Play Integrity in the Firebase console. Enforcement is never disabled.
 */
public final class AppCheckSetup {

    public static final String PROVIDER = "none";
    private static final String TAG = "CHIPCHAP-AI";

    private AppCheckSetup() { }

    public static void install(Context context) {
        Log.i(TAG, "App Check: no production provider installed yet (release build).");
    }

    public static void logTokenStatus() { }
}
