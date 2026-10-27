package com.example.touristguide;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.touristguide.databinding.DialogInputBinding;
import com.example.touristguide.databinding.FragmentTabMoreBinding;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Profile, account, saved places, travel personality picks and settings. */
public class MoreFragment extends Fragment {

    private FragmentTabMoreBinding binding;
    private PlaceAdapter savedAdapter;

    private final ActivityResultLauncher<Intent> accountLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> refresh());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentTabMoreBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        buildPersonality();
        buildSavedList();
        binding.findFood.setOnClickListener(v -> {
            Store.saveQuery(Store.savedQuery().withCategory(PlaceQuery.Category.RESTAURANTS));
            ((MainActivity) requireActivity()).selectTab(R.id.nav_explore);
        });
        binding.playQuiz.setOnClickListener(v -> startActivity(new Intent(requireContext(), QuizActivity.class)));

        binding.liveSwitch.setChecked(Store.liveEnabled());
        binding.liveSwitch.setOnCheckedChangeListener((b, checked) -> {
            Store.setLiveEnabled(checked);
            PlaceRepository.clearCache();
        });
        binding.themeRow.setOnClickListener(v -> chooseTheme());
        binding.serverRow.setOnClickListener(v -> editServer());
        binding.clearCacheRow.setOnClickListener(v -> confirmClearCache());
        binding.resetQuizRow.setOnClickListener(v -> confirmResetQuiz());

        binding.accountRestore.setOnClickListener(v -> confirmRestore());
        binding.accountSignOut.setOnClickListener(v -> confirmSignOut());
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private void refresh() {
        if (binding == null) return;
        int saved = Store.savedCount();
        int planned = PlanRepository.get().size();
        int quizzes = Store.quizzesPlayed();
        binding.profileStats.setText(getString(R.string.profile_stats_fmt, saved, planned, quizzes));
        bindBadge(binding.badgeIconCurator, binding.badgeStateCurator, binding.badgeCurator, saved >= 3,
                locked(R.plurals.badge_curator_locked, 3 - saved), R.string.badge_curator_name);
        bindBadge(binding.badgeIconPlanner, binding.badgeStatePlanner, binding.badgePlanner, planned >= 3,
                locked(R.plurals.badge_planner_locked, 3 - planned), R.string.badge_planner_name);
        bindBadge(binding.badgeIconQuiz, binding.badgeStateQuiz, binding.badgeQuiz, quizzes >= 3,
                locked(R.plurals.badge_quiz_locked, 3 - quizzes), R.string.badge_quiz_name);

        binding.serverValue.setText(Store.backendUrl());
        binding.themeValue.setText(themeLabel(Store.themeMode()));
        binding.about.setText(getString(R.string.more_about_app, BuildConfig.VERSION_NAME));
        refreshSaved();
        refreshAccount();
    }

    private String locked(int plural, int remaining) {
        int n = Math.max(0, remaining);
        return getResources().getQuantityString(plural, n, n);
    }

    private void bindBadge(android.widget.TextView icon, android.widget.TextView state, View container,
                           boolean earned, String lockedText, int name) {
        state.setText(earned ? getString(R.string.badge_earned) : lockedText);
        icon.setAlpha(earned ? 1f : 0.35f);
        container.setContentDescription(getString(R.string.badge_desc_fmt, getString(name), state.getText()));
    }

    // ------------------------------------------------------------------ account

    private void refreshAccount() {
        binding.accountProgress.setVisibility(View.GONE);
        if (!AuthManager.isConfigured(requireContext())) {
            binding.accountTitle.setText(R.string.account_guest);
            binding.accountBody.setText(R.string.account_guest_not_configured);
            binding.accountPrimary.setVisibility(View.GONE);
            binding.accountSignedInActions.setVisibility(View.GONE);
            return;
        }
        FirebaseUser user = AuthManager.currentUser(requireContext());
        binding.accountPrimary.setVisibility(View.VISIBLE);
        if (user == null) {
            binding.accountTitle.setText(R.string.account_guest);
            binding.accountBody.setText(R.string.account_guest_body);
            binding.accountPrimary.setText(R.string.account_sign_in_cta);
            binding.accountPrimary.setOnClickListener(v ->
                    accountLauncher.launch(new Intent(requireContext(), AccountActivity.class)));
            binding.accountSignedInActions.setVisibility(View.GONE);
        } else {
            binding.accountTitle.setText(user.getEmail());
            binding.accountBody.setText(R.string.account_signed_in_body);
            binding.accountPrimary.setText(R.string.account_backup);
            binding.accountPrimary.setOnClickListener(v -> backup());
            binding.accountSignedInActions.setVisibility(View.VISIBLE);
        }
    }

    private final CloudSync.Callback syncCallback = new CloudSync.Callback() {
        @Override
        public void onDone() {
            if (binding == null) return;
            binding.accountProgress.setVisibility(View.GONE);
            Toast.makeText(requireContext(), R.string.account_sync_done, Toast.LENGTH_SHORT).show();
            refresh();
        }

        @Override
        public void onNothingToRestore() {
            if (binding == null) return;
            binding.accountProgress.setVisibility(View.GONE);
            Toast.makeText(requireContext(), R.string.account_nothing_to_restore, Toast.LENGTH_LONG).show();
        }

        @Override
        public void onError(String message) {
            if (binding == null) return;
            binding.accountProgress.setVisibility(View.GONE);
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
        }
    };

    private void backup() {
        binding.accountProgress.setVisibility(View.VISIBLE);
        CloudSync.backup(requireContext(), syncCallback);
    }

    private void confirmRestore() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.account_restore)
                .setMessage(R.string.account_restore_confirm)
                .setPositiveButton(R.string.account_restore, (d, w) -> {
                    binding.accountProgress.setVisibility(View.VISIBLE);
                    CloudSync.restore(requireContext(), syncCallback);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmSignOut() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.account_sign_out)
                .setMessage(R.string.account_sign_out_confirm)
                .setPositiveButton(R.string.account_sign_out, (d, w) -> {
                    AuthManager.signOut(requireContext());
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ saved places

    private void buildSavedList() {
        savedAdapter = new PlaceAdapter(new PlaceAdapter.Listener() {
            @Override
            public void onOpen(Place place) {
                ((MainActivity) requireActivity()).openDetails(place);
            }

            @Override
            public void onAddToPlan(Place place) {
                ((MainActivity) requireActivity()).addToPlan(place, () -> refresh());
            }

            @Override
            public void onSavedChanged(Place place, boolean saved) {
                refresh();
            }
        }, true);
        RecyclerView list = binding.savedSection.savedList;
        list.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        list.setAdapter(savedAdapter);
        list.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int pad = dp(4);
                outRect.set(pad, 0, pad, pad);
            }
        });
    }

    private void refreshSaved() {
        List<Place> saved = Store.savedPlaces();
        LocationRepository.Selected loc = LocationRepository.selected();
        savedAdapter.setOrigin(loc.lat, loc.lng);
        savedAdapter.setItems(new ArrayList<>(saved));
        boolean has = !saved.isEmpty();
        binding.savedSection.savedEmpty.setVisibility(has ? View.GONE : View.VISIBLE);
        binding.savedSection.savedList.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ travel personality

    private void buildPersonality() {
        binding.personalityList.removeAllViews();
        for (Personality p : personalities()) {
            MaterialButton b = new MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setText(p.label);
            b.setMinHeight(dp(48));
            b.setOnClickListener(v -> showSuggestions(p));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            binding.personalityList.addView(b, lp);
        }
    }

    private void showSuggestions(Personality p) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(p.label)
                .setMessage(p.picks)
                .setPositiveButton(R.string.plan_add, (d, w) -> confirmReplacePlan(p))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmReplacePlan(Personality p) {
        Runnable apply = () -> {
            List<Place> places = new ArrayList<>();
            for (String id : p.placeIds) {
                Place place = Catalog.byId(id);
                if (place != null) places.add(place);
            }
            PlanRepository.get().replaceWith(places);
            PlanRepository.save();
            refresh();
            ((MainActivity) requireActivity()).showMessage(getString(R.string.plan_set_toast), true);
        };
        if (PlanRepository.get().isEmpty()) {
            apply.run();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.plan_add)
                .setMessage(getString(R.string.plan_replace_confirm_fmt, PlanRepository.get().size()))
                .setPositiveButton(R.string.plan_replace_yes, (d, w) -> apply.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private List<Personality> personalities() {
        List<Personality> out = new ArrayList<>();
        out.add(new Personality(getString(R.string.personality_history), getString(R.string.picks_history),
                ids("Gateway of India", "Chhatrapati Shivaji Maharaj Terminus",
                        "Chhatrapati Shivaji Maharaj Vastu Sangrahalaya", "Elephanta Caves")));
        out.add(new Personality(getString(R.string.personality_foodie), getString(R.string.picks_foodie),
                ids("Colaba Causeway", "Leopold Cafe", "Kyani & Co.", "Girgaon Chowpatty", "Juhu Beach")));
        out.add(new Personality(getString(R.string.personality_nature), getString(R.string.picks_nature),
                ids("Marine Drive", "Hanging Gardens", "Sanjay Gandhi National Park", "Bandra-Worli Sea Link")));
        out.add(new Personality(getString(R.string.personality_photo), getString(R.string.picks_photo),
                ids("Gateway of India", "Marine Drive", "Bandra-Worli Sea Link", "Girgaon Chowpatty")));
        return out;
    }

    private static List<String> ids(String... names) {
        List<String> out = new ArrayList<>();
        for (String n : Arrays.asList(names)) out.add(Place.CURATED_PREFIX + Place.slug(n));
        return out;
    }

    private static class Personality {
        final String label;
        final String picks;
        final List<String> placeIds;

        Personality(String label, String picks, List<String> placeIds) {
            this.label = label;
            this.picks = picks;
            this.placeIds = placeIds;
        }
    }

    // ------------------------------------------------------------------ settings

    private String themeLabel(int mode) {
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) return getString(R.string.theme_light);
        if (mode == AppCompatDelegate.MODE_NIGHT_YES) return getString(R.string.theme_dark);
        return getString(R.string.theme_system);
    }

    private void chooseTheme() {
        int[] modes = {AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO,
                AppCompatDelegate.MODE_NIGHT_YES};
        String[] labels = {getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)};
        int current = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i] == Store.themeMode()) current = i;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_theme)
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    Store.setThemeMode(modes[which]);
                    d.dismiss();
                    AppCompatDelegate.setDefaultNightMode(modes[which]);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void editServer() {
        DialogInputBinding d = DialogInputBinding.inflate(getLayoutInflater());
        d.inputLayout.setHint(getString(R.string.settings_server_hint));
        d.inputLayout.setHelperText(getString(R.string.settings_server_helper, BuildConfig.BACKEND_URL));
        d.input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        d.input.setText(Store.backendUrl());
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_ai_server)
                .setView(d.getRoot())
                .setPositiveButton(R.string.save, null)
                .setNeutralButton(R.string.settings_server_default, (dlg, w) -> {
                    Store.setBackendUrl("");
                    PlaceRepository.clearCache();
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = d.input.getText() == null ? "" : d.input.getText().toString().trim();
            if (!isValidServerUrl(url)) {
                d.inputLayout.setError(getString(R.string.settings_server_invalid));
                return;
            }
            Store.setBackendUrl(url);
            PlaceRepository.clearCache();
            dialog.dismiss();
            refresh();
            testServer();
        });
    }

    static boolean isValidServerUrl(String url) {
        if (url == null) return false;
        android.net.Uri uri = android.net.Uri.parse(url);
        String scheme = uri.getScheme();
        return ("https".equals(scheme) || "http".equals(scheme)) && uri.getHost() != null && !uri.getHost().isEmpty();
    }

    private void testServer() {
        BackendClient.health(new BackendClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                if (binding == null) return;
                Toast.makeText(requireContext(), getString(R.string.settings_server_ok_fmt,
                        yesNo(value.optBoolean("places_configured")), yesNo(value.optBoolean("ai_configured"))),
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(BackendClient.ApiError error) {
                if (binding == null) return;
                Toast.makeText(requireContext(), Formats.error(requireContext(), error), Toast.LENGTH_LONG).show();
            }
        });
    }

    private String yesNo(boolean value) {
        return getString(value ? R.string.yes : R.string.no);
    }

    private void confirmClearCache() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_clear_cache)
                .setMessage(R.string.settings_clear_cache_confirm)
                .setPositiveButton(R.string.settings_clear_yes, (d, w) -> {
                    PlaceRepository.clearCache();
                    Toast.makeText(requireContext(), R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmResetQuiz() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_reset_quiz)
                .setMessage(R.string.settings_reset_quiz_confirm)
                .setPositiveButton(R.string.settings_reset_yes, (d, w) -> {
                    Store.resetQuiz();
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }
}
