# hangman-ng — design

Date: 2026-10-22
Status: M1-M2 implemented; M3-M4 planned

## Goal

Implement the Strikingly Hangman interview task so that the strategy can be **compared
empirically**, not argued about. The deliverable is a solver plus the instrument used to
judge it: a reproducible offline simulator, a benchmark harness, and per-difficulty-tier
reporting.

The repository already contains a Java 8 implementation (`HangmanPlayer.java`, 579 lines)
that scored 1326 against the real server in 2017. That implementation is left untouched and
serves as the reference point for every comparison below.

## Constraints

- Java 21 (LTS), Maven, JUnit 5 + AssertJ.
- A new module, `hangman-ng`; no existing file is modified or deleted.
- No runtime dependencies in M1: the simulator needs no HTTP stack and no JSON library.
  Jackson and `java.net.http` arrive with the real transport in M4.
- External word-frequency data may be downloaded; the artifact is committed as a gzipped
  resource so builds stay offline and reproducible.

## Scoring model

The instructions never state the formula. It is recoverable from the two published examples:

| correct | wrong | reported score | `20 * correct - wrong` |
|---:|---:|---:|---:|
| 78 | 234 | 1326 | 1326 |
| 77 | 233 | 1307 | 1307 |

`score = 20 * correctWordCount - totalWrongGuessCount`. Encoded in `GameResult.scoreOf` and
asserted at runtime by `isScoreConsistent()`.

Consequence for strategy: a guess is worth taking while `p(hit) * 20` exceeds the expected
cost of the misses it may cause. Maximising P(hit) — the greedy rule the legacy implementation
also used — is therefore the right first-order objective, and skipping a word is only
profitable when the candidate set is wide and the miss budget is nearly spent.

## Module design

```
api        HangmanClient, StartGame, WordState, GameResult, HangmanApiException
dict       Dictionary, DictionaryLoader, WordWeighting, RankWordWeighting
solver     Solver, EliminationSolver, LetterSelector, FrequencyLetterSelector, GuessContext
engine     GameEngine, GameListener
sim        SimulatedServer, SimulationConfig, EvaluationHarness, BenchmarkReport
transport  RetryingHangmanClient, Sleeper
cli        GameRunner
```

Dependency direction: `cli → sim → engine → solver → dict → api`. No cycles; `solver` knows
nothing about HTTP, scoring or game flow; `engine` owns no per-word state.

### Invariants

1. **One word, one solver.** A `Solver` is created per word by `SolverFactory` and discarded.
   All per-word state (guessed mask, pattern, candidate array) is instance state, so there is
   nothing to reset and nothing shared.
2. **The dictionary is immutable.** Filtering produces a new `int[]` of indices. The legacy
   implementation's shared `CopyOnWriteArrayList` — mutated while filtering, rebuilt 80 times
   per game — cannot exist here.
3. **Rules come from the server.** `numberOfWordsToGuess` and
   `numberOfGuessAllowedForEachWord` are read from `StartGame`; `80` and `10` appear nowhere
   in the engine.
4. **Retried calls are safe.** `SimulatedServer` injects faults before mutating state, so a
   transport-level failure never double-counts a guess.

### The consistency rule

A candidate word survives iff, for every position `i`:

- if `i` is revealed, the candidate's letter equals the revealed letter;
- if `i` is masked, the candidate's letter has **never been guessed**.

The second clause subsumes two facts that the legacy code tracked separately and applied only
to the most recent observation: "the word contains none of the letters we missed" and "the
revealed letter occurs exactly as many times as the mask shows". Because a hit reveals every
occurrence, a guessed letter cannot hide at a masked position.

Data layout: words cached as `String[]`, a parallel `int[]` of 26-bit masks, an
`int[]` of weights shaped as a cumulative index, and a `Map<Integer, int[]>` of length buckets
built once. A filter pass is O(candidates × length) over primitives with at most one
allocation, and none when nothing is eliminated.

### Letter selection

`FrequencyLetterSelector` scores each unguessed letter over the surviving candidates and takes
the maximum, breaking ties by static English frequency. Two modes:

