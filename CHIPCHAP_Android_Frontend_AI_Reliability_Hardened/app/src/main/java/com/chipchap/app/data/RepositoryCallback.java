package com.chipchap.app.data;

/**
 * Minimal asynchronous result contract for the Firestore data layer.
 * Failures are always surfaced honestly; repositories never report success for a failed operation.
 */
public interface RepositoryCallback<T> {
    void onSuccess(T result);
    void onFailure(Exception error);
}
