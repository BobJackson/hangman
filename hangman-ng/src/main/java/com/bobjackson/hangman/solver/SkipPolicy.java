package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.dict.Dictionary;

/**
 * Decides when a word is not worth any more misses.
 *
 * <p>This is the one decision in the game that is not about which letter to pick. Score is
 * {@code 20 * correct - wrong}, so a word that will never be revealed costs exactly what it
 * spends before it is given up — and nothing for the misses never spent. Abandoning therefore
 * converts a certain loss of {@code m} points into a certain loss of zero, at the price of the
 * twenty points that continuing might still have won.
 *
 * <p>Two policies are offered because they answer different questions:
 * <ul>
 *   <li>{@link #hopeless()} abandons only when nothing in the dictionary explains the
 *       evidence. It cannot cost a point, because there is no candidate left to lose.</li>
 *   <li>{@link #expectedValue()} also abandons words that are merely unlikely, using
 *       {@code 20 * P(solve) < (1 - P) * missesLeft}. It can be wrong, and being wrong costs
 *       the full twenty points.</li>
 * </ul>
 * The gap between them is what the README reports: how much of the skip idea is arithmetic
 * and how much is a bet.
 */
@FunctionalInterface
public interface SkipPolicy {

    /** Never give up. */
    SkipPolicy NEVER = context -> false;

    boolean shouldSkip(SkipContext context);

    /** Gives up only on words that cannot be finished. */
    static SkipPolicy hopeless() {
        return Hopeless.INSTANCE;
    }

    /** Gives up whenever continuing is worth less than the misses it is expected to burn. */
    static SkipPolicy expectedValue() {
        return ExpectedValue.INSTANCE;
    }

    /**
     * Gives up only when nothing in the dictionary can explain the evidence.
     *
     * <p>The obvious zero-risk rule — give up when more distinct letters are hidden than
     * misses remain — is wrong, and wrong in the direction that costs points. A hit does not
     * consume a miss, and a word allows unlimited guesses, so a thirteen-letter answer with
     * eleven distinct letters is revealed by eleven hits and zero misses. Measured on a
     * hundred sessions, that rule dropped 2.8% of the thirteen-plus tier and lost 13 points.
     *
     * <p>What remains is the one state that cannot be recovered: an empty candidate set. No
     * word in the list fits the evidence, so the next reveal can only come from guessing the
     * answer blind, and that is not a reason to keep spending.
     */
    final class Hopeless implements SkipPolicy {

        static final Hopeless INSTANCE = new Hopeless();

        @Override
        public boolean shouldSkip(SkipContext context) {
            return context.candidates().length == 0;
        }

        @Override
        public String toString() {
            return "hopeless";
        }
    }

    /**
     * Bets on the arithmetic: continue while {@code 20 * P(solve)} exceeds the misses a failure
     * is expected to burn, {@code (1 - P) * missesLeft}.
     *
     * <p>{@code P(solve)} is estimated per candidate as a negative-binomial tail — the chance of
     * landing {@code d} hits before {@code missesLeft} misses — with a single hit rate {@code p}
     * shared by all of them, namely the best coverage any unguessed letter offers. That is
     * deliberately optimistic in one direction and pessimistic in another: it assumes every hit
     * keeps offering the same {@code p}, which overstates early progress and understates how
     * much the candidate set narrows, and it charges no cost for the hits themselves.
     *
     * <p>Evaluated at every misses-left value the game can reach, rather than only in the last
     * few turns: a fixed window would silently stop working the moment {@code --allow} changed,
     * and eight thousand words say the arithmetic never clears the bar at any of them — see the
     * README.
     */
    final class ExpectedValue implements SkipPolicy {

        static final ExpectedValue INSTANCE = new ExpectedValue();

        @Override
        public boolean shouldSkip(SkipContext context) {
            int missesLeft = context.wrongGuessesLeft();
            if (missesLeft <= 0) {
                return false;
            }
            if (context.candidates().length == 0) {
                return true;
            }

            Dictionary dictionary = context.dictionary();
            String pattern = context.pattern();
            int[] hidden = hiddenPositions(pattern);

            long[] weightByHits = new long[hidden.length + 1];
            long[] weightByLetter = new long[26];
            long total = 0;

            for (int index : context.candidates()) {
                long weight = dictionary.weightAt(index);
                total += weight;

                int letters = 0;
                for (int position : hidden) {
                    letters |= LetterFrequency.bitOf(dictionary.wordAt(index).charAt(position));
                }
                weightByHits[Integer.bitCount(letters)] += weight;

                int unguessed = dictionary.maskAt(index) & ~context.guessedMask();
                while (unguessed != 0) {
                    int bit = Integer.lowestOneBit(unguessed);
                    weightByLetter[Integer.numberOfTrailingZeros(bit)] += weight;
                    unguessed ^= bit;
                }
            }

            double bestHitRate = 0.0;
            for (long weight : weightByLetter) {
                bestHitRate = Math.max(bestHitRate, (double) weight / total);
            }

            double solveProbability = 0.0;
            for (int hits = 0; hits < weightByHits.length; hits++) {
                if (weightByHits[hits] == 0) {
                    continue;
                }
                solveProbability += (double) weightByHits[hits] / total
                        * solveProbability(hits, bestHitRate, missesLeft);
            }

            return 20.0 * solveProbability < (1.0 - solveProbability) * missesLeft;
        }

        /** Positions still hidden, in ascending order. */
        private static int[] hiddenPositions(String pattern) {
            int count = 0;
            for (int i = 0; i < pattern.length(); i++) {
                if (pattern.charAt(i) == '*') {
                    count++;
                }
            }
            int[] positions = new int[count];
            int next = 0;
            for (int i = 0; i < pattern.length(); i++) {
                if (pattern.charAt(i) == '*') {
                    positions[next++] = i;
                }
            }
            return positions;
        }

        /**
         * Probability of collecting {@code hits} successes before {@code misses} failures, when
         * each attempt succeeds with probability {@code p}: the negative-binomial tail
         * {@code sum over j <= misses of C(hits + j - 1, j) p^hits (1 - p)^j}.
         */
        static double solveProbability(int hits, double p, int misses) {
            if (hits <= 0) {
                return 1.0;
            }
            if (p <= 0.0) {
                return 0.0;
            }
            if (p >= 1.0) {
                return 1.0;
            }
            double term = Math.pow(p, hits);
            double sum = term;
            for (int missesSoFar = 1; missesSoFar <= misses; missesSoFar++) {
                term *= (hits + missesSoFar - 1) * (1.0 - p) / missesSoFar;
                sum += term;
            }
            return Math.min(1.0, sum);
        }

        @Override
        public String toString() {
            return "expected-value";
        }
    }
}
