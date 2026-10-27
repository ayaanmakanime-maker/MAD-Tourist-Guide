package com.example.touristguide;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.touristguide.databinding.FragmentBudgetBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Manual trip budget. Every value is typed by the user and saved as they type; calculations
 * come from {@link BudgetCalculator}. Nothing here is filled in by the AI or by Google prices.
 */
public class BudgetFragment extends Fragment implements PlanRepository.Listener {

    private FragmentBudgetBinding binding;
    /** Field key -> its input layout (for errors). */
    private final Map<String, TextInputLayout> fields = new LinkedHashMap<>();
    private boolean populating;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentBudgetBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        fields.put(BudgetRepository.TOTAL, binding.totalLayout);
        fields.put(BudgetRepository.DAYS, binding.daysLayout);
        fields.put(BudgetRepository.FOOD, binding.foodLayout);
        fields.put(BudgetRepository.TRANSPORT, binding.transportLayout);
        fields.put(BudgetRepository.TICKETS, binding.ticketsLayout);
        fields.put(BudgetRepository.SHOPPING, binding.shoppingLayout);
        fields.put(BudgetRepository.OTHER, binding.otherLayout);

        // First use: default the number of days to the trip length.
        if (BudgetRepository.isEmpty()) {
            BudgetRepository.setText(BudgetRepository.DAYS, String.valueOf(PlanRepository.get().days()));
        }
        populate();
        for (Map.Entry<String, TextInputLayout> e : fields.entrySet()) {
            EditText input = e.getValue().getEditText();
            if (input == null) continue;
            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (populating) return;
                    BudgetRepository.setText(e.getKey(), s.toString());
                    recalculate();
                }
            });
        }
        binding.reset.setOnClickListener(v -> confirmReset());
        binding.useItineraryCosts.setOnClickListener(v -> {
            long total = PlanRepository.get().enteredCostTotal();
            setField(BudgetRepository.TICKETS, String.valueOf(total));
        });
        PlanRepository.addListener(this);
        recalculate();
    }

    @Override
    public void onDestroyView() {
        PlanRepository.removeListener(this);
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onPlanChanged() {
        if (binding != null) recalculate();
    }

    private void populate() {
        populating = true;
        for (Map.Entry<String, TextInputLayout> e : fields.entrySet()) {
            EditText input = e.getValue().getEditText();
            if (input != null) input.setText(BudgetRepository.text(e.getKey()));
        }
        populating = false;
    }

    private void setField(String key, String value) {
        EditText input = fields.get(key).getEditText();
        if (input != null) input.setText(value); // the watcher saves and recalculates
    }

    private void confirmReset() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.budget_reset)
                .setMessage(R.string.budget_reset_confirm)
                .setPositiveButton(R.string.budget_reset_yes, (d, w) -> {
                    BudgetRepository.reset();
                    BudgetRepository.setText(BudgetRepository.DAYS, String.valueOf(PlanRepository.get().days()));
                    populate();
                    recalculate();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Validates every field and redraws the summary immediately. */
    private void recalculate() {
        Context c = requireContext();
        for (Map.Entry<String, TextInputLayout> e : fields.entrySet()) {
            String key = e.getKey();
            String raw = BudgetRepository.text(key);
            BudgetCalculator.Parsed p = key.equals(BudgetRepository.DAYS)
                    ? BudgetCalculator.parseDays(raw) : BudgetCalculator.parseAmount(raw);
            boolean required = key.equals(BudgetRepository.TOTAL) || key.equals(BudgetRepository.DAYS);
            boolean showError = !p.ok() && (p.error != BudgetCalculator.Error.EMPTY || (required && !raw.isEmpty()));
            String message = key.equals(BudgetRepository.DAYS) && p.error != BudgetCalculator.Error.EMPTY
                    ? getString(R.string.budget_error_days) : errorText(c, p.error);
            e.getValue().setError(showError ? message : null);
        }

        BudgetCalculator.Summary s = BudgetRepository.summary();
        BudgetCalculator.Inputs in = BudgetRepository.inputs();
        if (s == null || in == null) {
            binding.resultHeadline.setText(R.string.budget_enter_total);
            binding.resultDetails.setText(R.string.budget_enter_total_body);
            binding.utilisation.setProgress(0);
            binding.overWarning.setVisibility(View.GONE);
        } else {
            binding.resultHeadline.setText(s.overBudget
                    ? getString(R.string.budget_over_headline_fmt, Money.inr(-s.remaining))
                    : getString(R.string.budget_left_headline_fmt, Money.inr(s.remaining)));
            String utilisation = s.utilisationPercent < 0 ? "—"
                    : String.format(Locale.getDefault(), "%.1f%%", s.utilisationPercent);
            binding.resultDetails.setText(getString(R.string.budget_details_fmt,
                    Money.inr(in.total), Money.inr(s.planned), utilisation, in.days,
                    Money.inr(s.dailyBudget), Money.inr(s.plannedPerDay), Money.inr(s.remainingPerDay)));
            binding.utilisation.setProgress((int) Math.max(0, Math.min(100, Math.round(s.utilisationPercent))));
            binding.utilisation.setContentDescription(getString(R.string.budget_utilisation_desc_fmt, utilisation));
            binding.overWarning.setVisibility(s.overBudget ? View.VISIBLE : View.GONE);
            binding.overWarning.setText(getString(R.string.budget_over_warning_fmt, Money.inr(-s.remaining)));
        }

        Itinerary it = PlanRepository.get();
        int withCost = it.stopsWithCost();
        binding.itineraryCosts.setText(withCost == 0 ? getString(R.string.budget_itinerary_none)
                : getString(R.string.budget_itinerary_fmt, Money.inr(it.enteredCostTotal()), withCost, it.size()));
        binding.useItineraryCosts.setVisibility(withCost == 0 ? View.GONE : View.VISIBLE);
    }

    static String errorText(Context c, BudgetCalculator.Error error) {
        switch (error) {
            case EMPTY: return c.getString(R.string.budget_error_required);
            case NEGATIVE: return c.getString(R.string.budget_error_negative);
            case TOO_LARGE: return c.getString(R.string.budget_error_too_large_fmt, Money.inr(BudgetCalculator.MAX_AMOUNT));
            case ZERO_DAYS: return c.getString(R.string.budget_error_days);
            case INVALID:
            default:
                return c.getString(R.string.budget_error_invalid);
        }
    }
}
