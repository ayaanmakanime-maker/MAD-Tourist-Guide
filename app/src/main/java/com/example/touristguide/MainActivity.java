package com.example.touristguide;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.example.touristguide.databinding.ActivityMainBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/** Hosts the five tabs and the shared actions every tab uses (details, plan, map, chat). */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private int loadedTab = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Store.init(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.container, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            return insets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(binding.nav, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, 0, bars.right, bars.bottom);
            return insets;
        });

        binding.nav.setOnItemSelectedListener(item -> {
            choose(item.getItemId());
            return true;
        });
        binding.nav.setOnItemReselectedListener(item -> choose(item.getItemId()));

        int tabId = savedInstanceState == null
                ? R.id.nav_explore
                : savedInstanceState.getInt("tab", R.id.nav_explore);
        choose(tabId);
        binding.nav.setSelectedItemId(tabId);
    }

    private void choose(int id) {
        if (loadedTab == id) return;
        loadedTab = id;
        if (id == R.id.nav_map) showTab(new MapTabFragment());
        else if (id == R.id.nav_plan) showTab(new PlanFragment());
        else if (id == R.id.nav_chat) showTab(new ChatFragment());
        else if (id == R.id.nav_more) showTab(new MoreFragment());
        else showTab(new ExploreFragment());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("tab", loadedTab);
        super.onSaveInstanceState(outState);
    }

    private void showTab(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, fragment)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .commit();
    }

    // ------------------------------------------------------------------ shared actions

    public void selectTab(int id) {
        binding.nav.setSelectedItemId(id);
    }

    public void openDetails(Place place) {
        PlaceRepository.remember(place);
        if (getSupportFragmentManager().isStateSaved()) return;
        DetailSheet.newInstance(place.id).show(getSupportFragmentManager(), "detail");
    }

    /** Centres the Map tab on a place (works for live, curated and itinerary places). */
    public void showOnMap(Place place) {
        PlaceRepository.remember(place);
        PlaceRepository.requestMapFocus(place.id);
        selectTab(R.id.nav_map);
    }

    /** Opens Chat with a question ready to send. */
    public void askAssistant(String prompt) {
        ChatSession.pendingPrompt = prompt;
        selectTab(R.id.nav_chat);
    }

    /**
     * Adds a place to the trip, asking which day when the trip has several. Duplicates are
     * refused with a message rather than added twice.
     */
    public void addToPlan(Place place, Runnable onChanged) {
        if (PlanRepository.contains(place.id)) {
            showMessage(getString(R.string.already_in_plan), true);
            return;
        }
        int days = PlanRepository.get().days();
        if (days <= 1) {
            finishAdd(place, 1, onChanged);
            return;
        }
        String[] labels = new String[days];
        for (int d = 0; d < days; d++) labels[d] = getString(R.string.day_fmt, d + 1);
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.add_to_day_title_fmt, place.name))
                .setItems(labels, (dialog, which) -> finishAdd(place, which + 1, onChanged))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void finishAdd(Place place, int day, Runnable onChanged) {
        Itinerary.AddResult result = PlanRepository.add(place, day);
        if (result == Itinerary.AddResult.FULL) {
            showMessage(getString(R.string.plan_full_fmt, Itinerary.MAX_STOPS), false);
            return;
        }
        if (onChanged != null) onChanged.run();
        showMessage(getString(R.string.plan_added_day_fmt, place.name, day), true);
    }

    /** Snackbar above the bottom navigation; optionally with a "View plan" action. */
    public void showMessage(String text, boolean withPlanAction) {
        Snackbar bar = Snackbar.make(binding.getRoot(), text, Snackbar.LENGTH_LONG).setAnchorView(binding.nav);
        if (withPlanAction && loadedTab != R.id.nav_plan) {
            bar.setAction(R.string.action_view_plan, v -> selectTab(R.id.nav_plan));
        }
        bar.show();
    }
}
