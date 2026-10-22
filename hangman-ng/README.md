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
| M2 | frequency-weighted priors in the solver | previewed here (`--priors ranked`), not yet the default |
| M3 | exact expected-miss minimisation and a skip policy | not started |
| M4 | real HTTP client, parallel session selection | not started |

## Measured results

Every number below is reproducible: `20` sessions of `80` words from a single seed, weighted
answer sampling, cumulative difficulty tiers, uniform priors unless stated otherwise.
A full session costs about 80 ms, so the whole table takes seconds.

### Baseline and the prior effect

| variant | mean | median | sd | min | max | solved | wrong/word |
|---|---:|---:|---:|---:|---:|---:|---:|
| `coverage`, uniform priors (M1 baseline) | **1154.10** | 1167.50 | 80.82 | 978 | 1301 | 90.5% | 3.67 |
| `occurrences`, uniform priors | 1152.40 | 1170.00 | 78.80 | 978 | 1261 | 90.6% | 3.71 |
| `coverage`, ranked priors (M2 preview) | **1329.45** | 1331.50 | 54.32 | 1214 | 1409 | 96.1% | 2.59 |
| `occurrences`, ranked priors | 1328.00 | 1325.50 | 52.64 | 1233 | 1405 | 96.1% | 2.61 |

The ranked-priors row lands within one point of the 1326 the legacy implementation reported
against the real server in 2017 — with 78/80 correct and 234 wrong. That agreement is the
strongest evidence available that the simulator's answer distribution is not wildly wrong: the
model reproduces a number it was not tuned to reproduce.

The two counting modes are statistically indistinguishable (`sd` ≈ 53, difference ≈ 1.5).
`COVERAGE` stays the default because it estimates P(hit) directly; the measurement says the
distinction is not worth arguing about.

### Where the points go

| tier | uniform priors solved | wrong/word | ranked priors solved | wrong/word |
|---|---:|---:|---:|---:|
| 1-20 (`<=5`) | 79.3% | 6.33 | 90.5% | 4.49 |
| 21-40 (`<=8`) | 89.8% | 4.51 | 96.5% | 3.15 |
| 41-60 (`<=12`) | 93.0% | 3.52 | 97.3% | 2.54 |
| 61-80 (`>12`) | 100% | 0.34 | 100% | 0.20 |

Short words cost almost everything, and long words cost almost nothing — the latter confirming
the original author's intuition that by the time words get long, a big dictionary makes them
nearly free. The first tier is where the remaining score is hiding.

### Sensitivity: what the unverifiable assumptions are worth

The real server is gone, so three properties of it are guesses. The harness measures them
rather than asserting them.

| assumption changed | mean score | solved | wrong/word |
|---|---:|---:|---:|
| tier bounds read as disjoint buckets, not cumulative | 1410.25 | 97.6% | 1.90 |
| answers drawn uniformly instead of by frequency | 1240.50 | 93.1% | 3.12 |
| 5% of every call fails (retry layer enabled) | 1329.45 | 96.1% | 2.59 |
| answer pool cut to the top 5,000 (uniform priors) | 1116.70 | 89.4% | 3.93 |
| answer pool cut to the top 1,000 (uniform priors) | 1036.00 | 85.9% | 4.23 |

The fault-injection row is byte-identical to the clean run. That is the point of it: retries
must not change the score, and here they provably do not.

## Quick start

```bash
cd hangman-ng
mvn test                                # 71 tests, no network, no sleeps
mvn -q package && java -jar target/hangman-ng.jar
```

The CLI plays against the offline simulator and prints one report per variant:

```
hangman-ng | offline evaluation
dictionary: /path/to/words.txt: 416264 words from 416264 lines (rejected 0, duplicates 0)
ranking:    rank[size=96886, exponent=0.5, scale=1000000]
priors:     uniform (every surviving candidate counts once; the M1 baseline)
answer pool: 79971 words from the top 96886 ranked (16915 not in the dictionary, dropped)
  1-20 (<=5)      16480 words, length 3-5
  ...

=== coverage | 20 sessions x 80 words, weighted sampling, cumulative tiers, seed 42 ===
sessions 20   words 1600   correct 1448 (90.5%)   wrong 5878 (3.67/word)
score    mean 1154.10   median 1167.50   sd 80.82   min 978   max 1301
tier            words  solved    rate  wrong/word  guesses  blind
1-20 (<=5)        400     317   79.3%        6.33    10.10   0.0%
...
```

Useful flags: `--games`, `--seed`, `--pool`, `--sampling`, `--difficulty`, `--priors`,
`--variants`, `--failure-rate`, `--dictionary`. `--help` lists them all.

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
- **Tier bounds are read literally** (`<=5`, `<=8`, `<=12`, `>12`), which is the wording the
  instructions use. The legacy implementation treated them as disjoint buckets, a different
  game; `--difficulty bucketed` measures the difference, and it is worth 80 points.
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
| `engine` | the game state machine and observation hooks | `api`, `solver` |
| `sim` | simulated server, harness, benchmark report | all of the above |
| `transport` | retry decorator with bounded exponential backoff | `api` |
| `cli` | argument parsing and reporting | `dict`, `sim`, `solver` |

Dependencies point one way. The solver knows nothing about HTTP, scoring or game flow; the
engine owns no per-word state, so it can play eighty words or eighty thousand with no reset
logic.

**One rule does the work of two data structures.** A candidate word survives iff every
revealed position matches and every masked position holds a letter that has *never been
guessed*. Because a hit reveals every occurrence, the second clause already subsumes both
"contains none of the missed letters" and "the revealed letter occurs exactly this many times".
The legacy implementation tracked those two facts separately, in a `wrongHistory` list and a
`rightGuess` map, and applied them only to the most recent entry of each.

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

71 tests, no network, no sleeps, no flakiness:

| class | what it pins down |
|---|---|
| `DictionaryLoaderTest` | CRLF residue, rejection and duplicate counts, length buckets |
| `DictionaryTest` | masks, deduplication, weight validation, index immutability |
| `RankWordWeightingTest` | rank order from the bundled resource, weight decay, `topN` |
| `SimulationConfigTest` | tier boundaries and both readings of the documented bounds |
| `EliminationSolverTest` | the consistency rule, repeated letters, dictionary-miss fallback |
| `SimulatedServerTest` | reveal-all-occurrences, miss accounting, determinism, fault injection |
| `GameEngineTest` | advertised word count, abandonment, listener contract, failure propagation |
| `RetryingHangmanClientTest` | retry budget, non-retryable errors, backoff curve, interrupts |
| `BenchmarkReportTest` | median, sample standard deviation, per-tier aggregation |

Retries are tested with an injected `Sleeper`, so the backoff curve is asserted exactly without
waiting for it.

## Limits

- The answer distribution is a model, not the server. The sensitivity table above is the honest
  bound on what that is worth.
- The solver is greedy, not optimal. It maximises the chance of a hit this turn; it does not
  minimise the expected number of misses over the rest of the word (M3).
- A word is abandoned only when the misses run out. Deliberately skipping a word whose
  candidate set is still wide is left to M3, and the baseline suggests the gain is small: at
  three misses spent, the break-even probability of eventually solving is already below 40%.
