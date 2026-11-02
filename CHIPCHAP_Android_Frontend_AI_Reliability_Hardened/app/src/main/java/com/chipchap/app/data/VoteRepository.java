package com.chipchap.app.data;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * Firestore data layer for reports/{reportId}/votes/{uid}.
 *
 * The authenticated uid is the document id, which is what makes a vote idempotent: tapping upvote
 * repeatedly rewrites the same document instead of inflating a counter. The uid always comes from
 * FirebaseAuth, never from JavaScript.
 *
 * The count is derived by reading the votes collection rather than kept in a client-writable
 * counter field on the report, which a citizen must not be able to edit.
 */
public class VoteRepository {

    public static final String COLLECTION_VOTES = "votes";

    /** Vote count plus whether the signed-in user is one of the voters. */
    public static class VoteState {
        public final int count;
        public final boolean votedByCurrentUser;
        public VoteState(int count, boolean votedByCurrentUser) {
            this.count = count;
            this.votedByCurrentUser = votedByCurrentUser;
        }
    }

    private final FirebaseAuth auth;
    private final FirebaseFirestore firestore;

    public VoteRepository() {
        this(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance());
    }

    public VoteRepository(FirebaseAuth auth, FirebaseFirestore firestore) {
        this.auth = auth;
        this.firestore = firestore;
    }

    /** Records the signed-in user's vote. Voting twice is a no-op, not a second vote. */
    public void vote(String reportId, final RepositoryCallback<VoteState> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        if (isBlank(reportId)) {
            fail(callback, new IllegalArgumentException("A reportId is required."));
            return;
        }

        final String uid = user.getUid();
        Map<String, Object> data = new HashMap<>();
        data.put("uid", uid);
        data.put("createdAt", FieldValue.serverTimestamp());

        DocumentReference reference = votesFor(reportId.trim()).document(uid);
        reference.set(data)
                .addOnSuccessListener(ignored -> loadVoteState(reportId, callback))
                .addOnFailureListener(error -> fail(callback, error));
    }

    /** Removes the signed-in user's vote, so an upvote can be undone. */
    public void removeVote(String reportId, final RepositoryCallback<VoteState> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        if (isBlank(reportId)) {
            fail(callback, new IllegalArgumentException("A reportId is required."));
            return;
        }
        votesFor(reportId.trim()).document(user.getUid()).delete()
                .addOnSuccessListener(ignored -> loadVoteState(reportId, callback))
                .addOnFailureListener(error -> fail(callback, error));
    }

    /** Reads the real stored count and the signed-in user's own vote state. */
    public void loadVoteState(String reportId, final RepositoryCallback<VoteState> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        if (isBlank(reportId)) {
            fail(callback, new IllegalArgumentException("A reportId is required."));
            return;
        }
        final String uid = user.getUid();
        votesFor(reportId.trim()).get()
                .addOnSuccessListener(snapshots -> {
                    if (callback == null) return;
                    int count = 0;
                    boolean mine = false;
                    for (com.google.firebase.firestore.QueryDocumentSnapshot document : snapshots) {
                        count++;
                        if (uid.equals(document.getId())) mine = true;
                    }
                    callback.onSuccess(new VoteState(count, mine));
                })
                .addOnFailureListener(error -> fail(callback, error));
    }

    private CollectionReference votesFor(String reportId) {
        return firestore.collection(ComplaintRepository.COLLECTION_REPORTS)
                .document(reportId).collection(COLLECTION_VOTES);
    }

    private static void fail(RepositoryCallback<?> callback, Exception error) {
        if (callback != null) callback.onFailure(error);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
