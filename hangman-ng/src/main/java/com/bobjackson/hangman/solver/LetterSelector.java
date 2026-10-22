package com.bobjackson.hangman.solver;

/**
 * Chooses a letter given the current evidence.
 *
 * <p>Split from {@link Solver} because "which words are still possible" and "which letter
 * is worth asking about" are genuinely separate concerns. The elimination logic is fixed
 * and provably correct; the selection heuristic is where all the tuning lives.
 */
public interface LetterSelector {

    /**
     * The letter to guess next, uppercase {@code A-Z}, not already in
     * {@link GuessContext#guessedMask()}.
     */
    char select(GuessContext context);
}
