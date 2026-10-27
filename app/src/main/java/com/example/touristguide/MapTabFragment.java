package com.example.touristguide;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.touristguide.databinding.FragmentTabMapBinding;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Map of the same places Explore shows (from {@link PlaceRepository}) plus the trip's stops.
 * Markers: red = attractions, orange = food, teal = in your plan.
 *
 * <p>Uses Google Maps when a MAPS_API_KEY is configured, otherwise OpenStreetMap ({@link OsmMap}),
 * which needs no key, so the map always works.</p>
 */
public class MapTabFragment extends Fragment implements OnMapReadyCallback, PlaceRepository.Listener {

    private FragmentTabMapBinding binding;
    private GoogleMap map;
    private OsmMap osm;
    private final Map<Marker, String> markerIds = new HashMap<>();
    private final Map<String, Marker> markersById = new HashMap<>();
    private boolean keepCamera;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (LocationRepository.hasPermission(requireContext())) {
                    useDeviceLocation();
                } else {
                    Toast.makeText(requireContext(), R.string.map_location_denied, Toast.LENGTH_LONG).show();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        OsmMap.configure(requireContext()); // before the OSM MapView is inflated
        binding = FragmentTabMapBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        if (hasGoogleMapsKey(requireContext()) && Store.useGoogleMaps()) {
            SupportMapFragment child = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map);
            if (child != null) child.getMapAsync(this);
        } else {
            binding.map.setVisibility(View.GONE);
            binding.osmMap.setVisibility(View.VISIBLE);
            osm = new OsmMap(binding.osmMap, new OsmMap.Listener() {
                @Override
                public void onPlaceTapped(Place place) {
                    ((MainActivity) requireActivity()).openDetails(place);
                }

                @Override
                public void onUserMovedMap() {
                    if (binding != null) binding.searchArea.setVisibility(View.VISIBLE);
                }
            });
            PlaceRepository.addListener(this);
        }
        binding.locationFab.setOnClickListener(v -> onLocationFab());
        binding.searchArea.setOnClickListener(v -> searchThisArea());
        if (PlaceRepository.state() == null) PlaceRepository.load(Store.savedQuery(), false);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (osm != null) osm.onResume();
    }

    @Override
    public void onPause() {
        if (osm != null) osm.onPause();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        PlaceRepository.removeListener(this);
        if (osm != null) osm.onDestroy();
        osm = null;
        map = null;
        binding = null;
        super.onDestroyView();
    }

    /** True when the build includes a MAPS_API_KEY (it may still fail if the key's project has no billing). */
    static boolean hasGoogleMapsKey(android.content.Context c) {
        try {
            Bundle meta = c.getPackageManager()
                    .getApplicationInfo(c.getPackageName(), PackageManager.GET_META_DATA).metaData;
            String key = meta == null ? "" : meta.getString("com.google.android.geo.API_KEY", "");
            return key != null && !key.trim().isEmpty();
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        if (binding == null) return;
        map = googleMap;
        map.getUiSettings().setZoomControlsEnabled(true);
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setMyLocationButtonEnabled(false);
        map.setOnInfoWindowClickListener(marker -> {
            Place p = PlaceRepository.get(markerIds.get(marker));
            if (p != null) ((MainActivity) requireActivity()).openDetails(p);
        });
        map.setOnCameraMoveStartedListener(reason -> {
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE && binding != null) {
                binding.searchArea.setVisibility(View.VISIBLE);
            }
        });
        enableMyLocationLayer();
        PlaceRepository.addListener(this); // draws markers immediately with the current state
    }

    @Override
    public void onPlacesChanged(PlaceRepository.State state) {
        if (binding == null) return;
        binding.mapStatus.setText(statusText(state));
        if (osm != null) drawOsm(state);
        else if (map != null) drawMarkers(state);
    }

    private String statusText(PlaceRepository.State s) {
        String area = LocationRepository.selected().label;
        switch (s.status) {
            case LOADING:
                return getString(R.string.map_loading);
            case LIVE:
                return getString(R.string.map_status_live_fmt, s.places.size(), area);
            case CACHED:
                return getString(R.string.map_status_cached_fmt, s.places.size(), Formats.ago(s.fetchedAt));
            default:
                return getString(R.string.map_status_curated_fmt, s.places.size());
        }
    }

    /** Plan stops first so they keep their colour even when they are also in the results. */
    private static List<Place> placesToShow(PlaceRepository.State state) {
        Map<String, Place> all = new LinkedHashMap<>();
        for (Itinerary.Stop s : PlanRepository.get().stops()) all.put(s.id(), s.place);
        for (Place p : state.places) if (!all.containsKey(p.id)) all.put(p.id, p);
        return new ArrayList<>(all.values());
    }

    // ------------------------------------------------------------------ OpenStreetMap

    private void drawOsm(PlaceRepository.State state) {
        Place focus = PlaceRepository.get(PlaceRepository.consumeMapFocus());
        boolean moveCamera = focus == null && !keepCamera;
        keepCamera = false;
        osm.draw(state, placesToShow(state), moveCamera);
        if (focus != null) osm.focus(focus);
    }

    // ------------------------------------------------------------------ Google Maps

    private void drawMarkers(PlaceRepository.State state) {
        map.clear();
        markerIds.clear();
        markersById.clear();
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        int count = 0;

        for (Place p : placesToShow(state)) {
            boolean inPlan = PlanRepository.contains(p.id);
            float hue = inPlan ? BitmapDescriptorFactory.HUE_CYAN
                    : p.food ? BitmapDescriptorFactory.HUE_ORANGE : BitmapDescriptorFactory.HUE_RED;
            String snippet = Formats.facts(requireContext(), p, state.lat, state.lng);
            Marker m = map.addMarker(new MarkerOptions()
                    .position(new LatLng(p.lat, p.lng))
                    .title(inPlan ? getString(R.string.map_marker_plan_fmt, p.name) : p.name)
                    .snippet(snippet.isEmpty() ? Formats.subtitle(requireContext(), p) : snippet)
                    .icon(BitmapDescriptorFactory.defaultMarker(hue)));
            if (m == null) continue;
            markerIds.put(m, p.id);
            markersById.put(p.id, m);
            if (state.places.contains(p)) {
                bounds.include(m.getPosition());
                count++;
            }
        }

        LatLng centre = new LatLng(state.lat, state.lng);
        map.addCircle(new CircleOptions().center(centre).radius(state.query.radiusMeters)
                .strokeWidth(2f).strokeColor(ContextCompat.getColor(requireContext(), R.color.accent))
                .fillColor(0x1A0F7A6E));

        // "Show on map" from another screen wins over fitting all markers.
        if (focusOn(PlaceRepository.consumeMapFocus())) return;
        if (keepCamera) {
            keepCamera = false; // the user just chose this view with "Search this area"
            return;
        }
        bounds.include(centre);
        try {
            if (count > 0) map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 80));
            else map.moveCamera(CameraUpdateFactory.newLatLngZoom(centre, 13f));
        } catch (IllegalStateException e) {
            // Map not laid out yet.
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(centre, 12f));
        }
    }

    /** Centres on a place and opens its info window. Returns false if the id is unknown. */
    private boolean focusOn(String id) {
        Place p = PlaceRepository.get(id);
        if (p == null || map == null) return false;
        Marker m = markersById.get(id);
        if (m == null) {
            m = map.addMarker(new MarkerOptions().position(new LatLng(p.lat, p.lng)).title(p.name)
                    .snippet(Formats.subtitle(requireContext(), p)));
            if (m != null) markerIds.put(m, p.id);
        }
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(p.lat, p.lng), 15f));
        if (m != null) m.showInfoWindow();
        return true;
    }

    // ------------------------------------------------------------------ shared actions

    private void searchThisArea() {
        double lat;
        double lng;
        if (osm != null) {
            GeoPoint c = osm.centre();
            lat = c.getLatitude();
            lng = c.getLongitude();
        } else if (map != null) {
            LatLng target = map.getCameraPosition().target;
            lat = target.latitude;
            lng = target.longitude;
        } else {
            return;
        }
        LocationRepository.select(new LocationRepository.Selected(lat, lng,
                getString(R.string.location_map_area), LocationRepository.Kind.MANUAL));
        binding.searchArea.setVisibility(View.GONE);
        keepCamera = true;
        PlaceRepository.State s = PlaceRepository.state();
        PlaceRepository.load(s == null ? Store.savedQuery() : s.query, true);
    }

    private void onLocationFab() {
        if (LocationRepository.hasPermission(requireContext())) {
            useDeviceLocation();
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    private void useDeviceLocation() {
        enableMyLocationLayer();
        LocationRepository.useDeviceLocation(requireContext(), getString(R.string.location_current),
                new LocationRepository.Callback() {
                    @Override
                    public void onLocation(LocationRepository.Selected selected) {
                        if (binding == null) return;
                        PlaceRepository.State s = PlaceRepository.state();
                        PlaceRepository.load(s == null ? Store.savedQuery() : s.query, true);
                    }

                    @Override
                    public void onFailure(LocationRepository.Failure failure) {
                        if (binding == null) return;
                        Toast.makeText(requireContext(), R.string.map_location_unavailable, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @SuppressLint("MissingPermission") // checked by hasPermission()
    private void enableMyLocationLayer() {
        if (map == null || !LocationRepository.hasPermission(requireContext())) return;
        try {
            map.setMyLocationEnabled(true);
        } catch (SecurityException ignored) {
            // Permission revoked between the check and the call.
        }
    }
}
