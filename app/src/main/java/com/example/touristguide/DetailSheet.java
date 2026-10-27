package com.example.touristguide;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.touristguide.databinding.DetailSheetBinding;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/** Details for one place (live, cached or curated), with save / plan / map / chat actions. */
public class DetailSheet extends BottomSheetDialogFragment {
    private static final String ARG_ID = "place_id";

    private DetailSheetBinding binding;
    private Place place;

    public static DetailSheet newInstance(String placeId) {
        DetailSheet sheet = new DetailSheet();
        Bundle args = new Bundle();
        args.putString(ARG_ID, placeId);
        sheet.setArguments(args);
        return sheet;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        dialog.getBehavior().setPeekHeight(BottomSheetBehavior.PEEK_HEIGHT_AUTO);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = DetailSheetBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        place = PlaceRepository.get(requireArguments().getString(ARG_ID));
        if (place == null) {
            // e.g. process restarted and the live result is gone: close instead of crashing.
            Toast.makeText(requireContext(), R.string.detail_unavailable, Toast.LENGTH_LONG).show();
            view.post(this::dismissAllowingStateLoss);
            return;
        }
        PlaceRepository.setLastViewed(place.id);
        bind();
        loadPhoto();

        binding.directions.setOnClickListener(v -> openDirections());
        binding.save.setOnClickListener(v -> {
            boolean added = Store.toggleSaved(place);
            Toast.makeText(requireContext(), added ? R.string.saved_toast : R.string.unsaved_toast, Toast.LENGTH_SHORT).show();
            bindButtons();
        });
        binding.addPlan.setOnClickListener(v -> main().addToPlan(place, this::bindButtons));
        binding.showOnMap.setOnClickListener(v -> {
            dismiss();
            main().showOnMap(place);
        });
        binding.ask.setOnClickListener(v -> {
            dismiss();
            main().askAssistant(getString(place.food ? R.string.ask_food_place_fmt : R.string.ask_place_fmt, place.name));
        });
        binding.openMaps.setOnClickListener(v -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(place.mapsUri))));

        // Live places: refresh details (hours, rating, photo) and detect places that disappeared.
        if (place.isLive()) {
            PlaceRepository.refreshDetails(place, new BackendClient.Callback<Place>() {
                @Override
                public void onSuccess(Place fresh) {
                    if (binding == null) return;
                    place = fresh;
                    bind();
                    loadPhoto();
                }

                @Override
                public void onError(BackendClient.ApiError error) {
                    if (binding == null) return;
                    if (error.kind == BackendClient.ErrorKind.NOT_FOUND) binding.unavailable.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private MainActivity main() {
        return (MainActivity) requireActivity();
    }

    private void bind() {
        Context c = requireContext();
        binding.icon.setText(place.emoji);
        binding.category.setText(place.category);
        binding.category.setVisibility(place.category.isEmpty() ? View.GONE : View.VISIBLE);
        binding.name.setText(place.name);
        binding.area.setText(place.area);
        binding.area.setVisibility(place.area.isEmpty() ? View.GONE : View.VISIBLE);

        PlaceRepository.State state = PlaceRepository.state();
        binding.source.setText(Formats.source(c, place,
                state == null ? PlaceRepository.Status.LIVE : state.status));

        LocationRepository.Selected loc = LocationRepository.selected();
        binding.factsList.removeAllViews();
        addFact(R.string.fact_rating, Formats.rating(c, place));
        addFact(R.string.fact_distance, Formats.distance(c, place, loc.lat, loc.lng));
        addFact(R.string.fact_open, Formats.openStatus(c, place));
        if (place.isLive()) {
            addFact(R.string.fact_price, Formats.googlePrice(c, place));
        } else {
            if (place.minutes > 0) addFact(R.string.fact_visit, getString(R.string.minutes_fmt, place.minutes));
            addFact(R.string.fact_entry, Formats.priceLabel(c, place.budget));
        }
        if (binding.factsList.getChildCount() == 0) addFact(R.string.fact_info, getString(R.string.detail_no_facts));

        binding.hours.setVisibility(place.hours.isEmpty() ? View.GONE : View.VISIBLE);
        binding.hours.setText(TextUtils.join("\n", place.hours));

        boolean hasDescription = !place.description.isEmpty();
        binding.aboutTitle.setVisibility(hasDescription ? View.VISIBLE : View.GONE);
        binding.description.setVisibility(hasDescription ? View.VISIBLE : View.GONE);
        binding.description.setText(place.description);
        binding.note.setText(place.isLive() ? R.string.detail_note_live : R.string.detail_note);
        binding.openMaps.setVisibility(place.mapsUri != null ? View.VISIBLE : View.GONE);
        binding.attribution.setVisibility(place.isLive() ? View.VISIBLE : View.GONE);
        bindButtons();
    }

    private void addFact(int label, String value) {
        if (value == null || value.isEmpty()) return;
        TextView tv = new TextView(requireContext());
        tv.setTextAppearance(R.style.Text_Body);
        tv.setText(getString(R.string.fact_line_fmt, getString(label), value));
        tv.setPadding(0, 4, 0, 4);
        binding.factsList.addView(tv);
    }

    private void bindButtons() {
        if (binding == null) return;
        boolean saved = Store.isSaved(place.id);
        binding.save.setText(saved ? R.string.detail_unsave : R.string.detail_save);
        binding.save.setIconResource(saved ? R.drawable.ic_heart : R.drawable.ic_heart_border);
        boolean inPlan = PlanRepository.contains(place.id);
        binding.addPlan.setText(inPlan ? R.string.detail_in_itinerary : R.string.detail_add_itinerary);
        binding.addPlan.setIconResource(inPlan ? R.drawable.ic_check : R.drawable.ic_add);
    }

    private void loadPhoto() {
        if (place.photoName == null) return;
        int width = getResources().getDisplayMetrics().widthPixels;
        ImageLoader.loadPlacePhoto(place, width, binding.photo, new ImageLoader.Callback() {
            @Override
            public void onLoaded(Bitmap bitmap) {
                if (binding == null) return;
                binding.photo.setVisibility(View.VISIBLE);
                binding.photo.setContentDescription(getString(R.string.photo_desc_fmt, place.name));
                binding.icon.setVisibility(View.GONE);
                if (place.photoAttribution != null) {
                    binding.photoCredit.setVisibility(View.VISIBLE);
                    binding.photoCredit.setText(getString(R.string.photo_credit_fmt, place.photoAttribution));
                }
            }

            @Override
            public void onFailed() {
                // Keep the emoji header; photos are optional.
            }
        });
    }

    private void openDirections() {
        open(Formats.directions(place));
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.error_no_maps, Toast.LENGTH_SHORT).show();
        }
    }
}
