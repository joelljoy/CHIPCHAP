package com.chipchap.app.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checkpoint 3 - Civic Hotspot Intelligence for the government analytics screen.
 *
 * Deterministic DESCRIPTIVE counts over the reports the government portal actually loaded
 * (one bounded Firestore read). This is not prediction, machine learning or AI: every number
 * is a count of stored reports, and nothing is estimated or filled in.
 *
 * Pure Java (no Android or Firebase types) so it can be unit tested without a device.
 * Distances and coordinate validity reuse the Checkpoint 2 helpers in DuplicateReportFinder,
 * which this class only calls and never changes.
 *
 * Definitions
 *  - Unresolved: status SUBMITTED, UNDER_REVIEW, ASSIGNED or IN_PROGRESS (the lifecycle values the
 *    app writes). RESOLVED is never unresolved. A missing or unrecognised status is counted
 *    separately as "unknown" and is NOT treated as unresolved.
 *  - Open over 7 days: unresolved AND (now - createdAt) strictly greater than 7 x 24 h, with one
 *    "now" for the whole calculation. Exactly 7 days does not qualify. A missing, malformed or
 *    unsupported createdAt is never treated as old; such reports are counted separately.
 *  - Valid location: the app's existing rule (DuplicateReportFinder.validCoordinate and the map):
 *    both numbers present, finite, in range, and not exactly (0,0).
 *
 * Hotspot method (candidate areas; NOT validated thresholds, tune with real data)
 *  1. Take unresolved reports with a valid location, ordered by report id (then latitude,
 *     longitude) so the input order of Firestore results cannot change the outcome.
 *  2. Two reports are neighbours when their Haversine distance is <= HOTSPOT_RADIUS_METERS
 *     (the boundary distance counts as inside).
 *  3. Repeatedly pick the not-yet-used report with the most not-yet-used neighbours (ties: the
 *     first in the order of step 1). If it and its unused neighbours number fewer than
 *     HOTSPOT_MIN_REPORTS, stop. Otherwise they form one candidate area centred on that report's
 *     stored coordinate, and are all marked used.
 *  4. Because a used report can never join another area, overlapping circles never share a
 *     report: every report is counted in at most one area, so area counts can be added up.
 *     Every member is within the radius of the area's centre report (so within 2 x radius of
 *     each other).
 *  5. Areas are ranked by report count (descending), then by centre report id (ascending).
 * Geographic closeness does not mean the reports share a cause or describe the same incident.
 */
public final class CivicAnalytics {

    private CivicAnalytics() { }

    /** Unresolved reports older than this (strictly) are "open over 7 days". */
    public static final long AGING_THRESHOLD_MS = 7L * 24L * 60L * 60L * 1000L;
    /** Candidate-area radius around a centre report. A starting value, not empirically validated. */
    public static final double HOTSPOT_RADIUS_METERS = 250.0;
    /** Fewest unresolved reports (centre included) that make a candidate area. Not validated. */
    public static final int HOTSPOT_MIN_REPORTS = 3;
    /** Candidate areas sent to the screen; the total number found is reported as well. */
    public static final int MAX_HOTSPOTS = 5;

    public static final String RESOLVED = "RESOLVED";
    public static final List<String> OPEN_STATUSES = Collections.unmodifiableList(java.util.Arrays.asList(
            "SUBMITTED", "UNDER_REVIEW", "ASSIGNED", "IN_PROGRESS"));
    /** Status present but not one of the lifecycle values (or not text). */
    public static final String STATUS_UNRECOGNISED = "UNRECOGNISED";
    /** Status absent or blank. */
    public static final String STATUS_MISSING = "MISSING";

    public static final List<String> CATEGORIES = Collections.unmodifiableList(java.util.Arrays.asList(
            "Infrastructure", "Cleanliness", "Safety", "Other"));
    public static final String CATEGORY_NOT_SET = "Not set";
    public static final String CATEGORY_UNRECOGNISED = "Unrecognised";

    public enum TimeState { OK, MISSING, MALFORMED, UNSUPPORTED }
    public enum LocationState { VALID, MISSING, INVALID }

    /** One stored report, reduced to the fields analytics use. Built only by fromFields(). */
    public static final class Record {
        public final String reportId;
        /** Lifecycle key, STATUS_UNRECOGNISED or STATUS_MISSING. */
        public final String status;
        /** One of CATEGORIES, CATEGORY_NOT_SET or CATEGORY_UNRECOGNISED. */
        public final String category;
        public final LocationState locationState;
        public final double latitude;
        public final double longitude;
        public final TimeState timeState;
        public final long createdAtMs;
        /** Stored address, used only as a label, never for distances. */
        public final String address;
        /** False when the app's report model could not read the whole document. */
        public final boolean readable;

        private Record(String reportId, String status, String category, LocationState locationState,
                       double latitude, double longitude, TimeState timeState, long createdAtMs,
                       String address, boolean readable) {
            this.reportId = reportId;
            this.status = status;
            this.category = category;
            this.locationState = locationState;
            this.latitude = latitude;
            this.longitude = longitude;
            this.timeState = timeState;
            this.createdAtMs = createdAtMs;
            this.address = address;
            this.readable = readable;
        }

