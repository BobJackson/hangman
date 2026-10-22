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
}
