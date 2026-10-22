package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.api.WordState;

/**
 * Creates a fresh {@link Solver} for each word.
 *
 * <p>A solver holds per-word state, so it must not be reused across words — the legacy
 * implementation's static fields were exactly this mistake, and clearing them correctly on
 * every transition was the source of several of its bugs. Making "one word, one solver"
 * structural removes that whole class of defect.
 */
@FunctionalInterface
public interface SolverFactory {

    /**
     * @param firstState          the freshly served word, fully masked
     * @param wrongGuessesAllowed misses permitted on this word
     */
    Solver create(WordState firstState, int wrongGuessesAllowed);
}
