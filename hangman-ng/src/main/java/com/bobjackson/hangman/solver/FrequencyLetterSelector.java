package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.dict.Dictionary;

/**
 * Picks the letter that appears most often among the surviving candidates.
 *
 * <p>This is the greedy step of the legacy algorithm, and it is a sound one: maximising
 * the chance of a hit is the same as minimising the chance of scoring a miss on this turn,
 * and only misses cost points. What the legacy version got wrong was the bookkeeping
 * around it — a static map that was not cleared when the candidate set emptied, so a word
 * could be attacked using the <em>previous</em> word's letter statistics.
 *
 * <p>Two counting modes are offered because they are not equivalent, and the difference is
 * measurable:
 * <ul>
 *   <li>{@link Mode#COVERAGE} scores a letter by the weight of candidates that contain it
 *       at all. This is a direct estimate of P(hit).</li>
 *   <li>{@link Mode#OCCURRENCES} scores by total letter occurrences, the legacy behaviour.
 *       It over-values letters that repeat inside a single word — guessing the second
 *       {@code S} in {@code MISSISSIPPI} buys no new information, because a hit reveals
 *       every occurrence at once.</li>
 * </ul>
 * Coverage is the default and the difference between the two is reported by the harness.
 *
 * <p>Ties break toward static English letter frequency, which also means an empty
 * candidate set degrades gracefully to pure frequency-based guessing instead of failing.
 */
public final class FrequencyLetterSelector implements LetterSelector {

    /** How a letter's score is accumulated across candidates. */
    public enum Mode {
        /** Weight of distinct candidate words containing the letter. Estimates P(hit). */
        COVERAGE,
        /** Weight times the number of occurrences of the letter. The legacy behaviour. */
        OCCURRENCES
    }

    private final Mode mode;

    public FrequencyLetterSelector() {
        this(Mode.COVERAGE);
    }

    public FrequencyLetterSelector(Mode mode) {
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
    }

    public Mode mode() {
        return mode;
    }

    @Override
    public char select(GuessContext context) {
        long[] score = new long[26];
        if (context.hasCandidates()) {
            accumulate(context, score);
        }

        char best = '\0';
        long bestScore = Long.MIN_VALUE;
        int bestRank = Integer.MAX_VALUE;

        for (char c = 'A'; c <= 'Z'; c++) {
            if (context.isGuessed(c)) {
                continue;
            }
            long s = score[c - 'A'];
            int rank = LetterFrequency.rank(c);
            if (s > bestScore || (s == bestScore && rank < bestRank)) {
                best = c;
                bestScore = s;
                bestRank = rank;
            }
        }

        if (best == '\0') {
            throw new IllegalStateException("no unguessed letter remains");
        }
        return best;
    }

    private void accumulate(GuessContext context, long[] score) {
        Dictionary dictionary = context.dictionary();
        for (int idx : context.candidates()) {
            long weight = dictionary.weightAt(idx);
            if (mode == Mode.COVERAGE) {
                // Walk only the set bits: a word contributes at most its distinct letters.
                int mask = dictionary.maskAt(idx);
                while (mask != 0) {
                    int bit = Integer.lowestOneBit(mask);
                    score[Integer.numberOfTrailingZeros(bit)] += weight;
                    mask ^= bit;
                }
            } else {
                String word = dictionary.wordAt(idx);
                for (int i = 0; i < word.length(); i++) {
                    score[word.charAt(i) - 'A'] += weight;
                }
            }
        }
    }

    @Override
    public String toString() {
        return "frequency[" + mode + ']';
    }
}
