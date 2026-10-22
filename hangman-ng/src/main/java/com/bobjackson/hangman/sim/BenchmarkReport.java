package com.bobjackson.hangman.sim;

import com.bobjackson.hangman.api.GameResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One measurement: every session produced by a single solver variant under a single
 * {@link SimulationConfig}.
 *
 * <p>A single score is not a measurement. The official instructions already show a
 * 19-point swing between two runs of the same program — 1326 and 1307 — purely from which
 * words were dealt. This report therefore carries the distribution (mean, median, sample
 * standard deviation, minimum, maximum) next to per-tier detail, because an aggregate such
 * as "93% correct" hides the fact that almost all of the misses usually come from one tier.
 *
 * <p>Everything here is derived once in the constructor and immutable afterwards: a report
 * that recomputed its statistics on every accessor would be doing that work inside a
 * benchmark loop.
 */
public final class BenchmarkReport {

    /**
     * What happened to one word.
     *
     * @param wordIndex       1-based position within its session; this is what decides the tier
     * @param length          letters in the answer
     * @param solved          whether the answer was fully revealed
     * @param wrongGuesses    misses charged against this word
     * @param guessesUsed     letters spent on this word, hit or miss
     * @param finalCandidates dictionary words still consistent when the word ended
     * @param abandoned       true when the solver gave up rather than running out of misses
     */
    public record WordOutcome(int wordIndex, int length, boolean solved,
                              int wrongGuesses, int guessesUsed, int finalCandidates,
                              boolean abandoned) {

        public WordOutcome {
            if (wordIndex <= 0) {
                throw new IllegalArgumentException("wordIndex is 1-based: " + wordIndex);
            }
            if (length <= 0) {
                throw new IllegalArgumentException("length must be positive: " + length);
            }
            if (wrongGuesses < 0 || guessesUsed < 0 || finalCandidates < 0) {
                throw new IllegalArgumentException("counters must not be negative");
            }
            if (guessesUsed < wrongGuesses) {
                throw new IllegalArgumentException(
                        "guessesUsed " + guessesUsed + " is less than wrongGuesses " + wrongGuesses);
            }
            if (abandoned && solved) {
                throw new IllegalArgumentException("a solved word cannot have been abandoned");
            }
        }

        /**
         * True when no dictionary word could explain the evidence, which means the answer was
         * simply not in our word list. The engine cannot distinguish this case from a hard
         * word, which is exactly why it is reported separately.
         */
        public boolean notInDictionary() {
            return finalCandidates == 0;
        }
    }

    /** Aggregate behaviour on one difficulty tier. */
    public record TierStats(String label, int words, int solved, int wrongGuesses,
                            int guessesUsed, int notInDictionary, int abandoned) {

        /** Fraction of words on this tier that were fully revealed, in [0,1]. */
        public double solveRate() {
            return words == 0 ? 0.0 : (double) solved / words;
        }

        /** Mean misses charged per word on this tier. */
        public double wrongPerWord() {
            return words == 0 ? 0.0 : (double) wrongGuesses / words;
        }

        /** Mean letters spent per word on this tier. */
        public double guessesPerWord() {
            return words == 0 ? 0.0 : (double) guessesUsed / words;
        }

        /** Fraction of words whose answer the dictionary did not contain, in [0,1]. */
        public double notInDictionaryRate() {
            return words == 0 ? 0.0 : (double) notInDictionary / words;
        }

        /** Fraction of words given up on before the misses ran out, in [0,1]. */
        public double abandonedRate() {
            return words == 0 ? 0.0 : (double) abandoned / words;
        }
    }

    private final String label;
    private final SimulationConfig config;
    private final int attempts;
    private final List<GameResult> games;
    private final List<WordOutcome> words;
    private final List<TierStats> tiers;

    private final int totalWords;
    private final int totalCorrect;
    private final int totalWrong;
    private final int totalGuesses;
    private final int notInDictionary;

    private final double meanScore;
    private final double medianScore;
    private final double sampleStdDevScore;
    private final int minScore;
    private final int maxScore;

    /** A report of a single attempt per session. */
    public BenchmarkReport(String label, SimulationConfig config,
                           List<GameResult> games, List<WordOutcome> words) {
        this(label, config, 1, games, words);
    }

