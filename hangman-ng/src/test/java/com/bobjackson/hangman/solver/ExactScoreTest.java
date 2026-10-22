package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.WordWeighting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins {@link ExactScore} to a reference that shares none of its machinery: no memo, no state
 * object, no bit tricks, straight recursion over {@link List}s. Two independent write-ups of
 * the same recurrence is the only honest way to test a value function whose real answer cannot
 * be written down.
 */
class ExactScoreTest {

    private static final Dictionary WORDS = Dictionary.of(
            List.of("CAT", "BAT", "RAT", "COT"), WordWeighting.uniform());

    private static final int[] ALL = WORDS.indicesOfLength(3);

    @Test
    void pricesEveryLetterExactlyAsAnUncachedReference() {
        int[] masks = {
                0,
                LetterFrequency.bitOf('T'),
                LetterFrequency.bitOf('A'),
                LetterFrequency.bitOf('A') | LetterFrequency.bitOf('T'),
        };
        for (int mask : masks) {
            for (int missesLeft = 1; missesLeft <= 2; missesLeft++) {
                ExactScore score = ExactScore.measure(WORDS, ALL, mask, missesLeft, 1_000_000)
                        .orElseThrow();
                double[] expected = reference(WORDS, ALL, mask, missesLeft);
                for (int c = 0; c < 26; c++) {
                    char letter = (char) ('A' + c);
                    if ((mask & LetterFrequency.bitOf(letter)) != 0) {
                        continue;
                    }
                    assertThat(score.valueOf(letter))
                            .as("letter %s, mask %s, %d misses left", letter, mask, missesLeft)
                            .isCloseTo(expected[c], within(1e-9));
                }
                assertThat(score.bestValue())
                        .as("best value, mask %s, %d misses left", mask, missesLeft)
                        .isCloseTo(bestOf(expected), within(1e-9));
            }
        }
    }

