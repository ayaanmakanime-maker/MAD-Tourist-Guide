package com.example.touristguide;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MoreFragment extends Fragment {

    private com.example.touristguide.databinding.FragmentTabMoreBinding binding;
    private PlaceAdapter savedAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = com.example.touristguide.databinding.FragmentTabMoreBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        buildPersonality();
        buildSavedList();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        int saved = Store.savedCount();
        int planned = Store.planCount();
        binding.profileStats.setText(getString(R.string.profile_stats_fmt, saved, planned));
        boolean curator = saved >= 3;
        boolean planner = planned >= 3;
        binding.badgeStateCurator.setText(curator
                ? getString(R.string.badge_earned)
                : getString(R.string.badge_curator_locked, Math.max(0, 3 - saved)));
        binding.badgeStatePlanner.setText(planner
                ? getString(R.string.badge_earned)
                : getString(R.string.badge_planner_locked, Math.max(0, 3 - planned)));
        binding.badgeIconCurator.setAlpha(curator ? 1f : 0.35f);
        binding.badgeIconPlanner.setAlpha(planner ? 1f : 0.35f);
        binding.serverValue.setText(getString(R.string.more_ai_value, BuildConfig.GEMINI_MODEL));
        binding.about.setText(getString(R.string.more_about_app, BuildConfig.VERSION_NAME));
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildSavedList() {
        PlaceAdapter adapter = new PlaceAdapter(requireActivity(), new ArrayList<>(),
                new PlaceAdapter.Listener() {
                    @Override
                    public void onOpen(Place place) {
                        ((MainActivity) requireActivity()).openDetails(place);
                    }

                    @Override
                    public void onSavedChanged(Place place, boolean added) {
                        refreshSaved();
                    }
                }, null);
        binding.savedSection.savedList.setLayoutManager(
                new androidx.recyclerview.widget.LinearLayoutManager(requireContext(),
                        androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false));
        binding.savedSection.savedList.setAdapter(adapter);
        binding.savedSection.savedList.addItemDecoration(new androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull android.graphics.Rect outRect,
                                       @NonNull android.view.View view,
                                       @NonNull androidx.recyclerview.widget.RecyclerView parent,
                                       @NonNull androidx.recyclerview.widget.RecyclerView.State state) {
                int pad = dp(4);
                outRect.set(pad, pad, pad, pad);
            }
        });
        savedAdapter = adapter;
        refreshSaved();
    }

    private void refreshSaved() {
        List<Place> saved = Store.savedPlaces();
        List<Object> rows = new ArrayList<>(saved);
        PlaceAdapter a = savedAdapter;
        if (a != null) a.setItems(rows);
        boolean has = !saved.isEmpty();
        binding.savedSection.savedEmpty.setVisibility(has ? View.GONE : View.VISIBLE);
        binding.savedSection.savedList.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    private void buildPersonality() {
        binding.personalityList.removeAllViews();
        for (Personality p : personalities()) {
            MaterialButton b = new MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setText(p.label);
            b.setTextSize(13);
            b.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            b.setOnClickListener(v -> showSuggestions(p));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            binding.personalityList.addView(b, lp);
        }
    }

    private void showSuggestions(Personality p) {
        new AlertDialog.Builder(requireContext())
                .setTitle(p.label)
                .setMessage(p.picks)
                .setPositiveButton(R.string.plan_add, (d, w) -> {
                    Store.replacePlan(p.planNames);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private List<Personality> personalities() {
        List<Personality> out = new ArrayList<>();
        out.add(new Personality(getString(R.string.personality_history),
                getString(R.string.picks_history),
                Arrays.asList("Gateway of India",
                        "Chhatrapati Shivaji Maharaj Terminus",
                        "Chhatrapati Shivaji Maharaj Vastu Sangrahalaya",
                        "Elephanta Caves")));
        out.add(new Personality(getString(R.string.personality_foodie),
                getString(R.string.picks_foodie),
                Arrays.asList("Colaba Causeway", "Juhu Beach", "Girgaon Chowpatty")));
        out.add(new Personality(getString(R.string.personality_nature),
                getString(R.string.picks_nature),
                Arrays.asList("Marine Drive", "Hanging Gardens",
                        "Sanjay Gandhi National Park", "Bandra-Worli Sea Link")));
        out.add(new Personality(getString(R.string.personality_photo),
                getString(R.string.picks_photo),
                Arrays.asList("Gateway of India", "Marine Drive",
                        "Bandra-Worli Sea Link", "Girgaon Chowpatty")));
        return out;
    }

    private static class Personality {
        final String label;
        final String picks;
        final List<String> planNames;

        Personality(String label, String picks, List<String> planNames) {
            this.label = label;
            this.picks = picks;
            this.planNames = planNames;
        }
    }
}
