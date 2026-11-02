package com.chipchap.app.data;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checkpoint F1 - driving route PREVIEW from the Geoapify Routing API.
 *
 * <p>Request (Geoapify Routing API, GET):
 * {@code https://api.geoapify.com/v1/routing?waypoints=LAT,LON|LAT,LON&mode=drive&units=metric&format=geojson&apiKey=KEY}
 * - waypoints are "lat,lon" pairs separated by "|"; the first is the device position, the second
 * the report's stored coordinates.
 *
 * <p>Response (format=geojson): a FeatureCollection whose first Feature is the route. Its geometry
 * is a MultiLineString of [lon, lat] positions (one LineString per leg between waypoints), and its
 * properties carry {@code distance} (meters with units=metric, confirmed by
 * {@code distance_units}) and {@code time} (estimated travel time in seconds). Everything is
 * validated here; a response without a usable route never produces a route.
 *
 * <p>Same networking pattern as {@link CloudEvidenceRepository}: plain {@link HttpURLConnection}
 * on a single background thread, result delivered on the main thread. No new dependency.
 *
 * <p>Checkpoint F2 adds PLACE SEARCH with the Geoapify Geocoding (Address Autocomplete) API,
 * {@code https://api.geoapify.com/v1/geocode/autocomplete?text=…&format=json&limit=…&lang=en[&bias=proximity:LON,LAT]&apiKey=KEY}.
 * With format=json the answer is {@code {"results":[{name, formatted, address_line1, address_line2,
 * lat, lon, result_type, category, …}]}}; fields may be missing and every result is validated.
 * Searches run on their own thread so a slow search never delays a route, and a search that has
 * been superseded before it starts is never sent.
 *
 * <p>The API key comes from BuildConfig (local.properties). A key inside an APK can be extracted;
 * this is an accepted MVP limitation, and the key is never logged.
 */
public class GeoapifyRouteService {

    /** A validated route. Geometry is a GeoJSON MultiLineString of [lon, lat] positions. */
    public static final class Route {
        public final JSONObject geometry;
        public final double distanceMeters;
        public final double timeSeconds;

        Route(JSONObject geometry, double distanceMeters, double timeSeconds) {
            this.geometry = geometry;
            this.distanceMeters = distanceMeters;
            this.timeSeconds = timeSeconds;
        }
    }

    /** A validated place from the Geocoding API. Coordinates are exactly what Geoapify returned. */
    public static final class Place {
        public final String name;
        public final String address;
        public final String type;
        public final double lat;
        public final double lng;

        Place(String name, String address, String type, double lat, double lng) {
            this.name = name;
            this.address = address;
            this.type = type;
            this.lat = lat;
            this.lng = lng;
        }
    }

    /** Exactly one method is called on the main thread, unless the search was superseded or the service shut down. */
    public interface PlaceCallback {
        void onPlaces(List<Place> places);
        void onFailed(String message);
    }

    /** Exactly one method is called, always on the main thread, unless the service was shut down. */
    public interface RouteCallback {
        void onRoute(Route route);
        void onFailed(String message);
    }

    public static final String MSG_NOT_CONFIGURED = "Route preview is not configured in this build.";
    public static final String MSG_UNAVAILABLE = "Unable to calculate a route right now.";
    public static final String MSG_NO_ROUTE = "No route could be found to this report.";
    public static final String MSG_LIMIT = "Navigation service limit reached. Please try again later.";
    public static final String MSG_NETWORK = "No network connection. Check your connection and try again.";
    public static final String MSG_TIMEOUT = "The route request timed out. Please try again.";
    public static final String MSG_AUTH = "Route preview could not be authorised. Check the Geoapify API key configuration.";

    public static final String MSG_SEARCH_UNAVAILABLE = "Place search is unavailable right now.";
    public static final String MSG_SEARCH_AUTH = "Place search could not be authorised. Check the Geoapify API key configuration.";

    private static final String TAG = "CHIPCHAP-ROUTE";
    private static final String ENDPOINT = "https://api.geoapify.com/v1/routing";
    private static final String GEOCODE_ENDPOINT = "https://api.geoapify.com/v1/geocode/autocomplete";
    /** Small result pages keep each search to one cheap request. */
    public static final int MAX_PLACE_RESULTS = 6;
    public static final int MAX_QUERY_CHARS = 120;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;
    /** A driving route is a few hundred KB at most; anything far larger is not trusted. */
    private static final int MAX_RESPONSE_CHARS = 6 * 1024 * 1024;
    private static final int MAX_ROUTE_POINTS = 100000;

    private final String apiKey;
    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final ExecutorService requests = Executors.newSingleThreadExecutor();
    private final ExecutorService searches = Executors.newSingleThreadExecutor();
    /** Latest search token; a queued search with an older token is skipped without a request. */
    private final AtomicInteger searchToken = new AtomicInteger();
    private volatile boolean closed = false;

    public GeoapifyRouteService(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /** False when local.properties supplied no usable key; nothing is requested in that case. */
    public boolean isConfigured() {
        if (apiKey.length() < 16 || apiKey.length() > 256) return false;
        if (apiKey.toUpperCase(Locale.US).contains("GEOAPIFY_API_KEY")) return false;
        for (int i = 0; i < apiKey.length(); i++) {
            char c = apiKey.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** One driving route from origin to destination. Never called automatically - explicit taps only. */
    public void fetchDrivingRoute(final double originLat, final double originLng,
                                  final double destLat, final double destLng,
                                  final RouteCallback callback) {
        if (!isConfigured()) { post(callback, null, MSG_NOT_CONFIGURED); return; }
        if (!validLatLng(originLat, originLng) || !validLatLng(destLat, destLng)) {
            post(callback, null, MSG_UNAVAILABLE);
            return;
        }
        try {
            requests.execute(() -> {
                try {
                    Route route = requestBlocking(originLat, originLng, destLat, destLng);
                    post(callback, route, null);
                } catch (RouteFailure failure) {
                    post(callback, null, failure.getMessage());
                } catch (SocketTimeoutException timeout) {
                    Log.w(TAG, "ROUTE_FAILED timeout");
                    post(callback, null, MSG_TIMEOUT);
                } catch (UnknownHostException | ConnectException | NoRouteToHostException offline) {
                    Log.w(TAG, "ROUTE_FAILED network " + offline.getClass().getSimpleName());
                    post(callback, null, MSG_NETWORK);
                } catch (IOException io) {
                    Log.w(TAG, "ROUTE_FAILED io " + io.getClass().getSimpleName());
                    post(callback, null, MSG_UNAVAILABLE);
                } catch (JSONException malformed) {
                    Log.w(TAG, "ROUTE_FAILED malformed JSON");
                    post(callback, null, MSG_UNAVAILABLE);
                } catch (RuntimeException unexpected) {
                    Log.w(TAG, "ROUTE_FAILED unexpected " + unexpected.getClass().getSimpleName());
                    post(callback, null, MSG_UNAVAILABLE);
                }
            });
        } catch (RejectedExecutionException shutDown) {
            // The Activity is being destroyed; nobody is waiting for this answer.
        }
    }

    /**
     * Place/address search (government map). Called only after the WebView has debounced typing;
     * text is trimmed and limited to MAX_QUERY_CHARS. bias is {lat, lng} or null.
     */
    public void searchPlaces(final String text, final double[] bias, final PlaceCallback callback) {
        final int token = searchToken.incrementAndGet();
        if (!isConfigured()) { postPlaces(callback, token, null, MSG_NOT_CONFIGURED); return; }
        final String query = text == null ? "" : text.trim();
        if (query.length() < 3 || query.length() > MAX_QUERY_CHARS) { postPlaces(callback, token, null, MSG_SEARCH_UNAVAILABLE); return; }
        try {
            searches.execute(() -> {
                if (token != searchToken.get()) return;   // superseded while queued: never sent
                try {
                    List<Place> places = searchBlocking(query, bias);
                    postPlaces(callback, token, places, null);
                } catch (RouteFailure failure) {
                    postPlaces(callback, token, null, failure.getMessage());
                } catch (SocketTimeoutException timeout) {
                    Log.w(TAG, "PLACE_SEARCH_FAILED timeout");
                    postPlaces(callback, token, null, "The place search timed out. Please try again.");
                } catch (UnknownHostException | ConnectException | NoRouteToHostException offline) {
                    Log.w(TAG, "PLACE_SEARCH_FAILED network " + offline.getClass().getSimpleName());
                    postPlaces(callback, token, null, MSG_NETWORK);
                } catch (IOException | JSONException | RuntimeException other) {
                    Log.w(TAG, "PLACE_SEARCH_FAILED " + other.getClass().getSimpleName());
                    postPlaces(callback, token, null, MSG_SEARCH_UNAVAILABLE);
                }
            });
        } catch (RejectedExecutionException shutDown) {
            // The Activity is being destroyed; nobody is waiting for this answer.
        }
    }

    /** Any search still queued or running is ignored; its answer is never delivered. */
    public void cancelSearches() {
        searchToken.incrementAndGet();
    }

    /** Stops the workers. Pending answers are dropped, never delivered to a destroyed screen. */
    public void shutdown() {
        closed = true;
        searchToken.incrementAndGet();
        requests.shutdownNow();
        searches.shutdownNow();
    }

    private List<Place> searchBlocking(String query, double[] bias) throws IOException, JSONException, RouteFailure {
        StringBuilder url = new StringBuilder(GEOCODE_ENDPOINT)
                .append("?text=").append(URLEncoder.encode(query, "UTF-8"))
                .append("&format=json&lang=en&limit=").append(MAX_PLACE_RESULTS);
        if (bias != null && bias.length == 2 && validLatLng(bias[0], bias[1])) {
            url.append("&bias=").append(URLEncoder.encode(
                    String.format(Locale.US, "proximity:%.5f,%.5f", bias[1], bias[0]), "UTF-8"));
        }
        url.append("&apiKey=").append(URLEncoder.encode(apiKey, "UTF-8"));
        String body = getJson(url.toString(), true);
        JSONObject root = new JSONObject(body);
        JSONArray results = root.optJSONArray("results");
        if (results == null) throw new RouteFailure(MSG_SEARCH_UNAVAILABLE);
        List<Place> places = new ArrayList<>();
        for (int i = 0; i < results.length() && places.size() < MAX_PLACE_RESULTS; i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            double lat = number(r, "lat");
            double lng = number(r, "lon");
            if (!validLatLng(lat, lng)) continue;   // a result without real coordinates is not offered
            String name = r.optString("name", "").trim();
            String line1 = r.optString("address_line1", "").trim();
            String line2 = r.optString("address_line2", "").trim();
            String formatted = r.optString("formatted", "").trim();
            String title = !name.isEmpty() ? name : (!line1.isEmpty() ? line1 : formatted);
            if (title.isEmpty()) continue;
            String address = !formatted.isEmpty() ? formatted : (line1 + (line2.isEmpty() ? "" : ", " + line2));
            if (address.equals(title)) address = line2;
            String type = r.optString("result_type", "").trim();
            places.add(new Place(clip(title, 120), clip(address, 200), clip(type, 40), lat, lng));
        }
        Log.i(TAG, "PLACE_SEARCH_OK results=" + places.size());
        return places;
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** One GET. A non-200 answer becomes an honest message; the URL (it carries the key) is never logged. */
    private String getJson(String url, boolean geocode) throws IOException, RouteFailure {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            String body = readAll(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
            Log.i(TAG, (geocode ? "PLACE_HTTP" : "ROUTE_HTTP") + " status=" + status);
            if (status != HttpURLConnection.HTTP_OK) {
                throw new RouteFailure(geocode ? searchMessageForStatus(status, body) : messageForStatus(status, body));
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private String searchMessageForStatus(int status, String body) {
        logServerError(status, body);
        if (status == 401 || status == 403) return MSG_SEARCH_AUTH;
        if (status == 429) return MSG_LIMIT;
        return MSG_SEARCH_UNAVAILABLE;
    }

    private String logServerError(int status, String body) {
        String serverMessage = "";
        try {
            JSONObject error = new JSONObject(body == null ? "" : body);
            serverMessage = error.optString("message", "");
        } catch (JSONException ignored) { }
        String logged = serverMessage.replace(apiKey, "***");
        if (logged.length() > 160) logged = logged.substring(0, 160);
        Log.w(TAG, "GEOAPIFY_HTTP_ERROR status=" + status + " message=" + logged);
        return serverMessage;
    }

    private Route requestBlocking(double originLat, double originLng, double destLat, double destLng)
            throws IOException, JSONException, RouteFailure {
        String waypoints = String.format(Locale.US, "%.6f,%.6f|%.6f,%.6f", originLat, originLng, destLat, destLng);
        String url = ENDPOINT
                + "?waypoints=" + URLEncoder.encode(waypoints, "UTF-8")
                + "&mode=drive&units=metric&format=geojson"
                + "&apiKey=" + URLEncoder.encode(apiKey, "UTF-8");
        return parse(getJson(url, false));
    }

    private String messageForStatus(int status, String body) {
        String serverMessage = logServerError(status, body);
        if (status == 401 || status == 403) return MSG_AUTH;
        // Only 429 is treated as a usage limit; other errors are not reported as quota problems.
        if (status == 429) return MSG_LIMIT;
        if (status >= 400 && status < 500) {
            String lower = serverMessage.toLowerCase(Locale.US);
            if (lower.contains("route") || lower.contains("road") || lower.contains("path")
                    || lower.contains("unreachable") || lower.contains("too far")) return MSG_NO_ROUTE;
        }
        return MSG_UNAVAILABLE;
    }

    /** Defensive parse of the documented GeoJSON response. Throws rather than invent any value. */
    private static Route parse(String body) throws JSONException, RouteFailure {
        if (body == null || body.isEmpty()) throw new RouteFailure(MSG_UNAVAILABLE);
        JSONObject root = new JSONObject(body);
        JSONArray features = root.optJSONArray("features");
        if (features == null) throw new RouteFailure(MSG_UNAVAILABLE);
        if (features.length() == 0) throw new RouteFailure(MSG_NO_ROUTE);
        JSONObject feature = features.optJSONObject(0);
        if (feature == null) throw new RouteFailure(MSG_UNAVAILABLE);

        JSONObject properties = feature.optJSONObject("properties");
        if (properties == null) throw new RouteFailure(MSG_UNAVAILABLE);
        String units = properties.optString("distance_units", "");
        if (!units.isEmpty() && !"meters".equalsIgnoreCase(units)) throw new RouteFailure(MSG_UNAVAILABLE);
        double distance = number(properties, "distance");
        double time = number(properties, "time");
        if (Double.isNaN(distance) || distance < 0 || Double.isNaN(time) || time < 0) {
            Log.w(TAG, "ROUTE_FAILED missing distance/time");
            throw new RouteFailure(MSG_UNAVAILABLE);
        }

        JSONObject geometry = feature.optJSONObject("geometry");
        if (geometry == null) throw new RouteFailure(MSG_UNAVAILABLE);
        String type = geometry.optString("type", "");
        JSONArray coordinates = geometry.optJSONArray("coordinates");
        if (coordinates == null) throw new RouteFailure(MSG_UNAVAILABLE);
        JSONArray lines = new JSONArray();
        if ("MultiLineString".equals(type)) {
            for (int i = 0; i < coordinates.length(); i++) {
                JSONArray line = coordinates.optJSONArray(i);
                if (line == null) throw new RouteFailure(MSG_UNAVAILABLE);
                lines.put(line);
            }
        } else if ("LineString".equals(type)) {
            lines.put(coordinates);
        } else {
            throw new RouteFailure(MSG_UNAVAILABLE);
        }

        JSONArray cleanLines = new JSONArray();
        int total = 0;
        for (int i = 0; i < lines.length(); i++) {
            JSONArray line = lines.getJSONArray(i);
            JSONArray clean = new JSONArray();
            for (int j = 0; j < line.length(); j++) {
                JSONArray point = line.optJSONArray(j);
                if (point == null || point.length() < 2) throw new RouteFailure(MSG_UNAVAILABLE);
                double lon = point.optDouble(0, Double.NaN);
                double lat = point.optDouble(1, Double.NaN);
                if (!validLatLng(lat, lon)) throw new RouteFailure(MSG_UNAVAILABLE);
                JSONArray position = new JSONArray();
                position.put(lon);
                position.put(lat);
                clean.put(position);
                if (++total > MAX_ROUTE_POINTS) throw new RouteFailure(MSG_UNAVAILABLE);
            }
            if (clean.length() >= 2) cleanLines.put(clean);
        }
        if (cleanLines.length() == 0) throw new RouteFailure(MSG_NO_ROUTE);

        JSONObject cleanGeometry = new JSONObject();
        cleanGeometry.put("type", "MultiLineString");
        cleanGeometry.put("coordinates", cleanLines);
        Log.i(TAG, "ROUTE_PARSED points=" + total + " meters=" + Math.round(distance) + " seconds=" + Math.round(time));
        return new Route(cleanGeometry, distance, time);
    }

    private static double number(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Number)) return Double.NaN;
        double d = ((Number) value).doubleValue();
        return Double.isInfinite(d) ? Double.NaN : d;
    }

    private static boolean validLatLng(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng) && !Double.isInfinite(lat) && !Double.isInfinite(lng)
                && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
                if (builder.length() > MAX_RESPONSE_CHARS) throw new IOException("Route response too large");
            }
            return builder.toString();
        } finally {
            reader.close();
        }
    }

    private void post(final RouteCallback callback, final Route route, final String failure) {
        if (callback == null || closed) return;
        mainThread.post(() -> {
            if (closed) return;
            if (failure == null) callback.onRoute(route);
            else callback.onFailed(failure);
        });
    }

    private void postPlaces(final PlaceCallback callback, final int token, final List<Place> places, final String failure) {
        if (callback == null || closed) return;
        mainThread.post(() -> {
            if (closed || token != searchToken.get()) return;   // superseded or cancelled: dropped
            if (failure == null) callback.onPlaces(places);
            else callback.onFailed(failure);
        });
    }

    /** A failure whose message is already the honest, user-facing text. */
    private static final class RouteFailure extends Exception {
        RouteFailure(String message) { super(message); }
    }
}