- `COVERAGE` (default) — sum of weights of candidates *containing* the letter. Estimates
  P(hit) directly, which is what the score rewards.
- `OCCURRENCES` — sum of weights times occurrence counts, the legacy behaviour. Over-values
  letters that repeat inside one word, since a hit reveals all occurrences at once.

An empty candidate set (answer not in our dictionary) degrades to pure frequency order rather
than failing.

## Data pipeline

`tools/fetch-data.sh` downloads
`david47k/top-english-wordlists/top_english_words_lower_100000.txt` (Google ngram
frequencies 1950-2012, CC BY 3.0), keeps `^[a-z]{3,20}$`, de-duplicates, upper-cases, and
gzips into `src/main/resources/word-ranks.txt.gz`:

- 100,000 lines in, 96,886 words out, 395 KB;
- tier capacity by length: 22,421 / 40,527 / 28,949 / 4,989.

Two lists, two jobs:

| list | size | job |
|---|---:|---|
| rank list (`word-ranks.txt.gz`) | 96,886 | answer sampling weights; the solver's default priors |
| dictionary (`../words.txt`) | 416,264 | candidate set — every word the server plausibly knows |

Measured overlap: 82.5% of the ranked words appear in `words.txt`. The 17.5% that do
not are excluded from the answer pool, because serving a word the dictionary cannot contain
measures corpus coverage rather than solver quality. The `blind` column stays in every report
as the check that this exclusion happened.

Weight decays as `scale / rank^exponent` with `exponent = 0.5`, `scale = 1e6`, floor 1. A pure
Zipf exponent of 1.0 would let the top few words swallow the distribution; 0.0 collapses to
uniform.

## Simulation model

`SimulatedServer` implements `HangmanClient` in-process. Fidelity choices:

- a hit reveals every occurrence, as documented;
- answers are drawn from their tier by weight (`--sampling uniform` for the pessimistic case);
- tiers follow the documented word-index ranges;
- a repeated wrong letter costs another miss, matching a server with no memory of the
  conversation;
- faults are injected with probability `--failure-rate` before any state change.

Three ambiguities are knobs rather than assumptions:

| ambiguity | readings | why it matters |
|---|---|---|
| tier bounds | `CUMULATIVE` (`<=5`, `<=8`, `<=12`, `>12`) vs `BUCKETED` (3-5, 6-8, 9-12, 13+) | settled by the control group: the server used buckets |
| answer distribution | `WEIGHTED` vs `UNIFORM` | worth ~90 points |
| pool size | top-N of the ranked list | worth ~120 points between N=1k and N=100k |

## Evaluation protocol

- `--games 100` sessions per variant, `--words 80`, `--seed 42`. Twenty is enough to see a
  100-point effect but not a 10-point one, and the exponent plateau turned out to live in that
  second range.
- Each session derives its own seed as `seed + i * 1_000_003`, so session *i* is identical
  regardless of how many sessions the run contains — a single bad session is reproducible on
  its own.
- Reported: mean, median, sample standard deviation, min, max, plus solved rate, misses per
  word, guesses per word and dictionary-miss rate, overall and per difficulty tier.
- Comparison procedure: change one knob, hold the seed, report the delta against the
  distribution's spread. A difference smaller than the standard deviation is not a result.

## Test strategy

84 tests, no network, no real sleeps.

| class | focus |
|---|---|
| `DictionaryLoaderTest` | CRLF residue, rejection/duplicate accounting, length buckets |
| `DictionaryTest` | masks, deduplication, weight validation, index immutability |
| `RankWordWeightingTest` | rank order, weight decay, `topN` bounds |
| `SimulationConfigTest` | tier boundaries, both readings, the bucketed default |
| `EliminationSolverTest` | consistency rule, repeated letters, empty-candidate fallback |
| `SimulatedServerTest` | reveal-all-occurrences, miss accounting, determinism, fault injection |
| `GameEngineTest` | advertised word count, abandonment, listener contract, error propagation |
| `RetryingHangmanClientTest` | retry budget, non-retryable errors, backoff curve, interrupts |
| `BenchmarkReportTest` | median, sample standard deviation, per-tier aggregation |
| `LegacyTiersTest` | length grouping, word-number selection, empty buckets |
| `LegacySolverTest` | first guess, miss-only length filter, stale counts, tie-break order |

