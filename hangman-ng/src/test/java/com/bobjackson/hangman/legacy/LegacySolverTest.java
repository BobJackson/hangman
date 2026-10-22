package com.bobjackson.hangman.legacy;

import com.bobjackson.hangman.api.WordState;
import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.WordWeighting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegacySolverTest {

    private static final int WORD_ONE = 1;
    private static final int WORD_SIXTY_ONE = 61;

    private static LegacySolver solverFor(String[] words, String firstMask, int wordNumber) {
        Dictionary dictionary = Dictionary.of(List.of(words), WordWeighting.uniform());
        return new LegacySolver(LegacyTiers.of(dictionary), new WordState(firstMask, wordNumber, 0));
    }

    @Test
    void alwaysOpensWithE() {
        LegacySolver solver = solverFor(new String[]{"CAT", "DOG"}, "***", WORD_ONE);

        assertThat(solver.nextGuess()).isEqualTo('E');
    }

    @Test
    void aHitOnTheFirstGuessLeavesOtherLengthsInPlay() {
        // BEEF is four letters and must not survive a three-letter word — but the length filter
        // lives in the wrong-guess path, so a first-guess hit never reaches it.
        LegacySolver solver = solverFor(new String[]{"BEE", "BEEF", "SEE", "CAT"}, "***", WORD_ONE);
        assertThat(solver.nextGuess()).isEqualTo('E');

        solver.observe('E', new WordState("*EE", WORD_ONE, 0));

        assertThat(solver.candidateCount()).isEqualTo(3);
    }

    @Test
    void aMissFiltersByLengthAsWell() {
        LegacySolver solver = solverFor(new String[]{"BEE", "BEEF", "SEE", "CAT"}, "***", WORD_ONE);
        assertThat(solver.nextGuess()).isEqualTo('E');

        solver.observe('E', new WordState("***", WORD_ONE, 1));

        assertThat(solver.candidateCount()).isEqualTo(1);
    }

    @Test
    void reusesTheLastOccurrenceCountsWhenTheCandidateSetIsEmpty() {
        LegacySolver solver = solverFor(new String[]{"ABC", "DEF"}, "***", WORD_ONE);
        assertThat(solver.nextGuess()).isEqualTo('E');

        // E misses: DEF leaves, ABC stays, and its letters get counted.
        solver.observe('E', new WordState("***", WORD_ONE, 1));
        assertThat(solver.nextGuess()).isEqualTo('A');

        // A misses: ABC leaves and the set is now empty. The counts are never refreshed, so
        // the next letter comes from the stale B/C counts rather than the fallback order.
        solver.observe('A', new WordState("***", WORD_ONE, 2));
        assertThat(solver.candidateCount()).isZero();
        assertThat(solver.nextGuess()).isEqualTo('B');
    }

    @Test
    void fallsBackToTheFixedLetterOrderWhenNoCountsExist() {
        // Word 61 selects the >12 bucket, which holds nothing for this dictionary.
        LegacySolver solver = solverFor(new String[]{"CAT"}, "*************", WORD_SIXTY_ONE);
        assertThat(solver.nextGuess()).isEqualTo('E');

        solver.observe('E', new WordState("*************", WORD_SIXTY_ONE, 1));

        assertThat(solver.candidateCount()).isZero();
        assertThat(solver.nextGuess()).as("second letter of EAOIUTSRNHLDCMFPGWYBVKXJQZ").isEqualTo('A');
    }

    @Test
    void resolvesEqualCountsAlphabetically() {
        // B, C and X all occur twice. HashMap iteration over Character keys is alphabetical,
        // so the original picked B; the frequency order would have picked C.
        LegacySolver solver = solverFor(new String[]{"BXB", "CXC"}, "***", WORD_ONE);
        assertThat(solver.nextGuess()).isEqualTo('E');

        solver.observe('E', new WordState("***", WORD_ONE, 1));

        assertThat(solver.nextGuess()).isEqualTo('B');
    }
}
