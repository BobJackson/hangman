package com.bobjackson.hangman.dict;

/**
 * Assigns a prior weight to a dictionary word: how likely it is to be the answer.
 *
 * <p>Separated from {@link Dictionary} so the weighting model can be swapped without
 * touching the data structure. Milestone M1 evaluates the solver under
 * {@link #uniform()}; M2 switches in {@link RankWordWeighting} and the harness measures
 * what the change is worth.
 *
 * <p>Weights are positive integers so that sampling and expectation maths stay in exact
 * arithmetic. Callers must accumulate them into {@code long}.
 */
public interface WordWeighting {

    /**
     * The weight of {@code word}. Must be positive.
     *
     * <p>Implementations decide what an unrecognised word scores; a small positive floor
     * keeps it a candidate without letting it dominate.
     */
    int weightOf(String word);

    /** Every word weighs the same. The baseline, and the legacy implementation's implicit model. */
    static WordWeighting uniform() {
        return Uniform.INSTANCE;
    }

    /** Singleton uniform weighting. */
    enum Uniform implements WordWeighting {
        INSTANCE;

        @Override
        public int weightOf(String word) {
            return 1;
        }

        @Override
        public String toString() {
            return "uniform";
        }
    }
}
