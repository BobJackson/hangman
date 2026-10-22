package com.bobjackson.hangman.sim;

import com.bobjackson.hangman.api.GameResult;
import com.bobjackson.hangman.api.HangmanClient;
import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.RankWordWeighting;
import com.bobjackson.hangman.engine.GameEngine;
import com.bobjackson.hangman.engine.GameListener;
import com.bobjackson.hangman.solver.EliminationSolver;
import com.bobjackson.hangman.solver.LetterSelector;
import com.bobjackson.hangman.solver.SkipPolicy;
import com.bobjackson.hangman.solver.SolverFactory;
import com.bobjackson.hangman.api.WordState;
import com.bobjackson.hangman.transport.RetryingHangmanClient;
import com.bobjackson.hangman.transport.Sleeper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;

/**
 * Runs a solver variant against {@link SimulatedServer} and collects a {@link BenchmarkReport}.
 *
 * <h2>Why the answer pool is intersected with the dictionary</h2>
 * Answers are drawn from the most frequent ranked words <em>that also exist in our
 * dictionary</em>. Serving a word the dictionary cannot contain measures corpus coverage,
 * not solver quality: every strategy would fail identically, and for a reason no strategy
 * can fix. The {@code blind} column stays in the report as the check that the intersection
 * did its job — anything other than 0% means the simulator served a word outside the
 * dictionary.
 *
 * <h2>Reproducibility</h2>
 * Each session gets its own seed, derived as {@code seed + i * 1_000_003}. Session 7 is
 * therefore identical whether the run had eight sessions or eight thousand, which makes a
 * single bad session reproducible on its own instead of "run it again and hope".
 *
 * <h2>Best of N</h2>
 * {@link #runBestOf} plays every game {@code attempts} times and keeps the highest score of
 * each group, modelling a server that keeps the best score submitted rather than the last
 * one. The extra attempts are played concurrently because they share nothing: each owns its
 * own {@link SimulatedServer}, {@link GameEngine} and {@link Recorder}, while the dictionary,
 * the ranking and the answer pool are read-only. Seeds are laid out so that the first
 * attempt of a group keeps the seed it would have had in a plain run
 * ({@code seed + (game + attempt * games) * stride}), which makes best-of-N strictly dominate
 * best-of-1 game by game and keeps every session reproducible by itself.
 *
 * <p>The dictionary, the ranking and the pool are built once and shared by every session
 * and every variant. That is the point of the whole exercise: the legacy client rebuilt a
 * 416k-word list before each of its 80 words, and mutated it while filtering.
 */
public final class EvaluationHarness {

    private static final System.Logger LOG = System.getLogger(EvaluationHarness.class.getName());

    /** Stride between per-session seeds; odd and large enough that streams do not overlap. */
    private static final long GAME_SEED_STRIDE = 1_000_003L;

    /**
     * A named strategy, as a binding from a dictionary to a solver factory.
     *
     * <p>Most variants differ only in {@link LetterSelector}, and {@link #selectingLetters}
     * spells that out. The binding is a function rather than a selector so that a strategy
     * with a different <em>structure</em> — the legacy reimplementation in {@code legacy} — can
     * run through the same engine, transport and report. Everything except the solver is
     * identical, so a score difference is attributable to the strategy alone, which is the
     * entire reason the design separates "which words are possible" from "which letter is
     * worth asking".
     */
    public record Variant(String name, Function<Dictionary, SolverFactory> binder) {

        public Variant {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(binder, "binder");
            if (name.isBlank()) {
                throw new IllegalArgumentException("variant name must not be blank");
            }
        }

        /** A variant that varies only which letter it asks for next. */
        public static Variant selectingLetters(String name, LetterSelector selector) {
            return selectingLetters(name, selector, SkipPolicy.NEVER);
        }

        /** A variant that varies the letters asked for and when a word is given up. */
        public static Variant selectingLetters(String name, LetterSelector selector, SkipPolicy skipPolicy) {
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(skipPolicy, "skipPolicy");
            return new Variant(name, dictionary -> (firstState, wrongGuessesAllowed) ->
                    EliminationSolver.forWord(dictionary, selector, firstState, wrongGuessesAllowed, skipPolicy));
        }

