package com.example.touristguide;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.touristguide.databinding.FragmentTabPlanBinding;
import com.example.touristguide.databinding.ItemPlanBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlanFragment extends Fragment {

    private FragmentTabPlanBinding binding;
    private StopAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentTabPlanBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        adapter = new StopAdapter();
        binding.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.list.setAdapter(adapter);
        binding.clear.setOnClickListener(v -> confirmClear());
        binding.reorder.setOnClickListener(v -> {
            Store.reorderPlanByArea();
            Toast.makeText(requireContext(), R.string.plan_reorder_toast, Toast.LENGTH_SHORT).show();
            refresh();
        });
        binding.openDay.setOnClickListener(v -> openWholeRoute());
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<Place> stops = Store.planPlaces();
        adapter.setStops(stops);
        boolean empty = stops.isEmpty();
        binding.summary.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.planActions.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.clear.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.planEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.list.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) {
            int minutes = 0;
            int budget = 0;
            for (Place p : stops) {
                minutes += p.minutes;
                budget += p.budget;
            }
            binding.summaryTime.setText(getString(R.string.plan_summary_time_fmt,
                    minutes, formatHours(minutes)));
            binding.summaryBudget.setText(getString(R.string.plan_summary_budget_fmt, budget));
        }
    }

    private String formatHours(int minutes) {
        double hours = minutes / 60.0;
        return String.format(Locale.US, "%.1f", hours);
    }

    private void confirmClear() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.plan_clear)
                .setMessage(R.string.plan_clear_confirm)
                .setPositiveButton(R.string.plan_clear_yes, (d, w) -> {
                    Store.clearPlan();
                    refresh();
                    Toast.makeText(requireContext(), R.string.plan_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openWholeRoute() {
        List<Place> stops = Store.planPlaces();
        if (stops.isEmpty()) return;
        StringBuilder url = new StringBuilder("https://www.google.com/maps/dir/?api=1");
        url.append("&destination=").append(stops.get(stops.size() - 1).lat)
                .append(",").append(stops.get(stops.size() - 1).lng);
        if (stops.size() > 2) {
            StringBuilder waypoints = new StringBuilder("&waypoints=");
            for (int i = 1; i < stops.size() - 1; i++) {
                if (waypoints.length() > 10) waypoints.append("|");
                waypoints.append(stops.get(i).lat).append(",").append(stops.get(i).lng);
            }
            url.append(waypoints);
        }
        url.append("&travelmode=transit");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())));
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.error_no_maps, Toast.LENGTH_SHORT).show();
        }
    }

    private class StopAdapter extends RecyclerView.Adapter<StopHolder> {
        private List<Place> stops = new ArrayList<>();

        void setStops(List<Place> list) {
            stops = list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public StopHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemPlanBinding b = ItemPlanBinding.inflate(getLayoutInflater(), parent, false);
            return new StopHolder(b);
        }

        @Override
        public void onBindViewHolder(@NonNull StopHolder holder, int position) {
            Place p = stops.get(position);
            android.content.Context ctx = holder.b.getRoot().getContext();
            holder.b.name.setText(p.name);
            holder.b.meta.setText(ctx.getString(R.string.plan_meta_fmt,
                    p.category, p.area, Formats.metaLabel(ctx, p.minutes, p.budget)));
            holder.b.index.setText(String.format(Locale.US, "%d", position + 1));
            holder.b.delete.setOnClickListener(v -> {
                Store.removeFromPlan(p.name);
                refresh();
            });
            holder.b.getRoot().setOnClickListener(v ->
                    ((MainActivity) requireActivity()).openDetails(p));
        }

        @Override
        public int getItemCount() {
            return stops.size();
        }
    }

    private static class StopHolder extends RecyclerView.ViewHolder {
        final ItemPlanBinding b;

        StopHolder(ItemPlanBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
