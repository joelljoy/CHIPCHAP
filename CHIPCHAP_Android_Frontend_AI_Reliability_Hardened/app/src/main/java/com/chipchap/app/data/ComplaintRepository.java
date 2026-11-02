package com.chipchap.app.data;

import com.chipchap.app.models.Complaint;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.WriteBatch;
import java.util.HashMap;
import java.util.Map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Firestore data layer for reports/{reportId}.
 *
 * Ownership and privileged fields are decided here, not by the caller:
 *  - reportId is a Firestore-generated document id;
 *  - citizenUid always comes from FirebaseAuth.getCurrentUser();
 *  - citizenName comes from the citizen's own users/{uid}.fullName (the profile Edit Profile
 *    writes), falling back to the Firebase Authentication display name, then the email;
 *  - status is forced to SUBMITTED at creation;
 *  - assignedGovernmentUid / assignedGovernmentName are always null at creation;
 *  - createdAt / updatedAt are Firestore server timestamps.
 *
 * Anything a caller sets on those fields in the draft is ignored. Only title, description,
 * category, latitude, longitude, address and localImagePath are taken from the draft.
 *
 * This repository is the native foundation for the next phase. The existing Stitch complaint
 * screens are intentionally not wired to it yet.
 */
public class ComplaintRepository {

    public static final String COLLECTION_REPORTS = "reports";

    private final FirebaseAuth auth;
    private final FirebaseFirestore firestore;

    public ComplaintRepository() {
        this(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance());
    }

    public ComplaintRepository(FirebaseAuth auth, FirebaseFirestore firestore) {
        this.auth = auth;
        this.firestore = firestore;
    }

