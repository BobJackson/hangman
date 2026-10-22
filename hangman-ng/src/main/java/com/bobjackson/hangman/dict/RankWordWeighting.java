package com.bobjackson.hangman.dict;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A frequency-ranked word list, used for two distinct jobs:
 *
 * <ol>
 *   <li><b>Answer pool</b> — {@link #topN(int)} hands the simulator the most common words,
 *       which is a far better model of what a real server asks for than sampling the raw
 *       416k dictionary uniformly.</li>
 *   <li><b>Solver priors</b> — as a {@link WordWeighting}, so a candidate like
 *       {@code ZRICH} (an ASCII-mangled "Zürich" that survives in {@code words.txt}) is
 *       kept but effectively ignored, rather than being deleted and risking a miss.</li>
 * </ol>
 *
 * <p>The backing list is sorted by descending corpus frequency, so a word's 1-based
 * position <em>is</em> its rank. Weight decays as {@code scale / rank^exponent}, clamped
 * to {@code floorWeight}. The default exponent of 0.5 is deliberately tempered: a pure
 * Zipf exponent of 1.0 would let the top few words swallow the entire distribution, while
 * 0.0 removes the ranking signal entirely and collapses to a uniform belief.
 */
public final class RankWordWeighting implements WordWeighting {

    /** Classpath location of the artifact produced by {@code tools/fetch-data.sh}. */
    public static final String DEFAULT_RESOURCE = "/word-ranks.txt.gz";

    /** Default Zipf tempering exponent; 0 = uniform, 1 = pure Zipf. */
    public static final double DEFAULT_EXPONENT = 0.5;

    /** Numerator of the weight formula. Large enough that the floor stays negligible. */
    public static final int DEFAULT_SCALE = 1_000_000;

    /** Weight given to words absent from the ranked list. */
    public static final int DEFAULT_FLOOR_WEIGHT = 1;

    private final List<String> byRank;
    private final Map<String, Integer> rankOfWord;
    private final double exponent;
    private final int scale;
    private final int floorWeight;
    private final int[] weightByRank;

    private RankWordWeighting(List<String> byRank, double exponent, int scale, int floorWeight) {
        if (byRank.isEmpty()) {
            throw new IllegalArgumentException("ranked word list must not be empty");
        }
        if (exponent < 0) {
            throw new IllegalArgumentException("exponent must not be negative: " + exponent);
        }
        if (scale <= 0 || floorWeight <= 0) {
            throw new IllegalArgumentException("scale and floorWeight must be positive");
        }
        this.byRank = Collections.unmodifiableList(new ArrayList<>(byRank));
        this.exponent = exponent;
        this.scale = scale;
        // An exponent of zero means "the ranking carries no information". Leaving the floor at
        // its usual 1 would still push every unlisted word below every listed one, so the knob
        // would never reach the belief it advertises. Flattening the floor too makes exponent 0
        // collapse exactly onto WordWeighting.uniform(), which is what a scan's left endpoint
        // has to be if the curve is to be readable.
        this.floorWeight = exponent == 0.0 ? scale : floorWeight;

        this.rankOfWord = new HashMap<>(byRank.size() * 2);
        for (int i = 0; i < byRank.size(); i++) {
            this.rankOfWord.putIfAbsent(byRank.get(i), i + 1);
        }
        this.weightByRank = new int[byRank.size() + 1];
        for (int rank = 1; rank < weightByRank.length; rank++) {
            weightByRank[rank] = weightForRank(rank);
        }
    }

    /** Loads {@value #DEFAULT_RESOURCE} from the classpath with default tuning. */
    public static RankWordWeighting fromClasspath() {
        return fromClasspath(DEFAULT_EXPONENT);
    }

    /**
     * Loads {@value #DEFAULT_RESOURCE} with a custom prior strength, other tuning at defaults.
     *
     * <p>Choosing {@code 0.0} makes every word carry the same weight — including words the
     * ranked list does not contain — so the result is the same belief as
     * {@link WordWeighting#uniform()}, merely expressed through the rank machinery.
     */
    public static RankWordWeighting fromClasspath(double exponent) {
        return fromClasspath(DEFAULT_RESOURCE, exponent, DEFAULT_SCALE, DEFAULT_FLOOR_WEIGHT);
    }

    /** Loads a ranked list from the classpath. Lines are read in order; rank = line number. */
    public static RankWordWeighting fromClasspath(String resource, double exponent, int scale, int floorWeight) {
        InputStream in = RankWordWeighting.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException(
                    "missing classpath resource " + resource + "; run tools/fetch-data.sh to generate it");
        }
        List<String> words = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String w = line.strip();
                if (!w.isEmpty()) {
                    words.add(w);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + resource, e);
        }
        return new RankWordWeighting(words, exponent, scale, floorWeight);
    }

    /** Builds a weighting from an explicit rank-ordered list. Mostly for tests. */
    public static RankWordWeighting of(List<String> rankOrderedWords) {
        return of(rankOrderedWords, DEFAULT_EXPONENT);
    }

    /** Builds a weighting with a custom exponent. Mostly for tests. */
    public static RankWordWeighting of(List<String> rankOrderedWords, double exponent) {
        return new RankWordWeighting(rankOrderedWords, exponent, DEFAULT_SCALE, DEFAULT_FLOOR_WEIGHT);
    }

    @Override
    public int weightOf(String word) {
        Integer rank = rankOfWord.get(word);
        return rank == null ? floorWeight : weightByRank[rank];
    }

    /** 1-based frequency rank, or {@code -1} when the word is not in the ranked list. */
    public int rankOf(String word) {
        return rankOfWord.getOrDefault(word, -1);
    }

    /** How many words carry an explicit rank. */
    public int size() {
        return byRank.size();
    }

    /**
     * The {@code n} most frequent words, in rank order — the simulator's answer pool.
     *
     * @throws IllegalArgumentException if {@code n} is not positive
     */
    public List<String> topN(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive: " + n);
        }
        return List.copyOf(byRank.subList(0, Math.min(n, byRank.size())));
    }

    private int weightForRank(int rank) {
        double raw = scale / Math.pow(rank, exponent);
        long rounded = Math.round(raw);
        if (rounded < floorWeight) {
            return floorWeight;
        }
        return rounded > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rounded;
    }

    @Override
    public String toString() {
        return "rank[size=" + byRank.size() + ", exponent=" + exponent + ", scale=" + scale + ']';
    }
}
