package com.chipchap.app.models;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.ServerTimestamp;

/**
 * Report history entry stored at reports/{reportId}/timeline/{timelineId}.
 *
 * actorUid is always derived from FirebaseAuth.getCurrentUser() inside TimelineRepository.
 * Timeline entries are append-only: existing history must never be overwritten by a status change.
 */
public class TimelineEvent {

    private String status;
    private String message;
    private String actorUid;
    private String actorName;

    @ServerTimestamp private Timestamp createdAt;

    public TimelineEvent() { }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getActorUid() { return actorUid; }
    public void setActorUid(String actorUid) { this.actorUid = actorUid; }

    public String getActorName() { return actorName; }
    public void setActorName(String actorName) { this.actorName = actorName; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}
