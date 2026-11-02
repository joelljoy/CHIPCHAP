package com.chipchap.app.data;

/** Small helper that turns Firestore exceptions into honest, user-readable messages. */
public final class FirestoreErrors {

    private FirestoreErrors() { }

    /** The Firestore status code, when there is one, so a failure can actually be diagnosed. */
    public static String code(Exception error) {
        if (error instanceof com.google.firebase.firestore.FirebaseFirestoreException) {
            com.google.firebase.firestore.FirebaseFirestoreException.Code code =
                    ((com.google.firebase.firestore.FirebaseFirestoreException) error).getCode();
            return code == null ? "" : code.name();
        }
        return "";
    }

    public static String describe(Exception error, String fallback) {
        if (error == null || error.getMessage() == null) return fallback;
        String lower = error.getMessage().toLowerCase();
        if (lower.contains("permission_denied") || lower.contains("permission denied") || lower.contains("insufficient permissions")) {
            return "PERMISSION_DENIED: the deployed Firestore security rules rejected this. "
                    + "The rules in firestore.rules must be deployed to project chipchap-e967b.";
        }
        if (lower.contains("unavailable") || lower.contains("network") || lower.contains("connection")) {
            return "Could not reach the database. Check your connection and try again.";
        }
        if (lower.contains("unauthenticated")) {
            return "Your session expired. Sign in again and retry.";
        }
        if (lower.contains("failed_precondition") || lower.contains("requires an index")) {
            return "The database needs an index for this query. Check the Firebase console.";
        }
        String code = code(error);
        return code.isEmpty() ? fallback : (code + ": " + fallback);
    }
}
