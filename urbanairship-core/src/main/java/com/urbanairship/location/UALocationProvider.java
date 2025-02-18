/* Copyright 2018 Urban Airship and Contributors */

package com.urbanairship.location;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.annotation.WorkerThread;

import com.urbanairship.Cancelable;
import com.urbanairship.Logger;
import com.urbanairship.ResultCallback;
import com.urbanairship.google.PlayServicesUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.app.PendingIntent.getService;

/**
 * Location provider that automatically selects between the standard android location
 * and the fused location. This class is not thread safe.
 */
class UALocationProvider {

    @Nullable
    private LocationAdapter availableAdapter;
    private boolean isConnected = false;

    private final List<LocationAdapter> adapters = new ArrayList<>();
    private final Context context;
    private final Intent locationUpdateIntent;

    /**
     * UALocationProvider constructor.
     *
     * @param context The application context.
     * @param locationUpdateIntent The update intent to send for location responses.
     */
    UALocationProvider(@NonNull Context context, @NonNull Intent locationUpdateIntent) {
        this.context = context;
        this.locationUpdateIntent = locationUpdateIntent;

        // This is to prevent a log message saying Google Play Services is unavailable on amazon devices.
        if (PlayServicesUtils.isGooglePlayStoreAvailable(context) && PlayServicesUtils.isFusedLocationDependencyAvailable()) {
            adapters.add(new FusedLocationAdapter(context));
        }

        adapters.add(new StandardLocationAdapter());
    }

    @VisibleForTesting
    UALocationProvider(@NonNull Context context, @NonNull Intent locationUpdateIntent, LocationAdapter... adapters) {
        this.context = context;
        this.locationUpdateIntent = locationUpdateIntent;
        this.adapters.addAll(Arrays.asList(adapters));
    }

    /**
     * Cancels all location requests for the connected adapter's pending intent.
     */
    @WorkerThread
    void cancelRequests() {
        Logger.verbose("UALocationProvider - Canceling location requests.");
        connect();

        if (availableAdapter == null) {
            Logger.debug("UALocationProvider - Ignoring request, connected adapter unavailable.");
            return;
        }

        try {
            PendingIntent pendingIntent = PendingIntent.getService(context, availableAdapter.getRequestCode(), this.locationUpdateIntent, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pendingIntent != null) {
                availableAdapter.cancelLocationUpdates(context, pendingIntent);
            }
        } catch (Exception ex) {
            Logger.error("Unable to cancel location updates: " + ex.getMessage());
        }
    }

    /**
     * Requests location updates.
     *
     * @param options The request options.
     * @throws IllegalStateException if the provider is not connected.
     */
    @WorkerThread
    void requestLocationUpdates(@NonNull LocationRequestOptions options) {
        connect();

        if (availableAdapter == null) {
            Logger.debug("UALocationProvider - Ignoring request, connected adapter unavailable.");
            return;
        }

        Logger.verbose("UALocationProvider - Requesting location updates: " + options);
        try {
            PendingIntent pendingIntent = PendingIntent.getService(context, availableAdapter.getRequestCode(), this.locationUpdateIntent, PendingIntent.FLAG_UPDATE_CURRENT |FLAG_MUTABLE);
            availableAdapter.requestLocationUpdates(context, options, pendingIntent);
        } catch (Exception ex) {
            Logger.error("Unable to request location updates: " + ex.getMessage());
        }
    }

    /**
     * Requests a single location update.
     *
     * @param options The request options.
     * @return A pending location result.
     */
    @WorkerThread
    Cancelable requestSingleLocation(@NonNull LocationRequestOptions options, ResultCallback<Location> resultCallback) {
        connect();

        if (availableAdapter == null) {
            Logger.debug("UALocationProvider - Ignoring request, connected adapter unavailable.");
        }

        Logger.verbose("UALocationProvider - Requesting single location update: " + options);

        try {
            return availableAdapter.requestSingleLocation(context, options, resultCallback);
        } catch (Exception ex) {
            Logger.error("Unable to request location: " + ex.getMessage());
        }

        return null;
    }

    /**
     * Connects to the provider.
     */
    @WorkerThread
    private void connect() {
        if (isConnected) {
            return;
        }

        for (LocationAdapter adapter : adapters) {
            Logger.verbose("UALocationProvider - Attempting to connect to location adapter: " + adapter);

            if (adapter.isAvailable(context)) {

                if (availableAdapter == null) {
                    Logger.verbose("UALocationProvider - Using adapter: " + adapter);
                    availableAdapter = adapter;
                }

                /*
                 * Need to cancel requests on all providers regardless of the current
                 * connected provider because pending intents persist between app starts
                 * and there is no way to determine what provider was used previously.
                 */
                try {
                    PendingIntent pendingIntent = PendingIntent.getService(context, adapter.getRequestCode(), this.locationUpdateIntent, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                    if (pendingIntent != null) {
                        adapter.cancelLocationUpdates(context, pendingIntent);
                    }
                } catch (Exception ex) {
                    Logger.error("Unable to cancel location updates: " + ex.getMessage());
                }
            } else {
                Logger.verbose("UALocationProvider - Adapter unavailable: " + adapter);
            }
        }

        isConnected = true;
    }


    /**
     * Called when a system location provider availability changes.
     *
     * @param options Current location request options.
     */
    @WorkerThread
    void onSystemLocationProvidersChanged(@NonNull LocationRequestOptions options) {
        Logger.verbose("UALocationProvider - Available location providers changed.");

        connect();

        if (availableAdapter != null) {
            PendingIntent pendingIntent = PendingIntent.getService(context, availableAdapter.getRequestCode(), this.locationUpdateIntent, PendingIntent.FLAG_UPDATE_CURRENT |FLAG_MUTABLE);
            availableAdapter.onSystemLocationProvidersChanged(context, options, pendingIntent);
        }
    }

    /**
     * Checks if updates are currently being requested or not.
     *
     * @return {@code true} if updates are being requested, otherwise {@code false}.
     */
    @WorkerThread
    boolean areUpdatesRequested() {
        connect();

        if (availableAdapter == null) {
            return false;
        }

        return getService(context, availableAdapter.getRequestCode(), this.locationUpdateIntent, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE) != null;
    }
}