`Sleeper` is injected so the backoff curve is asserted exactly without waiting for it.
`GameEngineTest` uses a hand-written fake, deliberately independent of `SimulatedServer`, so a
bug in the simulator cannot hide a bug in the engine.

## Measured baseline

Seed 42, 100 sessions × 80 words, weighted sampling, bucketed tiers:

| variant | mean | sd | solved | wrong/word |
|---|---:|---:|---:|---:|
| coverage, corpus priors (default) | 1404.65 | 34.75 | 97.5% | 1.94 |
| occurrences, corpus priors | 1401.07 | 38.41 | 97.4% | 1.96 |
| coverage, uniform priors | 1286.04 | 51.70 | 94.1% | 2.75 |
| occurrences, uniform priors | 1281.28 | 50.88 | 94.1% | 2.80 |

Three findings drive the remaining roadmap:

1. **Priors are worth ~119 points**, more than anything else measured so far.
2. **The first tier costs most of the loss** (4.63 misses per word with priors, 6.48 without,
   versus 0.20 in the last tier; the four tiers average 1.94, so this one tier is 60% of every
   miss in the game). Any further work should target short words with large candidate sets.
3. **The prior's strength is not worth tuning.** 100 sessions per point: 0 → 1286.04,
   0.25 → 1396.72, 0.5 → 1404.65, 0.75 → 1401.62, 1.0 → 1395.29. Everything from 0.25 to 1.0
   sits inside three standard errors of the plateau; only "use it at all" matters.

Pool size, 100 sessions per cell, coverage, corpus / uniform: 1,000 → 1511.47 / 1279.23,
5,000 → 1498.22 / 1297.46, 20,000 → 1468.32 / 1286.65, 100,000 → 1404.65 / 1286.04. The corpus
column slopes 107 points while the uniform column moves 18 with no trend, which is a sharper
statement than M1 could make: the pool is difficulty only for a solver that reads the corpus.
A solver with no ranking signal picks letters the same way whatever is eligible.

Under cumulative tiers the uniform column *did* slope (1023.59 → 1150.60). The bucket reading
removes the path that produced it — a short answer landing in a tier whose candidate set spans
the whole short end of the dictionary — and with it the effect.

### The control group

`legacy/LegacySolver` replays the original decision logic behind the same `Solver` interface, so
the harness can score it. It exists for two reasons: to price the defects listed earlier, and to
settle the tier ambiguity that M1 had been treating as a coin flip.

100 sessions per cell, seed 42, weighted sampling:

| difficulty | coverage | legacy |
|---|---:|---:|
| bucketed | 1404.65 (sd 34.75) | 1260.24 (sd 50.30) |
| cumulative | 1322.29 (sd 50.91) | 841.13 (sd 92.42) |

The published client scored 1326 in one real session. Under buckets that sits 1.31 standard
deviations above the replay's mean — an ordinary draw. Under cumulative it sits 5.25 standard
deviations above. A 5σ gap is not a bad session, so the server used disjoint buckets: the
client's tier logic was following the server, and the `<=5` wording describes a game that was
never played.

Two consequences, one of which retracts an earlier claim:

- The rewrite is worth **144 points** in the world that existed (1404.65 − 1260.24) and **481**
  in the world the document describes. The first is the honest headline. The second measures
  how destructive the mismatch becomes when the answer's length falls outside the bucket the
  word number selected: the `blind` rate leaves 0.0% for 38.7% and 70.0% on tiers two and three.
- The M1 statement that 1329 "reproduced" 1326 was a coincidence, not a validation. It compared
  `coverage` under cumulative tiers against `legacy` under buckets — two programs in two
  different games. Withdrawn. What is real is the weaker claim that the legacy replay's own
  distribution contains 1326 at 1.31σ.

