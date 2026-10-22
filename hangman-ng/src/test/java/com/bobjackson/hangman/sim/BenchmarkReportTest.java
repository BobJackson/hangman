package com.bobjackson.hangman.sim;

import com.bobjackson.hangman.api.GameResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class BenchmarkReportTest {

    private static final SimulationConfig CONFIG = new SimulationConfig(1000,
            SimulationConfig.Sampling.WEIGHTED, SimulationConfig.DifficultySemantics.CUMULATIVE,
            2, 42L, 0.0, 4, 10);

    private final List<GameResult> games = List.of(
            new GameResult(4, 3, 10, GameResult.scoreOf(3, 10)),
            new GameResult(4, 4, 5, GameResult.scoreOf(4, 5)));

    private final List<BenchmarkReport.WordOutcome> words = List.of(
            new BenchmarkReport.WordOutcome(1, 5, true, 0, 5, 1),
            new BenchmarkReport.WordOutcome(2, 8, true, 0, 7, 1),
            new BenchmarkReport.WordOutcome(3, 9, true, 0, 8, 1),
            new BenchmarkReport.WordOutcome(4, 13, false, 10, 10, 0),
            new BenchmarkReport.WordOutcome(1, 5, true, 0, 4, 1),
            new BenchmarkReport.WordOutcome(2, 8, true, 0, 5, 1),
            new BenchmarkReport.WordOutcome(3, 9, true, 0, 6, 1),
            new BenchmarkReport.WordOutcome(4, 13, true, 0, 9, 1));

    @Test
    void summarisesTheScoreDistribution() {
        BenchmarkReport report = new BenchmarkReport("coverage", CONFIG, games, words);

        assertThat(report.meanScore()).isEqualTo(62.5);
        assertThat(report.medianScore()).isEqualTo(62.5);
        assertThat(report.sampleStdDevScore()).isCloseTo(17.6777, within(0.001));
        assertThat(report.minScore()).isEqualTo(50);
        assertThat(report.maxScore()).isEqualTo(75);
    }

    @Test
    void aggregatesTheWordOutcomes() {
        BenchmarkReport report = new BenchmarkReport("coverage", CONFIG, games, words);

        assertThat(report.totalWords()).isEqualTo(8);
        assertThat(report.totalCorrect()).isEqualTo(7);
        assertThat(report.solveRate()).isEqualTo(0.875);
        assertThat(report.totalWrong()).isEqualTo(10);
        assertThat(report.wrongPerWord()).isEqualTo(1.25);
        assertThat(report.totalGuesses()).isEqualTo(54);
        assertThat(report.notInDictionary()).isEqualTo(1);
    }

    @Test
    void breaksTheResultDownByDifficultyTier() {
        BenchmarkReport report = new BenchmarkReport("coverage", CONFIG, games, words);

        BenchmarkReport.TierStats hardest = report.tiers().get(3);
        assertThat(hardest.label()).isEqualTo("4-4 (>12)");
        assertThat(hardest.words()).isEqualTo(2);
        assertThat(hardest.solved()).isEqualTo(1);
        assertThat(hardest.solveRate()).isEqualTo(0.5);
        assertThat(hardest.wrongPerWord()).isEqualTo(5.0);
        assertThat(hardest.notInDictionaryRate()).isEqualTo(0.5);

        assertThat(report.tiers().get(0).solveRate()).isEqualTo(1.0);
        assertThat(report.tiers().get(0).guessesPerWord()).isEqualTo(4.5);
    }

    @Test
    void rendersATableThatNamesTheVariantAndTheTierBounds() {
        String table = new BenchmarkReport("coverage", CONFIG, games, words).toTable();

        assertThat(table).contains("coverage");
        assertThat(table).contains("1-1 (<=5)");
        assertThat(table).contains("sessions 2");
        assertThat(table).contains("correct 7 (87.5%)");
        assertThat(table).contains("blind");
    }

    @Test
    void rendersAOneLineSummary() {
        String line = new BenchmarkReport("occurrences", CONFIG, games, words).toSummaryLine();

        assertThat(line).startsWith("occurrences");
        assertThat(line).contains("score    62.50");
        assertThat(line).contains("correct  87.5%");
    }

    @Test
    void rejectsAReportWithNoSessions() {
        assertThatThrownBy(() -> new BenchmarkReport("coverage", CONFIG, List.of(), words))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnImpossibleWordOutcome() {
        assertThatThrownBy(() -> new BenchmarkReport.WordOutcome(0, 5, true, 0, 5, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenchmarkReport.WordOutcome(1, 5, false, 5, 2, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("less than wrongGuesses");
    }
}