        /** Binds this strategy to a dictionary, producing fresh solvers per word. */
        public SolverFactory toFactory(Dictionary dictionary) {
            return binder.apply(dictionary);
        }
    }

    private final SimulationConfig config;
    private final Dictionary dictionary;
    private final RankWordWeighting ranking;
    private final List<String> answerPool;
    private final int droppedFromPool;

    /**
     * @param config    how many sessions, which seeds, which sampling and difficulty reading
     * @param dictionary the solver's word list; also the source of the letter statistics
     * @param ranking   corpus frequencies, used both for answer sampling and as a word source
     */
    public EvaluationHarness(SimulationConfig config, Dictionary dictionary, RankWordWeighting ranking) {
        this.config = Objects.requireNonNull(config, "config");
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
        this.ranking = Objects.requireNonNull(ranking, "ranking");

        List<String> requested = ranking.topN(config.poolSize());
        List<String> usable = intersect(requested, dictionary);
        if (usable.isEmpty()) {
            throw new IllegalStateException("no ranked word is present in the dictionary; "
                    + "check that the dictionary is uppercase A-Z");
        }
        this.answerPool = usable;
        this.droppedFromPool = requested.size() - usable.size();
        LOG.log(System.Logger.Level.DEBUG, "answer pool: {0} of {1} ranked words usable",
                usable.size(), requested.size());
    }

    public SimulationConfig config() {
        return config;
    }

    public Dictionary dictionary() {
        return dictionary;
    }

    /** How many words can actually be served: the ranked pool narrowed to known words. */
    public int answerPoolSize() {
        return answerPool.size();
    }

    /** Ranked words dropped because the dictionary has no entry for them. */
    public int droppedFromPool() {
        return droppedFromPool;
    }

    /** Runs one variant over every configured session and returns the aggregate. */
    public BenchmarkReport run(Variant variant) {
        return runBestOf(variant, 1);
    }

    /**
     * Plays each of the configured games {@code attempts} times and keeps the best score.
     *
     * <p>The attempts of one game are independent sessions, so they run concurrently; a
     * best-of-eight measurement therefore costs roughly one measurement's wall clock instead
     * of eight. {@code attempts == 1} runs on the calling thread and reproduces {@link #run}
     * exactly.
     *
     * <p>Only the winning attempt's word outcomes are reported. Mixing in the losers would
     * make the score line describe best-of-N while the solve rate and miss counts described
     * the average attempt, and the two would no longer be about the same sessions. The price
     * of that choice is that the per-tier columns describe a lucky session and read
     * optimistically; the score line is the unbiased number.
     *
     * @param attempts how many sessions to play per game; at least 1
     */
    public BenchmarkReport runBestOf(Variant variant, int attempts) {
        Objects.requireNonNull(variant, "variant");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1: " + attempts);
        }
        SolverFactory factory = variant.toFactory(dictionary);

        List<GameResult> results = new ArrayList<>(config.games());
        List<BenchmarkReport.WordOutcome> outcomes =
                new ArrayList<>(config.games() * config.wordsPerGame());

        ExecutorService pool = attempts == 1 ? null : newPool(attempts);
        try {
            for (int game = 0; game < config.games(); game++) {
                Attempt kept = pool == null
                        ? playAttempt(game, 0, factory, "bobjackson-" + (game + 1))
                        : bestOf(game, attempts, factory, pool);
                results.add(kept.result());
                outcomes.addAll(kept.outcomes());
            }
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
        }

