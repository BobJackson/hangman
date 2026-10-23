# What the measurements say

English | [中文](zh-CN/findings.md)

One page. The evidence behind every number here, and how to reproduce it, is in
[the README](../README.md); the reasoning behind the design is in
[the spec](specs/2026-10-22-hangman-redesign-design.md).

## The headline

**1404.65** mean score per session (sd 34.75, 100 sessions, seed 42, 80 words, weighted answers,
bucketed tiers). A player who already knew every answer and never missed would score **1600**.

The gap is therefore **195 points**, and this page is about what is in it.

## What each change was worth

| change | points per session | how it was measured |
|---|---:|---|
| rewrite against the original client | **+144** | same simulator, same seed, same rules |
| corpus priors instead of uniform | **+119** | the largest single effect anywhere in the project |
| best of 8 submissions | **+48** | a server rule, not an algorithm; costs no score to adopt |
| abandoning words, with perfect foresight | **+10** | and exactly 0 without it — measured twice |
| exact lookahead, endgame only | **+1.3 to +3.1** | 1.6% to 3.7% of decisions priced exactly |
| maximising information instead of hits | **−875** | the wrong objective, measured rather than argued |
| pricing every guess against its own odds | **0** | nothing to fix; see below |

Two things are worth noticing in that table. The biggest gain came from a rules question, not from
the solver. And the two most sophisticated things built — information gain and exact lookahead —
are worth −875 and +3 respectively.

## The two negative results

**M5 — the endgame is already nearly optimal.** `ExactScore` prices one decision out completely, so
its answer is the true expectation rather than an estimate. On the states small enough to finish,
9% to 21% of `coverage`'s choices are not optimal, and the whole of that is worth 1.26 to 3.14
points per session — 0.12 points per wrong decision. The expectation is nearly flat across the
sensible letters near the end of a word: with one miss left and three candidates, the certain
reveal and the one-in-three shot are both worth `20/3`. Widening the reach 2.3× buys 1.9 points
and doubles the wall clock.

**M6 — every miss was priced, and the price was right.** The audit reaches 1.6% of decisions, so a
second probe wraps the solver and records all 70,788 of them, each against the `P(hit)` that
motivated it. Half of the guesses (49.2%) land on a letter every surviving candidate contains —
certain hits, which cost 0.9% of the misses. The other half misses at 42.7% against a model that
predicted about 43%. Row by row across ten `P(hit)` tenths, eighteen word lengths and ten guess
positions, the largest deviation outside a 53-decision row is one standard error.

Calibration alone would only show the model knows its own odds. It becomes decisive together with
the fact that `coverage` maximises that same number at every step: no other letter could have
lowered the expected miss count at the moment it was chosen.

## Where the 195 points are

| tier | misses per session | unsolved per session | points |
|---|---:|---:|---:|
| 1-20 (3-5) | 92.6 | 2.01 | **132.8** |
| 21-40 (6-8) | 43.6 | 0.01 | 43.8 |
| 41-60 (9-12) | 15.0 | 0 | 15.0 |
| 61-80 (13+) | 4.0 | 0 | 4.0 |

Two thirds of it is the short-word tier, and the reason is arithmetic, not strategy. A three-letter
answer offers three positions to place a letter on, and the best available letter is present in
only 28.6% of the surviving words; a thirteen-letter answer offers thirteen positions and the best
letter is present in 97.6% of them. That is why a three-letter word misses 71.4% of the time and a
thirteen-letter word 2.4%.

So the gap decomposes into roughly 155 points of misses and 40 points of words that were never
solved — 202 of the 8,000, almost all of them in the first tier. Neither is reachable by choosing
better letters: the letters chosen were already the best available, and their misses arrived at
the rate quoted.

## What would have to be true to get the rest

The only remaining lever is a **sequence**: take a worse letter now, in exchange for better odds on
the next three. That has been measured at both ends and not in the middle.

- In the extreme, `information` pays for information in misses and loses 875 points. Exploration
  is not free here: the score changes by `21p − 1` per turn, which is monotone in `p`.
- In the endgame, exact pricing says the best sequence is worth 1.3 to 3.1 points.
- In the middle — a thousand candidates, most misses still in hand — the state space is about
  `2^15` per decision at 26 letter evaluations each. Nothing here prices it.

A claim that the middle is worth 100 points would therefore be unsupported, and so would a claim
that it is worth nothing. What can be said is narrower and firmer: **the letter chosen at each step
was the best available, and the misses it produced were the ones it promised.** Any remaining
improvement has to come from a planner that does not exist yet, not from correcting a mistake in
this one.

## Reproducing

```bash
cd hangman-ng
mvn test                                     # 172 tests, no network, no sleeps
mvn -q package
java -jar target/hangman-ng.jar --variants coverage,legacy,information
java -jar target/hangman-ng.jar --variants coverage,audited
java -jar target/hangman-ng.jar --variants coverage,anatomy
```

All three runs use `--games 100 --seed 42`, which the defaults do not; the README prints the
exact flags beside every table it reports.
