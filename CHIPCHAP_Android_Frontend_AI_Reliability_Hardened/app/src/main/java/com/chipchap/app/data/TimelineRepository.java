package com.chipchap.app.data;

import com.chipchap.app.models.TimelineEvent;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Firestore data layer for reports/{reportId}/timeline/{timelineId}.
 *
 * actorUid always comes from FirebaseAuth.getCurrentUser(); actorName comes from the actor's own
 * users/{uid}.fullName, falling back to the Firebase Authentication display name, then the email. Timeline entries are append-only — this
 * repository intentionally exposes no update or delete operation, so report history is preserved
 * rather than overwritten when a status changes.
 */
public class TimelineRepository {

    public static final String COLLECTION_REPORTS = ComplaintRepository.COLLECTION_REPORTS;
    public static final String COLLECTION_TIMELINE = "timeline";

    private final FirebaseAuth auth;
    private final FirebaseFirestore firestore;

    public TimelineRepository() {
        this(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance());
    }

    public TimelineRepository(FirebaseAuth auth, FirebaseFirestore firestore) {
        this.auth = auth;
        this.firestore = firestore;
    }

    /** Appends one history entry to a report and returns the stored event. */
    public void addEvent(String reportId, String status, String message,
                         final RepositoryCallback<TimelineEvent> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        if (reportId == null || reportId.trim().isEmpty()) {
            fail(callback, new IllegalArgumentException("A reportId is required."));
            return;
        }
        if (status == null || status.trim().isEmpty()) {
            fail(callback, new IllegalArgumentException("A timeline status is required."));
            return;
        }

        final TimelineEvent event = new TimelineEvent();
        event.setStatus(status.trim());
        event.setMessage(trimmed(message));
        event.setActorUid(user.getUid());
        // createdAt stays null so @ServerTimestamp writes the server value.

        final String authFallback = resolveActorName(user);
        final DocumentReference reference = timelineFor(reportId.trim()).document();
        firestore.collection(UserRepository.COLLECTION_USERS).document(user.getUid()).get()
                .addOnCompleteListener(profileTask -> {
                    String profileName = null;
                    if (profileTask.isSuccessful() && profileTask.getResult() != null
                            && profileTask.getResult().exists()) {
                        profileName = profileTask.getResult().getString("fullName");
                    }
                    event.setActorName(profileName != null && !profileName.trim().isEmpty()
                            ? profileName.trim() : authFallback);
                    reference.set(event)
                            .addOnSuccessListener(ignored -> reference.get()
                                    .addOnSuccessListener(snapshot -> {
                                        if (callback == null) return;
                                        callback.onSuccess(snapshot.exists()
                                                ? snapshot.toObject(TimelineEvent.class) : event);
                                    })
                                    .addOnFailureListener(error -> fail(callback, error)))
                            .addOnFailureListener(error -> fail(callback, error));
                });
    }

    /** Reads a report's history, oldest first. */
    public void loadTimeline(String reportId, final RepositoryCallback<List<TimelineEvent>> callback) {
        if (reportId == null || reportId.trim().isEmpty()) {
            fail(callback, new IllegalArgumentException("A reportId is required."));
            return;
        }
        timelineFor(reportId.trim())
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .get()
                .addOnSuccessListener(snapshots -> {
                    if (callback == null) return;
                    List<TimelineEvent> events = new ArrayList<>();
                    for (QueryDocumentSnapshot document : snapshots) {
                        TimelineEvent event = document.toObject(TimelineEvent.class);
                        if (event != null) events.add(event);
                    }
                    callback.onSuccess(events);
                })
                .addOnFailureListener(error -> fail(callback, error));
    }

    private CollectionReference timelineFor(String reportId) {
        return firestore.collection(COLLECTION_REPORTS).document(reportId).collection(COLLECTION_TIMELINE);
    }

    private static String resolveActorName(FirebaseUser user) {
        String displayName = user.getDisplayName();
        if (displayName != null && !displayName.trim().isEmpty()) return displayName.trim();
        String email = user.getEmail();
        return email == null ? "" : email.trim();
    }

    private static void fail(RepositoryCallback<?> callback, Exception error) {
        if (callback != null) callback.onFailure(error);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