        public boolean isUnresolved() { return OPEN_STATUSES.contains(status); }
    }

    /**
     * Classifies raw Firestore field values without guessing. createdAt must already be a
     * java.util.Date (the repository converts a Firestore Timestamp with toDate()); any other
     * type, such as a string or a number, is UNSUPPORTED and is never parsed into a date.
     */
    public static Record fromFields(String reportId, Object status, Object category, Object latitude,
                                    Object longitude, Object createdAt, Object address, boolean readable) {
        String id = reportId == null ? "" : reportId;

        String statusKey;
        if (status == null) statusKey = STATUS_MISSING;
        else if (!(status instanceof String)) statusKey = STATUS_UNRECOGNISED;
        else {
            String s = ((String) status).trim().toUpperCase(Locale.ROOT);
            if (s.isEmpty()) statusKey = STATUS_MISSING;
            else if (RESOLVED.equals(s) || OPEN_STATUSES.contains(s)) statusKey = s;
            else statusKey = STATUS_UNRECOGNISED;
        }

        String categoryLabel;
        if (category == null) categoryLabel = CATEGORY_NOT_SET;
        else if (!(category instanceof String)) categoryLabel = CATEGORY_UNRECOGNISED;
        else {
            String c = ((String) category).trim();
            if (c.isEmpty()) categoryLabel = CATEGORY_NOT_SET;
            else {
                categoryLabel = CATEGORY_UNRECOGNISED;
                for (String known : CATEGORIES) if (known.equalsIgnoreCase(c)) { categoryLabel = known; break; }
            }
        }

        LocationState locationState;
        double lat = 0, lng = 0;
        if (latitude == null || longitude == null) {
            // One present and the other not is still unusable; neither is invented.
            locationState = (latitude == null && longitude == null) ? LocationState.MISSING : LocationState.INVALID;
        } else if (!(latitude instanceof Number) || !(longitude instanceof Number)) {
            locationState = LocationState.INVALID;
        } else {
            lat = ((Number) latitude).doubleValue();
            lng = ((Number) longitude).doubleValue();
            locationState = DuplicateReportFinder.validCoordinate(lat, lng) ? LocationState.VALID : LocationState.INVALID;
        }

        TimeState timeState;
        long createdAtMs = 0L;
        if (createdAt == null) timeState = TimeState.MISSING;
        else if (createdAt instanceof Date) {
            long ms = ((Date) createdAt).getTime();
            // The app never writes a time at or before 1970; such a value is not a real creation time.
            if (ms <= 0L) timeState = TimeState.MALFORMED;
            else { timeState = TimeState.OK; createdAtMs = ms; }
        } else timeState = TimeState.UNSUPPORTED;

        String label = address instanceof String ? ((String) address).trim() : "";
        return new Record(id, statusKey, categoryLabel, locationState, lat, lng, timeState, createdAtMs, label, readable);
    }

    /** One candidate area. Members are the reports counted in it (each report in one area at most). */
    public static final class Hotspot {
        public final String centreReportId;
        public final double latitude;
        public final double longitude;
        public final String address;
        public final List<String> reportIds;
        /** Member counts per category, in CATEGORIES order then the other labels; zero counts omitted. */
        public final Map<String, Integer> categories;

        Hotspot(Record centre, List<Record> members) {
            this.centreReportId = centre.reportId;
            this.latitude = centre.latitude;
            this.longitude = centre.longitude;
            this.address = centre.address;
            List<String> ids = new ArrayList<>();
            Map<String, Integer> byCategory = emptyCategoryMap();
            for (Record r : members) {
                ids.add(r.reportId);
                byCategory.put(r.category, byCategory.get(r.category) + 1);
            }
            Map<String, Integer> nonZero = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : byCategory.entrySet()) if (e.getValue() > 0) nonZero.put(e.getKey(), e.getValue());
            this.reportIds = Collections.unmodifiableList(ids);
            this.categories = Collections.unmodifiableMap(nonZero);
        }

