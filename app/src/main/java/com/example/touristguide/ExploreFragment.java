package com.example.touristguide;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.touristguide.databinding.FragmentExploreBinding;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ExploreFragment extends Fragment implements PlaceAdapter.Listener {

    private FragmentExploreBinding binding;
    private PlaceAdapter adapter;
    private String filter = "All";
    private final Random random = new Random();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentExploreBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        adapter = new PlaceAdapter(requireActivity(), new ArrayList<>(), this, new QuizBridge());
        binding.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.list.setAdapter(adapter);

        binding.search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                refreshList();
            }
        });
        binding.surprise.setOnClickListener(v -> surprise());
        buildChips();
        refreshList();
    }

    private void surprise() {
        List<Place> all = Catalog.PLACES;
        openDetails(all.get(random.nextInt(all.size())));
    }

    private void buildChips() {
        binding.chips.removeAllViews();
        String[] labels = getResources().getStringArray(R.array.filter_labels);
        for (int i = 0; i < Catalog.FILTERS.length; i++) {
            final String key = Catalog.FILTERS[i];
            final String label = i < labels.length ? labels[i] : key;
            MaterialButton chip = new MaterialButton(requireContext(), null,
                    key.equals(filter)
                            ? com.google.android.material.R.attr.materialButtonStyle
                            : com.google.android.material.R.attr.materialButtonOutlinedStyle);
            chip.setText(label);
            chip.setTextSize(11);
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setMinWidth(0);
            chip.setMinimumWidth(0);
            chip.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            chip.setOnClickListener(v -> {
                filter = key;
                buildChips();
                refreshList();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
            lp.setMargins(dp(2), 0, dp(2), 0);
            binding.chips.addView(chip, lp);
        }
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refreshList() {
        List<Object> rows = new ArrayList<>();
        rows.add(PlaceAdapter.TYPE_QUIZ);
        String query = binding.search.getText() == null ? "" : binding.search.getText().toString();
        List<Place> match = Catalog.filtered(query, filter);
        if (match.isEmpty()) {
            rows.add(PlaceAdapter.TYPE_EMPTY);
        } else {
            rows.addAll(match);
        }
        rows.add(PlaceAdapter.TYPE_TIP);
        adapter.setItems(rows);
    }

    @Override
    public void onOpen(Place place) {
        openDetails(place);
    }

    @Override
    public void onSavedChanged(Place place, boolean added) {
    }

    private void openDetails(Place place) {
        DetailSheet sheet = DetailSheet.newInstance(place);
        sheet.show(getChildFragmentManager(), sheet.getTag());
    }

    private class QuizBridge implements PlaceAdapter.QuizCallbacks {
        public String question() {
            switch (Store.quizIndex()) {
                case 0: return getString(R.string.quiz_q1);
                case 1: return getString(R.string.quiz_q2);
                default: return getString(R.string.quiz_q3);
            }
        }

        public boolean answerVisible() {
            return Store.quizAnswerVisible();
        }

        public void toggleAnswer() {
            Store.setQuizAnswerVisible(!Store.quizAnswerVisible());
        }

        public void next() {
            Store.setQuizIndex(Store.quizIndex() + 1);
            Store.setQuizAnswerVisible(false);
        }
    }
}
