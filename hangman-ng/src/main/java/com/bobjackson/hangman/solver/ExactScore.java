package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.dict.Dictionary;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The exact expected score of every letter still available in one decision.
 *
 * <p>{@link FrequencyLetterSelector} maximises P(hit) on this turn. That is not the same as
 * maximising the score at the end of the word: a miss spends one of the misses, and a hit
 * reveals positions that make the remaining letters easier. This class prices the whole rest
 * of the word out, with no heuristic at the leaf, so its answer is the true expectation rather
 * than an estimate of it.
 *
 * <h2>The pattern is implicit</h2>
 * The state here is (surviving candidates, guessed mask, misses left). There is no pattern
 * field, and none is needed: position {@code i} is revealed exactly when every candidate
 * carries the same letter there <em>and</em> that letter has been guessed, because a guessed
 * letter cannot hide at a masked position. That is {@link EliminationSolver}'s consistency
 * rule read backwards, which is why the two definitions cannot drift apart.
 *
 * <h2>Why it only runs on small states</h2>
 * A miss does not shrink the candidate set, it only spends a letter, so the reachable states
 * grow roughly like the ways to choose {@code k} of the letters a word does not use. For a
 * short word that is {@code C(15, k)} summed to {@code k = 10}, about 2^15 states, each costing
 * 26 letter evaluations: hundreds of millions of operations for one decision, which would cost
 * more than the game it is measuring. {@link #measure} therefore returns empty for states above
 * the caller's thresholds and for anything that runs past its node budget. Nothing is
 * approximated in that case, the decision is simply not priced.
 */
final class ExactScore {

    /** What a solved word is worth. Misses are charged to the score line, not to this value. */
    private static final double HIT = 20.0;

    private final Dictionary dictionary;
    private final int budget;
    private final Map<State, Double> memo = new HashMap<>();
    private final double[] byLetter = new double[26];
    private int nodes;

    private ExactScore(Dictionary dictionary, int budget) {
        this.dictionary = dictionary;
        this.budget = budget;
    }

    /**
     * Prices one decision, or returns empty when it is too large to price exactly.
     *
     * @param candidates       indices into {@code dictionary}, all the same length
     * @param guessedMask      letters already asked about
     * @param wrongGuessesLeft misses still available on this word; at least 1
     * @param nodeBudget       maximum number of distinct states to expand
     */
    static Optional<ExactScore> measure(Dictionary dictionary, int[] candidates, int guessedMask,
                                        int wrongGuessesLeft, int nodeBudget) {
        Objects.requireNonNull(dictionary, "dictionary");
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.length == 0 || wrongGuessesLeft <= 0 || nodeBudget <= 0
                || guessedMask == LetterFrequency.ALL_LETTERS) {
            return Optional.empty();
        }
        ExactScore score = new ExactScore(dictionary, nodeBudget);
        try {
            score.priceEveryLetter(candidates, guessedMask, wrongGuessesLeft);
            return Optional.of(score);
        } catch (BudgetExceeded e) {
            return Optional.empty();
        }
    }

    /** Expected points from this turn to the end of the word if {@code letter} is asked now. */
    double valueOf(char letter) {
        int index = letter - 'A';
        if (index < 0 || index >= 26 || Double.isNaN(byLetter[index])) {
            throw new IllegalArgumentException("letter was not priced: '" + letter + '\'');
        }
        return byLetter[index];
    }

    /** The highest expected value any available letter can reach. */
    double bestValue() {
        double best = Double.NEGATIVE_INFINITY;
        for (double value : byLetter) {
            if (!Double.isNaN(value) && value > best) {
                best = value;
            }
        }
        if (best == Double.NEGATIVE_INFINITY) {
            throw new IllegalStateException("no letter was priced");
        }
        return best;
    }

    /** The first letter, alphabetically, that reaches {@link #bestValue()}. */
    char bestLetter() {
        double best = bestValue();
        for (int c = 0; c < 26; c++) {
            if (byLetter[c] == best) {
                return (char) ('A' + c);
            }
        }
        throw new AssertionError("bestValue is not reachable");
    }

    /** How many distinct states were expanded; the unit the budget is spent in. */
    int nodes() {
        return nodes;
    }

    private void priceEveryLetter(int[] candidates, int guessedMask, int wrongGuessesLeft) {
        double total = weightOf(candidates);
        for (int c = 0; c < 26; c++) {
            if ((guessedMask & (1 << c)) != 0) {
                // Already asked; not an option. NaN marks "not priced" without a second array.
                byLetter[c] = Double.NaN;
            } else {
                byLetter[c] = price(candidates, guessedMask, wrongGuessesLeft, c, total);
            }
        }
    }

    private double price(int[] set, int mask, int missesLeft, int letter, double total) {
        int bit = 1 << letter;
        int[] hits = filter(set, bit);
        if (hits.length == 0) {
            // It cannot hit, so asking it only spends a miss: no twenty on this branch.
            return value(set, mask | bit, missesLeft - 1);
        }
        double p = weightOf(hits) / total;
        return p * value(hits, mask | bit, missesLeft)
                + (1.0 - p) * value(set, mask | bit, missesLeft - 1);
    }

    /** Expectation from a state to the end of the word, the twenty included once it is solved. */
    private double value(int[] set, int mask, int missesLeft) {
        if (set.length == 0 || missesLeft <= 0) {
            // Nothing left to win: the answer is not in the dictionary, or the word is lost.
            return 0.0;
        }
        if (isSolved(dictionary, set, mask)) {
            // Counted here, once, and only here. Adding it per hit would pay a twenty for
            // every revealed position instead of for the word.
            return HIT;
        }
        State key = new State(set, mask, missesLeft);
        Double cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        if (++nodes > budget) {
            throw BudgetExceeded.INSTANCE;
        }

        double total = weightOf(set);
        double best = 0.0;
        for (int c = 0; c < 26; c++) {
            int bit = 1 << c;
            if ((mask & bit) != 0) {
                continue;
            }
            double candidate = price(set, mask, missesLeft, c, total);
            if (candidate > best) {
                best = candidate;
            }
        }
        memo.put(key, best);
        return best;
    }

    /**
     * True when every position carries one letter across the candidates and that letter is
     * known, which is exactly the pattern being fully revealed.
     *
     * <p>Package-private so a test can pin the rule down without going through the DP.
     */
    static boolean isSolved(Dictionary dictionary, int[] set, int mask) {
        if (set.length == 0) {
            return false;
        }
        int length = dictionary.wordAt(set[0]).length();
        for (int i = 0; i < length; i++) {
            char letter = dictionary.wordAt(set[0]).charAt(i);
            for (int j = 1; j < set.length; j++) {
                if (dictionary.wordAt(set[j]).charAt(i) != letter) {
                    return false;
                }
            }
            if ((mask & (1 << (letter - 'A'))) == 0) {
                return false;
            }
        }
        return true;
    }

    private int[] filter(int[] set, int letterBit) {
        int[] hits = new int[set.length];
        int n = 0;
        for (int idx : set) {
            if ((dictionary.maskAt(idx) & letterBit) != 0) {
                hits[n++] = idx;
            }
        }
        // Returning the input unchanged keeps the memo keys equal for a letter that eliminates
        // nothing, which is the common case near the end of a word.
        return n == set.length ? set : Arrays.copyOf(hits, n);
    }

    private double weightOf(int[] set) {
        long sum = 0;
        for (int idx : set) {
            sum += dictionary.weightAt(idx);
        }
        return sum;
    }

    /** One reachable situation: what is still possible, what has been asked, what is left. */
    private static final class State {

        private final int[] candidates;
        private final int mask;
        private final int missesLeft;
        private final int hash;

        State(int[] candidates, int mask, int missesLeft) {
            this.candidates = candidates;
            this.mask = mask;
            this.missesLeft = missesLeft;
            this.hash = Objects.hash(Arrays.hashCode(candidates), mask, missesLeft);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof State state
                    && state.mask == mask
                    && state.missesLeft == missesLeft
                    && Arrays.equals(state.candidates, candidates);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** Unwinds the whole recursion at once when the budget is gone. Carries no stack trace. */
    private static final class BudgetExceeded extends RuntimeException {

        static final BudgetExceeded INSTANCE = new BudgetExceeded();

        private BudgetExceeded() {
            super(null, null, false, false);
        }
    }
}
