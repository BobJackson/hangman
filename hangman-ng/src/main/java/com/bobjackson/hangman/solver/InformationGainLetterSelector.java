package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.dict.Dictionary;

/**
 * Picks the letter that splits the surviving candidates most evenly, ignoring what a miss costs.
 *
 * <p>This is the deliberate opposite of {@link FrequencyLetterSelector}: that one maximises the
 * chance of a hit, which also minimises the chance of paying a point this turn. This one
 * maximises expected information — the letters that halve the candidate set — and that means
 * preferring a coin flip over a near-certain hit.
 *
 * <p>It exists to answer a question the score formula makes unavoidable. A solved word is worth
 * twenty points and a miss costs one, so on a tier where a hundred words are played per session,
 * one percentage point of solve rate is worth about 0.2 points per word, while cutting a
 * hundredth of a miss per word is worth 0.01. The greedy rule optimises the cheap quantity. If
 * this selector finishes more words, the greedy rule was buying accuracy it did not need; if it
 * finishes fewer, the greedy rule was right and the extra misses bought nothing.
 *
 * <p>Score for a letter is the expected drop in {@code log2(candidate count)}:
 * {@code p * log2(n / hits) + (1 - p) * log2(n / (n - hits))}. It is zero for a letter no
 * candidate contains and zero for one every candidate contains, because neither separates
 * anything — the first is a guaranteed miss and the second reveals a letter already implied by
 * the mask.
 */
public final class InformationGainLetterSelector implements LetterSelector {

    @Override
    public char select(GuessContext context) {
        if (!context.hasCandidates()) {
            return LetterFrequency.firstUnguessed(context.guessedMask());
        }

        long[] weightContaining = new long[26];
        long total = 0;
        Dictionary dictionary = context.dictionary();
        for (int index : context.candidates()) {
            long weight = dictionary.weightAt(index);
            total += weight;
            int letters = dictionary.maskAt(index) & ~context.guessedMask();
            while (letters != 0) {
                int bit = Integer.lowestOneBit(letters);
                weightContaining[Integer.numberOfTrailingZeros(bit)] += weight;
                letters ^= bit;
            }
        }

        char best = '\0';
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestRank = Integer.MAX_VALUE;

        for (char candidate = 'A'; candidate <= 'Z'; candidate++) {
            if (context.isGuessed(candidate)) {
                continue;
            }
            double score = expectedGain(weightContaining[candidate - 'A'], total);
            int rank = LetterFrequency.rank(candidate);
            if (score > bestScore || (score == bestScore && rank < bestRank)) {
                best = candidate;
                bestScore = score;
                bestRank = rank;
            }
        }

        if (best == '\0') {
            throw new IllegalStateException("no unguessed letter remains");
        }
        return best;
    }

    /** Expected reduction in {@code log2(candidates)} from asking this letter. */
    static double expectedGain(long hits, long total) {
        if (total <= 0 || hits <= 0 || hits >= total) {
            return 0.0;
        }
        double n = total;
        double hitRate = hits / n;
        return hitRate * log2(n / hits) + (1.0 - hitRate) * log2(n / (n - hits));
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    @Override
    public String toString() {
        return "information-gain";
    }
}
