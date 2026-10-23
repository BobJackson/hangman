# hangman-ng

English | [中文](docs/zh-CN/README.md)

A solver for the [Strikingly Hangman interview test](https://github.com/joycehan/strikingly-interview-test-instructions/tree/new),
built as something that can be **measured** instead of something that has to be run against a
server that no longer exists.

Java 21 · Maven · JUnit 5 + AssertJ · no runtime dependencies.

The Java 8 project in the repository root is untouched. This module is independent: its own
`pom.xml`, its own packages, its own tests.

If you only read one page, read [docs/findings.md](docs/findings.md): what each change was
worth, the two negative results, and where the remaining 195 points are. Everything below is the
evidence behind it.

---

## Status

| Milestone | Scope | State |
|---|---|---|
| **M1** | offline simulator, constraint solver, CLI, unit tests | **done** |
| **M2** | frequency-weighted priors, exponent knob | **done** — corpus priors are the default |
| **C** | the legacy algorithm replayed in-module as a control group | **done** — and it re-reads M1's headline |
| **M3** | a skip policy: give a word up rather than run out of misses | **done** — measured, and switched off |
| **M4** | real HTTP client, parallel session selection | **done** — the original endpoint is gone, so both halves of the protocol are now served locally and dialled over a socket; best-of-N is worth +48 |
| **M5** | an exact lookahead audit on the states that can be solved out | **done** — 1.3 to 3.1 points per session, on the 1.6% of decisions that can be priced |
| **M6** | a calibrated probe of every guess: was each miss avoidable? | **done** — no; the misses arrive at exactly the rate the model assigned them |

## Measured results

Every number below is reproducible: `100` sessions of `80` words from a single seed, weighted
answer sampling, bucketed difficulty tiers — the reading the original server used, settled by
the control group below — and corpus priors wherever priors apply. A full session costs about
120 ms, so the whole table takes a couple of minutes.

### Baseline and the prior effect

| variant | mean | median | sd | min | max | solved | wrong/word |
|---|---:|---:|---:|---:|---:|---:|---:|
| `coverage`, corpus priors (default) | **1404.65** | 1412.00 | 34.75 | 1316 | 1474 | 97.5% | 1.94 |
| `occurrences`, corpus priors | 1401.07 | 1405.00 | 38.41 | 1295 | 1469 | 97.4% | 1.96 |
| `coverage`, uniform priors (`--priors uniform`) | **1286.04** | 1292.50 | 51.70 | 1150 | 1401 | 94.1% | 2.75 |
| `occurrences`, uniform priors | 1281.28 | 1285.00 | 50.88 | 1128 | 1395 | 94.1% | 2.80 |

Priors are worth **119 points** (1404.65 − 1286.04) against a standard error of about 6, which
is the largest single effect measured anywhere in this README.

The two counting modes are statistically indistinguishable (`sd` ≈ 36, difference ≈ 3.6).
`COVERAGE` stays the default because it estimates P(hit) directly; the measurement says the
distinction is not worth arguing about.

An earlier draft of this section read a similar-looking number (1329) as the strongest evidence
that the simulator matched the real server, which reported 1326. That was wrong twice: 1326 is
a single session rather than a mean, and the comparison crossed two different programs playing
two different tier readings. The control group below does that comparison properly, and it is
what moved the default `--difficulty` from `cumulative` to `bucketed`.

### What the prior strength is worth

`--exponent` tempers the rank decay: `1` is a pure Zipf, `0` removes the ranking signal
entirely. At `0` the measurement collapses onto `--priors uniform` exactly — the same seed
produces a byte-identical report — so the knob has a verified left endpoint.

| exponent | mean | sd | solved | wrong/word |
|---|---:|---:|---:|---:|
| 0 (flat) | 1286.04 | 51.70 | 94.1% | 2.75 |
| 0.25 | 1396.72 | 33.28 | 97.3% | 1.99 |
| **0.5 (default)** | **1404.65** | 34.75 | 97.5% | 1.94 |
| 0.75 | 1401.62 | 36.13 | 97.4% | 1.95 |
| 1.0 | 1395.29 | 39.39 | 97.2% | 2.00 |

100 sessions per row, so the standard error of each mean is about `sd / 10` ≈ 3.5 points.

Two readings, in order of how much they matter:

1. **Whether to use the prior at all is the whole decision.** Going from flat to 0.25 is worth
   111 points — more than every other knob in this README combined.
2. **How strong to make it barely matters.** 0.25 through 1.0 spans 9 points, under three
   standard errors. `0.5` happens to be the highest point measured, but it leads its neighbours
   by about one standard error, so calling it "tuned" would be overreading noise. The
   defensible claim is that any non-zero exponent works and this one needs no attention.

### Where the points go

| tier | uniform solved | wrong/word | corpus solved | wrong/word |
|---|---:|---:|---:|---:|
| 1-20 (3-5) | 77.1% | 6.48 | 90.0% | 4.63 |
| 21-40 (6-8) | 99.4% | 3.08 | 100.0% | 2.18 |
| 41-60 (9-12) | 100% | 1.12 | 100.0% | 0.75 |
| 61-80 (13+) | 100% | 0.32 | 100.0% | 0.20 |

Short words cost almost everything, and long words cost almost nothing — the latter confirming
the original author's intuition that by the time words get long, a big dictionary makes them
nearly free. The first tier is where the remaining score is hiding: 4.63 misses per word on a
quarter of the words accounts for 60% of every miss in the game, against an average of 1.94.
The last tier is solved every time by both priors.

The first tier is identical under both readings — both cap it at five letters. The bucket reading
bites on the second tier, where it asks for 6-8 letters specifically (33,887 words) instead of
anything up to eight (50,367). That is why this table is milder than the cumulative one.

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

### Giving a word up is worth ten points, and only with perfect foresight

A solver may now abandon a word. `Solver.shouldAbandon()` is asked once per turn, before the
next guess; the engine breaks the word loop and the report grows a `skipped` column. The default
answer is never, which is why every other table in this README is unchanged, byte for byte.

Three rules were measured. The baseline is the first row; the reverted one was a 20-session
run, the other two 100:

| rule | how often it fired | mean | result |
|---|---|---:|---|
| never (default) | — | 1404.65 | baseline |
| "not enough misses left to reveal the hidden letters" | 2.8% of the 13+ tier | 1397.25 (20 sessions) | **−13**, reverted |
| "no dictionary word explains the evidence" (`hopeless`) | never | 1404.65 | identical |
| "20·P(solve) < (1−P)·misses left" (`expected-value`) | never | 1404.65 | identical, 1.8× slower |

The reverted rule is the interesting one. It reads as free — give up when the remaining reveals
cost more misses than you have — and it is wrong, in the direction that costs score: **a hit does
not consume a miss**, and a word allows unlimited guesses. A thirteen-letter answer with eleven
distinct letters is revealed by eleven hits and zero misses. Pricing hits as misses skipped eleven
words of four hundred in the long-word tier, every one of them a word that tier solves anyway, and
that is 13 points gone.

What survives is the one state that cannot be recovered: an empty candidate set, meaning the
answer is not in `words.txt`. That is real, and it never happens here, because the answer pool
is intersected with the dictionary (`blind` is 0.0% everywhere). The expected-value rule is
strictly better founded and also never fires: over 8,000 words the arithmetic never clears a
20-point prize against a 1-point-per-miss cost.

So what is the whole idea worth? Only words that end with the misses exhausted can be improved,
and only by the misses they had not yet spent. In the 100-session baseline, 201 of the 2,000
first-tier words ended with all ten misses gone; no word on any other tier did. A clairvoyant who
walks away after the k-th guess saves `201 × (10 − k) / 100` points per session:

| walks away after | points per session |
|---|---:|
| 0 guesses | 20.10 |
| 3 | 14.07 |
| 5 | 10.05 |

Ten points, 0.7%, for a solver that already knows which words it will fail. A solver that does
not know is the version that was measured twice, and it scored zero. Reproduce with
`--variants coverage,hopeless,expected-value`.

### Maximising information loses 875 points

`information` replaces the hit-rate objective with expected information gain:
`p·log2(n/hits) + (1−p)·log2(n/(n−hits))`, the expected reduction in `log2(candidates)`.

| variant | mean | sd | solved | wrong/word |
|---|---:|---:|---:|---:|
| `coverage` | **1404.65** | 34.75 | 97.5% | 1.94 |
| `information` | 529.50 | 107.56 | 67.8% | 6.95 |

It is not close, and it fails in both directions at once — fewer words solved *and* more misses
per word — which is what a wrong objective looks like rather than an unlucky one.

The arithmetic says the same thing in one line. Per turn the score changes by
`20p − (1 − p) = 21p − 1`, which is monotone in `p`: the best letter *this turn* is the one most
likely to hit. Information gain is the right objective only when exploration is free, and here it
is not — a miss costs a point and a hit costs nothing, so a coin-flip letter is a bad buy however
much it teaches. The selector's own scores show the trap: `gain(50/100) = 1.0` bit, while
`gain(90/100) = 0.469`, so it prefers a 50/50 letter over a near-certain hit by a factor of two.
On the 13+ tier, where candidates differ by a letter or two, it saturates the miss budget on
probes.

### Best of N: the largest gain available, and it is not in the algorithm

Nothing in the score depends on the solver being unlucky. If more than one session may be played,
the highest score is the one to keep, and the spread buys more than any strategy change measured
here. `--best-of N` plays each game N times, keeps the best attempt, and runs the attempts of one
game concurrently: 100 games per row, `coverage`, same seeds.

| sessions per game | mean | vs baseline | sd | min | max | wall clock |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1404.65 | — | 34.75 | 1316 | 1474 | 11.4 s |
| 2 | 1425.02 | +20.37 | 27.58 | 1324 | 1478 | 11.4 s |
| 4 | 1440.96 | +36.31 | 19.09 | 1391 | 1482 | 12.6 s |
| 8 | 1453.04 | +48.39 | 16.13 | 1413 | 1484 | 24.1 s |
| 16 | 1460.66 | +56.01 | 11.49 | 1430 | 1485 | 49.8 s |

This is the maximum of N samples, so the expected gain is `σ·e_N` where `e_N` is the expected
maximum of N standard normal draws — 0.564, 1.029, 1.424, 1.766 for N = 2, 4, 8, 16. With
σ = 34.75 that predicts +19.6, +35.8, +49.5 and +61.4 against the measured +20.4, +36.3, +48.4
and +56.0. Five numbers, four of them within a point and a half, is enough to say the mechanism
is understood rather than merely observed.

Three things it buys, in order of usefulness: the mean rises, the floor rises faster (1316 →
1430, which is what matters if a score has to clear a bar), and the spread halves. Two caveats.
The instructions never say which submission counts — "best" assumes a server that keeps the
highest score, and if it kept the *last* one the same result needs the best session submitted
last, which needs the sessions alive at the same time. And a best-of report's per-tier table
describes the winning session, so it reads optimistic; only the score line is unbiased.

+48 points from repeating the same program is worth remembering next to the +144 the rewrite was
worth, and the ~10 that the best possible skip rule was worth. The cheapest remaining points were
never in the algorithm.

### Looking further ahead is worth 1.3 to 3.1 points, on the 1.6% of decisions that can be priced

The last unmeasured question was whether the greedy step is actually the best one. Answering it in
general needs a planner over the belief state, and that state space does not fit: a miss does not
shrink the candidate set, it only spends a letter, so the reachable states grow like the ways to
choose `k` of the letters a word does not use — `C(15, k)` summed to `k = 10` is about 2^15 per
decision, each costing 26 letter evaluations. One decision would be hundreds of millions of
operations. Truncating the search is worse than not running it at all: a leaf priced as if it were
the whole word, against a search that only looks two turns deep, systematically prefers letters
that reveal a lot now — the exact direction that cost the information-gain variant 875 points.

So the audit goes the other way. `audited` plays `coverage`'s letter, unchanged, and only on
states small enough to solve out exactly it computes what every available letter was worth and
records the difference. An audited run therefore scores bit for bit what the plain run scores, and
the number it prints is a measurement rather than a bet.

100 sessions, `coverage`, same seed. Each row is one setting of the three limits —
`--audit-candidates`, `--audit-misses`, `--audit-budget`:

| limits (candidates / misses / states) | decisions priced | not optimal | points per game foregone |
|---|---:|---:|---:|
| 40 / 3 / 5,000 (default) | 1,121 of 70,788 | 13.8% | 1.26 |
| 40 / 3 / 20,000 | 1,146 of 70,788 | 14.9% | 1.30 |
| 40 / 5 / 5,000 | 2,015 of 70,788 | 9.1% | 1.39 |
| 80 / 3 / 5,000 | 1,260 of 70,788 | 20.9% | 1.69 |
| 80 / 5 / 20,000 | 2,617 of 70,788 | 18.7% | 3.14 |

Two things to read here, and one that must not be read.

**Being wrong is common; being wrong expensively is not.** Between 9% and 21% of the priced
decisions are not optimal, but the widest row is worth 314 points across 100 sessions over 2,617
decisions — 0.12 points each. The gaps are small because the expectation is nearly flat across the
reasonable letters near the end of a word: with one miss left and three candidates, the certain
reveal and the one-in-three shot are both worth `20/3`.

**Widening the limits barely moves the answer.** Reaching 2.3× further than the default roughly
doubles the wall clock and buys 1.9 points per game. Whatever lookahead is worth here, it does not
scale with the fraction of decisions inspected.

**What must not be read: that lookahead is worth three points in total.** The audit prices 1.6% to
3.7% of all choices. Middle-game decisions — a thousand candidates, most misses still in hand —
are exactly the ones the budget cannot reach, and exactly the ones with the most room to be wrong.
The table says the endgame, where a word is actually saved or lost and the twenty-point swing is
largest, is nearly optimally played. It says nothing about the rest of the game, and it cannot.

What it does say is enough to order the work. Against a remaining gap of 195 points per session,
an endgame that is short by 0.6% to 1.6% is not where the next milestone is. Reproduce with
`--variants coverage,audited`; the three limits exist so the coverage claim can be checked rather
than taken.

### Every miss was priced, and the price was right

The audit above covers the 1.6% of decisions small enough to solve out; this section covers all of
them, and asks the other question. Not "was there a better letter?" but "were the misses
avoidable?".

`anatomy` wraps the solver and plays exactly what `coverage` plays — 1404.65 against 1404.65 on the
same seed, at 11.8 s against 10.8 s — and for every guess records the `P(hit)` that motivated it
next to what the server answered. 100 sessions, 70,788 guesses, 15,495 misses.

| bucket | guesses | share | misses | share of misses | observed |
|---|---:|---:|---:|---:|---:|
| `P(hit)` 0.9 to 1.0 | 34,816 | 49.2% | 139 | 0.9% | 0.4% |
| `P(hit)` below 0.9 | 35,972 | 50.8% | 15,356 | 99.1% | 42.7% |
| all | 70,788 | 100% | 15,495 | 100% | 21.9% |

**Half of every guess is free, and all of the damage is in the other half.** 34,816 of the 70,788
guesses land on a letter that every surviving candidate contains: a certain hit, which cannot cost
a miss and does not need one. Those guesses produce 0.9% of the misses. The other half is where
the score is lost, and it loses at 42.7% against a model that predicted about 43%.

**The prediction holds row by row.** Ten rows by `P(hit)`, eighteen by word length, ten by guess
index. The largest deviation outside the smallest row is 0.011 on a 7,453-decision row — one
standard error. The `0.0-0.1` row is off by 0.071, on 53 decisions whose own standard error is
0.077.

Two of the splits explain the shape of the score. Selected rows:

| word length | 3 | 4 | 5 | 6 | 8 | 13 |
|---|---:|---:|---:|---:|---:|---:|
| observed miss rate | 71.4% | 57.4% | 44.5% | 35.5% | 18.8% | 2.4% |

A three-letter answer gives the solver three positions to work with, and the letter `coverage`
picks is present in only 28.6% of the surviving words. A thirteen-letter answer gives it thirteen
positions, and the letter it picks is present in 97.6% of them. That is the whole reason tier one
is where the points are: not a shortage of candidates, but a shortage of places to put a letter.

| guess within the word | 1 | 2 | 3 | 5 | 9 | 10+ |
|---|---:|---:|---:|---:|---:|---:|
| observed miss rate | 31.5% | 31.1% | 26.3% | 20.1% | 14.4% | 18.3% |

The first two guesses of a word are the expensive ones, because nothing has been revealed yet. The
rise at `10+` is survivorship: a word still being guessed after ten turns is a word that was not
solved, so only the hard ones remain in that row.

**What the calibration does and does not prove.** On its own, agreement between observed and
predicted misses says only that the model knows its own odds; a solver choosing terrible letters
would be just as well calibrated at a worse rate. It becomes decisive because `coverage` maximises
that same number at every step: a calibrated, greedily maximised `P(hit)` means no other letter
could have lowered the expected miss count at the moment it was chosen. The only way to beat it is
to take a worse letter now in exchange for better odds later — the sequence question, which the
section above prices at 1.3 to 3.1 points in the endgame and `information` prices at minus 875
points in the extreme.

The 4.63 misses per first-tier word are therefore not four and a half mistakes. They are four and
a half coin flips, called at the odds the model published and landing at the rate it published.
There is no mispricing to fix, no weight to retune, and no bug hiding in the candidate set. What
is left is the middle game, unpriced by the audit and not explained by this probe either — but the
misses there are the ones the model called, not ones it failed to see.

Reproduce with `--variants coverage,anatomy`. The probe prints three tables and changes nothing.

### Sensitivity: what the remaining guesses are worth

The control group settled the tier question — that one is no longer a guess. Two properties of
the server are still modelled rather than known, and the harness measures them:

| assumption changed | mean score | solved | wrong/word |
|---|---:|---:|---:|
| answers drawn uniformly instead of by frequency | 1341.99 | 95.8% | 2.39 |
| 5% of every call fails (retry layer enabled) | 1404.65 | 97.5% | 1.94 |

The fault-injection row is byte-identical to the clean run. That is the point of it: retries
must not change the score, and here they provably do not.

### Pool size is difficulty, but only against a prior

The pool is the set of words the server may ask for. Narrowing it changes what is being played,
but only one of the two solvers notices.

| answer pool | corpus priors | uniform priors |
|---|---:|---:|
| top 1,000 | 1511.47 | 1279.23 |
| top 5,000 | 1498.22 | 1297.46 |
| top 20,000 | 1468.32 | 1286.65 |
| top 100,000 | 1404.65 | 1286.04 |

100 sessions per cell, `coverage`, bucketed tiers.

Corpus priors lose **107 points** as the pool widens. Uniform priors move between 1279 and 1297
with no trend at all — an 18-point spread against a standard error near 5, small beside the
107-point slope. So the pool is not a difficulty knob in general; it is a difficulty knob for a
solver that reads the corpus. A small pool is easier for corpus priors because its answers are
exactly the words those priors rank highest. It does nothing for a solver with no ranking
signal, which has to pick letters the same way no matter which words are eligible.

The gap therefore narrows from +232 at 1,000 words to +119 at 100,000: a larger pool is mostly
long tail, where no prior helps much.

Scores are comparable only at a fixed `--pool`, which is why every report header prints it.
Under cumulative tiers the uniform column *did* slope (1024 → 1151), because there a short
answer could land in a tier whose candidate set spans the whole short end of the dictionary.
The bucket reading removes that path, and with it the effect.

## Quick start

```bash
cd hangman-ng
mvn test                                # 180 tests, no network, no sleeps
mvn -q package && java -jar target/hangman-ng.jar
```

The CLI plays against the offline simulator and prints one report per variant:

```
hangman-ng | offline evaluation
dictionary: /path/to/words.txt: 416264 words from 416264 lines (rejected 0, duplicates 0)
ranking:    rank[size=96886, exponent=0.5, scale=1000000]
priors:     from corpus rank (rank[size=96886, exponent=0.5, scale=1000000])
answer pool: 79971 words from the top 96886 ranked (16915 not in the dictionary, dropped)
  1-20 (3-5)      16480 words, length 3-5
  21-40 (6-8)     33887 words, length 6-8
  ...

=== coverage | 20 sessions x 80 words, weighted sampling, bucketed tiers, seed 42 ===
sessions 20   words 1600   correct 1562 (97.6%)   wrong 3035 (1.90/word)
score    mean 1410.25   median 1416.00   sd 38.96   min 1320   max 1474
tier            words  solved    rate  wrong/word  guesses skipped  blind
1-20 (3-5)        400     362   90.5%        4.49     8.40    0.0%   0.0%
21-40 (6-8)       400     400  100.0%        2.22     8.21    0.0%   0.0%
...
```

Useful flags: `--games`, `--seed`, `--pool`, `--sampling`, `--difficulty`, `--priors`,
`--exponent`, `--variants` (`coverage`, `occurrences`, `information`, `audited`, `anatomy`,
`hopeless`, `expected-value`, `legacy`), `--best-of`, `--failure-rate`, `--dictionary`, and the
three audit limits `--audit-candidates`, `--audit-misses`, `--audit-budget`. `--help` lists them
all.

### Over a socket

`--serve` turns the process into the other end of the documented protocol, so the HTTP path can
be exercised without the 2014 server:

```bash
java -jar target/hangman-ng.jar --serve --port 8080                    # terminal 1
java -jar target/hangman-ng.jar --endpoint http://127.0.0.1:8080/game/on --games 1   # terminal 2
```

`MockHangmanServer` forwards every action to a per-session `SimulatedServer`, so the rules served
over the wire are the rules the harness measures — there is no second implementation to drift.
The seed stays fixed, so each session replays the same answers. Sessions are named by the server
rather than by the simulator, because a fixed seed hands every caller the same session id and
two clients would overwrite each other. `--endpoint` reads both rule parameters from the
`startGame` reply and ignores `--words` / `--allow`, so a disagreement about the wire format
shows up as a game that ends after a different number of words.

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
- **Tier bounds follow the server, not the document.** The instructions write `<=5`, `<=8`,
  `<=12`, `>12`, which reads as cumulative; the legacy client built disjoint buckets instead.
  Replaying the legacy logic under both readings shows that only the bucket reading can produce
  the 1326 it actually scored, so the client was following the server. `--difficulty` therefore
  defaults to `bucketed`; `cumulative` stays available for anyone who wants to play the game
  the instructions describe.
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
cost of the misses it might cause. That is why the solver maximises coverage. Four alternatives
to that greedy step were measured instead of argued about, and all four came back small or
negative: an information-gain objective loses 875 points, giving words up is worth ten with
perfect foresight, an exact lookahead over the decisions small enough to solve out is worth 1.3 to
3.1 points per session, and a per-guess audit of all 70,788 choices finds the misses arriving at
exactly the rate the model assigned them.

## Architecture

| package | responsibility | depends on |
|---|---|---|
| `api` | typed records for the five actions; no transport | — |
| `dict` | immutable dictionary, length buckets, 26-bit masks, rank-weighted priors | `api` |
| `solver` | constraint elimination, letter selection, skip policy, exact lookahead audit, per-guess miss probe | `api`, `dict` |
| `legacy` | the original client's decision logic, replayed as a control group | `api`, `dict`, `solver` |
| `engine` | the game state machine and observation hooks | `api`, `solver` |
| `sim` | simulated server, harness, benchmark report | all of the above |
| `transport` | `HttpHangmanClient` over `java.net.http` with a hand-written JSON codec, retry decorator, and `MockHangmanServer` serving the same protocol | `api` |
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

180 tests, no network, no sleeps, no flakiness:

| class | what it pins down |
|---|---|
| `DictionaryLoaderTest` | CRLF residue, rejection and duplicate counts, length buckets |
| `DictionaryTest` | masks, deduplication, weight validation, index immutability |
| `RankWordWeightingTest` | rank order, weight decay, `topN`, exponent 0 collapsing to uniform |
| `SimulationConfigTest` | tier boundaries and both readings of the documented bounds |
| `EliminationSolverTest` | the consistency rule, repeated letters, dictionary-miss fallback |
| `SkipPolicyTest` | empty candidate sets, the hit-versus-miss arithmetic, the negative-binomial tail |
| `InformationGainLetterSelectorTest` | the 50/50 peak, symmetry, empty-candidate fallback |
| `ExactScoreTest` | every letter against an uncached reference, the implicit pattern, the budget |
| `AuditedLetterSelectorTest` | the delegate's letter is played unchanged, gaps are found and counted |
| `MissAnatomyTest` | the delegate's letter is played unchanged, hits read off the miss counter, one letter priced |
| `MissAnatomySummaryTest` | bucket boundaries, pooled guess indices, delta sign, sample validation |
| `SimulatedServerTest` | reveal-all-occurrences, miss accounting, determinism, fault injection |
| `GameEngineTest` | advertised word count, abandonment, listener contract, failure propagation |
| `EvaluationHarnessTest` | best-of-N keeps the maximum, seeds nest, concurrent runs are identical |
| `RetryingHangmanClientTest` | retry budget, non-retryable errors, backoff curve, interrupts |
| `JsonTest` | the protocol's scalar types, escapes, malformed and truncated input |
| `HttpHangmanClientTest` | rule parameters read rather than assumed, session forwarding, non-200s |
| `MockHangmanServerTest` | a full session through both real halves at once, session lifecycle, 400/405 paths |
| `BenchmarkReportTest` | median, sample standard deviation, per-tier aggregation, abandoned counts |
| `LegacyTiersTest` | length grouping, word-number selection, empty buckets |
| `LegacySolverTest` | first guess, length filter on misses only, stale counts, tie-break order |

Retries are tested with an injected `Sleeper`, so the backoff curve is asserted exactly without
waiting for it. The HTTP client is tested against `com.sun.net.httpserver.HttpServer` on
`127.0.0.1`, which ships with the JDK: no mocking framework, no network, and the real
`java.net.http` stack on both ends.

`MockHangmanServerTest` is the one place where both halves are real at the same time: the
client's requests are parsed by the server's own reader and the server's replies by the
client's own parser. A stub agrees with whatever the client expects, so it cannot catch the two
of them disagreeing about a field name; this test can.

## Limits

- The answer distribution is a model, not the server. The sensitivity table above is the honest
  bound on what that is worth.
- The tier reading is inferred from one data point plus a faithful replay, not from the
  server's source. It is the strongest inference available — a 5σ gap separates the two
  readings — but it does rest on 1326 being an honest session. `--difficulty cumulative`
  reproduces the other world (1322.29 instead of 1404.65) for anyone who wants to see it.
- The legacy replay is faithful to the decisions, not to the runtime. It skips letters already
  guessed, where the original could repeat one; that deviation can only raise the legacy score,
  so every gap reported here is a lower bound on the real one.
- The solver is greedy, not optimal. The alternatives were measured rather than argued about: an
  information-gain objective costs 875 points, giving words up is worth ten with perfect foresight
  and less than nothing without it, and an exact lookahead is worth 1.3 to 3.1 points per session on
  the decisions small enough to solve out. That last number covers 1.6% to 3.7% of all choices. The
  per-guess probe narrows what remains: every miss in a 100-session run arrived at the rate the
  model itself assigned it, so the greedy step is not mispriced and no letter was chosen on a
  weight that was wrong. What that probe cannot do is price a *sequence* — a worse letter now for
  better odds later — and the middle game, where most of those sequences would live, is still
  unmeasured. No estimate of it is offered.
- `HttpHangmanClient` has never talked to the real server, because there is no real server. Both
  halves of the documented protocol are now implemented in this module and tested against each
  other over a socket, which proves they agree with each other — not that they agree with the
  2014 original. The prose in the instructions is still the only evidence about the shape itself.
- Best-of-N assumes a scoring rule the instructions never state. If the server kept the last
  submission rather than the best, the same result needs the best session submitted last, which
  requires holding every session open. The gain itself is not in doubt — it is the maximum of
  N samples either way.
