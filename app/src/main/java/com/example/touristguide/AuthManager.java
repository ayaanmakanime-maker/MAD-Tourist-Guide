package com.example.touristguide;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;

import java.util.regex.Pattern;

/**
 * Optional email/password accounts via Firebase Authentication. Passwords go straight to
 * Firebase over TLS and are never stored by the app. Without a google-services.json the app
 * reports that accounts are not configured and everything keeps working in guest mode.
 */
final class AuthManager {
    public static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    interface Callback {
        void onSuccess();

        void onError(String message);
    }

    private AuthManager() {
    }

    /** True only when the build includes a Firebase configuration and Firebase started. */
    static boolean isConfigured(Context c) {
        try {
            return BuildConfig.FIREBASE_CONFIGURED && !FirebaseApp.getApps(c).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static FirebaseUser currentUser(Context c) {
        return isConfigured(c) ? FirebaseAuth.getInstance().getCurrentUser() : null;
    }

    static boolean isValidEmail(String email) {
        return email != null && email.length() <= 254 && EMAIL.matcher(email.trim()).matches();
    }

    static boolean isValidPassword(String password) {
        return password != null && password.length() >= MIN_PASSWORD_LENGTH && password.length() <= 128;
    }

    static void signIn(Context c, String email, String password, Callback cb) {
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password)
                .addOnSuccessListener(r -> cb.onSuccess())
                .addOnFailureListener(e -> cb.onError(message(c, e)));
    }

    static void register(Context c, String email, String password, Callback cb) {
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email.trim(), password)
                .addOnSuccessListener(r -> cb.onSuccess())
                .addOnFailureListener(e -> cb.onError(message(c, e)));
    }

    static void sendPasswordReset(Context c, String email, Callback cb) {
        FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim())
                .addOnSuccessListener(r -> cb.onSuccess())
                .addOnFailureListener(e -> cb.onError(message(c, e)));
    }

    /** Signs out. Local favourites, trips, budget and quiz progress stay on the device. */
    static void signOut(Context c) {
        if (isConfigured(c)) FirebaseAuth.getInstance().signOut();
    }

    static String message(Context c, Exception e) {
        if (e instanceof FirebaseAuthUserCollisionException) return c.getString(R.string.auth_error_exists);
        if (e instanceof FirebaseAuthWeakPasswordException) return c.getString(R.string.auth_error_weak, MIN_PASSWORD_LENGTH);
        if (e instanceof FirebaseAuthInvalidUserException) return c.getString(R.string.auth_error_no_user);
        if (e instanceof FirebaseAuthInvalidCredentialsException) return c.getString(R.string.auth_error_credentials);
        if (e instanceof FirebaseNetworkException) return c.getString(R.string.error_network);
        if (e instanceof FirebaseTooManyRequestsException) return c.getString(R.string.auth_error_too_many);
        return c.getString(R.string.auth_error_generic);
    }
}
