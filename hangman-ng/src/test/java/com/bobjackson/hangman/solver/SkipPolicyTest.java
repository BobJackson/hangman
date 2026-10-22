package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.WordWeighting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SkipPolicyTest {

    private static final Dictionary FIVE_DISJOINT =
            lexicon("ABCDE", "FGHIJ", "KLMNO", "PQRST", "UVWXY");

    @Test
    void countsDistinctHiddenLettersNotPositions() {
        // LEVEL has five positions but three distinct letters, and one hit reveals both Ls.
        SkipContext context = context(lexicon("LEVEL"), "*****", 0, 10);

        assertThat(context.hitsRequired("LEVEL")).isEqualTo(3);
    }

    @Test
    void aRevealedLetterNoLongerCounts() {
        SkipContext context = context(lexicon("LEVEL"), "L***L", 0, 10);

        assertThat(context.hitsRequired("LEVEL")).isEqualTo(2);
    }

    @Test
    void rejectsACandidateOfTheWrongLength() {
        SkipContext context = context(lexicon("CAT"), "***", 0, 10);

        assertThatThrownBy(() -> context.hitsRequired("BIRD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void hopelessDoesNotGiveUpAWordJustBecauseItNeedsMoreHitsThanMissesRemain() {
        // Five distinct letters hidden, one miss left. Still winnable: a hit costs no miss and
        // a word allows unlimited guesses, so five hits and one miss finishes it. The first
        // version of this policy compared those two numbers instead and lost 13 points per
        // hundred sessions to the words it dropped.
        SkipContext context = context(FIVE_DISJOINT, "*****", 0, 1);

        assertThat(context.hitsRequired("ABCDE")).isEqualTo(5);
        assertThat(SkipPolicy.hopeless().shouldSkip(context)).isFalse();
    }

    @Test
    void hopelessGivesUpOnlyWhenNothingInTheDictionaryFits() {
        SkipContext empty = new SkipContext(FIVE_DISJOINT, new int[0], "*****", 0, 3);
        SkipContext populated = context(FIVE_DISJOINT, "*****", 0, 3);

        assertThat(SkipPolicy.hopeless().shouldSkip(empty)).isTrue();
        assertThat(SkipPolicy.hopeless().shouldSkip(populated)).isFalse();
    }

    @Test
    void expectedValueAlsoTreatsAnEmptyCandidateSetAsHopeless() {
        SkipContext context = new SkipContext(FIVE_DISJOINT, new int[0], "*****", 0, 3);

        assertThat(SkipPolicy.expectedValue().shouldSkip(context)).isTrue();
    }

    @Test
    void expectedValueGivesUpOnALongShotThatIsStillTechnicallyWinnable() {
        // Five hits needed, five misses allowed, and every candidate carries five unique
        // letters, so any single hit rate is at most one in five. Hopeless says no; the
        // arithmetic says the twenty points are not worth the five misses.
        SkipContext context = context(FIVE_DISJOINT, "*****", 0, 5);

        assertThat(SkipPolicy.hopeless().shouldSkip(context)).isFalse();
        assertThat(SkipPolicy.expectedValue().shouldSkip(context)).isTrue();
    }

    @Test
    void expectedValueKeepsAWordWhenTheNextLetterIsNearlyCertain() {
        // Every candidate shares the same five letters, so the best hit rate is 1.0 and the
        // word is not a gamble at all — even with a single miss left.
        Dictionary dictionary = lexicon("ABCDE", "ABCDF", "ABCDG", "ABCDH", "ABCDI");
        SkipContext context = new SkipContext(dictionary, new int[]{0, 1, 2, 3, 4}, "*****", 0, 1);

        assertThat(SkipPolicy.expectedValue().shouldSkip(context)).isFalse();
    }

    @Test
    void negativeBinomialTailMatchesTheClosedForm() {
        // One hit with no slack: exactly p.
        assertThat(SkipPolicy.ExpectedValue.solveProbability(1, 0.5, 0)).isEqualTo(0.5);
        // One hit with one miss allowed: p + p(1-p).
        assertThat(SkipPolicy.ExpectedValue.solveProbability(1, 0.5, 1)).isCloseTo(0.75, within(1e-12));
        // Two hits with no slack: p^2.
        assertThat(SkipPolicy.ExpectedValue.solveProbability(2, 0.5, 0)).isCloseTo(0.25, within(1e-12));
        // Two hits with one miss allowed: p^2 + 2p^2(1-p).
        assertThat(SkipPolicy.ExpectedValue.solveProbability(2, 0.5, 1)).isCloseTo(0.5, within(1e-12));
    }

    @Test
    void aSolvedWordIsCertainAndAnUnreachableOneIsNot() {
        assertThat(SkipPolicy.ExpectedValue.solveProbability(0, 0.0, 0)).isEqualTo(1.0);
        assertThat(SkipPolicy.ExpectedValue.solveProbability(3, 0.0, 5)).isZero();
        assertThat(SkipPolicy.ExpectedValue.solveProbability(3, 1.0, 0)).isEqualTo(1.0);
    }

    private static SkipContext context(Dictionary dictionary, String pattern, int guessedMask, int missesLeft) {
        int[] candidates = new int[dictionary.size()];
        for (int i = 0; i < candidates.length; i++) {
            candidates[i] = i;
        }
        return new SkipContext(dictionary, candidates, pattern, guessedMask, missesLeft);
    }

    private static Dictionary lexicon(String... words) {
        return Dictionary.of(List.of(words), WordWeighting.uniform());
    }
}
