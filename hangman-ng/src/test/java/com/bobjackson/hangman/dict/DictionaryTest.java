package com.bobjackson.hangman.dict;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DictionaryTest {

    @Test
    void maskHasOneBitPerDistinctLetter() {
        int mask = Dictionary.maskOf("BANANA");

        assertThat(Integer.bitCount(mask)).isEqualTo(3);
        assertThat(mask).isEqualTo(bit('B') | bit('A') | bit('N'));
    }

    @Test
    void maskRejectsAnythingOutsideUpperAscii() {
        assertThatThrownBy(() -> Dictionary.maskOf("AB1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dictionary.maskOf("ab")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dictionary.maskOf("A B")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dictionary.maskOf("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deduplicatesInputAndSumsWeights() {
        Dictionary dictionary = Dictionary.of(List.of("AA", "AA", "BBB"), WordWeighting.uniform());

        assertThat(dictionary.size()).isEqualTo(2);
        assertThat(dictionary.totalWeight()).isEqualTo(2);
        assertThat(dictionary.weightAt(0)).isEqualTo(1);
    }

    @Test
    void rejectsAWeightingThatReturnsNonPositiveWeights() {
        assertThatThrownBy(() -> Dictionary.of(List.of("AA"), word -> 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-positive weight");
    }

    @Test
    void rejectsAnEmptyWordList() {
        assertThatThrownBy(() -> Dictionary.of(List.of(), WordWeighting.uniform()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void indicesOfLengthIsACopySoCallersCannotCorruptTheIndex() {
        Dictionary dictionary = Dictionary.of(List.of("CAT", "DOG"), WordWeighting.uniform());

        int[] first = dictionary.indicesOfLength(3);
        first[0] = -1;
        first[1] = -1;

        assertThat(dictionary.indicesOfLength(3)).containsExactly(0, 1);
    }

    @Test
    void lengthIndexesCoverEveryWordExactlyOnce() {
        Dictionary dictionary = Dictionary.of(List.of("A", "BB", "CC", "DDD"), WordWeighting.uniform());

        int total = 0;
        for (int len : dictionary.lengthsArray()) {
            for (int index : dictionary.indicesOfLength(len)) {
                assertThat(dictionary.wordAt(index)).hasSize(len);
                total++;
            }
        }
        assertThat(total).isEqualTo(dictionary.size());
    }

    private static int bit(char letter) {
        return 1 << (letter - 'A');
    }
}
