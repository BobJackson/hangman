package com.bobjackson.hangman.legacy;

import com.bobjackson.hangman.dict.Dictionary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The four dictionaries the legacy client built from {@code words.txt}, rebuilt faithfully.
 *
 * <p>This exists to answer a question the original code cannot answer about itself: what
 * score does <em>its</em> algorithm get on the same simulator the rewrite is measured on.
 * The buckets are indexed the way the legacy code indexed them, including the detail that
 * makes the two halves disagree with each other.
 *
 * <h2>The mismatch being reproduced</h2>
 * Words are bucketed by <b>length</b> ({@code <=5}, {@code <=8}, {@code <=12}, {@code >12})
 * but selected by <b>word number</b> ({@code 1-20}, {@code 21-40}, {@code 41-60},
 * {@code 61-80}). Read against the instructions' literal wording the two halves disagree:
 * under {@code <=5} rules word 21 may be three letters, and then the client looks it up in the
 * six-to-eight-letter bucket, finds nothing, falls back to a fixed letter order and spends its
 * allowance guessing blind.
 *
 * <p>Running both readings is what settles which one the server used. The published client
 * scored 1326; the replay scores 1260 ± 50 under disjoint buckets and 841 under cumulative
 * tiers. The server bucketed by length, so the client was consistent with it, and the mismatch
 * only appears when the two halves are read against a rule that was never in force. It is
 * reproduced here anyway, because measuring what it would have cost is the reason this class
 * exists.
 *
 * <p>The legacy client rebuilt all four lists on every {@code nextWord}, walking 416k words
 * eighty times per game. Here they are built once per run: rebuilding is a cost, not a
 * decision, and measuring it would take hours rather than seconds. The cost is discussed in
 * the README instead of measured.
 */
public final class LegacyTiers {

    /** Number of buckets in the legacy client. */
    public static final int COUNT = 4;

    private final Dictionary dictionary;
    private final int[][] buckets;

    private LegacyTiers(Dictionary dictionary, int[][] buckets) {
        this.dictionary = dictionary;
        this.buckets = buckets;
    }

    /** Groups every dictionary word into the four legacy buckets, by length. */
    public static LegacyTiers of(Dictionary dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        List<List<Integer>> grouped = new ArrayList<>(COUNT);
        for (int tier = 0; tier < COUNT; tier++) {
            grouped.add(new ArrayList<>());
        }
        for (int index = 0; index < dictionary.size(); index++) {
            grouped.get(tierOfLength(dictionary.wordAt(index).length())).add(index);
        }
        int[][] buckets = new int[COUNT][];
        for (int tier = 0; tier < COUNT; tier++) {
            buckets[tier] = grouped.get(tier).stream().mapToInt(Integer::intValue).toArray();
        }
        return new LegacyTiers(dictionary, buckets);
    }

    /**
     * The bucket the legacy client would have used for the word at {@code totalWordCount}.
     *
     * @param totalWordCount 1-based index of the current word, as the server reports it
     */
    public int[] indicesForWord(int totalWordCount) {
        return buckets[tierOfWordCount(totalWordCount)];
    }

    public Dictionary dictionary() {
        return dictionary;
    }

    /** Bucket assignment by length, exactly as {@code initDictionary} did it. */
    private static int tierOfLength(int length) {
        if (length <= 5) {
            return 0;
        }
        if (length <= 8) {
            return 1;
        }
        return length <= 12 ? 2 : 3;
    }

    /** Bucket selection by word number, exactly as {@code findDictionaryByTotalWordCount} did. */
    private static int tierOfWordCount(int totalWordCount) {
        if (totalWordCount <= 20) {
            return 0;
        }
        if (totalWordCount <= 40) {
            return 1;
        }
        return totalWordCount <= 60 ? 2 : 3;
    }
}
