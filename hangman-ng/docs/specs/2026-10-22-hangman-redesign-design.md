# hangman-ng — design

English | [中文](../zh-CN/specs/2026-10-22-hangman-redesign-design.md)

Date: 2026-10-22
Status: M1-M6 implemented and measured

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
- No runtime dependencies, including M4. `java.net.http` is in the JDK, and the protocol's JSON
  is small enough that a 240-line recursive-descent parser beats a 2018-vintage Jackson that is
  the only thing available offline.
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
also used — is therefore the right first-order objective. Four alternatives were built and
measured rather than argued about, and all four came back small or negative: an information-gain
objective by 875 points, abandoning words by everything it had (the best possible version is worth
ten), an exact lookahead worth 1.3 to 3.1 points per session on the decisions that can be priced,
and a per-guess probe finding that the misses arrive at exactly the rate the model assigned them.

## Module design

```
api        HangmanClient, StartGame, WordState, GameResult, HangmanApiException
dict       Dictionary, DictionaryLoader, WordWeighting, RankWordWeighting
solver     Solver, EliminationSolver, LetterSelector, FrequencyLetterSelector,
           InformationGainLetterSelector, SkipPolicy, SkipContext, GuessContext,
           ExactScore, AuditedLetterSelector, AuditSummary,
           MissAnatomy, MissAnatomySummary
engine     GameEngine, GameListener
sim        SimulatedServer, SimulationConfig, EvaluationHarness, BenchmarkReport
transport  HttpHangmanClient, Json, RetryingHangmanClient, Sleeper
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

`InformationGainLetterSelector` replaces that objective with the expected reduction in
`log2(candidates)` and is kept as the measured counter-example: 529.50 against 1404.65. The score
changes by `21p - 1` per turn, which is monotone in `p`, so exploration that costs a miss is never
worth paying for; the selector's own arithmetic prefers a 50/50 letter (1.0 bit) to a 90% hit
(0.469 bit) by a factor of two. Entropy is the right objective only when probes are free.

### Abandoning a word

`Solver.shouldAbandon()` is asked once per turn, before the next guess. `GameEngine` is the only
place that acts on it, and the default answer is never, so the baseline is untouched. `SkipPolicy`
supplies the two implemented rules: `hopeless` (no dictionary word explains the evidence) and
`expectedValue` (`20·P(solve) < (1−P)·missesLeft`, with `P` from a negative-binomial tail).

Both are measured and both are off by default. The intuitive third rule — give up when the hidden
letters cost more misses than remain — is wrong, and expensively so, because a hit does not
consume a miss: it skipped 2.8% of the long-word tier, all of them certain solves, for −13 points.
The omniscient upper bound for the whole idea is ten points: only the 201 first-tier words that
ran out of misses can be improved, and each by at most the misses it had not spent.

### Transport

`HttpHangmanClient` speaks the documented single-endpoint protocol over `java.net.http`, reading
`numberOfWordsToGuess` and `numberOfGuessAllowedForEachWord` from the response rather than
assuming 80 and 10. It deliberately does not retry: `RetryingHangmanClient` wraps it, so the
policy can be tested without a socket. `Json` is package-private and exists because the only
JSON library available offline here is from 2018; the protocol uses six field types and no
nesting beyond one object.

### Looking ahead without pretending

A planner over the belief state is the obvious next step and it does not fit. A miss does not
shrink the candidate set, it only spends a letter, so the reachable states grow like the ways to
choose `k` of the letters a word does not use: `C(15, k)` summed to `k = 10` is about 2^15 per
decision, each costing 26 letter evaluations. Truncating the search is worse than skipping it: a
leaf value that prices only the next few turns against a whole-word scale systematically prefers
large reveals, which is the direction that lost 875 points. So the milestone measures instead.

`ExactScore` solves one decision out completely, with no heuristic leaf, so its answer is the
true expectation. The state is (candidates, guessed mask, misses left) and the pattern is
implicit: position `i` is revealed exactly when every candidate agrees there and that letter has
been guessed — `EliminationSolver`'s consistency rule read backwards, so the two cannot drift
apart. `AuditedLetterSelector` wraps `coverage`, asks `ExactScore` what the best letter was worth
and what the chosen letter was worth, records the difference, and then plays the chosen letter
unchanged. An audited run therefore scores bit for bit what the plain run scores; only
`AuditSummary` differs. That is what makes the number readable as a measurement rather than a
bet, and it is why the audit could be run on the main configuration without contaminating it.

### Pricing every guess

The audit prices 1.6% of decisions. The rest cannot be solved out, so the second probe asks a
different question: not "was there a better letter?" but "were the misses the chosen letter
produced the ones the model predicted?".

`MissAnatomy` wraps `EliminationSolver` and forwards every call, so an instrumented run scores
bit for bit what a plain one scores — 1404.65 against 1404.65, at 11.8 s against 10.8 s over 100
sessions. `nextGuess()` snapshots the candidate count, the misses left and the delegate's own
`P(hit)` for the letter about to be played; `observe()` compares
`wrongGuessCountOfCurrentWord` against the value before the guess, which distinguishes a hit from
a miss even in the case that defeats inference by candidate-set size — a hit on a letter every
candidate already contains leaves the set untouched.

The probe needs the candidate array, which the solver already holds, so `EliminationSolver` grew
one package-private diagnostic accessor rather than a second implementation of the consistency
rule. Nothing public changed, and `GuessContext` still has no pattern field.

`MissAnatomySummary` accumulates three groupings — by word length, by guess index, by `P(hit)`
tenth — each reporting the observed miss rate beside `1 - mean(P(hit))`. The third is the one that
can be wrong: the letters, their `P(hit)` and the simulator's sampling all come from the same
`RankWordWeighting`, so a calibrated probe must show the observed rate tracking the predicted one
row by row, and a row that does not names a real defect.

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
- Best-of-N reuses that layout in the other order: attempt *a* of game *g* uses
  `seed + (g + a * games) * 1_000_003`, so the first attempt of every game is exactly the session
  a plain run would have played. Best-of-N therefore dominates best-of-1 game by game, and every
  individual session stays reproducible by itself.
- Reported: mean, median, sample standard deviation, min, max, plus solved rate, misses per
  word, guesses per word and dictionary-miss rate, overall and per difficulty tier.
- Comparison procedure: change one knob, hold the seed, report the delta against the
  distribution's spread. A difference smaller than the standard deviation is not a result.
- The lookahead audit adds a second kind of evidence: an exact expected score, computed by value
  iteration over the states small enough to finish, compared against the letter actually played.
  It needs no seed discipline because it is not a sample; its limits are coverage, not noise, and
  the CLI prints both the number of decisions priced and the number skipped.
- The per-guess probe adds a third kind of evidence: a calibration check over every decision,
  not a sample of them. It needs no seed discipline either, but it measures the model against
  itself and can only detect a model that is internally inconsistent, never one that is
  consistently wrong about the world — if the weights and the sampling were equally miscalibrated,
  it would see perfect agreement.

## Test strategy

172 tests, no network, no real sleeps.

| class | focus |
|---|---|
| `DictionaryLoaderTest` | CRLF residue, rejection/duplicate accounting, length buckets |
| `DictionaryTest` | masks, deduplication, weight validation, index immutability |
| `RankWordWeightingTest` | rank order, weight decay, `topN` bounds |
| `SimulationConfigTest` | tier boundaries, both readings, the bucketed default |
| `EliminationSolverTest` | consistency rule, repeated letters, empty-candidate fallback |
| `SkipPolicyTest` | empty candidate sets, hit-versus-miss arithmetic, the negative-binomial tail |
| `InformationGainLetterSelectorTest` | the 50/50 peak, symmetry, empty-candidate fallback |
| `ExactScoreTest` | every letter against an uncached reference, the implicit pattern, budget refusal |
| `AuditedLetterSelectorTest` | the delegate's letter played unchanged, gaps found, coverage counted |
| `MissAnatomyTest` | the delegate's letter played unchanged, hits read off the miss counter, one letter priced |
| `MissAnatomySummaryTest` | bucket boundaries, pooled guess indices, delta sign, sample validation |
| `SimulatedServerTest` | reveal-all-occurrences, miss accounting, determinism, fault injection |
| `GameEngineTest` | advertised word count, abandonment, listener contract, error propagation |
| `EvaluationHarnessTest` | best-of-N keeps the maximum, seeds nest, concurrent runs are identical |
| `RetryingHangmanClientTest` | retry budget, non-retryable errors, backoff curve, interrupts |
| `JsonTest` | scalar types, escapes, malformed and truncated input |
| `HttpHangmanClientTest` | rule parameters read not assumed, session forwarding, non-200s, timeouts |
| `BenchmarkReportTest` | median, sample standard deviation, per-tier aggregation, abandoned counts |
| `LegacyTiersTest` | length grouping, word-number selection, empty buckets |
| `LegacySolverTest` | first guess, miss-only length filter, stale counts, tie-break order |

`Sleeper` is injected so the backoff curve is asserted exactly without waiting for it.
`GameEngineTest` uses a hand-written fake, deliberately independent of `SimulatedServer`, so a
bug in the simulator cannot hide a bug in the engine. `HttpHangmanClientTest` runs against a
`com.sun.net.httpserver.HttpServer` on `127.0.0.1`, so the real `java.net.http` stack exercises
the real parser without leaving the machine.

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
- **M3** — a skip policy. Done, with a negative result: `hopeless` and `expectedValue` both fire
  zero times in 8,000 words, the intuitive "not enough misses left" rule costs 13 points, and the
  omniscient upper bound for the whole idea is ten. Kept behind `--variants`, off by default.
- **M4** — `HttpHangmanClient` over `java.net.http` with a hand-written JSON codec (no Jackson),
  and best-of-N session selection. Done: +20.4 / +36.3 / +48.4 / +56.0 points for N = 2 / 4 / 8 /
  16, against `σ·e_N` predictions of +19.6 / +35.8 / +49.5 / +61.4. The attempts of one game run
  concurrently, so N = 4 costs 11% more wall clock than N = 1.
- **M5** — an exact lookahead audit, the answer to M3's open question. Done, and small: 9% to 21%
  of the priced decisions are not optimal, but the whole audit is worth 1.26 to 3.14 points per
  session, because the gaps are 0.12 points apiece. It prices 1.6% to 3.7% of all choices; the
  middle game is untouched by the number and by this milestone.
- **M6** — a calibration probe over every guess, the answer to the hole M5 left. Done, with a
  negative result: 49.2% of the 70,788 guesses are certain hits that cost 0.9% of the misses, and
  the remaining half misses at 42.7% against a predicted 43%. Row by row the largest deviation
  outside a 53-decision row is one standard error. There is no mispricing to fix.
- **C** (inserted between M2 and M3) — the legacy algorithm replayed as a control group. Done:
  it re-reads M1's headline, prices the defect table at 144 points, and settles the tier
  ambiguity in favour of buckets.

### Best of N

100 games per row, `coverage`, seed 42:

| sessions per game | mean | sd | min | max | wall clock |
|---:|---:|---:|---:|---:|---:|
| 1 | 1404.65 | 34.75 | 1316 | 1474 | 11.4 s |
| 2 | 1425.02 | 27.58 | 1324 | 1478 | 11.4 s |
| 4 | 1440.96 | 19.09 | 1391 | 1482 | 12.6 s |
| 8 | 1453.04 | 16.13 | 1413 | 1484 | 24.1 s |
| 16 | 1460.66 | 11.49 | 1430 | 1485 | 49.8 s |

The mechanism is understood, not merely observed: the mean of a maximum of N draws rises by
`σ · e_N`, where `e_N` is the expected maximum of N standard normals. Four of the five deltas land
within 1.5 points of that prediction. This is also the largest single gain in the whole exercise,
which is the uncomfortable finding — bigger than the 144 points the rewrite was worth against the
original, and far bigger than the ten the best possible skip rule was worth.

### The lookahead audit

100 games, `coverage`, seed 42, one row per setting of the three limits:

| candidates / misses / states | decisions priced | not optimal | points per game foregone |
|---|---:|---:|---:|
| 40 / 3 / 5,000 (default) | 1,121 of 70,788 | 13.8% | 1.26 |
| 40 / 3 / 20,000 | 1,146 of 70,788 | 14.9% | 1.30 |
| 40 / 5 / 5,000 | 2,015 of 70,788 | 9.1% | 1.39 |
| 80 / 3 / 5,000 | 1,260 of 70,788 | 20.9% | 1.69 |
| 80 / 5 / 20,000 | 2,617 of 70,788 | 18.7% | 3.14 |

Two readings and one prohibition. Being wrong is common and cheap: the widest row is 314 points
over 100 sessions across 2,617 decisions, 0.12 each, because the expectation is nearly flat across
the sensible letters — with one miss left and three candidates, a certain reveal and a one-in-three
shot are both worth 20/3. Widening the reach 2.3× buys 1.9 points and roughly doubles the wall
clock, so the result does not scale with coverage. And it must not be read as the total value of
lookahead:
the audit prices 1.6% to 3.7% of choices, and the states it cannot reach are precisely the wide
ones with the most scope to be wrong. What it does establish is that the endgame — where the
twenty-point swing is largest and a word is actually saved or lost — is within 0.6% to 1.6% of
optimal, against a remaining gap of 195 points per session.

### The per-guess probe

100 games, `coverage`, seed 42, 70,788 guesses and 15,495 misses:

| bucket | guesses | share | misses | share of misses | observed miss rate |
|---|---:|---:|---:|---:|---:|
| `P(hit)` 0.9 to 1.0 | 34,816 | 49.2% | 139 | 0.9% | 0.4% |
| `P(hit)` below 0.9 | 35,972 | 50.8% | 15,356 | 99.1% | 42.7% |
| all | 70,788 | 100% | 15,495 | 100% | 21.9% |

The model predicted 22.0% overall, and about 43% on the risky half. Three readings:

1. **Half of the guesses cannot cost a miss.** 34,816 of 70,788 land on a letter every surviving
   candidate contains, and they produce 0.9% of the misses. Everything that goes wrong happens in
   the other half, and there the observed rate and the predicted one coincide.
2. **The prediction holds row by row.** Ten rows by `P(hit)`, eighteen by word length, ten by
   guess index. The largest deviation outside the smallest row is 0.011 on a 7,453-decision row,
   one standard error; the `0.0-0.1` row is off by 0.071 on 53 decisions whose own standard error
   is 0.077.
3. **Calibration plus greed closes the single-step question.** On its own the agreement says only
   that the model knows its own odds — a solver choosing terrible letters would be equally well
   calibrated at a worse rate. It becomes decisive because `coverage` maximises that same number
   at every step, so no other letter could have lowered the expected miss count when it was
   chosen.

The shape is worth recording because it explains where the score is. The chosen letter hits 28.6%
of the time on a three-letter answer and 97.6% on a thirteen-letter one: a short word offers three
positions to use a letter on, a long one offers thirteen. Within a word, the first two guesses
carry a 31.5% miss rate and the ninth 14.4%; the rise at `10+` is survivorship, since a word still
running after ten turns is one that was not solved.

What the probe cannot do is price a *sequence* — a worse letter now for better odds later. That
is the question the audit prices at 1.3 to 3.1 points in the endgame and `information` prices at
−875 in the extreme. The middle game is still unpriced, but it is no longer unexplained: the
misses there are the ones the model called.

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
| abandon on evidence, not on miss arithmetic | a hit does not consume a miss, so "not enough misses to reveal the letters" is not a losing position; it was implemented, measured at −13 points, and replaced by the empty-candidate test | keep the intuitive rule because it looked free |
| skip policy off by default | both implementable rules fire zero times in 8,000 words; a default that never changes behaviour but adds a check per turn is a liability | turn it on because it was built |
| hand-written `Json` instead of Jackson | zero runtime dependencies keeps `mvn -o` working; the protocol has six field types and one level of nesting | add Jackson 2.9, the only version available offline here |
| `HttpHangmanClient` does not retry | retry policy is testable only when it is separable, and call counts are otherwise invisible | retry inside the client, where every protocol test would need a socket |
| seed layout `g + a * games` for best-of-N | attempt 0 of every game is the plain run's session, so best-of-N is provably not worse game by game and each session stays reproducible on its own | `g * attempts + a`, which makes every group's first session new and breaks the comparison |
| concurrent attempts in the harness | they share nothing but immutable state; eight sessions cost one session's wall clock, which is what makes a 1,600-session measurement practical | run them sequentially and pay eight times the wall clock |
| the pattern is rebuilt from the candidates, not stored | `GuessContext` has no pattern field and does not need one: position `i` is revealed iff every candidate agrees there and the letter is known, which is the consistency rule read backwards | add a pattern field to the public selector contract so the audit can read it |
| the audit plays the delegate's letter, always | a measurement that changes the thing measured is an experiment with two variables; this way the audited run's score is identical by construction and the gap is a number rather than a delta with noise | let the audit return the optimal letter and measure the score difference — the difference would be dominated by the ±35-point spread |
| over-budget decisions are refused, not truncated | a truncated recursion prices the last few turns against a whole-word scale, which systematically prefers large reveals — the 875-point mistake | cap the depth and accept a biased answer |
| the three audit limits are CLI flags | the README claims the audit covers 1.6% of choices; a reader must be able to widen that and watch the number, or the claim is unauditable | hard-code the limits and report one row |
| the probe wraps the solver, not the selector | the hit-or-miss answer exists only at the solver layer, and forwarding every call is what makes an instrumented run score identically | infer hits from the candidate set shrinking — a hit on a letter every candidate contains leaves it unchanged |
| one package-private candidate accessor | the probe lives in `solver` and needs the array the solver already holds; a copy per guess would be the only cost the probe adds | rebuild the candidate set from the evidence, duplicating the consistency rule |
| `P(hit)` priced for one letter, not twenty-six | the probe runs once per guess over up to 16k candidates; the selector's full pass would be 26× the work for one number | reuse the selector's accumulation and take one cell |
| deltas are read against their own noise, not pooled | a ten-decision row can swing by 0.3; publishing the row's size and its standard error is what keeps the calibration claim falsifiable | report one total deviation and call it agreement |

## Risks

- The answer distribution is unverifiable. Mitigation: the sensitivity table, reported
  alongside every headline number.
- 17.5% of the ranked list is absent from `words.txt`, so the effective answer pool is
  narrower than the rank list implies. Mitigation: reported in the CLI header, and `blind`
  stays in every report.
- A greedy solver leaves value on the table on wide candidate sets. Partly mitigated: the
  information-gain alternative was built and measured (−875), so "greedy is wrong" is no longer
  an untested intuition; the endgame is priced exactly at 1.3 to 3.1 points; and the per-guess
  probe shows every miss arriving at the rate the model assigned it, so the single-step choice is
  not mispriced. What stays untested is the middle game — a thousand candidates, most misses in
  hand — where the audit's state space does not fit. A calibrated greedy step rules out a wrong
  *letter* there; it does not rule out a worse *sequence*.
- Best-of-N depends on a rule the instructions never state: which of several submissions counts.
  Mitigation: the CLI says so on every best-of run, and the README states which reading each row
  assumes.
- A score without its `--pool` is meaningless for any solver that reads the corpus: the pool
  moves that solver by 107 points across the sweep, and a solver without a prior by 18.
  Mitigation: every report header prints it, and the sweep is in the README.
- The tier reading rests on one historical session plus a replay, not on the server's source.
  Mitigation: the gap it produces is 5σ, and `--difficulty` keeps both readings runnable so the
  weaker one can be checked directly.
- The legacy replay skips letters already guessed, where the original could repeat one. That
  can only raise the legacy score, making every reported gap a lower bound. Mitigation: none
  needed — the direction is known and conservative.