    /**
     * @param label    variant name, e.g. {@code "coverage"}
     * @param config   the configuration the sessions were run under
     * @param attempts how many independent sessions were played per reported game
     * @param games    one result per game — the attempt that scored highest; must not be empty
     * @param words    one outcome per attempted word, for the attempts that were kept
     */
    public BenchmarkReport(String label, SimulationConfig config, int attempts,
                           List<GameResult> games, List<WordOutcome> words) {
        if (games.isEmpty()) {
            throw new IllegalArgumentException("a report needs at least one session");
        }
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1: " + attempts);
        }
        this.label = label;
        this.config = config;
        this.attempts = attempts;
        this.games = List.copyOf(games);
        this.words = List.copyOf(words);

        int correct = 0;
        int wrong = 0;
        int guesses = 0;
        int blind = 0;
        for (WordOutcome w : this.words) {
            if (w.solved()) {
                correct++;
            }
            wrong += w.wrongGuesses();
            guesses += w.guessesUsed();
            if (w.notInDictionary()) {
                blind++;
            }
        }
        this.totalWords = this.words.size();
        this.totalCorrect = correct;
        this.totalWrong = wrong;
        this.totalGuesses = guesses;
        this.notInDictionary = blind;

        int[] scores = new int[this.games.size()];
        long sum = 0;
        for (int i = 0; i < scores.length; i++) {
            scores[i] = this.games.get(i).score();
            sum += scores[i];
        }
        Arrays.sort(scores);
        this.minScore = scores[0];
        this.maxScore = scores[scores.length - 1];
        this.meanScore = (double) sum / scores.length;
        int mid = scores.length / 2;
        this.medianScore = scores.length % 2 == 1
                ? scores[mid]
                : (scores[mid - 1] + scores[mid]) / 2.0;

        double squaredError = 0.0;
        for (int score : scores) {
            double delta = score - meanScore;
            squaredError += delta * delta;
        }
        // Sample standard deviation (n-1): these sessions are a sample of all possible games,
        // not the whole population. Undefined for a single session, where 0 is the honest answer.
        this.sampleStdDevScore = scores.length < 2 ? 0.0 : Math.sqrt(squaredError / (scores.length - 1));

