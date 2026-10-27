package com.example.touristguide;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.touristguide.databinding.DialogInputBinding;
import com.example.touristguide.databinding.DialogTripBinding;
import com.example.touristguide.databinding.FragmentTabPlanBinding;
import com.example.touristguide.databinding.ItemDayHeaderBinding;
import com.example.touristguide.databinding.ItemPlanBinding;
import com.example.touristguide.databinding.ItemPlanHeaderBinding;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Plan tab: the day-by-day itinerary, with the budget planner one toggle away. */
public class PlanFragment extends Fragment implements PlanRepository.Listener {

    /** Google Maps URLs accept a limited number of waypoints. */
    private static final int MAX_WAYPOINTS = 9;
    private static final String HEADER = "header";

    private FragmentTabPlanBinding binding;
    private final PlanAdapter adapter = new PlanAdapter();
    private static boolean showBudget;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentTabPlanBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.list.setAdapter(adapter);
        binding.exploreCta.setOnClickListener(v -> ((MainActivity) requireActivity()).selectTab(R.id.nav_explore));
        binding.foodCta.setOnClickListener(v -> {
            Store.saveQuery(Store.savedQuery().withCategory(PlaceQuery.Category.RESTAURANTS));
            ((MainActivity) requireActivity()).selectTab(R.id.nav_explore);
        });
        binding.planToggle.check(showBudget ? R.id.tabBudget : R.id.tabItinerary);
        binding.planToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            showBudget = checkedId == R.id.tabBudget;
            applyToggle();
        });
        applyToggle();
        PlanRepository.addListener(this);
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroyView() {
        PlanRepository.removeListener(this);
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onPlanChanged() {
        refresh();
    }

    private void applyToggle() {
        binding.itineraryContent.setVisibility(showBudget ? View.GONE : View.VISIBLE);
        binding.budgetContainer.setVisibility(showBudget ? View.VISIBLE : View.GONE);
    }

    private void refresh() {
        if (binding == null) return;
        Itinerary it = PlanRepository.get();
        boolean empty = it.isEmpty();
        binding.list.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.planEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);

        List<Object> rows = new ArrayList<>();
        rows.add(HEADER);
        for (int d = 1; d <= it.days(); d++) {
            List<Itinerary.Stop> stops = it.stopsForDay(d);
            if (it.days() > 1 || !stops.isEmpty()) rows.add(d);
            rows.addAll(stops);
        }
        adapter.setRows(rows);
    }

    private void persist() {
        PlanRepository.save(); // triggers onPlanChanged -> refresh
    }

    // ------------------------------------------------------------------ trip-level actions

    private void editTrip() {
        Itinerary it = PlanRepository.get();
        DialogTripBinding d = DialogTripBinding.inflate(getLayoutInflater());
        d.nameInput.setText(it.tripName());
        d.daysInput.setText(String.valueOf(it.days()));
        final String[] date = {it.startDate()};
        Runnable showDate = () -> d.startDate.setText(date[0].isEmpty()
                ? getString(R.string.trip_pick_date) : getString(R.string.trip_start_fmt, formatDate(date[0], 0)));
        showDate.run();
        d.startDate.setOnClickListener(v -> {
            MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                    .setTitleText(R.string.trip_pick_date).build();
            picker.addOnPositiveButtonClickListener(millis -> {
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                iso.setTimeZone(TimeZone.getTimeZone("UTC")); // the picker returns UTC midnight
                date[0] = iso.format(new Date(millis));
                showDate.run();
            });
            picker.show(getParentFragmentManager(), "date");
        });
        d.clearDate.setOnClickListener(v -> {
            date[0] = "";
            showDate.run();
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.plan_edit_trip)
                .setView(d.getRoot())
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            BudgetCalculator.Parsed days = BudgetCalculator.parseDays(text(d.daysInput.getText()));
            if (!days.ok() || days.value > Itinerary.MAX_DAYS) {
                d.daysLayout.setError(getString(R.string.trip_days_error_fmt, Itinerary.MAX_DAYS));
                return;
            }
            int movedStops = 0;
            for (Itinerary.Stop s : it.stops()) if (s.day > days.value) movedStops++;
            it.setTripName(text(d.nameInput.getText()));
            it.setDays((int) days.value);
            it.setStartDate(date[0]);
            persist();
            if (movedStops > 0) {
                Toast.makeText(requireContext(), getString(R.string.trip_days_moved_fmt, movedStops, (int) days.value),
                        Toast.LENGTH_LONG).show();
            }
            dialog.dismiss();
        });
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.plan_clear)
                .setMessage(R.string.plan_clear_confirm)
                .setPositiveButton(R.string.plan_clear_yes, (d, w) -> {
                    PlanRepository.get().clear();
                    persist();
                    Toast.makeText(requireContext(), R.string.plan_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void orderByProximity() {
        PlanRepository.get().orderEachDayByProximity();
        persist();
        Toast.makeText(requireContext(), R.string.plan_reorder_toast, Toast.LENGTH_LONG).show();
    }

    /** Opens one day's stops in Google Maps (from the current location, in plan order). */
    private void navigateDay(int day) {
        List<Itinerary.Stop> stops = PlanRepository.get().stopsForDay(day);
        if (stops.isEmpty()) return;
        Place last = stops.get(stops.size() - 1).place;
        Uri.Builder b = Formats.directions(last).getData().buildUpon();
        List<Itinerary.Stop> middle = stops.subList(0, stops.size() - 1);
        if (!middle.isEmpty()) {
            StringBuilder waypoints = new StringBuilder();
            for (int i = 0; i < Math.min(MAX_WAYPOINTS, middle.size()); i++) {
                if (i > 0) waypoints.append('|');
                Place p = middle.get(i).place;
                waypoints.append(p.lat).append(',').append(p.lng);
            }
            b.appendQueryParameter("waypoints", waypoints.toString());
            if (middle.size() > MAX_WAYPOINTS) {
                Toast.makeText(requireContext(), getString(R.string.plan_waypoints_limited_fmt, MAX_WAYPOINTS),
                        Toast.LENGTH_LONG).show();
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, b.build()));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.error_no_maps, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ stop-level actions

    private void showStopMenu(View anchor, Itinerary.Stop stop) {
        Itinerary it = PlanRepository.get();
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        Menu m = menu.getMenu();
        m.add(Menu.NONE, 1, 1, R.string.plan_edit_note);
        m.add(Menu.NONE, 2, 2, R.string.plan_set_cost);
        if (it.days() > 1) m.add(Menu.NONE, 3, 3, R.string.plan_move_day);
        m.add(Menu.NONE, 4, 4, R.string.directions);
        m.add(Menu.NONE, 5, 5, R.string.action_show_on_map);
        m.add(Menu.NONE, 6, 6, R.string.remove);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: editNote(stop); break;
                case 2: editCost(stop); break;
                case 3: moveToDay(stop); break;
                case 4: openDirections(stop.place); break;
                case 5: ((MainActivity) requireActivity()).showOnMap(stop.place); break;
                default: remove(stop);
            }
            return true;
        });
        menu.show();
    }

    private void editNote(Itinerary.Stop stop) {
        DialogInputBinding d = DialogInputBinding.inflate(getLayoutInflater());
        d.inputLayout.setHint(getString(R.string.plan_note_hint));
        d.inputLayout.setCounterEnabled(true);
        d.inputLayout.setCounterMaxLength(Itinerary.MAX_NOTE_LENGTH);
        d.input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        d.input.setText(stop.note);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.plan_note_title_fmt, stop.place.name))
                .setView(d.getRoot())
                .setPositiveButton(R.string.save, (dialog, w) -> {
                    PlanRepository.get().setNote(stop.id(), text(d.input.getText()));
                    persist();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** The user's own estimate; never filled in automatically from Google price levels. */
    private void editCost(Itinerary.Stop stop) {
        DialogInputBinding d = DialogInputBinding.inflate(getLayoutInflater());
        d.inputLayout.setHint(getString(R.string.plan_cost_hint));
        d.inputLayout.setHelperText(getString(R.string.plan_cost_helper));
        d.inputLayout.setPrefixText("₹");
        d.input.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (stop.estimatedCost != null) d.input.setText(String.valueOf(stop.estimatedCost));
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.plan_cost_title_fmt, stop.place.name))
                .setView(d.getRoot())
                .setPositiveButton(R.string.save, null)
                .setNeutralButton(R.string.plan_cost_clear, (dlg, w) -> {
                    PlanRepository.get().setEstimatedCost(stop.id(), null);
                    persist();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            BudgetCalculator.Parsed p = BudgetCalculator.parseAmount(text(d.input.getText()));
            if (p.error == BudgetCalculator.Error.EMPTY) {
                PlanRepository.get().setEstimatedCost(stop.id(), null);
            } else if (!p.ok()) {
                d.inputLayout.setError(BudgetFragment.errorText(requireContext(), p.error));
                return;
            } else {
                PlanRepository.get().setEstimatedCost(stop.id(), p.value);
            }
            persist();
            dialog.dismiss();
        });
    }

    private void moveToDay(Itinerary.Stop stop) {
        int days = PlanRepository.get().days();
        String[] labels = new String[days];
        for (int d = 0; d < days; d++) labels[d] = getString(R.string.day_fmt, d + 1);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.plan_move_day)
                .setSingleChoiceItems(labels, stop.day - 1, (dialog, which) -> {
                    PlanRepository.get().moveToDay(stop.id(), which + 1);
                    persist();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void remove(Itinerary.Stop stop) {
        Itinerary it = PlanRepository.get();
        int index = it.indexOf(stop.id());
        it.remove(stop.id());
        persist();
        Snackbar.make(binding.getRoot(), getString(R.string.plan_removed_fmt, stop.place.name), Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo, v -> {
                    PlanRepository.get().restore(stop, index);
                    persist();
                })
                .show();
    }

    private void openDirections(Place p) {
        try {
            startActivity(Formats.directions(p));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.error_no_maps, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String text(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    /** "Sat, 14 Nov" for day N of a trip starting on an ISO date; empty if no date. */
    private static String formatDate(String isoStart, int offsetDays) {
        if (isoStart == null || isoStart.isEmpty()) return "";
        try {
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date start = iso.parse(isoStart);
            if (start == null) return "";
            Calendar cal = Calendar.getInstance();
            cal.setTime(start);
            cal.add(Calendar.DAY_OF_MONTH, offsetDays);
            return DateFormat.getDateInstance(DateFormat.MEDIUM).format(cal.getTime());
        } catch (ParseException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ adapter

    private class PlanAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_DAY = 1;
        private static final int TYPE_STOP = 2;
        private List<Object> rows = new ArrayList<>();

        void setRows(List<Object> list) {
            rows = list;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            Object o = rows.get(position);
            if (o instanceof Integer) return TYPE_DAY;
            if (o instanceof Itinerary.Stop) return TYPE_STOP;
            return TYPE_HEADER;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) return new Holder<>(ItemPlanHeaderBinding.inflate(inf, parent, false));
            if (viewType == TYPE_DAY) return new Holder<>(ItemDayHeaderBinding.inflate(inf, parent, false));
            return new Holder<>(ItemPlanBinding.inflate(inf, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object binding = ((Holder<?>) holder).b;
            Object row = rows.get(position);
            if (binding instanceof ItemPlanHeaderBinding) bindHeader((ItemPlanHeaderBinding) binding);
            else if (binding instanceof ItemDayHeaderBinding) bindDay((ItemDayHeaderBinding) binding, (Integer) row);
            else bindStop((ItemPlanBinding) binding, (Itinerary.Stop) row);
        }

        private void bindHeader(ItemPlanHeaderBinding b) {
            Itinerary it = PlanRepository.get();
            b.tripName.setText(it.tripName().isEmpty() ? getString(R.string.plan_title) : it.tripName());
            String dates = it.startDate().isEmpty() ? getString(R.string.plan_days_fmt, it.days())
                    : getString(R.string.plan_days_from_fmt, it.days(), formatDate(it.startDate(), 0));
            b.tripDates.setText(dates);
            b.editTrip.setOnClickListener(v -> editTrip());
            b.summaryStops.setText(getResources().getQuantityString(R.plurals.plan_stops, it.size(), it.size()));
            int withCost = it.stopsWithCost();
            b.summaryCost.setText(withCost == 0 ? getString(R.string.plan_cost_none)
                    : getString(R.string.plan_cost_entered_fmt, Money.inr(it.enteredCostTotal()), withCost, it.size()));
            b.reorder.setOnClickListener(v -> orderByProximity());
            b.reorder.setEnabled(it.size() > 2);
            b.clear.setOnClickListener(v -> confirmClear());
        }

        private void bindDay(ItemDayHeaderBinding b, int day) {
            Itinerary it = PlanRepository.get();
            String date = formatDate(it.startDate(), day - 1);
            b.dayTitle.setText(date.isEmpty() ? getString(R.string.day_fmt, day) : getString(R.string.day_date_fmt, day, date));
            int n = it.stopsForDay(day).size();
            b.daySub.setText(n == 0 ? getString(R.string.plan_day_empty)
                    : getResources().getQuantityString(R.plurals.plan_stops, n, n));
            b.navigateDay.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
            b.navigateDay.setContentDescription(getString(R.string.plan_navigate_day_desc_fmt, day));
            b.navigateDay.setOnClickListener(v -> navigateDay(day));
        }

        private void bindStop(ItemPlanBinding b, Itinerary.Stop stop) {
            Context c = b.getRoot().getContext();
            Place p = stop.place;
            List<Itinerary.Stop> dayStops = PlanRepository.get().stopsForDay(stop.day);
            int index = dayStops.indexOf(stop);
            b.index.setText(String.format(Locale.getDefault(), "%d", index + 1));
            b.name.setText(p.name);
            String facts = Formats.facts(c, p, LocationRepository.selected().lat, LocationRepository.selected().lng);
            b.meta.setText(facts.isEmpty() ? Formats.subtitle(c, p) : Formats.subtitle(c, p) + " • " + facts);
            b.note.setVisibility(stop.note.isEmpty() ? View.GONE : View.VISIBLE);
            b.note.setText(stop.note);
            b.cost.setVisibility(stop.estimatedCost == null ? View.GONE : View.VISIBLE);
            if (stop.estimatedCost != null) {
                b.cost.setText(getString(R.string.plan_cost_your_estimate_fmt, Money.inr(stop.estimatedCost)));
            }
            b.up.setEnabled(index > 0);
            b.up.setAlpha(index > 0 ? 1f : 0.3f);
            b.down.setEnabled(index < dayStops.size() - 1);
            b.down.setAlpha(index < dayStops.size() - 1 ? 1f : 0.3f);
            b.up.setContentDescription(getString(R.string.plan_move_up_fmt, p.name));
            b.down.setContentDescription(getString(R.string.plan_move_down_fmt, p.name));
            b.more.setContentDescription(getString(R.string.plan_more_actions_fmt, p.name));
            b.up.setOnClickListener(v -> {
                PlanRepository.get().moveUp(stop.id());
                persist();
            });
            b.down.setOnClickListener(v -> {
                PlanRepository.get().moveDown(stop.id());
                persist();
            });
            b.more.setOnClickListener(v -> showStopMenu(v, stop));
            b.getRoot().setOnClickListener(v -> ((MainActivity) requireActivity()).openDetails(p));
        }
    }

    private static class Holder<B extends androidx.viewbinding.ViewBinding> extends RecyclerView.ViewHolder {
        final B b;

        Holder(B b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
