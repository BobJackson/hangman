package com.bobjackson.hangman.legacy;

import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.WordWeighting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyTiersTest {

    private static Dictionary dictionary(String... words) {
        return Dictionary.of(List.of(words), WordWeighting.uniform());
    }

    @Test
    void bucketsWordsByLength() {
        LegacyTiers tiers = LegacyTiers.of(dictionary("CAT", "HORSE", "ELEPHANT", "MAGNIFICENT", "EXTRAORDINARILY"));

        assertThat(tiers.indicesForWord(1)).as("<=5").hasSize(2);   // CAT, HORSE
        assertThat(tiers.indicesForWord(21)).as("<=8").hasSize(1);  // ELEPHANT
        assertThat(tiers.indicesForWord(41)).as("<=12").hasSize(1); // MAGNIFICENT
        assertThat(tiers.indicesForWord(61)).as(">12").hasSize(1);  // EXTRAORDINARILY
    }

    @Test
    void selectsTheBucketByWordNumberNotByTheAnswerLength() {
        LegacyTiers tiers = LegacyTiers.of(dictionary("CAT", "HORSE", "ELEPHANT"));

        // Word 21 may legitimately be five letters or shorter under the documented rules; the
        // bucket it lands in holds only six-to-eight-letter words, so the lookup finds nothing.
        int[] bucket = tiers.indicesForWord(21);
        assertThat(bucket).isNotEmpty();
        for (int index : bucket) {
            assertThat(tiers.dictionary().wordAt(index).length()).isBetween(6, 8);
        }
    }

    @Test
    void anEmptyBucketStaysEmpty() {
        LegacyTiers tiers = LegacyTiers.of(dictionary("CAT"));

        assertThat(tiers.indicesForWord(41)).isEmpty();
        assertThat(tiers.indicesForWord(61)).isEmpty();
    }
}
