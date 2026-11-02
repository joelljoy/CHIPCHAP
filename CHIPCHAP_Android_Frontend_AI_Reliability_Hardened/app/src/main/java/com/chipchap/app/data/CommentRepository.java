package com.chipchap.app.data;

import com.chipchap.app.models.Comment;
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
 * Firestore data layer for reports/{reportId}/comments/{commentId}.
 *
 * authorUid always comes from FirebaseAuth. authorName is the author's current users/{uid}.fullName
 * (the name Edit Profile writes), falling back to the Firebase Authentication display name, then the
 * email, only when that profile cannot be read. JavaScript supplies only the comment text, which is
 * validated natively: an empty comment is rejected before any write.
 */
public class CommentRepository {

    public static final String COLLECTION_COMMENTS = "comments";
    public static final int MAX_COMMENT_LENGTH = 1000;

    private final FirebaseAuth auth;
    private final FirebaseFirestore firestore;

    public CommentRepository() {
        this(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance());
    }

    public CommentRepository(FirebaseAuth auth, FirebaseFirestore firestore) {
        this.auth = auth;
        this.firestore = firestore;
    }

    /** Appends a comment owned by the signed-in user and returns the stored document. */
    public void addComment(String reportId, String text, final RepositoryCallback<Comment> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        if (isBlank(reportId)) {
            fail(callback, new IllegalArgumentException("A reportId is required."));
            return;
        }
        String cleanText = text == null ? "" : text.trim();
        if (cleanText.isEmpty()) {
            fail(callback, new IllegalArgumentException("Write a comment before sending."));
            return;
        }
        if (cleanText.length() > MAX_COMMENT_LENGTH) {
            fail(callback, new IllegalArgumentException("Keep the comment under " + MAX_COMMENT_LENGTH + " characters."));
            return;
        }

        final Comment comment = new Comment();
        comment.setAuthorUid(user.getUid());
        comment.setText(cleanText);
        // createdAt stays null so @ServerTimestamp writes the server value.

        // The Firestore profile is authoritative for the displayed name, so a name changed through
        // Edit Profile is used immediately. The read is keyed by the Auth uid only, and this path
        // never writes to the profile.
        final String authFallback = resolveAuthorName(user);
        final DocumentReference reference = commentsFor(reportId.trim()).document();
        firestore.collection(UserRepository.COLLECTION_USERS).document(user.getUid()).get()
                .addOnCompleteListener(profileTask -> {
                    String profileName = null;
                    if (profileTask.isSuccessful() && profileTask.getResult() != null
                            && profileTask.getResult().exists()) {
                        profileName = profileTask.getResult().getString("fullName");
                    }
                    comment.setAuthorName(profileName != null && !profileName.trim().isEmpty()
                            ? profileName.trim() : authFallback);
                    reference.set(comment)
                            .addOnSuccessListener(ignored -> reference.get()
                                    .addOnSuccessListener(snapshot -> {
                                        if (callback == null) return;
                                        callback.onSuccess(snapshot.exists()
                                                ? snapshot.toObject(Comment.class) : comment);
                                    })
                                    .addOnFailureListener(error -> fail(callback, error)))
                            .addOnFailureListener(error -> fail(callback, error));
                });
    }

    /** Reads a report's comments, oldest first. */
    public void loadComments(String reportId, final RepositoryCallback<List<Comment>> callback) {
        if (isBlank(reportId)) {
            fail(callback, new IllegalArgumentException("A reportId is required."));
            return;
        }
        commentsFor(reportId.trim())
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .get()
                .addOnSuccessListener(snapshots -> {
                    if (callback == null) return;
                    List<Comment> comments = new ArrayList<>();
                    for (QueryDocumentSnapshot document : snapshots) {
                        Comment comment = document.toObject(Comment.class);
                        if (comment != null) comments.add(comment);
                    }
                    callback.onSuccess(comments);
                })
                .addOnFailureListener(error -> fail(callback, error));
    }

    private CollectionReference commentsFor(String reportId) {
        return firestore.collection(ComplaintRepository.COLLECTION_REPORTS)
                .document(reportId).collection(COLLECTION_COMMENTS);
    }

    private static String resolveAuthorName(FirebaseUser user) {
        String displayName = user.getDisplayName();
        if (displayName != null && !displayName.trim().isEmpty()) return displayName.trim();
        String email = user.getEmail();
        return email == null ? "" : email.trim();
    }

    private static void fail(RepositoryCallback<?> callback, Exception error) {
        if (callback != null) callback.onFailure(error);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
