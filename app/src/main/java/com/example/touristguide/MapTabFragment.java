package com.example.touristguide;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
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
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

public class MapTabFragment extends Fragment implements OnMapReadyCallback {

    private FragmentTabMapBinding binding;
    private GoogleMap map;
    private boolean locationEnabled;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    enableMyLocation();
                } else {
                    Toast.makeText(requireContext(), R.string.map_location_denied,
                            Toast.LENGTH_LONG).show();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentTabMapBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        if (hasMapKey()) {
            binding.fallback.setVisibility(View.GONE);
            SupportMapFragment child = (SupportMapFragment)
                    getChildFragmentManager().findFragmentById(R.id.map);
            if (child != null) child.getMapAsync(this);
        } else {
            binding.map.setVisibility(View.GONE);
            binding.locationFab.setVisibility(View.GONE);
            binding.fallback.setVisibility(View.VISIBLE);
        }
        binding.openExternal.setOnClickListener(v -> openOverviewInMapsApp());
        binding.locationFab.setOnClickListener(v -> onLocationFab());
    }

    private boolean hasMapKey() {
        try {
            Bundle meta = requireActivity().getPackageManager()
                    .getApplicationInfo(requireContext().getPackageName(),
                            PackageManager.GET_META_DATA).metaData;
            String key = meta == null ? "" : meta.getString("com.google.android.geo.API_KEY", "");
            return key != null && !key.trim().isEmpty();
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        map.getUiSettings().setZoomControlsEnabled(false);
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setMyLocationButtonEnabled(false);

        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (Place p : Catalog.PLACES) {
            LatLng pos = new LatLng(p.lat, p.lng);
            map.addMarker(new MarkerOptions()
                    .position(pos)
                    .title(p.name)
                    .snippet(p.category + " • " + p.area));
            bounds.include(pos);
        }
        map.setOnInfoWindowClickListener(marker -> {
            Place p = Catalog.byName(marker.getTitle());
            if (p != null) openDetails(p);
        });
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 60));
        } catch (IllegalStateException e) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(18.9750, 72.8300), 11f));
        }
    }

    private void onLocationFab() {
        if (map == null) return;
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            enableMyLocation();
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    private void enableMyLocation() {
        if (map == null || locationEnabled) {
            locateMe();
            return;
        }
        try {
            map.setMyLocationEnabled(true);
            map.getUiSettings().setMyLocationButtonEnabled(false);
            locationEnabled = true;
            locateMe();
        } catch (SecurityException e) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    private void locateMe() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationServices.getFusedLocationProviderClient(requireContext())
                .getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(requireActivity(), location -> {
                    if (location == null || map == null) {
                        Toast.makeText(requireContext(), R.string.map_location_unavailable,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                            new LatLng(location.getLatitude(), location.getLongitude()), 14f),
                            600, null);
                });
    }

    private void openOverviewInMapsApp() {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/search/?api=1&query=tourist+attractions+Mumbai"));
        startActivity(intent);
    }

    private void openDetails(Place place) {
        DetailSheet sheet = DetailSheet.newInstance(place);
        sheet.show(getParentFragmentManager(), sheet.getTag());
    }
}
