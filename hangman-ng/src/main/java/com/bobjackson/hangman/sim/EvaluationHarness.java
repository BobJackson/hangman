package com.bobjackson.hangman.sim;

import com.bobjackson.hangman.api.GameResult;
import com.bobjackson.hangman.api.HangmanClient;
import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.RankWordWeighting;
import com.bobjackson.hangman.engine.GameEngine;
import com.bobjackson.hangman.engine.GameListener;
import com.bobjackson.hangman.solver.EliminationSolver;
import com.bobjackson.hangman.solver.LetterSelector;
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
 * <p>The dictionary, the ranking and the pool are built once and shared by every session
 * and every variant. That is the point of the whole exercise: the legacy client rebuilt a
 * 416k-word list before each of its 80 words, and mutated it while filtering.
 */
public final class EvaluationHarness {

    private static final System.Logger LOG = System.getLogger(EvaluationHarness.class.getName());

    /** Stride between per-session seeds; odd and large enough that streams do not overlap. */
    private static final long GAME_SEED_STRIDE = 1_000_003L;

    /**
     * A named letter-selection strategy.
     *
     * <p>Only the selector varies. The elimination logic, the engine and the transport are
     * identical across variants, so a score difference is attributable to the heuristic
     * alone — which is the entire reason the design separates "which words are possible"
     * from "which letter is worth asking".
     */
    public record Variant(String name, LetterSelector selector) {

        public Variant {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(selector, "selector");
            if (name.isBlank()) {
                throw new IllegalArgumentException("variant name must not be blank");
            }
        }

        /** Binds this strategy to a dictionary, producing fresh solvers per word. */
        public SolverFactory toFactory(Dictionary dictionary) {
            return (firstState, wrongGuessesAllowed) ->
                    EliminationSolver.forWord(dictionary, selector, firstState, wrongGuessesAllowed);
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
        Objects.requireNonNull(variant, "variant");
        SolverFactory factory = variant.toFactory(dictionary);

        List<GameResult> results = new ArrayList<>(config.games());
        List<BenchmarkReport.WordOutcome> outcomes =
                new ArrayList<>(config.games() * config.wordsPerGame());

        for (int session = 0; session < config.games(); session++) {
            SimulationConfig sessionConfig = config.withSeed(config.seed() + session * GAME_SEED_STRIDE);
            Recorder recorder = new Recorder();
            try (HangmanClient client = clientFor(sessionConfig)) {
                GameEngine engine = new GameEngine(client, factory, recorder);
                results.add(engine.play("bobjackson-" + (session + 1)));
            }
            outcomes.addAll(recorder.outcomes);
        }

        return new BenchmarkReport(variant.name(), config, results, outcomes);
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

    /** Captures one outcome per finished word; the engine calls this exactly once per word. */
    private static final class Recorder implements GameListener {

        private final List<BenchmarkReport.WordOutcome> outcomes = new ArrayList<>();

        @Override
        public void onWordFinished(WordState state, int candidateCount, int guessesUsed) {
            outcomes.add(new BenchmarkReport.WordOutcome(
                    state.totalWordCount(),
                    state.length(),
                    state.solved(),
                    state.wrongGuessCountOfCurrentWord(),
                    guessesUsed,
                    candidateCount));
        }
    }
}
