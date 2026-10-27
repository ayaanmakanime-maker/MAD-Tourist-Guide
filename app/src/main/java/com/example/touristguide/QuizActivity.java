package com.example.touristguide;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.example.touristguide.databinding.ActivityQuizBinding;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import java.util.List;
import java.util.Random;

/** The Mumbai quiz: choose a topic and difficulty, answer with instant feedback, see results. */
public class QuizActivity extends AppCompatActivity {

    /** Keeps the game across rotation. */
    public static class State extends ViewModel {
        QuizQuestion.Category category; // null = all topics
        QuizQuestion.Difficulty difficulty = QuizQuestion.Difficulty.EASY;
        QuizSession session;
        boolean showingResult;
        boolean newBest;
    }

    private ActivityQuizBinding binding;
    private State state;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Store.init(this);
        binding = ActivityQuizBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        state = new ViewModelProvider(this).get(State.class);

        buildCategoryChips();
        binding.difficulty.check(difficultyButton(state.difficulty));
        binding.difficulty.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            state.difficulty = id == R.id.hard ? QuizQuestion.Difficulty.HARD
                    : id == R.id.medium ? QuizQuestion.Difficulty.MEDIUM : QuizQuestion.Difficulty.EASY;
            showBest();
        });
        binding.start.setOnClickListener(v -> start());
        binding.close.setOnClickListener(v -> finish());
        binding.next.setOnClickListener(v -> next());
        binding.quit.setOnClickListener(v -> {
            state.session = null;
            render();
        });
        binding.retry.setOnClickListener(v -> start());
        binding.restart.setOnClickListener(v -> {
            state.session = null;
            state.showingResult = false;
            render();
        });
        binding.done.setOnClickListener(v -> finish());
        render();
    }

    private void buildCategoryChips() {
        binding.categories.removeAllViews();
        addChip(getString(R.string.quiz_all_topics), null);
        for (QuizQuestion.Category c : QuizQuestion.Category.values()) addChip(getString(c.label), c);
    }

    private void addChip(String label, QuizQuestion.Category category) {
        Chip chip = new Chip(this);
        chip.setText(label);
        chip.setCheckable(true);
        chip.setId(View.generateViewId());
        chip.setChecked(state.category == category);
        chip.setOnClickListener(v -> {
            state.category = category;
            showBest();
        });
        binding.categories.addView(chip);
    }

    private int difficultyButton(QuizQuestion.Difficulty d) {
        return d == QuizQuestion.Difficulty.HARD ? R.id.hard : d == QuizQuestion.Difficulty.MEDIUM ? R.id.medium : R.id.easy;
    }

    private String modeKey() {
        return (state.category == null ? "ALL" : state.category.name()) + "|" + state.difficulty.name();
    }

    private void showBest() {
        int[] best = Store.quizBest(modeKey());
        binding.setupBest.setText(best == null ? getString(R.string.quiz_no_best)
                : getString(R.string.quiz_best_fmt, best[0], best[1]));
    }

    private void start() {
        List<QuizQuestion> questions = QuizBank.select(state.category, state.difficulty, QuizBank.SESSION_LENGTH, new Random());
        if (questions.isEmpty()) return;
        state.session = new QuizSession(questions, new Random());
        state.showingResult = false;
        render();
    }

    private void render() {
        boolean playing = state.session != null && !state.showingResult;
        binding.setup.setVisibility(state.session == null ? View.VISIBLE : View.GONE);
        binding.question.setVisibility(playing ? View.VISIBLE : View.GONE);
        binding.result.setVisibility(state.showingResult ? View.VISIBLE : View.GONE);
        if (state.session == null) showBest();
        else if (playing) renderQuestion();
        else renderResult();
    }

    private void renderQuestion() {
        QuizSession s = state.session;
        QuizSession.Round round = s.current();
        binding.progressText.setText(getString(R.string.quiz_progress_fmt, s.index() + 1, s.total()));
        binding.scoreText.setText(getString(R.string.quiz_score_fmt, s.score()));
        binding.progressBar.setMax(s.total());
        binding.progressBar.setProgress(s.index() + (s.isAnswered() ? 1 : 0));
        binding.questionMeta.setText(getString(R.string.quiz_meta_fmt, getString(round.question.category.label),
                getString(round.question.difficulty.label)));
        binding.questionText.setText(round.question.question);

        binding.options.removeAllViews();
        for (int i = 0; i < round.options.size(); i++) {
            final int index = i;
            MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setAllCaps(false);
            b.setMinHeight(dp(52));
            b.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            b.setText(round.options.get(i));
            b.setOnClickListener(v -> answer(index));
            if (s.isAnswered()) {
                b.setEnabled(false);
                // Colour plus a ✓ / ✗ prefix, so feedback doesn't rely on colour alone.
                if (i == round.correctIndex) {
                    b.setText(getString(R.string.quiz_option_correct_fmt, round.options.get(i)));
                    tint(b, R.color.success);
                } else if (i == s.chosen()) {
                    b.setText(getString(R.string.quiz_option_wrong_fmt, round.options.get(i)));
                    tint(b, R.color.error);
                }
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            binding.options.addView(b, lp);
        }

        boolean answered = s.isAnswered();
        binding.feedback.setVisibility(answered ? View.VISIBLE : View.GONE);
        binding.next.setVisibility(answered ? View.VISIBLE : View.GONE);
        if (answered) {
            boolean correct = s.chosen() == round.correctIndex;
            binding.feedbackTitle.setText(correct ? R.string.quiz_correct : R.string.quiz_incorrect);
            binding.feedbackTitle.setTextColor(getColor(correct ? R.color.success : R.color.error));
            binding.feedbackBody.setText(correct ? round.question.explanation
                    : getString(R.string.quiz_answer_was_fmt, round.question.correctAnswer(), round.question.explanation));
            binding.next.setText(s.index() == s.total() - 1 ? R.string.quiz_see_results : R.string.quiz_next);
        }
    }

    private void tint(MaterialButton b, int color) {
        b.setStrokeColor(ColorStateList.valueOf(getColor(color)));
        b.setStrokeWidth(dp(2));
        b.setTextColor(getColor(color));
    }

    private void answer(int option) {
        if (state.session == null || state.session.isAnswered()) return;
        state.session.answer(option);
        renderQuestion();
        binding.feedback.announceForAccessibility(binding.feedbackTitle.getText());
    }

    private void next() {
        QuizSession s = state.session;
        s.next();
        if (s.isFinished()) {
            state.newBest = Store.recordQuiz(modeKey(), s.score(), s.total());
            state.showingResult = true;
        }
        render();
        binding.root.scrollTo(0, 0);
    }

    private void renderResult() {
        QuizSession s = state.session;
        int pct = s.percent();
        binding.resultEmoji.setText(pct >= 80 ? "🏆" : pct >= 50 ? "👏" : "📚");
        binding.resultScore.setText(getString(R.string.quiz_result_fmt, s.score(), s.total()));
        binding.resultMessage.setText(pct >= 80 ? R.string.quiz_result_great
                : pct >= 50 ? R.string.quiz_result_good : R.string.quiz_result_keep_going);
        int[] best = Store.quizBest(modeKey());
        binding.resultBest.setText(state.newBest ? getString(R.string.quiz_new_best)
                : best == null ? "" : getString(R.string.quiz_best_fmt, best[0], best[1]));
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }
}
