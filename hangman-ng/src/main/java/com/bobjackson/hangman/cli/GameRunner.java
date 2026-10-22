package com.bobjackson.hangman.cli;

import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.DictionaryLoader;
import com.bobjackson.hangman.dict.RankWordWeighting;
import com.bobjackson.hangman.dict.WordWeighting;
import com.bobjackson.hangman.legacy.LegacySolver;
import com.bobjackson.hangman.legacy.LegacyTiers;
import com.bobjackson.hangman.sim.BenchmarkReport;
import com.bobjackson.hangman.sim.EvaluationHarness;
import com.bobjackson.hangman.sim.SimulationConfig;
import com.bobjackson.hangman.solver.FrequencyLetterSelector;
import com.bobjackson.hangman.solver.InformationGainLetterSelector;
import com.bobjackson.hangman.solver.SkipPolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point for offline evaluation.
 *
 * <p>Lives outside {@code sim} and {@code engine} on purpose: the harness depends on the
 * engine, the engine knows nothing about either, and a CLI in {@code engine} would make the
 * two packages depend on each other. The dependency arrows all point one way —
 * {@code cli -> sim -> engine -> api}.
 *
 * <p>Every number this prints is reproducible from {@code --seed}; the point of the tool is
 * that a change can be argued about with evidence instead of intuition.
 */
public final class GameRunner {

