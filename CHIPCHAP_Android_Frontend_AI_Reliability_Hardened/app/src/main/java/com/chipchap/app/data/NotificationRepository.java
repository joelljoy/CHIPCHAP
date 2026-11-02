package com.chipchap.app.data;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-app notifications stored at users/{uid}/notifications/{notificationId}.
 *
 * Reading and marking read always use the uid of FirebaseAuth.getCurrentUser(); no caller - and
 * never JavaScript - chooses whose notifications are touched. Firestore security rules enforce the
 * same ownership on the server.
 *
 * Notifications are created only by a real government action on a report (status change or
 * assignment), inside the SAME Firestore batch that updates the report and appends its timeline
 * event (see {@link #addReportNotification}). The recipient is the report's own citizenUid. There is
 * no push/FCM delivery: the citizen sees them in the app's Notifications screen.
 */
public class NotificationRepository {

    public static final String COLLECTION_NOTIFICATIONS = "notifications";
    public static final String TYPE_STATUS_CHANGED = "STATUS_CHANGED";
    public static final String TYPE_RESOLVED = "RESOLVED";
    public static final String TYPE_ASSIGNED = "ASSIGNED";

    /** One stored notification, as read back for display. */
    public static class Item {
        public String id = "";
        public String title = "";
        public String body = "";
        public String type = "";
        public String reportId = "";
        public String status = "";
        public boolean read;
        public Timestamp createdAt;
    }

    private final FirebaseAuth auth;
    private final FirebaseFirestore firestore;

    public NotificationRepository() {
        this(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance());
    }

    public NotificationRepository(FirebaseAuth auth, FirebaseFirestore firestore) {
        this.auth = auth;
        this.firestore = firestore;
    }

    /** The signed-in user's own notifications, newest first. */
    public void loadMine(int limit, final RepositoryCallback<List<Item>> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        notificationsFor(user.getUid())
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit <= 0 ? 50 : limit)
                .get()
                .addOnSuccessListener(snapshots -> {
                    if (callback == null) return;
                    List<Item> items = new ArrayList<>();
                    for (QueryDocumentSnapshot document : snapshots) items.add(toItem(document));
                    callback.onSuccess(items);
                })
                .addOnFailureListener(error -> fail(callback, error));
    }

    /** Number of the signed-in user's unread notifications (capped at 99 for the badge). */
    public void countUnread(final RepositoryCallback<Integer> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        notificationsFor(user.getUid())
                .whereEqualTo("read", false)
                .limit(99)
                .get()
                .addOnSuccessListener(snapshots -> {
                    if (callback != null) callback.onSuccess(snapshots.size());
                })
                .addOnFailureListener(error -> fail(callback, error));
    }

    /** Marks one of the signed-in user's own notifications as read. Only the read flag changes. */
    public void markRead(String notificationId, final RepositoryCallback<Void> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        if (notificationId == null || notificationId.trim().isEmpty()) {
            fail(callback, new IllegalArgumentException("A notification id is required."));
            return;
        }
        Map<String, Object> change = new HashMap<>();
        change.put("read", true);
        notificationsFor(user.getUid()).document(notificationId.trim()).update(change)
                .addOnSuccessListener(ignored -> { if (callback != null) callback.onSuccess(null); })
                .addOnFailureListener(error -> fail(callback, error));
    }

    /**
     * Adds a notification for the report's owner to a batch that ALSO updates that report and
     * appends its timeline event, so the notification exists only if the real action is committed.
     * actorUid is the signed-in government user; the recipient is the report's stored citizenUid.
     */
    public void addReportNotification(WriteBatch batch, String citizenUid, String reportId,
                                      String reportTitle, String newStatus, String type) {
        FirebaseUser actor = auth.getCurrentUser();
        if (batch == null || actor == null || isBlank(citizenUid) || isBlank(reportId)) {
            throw new IllegalArgumentException("A batch, signed-in actor, recipient and report are required.");
        }
        String title = reportTitle == null || reportTitle.trim().isEmpty() ? "Your report" : reportTitle.trim();
        Map<String, Object> data = new HashMap<>();
        data.put("type", type);
        data.put("reportId", reportId);
        data.put("status", newStatus);
        data.put("read", false);
        data.put("actorUid", actor.getUid());
        data.put("createdAt", FieldValue.serverTimestamp());
        if (TYPE_RESOLVED.equals(type)) {
            data.put("title", "Report resolved");
            data.put("body", "Your report '" + title + "' has been marked as resolved.");
        } else if (TYPE_ASSIGNED.equals(type)) {
            data.put("title", "Report assigned");
            data.put("body", "Your report '" + title + "' has been assigned for action.");
        } else {
            data.put("title", "Report status updated");
            data.put("body", "Your report '" + title + "' is now " + statusLabel(newStatus) + ".");
        }
        DocumentReference reference = notificationsFor(citizenUid.trim()).document();
        batch.set(reference, data);
    }

    /** Same wording the screens use for each workflow status. */
    public static String statusLabel(String status) {
        if (status == null) return "updated";
        switch (status.trim().toUpperCase()) {
            case "SUBMITTED": return "Submitted";
            case "UNDER_REVIEW": return "Under review";
            case "ASSIGNED": return "Assigned";
            case "IN_PROGRESS": return "In Progress";
            case "RESOLVED": return "Resolved";
            default: return status.trim();
        }
    }

    private CollectionReference notificationsFor(String uid) {
        return firestore.collection(UserRepository.COLLECTION_USERS).document(uid)
                .collection(COLLECTION_NOTIFICATIONS);
    }

    private static Item toItem(DocumentSnapshot snapshot) {
        Item item = new Item();
        item.id = snapshot.getId();
        item.title = safe(snapshot.getString("title"));
        item.body = safe(snapshot.getString("body"));
        item.type = safe(snapshot.getString("type"));
        item.reportId = safe(snapshot.getString("reportId"));
        item.status = safe(snapshot.getString("status"));
        Boolean read = snapshot.getBoolean("read");
        item.read = read != null && read;
        item.createdAt = snapshot.getTimestamp("createdAt");
        return item;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }

    private static void fail(RepositoryCallback<?> callback, Exception error) {
        if (callback != null) callback.onFailure(error);
    }
}
