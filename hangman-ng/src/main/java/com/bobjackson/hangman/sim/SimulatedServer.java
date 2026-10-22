package com.bobjackson.hangman.sim;

import com.bobjackson.hangman.api.GameResult;
import com.bobjackson.hangman.api.HangmanApiException;
import com.bobjackson.hangman.api.HangmanClient;
import com.bobjackson.hangman.api.StartGame;
import com.bobjackson.hangman.api.WordState;
import com.bobjackson.hangman.dict.RankWordWeighting;
import com.bobjackson.hangman.dict.WordWeighting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * An in-process stand-in for the game server, implementing the documented protocol.
 *
 * <p>It exists because the original endpoint — a 2014 Heroku app — is almost certainly
 * gone, and because a solver that can only be measured against a live server cannot be
 * measured at all: one game costs minutes, the score has huge variance, and nothing is
 * reproducible. Here a full 80-word session takes milliseconds and any experiment can be
 * replayed exactly from a seed.
 *
 * <h2>Fidelity choices</h2>
 * <ul>
 *   <li>Revealing a letter reveals <em>every</em> occurrence, as documented.</li>
 *   <li>Answers are drawn by corpus frequency, not uniformly. A uniform draw over 100k
 *       words would serve things a real product would never ask for, and would make every
 *       solver look equally bad for the wrong reason. {@link SimulationConfig.Sampling}
 *       lets the pessimistic uniform model be measured too.</li>
 *   <li>Difficulty tiers follow the documented word-index ranges. The ambiguity in whether
 *       the bounds accumulate is modelled explicitly — see
 *       {@link SimulationConfig.DifficultySemantics}.</li>
 *   <li>Faults are injected <b>before</b> any state change, so a retried call is safe and
 *       cannot double-count a guess.</li>
 *   <li>A repeated wrong letter costs another miss, because the server has no memory of
 *       what was already asked. Our solver never repeats, so this only affects probing.</li>
 * </ul>
 */
public final class SimulatedServer implements HangmanClient {

    private static final System.Logger LOG = System.getLogger(SimulatedServer.class.getName());

    private final SimulationConfig config;
    private final TierPool[] poolsByTier;
    private final Random answerRandom;
    private final Random faultRandom;
    private final Random sessionRandom;

    private boolean started;
    private boolean submitted;
    private String sessionId;
    private String answer;
    private char[] mask;
    private int wrongCount;
    private int totalWordCount;
    private int correctWordCount;
    private int totalWrongGuesses;

    /** Uses the top {@link SimulationConfig#poolSize()} words as the answer pool. */
    public SimulatedServer(SimulationConfig config, RankWordWeighting ranked) {
        this(config, ranked.topN(config.poolSize()), ranked);
    }

    /** Uses an explicit answer pool; both sampling weights and dictionary weights come from {@code weighting}. */
    public SimulatedServer(SimulationConfig config, List<String> answerPool, WordWeighting weighting) {
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(answerPool, "answerPool");
        Objects.requireNonNull(weighting, "weighting");
        if (answerPool.isEmpty()) {
            throw new IllegalArgumentException("answerPool must not be empty");
        }
        this.answerRandom = new Random(config.seed());
        this.faultRandom = new Random(config.seed() * 31L + 17L);
        this.sessionRandom = new Random(config.seed() ^ 0x5DEECE66DL);
        this.poolsByTier = buildPools(config, answerPool, weighting);
        LOG.log(System.Logger.Level.DEBUG, "simulator ready: {0}, tiers={1}", config, describePools());
    }