    private GameRunner() {
        // entry point only
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            System.err.print(Options.USAGE);
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.print(Options.USAGE);
            return;
        }
        try {
            run(options);
        } catch (IOException | RuntimeException e) {
            // Include the exception type here: an unexpected failure is diagnosable, a bare
            // message containing only "null" is not.
            System.err.println("error: " + e);
            System.exit(1);
        }
    }

    private static void run(Options options) throws IOException {
        // Two rankings on purpose: `truth` is the world the simulator serves answers from and
        // never moves; `belief` is what the solver assumes. A --exponent scan has to change
        // only the second. Moving both at once would mean a score difference can no longer be
        // attributed to the prior, because the answer distribution shifted with it.
        RankWordWeighting truth = RankWordWeighting.fromClasspath();
        RankWordWeighting belief = options.exponent() == RankWordWeighting.DEFAULT_EXPONENT
                ? truth
                : RankWordWeighting.fromClasspath(options.exponent());
        WordWeighting priors = options.priors() == Priors.UNIFORM ? WordWeighting.uniform() : belief;
        LoadedDictionary loaded = loadDictionary(options, truth, priors);
        EvaluationHarness harness = new EvaluationHarness(options.config(), loaded.dictionary(), truth);

        System.out.println("hangman-ng | offline evaluation");
        System.out.println("dictionary: " + loaded.description());
        System.out.println("ranking:    " + truth);
        System.out.println("priors:     " + describePriors(options, belief));
        System.out.print(harness.describe());
        if (options.config().failureRate() > 0.0) {
            System.out.println("note: injected faults are retried without backoff so the run stays quick");
        }
        if (options.bestOf() > 1) {
            System.out.println("note: best of " + options.bestOf()
                    + ": every game is played that many times and only its highest score is kept;"
                    + " this assumes the server scores the best submission, which the instructions do not say");
        }
        System.out.println();

        List<BenchmarkReport> reports = new ArrayList<>();
        for (String name : options.variants()) {
            EvaluationHarness.Variant variant = variant(name);
            long started = System.nanoTime();
            BenchmarkReport report = harness.runBestOf(variant, options.bestOf());
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            reports.add(report);
            System.out.print(report.toTable());
            System.out.println("elapsed: " + elapsedMillis + " ms");
            System.out.println();
        }

        if (reports.size() > 1) {
            System.out.println("summary");
            for (BenchmarkReport report : reports) {
                System.out.println("  " + report.toSummaryLine());
            }
        }
    }

    private static EvaluationHarness.Variant variant(String name) {
        return switch (name) {
            case "coverage" -> EvaluationHarness.Variant.selectingLetters(name,
                    new FrequencyLetterSelector(FrequencyLetterSelector.Mode.COVERAGE));
            case "occurrences" -> EvaluationHarness.Variant.selectingLetters(name,
                    new FrequencyLetterSelector(FrequencyLetterSelector.Mode.OCCURRENCES));
            case "information" -> EvaluationHarness.Variant.selectingLetters(name,
                    new InformationGainLetterSelector());
            case "hopeless" -> EvaluationHarness.Variant.selectingLetters(name,
                    new FrequencyLetterSelector(FrequencyLetterSelector.Mode.COVERAGE),
                    SkipPolicy.hopeless());
            case "expected-value" -> EvaluationHarness.Variant.selectingLetters(name,
                    new FrequencyLetterSelector(FrequencyLetterSelector.Mode.COVERAGE),
                    SkipPolicy.expectedValue());
            case "legacy" -> new EvaluationHarness.Variant(name,
                    dictionary -> LegacySolver.factory(LegacyTiers.of(dictionary)));
            default -> throw new IllegalArgumentException(
                    "unknown variant \"" + name + "\"; expected coverage, occurrences, information, "
                            + "hopeless, expected-value or legacy");
        };
    }

    private static String describePriors(Options options, RankWordWeighting belief) {
        return options.priors() == Priors.UNIFORM
                ? "uniform (every surviving candidate counts once; the M1 baseline)"
                : "from corpus rank (" + belief + ')';
    }

    private static LoadedDictionary loadDictionary(Options options, RankWordWeighting ranking,
                                                   WordWeighting priors) {
        Path file = options.dictionaryPath() == null
                ? discoverWordsFile()
                : Path.of(options.dictionaryPath());
        if (file == null) {
            Dictionary dictionary = Dictionary.of(ranking.topN(options.config().poolSize()), priors);
            return new LoadedDictionary("no words.txt found, using the ranked list: "
                    + dictionary.size() + " words", dictionary);
        }
        try {
            DictionaryLoader.LoadResult result = DictionaryLoader.fromFile(file, priors);
            String description = String.format(Locale.ROOT,
                    "%s: %d words from %d lines (rejected %d, duplicates %d)",
                    file.toAbsolutePath().normalize(), result.kept(), result.linesRead(),
                    result.rejected(), result.duplicates());
            return new LoadedDictionary(description, result.dictionary());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Finds {@code words.txt} without being told where it is.
     *
     * <p>The Maven build runs with the module directory as the working directory, while a
     * plain {@code java -jar} from the repository root sees {@code words.txt} directly. Both
     * are supported rather than forcing the path to be repeated on every invocation.
     */
    private static Path discoverWordsFile() {
        for (String candidate : new String[]{"words.txt", "../words.txt", "../../words.txt"}) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private record LoadedDictionary(String description, Dictionary dictionary) {
    }

    /** Which belief the solver holds about how likely each surviving word is. */
    private enum Priors { UNIFORM, RANKED }

    /** Parsed command line; validation happens in the {@link SimulationConfig} constructor. */
    private record Options(SimulationConfig config, List<String> variants, String dictionaryPath,
                           Priors priors, double exponent, int bestOf, boolean help) {

        static final String USAGE = """
                usage: hangman [options]

                Runs the solver against the offline simulator and prints one report per variant.

                  --games N          sessions per variant                        (default 20)
                  --seed N           master seed; per-session seeds derive from it (default 42)
                  --pool N           how many of the most frequent words may be served (default 100000)
                  --sampling M       weighted | uniform                          (default weighted)
                  --difficulty M     bucketed | cumulative                       (default bucketed)
                                     bucketed is what the original server did; cumulative is
                                     the instructions' literal <= reading
                  --words N          words per session, a multiple of 4            (default 80)
                  --allow N          misses allowed per word                      (default 10)
                  --failure-rate F   probability that any single call fails        (default 0)
                  --best-of N        sessions played per game, highest score kept  (default 1)
                                     models a server that scores the best submission;
                                     the attempts run concurrently, so N costs about one
                                     session's wall clock
                  --variants LIST    coverage, occurrences, information, hopeless,
                                     expected-value, legacy
                                     (default coverage,occurrences; legacy replays
                                     the original client's decisions; information maximises
                                     candidate separation instead of hit rate; hopeless
                                     and expected-value use coverage letters and differ
                                     only in when they give a word up)
                  --dictionary PATH  word list; by default words.txt or ../words.txt is used
                  --priors M         ranked | uniform                            (default ranked)
                  --exponent F       prior strength for ranked priors, 0 = flat  (default 0.5)
                                     moves the solver's belief only; the simulator's answer
                                     distribution stays fixed, so a scan changes one variable
                  --help             print this message
                """;

        static Options parse(String[] args) {
            SimulationConfig defaults = SimulationConfig.defaults();
            int games = defaults.games();
            long seed = defaults.seed();
            int poolSize = defaults.poolSize();
            SimulationConfig.Sampling sampling = defaults.sampling();
            SimulationConfig.DifficultySemantics difficulty = defaults.difficulty();
            double failureRate = defaults.failureRate();
            int wordsPerGame = defaults.wordsPerGame();
            int wrongGuessesAllowed = defaults.wrongGuessesAllowed();
            List<String> variants = List.of("coverage", "occurrences");
            String dictionaryPath = null;
            Priors priors = Priors.RANKED;
            double exponent = RankWordWeighting.DEFAULT_EXPONENT;
            int bestOf = 1;
            boolean help = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--games" -> games = Integer.parseInt(next(args, ++i, arg));
                    case "--seed" -> seed = Long.parseLong(next(args, ++i, arg));
                    case "--pool" -> poolSize = Integer.parseInt(next(args, ++i, arg));
                    case "--sampling" -> sampling = samplingOf(next(args, ++i, arg));
                    case "--difficulty" -> difficulty = difficultyOf(next(args, ++i, arg));
                    case "--words" -> wordsPerGame = Integer.parseInt(next(args, ++i, arg));
                    case "--allow" -> wrongGuessesAllowed = Integer.parseInt(next(args, ++i, arg));
                    case "--failure-rate" -> failureRate = Double.parseDouble(next(args, ++i, arg));
                    case "--variants" -> variants = namesOf(next(args, ++i, arg));
                    case "--dictionary" -> dictionaryPath = next(args, ++i, arg);
                    case "--priors" -> priors = priorsOf(next(args, ++i, arg));
                    case "--exponent" -> exponent = Double.parseDouble(next(args, ++i, arg));
                    case "--best-of" -> bestOf = Integer.parseInt(next(args, ++i, arg));
                    case "--help", "-h" -> help = true;
                    default -> throw new IllegalArgumentException("unknown option: " + arg);
                }
            }

            // Silently ignoring this pair would produce a report labelled "uniform" from a run
            // the caller believed was testing a prior strength, so refuse instead.
            if (priors == Priors.UNIFORM && exponent != RankWordWeighting.DEFAULT_EXPONENT) {
                throw new IllegalArgumentException(
                        "--exponent only applies to --priors ranked; uniform ignores it");
            }

            if (bestOf < 1) {
                throw new IllegalArgumentException("--best-of must be at least 1, got " + bestOf);
            }

            SimulationConfig config = new SimulationConfig(poolSize, sampling, difficulty, games,
                    seed, failureRate, wordsPerGame, wrongGuessesAllowed);
            return new Options(config, variants, dictionaryPath, priors, exponent, bestOf, help);
        }

        private static String next(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return args[index];
        }

        private static SimulationConfig.Sampling samplingOf(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "weighted" -> SimulationConfig.Sampling.WEIGHTED;
                case "uniform" -> SimulationConfig.Sampling.UNIFORM;
                default -> throw new IllegalArgumentException(
                        "--sampling expects weighted or uniform, got \"" + raw + '"');
            };
        }

        private static SimulationConfig.DifficultySemantics difficultyOf(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "cumulative" -> SimulationConfig.DifficultySemantics.CUMULATIVE;
                case "bucketed" -> SimulationConfig.DifficultySemantics.BUCKETED;
                default -> throw new IllegalArgumentException(
                        "--difficulty expects cumulative or bucketed, got \"" + raw + '"');
            };
        }

        private static Priors priorsOf(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "ranked" -> Priors.RANKED;
                case "uniform" -> Priors.UNIFORM;
                default -> throw new IllegalArgumentException(
                        "--priors expects ranked or uniform, got \"" + raw + '"');
            };
        }

        private static List<String> namesOf(String raw) {
            List<String> names = new ArrayList<>();
            for (String part : raw.split(",")) {
                String name = part.strip().toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
            if (names.isEmpty()) {
                throw new IllegalArgumentException("--variants needs at least one name");
            }
            return List.copyOf(names);
        }
    }
}