        public int count() { return reportIds.size(); }
    }

    /** Everything the analytics screen shows, for one load. */
    public static final class Summary {
        /** Documents returned by the load (readable or not) - the "reports analysed" number. */
        public int total;
        /** Documents the app's report model could not fully read; still counted where possible. */
        public int unreadable;
        public int queryLimit;
        /** True when the load returned as many documents as its limit: older reports may be missing. */
        public boolean limitReached;
        public long nowMs;

        public int unresolved;
        public int resolved;
        /** Missing or unrecognised status: neither unresolved nor resolved. */
        public int unknownStatus;
        /** Lifecycle statuses in order, then STATUS_UNRECOGNISED and STATUS_MISSING. Sum == total. */
        public final Map<String, Integer> byStatus = new LinkedHashMap<>();
        /** CATEGORIES, then CATEGORY_NOT_SET and CATEGORY_UNRECOGNISED. Sum == total. */
        public final Map<String, Integer> byCategory = emptyCategoryMap();

        public int agingUnresolved;
        /** Unresolved reports whose createdAt is missing, malformed or unsupported. */
        public int unresolvedWithoutDate;

        public int unresolvedWithLocation;
        public int unresolvedWithoutLocation;
        /** All candidate areas found (hotspots holds at most MAX_HOTSPOTS of them). */
        public int hotspotCount;
        /** Reports inside ALL candidate areas found; each report counted once. */
        public int reportsInHotspots;
        public final List<Hotspot> hotspots = new ArrayList<>();
    }

    private static Map<String, Integer> emptyCategoryMap() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (String c : CATEGORIES) map.put(c, 0);
        map.put(CATEGORY_NOT_SET, 0);
        map.put(CATEGORY_UNRECOGNISED, 0);
        return map;
    }

    /** All metrics for the loaded records. nowMs is the single time reference for every age. */
    public static Summary compute(List<Record> records, int queryLimit, long nowMs) {
        Summary s = new Summary();
        s.queryLimit = queryLimit;
        s.nowMs = nowMs;
        s.byStatus.put("SUBMITTED", 0);
        s.byStatus.put("UNDER_REVIEW", 0);
        s.byStatus.put("ASSIGNED", 0);
        s.byStatus.put("IN_PROGRESS", 0);
        s.byStatus.put(RESOLVED, 0);
        s.byStatus.put(STATUS_UNRECOGNISED, 0);
        s.byStatus.put(STATUS_MISSING, 0);

        List<Record> located = new ArrayList<>();
        if (records != null) {
            for (Record r : records) {
                if (r == null) continue;
                s.total++;
                if (!r.readable) s.unreadable++;
                s.byStatus.put(r.status, s.byStatus.get(r.status) + 1);
                s.byCategory.put(r.category, s.byCategory.get(r.category) + 1);
                if (RESOLVED.equals(r.status)) { s.resolved++; continue; }
                if (!r.isUnresolved()) { s.unknownStatus++; continue; }
                s.unresolved++;
                if (r.timeState == TimeState.OK) {
                    if (nowMs - r.createdAtMs > AGING_THRESHOLD_MS) s.agingUnresolved++;
                } else {
                    s.unresolvedWithoutDate++;
                }
                if (r.locationState == LocationState.VALID) located.add(r);
                else s.unresolvedWithoutLocation++;
            }
        }
        s.limitReached = queryLimit > 0 && s.total >= queryLimit;
        s.unresolvedWithLocation = located.size();

        List<Hotspot> all = findHotspots(located);
        s.hotspotCount = all.size();
        for (Hotspot h : all) s.reportsInHotspots += h.count();
        for (int i = 0; i < all.size() && i < MAX_HOTSPOTS; i++) s.hotspots.add(all.get(i));
        return s;
    }

    private static final Comparator<Record> STABLE_ORDER = new Comparator<Record>() {
        @Override public int compare(Record a, Record b) {
            int c = a.reportId.compareTo(b.reportId);
            if (c != 0) return c;
            c = Double.compare(a.latitude, b.latitude);
            if (c != 0) return c;
            return Double.compare(a.longitude, b.longitude);
        }
    };

    /** The greedy, non-overlapping candidate areas described in the class comment, ranked. */
    static List<Hotspot> findHotspots(List<Record> located) {
        List<Hotspot> result = new ArrayList<>();
        if (located == null || located.size() < HOTSPOT_MIN_REPORTS) return result;
        List<Record> points = new ArrayList<>(located);
        Collections.sort(points, STABLE_ORDER);   // stable sort: equal keys keep input order
        int n = points.size();
        List<List<Integer>> neighbours = new ArrayList<>(n);
        for (int i = 0; i < n; i++) neighbours.add(new ArrayList<Integer>());
        // Cheap latitude pre-check: points further apart in latitude than the radius cannot match.
        double band = HOTSPOT_RADIUS_METERS * 1.01 / 111320.0;
        for (int i = 0; i < n; i++) {
            Record a = points.get(i);
            for (int j = i + 1; j < n; j++) {
                Record b = points.get(j);
                if (Math.abs(a.latitude - b.latitude) > band) continue;
                double d = DuplicateReportFinder.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude);
                if (d <= HOTSPOT_RADIUS_METERS) {
                    neighbours.get(i).add(j);
                    neighbours.get(j).add(i);
                }
            }
        }
        boolean[] used = new boolean[n];
        while (true) {
            int best = -1, bestCount = 0;
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                int count = 1;
                for (int j : neighbours.get(i)) if (!used[j]) count++;
                if (count > bestCount) { best = i; bestCount = count; }   // strict: first in order wins ties
            }
            if (best < 0 || bestCount < HOTSPOT_MIN_REPORTS) break;
            List<Record> members = new ArrayList<>();
            members.add(points.get(best));
            used[best] = true;
            for (int j : neighbours.get(best)) {
                if (!used[j]) { members.add(points.get(j)); used[j] = true; }
            }
            result.add(new Hotspot(points.get(best), members));
        }
        Collections.sort(result, new Comparator<Hotspot>() {
            @Override public int compare(Hotspot a, Hotspot b) {
                if (a.count() != b.count()) return b.count() - a.count();
                return a.centreReportId.compareTo(b.centreReportId);
            }
        });
        return result;
    }
}
