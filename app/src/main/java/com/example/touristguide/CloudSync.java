package com.example.touristguide;

import android.content.Context;

import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Manual cloud backup/restore of the signed-in user's data in Firestore (users/{uid}).
 * Only runs when the user taps Back up / Restore; nothing syncs in the background.
 * Security rules in firebase/firestore.rules restrict each document to its owner.
 */
final class CloudSync {
    static final int SCHEMA_VERSION = 1;

    interface Callback {
        void onDone();

        void onNothingToRestore();

        void onError(String message);
    }

    private CloudSync() {
    }

    private static DocumentReference doc(FirebaseUser user) {
        return FirebaseFirestore.getInstance().collection("users").document(user.getUid());
    }

    static void backup(Context c, Callback cb) {
        FirebaseUser user = AuthManager.currentUser(c);
        if (user == null) {
            cb.onError(c.getString(R.string.account_sign_in_first));
            return;
        }
        JSONObject data = Store.exportUserData();
        Map<String, Object> fields = new HashMap<>();
        fields.put("schemaVersion", SCHEMA_VERSION);
        fields.put("updatedAt", FieldValue.serverTimestamp());
        fields.put("favourites", data.optString("favourites", "{}"));
        fields.put("itinerary", data.optString("itinerary", "{}"));
        fields.put("budget", data.optString("budget", "{}"));
        fields.put("quiz", data.optString("quiz", "{}"));
        doc(user).set(fields)
                .addOnSuccessListener(v -> cb.onDone())
                .addOnFailureListener(e -> cb.onError(c.getString(R.string.account_sync_failed)));
    }

    static void restore(Context c, Callback cb) {
        FirebaseUser user = AuthManager.currentUser(c);
        if (user == null) {
            cb.onError(c.getString(R.string.account_sign_in_first));
            return;
        }
        doc(user).get()
                .addOnSuccessListener(snapshot -> {
                    if (!snapshot.exists()) {
                        cb.onNothingToRestore();
                        return;
                    }
                    Store.importUserData(snapshot.getString("favourites"), snapshot.getString("itinerary"),
                            snapshot.getString("budget"), snapshot.getString("quiz"));
                    PlanRepository.reload();
                    cb.onDone();
                })
                .addOnFailureListener(e -> cb.onError(c.getString(R.string.account_sync_failed)));
    }
}
