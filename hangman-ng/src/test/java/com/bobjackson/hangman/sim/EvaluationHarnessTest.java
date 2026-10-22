package com.bobjackson.hangman.sim;

import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.RankWordWeighting;
import com.bobjackson.hangman.solver.FrequencyLetterSelector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationHarnessTest {

    /** Mirrors {@code EvaluationHarness.GAME_SEED_STRIDE}; the seed layout is a promise, not a detail. */
    private static final long SEED_STRIDE = 1_000_003L;
    private static final long SEED = 7L;

    /** Words in every length bucket, so the simulator can fill all four tiers. */
    private static final List<String> WORDS = List.of(
            "CAT", "DOG", "BIRD",
            "SPRING", "HANGMAN", "PUZZLE",
            "CHOCOLATE", "DICTIONARY", "INFORMATION",
            "INTERNATIONAL", "CHARACTERISTIC");

    private static final EvaluationHarness.Variant VARIANT = EvaluationHarness.Variant.selectingLetters(
            "coverage", new FrequencyLetterSelector(FrequencyLetterSelector.Mode.COVERAGE));

    private final RankWordWeighting ranking = RankWordWeighting.of(WORDS);
    private final Dictionary dictionary = Dictionary.of(WORDS, ranking);

    @Test
    void bestOfOneIsThePlainRun() {
        BenchmarkReport plain = harness(SEED, 6).run(VARIANT);
        BenchmarkReport explicit = harness(SEED, 6).runBestOf(VARIANT, 1);

        assertThat(explicit.games()).isEqualTo(plain.games());
        assertThat(explicit.words()).isEqualTo(plain.words());
        assertThat(plain.attempts()).isEqualTo(1);
        assertThat(explicit.attempts()).isEqualTo(1);
    }

    @Test
    void everyKeptScoreIsTheHighestOfThatGamesAttempts() {
        int games = 3;
        int attempts = 4;
        BenchmarkReport report = harness(SEED, games).runBestOf(VARIANT, attempts);

        assertThat(report.attempts()).isEqualTo(attempts);
        assertThat(report.games()).hasSize(games);
        // Only the winners are reported, so the tier table describes the sessions whose score
        // was actually kept rather than an average over sessions nobody played.
        assertThat(report.words()).hasSize(games * 4);

        for (int game = 0; game < games; game++) {
            int expected = Integer.MIN_VALUE;
            for (int attempt = 0; attempt < attempts; attempt++) {
                expected = Math.max(expected, sessionScore(seedOf(game, attempt, games)));
            }
            assertThat(report.games().get(game).score()).isEqualTo(expected);
        }
    }

    @Test
    void bestOfNeverScoresLowerThanTheFirstAttemptOfTheSameGame() {
        BenchmarkReport single = harness(SEED, 4).run(VARIANT);
        BenchmarkReport best = harness(SEED, 4).runBestOf(VARIANT, 5);

        for (int game = 0; game < 4; game++) {
            assertThat(best.games().get(game).score())
                    .isGreaterThanOrEqualTo(single.games().get(game).score());
        }
        assertThat(best.meanScore()).isGreaterThanOrEqualTo(single.meanScore());
    }

    @Test
    void concurrentAttemptsProduceTheSameReportEveryTime() {
        BenchmarkReport first = harness(SEED, 4).runBestOf(VARIANT, 4);
        BenchmarkReport second = harness(SEED, 4).runBestOf(VARIANT, 4);

        assertThat(second.games()).isEqualTo(first.games());
        assertThat(second.words()).isEqualTo(first.words());
    }

    @Test
    void rejectsFewerThanOneAttempt() {
        assertThatThrownBy(() -> harness(SEED, 2).runBestOf(VARIANT, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempts");
    }

    /** The seed best-of-N uses for {@code attempt} of {@code game}, per the harness's layout. */
    private static long seedOf(int game, int attempt, int games) {
        return SEED + (game + (long) attempt * games) * SEED_STRIDE;
    }

    /** Score of one session played on its own; that session is exactly the attempt best-of-N plays. */
    private int sessionScore(long seed) {
        return harness(seed, 1).run(VARIANT).games().get(0).score();
    }

    private EvaluationHarness harness(long seed, int games) {
        SimulationConfig config = new SimulationConfig(WORDS.size(), SimulationConfig.Sampling.WEIGHTED,
                SimulationConfig.DifficultySemantics.BUCKETED, games, seed, 0.0, 4, 10);
        return new EvaluationHarness(config, dictionary, ranking);
    }
}
