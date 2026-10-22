package com.bobjackson.hangman.sim;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationConfigTest {

    @Test
    void mapsWordIndexesOntoFourEqualTiers() {
        SimulationConfig config = SimulationConfig.defaults();

        assertThat(config.tierOf(1)).isZero();
        assertThat(config.tierOf(20)).isZero();
        assertThat(config.tierOf(21)).isEqualTo(1);
        assertThat(config.tierOf(40)).isEqualTo(1);
        assertThat(config.tierOf(41)).isEqualTo(2);
        assertThat(config.tierOf(60)).isEqualTo(2);
        assertThat(config.tierOf(61)).isEqualTo(3);
        assertThat(config.tierOf(80)).isEqualTo(3);
    }

    @Test
    void readsTierBoundsLiterallyUnderCumulativeSemantics() {
        SimulationConfig cumulative = withDifficulty(SimulationConfig.DifficultySemantics.CUMULATIVE);

        // The instructions give upper bounds only, so a tier-2 word may still be short.
        assertThat(cumulative.acceptsLength(1, 4)).isTrue();
        assertThat(cumulative.acceptsLength(2, 4)).isTrue();
        assertThat(cumulative.acceptsLength(3, 13)).isTrue();
        assertThat(cumulative.acceptsLength(2, 13)).isFalse();
    }

    @Test
    void readsTierBoundsAsDisjointBucketsUnderBucketedSemantics() {
        SimulationConfig bucketed = withDifficulty(SimulationConfig.DifficultySemantics.BUCKETED);

        assertThat(bucketed.acceptsLength(1, 5)).isFalse();
        assertThat(bucketed.acceptsLength(1, 6)).isTrue();
        assertThat(bucketed.acceptsLength(2, 12)).isTrue();
        assertThat(bucketed.acceptsLength(2, 13)).isFalse();
        assertThat(bucketed.acceptsLength(3, 13)).isTrue();
    }

    @Test
    void defaultsToTheTierReadingTheServerUsed() {
        // Pinned by the legacy control group: replaying the original client under cumulative
        // tiers scores 5.25 standard deviations below the 1326 it actually scored, so the
        // server bucketed by length. The literal reading stays available, not default.
        assertThat(SimulationConfig.defaults().difficulty())
                .isEqualTo(SimulationConfig.DifficultySemantics.BUCKETED);
    }

    @Test
    void labelsTiersWithTheirWordRange() {
        assertThat(SimulationConfig.defaults().tierLabel(0)).isEqualTo("1-20 (3-5)");
        assertThat(SimulationConfig.defaults().tierLabel(3)).isEqualTo("61-80 (13+)");
        assertThat(withDifficulty(SimulationConfig.DifficultySemantics.CUMULATIVE).tierLabel(1))
                .isEqualTo("21-40 (<=8)");
    }

    @Test
    void derivedConfigurationsOnlyChangeWhatTheyName() {
        SimulationConfig base = SimulationConfig.defaults();

        assertThat(base.withSeed(7L).seed()).isEqualTo(7L);
        assertThat(base.withSeed(7L).games()).isEqualTo(base.games());
        assertThat(base.withGames(3).games()).isEqualTo(3);
        assertThat(base.withGames(3).seed()).isEqualTo(base.seed());
        assertThat(base.withFailureRate(0.25).failureRate()).isEqualTo(0.25);
        assertThat(base.withFailureRate(0.25).wordsPerGame()).isEqualTo(base.wordsPerGame());
    }

    @Test
    void rejectsConfigurationsThatWouldBreakTheSimulator() {
        assertThatThrownBy(() -> config(1, 1, 0.0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(1, 1, 0.0, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(1, 1, 1.0, 80)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(0, 1, 0.0, 80)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(1, 0, 0.0, 80)).isInstanceOf(IllegalArgumentException.class);
    }

    private static SimulationConfig withDifficulty(SimulationConfig.DifficultySemantics semantics) {
        return new SimulationConfig(100_000, SimulationConfig.Sampling.WEIGHTED, semantics,
                20, 42L, 0.0, 80, 10);
    }

    private static SimulationConfig config(int poolSize, int games, double failureRate, int wordsPerGame) {
        return new SimulationConfig(poolSize, SimulationConfig.Sampling.WEIGHTED,
                SimulationConfig.DifficultySemantics.CUMULATIVE, games, 42L, failureRate,
                wordsPerGame, 10);
    }
}
