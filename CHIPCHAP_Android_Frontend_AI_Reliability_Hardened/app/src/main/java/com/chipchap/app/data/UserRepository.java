package com.chipchap.app.data;

import com.chipchap.app.models.UserProfile;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Transaction;

import java.util.HashMap;
import java.util.Map;

/**
 * Firestore data layer for users/{uid}.
 *
 * Rules enforced here:
 *  - the document id and the stored uid always come from FirebaseAuth.getCurrentUser(),
 *    never from JavaScript, the WebView or a caller-supplied value;
 *  - email always comes from Firebase Authentication, which stays authoritative for identity;
 *  - createdAt is written once at creation and never rewritten on update;
 *  - updatedAt is refreshed with a Firestore server timestamp on every write;
 *  - role is written once at creation as CITIZEN and is never written again by this client.
 *    There is deliberately no method to promote a profile to GOVERNMENT: elevation must happen
 *    through trusted provisioning (Firebase custom claims / console / trusted backend).
 */
public class UserRepository {

    public static final String COLLECTION_USERS = "users";

    private final FirebaseAuth auth;
    private final FirebaseFirestore firestore;

    public UserRepository() {
        this(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance());
    }

    public UserRepository(FirebaseAuth auth, FirebaseFirestore firestore) {
        this.auth = auth;
        this.firestore = firestore;
    }

    /** Creates the profile if missing, otherwise updates the supplied application fields. */
    public void saveProfile(String fullName, String neighborhood, RepositoryCallback<UserProfile> callback) {
        writeProfile(fullName, neighborhood, false, callback);
    }

    /**
     * Creates users/{uid} from Firebase Authentication data only if it does not exist yet.
     * Used for accounts registered before Firestore existed. Never overwrites an existing profile.
     */
    public void ensureProfile(RepositoryCallback<UserProfile> callback) {
        writeProfile(null, null, true, callback);
    }

    /** Reads the authenticated user's profile. Returns null when the document does not exist. */
    public void loadProfile(RepositoryCallback<UserProfile> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        readProfile(documentFor(user.getUid()), callback);
    }

    private void writeProfile(final String fullName,
                              final String neighborhood,
                              final boolean onlyCreateIfMissing,
                              final RepositoryCallback<UserProfile> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }

        final String uid = user.getUid();
        final String email = user.getEmail() == null ? "" : user.getEmail();
        final String authDisplayName = user.getDisplayName() == null ? "" : user.getDisplayName().trim();
        final DocumentReference reference = documentFor(uid);

        firestore.runTransaction(new Transaction.Function<Void>() {
            @Override
            public Void apply(Transaction transaction) throws FirebaseFirestoreException {
                DocumentSnapshot snapshot = transaction.get(reference);
                Map<String, Object> data = new HashMap<>();

                if (!snapshot.exists()) {
                    data.put("uid", uid);
                    data.put("email", email);
                    data.put("fullName", firstNonEmpty(fullName, authDisplayName));
                    data.put("neighborhood", trimmed(neighborhood));
                    data.put("role", UserProfile.ROLE_CITIZEN);
                    data.put("createdAt", FieldValue.serverTimestamp());
                    data.put("updatedAt", FieldValue.serverTimestamp());
                    transaction.set(reference, data);
                    return null;
                }

                if (onlyCreateIfMissing) {
                    return null;
                }

                // Update path: createdAt and role are deliberately absent so Firestore preserves them.
                data.put("uid", uid);
                data.put("email", email);
                if (isNotEmpty(fullName)) data.put("fullName", fullName.trim());
                if (isNotEmpty(neighborhood)) data.put("neighborhood", neighborhood.trim());
                data.put("updatedAt", FieldValue.serverTimestamp());
                transaction.set(reference, data, SetOptions.merge());
                return null;
            }
        }).addOnSuccessListener(ignored -> readProfile(reference, callback))
          .addOnFailureListener(error -> fail(callback, error));
    }

    private void readProfile(DocumentReference reference, final RepositoryCallback<UserProfile> callback) {
        reference.get()
                .addOnSuccessListener(snapshot -> {
                    if (callback == null) return;
                    callback.onSuccess(snapshot.exists() ? snapshot.toObject(UserProfile.class) : null);
                })
                .addOnFailureListener(error -> fail(callback, error));
    }

    private DocumentReference documentFor(String uid) {
        return firestore.collection(COLLECTION_USERS).document(uid);
    }

    private static void fail(RepositoryCallback<?> callback, Exception error) {
        if (callback != null) callback.onFailure(error);
    }

    private static boolean isNotEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonEmpty(String preferred, String fallback) {
        return isNotEmpty(preferred) ? preferred.trim() : trimmed(fallback);
    }
}