    @Test
    void aWordThatCanStillBeSolvedIsWorthASureTwenty() {
        // One candidate and misses to spare: every letter in it is a certain hit, and the word
        // is revealed in at most three guesses, none of which can miss.
        int[] set = WORDS.indicesOfLength(3);
        int only = Arrays.stream(set).filter(i -> WORDS.wordAt(i).equals("CAT")).findFirst()
                .orElseThrow();

        ExactScore score = ExactScore.measure(WORDS, new int[]{only}, 0, 3, 1_000_000).orElseThrow();

        assertThat(score.bestValue()).isCloseTo(20.0, within(1e-9));
        assertThat(score.valueOf('C')).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void aLetterThatCannotHitIsWorthOnlyWhatTheMissLeaves() {
        int[] set = WORDS.indicesOfLength(3);
        int only = Arrays.stream(set).filter(i -> WORDS.wordAt(i).equals("CAT")).findFirst()
                .orElseThrow();

        // With one miss in hand, spending it on a letter CAT does not contain ends the word.
        ExactScore score = ExactScore.measure(WORDS, new int[]{only}, 0, 1, 1_000_000).orElseThrow();
        assertThat(score.valueOf('Z')).isCloseTo(0.0, within(1e-9));

        // With two, the miss is affordable and the sure winner is still reachable.
        ExactScore two = ExactScore.measure(WORDS, new int[]{only}, 0, 2, 1_000_000).orElseThrow();
        assertThat(two.valueOf('Z')).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void pricesNothingWhenTheCandidatesAreGone() {
        assertThat(ExactScore.measure(WORDS, new int[0], 0, 3, 1_000)).isEmpty();
    }

    @Test
    void pricesNothingWhenNoMissIsLeft() {
        assertThat(ExactScore.measure(WORDS, ALL, 0, 0, 1_000)).isEmpty();
    }

    @Test
    void givesUpInsteadOfApproximatingWhenTheBudgetIsGone() {
        // One state is not enough to see a whole decision, so the measurement is refused
        // rather than truncated: a truncated value is an estimate wearing an exact name.
        assertThat(ExactScore.measure(WORDS, ALL, 0, 3, 1)).isEmpty();
        assertThat(ExactScore.measure(WORDS, ALL, 0, 3, 1_000_000)).isPresent();
    }

    @Test
    void reportsHowMuchOfTheBudgetItSpent() {
        ExactScore score = ExactScore.measure(WORDS, ALL, 0, 2, 1_000_000).orElseThrow();
        assertThat(score.nodes()).isPositive();
        assertThat(score.nodes()).isLessThan(1_000_000);
    }

    @Test
    void refusesToPriceALetterThatWasNotOffered() {
        ExactScore score = ExactScore.measure(WORDS, ALL, LetterFrequency.bitOf('A'), 2, 1_000)
                .orElseThrow();

        assertThatThrownBy(() -> score.valueOf('A'))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not priced");
    }

    @Test
    void theImplicitPatternIsRevealedOnlyWhereEveryCandidateAgrees() {
        int[] all = WORDS.indicesOfLength(3);

        // T ends every word, so guessing it reveals the last position and nothing more: index 1
        // differs (A in CAT, BAT, RAT; O in COT) and index 0 differs too, so the mask is not
        // full and the word is not solved.
        assertThat(ExactScore.isSolved(WORDS, all, 0)).isFalse();
        assertThat(ExactScore.isSolved(WORDS, all, LetterFrequency.bitOf('T'))).isFalse();

        // One candidate: the word is fixed, so it is solved once its own letters are known.
        int only = all[0];
        String word = WORDS.wordAt(only);
        int full = 0;
        for (int i = 0; i < word.length(); i++) {
            full |= LetterFrequency.bitOf(word.charAt(i));
        }
        assertThat(ExactScore.isSolved(WORDS, new int[]{only}, 0)).isFalse();
        assertThat(ExactScore.isSolved(WORDS, new int[]{only}, full)).isTrue();
        assertThat(ExactScore.isSolved(WORDS, new int[0], full)).isFalse();
    }

    @Test
    void anEmptyCandidateSetIsNeverSolved() {
        assertThat(ExactScore.isSolved(WORDS, new int[0], LetterFrequency.ALL_LETTERS)).isFalse();
    }

    // --- the reference: the same recurrence, written without any of the production machinery ---

    private static double[] reference(Dictionary dictionary, int[] set, int mask, int missesLeft) {
        double[] byLetter = new double[26];
        Arrays.fill(byLetter, Double.NaN);
        if (set.length == 0 || missesLeft == 0
                || ExactScore.isSolved(dictionary, set, mask)) {
            return byLetter;
        }

        long total = 0;
        for (int index : set) {
            total += dictionary.weightAt(index);
        }

        for (int c = 0; c < 26; c++) {
            int bit = 1 << c;
            if ((mask & bit) != 0) {
                continue;
            }
            List<Integer> hits = new ArrayList<>();
            long hitWeight = 0;
            for (int index : set) {
                if ((dictionary.maskAt(index) & bit) != 0) {
                    hits.add(index);
                    hitWeight += dictionary.weightAt(index);
                }
            }
            double p = hits.isEmpty() ? 0.0 : (double) hitWeight / total;
            int[] hitArray = hits.stream().mapToInt(Integer::intValue).toArray();
            byLetter[c] = p * best(dictionary, hitArray, mask | bit, missesLeft)
                    + (1.0 - p) * best(dictionary, set, mask | bit, missesLeft - 1);
        }
        return byLetter;
    }

    private static double best(Dictionary dictionary, int[] set, int mask, int missesLeft) {
        if (set.length == 0 || missesLeft == 0) {
            return 0.0;
        }
        if (ExactScore.isSolved(dictionary, set, mask)) {
            return 20.0;
        }
        double best = 0.0;
        for (double value : reference(dictionary, set, mask, missesLeft)) {
            if (!Double.isNaN(value) && value > best) {
                best = value;
            }
        }
        return best;
    }

    private static double bestOf(double[] byLetter) {
        double best = Double.NEGATIVE_INFINITY;
        for (double value : byLetter) {
            if (!Double.isNaN(value) && value > best) {
                best = value;
            }
        }
        return best;
    }
}
