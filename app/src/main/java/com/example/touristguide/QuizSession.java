package com.example.touristguide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** One play-through of the quiz: shuffled options, scoring and progress. Pure Java (unit tested). */
public final class QuizSession {

    /** A question as shown to the player, with its options in shuffled order. */
    public static final class Round {
        public final QuizQuestion question;
        public final List<String> options;
        public final int correctIndex;

        Round(QuizQuestion question, Random random) {
            this.question = question;
            List<String> shuffled = new ArrayList<>(question.options);
            Collections.shuffle(shuffled, random);
            this.options = Collections.unmodifiableList(shuffled);
            this.correctIndex = shuffled.indexOf(question.correctAnswer());
        }
    }

    private final List<Round> rounds = new ArrayList<>();
    private int index;
    private int score;
    /** Chosen option for the current round, or -1 while unanswered. */
    private int chosen = -1;

    public QuizSession(List<QuizQuestion> questions, Random random) {
        if (questions == null || questions.isEmpty()) throw new IllegalArgumentException("No questions");
        for (QuizQuestion q : questions) rounds.add(new Round(q, random));
    }

    public Round current() {
        return rounds.get(Math.min(index, rounds.size() - 1));
    }

    /** Zero-based index of the current question. */
    public int index() { return index; }
    public int total() { return rounds.size(); }
    public int score() { return score; }
    public boolean isAnswered() { return chosen >= 0; }
    public int chosen() { return chosen; }
    public boolean isFinished() { return index >= rounds.size(); }

    /**
     * Records an answer for the current question. Repeated taps are ignored, so a question can
     * only score once. Returns true when the answer was correct.
     */
    public boolean answer(int option) {
        if (isFinished()) throw new IllegalStateException("Quiz finished");
        Round round = current();
        if (option < 0 || option >= round.options.size()) throw new IllegalArgumentException("option");
        if (isAnswered()) return chosen == round.correctIndex;
        chosen = option;
        boolean correct = option == round.correctIndex;
        if (correct) score++;
        return correct;
    }

    /** Moves to the next question. Must answer first. */
    public void next() {
        if (!isAnswered()) throw new IllegalStateException("Answer first");
        index++;
        chosen = -1;
    }

    /** Percentage score, rounded down. */
    public int percent() {
        return rounds.isEmpty() ? 0 : score * 100 / rounds.size();
    }
}
