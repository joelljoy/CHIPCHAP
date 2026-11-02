package com.chipchap.app.data;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Locale;

/**
 * One-shot device location capture for a complaint, using the platform LocationManager.
 *
 * Deliberately not integrated with Google Maps or the Play Services fused provider — this phase
 * only needs coordinates for the report document.
 *
 * It never invents a position. When permission is missing, location services are off, or no fix
 * arrives before the timeout, the callback reports unavailable with a truthful reason and the
 * report is submitted with null coordinates.
 */
public class LocationProvider {

    public interface LocationResultCallback {
        void onLocation(double latitude, double longitude, String address);
        void onUnavailable(String reason);
    }

    /** A cached fix older than this is not good enough to describe "where the issue is". */
    private static final long MAX_LAST_KNOWN_AGE_MS = 5 * 60 * 1000L;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public LocationProvider(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Either precision is enough: on Android 12+ the user may grant only approximate location. */
    public boolean hasPermission() {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private boolean granted(String permission) {
        return context.checkPermission(permission, android.os.Process.myPid(), android.os.Process.myUid())
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Asks for a current fix, falling back to a recent cached one. Always calls exactly one
     * callback method, on the main thread.
     */
    public void requestCurrentLocation(long timeoutMs, final LocationResultCallback callback) {
        requestLocation(timeoutMs, true, callback);
    }

    /**
     * Map centring. The same one-shot request, with two differences that only affect speed:
     * the fix is reported the moment it arrives (no reverse-geocoding round trip, the map does not
     * need an address), and on devices without the fused provider the network provider is asked
     * alongside GPS, so a quick coarse fix is not held up by a slow GPS cold start. The first real
     * fix wins; nothing is ever invented. Always calls exactly one callback method, on the main thread.
     */
    public void requestQuickLocation(long timeoutMs, final LocationResultCallback callback) {
        requestLocation(timeoutMs, false, callback);
    }

    /**
     * The most recent position the device already has, if it is no older than maxAgeMs.
     * Returns {latitude, longitude} or null. Never blocks and never starts the GPS.
     */
    public double[] recentLocation(long maxAgeMs) {
        if (!hasPermission()) return null;
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return null;
        Location best = freshestLastKnown(manager, maxAgeMs);
        return best == null ? null : new double[] { best.getLatitude(), best.getLongitude() };
    }

    /** Result of {@link #lookupAddress}: "" when no address is known. Called on the main thread. */
    public interface AddressCallback {
        void onAddress(String address);
    }

    /**
     * Address for a position the citizen chose on the incident map, using the same Geocoder as a
     * device fix. Runs off the main thread; reports "" honestly when there is no address.
     */
    public void lookupAddress(final double latitude, final double longitude, final AddressCallback callback) {
        if (callback == null) return;
        new Thread(() -> {
            final String address = reverseGeocode(latitude, longitude);
            handler.post(() -> callback.onAddress(address));
        }).start();
    }

    private void requestLocation(long timeoutMs, final boolean withAddress, final LocationResultCallback callback) {
        if (!hasPermission()) {
            callback.onUnavailable("Location permission has not been granted.");
            return;
        }

        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            callback.onUnavailable("Location services are not available on this device.");
            return;
        }

        String provider = bestEnabledProvider(manager);
        if (provider == null) {
            callback.onUnavailable("Location is turned off. Enable it and try again.");
            return;
        }

        final boolean[] finished = { false };
        final LocationListener[] listenerHolder = new LocationListener[1];

        final Runnable timeout = () -> {
            if (finished[0]) return;
            finished[0] = true;
            removeUpdates(manager, listenerHolder[0]);
            Location cached = freshestLastKnown(manager);
            if (cached != null) {
                deliver(cached, withAddress, callback);
            } else {
                callback.onUnavailable("Could not get a location fix in time. Try again with a clearer view of the sky.");
            }
        };

        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (finished[0]) return;
                finished[0] = true;
                handler.removeCallbacks(timeout);
                removeUpdates(manager, listenerHolder[0]);
                deliver(location, withAddress, callback);
            }
            @Override public void onProviderDisabled(String disabledProvider) { }
            @Override public void onProviderEnabled(String enabledProvider) { }
            @Override public void onStatusChanged(String changedProvider, int status, Bundle extras) { }
        };
        listenerHolder[0] = listener;

        try {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper());
        } catch (SecurityException error) {
            callback.onUnavailable("Location permission has not been granted.");
            return;
        } catch (IllegalArgumentException error) {
            callback.onUnavailable("This device does not offer a usable location provider.");
            return;
        }
        if (!withAddress && LocationManager.GPS_PROVIDER.equals(provider)
                && providerEnabled(manager, LocationManager.NETWORK_PROVIDER)) {
            // Quick mode only: the same listener also hears the network provider. Optional - if it
            // cannot be added, the GPS request above simply continues on its own.
            try {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, Looper.getMainLooper());
            } catch (SecurityException | IllegalArgumentException ignored) { }
        }

        handler.postDelayed(timeout, timeoutMs);
    }

    private void deliver(final Location location, final boolean withAddress, final LocationResultCallback callback) {
        final double latitude = location.getLatitude();
        final double longitude = location.getLongitude();
        if (!withAddress) {
            handler.post(() -> callback.onLocation(latitude, longitude, ""));
            return;
        }
        // Reverse geocoding is blocking and needs network; an empty address is reported honestly.
        new Thread(() -> {
            final String address = reverseGeocode(latitude, longitude);
            handler.post(() -> callback.onLocation(latitude, longitude, address));
        }).start();
    }

    private String reverseGeocode(double latitude, double longitude) {
        if (!Geocoder.isPresent()) return "";
        try {
            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            List<Address> results = geocoder.getFromLocation(latitude, longitude, 1);
            if (results == null || results.isEmpty()) return "";
            Address address = results.get(0);
            StringBuilder builder = new StringBuilder();
            for (int line = 0; line <= address.getMaxAddressLineIndex(); line++) {
                String value = address.getAddressLine(line);
                if (value == null || value.trim().isEmpty()) continue;
                if (builder.length() > 0) builder.append(", ");
                builder.append(value.trim());
            }
            return builder.toString();
        } catch (Exception error) {
            return "";
        }
    }

    /**
     * Each provider is probed separately. A single try/catch around all three used to report
     * "location is turned off" whenever the FUSED probe threw on a device that does not expose it,
     * even with GPS enabled.
     */
    private String bestEnabledProvider(LocationManager manager) {
        if (Build.VERSION.SDK_INT >= 31 && providerEnabled(manager, LocationManager.FUSED_PROVIDER)) {
            return LocationManager.FUSED_PROVIDER;
        }
        if (providerEnabled(manager, LocationManager.GPS_PROVIDER)) return LocationManager.GPS_PROVIDER;
        if (providerEnabled(manager, LocationManager.NETWORK_PROVIDER)) return LocationManager.NETWORK_PROVIDER;
        // Last resort: any other provider this device reports as enabled.
        try {
            for (String provider : manager.getAllProviders()) {
                if (LocationManager.PASSIVE_PROVIDER.equals(provider)) continue;
                if (providerEnabled(manager, provider)) return provider;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private boolean providerEnabled(LocationManager manager, String provider) {
        try {
            return manager.isProviderEnabled(provider);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Location freshestLastKnown(LocationManager manager) {
        return freshestLastKnown(manager, MAX_LAST_KNOWN_AGE_MS);
    }

    private Location freshestLastKnown(LocationManager manager, long maxAgeMs) {
        Location best = null;
        try {
            for (String provider : manager.getAllProviders()) {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate == null) continue;
                if (System.currentTimeMillis() - candidate.getTime() > maxAgeMs) continue;
                if (best == null || candidate.getTime() > best.getTime()) best = candidate;
            }
        } catch (SecurityException | IllegalArgumentException ignored) { }
        return best;
    }

    private void removeUpdates(LocationManager manager, LocationListener listener) {
        if (listener == null) return;
        try {
            manager.removeUpdates(listener);
        } catch (SecurityException ignored) { }
    }
}
