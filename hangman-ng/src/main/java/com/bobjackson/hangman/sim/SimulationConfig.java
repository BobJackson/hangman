package com.bobjackson.hangman.sim;

/**
 * Knobs for an offline evaluation run.
 *
 * <p>Three of these exist specifically to measure how sensitive a result is to assumptions
 * we cannot verify, because the original server is long gone:
 * {@link Sampling}, {@link DifficultySemantics} and {@code poolSize}. Reporting a single
 * number under one set of guesses would be misleading; the harness sweeps them instead.
 *
 * @param poolSize          how many of the most frequent words may be served as answers
 * @param sampling          how an answer is drawn from a tier's pool
 * @param difficulty        how the four documented difficulty tiers are interpreted
 * @param games             how many full sessions make up one measurement
 * @param seed              master seed; every run is reproducible from it
 * @param failureRate       probability that any single call fails, mimicking the overloaded server
 * @param wordsPerGame      words per session, normally the documented 80
 * @param wrongGuessesAllowed misses allowed per word, normally the documented 10
 */
public record SimulationConfig(int poolSize,
                               Sampling sampling,
                               DifficultySemantics difficulty,
                               int games,
                               long seed,
                               double failureRate,
                               int wordsPerGame,
                               int wrongGuessesAllowed) {

    /** How an answer word is drawn from its tier. */
    public enum Sampling {
        /** Probability proportional to corpus frequency. Models a server that asks for words, not noise. */
        WEIGHTED,
        /** Every pool member equally likely. The pessimistic, high-variance alternative. */
        UNIFORM
    }

    /**
     * How to read the four documented tiers.
     *
     * <p>The instructions give upper bounds only — "1st to 20th word: length &lt;= 5",
     * "21st to 40th: length &lt;= 8" — which literally means a tier-2 word may still be
     * three letters long. The legacy implementation instead treated them as disjoint
     * buckets (6-8 for tier 2). Both are modelled because only one of them was ever played,
     * and a replay of the legacy client is what says which: under cumulative tiers it scores
     * 5.25 standard deviations below the 1326 it actually scored, under buckets it lands on
     * the mean.
     */
    public enum DifficultySemantics {
        /** Literal reading: tier bounds accumulate. Never what the server did. */
        CUMULATIVE,
        /** Disjoint ranges: 3-5, 6-8, 9-12, 13+. The default, because the server used it. */
        BUCKETED
    }

    /** Number of documented difficulty tiers. */
    public static final int TIERS = 4;

    public SimulationConfig {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive: " + poolSize);
        }
        if (games <= 0) {
            throw new IllegalArgumentException("games must be positive: " + games);
        }
        if (failureRate < 0 || failureRate >= 1) {
            throw new IllegalArgumentException("failureRate must be in [0,1): " + failureRate);
        }
        if (wordsPerGame <= 0 || wordsPerGame % TIERS != 0) {
            throw new IllegalArgumentException(
                    "wordsPerGame must be a positive multiple of " + TIERS + ": " + wordsPerGame);
        }
        if (wrongGuessesAllowed <= 0) {
            throw new IllegalArgumentException("wrongGuessesAllowed must be positive: " + wrongGuessesAllowed);
        }
    }

    /**
     * The defaults used for the headline baseline: the documented 80 words and 10 misses,
     * twenty sessions per measurement, weighted sampling, bucketed tiers because that is what
     * the original server did, and no injected faults — failures are a separate robustness run
     * so the score stays clean.
     */
    public static SimulationConfig defaults() {
        return new SimulationConfig(100_000, Sampling.WEIGHTED, DifficultySemantics.BUCKETED,
                20, 42L, 0.0, 80, 10);
    }

    /** Which tier the {@code oneBasedIndex}-th word of the game belongs to, 0..3. */
    public int tierOf(int oneBasedIndex) {
        if (oneBasedIndex <= 0) {
            throw new IllegalArgumentException("word index is 1-based: " + oneBasedIndex);
        }
        int perTier = wordsPerGame / TIERS;
        return Math.min((oneBasedIndex - 1) / perTier, TIERS - 1);
    }

    /** Whether a word of this length is allowed in the given tier. */
    public boolean acceptsLength(int tier, int length) {
        return switch (difficulty) {
            case CUMULATIVE -> switch (tier) {
                case 0 -> length <= 5;
                case 1 -> length <= 8;
                case 2 -> length <= 12;
                default -> length > 12;
            };
            case BUCKETED -> switch (tier) {
                case 0 -> length <= 5;
                case 1 -> length >= 6 && length <= 8;
                case 2 -> length >= 9 && length <= 12;
                default -> length > 12;
            };
        };
    }

    /** Human-readable label for the tier, e.g. {@code "21-40 (<=8)"}. */
    public String tierLabel(int tier) {
        int perTier = wordsPerGame / TIERS;
        int first = tier * perTier + 1;
        int last = first + perTier - 1;
        String bound = switch (difficulty) {
            case CUMULATIVE -> switch (tier) {
                case 0 -> "<=5";
                case 1 -> "<=8";
                case 2 -> "<=12";
                default -> ">12";
            };
            case BUCKETED -> switch (tier) {
                case 0 -> "3-5";
                case 1 -> "6-8";
                case 2 -> "9-12";
                default -> "13+";
            };
        };
        return first + "-" + last + " (" + bound + ')';
    }

    /** Same configuration with a different seed. */
    public SimulationConfig withSeed(long newSeed) {
        return new SimulationConfig(poolSize, sampling, difficulty, games, newSeed,
                failureRate, wordsPerGame, wrongGuessesAllowed);
    }

    /** Same configuration with a different number of sessions. */
    public SimulationConfig withGames(int newGames) {
        return new SimulationConfig(poolSize, sampling, difficulty, newGames, seed,
                failureRate, wordsPerGame, wrongGuessesAllowed);
    }

    /** Same configuration with injected failures enabled. */
    public SimulationConfig withFailureRate(double newRate) {
        return new SimulationConfig(poolSize, sampling, difficulty, games, seed,
                newRate, wordsPerGame, wrongGuessesAllowed);
    }
}
