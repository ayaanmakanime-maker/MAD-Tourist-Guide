package com.example.touristguide;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.touristguide.databinding.ItemAreaResultBinding;
import com.example.touristguide.databinding.SheetLocationBinding;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;

import java.util.List;

/**
 * Lets the user choose where to explore: their current location, a searched area or landmark
 * (matches listed so the right one can be picked), or a preset.
 * Works without location permission. Reports back through the fragment result {@link #RESULT}.
 */
public class LocationSheet extends BottomSheetDialogFragment {
    public static final String RESULT = "location_result";
    /** True when the user asked to use the device location (the caller handles permission). */
    public static final String USE_DEVICE = "use_device";

    private SheetLocationBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = SheetLocationBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.useDevice.setOnClickListener(v -> {
            Bundle b = new Bundle();
            b.putBoolean(USE_DEVICE, true);
            getParentFragmentManager().setFragmentResult(RESULT, b);
            dismiss();
        });
        binding.areaGo.setOnClickListener(v -> search());
        binding.areaInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search();
                return true;
            }
            return false;
        });
        for (LocationRepository.Selected preset : LocationRepository.PRESETS) {
            Chip chip = new Chip(requireContext());
            chip.setText(preset.label);
            chip.setOnClickListener(v -> choose(preset));
            binding.presets.addView(chip);
        }
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private void search() {
        String text = binding.areaInput.getText() == null ? "" : binding.areaInput.getText().toString().trim();
        if (text.length() < 2) {
            binding.areaLayout.setError(getString(R.string.location_search_too_short));
            return;
        }
        binding.areaLayout.setError(null);
        binding.progress.setVisibility(View.VISIBLE);
        binding.areaGo.setEnabled(false);
        binding.results.removeAllViews();
        binding.resultsTitle.setVisibility(View.GONE);
        LocationRepository.findAreas(requireContext(), text, new LocationRepository.AreasCallback() {
            @Override
            public void onAreas(List<LocationRepository.AreaMatch> matches) {
                if (binding == null) return;
                binding.progress.setVisibility(View.GONE);
                binding.areaGo.setEnabled(true);
                showMatches(matches);
            }

            @Override
            public void onFailure(LocationRepository.Failure failure) {
                if (binding == null) return;
                binding.progress.setVisibility(View.GONE);
                binding.areaGo.setEnabled(true);
                binding.areaLayout.setError(getString(failure == LocationRepository.Failure.NOT_FOUND
                        ? R.string.location_not_found : R.string.location_search_unavailable));
            }
        });
    }

    /** Lists the matches so the user picks the right one (names alone are often ambiguous). */
    private void showMatches(List<LocationRepository.AreaMatch> matches) {
        binding.resultsTitle.setText(matches.get(0).fromGoogle
                ? R.string.location_results_google : R.string.location_results_device);
        binding.resultsTitle.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (LocationRepository.AreaMatch match : matches) {
            ItemAreaResultBinding row = ItemAreaResultBinding.inflate(inflater, binding.results, false);
            row.areaName.setText(match.location.label);
            row.areaAddress.setText(match.address);
            row.areaAddress.setVisibility(match.address.isEmpty() ? View.GONE : View.VISIBLE);
            row.getRoot().setContentDescription(getString(R.string.location_result_desc_fmt,
                    match.location.label, match.address));
            row.getRoot().setOnClickListener(v -> choose(match.location));
            binding.results.addView(row.getRoot());
        }
    }

    private void choose(LocationRepository.Selected preset) {
        LocationRepository.select(preset);
        done();
    }

    private void done() {
        getParentFragmentManager().setFragmentResult(RESULT, new Bundle());
        dismiss();
    }
}