        this.tiers = buildTiers(config, this.words);
    }

    /** Variant name this report was produced for. */
    public String label() {
        return label;
    }

    public SimulationConfig config() {
        return config;
    }

    /** How many independent sessions were played per reported game; 1 for a plain run. */
    public int attempts() {
        return attempts;
    }

    public List<GameResult> games() {
        return games;
    }

    public List<WordOutcome> words() {
        return words;
    }

    public List<TierStats> tiers() {
        return tiers;
    }

    public int totalWords() {
        return totalWords;
    }

    public int totalCorrect() {
        return totalCorrect;
    }

    public int totalWrong() {
        return totalWrong;
    }

    public int totalGuesses() {
        return totalGuesses;
    }

    /** Words whose answer was not in the dictionary; expected to be 0 for a correctly built pool. */
    public int notInDictionary() {
        return notInDictionary;
    }

    /** Fraction of attempted words revealed, in [0,1]. */
    public double solveRate() {
        return totalWords == 0 ? 0.0 : (double) totalCorrect / totalWords;
    }

    /** Total misses divided by words attempted — the divisor in the scoring formula. */
    public double wrongPerWord() {
        return totalWords == 0 ? 0.0 : (double) totalWrong / totalWords;
    }

    /** Total letters spent divided by words attempted. */
    public double guessesPerWord() {
        return totalWords == 0 ? 0.0 : (double) totalGuesses / totalWords;
    }

    public double meanScore() {
        return meanScore;
    }

    public double medianScore() {
        return medianScore;
    }

    public double sampleStdDevScore() {
        return sampleStdDevScore;
    }

    public int minScore() {
        return minScore;
    }

    public int maxScore() {
        return maxScore;
    }

    /** Mean score per word; useful when comparing differently sized benchmarks. */
    public double meanScorePerWord() {
        return totalWords == 0 ? 0.0 : meanScore / totalWords;
    }

    /**
     * The full report, as a fixed-width table.
     *
     * <p>ASCII only, deliberately: this ends up in terminals, CI logs and issue trackers,
     * and a box-drawing character that renders as mojibake is worse than no formatting.
     */
    public String toTable() {
        StringBuilder out = new StringBuilder(1024);
        out.append("=== ").append(label).append(" | ").append(configSummary()).append(" ===\n");
        out.append(String.format(Locale.ROOT,
                "sessions %d   words %d   correct %d (%.1f%%)   wrong %d (%.2f/word)%n",
                games.size(), totalWords, totalCorrect, 100.0 * solveRate(),
                totalWrong, wrongPerWord()));
        out.append(String.format(Locale.ROOT,
                "score    mean %.2f   median %.2f   sd %.2f   min %d   max %d%n",
                meanScore, medianScore, sampleStdDevScore, minScore, maxScore));
        out.append(String.format(Locale.ROOT, "%-14s %6s %7s %7s %11s %8s %7s %6s%n",
                "tier", "words", "solved", "rate", "wrong/word", "guesses", "skipped", "blind"));
        for (TierStats tier : tiers) {
            out.append(String.format(Locale.ROOT, "%-14s %6d %7d %6.1f%% %11.2f %8.2f %6.1f%% %5.1f%%%n",
                    tier.label(), tier.words(), tier.solved(), 100.0 * tier.solveRate(),
                    tier.wrongPerWord(), tier.guessesPerWord(), 100.0 * tier.abandonedRate(),
                    100.0 * tier.notInDictionaryRate()));
        }
        out.append("blind = words whose answer was not in the dictionary; 0 means the pool was built correctly")
                .append(System.lineSeparator());
        return out.toString();
    }

    /** One line, so several variants can be compared without scrolling. */
    public String toSummaryLine() {
        return String.format(Locale.ROOT, "%-14s score %8.2f (sd %6.2f)   correct %5.1f%%   wrong/word %.2f",
                label, meanScore, sampleStdDevScore, 100.0 * solveRate(), wrongPerWord());
    }

    private String configSummary() {
        StringBuilder out = new StringBuilder();
        out.append(games.size()).append(" sessions x ").append(config.wordsPerGame()).append(" words");
        out.append(", ").append(config.sampling().name().toLowerCase(Locale.ROOT)).append(" sampling");
        out.append(", ").append(config.difficulty().name().toLowerCase(Locale.ROOT)).append(" tiers");
        if (config.failureRate() > 0.0) {
            out.append(String.format(Locale.ROOT, ", injected faults %.2f", config.failureRate()));
        }
        if (attempts > 1) {
            out.append(", best of ").append(attempts);
        }
        out.append(", seed ").append(config.seed());
        return out.toString();
    }

    private static List<TierStats> buildTiers(SimulationConfig config, List<WordOutcome> words) {
        int tierCount = SimulationConfig.TIERS;
        int[] counts = new int[tierCount];
        int[] solved = new int[tierCount];
        int[] wrong = new int[tierCount];
        int[] guesses = new int[tierCount];
        int[] blind = new int[tierCount];
        int[] abandoned = new int[tierCount];

        for (WordOutcome word : words) {
            int tier = config.tierOf(word.wordIndex());
            counts[tier]++;
            if (word.solved()) {
                solved[tier]++;
            }
            wrong[tier] += word.wrongGuesses();
            guesses[tier] += word.guessesUsed();
            if (word.notInDictionary()) {
                blind[tier]++;
            }
            if (word.abandoned()) {
                abandoned[tier]++;
            }
        }

        List<TierStats> stats = new ArrayList<>(tierCount);
        for (int tier = 0; tier < tierCount; tier++) {
            stats.add(new TierStats(config.tierLabel(tier), counts[tier], solved[tier],
                    wrong[tier], guesses[tier], blind[tier], abandoned[tier]));
        }
        return List.copyOf(stats);
    }

    @Override
    public String toString() {
        return "BenchmarkReport[label=" + label + ", sessions=" + games.size()
                + ", meanScore=" + String.format(Locale.ROOT, "%.2f", meanScore)
                + ", solveRate=" + String.format(Locale.ROOT, "%.1f%%", 100.0 * solveRate()) + ']';
    }
}
