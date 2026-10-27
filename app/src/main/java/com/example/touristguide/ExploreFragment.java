package com.example.touristguide;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.touristguide.databinding.FragmentExploreBinding;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Explore: nearby attractions, restaurants and cafés around the selected location. */
public class ExploreFragment extends Fragment implements PlaceAdapter.Listener, PlaceRepository.Listener {

    private static final long SEARCH_DEBOUNCE_MS = 700;
    /** The state whose "load more" error was already shown, so revisiting the tab doesn't repeat it. */
    private static PlaceRepository.State lastErrorShown;

    private FragmentExploreBinding binding;
    private PlaceAdapter adapter;
    private PlaceQuery query;
    private boolean permissionDenied;
    private boolean hidePermissionCard;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = () -> load(false);
    private final Random random = new Random();

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentExploreBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        query = Store.savedQuery();
        PlaceRepository.State existing = PlaceRepository.state();
        if (existing != null && existing.query.isTextSearch()) {
            query = query.withText(existing.query.text);
            binding.search.setText(existing.query.text);
        }

        adapter = new PlaceAdapter(this, false);
        binding.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.list.setAdapter(adapter);
        binding.refresh.setColorSchemeResources(R.color.accent);
        binding.refresh.setOnRefreshListener(() -> load(true));

