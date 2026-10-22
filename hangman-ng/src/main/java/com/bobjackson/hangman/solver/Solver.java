package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.api.WordState;

/**
 * Decides which letter to guess next, one word at a time.
 *
 * <p>Knows nothing about HTTP, scoring or game flow: it sees the current mask and the
 * server's replies, and returns letters. That isolation is what makes the strategy
 * unit-testable and swappable — M2 adds frequency weighting, M3 adds an exact
 * expected-miss minimiser over small candidate sets, and neither change touches the
 * engine or the transport.
 */
public interface Solver {

    /**
     * The next letter to guess, always uppercase {@code A-Z} and never a repeat.
     *
     * @throws IllegalStateException if no legal guess remains
     */
    char nextGuess();

    /**
     * Records the server's reply to the letter we just sent.
     *
     * @param guess the letter passed to the server
     * @param state the server's response, whose mask may have gained revealed letters
     */
    void observe(char guess, WordState state);

    /**
     * How many dictionary words remain consistent with everything observed so far.
     *
     * <p>Zero does not mean the game is lost — it means the answer is not in our
     * dictionary, and the solver switches to pure letter-frequency fallback. Exposing the
     * count is what lets the harness report how often that happens, which is the real
     * measure of dictionary quality.
     */
    int candidateCount();

    /**
     * True when the solver would rather give the word up than spend another miss on it.
     *
     * <p>Abandoning is only free because the score charges misses already spent, not misses
     * that were never spent: {@code 20 * correct - wrong} does not care how a word ended, only
     * that it did not end with a reveal. Whether the server permits it is a separate question —
     * see the skip measurement in the README, which reports both answers.
     *
     * <p>Asked once per turn, before {@link #nextGuess()}. The default is never to abandon.
     */
    default boolean shouldAbandon() {
        return false;
    }
}
