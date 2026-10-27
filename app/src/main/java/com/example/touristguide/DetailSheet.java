package com.example.touristguide;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.touristguide.databinding.DetailSheetBinding;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class DetailSheet extends BottomSheetDialogFragment {
    private static final String ARG_NAME = "name";
    private DismissListener dismissListener;

    public interface DismissListener {
        void onDetailDismissed(String placeName, boolean savedChanged, boolean planChanged);
    }

    private Place place;
    private boolean savedState;
    private boolean planStateAtOpen;

    public static DetailSheet newInstance(Place place) {
        DetailSheet sheet = new DetailSheet();
        Bundle args = new Bundle();
        args.putString(ARG_NAME, place.name);
        sheet.setArguments(args);
        return sheet;
    }

    public void setDismissListener(DismissListener listener) {
        dismissListener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        DetailSheetBinding binding = DetailSheetBinding.inflate(inflater, container, false);
        String name = requireArguments().getString(ARG_NAME);
        place = Catalog.byName(name);
        if (place == null) dismissNow();
        savedState = Store.isSaved(place.name);
        planStateAtOpen = Store.inPlan(place.name);
        return binding.getRoot();
    }

    private void forceCloseIfMissing() {
        if (place == null) {
            dismiss();
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        dialog.getBehavior().setPeekHeight(com.google.android.material.bottomsheet.BottomSheetBehavior.PEEK_HEIGHT_AUTO);
        return dialog;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        DetailSheetBinding binding = DetailSheetBinding.bind(view);
        refresh(binding);
        binding.directions.setOnClickListener(v -> openDirections());
        binding.save.setOnClickListener(v -> {
            boolean added = Store.toggleSaved(place.name);
            savedState = added;
            Toast.makeText(requireContext(), getString(
                    added ? R.string.saved_toast : R.string.unsaved_toast), Toast.LENGTH_SHORT).show();
            refresh(binding);
        });
        binding.addPlan.setOnClickListener(v -> {
            if (Store.inPlan(place.name)) {
                Toast.makeText(requireContext(), R.string.already_in_plan, Toast.LENGTH_SHORT).show();
            } else {
                Store.addToPlan(place.name);
                Toast.makeText(requireContext(), R.string.plan_added_toast, Toast.LENGTH_SHORT).show();
                refresh(binding);
            }
        });
    }

    private void refresh(DetailSheetBinding binding) {
        binding.icon.setText(place.emoji);
        binding.category.setText(place.category);
        binding.name.setText(place.name);
        binding.area.setText(getString(R.string.detail_area_fmt, place.area));
        binding.duration.setText(getString(R.string.detail_min_visit_fmt, place.minutes));
        binding.price.setText(Formats.priceLabel(requireContext(), place.budget));
        binding.statArea.setText(place.area);
        binding.description.setText(place.description);
        boolean saved = Store.isSaved(place.name);
        binding.save.setText(saved ? R.string.detail_unsave : R.string.detail_save);
        binding.save.setIconResource(saved ? R.drawable.ic_heart : R.drawable.ic_heart_border);
        binding.addPlan.setText(Store.inPlan(place.name)
                ? R.string.detail_in_itinerary : R.string.detail_add_itinerary);
    }

    private void openDirections() {
        try {
            Uri uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination="
                    + place.lat + "," + place.lng);
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.error_no_maps, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (dismissListener != null && place != null) {
            boolean savedChanged = Store.isSaved(place.name) != savedState;
            boolean planChanged = Store.inPlan(place.name) != planStateAtOpen;
            dismissListener.onDetailDismissed(place.name, savedChanged, planChanged);
        }
    }
}
