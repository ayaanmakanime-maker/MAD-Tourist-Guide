package com.example.touristguide;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.touristguide.databinding.ItemChatPlaceBinding;
import com.example.touristguide.databinding.ItemMsgBotBinding;
import com.example.touristguide.databinding.ItemMsgUserBinding;

import java.util.ArrayList;
import java.util.List;

/** Chat bubbles. Assistant messages can carry recommended places with "Add to plan" buttons. */
public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    static final int USER = 0;
    static final int BOT_OFFLINE = 1;
    static final int BOT_AI = 2;
    static final int PENDING = 3;

    public static class Msg {
        final int kind;
        final String text;
        /** Extra note under the bubble (error reason for offline answers). */
        final String detail;
        final List<Place> places;

        public Msg(int kind, String text) {
            this(kind, text, null, new ArrayList<>());
        }

        public Msg(int kind, String text, String detail, List<Place> places) {
            this.kind = kind;
            this.text = text;
            this.detail = detail;
            this.places = places == null ? new ArrayList<>() : places;
        }
    }

    public interface Callbacks {
        void onRetry();

        void onOpenPlace(Place place);

        void onAddPlace(Place place);
    }

    private final List<Msg> messages;
    private final Callbacks callbacks;

    public ChatAdapter(List<Msg> messages, Callbacks callbacks) {
        this.messages = messages;
        this.callbacks = callbacks;
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).kind == USER ? USER : BOT_AI;
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == USER) return new UserHolder(ItemMsgUserBinding.inflate(inflater, parent, false));
        return new BotHolder(ItemMsgBotBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Msg msg = messages.get(position);
        if (holder instanceof UserHolder) {
            ((UserHolder) holder).b.text.setText(msg.text);
        } else {
            ((BotHolder) holder).bind(msg, position == messages.size() - 1);
        }
    }

    static class UserHolder extends RecyclerView.ViewHolder {
        final ItemMsgUserBinding b;

        UserHolder(ItemMsgUserBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }

    class BotHolder extends RecyclerView.ViewHolder {
        final ItemMsgBotBinding b;

        BotHolder(ItemMsgBotBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind(Msg msg, boolean isLast) {
            Context c = b.getRoot().getContext();
            boolean offline = msg.kind == BOT_OFFLINE;
            b.text.setText(msg.text);
            b.label.setVisibility(offline || msg.kind == PENDING ? View.VISIBLE : View.GONE);
            b.label.setText(offline ? R.string.chat_offline_label : R.string.chat_pending_label);
            b.offlineNote.setVisibility(offline && msg.detail != null ? View.VISIBLE : View.GONE);
            b.offlineNote.setText(msg.detail);
            // Only the latest offline answer offers a retry.
            b.retry.setVisibility(offline && isLast ? View.VISIBLE : View.GONE);
            b.retry.setOnClickListener(v -> callbacks.onRetry());

            b.places.removeAllViews();
            b.places.setVisibility(msg.places.isEmpty() ? View.GONE : View.VISIBLE);
            LocationRepository.Selected loc = LocationRepository.selected();
            for (Place p : msg.places) {
                ItemChatPlaceBinding row = ItemChatPlaceBinding.inflate(LayoutInflater.from(c), b.places, false);
                row.placeName.setText(p.name);
                String facts = Formats.facts(c, p, loc.lat, loc.lng);
                row.placeMeta.setText(facts.isEmpty() ? Formats.source(c, p, PlaceRepository.Status.LIVE)
                        : facts + " • " + Formats.source(c, p, PlaceRepository.Status.LIVE));
                boolean inPlan = PlanRepository.contains(p.id);
                row.placeAdd.setText(inPlan ? R.string.detail_in_itinerary : R.string.action_add_plan);
                row.placeAdd.setIconResource(inPlan ? R.drawable.ic_check : R.drawable.ic_add);
                row.placeAdd.setContentDescription(c.getString(inPlan ? R.string.in_plan_named : R.string.add_plan_named, p.name));
                row.placeAdd.setOnClickListener(v -> callbacks.onAddPlace(p));
                row.getRoot().setOnClickListener(v -> callbacks.onOpenPlace(p));
                b.places.addView(row.getRoot());
            }
        }
    }
}