    /**
     * Persists a new report owned by the currently authenticated user and returns the stored
     * complaint, including its generated reportId.
     */
    public void submitComplaint(Complaint draft, final RepositoryCallback<Complaint> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        if (draft == null) {
            fail(callback, new IllegalArgumentException("A complaint draft is required."));
            return;
        }

        final DocumentReference reference = firestore.collection(COLLECTION_REPORTS).document();
        final Complaint complaint = new Complaint();

        // Server/auth-derived fields.
        complaint.setReportId(reference.getId());
        complaint.setCitizenUid(user.getUid());
        // citizenName is resolved from the Firestore profile just before the write, below.
        complaint.setStatus(Complaint.STATUS_SUBMITTED);
        complaint.setAssignedGovernmentUid(null);
        complaint.setAssignedGovernmentName(null);
        // A citizen can never create a report carrying a resolution photo; only the government
        // workflow writes that field, and the security rules enforce the same thing server-side.
        complaint.setResolutionEvidenceUrl(null);
        // createdAt / updatedAt stay null so @ServerTimestamp writes the server value.

        // Caller-supplied content fields.
        complaint.setTitle(trimmed(draft.getTitle()));
        complaint.setDescription(trimmed(draft.getDescription()));
        complaint.setCategory(trimmed(draft.getCategory()));
        complaint.setSeverity(trimmed(draft.getSeverity()));
        complaint.setLatitude(draft.getLatitude());
        complaint.setLongitude(draft.getLongitude());
        complaint.setAddress(trimmed(draft.getAddress()));
        complaint.setLocalImagePath(trimmed(draft.getLocalImagePath()));
        // Only a real Cloudinary HTTPS URL is stored. An empty value means the upload did not
        // succeed; the report is still created and the local photo remains the same-device
        // fallback, so evidence is never faked and never lost.
        complaint.setEvidenceUrl(cloudUrlOrEmpty(draft.getEvidenceUrl()));

        // The Firestore profile is authoritative for the displayed name, so a name changed through
        // Edit Profile is used immediately. The document read is keyed by the Auth uid only.
        final String authFallback = resolveCitizenName(user);
        firestore.collection(UserRepository.COLLECTION_USERS).document(user.getUid()).get()
                .addOnCompleteListener(task -> {
                    String profileName = null;
                    if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                        profileName = task.getResult().getString("fullName");
                    }
                    complaint.setCitizenName(profileName != null && !profileName.trim().isEmpty()
                            ? profileName.trim() : authFallback);
                    reference.set(complaint)
                            .addOnSuccessListener(ignored -> loadComplaint(reference.getId(), callback))
                            .addOnFailureListener(error -> fail(callback, error));
                });
    }

    /** Reads a single report. Returns null when the document does not exist. */
    public void loadComplaint(String reportId, final RepositoryCallback<Complaint> callback) {
        if (reportId == null || reportId.trim().isEmpty()) {
            fail(callback, new IllegalArgumentException("A reportId is required."));
            return;
        }
        firestore.collection(COLLECTION_REPORTS).document(reportId.trim()).get()
                .addOnSuccessListener(snapshot -> {
                    if (callback == null) return;
                    callback.onSuccess(snapshot.exists() ? toComplaint(snapshot) : null);
                })
                .addOnFailureListener(error -> fail(callback, error));
    }

    /**
     * Reads the authenticated user's own reports, newest first.
     * Sorting happens in memory so this phase needs no composite Firestore index.
     */
    public void loadMyComplaints(final RepositoryCallback<List<Complaint>> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        firestore.collection(COLLECTION_REPORTS)
                .whereEqualTo("citizenUid", user.getUid())
                .get()
                .addOnSuccessListener(snapshots -> {
                    if (callback == null) return;
                    List<Complaint> complaints = new ArrayList<>();
                    for (QueryDocumentSnapshot document : snapshots) {
                        Complaint complaint = toComplaint(document);
                        if (complaint != null) complaints.add(complaint);
                    }
                    Collections.sort(complaints, NEWEST_FIRST);
                    callback.onSuccess(complaints);
                })
                .addOnFailureListener(error -> fail(callback, error));
    }

    /**
     * Recent reports for the community feed, newest first.
     * Ordered on a single field, so no composite Firestore index is required.
     */
    public void loadCommunityReports(int limit, final RepositoryCallback<List<Complaint>> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        firestore.collection(COLLECTION_REPORTS)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit <= 0 ? 20 : limit)
                .get()
                .addOnSuccessListener(snapshots -> {
                    if (callback == null) return;
                    List<Complaint> complaints = new ArrayList<>();
                    for (QueryDocumentSnapshot document : snapshots) {
                        Complaint complaint = toComplaint(document);
                        if (complaint != null) complaints.add(complaint);
                    }
                    callback.onSuccess(complaints);
                })
                .addOnFailureListener(error -> fail(callback, error));
    }

    /**
     * Checkpoint 2 - reports whose stored latitude lies in [minLatitude, maxLatitude], for the
     * possible-duplicate check. One read-only range query on a single field (Firestore's automatic
     * single-field index - no composite index, no schema or rules change, nothing is written).
     * Longitude, category, status and the exact distance are checked by the caller. Bounded by
     * limit; the caller reports honestly when the limit was reached.
     */
    public void loadReportsInLatitudeBand(double minLatitude, double maxLatitude, int limit,
                                          final RepositoryCallback<List<Complaint>> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        firestore.collection(COLLECTION_REPORTS)
                .whereGreaterThanOrEqualTo("latitude", minLatitude)
                .whereLessThanOrEqualTo("latitude", maxLatitude)
                .limit(limit <= 0 ? 50 : limit)
                .get()
                .addOnSuccessListener(snapshots -> {
                    if (callback == null) return;
                    List<Complaint> complaints = new ArrayList<>();
                    for (QueryDocumentSnapshot document : snapshots) {
                        Complaint complaint = toComplaint(document);
                        if (complaint != null) complaints.add(complaint);
                    }
                    callback.onSuccess(complaints);
                })
                .addOnFailureListener(error -> fail(callback, error));
    }

    /**
     * Checkpoint 3 - the government portal's report load (dashboard, queue, analytics, profile).
     * The SAME read as loadCommunityReports(limit): newest first by createdAt, bounded by limit,
     * one Firestore query, nothing written. The difference is that every document is read
     * defensively: a document the report model cannot map (for example a field stored with the
     * wrong type) no longer aborts the whole load. It is left out of the report list, which could
     * not show it anyway, but it is still counted for analytics from its raw field values.
     * Ordering by createdAt means Firestore does not return documents with no createdAt field.
     */
    public void loadGovernmentReports(int limit, final RepositoryCallback<GovernmentReports> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, new IllegalStateException("No authenticated Firebase user."));
            return;
        }
        final int boundedLimit = limit <= 0 ? 20 : limit;
        firestore.collection(COLLECTION_REPORTS)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(boundedLimit)
                .get()
                .addOnSuccessListener(snapshots -> {
                    if (callback == null) return;
                    List<Complaint> complaints = new ArrayList<>();
                    List<CivicAnalytics.Record> records = new ArrayList<>();
                    for (QueryDocumentSnapshot document : snapshots) {
                        Complaint complaint;
                        try {
                            complaint = toComplaint(document);
                        } catch (RuntimeException unreadable) {
                            complaint = null;   // wrong field type: counted below, not shown
                        }
                        if (complaint != null) complaints.add(complaint);
                        records.add(analyticsRecord(document, complaint != null));
                    }
                    callback.onSuccess(new GovernmentReports(complaints, records, boundedLimit));
                })
                .addOnFailureListener(error -> fail(callback, error));
    }

    /** Raw field values only; CivicAnalytics decides what is usable. Nothing is guessed here. */
    private static CivicAnalytics.Record analyticsRecord(DocumentSnapshot document, boolean readable) {
        Object createdAt = document.get("createdAt");
        if (createdAt instanceof Timestamp) createdAt = ((Timestamp) createdAt).toDate();
        return CivicAnalytics.fromFields(document.getId(), document.get("status"), document.get("category"),
                document.get("latitude"), document.get("longitude"), createdAt, document.get("address"), readable);
    }

    /** One government load: the readable reports, one analytics record per document, the limit. */
    public static final class GovernmentReports {
        public final List<Complaint> complaints;
        public final List<CivicAnalytics.Record> records;
        public final int limit;

        GovernmentReports(List<Complaint> complaints, List<CivicAnalytics.Record> records, int limit) {
            this.complaints = complaints;
            this.records = records;
            this.limit = limit;
        }
    }

    /** Workflow statuses a government user may set. Anything else from JavaScript is rejected. */
    public static final java.util.List<String> GOVERNMENT_STATUSES = java.util.Arrays.asList(
            "SUBMITTED", "UNDER_REVIEW", "ASSIGNED", "IN_PROGRESS", "RESOLVED");

    /**
     * One atomic government action on a stored report: ONE WriteBatch that
     *  1. updates the report's status + updatedAt (and assignment fields when given),
     *  2. appends the timeline event,
     *  3. creates the citizen notification for the report's stored owner.
     * Either all three are committed or none is. The report must be the document just read from
     * Firestore - its reportId, citizenUid and title are never taken from JavaScript. actorUid is
     * the signed-in government user.
     */
    public void applyGovernmentUpdate(Complaint report, String newStatus, String assignedUid, String assignedName,
                                      String resolutionEvidenceUrl,
                                      String timelineMessage, String actorName, String notificationType,
                                      NotificationRepository notifications, final RepositoryCallback<Void> callback) {
        FirebaseUser actor = auth.getCurrentUser();
        if (actor == null) { fail(callback, new IllegalStateException("No authenticated Firebase user.")); return; }
        if (report == null || report.getReportId() == null || report.getReportId().isEmpty()
                || report.getCitizenUid() == null || report.getCitizenUid().isEmpty()) {
            fail(callback, new IllegalArgumentException("The report could not be read.")); return;
        }
        if (newStatus == null || !GOVERNMENT_STATUSES.contains(newStatus)) {
            fail(callback, new IllegalArgumentException("That status is not allowed.")); return;
        }
        String reportId = report.getReportId();
        DocumentReference reportRef = firestore.collection(COLLECTION_REPORTS).document(reportId);
        WriteBatch batch = firestore.batch();

        Map<String, Object> change = new HashMap<>();
        change.put("status", newStatus);
        change.put("updatedAt", FieldValue.serverTimestamp());
        if (assignedUid != null && assignedName != null) {
            change.put("assignedGovernmentUid", assignedUid);
            change.put("assignedGovernmentName", assignedName);
        }
        // The resolution photo rides in the SAME batch as the status, timeline event and
        // notification, so a report can never show as resolved without its evidence, or the
        // reverse. A non-Cloudinary or failed URL is simply not added - never a placeholder.
        String resolutionUrl = cloudUrlOrEmpty(resolutionEvidenceUrl);
        if (!resolutionUrl.isEmpty()) {
            change.put("resolutionEvidenceUrl", resolutionUrl);
        }
        batch.update(reportRef, change);

        Map<String, Object> event = new HashMap<>();
        event.put("status", newStatus);
        event.put("message", timelineMessage == null ? "" : timelineMessage.trim());
        event.put("actorUid", actor.getUid());
        event.put("actorName", actorName == null ? "" : actorName.trim());
        event.put("createdAt", FieldValue.serverTimestamp());
        batch.set(reportRef.collection(TimelineRepository.COLLECTION_TIMELINE).document(), event);

        notifications.addReportNotification(batch, report.getCitizenUid(), reportId,
                report.getTitle(), newStatus, notificationType);

        batch.commit()
                .addOnSuccessListener(ignored -> { if (callback != null) callback.onSuccess(null); })
                .addOnFailureListener(error -> fail(callback, error));
    }

    /** Accepts only an https://res.cloudinary.com/ URL; anything else becomes empty. */
    private static String cloudUrlOrEmpty(String url) {
        String value = url == null ? "" : url.trim();
        return value.startsWith("https://res.cloudinary.com/") ? value : "";
    }

    private static final Comparator<Complaint> NEWEST_FIRST = new Comparator<Complaint>() {
        @Override
        public int compare(Complaint left, Complaint right) {
            Timestamp a = left == null ? null : left.getCreatedAt();
            Timestamp b = right == null ? null : right.getCreatedAt();
            if (a == null && b == null) return 0;
            if (a == null) return 1;
            if (b == null) return -1;
            return b.compareTo(a);
        }
    };

    private static Complaint toComplaint(DocumentSnapshot snapshot) {
        Complaint complaint = snapshot.toObject(Complaint.class);
        if (complaint != null && (complaint.getReportId() == null || complaint.getReportId().isEmpty())) {
            complaint.setReportId(snapshot.getId());
        }
        return complaint;
    }

    private static String resolveCitizenName(FirebaseUser user) {
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
