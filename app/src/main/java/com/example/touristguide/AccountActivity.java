package com.example.touristguide;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.touristguide.databinding.ActivityAccountBinding;

/** Optional sign-in / registration / password reset. "Continue as guest" simply closes it. */
public class AccountActivity extends AppCompatActivity {

    private ActivityAccountBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAccountBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        binding.guest.setOnClickListener(v -> finish());
        if (!AuthManager.isConfigured(this)) {
            // Be explicit instead of pretending login works.
            binding.form.setVisibility(View.GONE);
            binding.accountIntro.setText(R.string.account_not_configured);
            return;
        }
        binding.signIn.setOnClickListener(v -> submit(false));
        binding.register.setOnClickListener(v -> submit(true));
        binding.forgot.setOnClickListener(v -> resetPassword());
    }

    private String email() {
        return binding.email.getText() == null ? "" : binding.email.getText().toString().trim();
    }

    private String password() {
        return binding.password.getText() == null ? "" : binding.password.getText().toString();
    }

    private boolean validate(boolean needPassword) {
        boolean ok = true;
        binding.emailLayout.setError(null);
        binding.passwordLayout.setError(null);
        if (!AuthManager.isValidEmail(email())) {
            binding.emailLayout.setError(getString(R.string.auth_invalid_email));
            ok = false;
        }
        if (needPassword && !AuthManager.isValidPassword(password())) {
            binding.passwordLayout.setError(getString(R.string.auth_error_weak, AuthManager.MIN_PASSWORD_LENGTH));
            ok = false;
        }
        return ok;
    }

    private void submit(boolean register) {
        if (!validate(true)) return;
        busy(true);
        AuthManager.Callback cb = new AuthManager.Callback() {
            @Override
            public void onSuccess() {
                setResult(RESULT_OK);
                finish();
            }

            @Override
            public void onError(String message) {
                busy(false);
                showStatus(message, true);
            }
        };
        if (register) AuthManager.register(this, email(), password(), cb);
        else AuthManager.signIn(this, email(), password(), cb);
    }

    private void resetPassword() {
        if (!validate(false)) return;
        busy(true);
        AuthManager.sendPasswordReset(this, email(), new AuthManager.Callback() {
            @Override
            public void onSuccess() {
                busy(false);
                showStatus(getString(R.string.account_reset_sent), false);
            }

            @Override
            public void onError(String message) {
                busy(false);
                showStatus(message, true);
            }
        });
    }

    private void busy(boolean busy) {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.signIn.setEnabled(!busy);
        binding.register.setEnabled(!busy);
        binding.forgot.setEnabled(!busy);
    }

    private void showStatus(String message, boolean error) {
        binding.status.setVisibility(View.VISIBLE);
        binding.status.setText(message);
        binding.status.setTextColor(getColor(error ? R.color.error : R.color.success));
    }
}
