package com.example.touristguide;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.touristguide.databinding.SheetLocationBinding;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;

/**
 * Lets the user choose where to explore: their current location, a searched area, or a preset.
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
        LocationRepository.searchArea(requireContext(), text, new LocationRepository.Callback() {
            @Override
            public void onLocation(LocationRepository.Selected selected) {
                if (binding == null) return;
                done();
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

    private void choose(LocationRepository.Selected preset) {
        LocationRepository.select(preset);
        done();
    }

    private void done() {
        getParentFragmentManager().setFragmentResult(RESULT, new Bundle());
        dismiss();
    }
}
