package com.example.touristguide;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class PlaceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onOpen(Place place);

        void onSavedChanged(Place place, boolean added);
    }

    public static final int TYPE_QUIZ = 0;
    public static final int TYPE_PLACE = 1;
    public static final int TYPE_TIP = 2;
    public static final int TYPE_EMPTY = 3;

    private final Activity activity;
    private final Listener listener;
    private final QuizCallbacks quiz;
    private List<Object> items;

    public interface QuizCallbacks {
        String question();

        boolean answerVisible();

        void toggleAnswer();

        void next();
    }

    public PlaceAdapter(Activity activity, List<Object> items, Listener listener, QuizCallbacks quiz) {
        this.activity = activity;
        this.items = items;
        this.listener = listener;
        this.quiz = quiz;
    }

    public void setItems(List<Object> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        return item instanceof Integer ? (Integer) item : TYPE_PLACE;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        boolean horizontal = parent instanceof RecyclerView
                && ((RecyclerView) parent).getLayoutManager() instanceof LinearLayoutManager
                && ((LinearLayoutManager) ((RecyclerView) parent).getLayoutManager()).getOrientation()
                        == LinearLayoutManager.HORIZONTAL;
        switch (viewType) {
            case TYPE_QUIZ:
                return new QuizHolder(inflater.inflate(R.layout.item_quiz, parent, false));
            case TYPE_TIP:
                return new SimpleHolder(inflater.inflate(R.layout.item_tip, parent, false));
            case TYPE_EMPTY:
                return new SimpleHolder(inflater.inflate(R.layout.item_empty, parent, false));
            default: {
                View card = inflater.inflate(R.layout.item_place, parent, false);
                if (horizontal) {
                    card.setLayoutParams(new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                return new PlaceHolder(card);
            }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof QuizHolder) {
            ((QuizHolder) holder).bind();
        } else if (holder instanceof PlaceHolder) {
            ((PlaceHolder) holder).bind((Place) items.get(position));
        }
    }

    class PlaceHolder extends RecyclerView.ViewHolder {
        final com.example.touristguide.databinding.ItemPlaceBinding b;

        PlaceHolder(android.view.View v) {
            super(v);
            b = com.example.touristguide.databinding.ItemPlaceBinding.bind(v);
        }

        void bind(Place p) {
            b.emoji.setText(p.emoji);
            b.name.setText(p.name);
            b.sub.setText(activity.getString(R.string.place_sub_fmt, p.category, p.area));
            b.description.setText(p.description);
            b.meta.setText(Formats.metaLabel(activity, p.minutes, p.budget));
            b.save.setImageResource(Store.isSaved(p.name) ? R.drawable.ic_heart : R.drawable.ic_heart_border);
            b.save.setOnClickListener(v -> {
                boolean added = Store.toggleSaved(p.name);
                b.save.setImageResource(added ? R.drawable.ic_heart : R.drawable.ic_heart_border);
                Toast.makeText(activity, activity.getString(
                        added ? R.string.saved_toast : R.string.unsaved_toast), Toast.LENGTH_SHORT).show();
                if (listener != null) listener.onSavedChanged(p, added);
            });
            b.details.setOnClickListener(v -> {
                if (listener != null) listener.onOpen(p);
            });
            b.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onOpen(p);
            });
        }
    }

    class QuizHolder extends RecyclerView.ViewHolder {
        final com.example.touristguide.databinding.ItemQuizBinding b;

        QuizHolder(android.view.View v) {
            super(v);
            b = com.example.touristguide.databinding.ItemQuizBinding.bind(v);
        }

        void bind() {
            b.quizTitle.setText(activity.getString(R.string.quiz_header_fmt,
                    activity.getString(R.string.quiz_title), Store.quizIndex() + 1));
            b.quizQuestion.setText(quiz.question());
            if (quiz.answerVisible()) {
                b.quizAnswer.setVisibility(android.view.View.VISIBLE);
                b.quizAnswer.setText(quizAnswerText());
                b.quizReveal.setText(R.string.quiz_hide);
            } else {
                b.quizAnswer.setVisibility(android.view.View.GONE);
                b.quizReveal.setText(R.string.quiz_reveal);
            }
            b.quizReveal.setOnClickListener(v -> {
                quiz.toggleAnswer();
                notifyItemChanged(getBindingAdapterPosition());
            });
            b.quizNext.setOnClickListener(v -> {
                quiz.next();
                notifyItemChanged(getBindingAdapterPosition());
            });
        }

        private String quizAnswerText() {
            switch (Store.quizIndex()) {
                case 0: return activity.getString(R.string.quiz_a1);
                case 1: return activity.getString(R.string.quiz_a2);
                default: return activity.getString(R.string.quiz_a3);
            }
        }
    }

    static class SimpleHolder extends RecyclerView.ViewHolder {
        SimpleHolder(android.view.View v) {
            super(v);
        }
    }
}
