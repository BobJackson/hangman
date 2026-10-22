# hangman-ng

A solver for the [Strikingly Hangman interview test](https://github.com/joycehan/strikingly-interview-test-instructions/tree/new),
built as something that can be **measured** instead of something that has to be run against a
server that no longer exists.

Java 21 · Maven · JUnit 5 + AssertJ · no runtime dependencies.

The Java 8 project in the repository root is untouched. This module is independent: its own
`pom.xml`, its own packages, its own tests.

---

## Status

| Milestone | Scope | State |
|---|---|---|
| **M1** | offline simulator, constraint solver, CLI, unit tests | **done** |
| **M2** | frequency-weighted priors, exponent knob | **done** — corpus priors are the default |
| **C** | the legacy algorithm replayed in-module as a control group | **done** — and it re-reads M1's headline |
| M3 | exact expected-miss minimisation and a skip policy | not started |
| M4 | real HTTP client, parallel session selection | not started |

## Measured results

Every number below is reproducible: `20` sessions of `80` words from a single seed, weighted
answer sampling, cumulative difficulty tiers, corpus priors (the default) unless stated
otherwise. A full session costs about 80 ms, so the whole table takes seconds.

### Baseline and the prior effect

| variant | mean | median | sd | min | max | solved | wrong/word |
|---|---:|---:|---:|---:|---:|---:|---:|
| `coverage`, corpus priors (default) | **1329.45** | 1331.50 | 54.32 | 1214 | 1409 | 96.1% | 2.59 |
| `occurrences`, corpus priors | 1328.00 | 1325.50 | 52.64 | 1233 | 1405 | 96.1% | 2.61 |
| `coverage`, uniform priors (`--priors uniform`) | **1154.10** | 1167.50 | 80.82 | 978 | 1301 | 90.5% | 3.67 |
| `occurrences`, uniform priors | 1152.40 | 1170.00 | 78.80 | 978 | 1261 | 90.6% | 3.71 |

An earlier draft of this section called the near-match with the 1326 the legacy implementation
reported in 2017 the strongest available evidence that the simulator's answer distribution was
right. **The control group below withdraws that claim.** 1326 is a single session, not a mean,
and the two numbers came from different programs playing different games: `coverage` under
cumulative tiers versus the legacy client under the disjoint buckets it actually used. It was a
coincidence that read as a validation.

What survives is weaker but sound: replaying the legacy logic under cumulative tiers scores
841.13, five standard deviations below 1326, so the server cannot have used that reading. Under
the bucket reading it scores 1260.24 ± 50.30, and 1326 sits 1.31 standard deviations above that
mean — an ordinary single-session draw. That is the agreement worth trusting, because it
compares one program to itself.

The two counting modes are statistically indistinguishable (`sd` ≈ 53, difference ≈ 1.5).
`COVERAGE` stays the default because it estimates P(hit) directly; the measurement says the
distinction is not worth arguing about.

### What the prior strength is worth

`--exponent` tempers the rank decay: `1` is a pure Zipf, `0` removes the ranking signal
entirely. At `0` the measurement collapses onto `--priors uniform` exactly — the same seed
produces a byte-identical report — so the knob has a verified left endpoint.

| exponent | mean | sd | solved | wrong/word |
|---|---:|---:|---:|---:|
| 0 (flat) | 1150.60 | 73.17 | 90.3% | 3.68 |
| 0.25 | 1311.02 | 53.33 | 95.5% | 2.72 |
| **0.5 (default)** | **1322.29** | 50.91 | 95.8% | 2.63 |
| 0.75 | 1317.17 | 52.98 | 95.5% | 2.64 |
| 1.0 | 1308.60 | 54.36 | 95.4% | 2.72 |

100 sessions per row, so the standard error of each mean is about `sd / 10` ≈ 5 points.

Two readings, in order of how much they matter:

1. **Whether to use the prior at all is the whole decision.** Going from flat to 0.25 is worth
   160 points — more than every other knob in this README combined.
2. **How strong to make it barely matters.** 0.25 through 1.0 spans 14 points, under three
   standard errors. The default sits in the middle of that plateau. Calling it "tuned" would be
   overreading noise; the defensible claim is that any non-zero exponent works and this one
   needs no attention.

### Where the points go

| tier | uniform solved | wrong/word | corpus solved | wrong/word |
|---|---:|---:|---:|---:|
| 1-20 (`<=5`) | 79.3% | 6.33 | 90.5% | 4.49 |
| 21-40 (`<=8`) | 89.8% | 4.51 | 96.5% | 3.15 |
| 41-60 (`<=12`) | 93.0% | 3.52 | 97.3% | 2.54 |
| 61-80 (`>12`) | 100% | 0.34 | 100% | 0.20 |

Short words cost almost everything, and long words cost almost nothing — the latter confirming
the original author's intuition that by the time words get long, a big dictionary makes them
nearly free. The first tier is where the remaining score is hiding.

### The control group: what the old code was worth

The legacy algorithm is reimplemented in `legacy/` — not as a competitor, but as the control
group. Same simulator, same seeds, same answer pool, same scoring formula. That turns "the old
code had defects" from a code-review opinion into a number, and it settles a question the rest
of this README had been guessing at.

100 sessions per cell, so each mean carries a standard error of 3.5 to 9 points:

| difficulty | variant | mean | sd | solved | wrong/word |
|---|---|---:|---:|---:|---:|
| bucketed | `coverage` | **1404.65** | 34.75 | 97.5% | 1.94 |
| bucketed | `legacy` | 1260.24 | 50.30 | 93.2% | 2.89 |
| cumulative | `coverage` | **1322.29** | 50.91 | 95.8% | 2.63 |
| cumulative | `legacy` | 841.13 | 92.42 | 76.3% | 4.74 |

**The server used disjoint buckets.** The legacy client as published scored 1326 once. The
replay scores 1260.24 under buckets — 1.31 standard deviations above that mean, an ordinary
draw — and 841.13 under cumulative tiers, 5.25 standard deviations below it. A 5σ gap is not a
bad session; it is a different game. The instructions' `<=5` wording describes a world the
original program could never have scored 1326 in, so the disjoint reading is what the server
did, and the client's tier logic was matching it rather than corrupting it.

**What the defects cost depends entirely on that answer.** In the world the server actually
played, the rewrite is worth **144 points** (1404.65 − 1260.24, about 24 standard errors — not
a close call). In the world the instructions literally describe, it is worth **481 points**,
because the tier mismatch becomes destructive: the `blind` column, 0.0% in every other report
in this README, jumps to 38.7% and 70.0% on tiers two and three, meaning the selected bucket
held no word that could explain the evidence at all. Neither number is wrong; they price the
same defect against two different worlds, and only the first one was ever played.

The 144 points are not evenly spread. Measured against the bucket reading:

| tier | `coverage` solved / wrong | `legacy` solved / wrong | points of the 144 |
|---|---:|---:|---:|
| 1-20 (3-5) | 90.0% / 4.63 | 73.5% / 6.71 | ~107 |
| 21-40 (6-8) | 100.0% / 2.18 | 99.4% / 3.12 | ~21 |
| 41-60 (9-12) | 100.0% / 0.75 | 100.0% / 1.21 | ~9 |
| 61-80 (13+) | 100.0% / 0.20 | 100.0% / 0.51 | ~6 |

Three quarters of the gap is the first tier, which is the same place the prior helps and the
same place M3 is aimed. The rest is easy to explain. Under buckets the candidate set is already
correct — the server only asks words inside the bucket it named, so the legacy filtering never
loses the answer, and its several filtering defects have nowhere to bite. What it lacks is a way
to *order* that set: it can rank letters by how often they occur inside the surviving words, but
it cannot weight the words themselves. At sixteen thousand candidates on the first tier, that is
the difference between 73.5% and 90.0% solved. Under cumulative tiers the filtering defects do
bite, which is where the 481 comes from.

Reproduce with `--variants legacy` (or `coverage,legacy` to see both in one run).

### Sensitivity: what the remaining guesses are worth

The control group settled the tier question — that one is no longer a guess. Two properties of
the server are still modelled rather than known, and the harness measures them:

| assumption changed | mean score | solved | wrong/word |
|---|---:|---:|---:|
| answers drawn uniformly instead of by frequency | 1240.50 | 93.1% | 3.12 |
| 5% of every call fails (retry layer enabled) | 1329.45 | 96.1% | 2.59 |

The fault-injection row is byte-identical to the clean run. That is the point of it: retries
must not change the score, and here they provably do not.

### Pool size defines the game, it does not tune the solver

The pool is the set of words the server may ask for. Narrowing it changes what is being played,
not how well it is played — and the two priors move in opposite directions.

| answer pool | corpus priors | uniform priors |
|---|---:|---:|
| top 1,000 | 1461.84 | 1023.59 |
| top 5,000 | 1446.80 | 1110.95 |
| top 20,000 | 1412.60 | 1138.60 |
| top 100,000 | 1322.29 | 1150.60 |

100 sessions per cell, `coverage`.

Under a flat prior a small pool is *harder*: its words are short, and a flat prior cannot
separate the 16,480 candidates of five letters or fewer. Under corpus priors it is *easier*:
the answers are exactly the words the prior ranks highest. The gap shrinks as the pool grows
(+438 at 1,000 words, +172 at 100,000) because a larger pool is mostly long tail, where no
prior helps much.

Both readings are correct for their own world, which is why every report header prints the pool
and why scores are only comparable at a fixed `--pool`.

## Quick start

```bash
cd hangman-ng
mvn test                                # 83 tests, no network, no sleeps
mvn -q package && java -jar target/hangman-ng.jar
```

The CLI plays against the offline simulator and prints one report per variant:

```
hangman-ng | offline evaluation
dictionary: /path/to/words.txt: 416264 words from 416264 lines (rejected 0, duplicates 0)
ranking:    rank[size=96886, exponent=0.5, scale=1000000]
priors:     from corpus rank (rank[size=96886, exponent=0.5, scale=1000000])
answer pool: 79971 words from the top 96886 ranked (16915 not in the dictionary, dropped)
  1-20 (<=5)      16480 words, length 3-5
  ...

=== coverage | 20 sessions x 80 words, weighted sampling, cumulative tiers, seed 42 ===
sessions 20   words 1600   correct 1537 (96.1%)   wrong 4151 (2.59/word)
score    mean 1329.45   median 1331.50   sd 54.32   min 1214   max 1409
tier            words  solved    rate  wrong/word  guesses  blind
1-20 (<=5)        400     362   90.5%        4.49     8.40   0.0%
...
```

Useful flags: `--games`, `--seed`, `--pool`, `--sampling`, `--difficulty`, `--priors`,
`--exponent`, `--variants` (including `legacy`), `--failure-rate`, `--dictionary`. `--help`
lists them all.

## Why a simulator

The interview's endpoint is a 2014 Heroku app. Even if it were still up, one game would be a
minute of network round-trips and one data point with a standard deviation around 80 points —
enough to tell a 200-point improvement from noise, and nothing more. `SimulatedServer`
implements the same five actions in-process, so a session takes milliseconds and any
experiment replays exactly from its seed.

Three things it models deliberately, because they are where the specification is ambiguous:

- **Answers are drawn by corpus frequency**, not uniformly. A uniform draw over 100k words
  serves things no product would ask for. `--sampling uniform` measures that pessimistic
  world too.
- **Tier bounds are a knob, and the control group settles which reading the server used.** The
  instructions write `<=5`, `<=8`, `<=12`, `>12`, which reads as cumulative; the legacy client
  built disjoint buckets instead. Replaying the legacy logic under both readings shows that
  only the bucket reading can produce the 1326 it actually scored, so the client was following
  the server, not corrupting it. `--difficulty` defaults to `cumulative` because that is what
  the document says — but the `bucketed` rows are the ones that describe the game that was
  played, and every headline number above is the cumulative one.
- **Faults are injected before any state change**, so a retried call cannot double-count a
  guess. The retry layer is therefore safe by construction, not by luck.

## The scoring formula

The instructions never state how the score is computed. It is recoverable from the two worked
examples they publish:

```
78 correct, 234 wrong -> 78*20 - 234 = 1326   (matches the reported score)
77 correct, 233 wrong -> 77*20 - 233 = 1307   (matches the reported score)
```

so `score = 20 * correctWordCount - totalWrongGuessCount`. This lives in
`GameResult.scoreOf` and `isScoreConsistent()` turns it into a runtime assertion: any server
whose reported score disagrees with the formula is a server whose rules have changed.

The practical consequence: a guess is worth making whenever `p(hit) * 20` exceeds the expected
cost of the misses it might cause. That is why the solver maximises coverage — and why a skip
policy (M3) can only ever be a marginal improvement over guessing.

## Architecture

| package | responsibility | depends on |
|---|---|---|
| `api` | typed records for the five actions; no transport | — |
| `dict` | immutable dictionary, length buckets, 26-bit masks, rank-weighted priors | `api` |
| `solver` | constraint elimination + letter selection | `api`, `dict` |
| `legacy` | the original client's decision logic, replayed as a control group | `api`, `dict`, `solver` |
| `engine` | the game state machine and observation hooks | `api`, `solver` |
| `sim` | simulated server, harness, benchmark report | all of the above |
| `transport` | retry decorator with bounded exponential backoff | `api` |
| `cli` | argument parsing and reporting | `dict`, `legacy`, `sim`, `solver` |

Dependencies point one way. The solver knows nothing about HTTP, scoring or game flow; the
engine owns no per-word state, so it can play eighty words or eighty thousand with no reset
logic.

**One rule does the work of two data structures.** A candidate word survives iff every
revealed position matches and every masked position holds a letter that has *never been
guessed*. Because a hit reveals every occurrence, the second clause already subsumes both
"contains none of the missed letters" and "the revealed letter occurs exactly this many times".
The legacy implementation tracked those two facts separately, in a `wrongHistory` list and a
`rightGuess` map, and replayed only the newest entry of each — which loses nothing while the
set is filtered in place, but leaves the state impossible to rebuild, which is why the original
rebuilt its candidate lists for every word.

## Defects this rewrite structurally removes

| legacy defect | how it is gone |
|---|---|
| length filter only ran after a wrong guess, so a first-guess hit kept every length in play | filtering by length happens at construction, from a pre-built index |
| short words pruned via `catch (StringIndexOutOfBoundsException)` | no exception is used as control flow; words are validated up front |
| shared `CopyOnWriteArrayList` mutated while filtering, 80 times per game | `Dictionary` is immutable; filtering produces a new `int[]` |
| static letter-frequency list rebuilt with `removeAll` / re-add | frequency order is an immutable constant, queried through a bitmask |
| `lettersByFrequency.get(0)` threw once all 26 letters were guessed | `IllegalStateException` with a message that names the real cause |
| reflection into `LinkedHashMap.tail` | no reflection; the code runs on JDK 9 and later, including this one |
| 80 and 10 baked into the client | read from `startGame` and never hard-coded |
| tier buckets keyed by word number, but grouped by word length | a tier is a config value that selects words by length, so the two can never disagree |

## Data

`src/main/resources/word-ranks.txt.gz` — 96,886 words, 395 KB — is generated by
`tools/fetch-data.sh` from
[david47k/top-english-wordlists](https://github.com/david47k/top-english-wordlists)
(`top_english_words_lower_100000.txt`), which compiles Google Books ngram frequencies
(1950-2012) and is licensed **CC BY 3.0**. Words are filtered to pure `A-Z`, length 3-20,
de-duplicated, upper-cased, and kept in frequency order: a word's line number *is* its rank.

Two word lists are in play, and they are not the same thing:

- **Answer pool** — the most frequent ranked words that also exist in the dictionary. Answers
  are drawn from here.
- **Dictionary** — `../words.txt` (416,264 words), the same file the original implementation
  used, so the solver has every word the server plausibly knows.

Of the 96,886 ranked words, 16,915 (17.5%) have no entry in `words.txt`. The solver is told to
ignore them as answers by construction; the `blind` column in every report stays at 0.0%,
which is the check that the intersection actually happened.

## Tests

83 tests, no network, no sleeps, no flakiness:

| class | what it pins down |
|---|---|
| `DictionaryLoaderTest` | CRLF residue, rejection and duplicate counts, length buckets |
| `DictionaryTest` | masks, deduplication, weight validation, index immutability |
| `RankWordWeightingTest` | rank order, weight decay, `topN`, exponent 0 collapsing to uniform |
| `SimulationConfigTest` | tier boundaries and both readings of the documented bounds |
| `EliminationSolverTest` | the consistency rule, repeated letters, dictionary-miss fallback |
| `SimulatedServerTest` | reveal-all-occurrences, miss accounting, determinism, fault injection |
| `GameEngineTest` | advertised word count, abandonment, listener contract, failure propagation |
| `RetryingHangmanClientTest` | retry budget, non-retryable errors, backoff curve, interrupts |
| `BenchmarkReportTest` | median, sample standard deviation, per-tier aggregation |
| `LegacyTiersTest` | length grouping, word-number selection, empty buckets |
| `LegacySolverTest` | first guess, length filter on misses only, stale counts, tie-break order |

Retries are tested with an injected `Sleeper`, so the backoff curve is asserted exactly without
waiting for it.

## Limits

- The answer distribution is a model, not the server. The sensitivity table above is the honest
  bound on what that is worth.
- **The default `--difficulty` is not the server's.** The control group shows the original server
  used disjoint buckets, but the CLI still defaults to the cumulative reading because that is
  what the instructions say. Every headline number in this README is therefore a slightly
  harder game than the one actually played, and the two are not interchangeable: the same code
  scores 1404.65 under buckets and 1322.29 under cumulative. Compare like with like.
- The legacy replay is faithful to the decisions, not to the runtime. It skips letters already
  guessed, where the original could repeat one; that deviation can only raise the legacy score,
  so every gap reported here is a lower bound on the real one.
- The solver is greedy, not optimal. It maximises the chance of a hit this turn; it does not
  minimise the expected number of misses over the rest of the word (M3).
- A word is abandoned only when the misses run out. Deliberately skipping a word whose
  candidate set is still wide is left to M3, and the baseline suggests the gain is small: at
  three misses spent, the break-even probability of eventually solving is already below 40%.
