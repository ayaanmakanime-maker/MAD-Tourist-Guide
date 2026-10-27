package com.example.touristguide;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.touristguide.databinding.ItemEmptyBinding;
import com.example.touristguide.databinding.ItemLoadMoreBinding;
import com.example.touristguide.databinding.ItemPermissionBinding;
import com.example.touristguide.databinding.ItemPlaceBinding;
import com.example.touristguide.databinding.ItemQuizEntryBinding;
import com.example.touristguide.databinding.ItemStatusBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Explore's list: place cards plus status, quiz, permission, loading, empty and "load more" rows.
 * Also used (places only, horizontally) for the saved strip on the More tab.
 */
public class PlaceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onOpen(Place place);

        void onAddToPlan(Place place);

        default void onSavedChanged(Place place, boolean saved) {
        }

        default void onPlayQuiz() {
        }

        default void onAllowLocation() {
        }

        default void onChooseArea() {
        }

        default void onDismissPermission() {
        }

        default void onClearFilters() {
        }

        default void onLoadMore() {
        }
    }

    /** Non-place rows. */
    public enum Row { QUIZ, PERMISSION, SKELETON, EMPTY, LOAD_MORE, LOADING_MORE, TIP }

    /** A status banner row (data source, errors, retry). */
    public static final class Status {
        final String title;
        final String body;
        final String action;
        final Runnable onAction;
        final boolean warning;

        public Status(String title, String body, String action, Runnable onAction, boolean warning) {
            this.title = title;
            this.body = body;
            this.action = action;
            this.onAction = onAction;
            this.warning = warning;
        }
    }

    private static final int TYPE_PLACE = 0;
    private static final int TYPE_STATUS = 1;
    private static final int TYPE_ROW_BASE = 10;

    private final Listener listener;
    private final boolean compact;
    private List<Object> items = new ArrayList<>();
    private double originLat = Double.NaN;
    private double originLng = Double.NaN;
    private PlaceRepository.Status sourceStatus = PlaceRepository.Status.LIVE;
    private String quizSubtitle = "";
    private String emptyText;
    private boolean permissionDenied;

    /** @param compact true for the narrow horizontal cards on the More tab */
    public PlaceAdapter(Listener listener, boolean compact) {
        this.listener = listener;
        this.compact = compact;
    }

    public void setItems(List<Object> rows) {
        items = rows;
        notifyDataSetChanged();
    }

    public void setOrigin(double lat, double lng) {
        originLat = lat;
        originLng = lng;
    }

    public void setSourceStatus(PlaceRepository.Status status) {
        sourceStatus = status;
    }

    public void setQuizSubtitle(String subtitle) {
        quizSubtitle = subtitle;
    }

    public void setPermissionDenied(boolean denied) {
        permissionDenied = denied;
    }

    public void setEmptyText(String text) {
        emptyText = text;
    }

    /** Re-binds cards for a place (after save / add-to-plan changes elsewhere). */
    public void refreshPlace(String placeId) {
        for (int i = 0; i < items.size(); i++) {
            Object o = items.get(i);
            if (o instanceof Place && ((Place) o).id.equals(placeId)) notifyItemChanged(i);
        }
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item instanceof Place) return TYPE_PLACE;
        if (item instanceof Status) return TYPE_STATUS;
        return TYPE_ROW_BASE + ((Row) item).ordinal();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_PLACE) {
            ItemPlaceBinding b = ItemPlaceBinding.inflate(inflater, parent, false);
            if (compact) {
                int width = (int) (parent.getResources().getDisplayMetrics().density * 280);
                b.getRoot().setLayoutParams(new RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return new PlaceHolder(b);
        }
        if (viewType == TYPE_STATUS) return new StatusHolder(ItemStatusBinding.inflate(inflater, parent, false));
        Row row = Row.values()[viewType - TYPE_ROW_BASE];
        switch (row) {
            case QUIZ:
                return new QuizHolder(ItemQuizEntryBinding.inflate(inflater, parent, false));
            case PERMISSION:
                return new PermissionHolder(ItemPermissionBinding.inflate(inflater, parent, false));
            case SKELETON:
                return new SimpleHolder(inflater.inflate(R.layout.item_skeleton, parent, false));
            case EMPTY:
                return new EmptyHolder(ItemEmptyBinding.inflate(inflater, parent, false));
            case LOAD_MORE:
            case LOADING_MORE:
                return new LoadMoreHolder(ItemLoadMoreBinding.inflate(inflater, parent, false));
            default:
                return new SimpleHolder(inflater.inflate(R.layout.item_tip, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        if (holder instanceof PlaceHolder) ((PlaceHolder) holder).bind((Place) item);
        else if (holder instanceof StatusHolder) ((StatusHolder) holder).bind((Status) item);
        else if (holder instanceof QuizHolder) ((QuizHolder) holder).bind();
        else if (holder instanceof PermissionHolder) ((PermissionHolder) holder).bind();
        else if (holder instanceof EmptyHolder) ((EmptyHolder) holder).bind();
        else if (holder instanceof LoadMoreHolder) ((LoadMoreHolder) holder).bind(item == Row.LOADING_MORE);
    }

    class PlaceHolder extends RecyclerView.ViewHolder {
        final ItemPlaceBinding b;

        PlaceHolder(ItemPlaceBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind(Place p) {
            Context c = b.getRoot().getContext();
            b.emoji.setText(p.emoji);
            b.name.setText(p.name);
            b.sub.setText(Formats.subtitle(c, p));
            String facts = Formats.facts(c, p, originLat, originLng);
            b.facts.setText(facts);
            b.facts.setVisibility(facts.isEmpty() ? View.GONE : View.VISIBLE);
            b.description.setText(p.description);
            b.description.setVisibility(p.description.isEmpty() || compact ? View.GONE : View.VISIBLE);
            b.source.setText(Formats.source(c, p, sourceStatus));

            boolean saved = Store.isSaved(p.id);
            b.save.setImageResource(saved ? R.drawable.ic_heart : R.drawable.ic_heart_border);
            b.save.setContentDescription(c.getString(saved ? R.string.detail_unsave_named : R.string.detail_save_named, p.name));
            b.save.setOnClickListener(v -> {
                boolean nowSaved = Store.toggleSaved(p);
                bind(p);
                v.announceForAccessibility(c.getString(nowSaved ? R.string.saved_toast : R.string.unsaved_toast));
                listener.onSavedChanged(p, nowSaved);
            });

            boolean inPlan = PlanRepository.contains(p.id);
            b.addPlan.setText(inPlan ? R.string.detail_in_itinerary : R.string.action_add_plan);
            b.addPlan.setIconResource(inPlan ? R.drawable.ic_check : R.drawable.ic_add);
            b.addPlan.setContentDescription(c.getString(inPlan ? R.string.in_plan_named : R.string.add_plan_named, p.name));
            b.addPlan.setOnClickListener(v -> listener.onAddToPlan(p));

            b.getRoot().setContentDescription(null);
            b.getRoot().setOnClickListener(v -> listener.onOpen(p));
        }
    }

    class StatusHolder extends RecyclerView.ViewHolder {
        final ItemStatusBinding b;

        StatusHolder(ItemStatusBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind(Status s) {
            Context c = b.getRoot().getContext();
            b.card.setCardBackgroundColor(c.getColor(s.warning ? R.color.warning_bg : R.color.surface_variant));
            b.statusTitle.setTextColor(c.getColor(s.warning ? R.color.warning_text : R.color.title));
            b.statusTitle.setText(s.title);
            b.statusBody.setText(s.body);
            b.statusBody.setVisibility(s.body == null || s.body.isEmpty() ? View.GONE : View.VISIBLE);
            boolean hasAction = s.action != null && s.onAction != null;
            b.statusAction.setVisibility(hasAction ? View.VISIBLE : View.GONE);
            if (hasAction) {
                b.statusAction.setText(s.action);
                b.statusAction.setOnClickListener(v -> s.onAction.run());
            }
        }
    }

    class QuizHolder extends RecyclerView.ViewHolder {
        final ItemQuizEntryBinding b;

        QuizHolder(ItemQuizEntryBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind() {
            b.quizSubtitle.setText(quizSubtitle);
            b.quizPlay.setOnClickListener(v -> listener.onPlayQuiz());
        }
    }

    class PermissionHolder extends RecyclerView.ViewHolder {
        final ItemPermissionBinding b;

        PermissionHolder(ItemPermissionBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind() {
            b.permissionTitle.setText(permissionDenied ? R.string.permission_denied_title : R.string.permission_title);
            b.permissionBody.setText(permissionDenied ? R.string.permission_denied_body : R.string.permission_body);
            b.permissionAllow.setText(permissionDenied ? R.string.permission_open_settings : R.string.location_use_device);
            b.permissionAllow.setOnClickListener(v -> listener.onAllowLocation());
            b.permissionManual.setOnClickListener(v -> listener.onChooseArea());
            b.permissionDismiss.setOnClickListener(v -> listener.onDismissPermission());
        }
    }

    class EmptyHolder extends RecyclerView.ViewHolder {
        final ItemEmptyBinding b;

        EmptyHolder(ItemEmptyBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind() {
            if (emptyText != null) b.emptyText.setText(emptyText);
            b.emptyAction.setOnClickListener(v -> listener.onClearFilters());
        }
    }

    class LoadMoreHolder extends RecyclerView.ViewHolder {
        final ItemLoadMoreBinding b;

        LoadMoreHolder(ItemLoadMoreBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind(boolean loading) {
            b.loadMore.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
            b.loadMoreProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
            b.loadMore.setOnClickListener(v -> listener.onLoadMore());
        }
    }

    static class SimpleHolder extends RecyclerView.ViewHolder {
        SimpleHolder(View v) {
            super(v);
        }
    }
}
