package com.bobjackson.hangman.solver;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * Counts where misses come from, and whether they were priced correctly when they happened.
 *
 * <h2>The question it answers</h2>
 * The tier-1 gap of 195 points per session decomposes into 132.8 points of tier-1 misses, and
 * misses are already spent — they cannot be recovered, only understood. The useful split is
 * therefore not "how many" but "were they avoidable": a miss that the model itself gave a 30%
 * chance of happening is the price of guessing, while a miss the model gave a 90% chance of
 * avoiding is evidence that the model is wrong.
 *
 * <h2>Why the predicted column is comparable at all</h2>
 * The letters, their {@code P(hit)}, and the simulator's answer sampling all come from the same
 * {@link com.bobjackson.hangman.dict.RankWordWeighting}. A calibrated probe therefore has to
 * show observed miss rates tracking {@code 1 - mean(P(hit))} row by row. Any row that does not
 * is a real defect, not noise, and the row names the defect's location.
 *
 * <h2>Reading a delta</h2>
 * Rows are not equally loud. A ten-decision row can swing by 0.3 and mean nothing; read the
 * delta against {@code 1.96 * sqrt(predicted * (1 - predicted) / decisions)}. That is stated
 * here rather than drawn as a column because it is a judgement about a number the reader already
 * has, not a second measurement.
 */
public final class MissAnatomySummary {

    /** Guess indices at or beyond this are pooled, so the second table stays readable. */
    private static final int LAST_INDEX_BUCKET = 10;

    /** How many {@code P(hit)} buckets the third table has; tenths, low to high. */
    private static final int HIT_BUCKETS = 10;

    /**
     * One guess and what came of it; the unit every table is built from.
     *
     * @param wordLength       letters in the answer, i.e. which difficulty tier this is
     * @param guessIndex       1-based position of this guess within the word
     * @param candidatesBefore words still consistent when the letter was chosen
     * @param missesLeftBefore misses still available when the letter was chosen
     * @param letter           what was actually asked
     * @param pHit             the model's own estimate that this letter would hit
     * @param hit              what the server said
     */
    public record GuessSample(int wordLength, int guessIndex, int candidatesBefore,
                              int missesLeftBefore, char letter, double pHit, boolean hit) {

        public GuessSample {
            if (wordLength <= 0) {
                throw new IllegalArgumentException("wordLength must be positive: " + wordLength);
            }
            if (guessIndex <= 0) {
                throw new IllegalArgumentException("guessIndex must be positive: " + guessIndex);
            }
            if (candidatesBefore < 0) {
                throw new IllegalArgumentException("candidatesBefore must not be negative: " + candidatesBefore);
            }
            if (missesLeftBefore < 0) {
                throw new IllegalArgumentException("missesLeftBefore must not be negative: " + missesLeftBefore);
            }
            if (letter < 'A' || letter > 'Z') {
                throw new IllegalArgumentException("letter must be A-Z, got '" + letter + '\'');
            }
            // Written as a negation so a NaN fails the check too.
            if (!(pHit >= 0.0 && pHit <= 1.0)) {
                throw new IllegalArgumentException("pHit must be within [0, 1]: " + pHit);
            }
        }
    }

    private final Map<Integer, Bucket> byLength = new TreeMap<>();
    private final Map<Integer, Bucket> byIndex = new TreeMap<>();
    private final Map<Integer, Bucket> byHitChance = new TreeMap<>();

    private long guesses;
    private long misses;
    private double predictedMisses;

    /** Records one guess. Called from every session thread, hence the lock. */
    public synchronized void record(GuessSample sample) {
        Objects.requireNonNull(sample, "sample");
        guesses++;
        predictedMisses += 1.0 - sample.pHit();
        if (!sample.hit()) {
            misses++;
        }
        bucket(byLength, sample.wordLength()).add(sample);
        bucket(byIndex, Math.min(sample.guessIndex(), LAST_INDEX_BUCKET)).add(sample);
        bucket(byHitChance, hitChanceBucket(sample.pHit())).add(sample);
    }

    /** Every guess seen so far. */
    public synchronized long guesses() {
        return guesses;
    }

    /** Every guess the server answered with a miss. */
    public synchronized long misses() {
        return misses;
    }

    /** The misses the model expected, summed over every recorded guess. */
    public synchronized double predictedMisses() {
        return predictedMisses;
    }

    /**
     * The probe as an ASCII report: one headline, then the same four numbers split three ways.
     */
    public synchronized String describe() {
        if (guesses == 0) {
            return "miss anatomy: no guess was recorded\n";
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT,
                "miss anatomy: %d guesses, %d misses (%.1f%%), model predicted %.1f%%%n",
                guesses, misses, 100.0 * misses / guesses, 100.0 * predictedMisses / guesses));

        table(out, "by word length", byLength, String::valueOf);
        table(out, "by guess index", byIndex,
                index -> index >= LAST_INDEX_BUCKET ? LAST_INDEX_BUCKET + "+" : String.valueOf(index));
        table(out, "by P(hit) tenth", byHitChance, MissAnatomySummary::hitChanceLabel);

        out.append(String.format(Locale.ROOT,
                "%n  Rows are not equally loud. Read a delta against 1.96*sqrt(p*(1-p)/n):%n"
                        + "  a ten-decision row can swing by 0.3 and mean nothing.%n"));
        return out.toString();
    }

    private static void table(StringBuilder out, String title, Map<Integer, Bucket> buckets,
                              IntFunction<String> label) {
        out.append(String.format(Locale.ROOT, "%n  %-16s %10s %10s %10s %8s%n",
                title, "decisions", "observed", "predicted", "delta"));
        for (Map.Entry<Integer, Bucket> entry : buckets.entrySet()) {
            Bucket bucket = entry.getValue();
            out.append(String.format(Locale.ROOT, "  %-16s %10d %10.3f %10.3f %+8.3f%n",
                    label.apply(entry.getKey()), bucket.decisions,
                    bucket.missRate(), bucket.predictedRate(), bucket.delta()));
        }
    }

    private static Bucket bucket(Map<Integer, Bucket> buckets, int key) {
        return buckets.computeIfAbsent(key, unused -> new Bucket());
    }

    /** Ten tenths, with a certain hit landing in the top one rather than in an eleventh. */
    private static int hitChanceBucket(double pHit) {
        return Math.min(HIT_BUCKETS - 1, (int) (pHit * HIT_BUCKETS));
    }

    /** {@code 0.3-0.4}: lower bound inclusive, upper bound exclusive, except the last bucket. */
    private static String hitChanceLabel(int bucket) {
        return String.format(Locale.ROOT, "%.1f-%.1f",
                (double) bucket / HIT_BUCKETS, (double) (bucket + 1) / HIT_BUCKETS);
    }

    /** One row of every table: what happened against what the model said would happen. */
    private static final class Bucket {

        private long decisions;
        private long misses;
        private double predictedMisses;

        void add(GuessSample sample) {
            decisions++;
            if (!sample.hit()) {
                misses++;
            }
            predictedMisses += 1.0 - sample.pHit();
        }

        double missRate() {
            return (double) misses / decisions;
        }

        double predictedRate() {
            return predictedMisses / decisions;
        }

        /** Positive means the model was too confident: more misses than it promised. */
        double delta() {
            return missRate() - predictedRate();
        }
    }
}
