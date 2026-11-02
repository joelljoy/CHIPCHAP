package com.chipchap.app.models;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.ServerTimestamp;

/**
 * Community comment stored at reports/{reportId}/comments/{commentId}.
 *
 * authorUid always comes from FirebaseAuth inside CommentRepository and is never accepted from
 * JavaScript. Comments are append-only: there is no update or delete path in this MVP.
 */
public class Comment {

    private String authorUid;
    private String authorName;
    private String text;

    @ServerTimestamp private Timestamp createdAt;

    public Comment() { }

    public String getAuthorUid() { return authorUid; }
    public void setAuthorUid(String authorUid) { this.authorUid = authorUid; }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}
