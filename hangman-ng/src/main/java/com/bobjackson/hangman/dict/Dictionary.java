package com.bobjackson.hangman.dict;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * An immutable word list with the two derived indexes the solver needs.
 *
 * <p>Two design choices matter here, both reactions to the legacy implementation:
 *
 * <ul>
 *   <li><b>Immutable.</b> The legacy code filtered candidates by calling {@code remove} on
 *       a shared {@code CopyOnWriteArrayList}, which destroyed the dictionary and forced a
 *       full 416k-word rebuild before every one of the 80 words. Filtering here produces a
 *       new {@code int[]} of indices and never touches this object.</li>
 *   <li><b>Index-based.</b> Candidates are dictionary offsets, not strings, so a filter
 *       pass allocates one array and no strings. Each word also carries a precomputed
 *       26-bit letter mask, letting "does this word contain letter c" become a single
 *       bitwise AND instead of a scan.</li>
 * </ul>
 */
public final class Dictionary {

    private static final int[] NO_INDICES = new int[0];

    private final String[] words;
    private final int[] masks;
    private final int[] weights;
    private final long totalWeight;
    private final Map<Integer, int[]> indicesByLength;

    private Dictionary(String[] words, int[] masks, int[] weights, Map<Integer, int[]> indicesByLength) {
        this.words = words;
        this.masks = masks;
        this.weights = weights;
        this.indicesByLength = indicesByLength;
        long sum = 0;
        for (int w : weights) {
            sum += w;
        }
        this.totalWeight = sum;
    }

    /**
     * Builds a dictionary, dropping duplicates and weighting each surviving word.
     *
     * @param rawWords  already-sanitised words: non-empty, {@code A-Z} only
     * @param weighting source of per-word priors
     * @throws IllegalArgumentException if a word is blank or contains a character outside {@code A-Z}
     */
    public static Dictionary of(Collection<String> rawWords, WordWeighting weighting) {
        Set<String> unique = new LinkedHashSet<>(rawWords);
        for (String w : unique) {
            validate(w);
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("dictionary must not be empty");
        }

        String[] words = unique.toArray(String[]::new);
        int[] masks = new int[words.length];
        int[] weights = new int[words.length];
        for (int i = 0; i < words.length; i++) {
            masks[i] = maskOf(words[i]);
            weights[i] = weighting.weightOf(words[i]);
            if (weights[i] <= 0) {
                throw new IllegalArgumentException(
                        "weighting returned non-positive weight " + weights[i] + " for \"" + words[i] + '"');
            }
        }

        // Group indices by word length once, so selecting candidates for a puzzle is O(1)
        // rather than a 416k-word scan per guess.
        Map<Integer, List<Integer>> grouped = new TreeMap<>();
        for (int i = 0; i < words.length; i++) {
            grouped.computeIfAbsent(words[i].length(), k -> new ArrayList<>()).add(i);
        }
        Map<Integer, int[]> byLength = new TreeMap<>();
        grouped.forEach((len, list) -> byLength.put(len, toIntArray(list)));

        return new Dictionary(words, masks, weights, Map.copyOf(byLength));
    }

    /** Number of distinct words. */
    public int size() {
        return words.length;
    }

    /** The word at {@code index}. */
    public String wordAt(int index) {
        return words[index];
    }

    /** Precomputed 26-bit letter mask of the word at {@code index}. */
    public int maskAt(int index) {
        return masks[index];
    }

    /** Prior weight of the word at {@code index}; always positive. */
    public int weightAt(int index) {
        return weights[index];
    }

    /** Sum of every word's weight. Accumulated in {@code long} to avoid overflow. */
    public long totalWeight() {
        return totalWeight;
    }

    /**
     * Indices of all words exactly {@code length} letters long, or an empty array if none.
     *
     * <p>The returned array is a copy; callers may keep or discard it freely.
     */
    public int[] indicesOfLength(int length) {
        int[] found = indicesByLength.get(length);
        return found == null ? NO_INDICES : found.clone();
    }

    /** Every word length present in the dictionary, ascending. */
    public Set<Integer> lengths() {
        return indicesByLength.keySet();
    }

    /**
     * The 26-bit letter mask of {@code word}: bit {@code i} is set when {@code 'A' + i} occurs.
     *
     * @throws IllegalArgumentException if {@code word} is blank or not pure {@code A-Z}
     */
    public static int maskOf(String word) {
        validate(word);
        int mask = 0;
        for (int i = 0; i < word.length(); i++) {
            mask |= 1 << (word.charAt(i) - 'A');
        }
        return mask;
    }

    private static void validate(String word) {
        if (word == null || word.isEmpty()) {
            throw new IllegalArgumentException("word must be non-empty");
        }
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 'A' || c > 'Z') {
                throw new IllegalArgumentException(
                        "word must contain only A-Z, found '" + c + "' in \"" + word + '"');
            }
        }
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    @Override
    public String toString() {
        return "Dictionary[size=" + words.length + ", lengths="
                + indicesByLength.keySet() + ", totalWeight=" + totalWeight + ']';
    }

    /** Lengths present, ascending, as an array. Handy for reports. */
    public int[] lengthsArray() {
        int[] out = new int[indicesByLength.size()];
        int i = 0;
        for (Integer len : indicesByLength.keySet()) {
            out[i++] = len;
        }
        Arrays.sort(out);
        return out;
    }
}
