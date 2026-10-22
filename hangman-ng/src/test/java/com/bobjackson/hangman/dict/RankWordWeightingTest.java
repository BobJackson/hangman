package com.bobjackson.hangman.dict;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankWordWeightingTest {

    @Test
    void loadsTheBundledRankingInFrequencyOrder() {
        RankWordWeighting ranking = RankWordWeighting.fromClasspath();

        assertThat(ranking.size()).isGreaterThan(90_000);
        assertThat(ranking.rankOf("THE")).isEqualTo(1);
        assertThat(ranking.rankOf("AND")).isEqualTo(2);
        assertThat(ranking.rankOf("NOT_A_WORD")).isEqualTo(-1);
    }

    @Test
    void weightsDecreaseWithRankAndNeverFallToZero() {
        RankWordWeighting ranking = RankWordWeighting.fromClasspath();

        assertThat(ranking.weightOf("THE")).isGreaterThan(ranking.weightOf("THEM"));
        assertThat(ranking.weightOf("THEM")).isGreaterThan(ranking.weightOf("THEIRS"));
        assertThat(ranking.weightOf("NOT_A_WORD")).isEqualTo(RankWordWeighting.DEFAULT_FLOOR_WEIGHT);
    }

    @Test
    void topNReturnsTheMostFrequentWordsInOrder() {
        RankWordWeighting ranking = RankWordWeighting.fromClasspath();

        List<String> top = ranking.topN(3);

        assertThat(top).containsExactly("THE", "AND", "THAT");
        assertThat(ranking.topN(ranking.size() + 10)).hasSize(ranking.size());
    }

    @Test
    void topNRejectsANonPositiveCount() {
        assertThatThrownBy(() -> RankWordWeighting.fromClasspath().topN(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canBeBuiltFromAnExplicitListForTests() {
        RankWordWeighting ranking = RankWordWeighting.of(List.of("AAA", "BBB", "CCC"));

        assertThat(ranking.size()).isEqualTo(3);
        assertThat(ranking.rankOf("AAA")).isEqualTo(1);
        assertThat(ranking.weightOf("AAA")).isGreaterThan(ranking.weightOf("CCC"));
        assertThat(ranking.topN(2)).containsExactly("AAA", "BBB");
    }

    @Test
    void rejectsAnEmptyRanking() {
        assertThatThrownBy(() -> RankWordWeighting.of(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anExponentOfZeroCollapsesThePriorToUniform() {
        RankWordWeighting flat = RankWordWeighting.of(List.of("AAA", "BBB", "CCC"), 0.0);

        // Unlisted words flatten with the rest; otherwise the knob could never reach uniform.
        assertThat(flat.weightOf("AAA"))
                .isEqualTo(flat.weightOf("BBB"))
                .isEqualTo(flat.weightOf("CCC"))
                .isEqualTo(flat.weightOf("NOT_LISTED"))
                .isEqualTo(RankWordWeighting.DEFAULT_SCALE);
    }

    @Test
    void aSteeperExponentConcentratesWeightOnTheTopRanks() {
        double gentle = topVsBottomRatio(RankWordWeighting.DEFAULT_EXPONENT);
        double steep = topVsBottomRatio(1.0);

        // rank ratio is (n/1)^exponent, so 3^0.5 ~ 1.73 and 3^1.0 = 3
        assertThat(steep).isGreaterThan(gentle).isGreaterThan(1.0);
    }

    @Test
    void loadsTheBundledRankingWithACustomExponent() {
        RankWordWeighting flat = RankWordWeighting.fromClasspath(0.0);
        List<String> top = flat.topN(2);

        assertThat(flat.weightOf(top.get(0))).isEqualTo(flat.weightOf(top.get(1)));
    }

    private static double topVsBottomRatio(double exponent) {
        RankWordWeighting ranking = RankWordWeighting.of(List.of("AAA", "BBB", "CCC"), exponent);
        return (double) ranking.weightOf("AAA") / ranking.weightOf("CCC");
    }
}