        return new BenchmarkReport(variant.name(), config, attempts, results, outcomes);
    }

    /** Plays one game's attempts concurrently and keeps the highest-scoring one. */
    private Attempt bestOf(int game, int attempts, SolverFactory factory, ExecutorService pool) {
        List<Future<Attempt>> pending = new ArrayList<>(attempts);
        for (int attempt = 0; attempt < attempts; attempt++) {
            // Captured by the task below, so it has to be final.
            final int nth = attempt;
            String playerId = "bobjackson-" + (game + 1) + '-' + (nth + 1);
            pending.add(pool.submit(() -> playAttempt(game, nth, factory, playerId)));
        }

        Attempt best = null;
        for (Future<Attempt> future : pending) {
            Attempt candidate = await(future);
            // Ties keep the earlier attempt, so the winner is deterministic when two attempts
            // happen to score the same.
            if (best == null || candidate.result().score() > best.result().score()) {
                best = candidate;
            }
        }
        return best;
    }

    private Attempt playAttempt(int game, int attempt, SolverFactory factory, String playerId) {
        SimulationConfig sessionConfig = config.withSeed(
                config.seed() + (game + (long) attempt * config.games()) * GAME_SEED_STRIDE);
        Recorder recorder = new Recorder();
        GameResult result;
        try (HangmanClient client = clientFor(sessionConfig)) {
            result = new GameEngine(client, factory, recorder).play(playerId);
        }
        return new Attempt(result, List.copyOf(recorder.outcomes));
    }

    private static Attempt await(Future<Attempt> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a session", e);
        } catch (ExecutionException e) {
            // Rethrow what the session actually threw; wrapping it in a report would hide the
            // one diagnostic that matters.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static ExecutorService newPool(int threads) {
        ThreadFactory factory = Thread.ofPlatform().name("hangman-session-", 0).daemon(true).factory();
        return Executors.newFixedThreadPool(threads, factory);
    }

    /**
     * The configured client, wrapped in a retrying decorator when faults are injected.
     *
     * <p>Backoff is neutralised for simulation. The score is what is being measured, and
     * real sleeps would add wall-clock time without changing a single guess; the retry
     * <em>policy</em> is exercised by its own unit test instead.
     */
    private HangmanClient clientFor(SimulationConfig sessionConfig) {
        HangmanClient server = new SimulatedServer(sessionConfig, answerPool, ranking);
        if (sessionConfig.failureRate() == 0.0) {
            return server;
        }
        return new RetryingHangmanClient(server,
                RetryingHangmanClient.DEFAULT_MAX_ATTEMPTS,
                RetryingHangmanClient.DEFAULT_BASE_DELAY_MILLIS,
                RetryingHangmanClient.DEFAULT_MAX_DELAY_MILLIS,
                Sleeper.noop(), LOG);
    }

    /** Pool composition and per-tier capacity, so the per-tier results can be read in context. */
    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT,
                "answer pool: %d words from the top %d ranked (%d not in the dictionary, dropped)%n",
                answerPool.size(), Math.min(config.poolSize(), ranking.size()), droppedFromPool));

        for (int tier = 0; tier < SimulationConfig.TIERS; tier++) {
            int count = 0;
            long minLength = Long.MAX_VALUE;
            long maxLength = Long.MIN_VALUE;
            for (String word : answerPool) {
                if (config.acceptsLength(tier, word.length())) {
                    count++;
                    minLength = Math.min(minLength, word.length());
                    maxLength = Math.max(maxLength, word.length());
                }
            }
            out.append(String.format(Locale.ROOT, "  %-14s %6d words, length %d-%d%n",
                    config.tierLabel(tier), count, minLength, maxLength));
        }
        return out.toString();
    }

    private static List<String> intersect(List<String> candidates, Dictionary dictionary) {
        Set<String> known = new HashSet<>(dictionary.size() * 2);
        for (int i = 0; i < dictionary.size(); i++) {
            known.add(dictionary.wordAt(i));
        }
        List<String> kept = new ArrayList<>(candidates.size());
        for (String word : candidates) {
            if (known.contains(word)) {
                kept.add(word);
            }
        }
        return List.copyOf(kept);
    }

    /** One played session and what it produced; the unit that best-of-N compares. */
    private record Attempt(GameResult result, List<BenchmarkReport.WordOutcome> outcomes) {
    }

    /** Captures one outcome per finished word; the engine calls this exactly once per word. */
    private static final class Recorder implements GameListener {

        private final List<BenchmarkReport.WordOutcome> outcomes = new ArrayList<>();

        @Override
        public void onWordFinished(WordState state, int candidateCount, int guessesUsed, boolean abandoned) {
            outcomes.add(new BenchmarkReport.WordOutcome(
                    state.totalWordCount(),
                    state.length(),
                    state.solved(),
                    state.wrongGuessCountOfCurrentWord(),
                    guessesUsed,
                    candidateCount,
                    abandoned));
        }
    }
}
