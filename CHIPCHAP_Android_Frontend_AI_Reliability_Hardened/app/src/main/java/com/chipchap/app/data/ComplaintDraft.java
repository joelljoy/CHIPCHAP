package com.chipchap.app.data;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * In-memory state for the complaint the citizen is currently creating.
 *
 * It exists because the capture -> details -> review flow spans three separate WebView page loads,
 * and a JavaScript variable does not survive those navigations. Nothing here is written to disk:
 * the draft lives for as long as the Activity does and is cleared after a successful submission.
 *
 * `localImagePath` is the absolute path of a photo in app-owned storage. The photo file itself
 * outlives the draft (see LocalPhotoRepository) so it is still available to a later phase.
 *
 * `severity` is captured from the existing Stitch UI so the review screen can show what the citizen
 * selected, but it is NOT part of the Phase 1 Complaint model and is not written to Firestore.
 * See IMPLEMENTATION_STATUS.md — adding it needs a model and security-rule change.
 */
public class ComplaintDraft {

    // Written on the Android main thread and read on the WebView JavaScript-bridge thread,
    // so each field is volatile to guarantee the review screen never shows a stale value.
    private volatile String localImagePath = "";
    /** Cloudinary URL for the captured photo; empty until the upload succeeds. */
    private volatile String evidenceUrl = "";
    /** True while the Cloudinary upload for the current photo is still running. */
    private volatile boolean evidenceUploading = false;
    private volatile String title = "";
    private volatile String description = "";
    private volatile String category = "";
    private volatile String severity = "";
    private volatile String address = "";
    private volatile Double latitude;
    private volatile Double longitude;

    /** Truthful, user-facing description of how location capture went. Never a fake coordinate. */
    private volatile String locationStatus = "";

    public String getEvidenceUrl() { return evidenceUrl; }
    public void setEvidenceUrl(String evidenceUrl) { this.evidenceUrl = safe(evidenceUrl); }

    public boolean isEvidenceUploading() { return evidenceUploading; }
    public void setEvidenceUploading(boolean uploading) { this.evidenceUploading = uploading; }

    public String getLocalImagePath() { return localImagePath; }
    public void setLocalImagePath(String localImagePath) { this.localImagePath = safe(localImagePath); }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = safe(title); }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = safe(description); }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = safe(category); }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = safe(severity); }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = safe(address); }

    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }

    public void setCoordinates(Double latitude, Double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public void clearCoordinates() {
        this.latitude = null;
        this.longitude = null;
        this.address = "";
    }

    public String getLocationStatus() { return locationStatus; }
    public void setLocationStatus(String locationStatus) { this.locationStatus = safe(locationStatus); }

    public boolean hasPhoto() { return !localImagePath.isEmpty(); }
    public boolean hasCoordinates() { return latitude != null && longitude != null; }

    /** Resets everything except the photo file on disk, which is deleted separately if needed. */
    public void clear() {
        localImagePath = "";
        evidenceUrl = "";
        evidenceUploading = false;
        title = "";
        description = "";
        category = "";
        severity = "";
        address = "";
        latitude = null;
        longitude = null;
        locationStatus = "";
    }

    /** Serialized for the WebView. The photo preview is fetched separately because it is large. */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("hasPhoto", hasPhoto());
        json.put("title", title);
        json.put("description", description);
        json.put("category", category);
        json.put("severity", severity);
        json.put("address", address);
        json.put("hasCoordinates", hasCoordinates());
        json.put("latitude", latitude == null ? JSONObject.NULL : latitude);
        json.put("longitude", longitude == null ? JSONObject.NULL : longitude);
        json.put("locationStatus", locationStatus);
        return json;
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