        binding.search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = query.withText(s.toString());
                handler.removeCallbacks(searchRunnable);
                handler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
            }
        });
        binding.search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                handler.removeCallbacks(searchRunnable);
                load(false);
                v.clearFocus();
                return true;
            }
            return false;
        });

        buildChips();
        binding.radiusButton.setOnClickListener(this::showRadiusMenu);
        binding.sortButton.setOnClickListener(this::showSortMenu);
        binding.openNow.setChecked(query.openNow);
        binding.openNow.setOnCheckedChangeListener((b, checked) -> {
            query = query.withOpenNow(checked);
            Store.saveQuery(query);
            load(false);
        });
        binding.locationButton.setOnClickListener(v -> openLocationSheet());
        binding.surprise.setOnClickListener(v -> surprise());
        updateControls();

        getParentFragmentManager().setFragmentResultListener(LocationSheet.RESULT, getViewLifecycleOwner(),
                (key, result) -> {
                    if (result.getBoolean(LocationSheet.USE_DEVICE)) onAllowLocation();
                    else onLocationChanged();
                });

        PlaceRepository.addListener(this);
        // First visit with permission already granted: use the device location straight away.
        if (LocationRepository.selected().kind == LocationRepository.Kind.DEFAULT
                && LocationRepository.hasPermission(requireContext())) {
            fetchDeviceLocation();
        } else if (existing == null || !sameQuery(existing.query)) {
            load(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Saved/plan state may have changed on other tabs.
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(searchRunnable);
        PlaceRepository.removeListener(this);
        binding = null;
        super.onDestroyView();
    }

    private boolean sameQuery(PlaceQuery other) {
        return other.category == query.category && other.radiusMeters == query.radiusMeters
                && other.sort == query.sort && other.openNow == query.openNow && other.text.equals(query.text);
    }

    private void load(boolean force) {
        if (binding == null) return;
        PlaceRepository.load(query, force);
    }

    // ------------------------------------------------------------------ controls

    private void buildChips() {
        binding.chips.removeAllViews();
        for (PlaceQuery.Category c : PlaceQuery.Category.values()) {
            Chip chip = new Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle);
            chip.setText(c.label);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setId(View.generateViewId());
            chip.setChecked(c == query.category);
            chip.setOnClickListener(v -> {
                if (query.category == c) return;
                query = query.withCategory(c);
                Store.saveQuery(query);
                load(false);
            });
            binding.chips.addView(chip);
        }
    }

    private void updateControls() {
        binding.radiusButton.setText(getString(R.string.radius_fmt, GeoUtils.formatDistance(query.radiusMeters)));
        binding.radiusButton.setContentDescription(getString(R.string.radius_desc_fmt,
                GeoUtils.formatDistance(query.radiusMeters)));
        int sortLabel = query.sort == PlaceQuery.Sort.DISTANCE ? R.string.sort_distance
                : query.sort == PlaceQuery.Sort.RATING ? R.string.sort_rating : R.string.sort_relevance;
        binding.sortButton.setText(getString(R.string.sort_fmt, getString(sortLabel)));
        LocationRepository.Selected loc = LocationRepository.selected();
        binding.locationButton.setText(getString(R.string.location_near_fmt, loc.label));
        binding.locationButton.setContentDescription(getString(R.string.location_change_desc_fmt, loc.label));
    }

    private void showRadiusMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        for (int i = 0; i < PlaceQuery.RADIUS_OPTIONS_M.length; i++) {
            menu.getMenu().add(Menu.NONE, i, i, getString(R.string.radius_fmt,
                    GeoUtils.formatDistance(PlaceQuery.RADIUS_OPTIONS_M[i])));
        }
        menu.setOnMenuItemClickListener(item -> {
            query = query.withRadius(PlaceQuery.RADIUS_OPTIONS_M[item.getItemId()]);
            Store.saveQuery(query);
            updateControls();
            load(false);
            return true;
        });
        menu.show();
    }

    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(Menu.NONE, 0, 0, R.string.sort_relevance);
        menu.getMenu().add(Menu.NONE, 1, 1, R.string.sort_distance);
        menu.getMenu().add(Menu.NONE, 2, 2, R.string.sort_rating);
        menu.setOnMenuItemClickListener(item -> {
            query = query.withSort(PlaceQuery.Sort.values()[item.getItemId()]);
            Store.saveQuery(query);
            updateControls();
            load(false);
            return true;
        });
        menu.show();
    }

    private void openLocationSheet() {
        new LocationSheet().show(getParentFragmentManager(), "location");
    }

    private void onLocationChanged() {
        if (binding == null) return;
        updateControls();
        load(true);
    }

    // ------------------------------------------------------------------ location permission

    @Override
    public void onAllowLocation() {
        Store.setLocationRationaleSeen();
        if (LocationRepository.hasPermission(requireContext())) {
            fetchDeviceLocation();
        } else if (permissionDenied
                && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            // "Don't ask again" was chosen: the only way back is the system settings page.
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().getPackageName(), null)));
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    private void onPermissionResult(Map<String, Boolean> result) {
        if (LocationRepository.hasPermission(requireContext())) {
            permissionDenied = false;
            fetchDeviceLocation();
        } else {
            permissionDenied = true;
            if (PlaceRepository.state() != null) onPlacesChanged(PlaceRepository.state());
        }
    }

    private void fetchDeviceLocation() {
        binding.refresh.setRefreshing(true);
        LocationRepository.useDeviceLocation(requireContext(), getString(R.string.location_current),
                new LocationRepository.Callback() {
                    @Override
                    public void onLocation(LocationRepository.Selected selected) {
                        onLocationChanged();
                    }

                    @Override
                    public void onFailure(LocationRepository.Failure failure) {
                        if (binding == null) return;
                        Toast.makeText(requireContext(), failure == LocationRepository.Failure.NO_PERMISSION
                                ? R.string.map_location_denied : R.string.map_location_unavailable, Toast.LENGTH_LONG).show();
                        load(false);
                    }
                });
    }

    @Override
    public void onChooseArea() {
        Store.setLocationRationaleSeen();
        openLocationSheet();
    }

    @Override
    public void onDismissPermission() {
        Store.setLocationRationaleSeen();
        hidePermissionCard = true;
        if (PlaceRepository.state() != null) onPlacesChanged(PlaceRepository.state());
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void onPlacesChanged(PlaceRepository.State state) {
        if (binding == null) return;
        updateControls();
        adapter.setOrigin(state.lat, state.lng);
        adapter.setSourceStatus(state.status);
        adapter.setPermissionDenied(permissionDenied);
        adapter.setQuizSubtitle(quizSubtitle());
        binding.refresh.setRefreshing(state.refreshing && !state.places.isEmpty());

        List<Object> rows = new ArrayList<>();
        PlaceAdapter.Status banner = banner(state);
        if (banner != null) rows.add(banner);
        if (showPermissionCard()) rows.add(PlaceAdapter.Row.PERMISSION);
        rows.add(PlaceAdapter.Row.QUIZ);
        if (state.status == PlaceRepository.Status.LOADING) {
            for (int i = 0; i < 4; i++) rows.add(PlaceAdapter.Row.SKELETON);
        } else if (state.places.isEmpty()) {
            adapter.setEmptyText(getString(query.openNow ? R.string.empty_open_now
                    : query.isTextSearch() ? R.string.empty_search : R.string.no_attractions));
            rows.add(PlaceAdapter.Row.EMPTY);
        } else {
            rows.addAll(state.places);
            if (state.loadingMore) rows.add(PlaceAdapter.Row.LOADING_MORE);
            else if (state.canLoadMore) rows.add(PlaceAdapter.Row.LOAD_MORE);
        }
        rows.add(PlaceAdapter.Row.TIP);
        adapter.setItems(rows);

        if (state.status == PlaceRepository.Status.LIVE && state.error != null && state != lastErrorShown) {
            lastErrorShown = state;
            Toast.makeText(requireContext(), Formats.error(requireContext(), state.error), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean showPermissionCard() {
        if (hidePermissionCard || LocationRepository.hasPermission(requireContext())) return false;
        if (LocationRepository.selected().kind == LocationRepository.Kind.MANUAL && !permissionDenied) return false;
        return permissionDenied || !Store.locationRationaleSeen();
    }

    private PlaceAdapter.Status banner(PlaceRepository.State s) {
        String area = LocationRepository.selected().label;
        String radius = GeoUtils.formatDistance(s.query.radiusMeters);
        switch (s.status) {
            case LIVE:
                return new PlaceAdapter.Status(getString(R.string.banner_live_title),
                        getString(R.string.banner_live_body_fmt, s.places.size(), radius, area, Formats.ago(s.fetchedAt)),
                        null, null, false);
            case CACHED:
                return new PlaceAdapter.Status(getString(R.string.banner_cached_title_fmt, Formats.ago(s.fetchedAt)),
                        getString(R.string.banner_reason_fmt, Formats.error(requireContext(), s.error)),
                        getString(R.string.action_retry), () -> load(true), true);
            case CURATED_FALLBACK:
                return new PlaceAdapter.Status(getString(R.string.banner_curated_title),
                        getString(R.string.banner_reason_fmt, Formats.error(requireContext(), s.error)),
                        getString(R.string.action_retry), () -> load(true), true);
            case CURATED_ONLY:
                return new PlaceAdapter.Status(getString(R.string.banner_curated_title),
                        getString(R.string.banner_live_off), null, null, false);
            default:
                return null;
        }
    }

    private String quizSubtitle() {
        int accuracy = Store.quizAccuracy();
        return accuracy < 0 ? getString(R.string.quiz_entry_new)
                : getString(R.string.quiz_entry_stats_fmt, Store.quizzesPlayed(), accuracy);
    }

    // ------------------------------------------------------------------ actions

    @Override
    public void onOpen(Place place) {
        ((MainActivity) requireActivity()).openDetails(place);
    }

    @Override
    public void onAddToPlan(Place place) {
        ((MainActivity) requireActivity()).addToPlan(place, () -> {
            if (adapter != null) adapter.refreshPlace(place.id);
        });
    }

    @Override
    public void onPlayQuiz() {
        startActivity(new Intent(requireContext(), QuizActivity.class));
    }

    @Override
    public void onClearFilters() {
        query = new PlaceQuery("", PlaceQuery.Category.ALL, query.radiusMeters, query.sort, false);
        Store.saveQuery(query);
        binding.search.setText("");
        binding.openNow.setChecked(false);
        buildChips();
        load(false);
    }

    @Override
    public void onLoadMore() {
        PlaceRepository.loadMore();
    }

    private void surprise() {
        List<Place> pool = PlaceRepository.current();
        if (pool.isEmpty()) pool = Catalog.PLACES;
        onOpen(pool.get(random.nextInt(pool.size())));
    }
}
