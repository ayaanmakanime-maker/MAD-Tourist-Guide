package com.example.touristguide;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    static final int USER = 0;
    static final int BOT_OFFLINE = 1;
    static final int BOT_AI = 2;
    static final int PENDING = 3;

    public static class Msg {
        final int kind;
        final String text;
        final String detail;

        public Msg(int kind, String text) {
            this(kind, text, null);
        }

        public Msg(int kind, String text, String detail) {
            this.kind = kind;
            this.text = text;
            this.detail = detail;
        }
    }

    public interface RetryListener {
        void onRetry();
    }

    private final List<Msg> messages;
    private final RetryListener retryListener;

    public ChatAdapter(List<Msg> messages, RetryListener retryListener) {
        this.messages = messages;
        this.retryListener = retryListener;
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).kind;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == USER) {
            return new UserHolder(inflater.inflate(R.layout.item_msg_user, parent, false));
        }
        return new BotHolder(inflater.inflate(R.layout.item_msg_bot, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Msg msg = messages.get(position);
        if (holder instanceof UserHolder) {
            ((UserHolder) holder).text.setText(msg.text);
        } else {
            BotHolder bot = (BotHolder) holder;
            bot.text.setText(msg.text);
            boolean offline = msg.kind == BOT_OFFLINE;
            bot.offlineNote.setVisibility(offline ? View.VISIBLE : View.GONE);
            bot.retry.setVisibility(offline ? View.VISIBLE : View.GONE);
            if (offline) {
                bot.offlineNote.setText(msg.detail == null || msg.detail.trim().isEmpty()
                        ? bot.offlineNote.getContext().getString(R.string.chat_offline_note)
                        : msg.detail);
                bot.retry.setOnClickListener(v -> {
                    if (retryListener != null) retryListener.onRetry();
                });
            }
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class UserHolder extends RecyclerView.ViewHolder {
        final android.widget.TextView text;

        UserHolder(View v) {
            super(v);
            text = v.findViewById(R.id.text);
        }
    }

    static class BotHolder extends RecyclerView.ViewHolder {
        final android.widget.TextView text;
        final android.widget.TextView offlineNote;
        final com.google.android.material.button.MaterialButton retry;

        BotHolder(View v) {
            super(v);
            text = v.findViewById(R.id.text);
            offlineNote = v.findViewById(R.id.offlineNote);
            retry = v.findViewById(R.id.retry);
        }
    }
}
