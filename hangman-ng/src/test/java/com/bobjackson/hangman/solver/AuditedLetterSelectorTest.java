package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.WordWeighting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The audit is only useful if it is invisible: it must return the delegate's letter unchanged
 * and put everything it learned into {@link AuditSummary}. These tests hold both halves.
 */
class AuditedLetterSelectorTest {

    private static final Dictionary WORDS = Dictionary.of(
            List.of("CAT", "BAT", "RAT"), WordWeighting.uniform());

    private static final int[] CANDIDATES = WORDS.indicesOfLength(3);

    /** The evidence is {@code **T}: T is known and ends every candidate. */
    private static final int AFTER_T = LetterFrequency.bitOf('T');

    @Test
    void playsExactlyTheLetterTheDelegateChose() {
        AuditSummary summary = new AuditSummary();
        AuditedLetterSelector audited = new AuditedLetterSelector(fixed('Z'), summary);

        assertThat(audited.select(context(1))).isEqualTo('Z');
    }

    @Test
    void pricesTheDecisionWithoutTakingOver() {
        AuditSummary summary = new AuditSummary();
        new AuditedLetterSelector(fixed('Z'), summary).select(context(1));

        assertThat(summary.decisions()).isEqualTo(1);
        assertThat(summary.overBudget()).isZero();
    }

    @Test
    void findsTheGapWhenTheDelegateWastesItsLastMiss() {
        AuditSummary summary = new AuditSummary();

        // Z is in no candidate, so with one miss left it ends the word at zero. The best letter
        // wins the twenty with probability 1/3, and with one miss left there is no second
        // chance, so the difference is exactly 20/3.
        new AuditedLetterSelector(fixed('Z'), summary).select(context(1));

        assertThat(summary.suboptimal()).isEqualTo(1);
        assertThat(summary.foregonePerGame(1)).isCloseTo(20.0 / 3.0, within(1e-9));
    }

    @Test
    void findsNoGapWhenTheDelegateTakesAnOptimalLetter() {
        AuditSummary summary = new AuditSummary();

        // A is contained in all three candidates, so it is a certain hit that also reveals the
        // middle position. Either of the three distinct letters ties with it at 20/3.
        new AuditedLetterSelector(fixed('A'), summary).select(context(1));

        assertThat(summary.decisions()).isEqualTo(1);
        assertThat(summary.suboptimal()).isZero();
        assertThat(summary.foregonePerGame(1)).isZero();
    }

    @Test
    void leavesWideStatesUnpriced() {
        AuditSummary summary = new AuditSummary();
        AuditedLetterSelector narrow = new AuditedLetterSelector(fixed('Z'), summary, 2, 3, 5_000);

        narrow.select(context(1));

        assertThat(summary.decisions()).isZero();
        assertThat(summary.overBudget()).isZero();
    }

    @Test
    void leavesDeepStatesUnpriced() {
        AuditSummary summary = new AuditSummary();
        AuditedLetterSelector shallow = new AuditedLetterSelector(fixed('Z'), summary, 40, 1, 5_000);

        shallow.select(context(3));

        assertThat(summary.decisions()).isZero();
    }

    @Test
    void countsADecisionItCouldNotFinishInsteadOfGuessingAtIt() {
        AuditSummary summary = new AuditSummary();
        AuditedLetterSelector tight = new AuditedLetterSelector(fixed('Z'), summary, 40, 3, 1);

        tight.select(context(1));

        assertThat(summary.decisions()).isZero();
        assertThat(summary.overBudget()).isEqualTo(1);
    }

    @Test
    void ignoresAnEmptyCandidateSet() {
        AuditSummary summary = new AuditSummary();
        AuditedLetterSelector audited = new AuditedLetterSelector(fixed('Z'), summary);

        audited.select(new GuessContext(WORDS, new int[0], AFTER_T, 1));

        assertThat(summary.decisions()).isZero();
        assertThat(summary.overBudget()).isZero();
    }

    @Test
    void refusesThresholdsThatWouldMakeItSilent() {
        AuditSummary summary = new AuditSummary();

        assertThatThrownBy(() -> new AuditedLetterSelector(fixed('A'), summary, 0, 3, 5_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditedLetterSelector(fixed('A'), summary, 40, 0, 5_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditedLetterSelector(fixed('A'), summary, 40, 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void describesWhatItSawInOneAsciiLine() {
        AuditSummary summary = new AuditSummary();
        new AuditedLetterSelector(fixed('Z'), summary).select(context(1));

        assertThat(summary.describe(2))
                .startsWith("audit: 1 of 1 choices priced exactly (100.0%), 1 not optimal (100.0%)")
                .contains("points per game foregone")
                .contains("0 over budget");
    }

    @Test
    void countsEveryChoiceItSawNotOnlyThePricedOnes() {
        AuditSummary summary = new AuditSummary();
        AuditedLetterSelector narrow = new AuditedLetterSelector(fixed('Z'), summary, 2, 3, 5_000);

        narrow.select(context(1));
        narrow.select(context(1));

        assertThat(summary.seen()).isEqualTo(2);
        assertThat(summary.decisions()).isZero();
    }

    @Test
    void saysSoWhenNothingCouldBePriced() {
        AuditSummary summary = new AuditSummary();
        summary.recordSeen();
        summary.recordOverBudget();

        assertThat(summary.describe(1))
                .isEqualTo("audit: 0 of 1 choices priced exactly (1 over budget)"
                        + System.lineSeparator());
    }

    private static GuessContext context(int missesLeft) {
        return new GuessContext(WORDS, CANDIDATES, AFTER_T, missesLeft);
    }

    private static LetterSelector fixed(char letter) {
        return ignored -> letter;
    }
}