    @Override
    public StartGame startGame(String playerId) {
        injectFault();
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId must not be blank");
        }
        this.started = true;
        this.submitted = false;
        this.sessionId = String.format("%016x", sessionRandom.nextLong());
        this.answer = null;
        this.mask = null;
        this.wrongCount = 0;
        this.totalWordCount = 0;
        this.correctWordCount = 0;
        this.totalWrongGuesses = 0;
        return new StartGame(sessionId, config.wordsPerGame(), config.wrongGuessesAllowed());
    }

    @Override
    public WordState nextWord() {
        injectFault();
        requireActiveSession();
        if (totalWordCount >= config.wordsPerGame()) {
            throw new IllegalStateException(
                    "all " + config.wordsPerGame() + " words have been served; call submitResult instead");
        }

        totalWordCount++;
        int tier = config.tierOf(totalWordCount);
        answer = poolsByTier[tier].sample(answerRandom);
        mask = new char[answer.length()];
        Arrays.fill(mask, WordState.MASK);
        wrongCount = 0;
        return snapshot();
    }

    @Override
    public WordState guess(char letter) {
        injectFault();
        requireActiveSession();
        if (answer == null) {
            throw new IllegalStateException("no active word; call nextWord first");
        }
        if (letter < 'A' || letter > 'Z') {
            throw new IllegalArgumentException("only 'A'-'Z' is accepted, got '" + letter + '\'');
        }
        if (isSolved() || isExhausted()) {
            throw new IllegalStateException("the current word is already over");
        }

        if (answer.indexOf(letter) >= 0) {
            for (int i = 0; i < answer.length(); i++) {
                if (answer.charAt(i) == letter) {
                    mask[i] = letter;
                }
            }
            if (isSolved()) {
                correctWordCount++;
            }
        } else {
            wrongCount++;
            totalWrongGuesses++;
        }
        return snapshot();
    }

    @Override
    public GameResult getResult() {
        injectFault();
        requireActiveSession();
        return result();
    }

    @Override
    public GameResult submitResult() {
        injectFault();
        requireActiveSession();
        submitted = true;
        return result();
    }

    /** The answer currently being played. Exposed for assertions in tests; not part of the protocol. */
    public String currentAnswer() {
        return answer;
    }

    /** The tier the current word belongs to, 0..3, or -1 before the first word. */
    public int currentTier() {
        return totalWordCount == 0 ? -1 : config.tierOf(totalWordCount);
    }

    /** One line per difficulty tier: how many words can be served. Diagnostics only. */
    public List<String> describePools() {
        List<String> lines = new ArrayList<>();
        for (int tier = 0; tier < poolsByTier.length; tier++) {
            lines.add(config.tierLabel(tier) + " -> " + poolsByTier[tier].size() + " words");
        }
        return lines;
    }

    private WordState snapshot() {
        return new WordState(new String(mask), totalWordCount, wrongCount);
    }

    private GameResult result() {
        return new GameResult(totalWordCount, correctWordCount, totalWrongGuesses,
                GameResult.scoreOf(correctWordCount, totalWrongGuesses));
    }

    private boolean isSolved() {
        for (char c : mask) {
            if (c == WordState.MASK) {
                return false;
            }
        }
        return true;
    }

    private boolean isExhausted() {
        return wrongCount >= config.wrongGuessesAllowed();
    }

    private void injectFault() {
        if (config.failureRate() > 0.0 && faultRandom.nextDouble() < config.failureRate()) {
            throw new HangmanApiException("simulated transient server failure", 500);
        }
    }

    private void requireActiveSession() {
        if (!started) {
            throw new IllegalStateException("call startGame first");
        }
        if (submitted) {
            throw new IllegalStateException("the session has already been submitted");
        }
    }

    private static TierPool[] buildPools(SimulationConfig config, List<String> answerPool, WordWeighting weighting) {
        TierPool[] pools = new TierPool[SimulationConfig.TIERS];
        for (int tier = 0; tier < SimulationConfig.TIERS; tier++) {
            List<String> words = new ArrayList<>();
            List<Integer> weights = new ArrayList<>();
            for (String word : answerPool) {
                if (config.acceptsLength(tier, word.length())) {
                    words.add(word);
                    weights.add(weighting.weightOf(word));
                }
            }
            if (words.isEmpty()) {
                throw new IllegalStateException(
                        "difficulty tier " + config.tierLabel(tier) + " has no words in the pool; "
                                + "enlarge poolSize or change the difficulty semantics");
            }
            pools[tier] = TierPool.of(words, weights, config.sampling() == SimulationConfig.Sampling.WEIGHTED);
        }
        return pools;
    }

    /** An immutable per-tier answer pool with an optional cumulative-weight index. */
    private static final class TierPool {

        private final String[] words;
        private final long[] cumulativeWeights;
        private final long totalWeight;
        private final boolean weighted;

        private TierPool(String[] words, long[] cumulativeWeights, long totalWeight, boolean weighted) {
            this.words = words;
            this.cumulativeWeights = cumulativeWeights;
            this.totalWeight = totalWeight;
            this.weighted = weighted;
        }

        static TierPool of(List<String> words, List<Integer> weights, boolean weighted) {
            String[] array = words.toArray(String[]::new);
            long[] cumulative = new long[array.length];
            long running = 0;
            for (int i = 0; i < array.length; i++) {
                running += weights.get(i);
                cumulative[i] = running;
            }
            if (weighted && running <= 0) {
                throw new IllegalStateException("weighted sampling needs positive total weight");
            }
            return new TierPool(array, cumulative, running, weighted);
        }

        int size() {
            return words.length;
        }

        /** Draws a word, by weight when configured, otherwise uniformly. */
        String sample(Random random) {
            if (!weighted) {
                return words[random.nextInt(words.length)];
            }
            long target = random.nextLong(totalWeight);
            int low = 0;
            int high = words.length - 1;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (cumulativeWeights[mid] <= target) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return words[low];
        }
    }
}
