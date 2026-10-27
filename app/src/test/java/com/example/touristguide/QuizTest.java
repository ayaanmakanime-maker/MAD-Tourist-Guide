package com.example.touristguide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class QuizTest {

    @Test
    public void bankIsWellFormed() {
        Set<String> ids = new HashSet<>();
        Set<String> questions = new HashSet<>();
        for (QuizQuestion q : QuizBank.QUESTIONS) {
            assertTrue("duplicate id " + q.id, ids.add(q.id));
            assertTrue("duplicate question " + q.question, questions.add(q.question));
            assertEquals(4, q.options.size());
            assertEquals("options must be unique: " + q.id, 4, new HashSet<>(q.options).size());
            for (String o : q.options) assertFalse(o.trim().isEmpty());
            assertFalse("explanation missing: " + q.id, q.explanation.trim().isEmpty());
        }
    }

    @Test
    public void everyCategoryHasQuestionsAtEveryDifficulty() {
        for (QuizQuestion.Category c : QuizQuestion.Category.values()) {
            for (QuizQuestion.Difficulty d : QuizQuestion.Difficulty.values()) {
                int n = QuizBank.select(c, d, 100, new Random(1)).size();
                assertTrue(c + "/" + d + " has " + n, n >= 4);
            }
        }
    }

    @Test
    public void originalQuestionsAreRetained() {
        boolean csmt = false, necklace = false, gateway = false;
        for (QuizQuestion q : QuizBank.QUESTIONS) {
            csmt |= q.question.contains("Victorian Gothic");
            necklace |= q.question.contains("night-time nickname");
            gateway |= q.question.contains("arch faces the Arabian Sea");
        }
        assertTrue(csmt && necklace && gateway);
    }

    @Test
    public void selectRespectsFiltersAndLimit() {
        List<QuizQuestion> mixed = QuizBank.select(null, QuizQuestion.Difficulty.EASY, QuizBank.SESSION_LENGTH, new Random(7));
        assertEquals(QuizBank.SESSION_LENGTH, mixed.size());
        for (QuizQuestion q : mixed) assertEquals(QuizQuestion.Difficulty.EASY, q.difficulty);
        List<QuizQuestion> food = QuizBank.select(QuizQuestion.Category.FOOD, QuizQuestion.Difficulty.HARD, 10, new Random(7));
        for (QuizQuestion q : food) {
            assertEquals(QuizQuestion.Category.FOOD, q.category);
            assertEquals(QuizQuestion.Difficulty.HARD, q.difficulty);
        }
    }

    @Test
    public void shuffledOptionsKeepTheCorrectAnswer() {
        for (int seed = 0; seed < 20; seed++) {
            QuizSession s = new QuizSession(QuizBank.QUESTIONS, new Random(seed));
            QuizSession.Round r = s.current();
            assertEquals(r.question.correctAnswer(), r.options.get(r.correctIndex));
        }
    }

    @Test
    public void scoringAndProgress() {
        List<QuizQuestion> qs = QuizBank.select(QuizQuestion.Category.LANDMARKS, QuizQuestion.Difficulty.EASY, 4, new Random(3));
        QuizSession s = new QuizSession(qs, new Random(3));
        assertEquals(4, s.total());
        // Correct, wrong, correct, wrong.
        for (int i = 0; i < 4; i++) {
            assertEquals(i, s.index());
            assertFalse(s.isAnswered());
            int correct = s.current().correctIndex;
            int choice = i % 2 == 0 ? correct : (correct + 1) % 4;
            assertEquals(i % 2 == 0, s.answer(choice));
            assertTrue(s.isAnswered());
            s.next();
        }
        assertTrue(s.isFinished());
        assertEquals(2, s.score());
        assertEquals(50, s.percent());
    }

    @Test
    public void answeringTwiceDoesNotScoreTwice() {
        QuizSession s = new QuizSession(QuizBank.QUESTIONS.subList(0, 2), new Random(1));
        int correct = s.current().correctIndex;
        assertTrue(s.answer(correct));
        assertTrue(s.answer(correct));
        // A later tap on a wrong option neither changes the recorded answer nor the score.
        assertTrue(s.answer((correct + 1) % 4));
        assertEquals(1, s.score());
        assertEquals(correct, s.chosen());
    }

    @Test
    public void cannotSkipWithoutAnswering() {
        QuizSession s = new QuizSession(QuizBank.QUESTIONS.subList(0, 2), new Random(1));
        try {
            s.next();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void differentSeedsGiveDifferentOrders() {
        List<QuizQuestion> a = QuizBank.select(null, QuizQuestion.Difficulty.MEDIUM, 10, new Random(1));
        List<QuizQuestion> b = QuizBank.select(null, QuizQuestion.Difficulty.MEDIUM, 10, new Random(2));
        assertNotEquals(a, b);
    }
}
