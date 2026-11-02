package com.chipchap.app.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Checkpoint 2 - POSSIBLE duplicate reports for a draft. Deterministic and explainable; no AI.
 *
 * A stored report is a candidate only when ALL of these hold:
 *  1. it has valid stored coordinates and lies within {@link #MATCH_RADIUS_METERS} of the draft's
 *     incident location (Haversine distance, computed here from the stored coordinates);
 *  2. its category equals the draft's category (both must be one of the existing categories);
 *  3. it is not RESOLVED.
 * Candidates are ordered by distance, then newest first, and at most {@link #MAX_CANDIDATES} are
 * returned. A candidate is never a confirmed duplicate: two different problems can sit 50 m apart.
 *
 * Pure Java on purpose (no Android/Firebase types) so the rules can be unit-tested directly.
 */
public final class DuplicateReportFinder {

    /**
     * Starting threshold, not proof of sameness: 100 m covers the usual GPS error of a phone fix
     * plus "the same stretch of street / the same junction", while a separate problem one block
     * away is normally further than that. Change it here only.
     */
    public static final double MATCH_RADIUS_METERS = 100.0;
    /** Small, readable list for the review screen. */
    public static final int MAX_CANDIDATES = 3;
    /** Upper bound on reports read per check (see ComplaintRepository.loadReportsInLatitudeBand). */
    public static final int QUERY_LIMIT = 150;
    /** The latitude band read from Firestore is a little wider than the radius, so edge cases are not lost. */
    public static final double BAND_MARGIN = 1.5;

    /** The existing CHIPCHAP categories (details-screen picker). No new taxonomy. */
    private static final String[] CATEGORIES = { "Infrastructure", "Cleanliness", "Safety", "Other" };
    private static final double EARTH_RADIUS_METERS = 6371008.8;
    private static final double METERS_PER_DEGREE_LATITUDE = 111320.0;

    private DuplicateReportFinder() { }

    /** The fields of a stored report the matcher needs - nothing about the reporter. */
    public static final class Record {
        public final String reportId, title, category, status;
        public final Double latitude, longitude;
        public final long createdAtMs;

        public Record(String reportId, String title, String category, String status,
                      Double latitude, Double longitude, long createdAtMs) {
            this.reportId = reportId; this.title = title; this.category = category; this.status = status;
            this.latitude = latitude; this.longitude = longitude; this.createdAtMs = createdAtMs;
        }
    }

    /** One possible duplicate, with the distance computed from the stored coordinates. */
    public static final class Candidate {
        public final String reportId, title, category, status;
        public final double distanceMeters;
        public final long createdAtMs;

        Candidate(Record r, String category, double distanceMeters) {
            this.reportId = r.reportId; this.title = r.title == null ? "" : r.title.trim();
            this.category = category; this.status = r.status == null ? "" : r.status.trim();
            this.distanceMeters = distanceMeters; this.createdAtMs = r.createdAtMs;
        }
    }

    /** Canonical category ("Infrastructure", ...) or null when missing or not an existing category. */
    public static String normalizeCategory(String value) {
        String v = value == null ? "" : value.trim();
        for (String c : CATEGORIES) if (c.equalsIgnoreCase(v)) return c;
        return null;
    }

    public static boolean validCoordinate(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) return false;
        double lat = latitude, lng = longitude;
        return !Double.isNaN(lat) && !Double.isInfinite(lat) && !Double.isNaN(lng) && !Double.isInfinite(lng)
                && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 && !(lat == 0 && lng == 0);
    }

    public static boolean isResolved(String status) {
        return status != null && "RESOLVED".equals(status.trim().toUpperCase(Locale.US));
    }

    /** Great-circle distance in metres (Haversine). */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** Half-height, in degrees of latitude, of the band that is read from Firestore. */
    public static double latitudeBandDegrees() {
        return MATCH_RADIUS_METERS * BAND_MARGIN / METERS_PER_DEGREE_LATITUDE;
    }

    /**
     * Candidates for a draft at (latitude, longitude) with the given category. The caller has
     * already checked the draft's coordinates and category; invalid stored records are skipped.
     */
    public static List<Candidate> find(double latitude, double longitude, String draftCategory, List<Record> stored) {
        List<Candidate> out = new ArrayList<>();
        String wanted = normalizeCategory(draftCategory);
        if (wanted == null || !validCoordinate(latitude, longitude) || stored == null) return out;
        for (Record r : stored) {
            if (r == null || r.reportId == null || r.reportId.trim().isEmpty()) continue;
            if (!validCoordinate(r.latitude, r.longitude)) continue;          // incomplete/invalid stored data
            String category = normalizeCategory(r.category);
            if (category == null || !category.equals(wanted)) continue;     // a missing category never matches
            if (isResolved(r.status)) continue;                               // not an active duplicate
            double d = distanceMeters(latitude, longitude, r.latitude, r.longitude);
            if (d > MATCH_RADIUS_METERS) continue;
            out.add(new Candidate(r, category, d));
        }
        Collections.sort(out, new Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) {
                int byDistance = Double.compare(a.distanceMeters, b.distanceMeters);
                return byDistance != 0 ? byDistance : Long.compare(b.createdAtMs, a.createdAtMs);
            }
        });
        return out.size() > MAX_CANDIDATES ? new ArrayList<>(out.subList(0, MAX_CANDIDATES)) : out;
    }
}