The gap is not spread evenly: ~107 of the 144 points are the first tier. Under buckets the
candidate set is already correct — the server only asks words inside the bucket it named, so the
legacy filtering never loses the answer — and what the client lacks is a way to *order* that
set. It ranks letters, not words, and at sixteen thousand candidates on the first tier that is
the difference between 73.5% and 90.0% solved.

## Milestones

- **M1** — simulator, dictionary, solver, engine, CLI, reports, tests. Done.
- **M2** — corpus priors as the default, `--exponent` knob, measured. Done: the scan shows the
  decision is binary (use corpus priors or not) and the exponent plateau is flat from 0.25 to 1.0.
- **M3** — exact expected-miss minimisation over small candidate sets, plus a skip policy
  driven by `EV(continue) = p * (30 - k) - 10`. Both target the first tier.
- **M4** — `HttpHangmanClient` over `java.net.http` + Jackson, and running several sessions in
  parallel to pick the best-final-score one, since a single session has `sd` ≈ 50-80 points.
- **C** (inserted between M2 and M3) — the legacy algorithm replayed as a control group. Done:
  it re-reads M1's headline, prices the defect table at 144 points, and settles the tier
  ambiguity in favour of buckets.

## Decisions and rejected alternatives

| decision | rationale | rejected |
|---|---|---|
| offline simulator first, HTTP last | an unmeasurable solver cannot be improved; a session must cost milliseconds | write the HTTP client first and iterate blind |
| immutable dictionary + index filtering | removes the O(n²) mutation that made the legacy run take 86 minutes | keep a mutable candidate list |
| one condition for candidate consistency | one rule instead of two structures kept in sync | `wrongHistory` list + `rightGuess` map |
| typed methods per action | each action returns exactly one shape; a sealed response hierarchy would add a layer nothing needs | generic `Response` with a `type` discriminator |
| `Sleeper` interface | the backoff curve is asserted exactly, and simulation does not wait | real sleeps behind a static call |
| `RetryingHangmanClient` in M1 | otherwise the simulator's fault injection is dead code with no test | defer retries to M4 |
| tiers as a config knob | the wording admits two readings, and the control group later showed which one the server used | silently pick the legacy reading |
| answer pool ∩ dictionary | isolates solver quality from corpus coverage | draw answers from the raw rank list |
| `--exponent 0` flattens the floor weight too | the knob must have a verifiable left endpoint; it now reproduces `--priors uniform` byte-for-byte | leave unlisted words at floor 1, making 0 a third belief nobody asked for |
| separate `truth` and `belief` rankings in the CLI | a prior scan must not move the answer distribution, or the result cannot be attributed | one ranking shared by simulator and solver |
| commit the gzipped rank list | builds stay offline; 395 KB is cheap | download at build time |
| replay the legacy algorithm in-module as a control group | a defect priced in points is an argument; the same defect described in prose is an opinion, and it was worth rewriting the harness `Variant` from "a letter selector" to "a solver factory" to allow it | paraphrase the defect list and move on |
| switch the default to `bucketed` once the control group proved it | the default should describe the game that was played; keeping the literal reading as the headline made every published number describe a world that never existed | keep `cumulative` to avoid re-running the tables — the cost was three minutes of measurement and one honest rewrite |

## Risks

- The answer distribution is unverifiable. Mitigation: the sensitivity table, reported
  alongside every headline number.
- 17.5% of the ranked list is absent from `words.txt`, so the effective answer pool is
  narrower than the rank list implies. Mitigation: reported in the CLI header, and `blind`
  stays in every report.
- A greedy solver leaves value on the table on wide candidate sets. Mitigation: quantified per
  tier, and the target of M3.
- A score without its `--pool` is meaningless for any solver that reads the corpus: the pool
  moves that solver by 107 points across the sweep, and a solver without a prior by 18.
  Mitigation: every report header prints it, and the sweep is in the README.
- The tier reading rests on one historical session plus a replay, not on the server's source.
  Mitigation: the gap it produces is 5σ, and `--difficulty` keeps both readings runnable so the
  weaker one can be checked directly.
- The legacy replay skips letters already guessed, where the original could repeat one. That
  can only raise the legacy score, making every reported gap a lower bound. Mitigation: none
  needed — the direction is known and conservative.
