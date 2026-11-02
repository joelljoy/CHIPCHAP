package com.chipchap.app.models;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

/**
 * Application profile stored at users/{uid}.
 *
 * Firebase Authentication remains authoritative for uid, email, emailVerified and session state.
 * Firestore is authoritative for the application profile fields below.
 *
 * The role field is never written by the client after creation (see UserRepository) and must not
 * be treated as an authorization boundary on its own. Trusted authorization still requires
 * Firebase custom claims plus server-enforced rules (see FIRESTORE_SECURITY.md).
 */
public class UserProfile {

    public static final String ROLE_CITIZEN = "CITIZEN";
    public static final String ROLE_GOVERNMENT = "GOVERNMENT";

    private String uid;
    private String email;
    private String fullName;
    private String neighborhood;
    private String role;

    @ServerTimestamp private Timestamp createdAt;
    @ServerTimestamp private Timestamp updatedAt;

    public UserProfile() { }

    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getNeighborhood() { return neighborhood; }
    public void setNeighborhood(String neighborhood) { this.neighborhood = neighborhood; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }

    /**
     * Convenience read of the stored profile role. This is NOT an authorization check.
     * Excluded from Firestore serialization so it never becomes a stored field.
     */
    @Exclude
    public boolean hasGovernmentRole() { return ROLE_GOVERNMENT.equals(role); }
}
