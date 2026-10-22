package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.WordWeighting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class InformationGainLetterSelectorTest {

    @Test
    void aLetterNoCandidateContainsIsWorthNothing() {
        assertThat(InformationGainLetterSelector.expectedGain(0, 100)).isZero();
    }

    @Test
    void aLetterEveryCandidateContainsIsWorthNothingEither() {
        // Certain to hit, and certain to teach nothing: the mask already implies it.
        assertThat(InformationGainLetterSelector.expectedGain(100, 100)).isZero();
    }

    @Test
    void anEvenSplitIsWorthOneBit() {
        // Half the candidates carry it, so either answer halves what is left.
        assertThat(InformationGainLetterSelector.expectedGain(50, 100)).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void anEvenSplitIsTheMostInformativeLetterAvailable() {
        double coinFlip = InformationGainLetterSelector.expectedGain(50, 100);
        double nearCertain = InformationGainLetterSelector.expectedGain(90, 100);

        // Symmetric: a letter in 10 of 100 separates as well as one in 90 of 100.
        assertThat(InformationGainLetterSelector.expectedGain(10, 100)).isCloseTo(nearCertain, within(1e-12));
        // And a coin flip beats both, which is exactly what a miss-averse rule refuses to do.
        assertThat(coinFlip).isGreaterThan(nearCertain);
        assertThat(coinFlip).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void prefersTheLetterThatSplitsTheCandidatesOverTheOneThatAlwaysHits() {
        // CAT and CAR both contain A, so asking A is a guaranteed hit that separates nothing:
        // both candidates survive, and two letters are still hidden. Asking T separates them.
        Dictionary dictionary = Dictionary.of(List.of("CAT", "CAR"), WordWeighting.uniform());

        assertThat(new InformationGainLetterSelector().select(
                new GuessContext(dictionary, new int[]{0, 1}, 0, 10))).isEqualTo('T');
    }

    @Test
    void fallsBackToLetterFrequencyWhenNothingIsLeft() {
        Dictionary dictionary = Dictionary.of(List.of("CAT"), WordWeighting.uniform());

        assertThat(new InformationGainLetterSelector().select(
                new GuessContext(dictionary, new int[0], 0, 10))).isEqualTo('E');
    }

    @Test
    void neverRepeatsAGuessedLetter() {
        Dictionary dictionary = Dictionary.of(List.of("CAT", "CAR"), WordWeighting.uniform());
        int guessedT = LetterFrequency.bitOf('T');

        assertThat(new InformationGainLetterSelector().select(
                new GuessContext(dictionary, new int[]{0, 1}, guessedT, 10))).isEqualTo('R');
    }
}
