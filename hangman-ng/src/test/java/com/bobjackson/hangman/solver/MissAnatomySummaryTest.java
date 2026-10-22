package com.bobjackson.hangman.solver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The summary is the whole output of the probe, so these tests are about the shape of the
 * report: which row a guess lands in, and whether the numbers on that row agree with the
 * samples that produced them.
 */
class MissAnatomySummaryTest {

    @Test
    void saysSoWhenNothingWasRecorded() {
        assertThat(new MissAnatomySummary().describe())
                .isEqualTo("miss anatomy: no guess was recorded" + System.lineSeparator());
    }

    @Test
    void countsHitsAndMissesInTheHeadline() {
        MissAnatomySummary summary = new MissAnatomySummary();
        summary.record(sample(3, 1, 'A', 0.75, false));
        summary.record(sample(3, 1, 'B', 0.75, true));

        assertThat(summary.guesses()).isEqualTo(2);
        assertThat(summary.misses()).isEqualTo(1);
        assertThat(summary.describe())
                .startsWith("miss anatomy: 2 guesses, 1 misses (50.0%), model predicted 25.0%");
    }

    @Test
    void printsOneRowPerWordLength() {
        MissAnatomySummary summary = new MissAnatomySummary();
        summary.record(sample(3, 1, 'A', 0.5, true));
        summary.record(sample(5, 1, 'A', 0.5, false));

        String report = summary.describe();

        assertThat(report).contains("by word length");
        assertThat(report.lines().filter(line -> line.startsWith("  3 "))).hasSize(1);
        assertThat(report.lines().filter(line -> line.startsWith("  5 "))).hasSize(1);
    }

    @Test
    void poolsGuessIndicesFromTenUpwards() {
        MissAnatomySummary summary = new MissAnatomySummary();
        summary.record(sample(3, 10, 'A', 0.5, true));
        summary.record(sample(3, 12, 'A', 0.5, false));

        assertThat(summary.describe())
                .contains("by guess index")
                .contains("10+");
        assertThat(summary.describe().lines().filter(line -> line.startsWith("  10+"))).hasSize(1);
    }

    @Test
    void reportsDeltaAsObservedMinusPredicted() {
        MissAnatomySummary summary = new MissAnatomySummary();
        // Two guesses at P(hit) 0.8: the model expects 0.2 misses each, and both missed.
        summary.record(sample(3, 1, 'A', 0.8, false));
        summary.record(sample(3, 1, 'B', 0.8, false));

        assertThat(summary.predictedMisses()).isCloseTo(0.4, within(1e-12));
        assertThat(summary.describe().lines()
                .filter(line -> line.startsWith("  3 "))
                .findFirst()
                .orElseThrow())
                .contains("1.000")   // observed
                .contains("0.200")   // predicted
                .contains("+0.800"); // delta
    }

    @Test
    void reportsANegativeDeltaWhenTheModelWasTooPessimistic() {
        MissAnatomySummary summary = new MissAnatomySummary();
        // The model kept a fifth of a miss in reserve and the guess hit anyway.
        summary.record(sample(3, 1, 'A', 0.8, true));

        assertThat(summary.describe().lines()
                .filter(line -> line.startsWith("  3 "))
                .findFirst()
                .orElseThrow())
                .contains("-0.200");
    }

    @Test
    void splitsTheHitChanceIntoTenTenths() {
        MissAnatomySummary summary = new MissAnatomySummary();
        summary.record(sample(3, 1, 'A', 0.35, true));

        assertThat(summary.describe())
                .contains("by P(hit) tenth")
                .contains("0.3-0.4");
    }

    @Test
    void putsACertainHitInTheTopTenthRatherThanAnEleventh() {
        MissAnatomySummary summary = new MissAnatomySummary();
        summary.record(sample(3, 1, 'A', 1.0, true));

        assertThat(summary.describe())
                .contains("0.9-1.0")
                .doesNotContain("1.0-1.1");
    }

    @Test
    void rejectsSamplesOutsideTheirRanges() {
        assertThatThrownBy(() -> new MissAnatomySummary.GuessSample(0, 1, 10, 5, 'A', 0.5, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MissAnatomySummary.GuessSample(3, 0, 10, 5, 'A', 0.5, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MissAnatomySummary.GuessSample(3, 1, -1, 5, 'A', 0.5, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MissAnatomySummary.GuessSample(3, 1, 10, -1, 'A', 0.5, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MissAnatomySummary.GuessSample(3, 1, 10, 5, 'a', 0.5, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MissAnatomySummary.GuessSample(3, 1, 10, 5, 'A', 1.5, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MissAnatomySummary.GuessSample(3, 1, 10, 5, 'A', Double.NaN, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MissAnatomySummary.GuessSample sample(int wordLength, int guessIndex, char letter,
                                                         double pHit, boolean hit) {
        return new MissAnatomySummary.GuessSample(wordLength, guessIndex, 100, 10, letter, pHit, hit);
    }
}
