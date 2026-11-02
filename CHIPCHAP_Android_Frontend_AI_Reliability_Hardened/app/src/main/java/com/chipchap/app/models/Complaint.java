package com.chipchap.app.models;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.ServerTimestamp;

/**
 * Civic report stored at reports/{reportId}.
 *
 * citizenUid is always derived from FirebaseAuth.getCurrentUser() inside ComplaintRepository and is
 * never accepted from JavaScript, the WebView or any client-supplied value.
 *
 * status, assignedGovernmentUid and assignedGovernmentName are privileged fields. The citizen
 * client only ever writes the initial SUBMITTED status at creation time; later transitions belong
 * to trusted government workflows in a later phase.
 *
 * localImagePath is a local-device file reference only. Firebase Storage is not on the current
 * plan, so no evidence is uploaded and no Base64 image data is ever written to Firestore.
 */
public class Complaint {

    public static final String STATUS_SUBMITTED = "SUBMITTED";

    private String reportId;
    private String citizenUid;
    private String citizenName;
    private String title;
    private String description;
    private String category;
    private String severity;
    private String status;
    private Double latitude;
    private Double longitude;
    private String address;
    private String localImagePath;
    /** Cloudinary HTTPS URL of the citizen's evidence photo. Empty/absent on older reports. */
    private String evidenceUrl;
    /** Cloudinary HTTPS URL of the government's after/resolution photo. Government-written only. */
    private String resolutionEvidenceUrl;
    private String assignedGovernmentUid;
    private String assignedGovernmentName;

    @ServerTimestamp private Timestamp createdAt;
    @ServerTimestamp private Timestamp updatedAt;

    public Complaint() { }

    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }

    public String getCitizenUid() { return citizenUid; }
    public void setCitizenUid(String citizenUid) { this.citizenUid = citizenUid; }

    public String getCitizenName() { return citizenName; }
    public void setCitizenName(String citizenName) { this.citizenName = citizenName; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getLocalImagePath() { return localImagePath; }
    public void setLocalImagePath(String localImagePath) { this.localImagePath = localImagePath; }

    public String getEvidenceUrl() { return evidenceUrl; }
    public void setEvidenceUrl(String evidenceUrl) { this.evidenceUrl = evidenceUrl; }

    public String getResolutionEvidenceUrl() { return resolutionEvidenceUrl; }
    public void setResolutionEvidenceUrl(String resolutionEvidenceUrl) { this.resolutionEvidenceUrl = resolutionEvidenceUrl; }

    public String getAssignedGovernmentUid() { return assignedGovernmentUid; }
    public void setAssignedGovernmentUid(String assignedGovernmentUid) { this.assignedGovernmentUid = assignedGovernmentUid; }

    public String getAssignedGovernmentName() { return assignedGovernmentName; }
    public void setAssignedGovernmentName(String assignedGovernmentName) { this.assignedGovernmentName = assignedGovernmentName; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }
}
