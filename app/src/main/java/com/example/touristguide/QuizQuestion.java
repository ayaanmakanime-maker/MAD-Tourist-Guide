package com.example.touristguide;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** One multiple-choice question. Option 0 is always the correct answer in the bank; sessions shuffle. */
public final class QuizQuestion {

    public enum Category {
        LANDMARKS(R.string.quiz_cat_landmarks),
        HISTORY(R.string.quiz_cat_history),
        CULTURE(R.string.quiz_cat_culture),
        GEOGRAPHY(R.string.quiz_cat_geography),
        FOOD(R.string.quiz_cat_food);

        public final int label;

        Category(int label) {
            this.label = label;
        }
    }

    public enum Difficulty {
        EASY(R.string.quiz_easy),
        MEDIUM(R.string.quiz_medium),
        HARD(R.string.quiz_hard);

        public final int label;

        Difficulty(int label) {
            this.label = label;
        }
    }

    public final String id;
    public final Category category;
    public final Difficulty difficulty;
    public final String question;
    /** The correct answer first, followed by three distractors. */
    public final List<String> options;
    public final String explanation;

    public QuizQuestion(String id, Category category, Difficulty difficulty, String question,
                        String correct, String wrong1, String wrong2, String wrong3, String explanation) {
        this.id = id;
        this.category = category;
        this.difficulty = difficulty;
        this.question = question;
        this.options = Collections.unmodifiableList(Arrays.asList(correct, wrong1, wrong2, wrong3));
        this.explanation = explanation;
    }

    public String correctAnswer() {
        return options.get(0);
    }
}
