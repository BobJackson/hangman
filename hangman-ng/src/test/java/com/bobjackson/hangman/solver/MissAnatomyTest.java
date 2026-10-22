package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.api.WordState;
import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.WordWeighting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The probe is only useful if it is invisible, so these tests hold two things at once: that it
 * forwards every call untouched, and that it prices the letter it actually sent.
 */
class MissAnatomyTest {

    private static final Dictionary WORDS = Dictionary.of(
            List.of("CAT", "BAT", "RAT"), WordWeighting.uniform());

    private static final int[] CANDIDATES = WORDS.indicesOfLength(3);

    private static final int WORD_ONE = 1;
    private static final int MISSES = 10;

    @Test
    void returnsTheDelegatesLetterUnchanged() {
        MissAnatomySummary summary = new MissAnatomySummary();
        FrequencyLetterSelector coverage = new FrequencyLetterSelector();
        MissAnatomy anatomy = anatomy(coverage, summary);

        char plain = EliminationSolver.forWord(WORDS, coverage, new WordState("***", WORD_ONE, 0), MISSES)
                .nextGuess();

        assertThat(anatomy.nextGuess()).isEqualTo(plain);
    }

    @Test
    void countsAHitAsAHit() {
        MissAnatomySummary summary = new MissAnatomySummary();
        MissAnatomy anatomy = anatomy(fixed('C'), summary);

        anatomy.nextGuess();
        anatomy.observe('C', new WordState("C**", WORD_ONE, 0));

        assertThat(summary.guesses()).isEqualTo(1);
        assertThat(summary.misses()).isZero();
    }

    @Test
    void countsAMissAsAMiss() {
        MissAnatomySummary summary = new MissAnatomySummary();
        MissAnatomy anatomy = anatomy(fixed('Z'), summary);

        anatomy.nextGuess();
        anatomy.observe('Z', new WordState("***", WORD_ONE, 1));

        assertThat(summary.guesses()).isEqualTo(1);
        assertThat(summary.misses()).isEqualTo(1);
    }

    @Test
    void pricesTheLetterItActuallySent() {
        // Only CAT contains a C, and all three words carry the same weight.
        assertThat(MissAnatomy.hitProbability(WORDS, CANDIDATES, 'C')).isCloseTo(1.0 / 3.0, within(1e-12));
    }

    @Test
    void pricesACertainLetterAsCertain() {
        assertThat(MissAnatomy.hitProbability(WORDS, CANDIDATES, 'T')).isEqualTo(1.0);
    }

    @Test
    void pricesALetterNoCandidateContainsAsZero() {
        assertThat(MissAnatomy.hitProbability(WORDS, CANDIDATES, 'Z')).isZero();
    }

    @Test
    void pricesAnEmptyCandidateSetAsZero() {
        assertThat(MissAnatomy.hitProbability(WORDS, new int[0], 'C')).isZero();
    }

    @Test
    void addsUpTheMissesItExpected() {
        MissAnatomySummary summary = new MissAnatomySummary();
        MissAnatomy anatomy = anatomy(fixed('C'), summary);

        anatomy.nextGuess();
        anatomy.observe('C', new WordState("C**", WORD_ONE, 0));

        assertThat(summary.predictedMisses()).isCloseTo(2.0 / 3.0, within(1e-12));
    }

    @Test
    void forwardsTheDelegateOnEveryOtherCall() {
        MissAnatomySummary summary = new MissAnatomySummary();
        MissAnatomy anatomy = anatomy(fixed('C'), summary);

        assertThat(anatomy.candidateCount()).isEqualTo(3);
        assertThat(anatomy.shouldAbandon()).isFalse();

        anatomy.nextGuess();
        anatomy.observe('C', new WordState("C**", WORD_ONE, 0));

        assertThat(anatomy.candidateCount()).isEqualTo(1);
    }

    @Test
    void reportsNothingBeforeTheFirstGuess() {
        MissAnatomySummary summary = new MissAnatomySummary();
        anatomy(fixed('C'), summary);

        assertThat(summary.guesses()).isZero();
        assertThat(summary.describe()).isEqualTo("miss anatomy: no guess was recorded"
                + System.lineSeparator());
    }

    @Test
    void refusesANonPositiveWordLength() {
        MissAnatomySummary summary = new MissAnatomySummary();
        EliminationSolver delegate = EliminationSolver.forWord(
                WORDS, fixed('C'), new WordState("***", WORD_ONE, 0), MISSES);

        assertThatThrownBy(() -> new MissAnatomy(delegate, WORDS, summary, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MissAnatomy anatomy(LetterSelector selector, MissAnatomySummary summary) {
        return new MissAnatomy(
                EliminationSolver.forWord(WORDS, selector, new WordState("***", WORD_ONE, 0), MISSES),
                WORDS, summary, 3);
    }

    private static LetterSelector fixed(char letter) {
        return ignored -> letter;
    }
}
