package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.api.WordState;
import com.bobjackson.hangman.dict.Dictionary;

import java.util.Objects;

/**
 * A read-only probe wrapped around a solver: it records what each guess cost, and changes nothing.
 *
 * <h2>Why a decorator rather than a branch inside the solver</h2>
 * The probe has to be provably invisible, or the numbers it produces describe a different
 * strategy than the one being measured. A {@link Solver} decorator is the smallest shape that
 * makes that true by construction: every call is forwarded, {@link #nextGuess} returns the
 * delegate's letter unchanged, and the score of an instrumented run is bit-for-bit the score of
 * a plain one. That is the same rule {@link AuditedLetterSelector} follows one level down, and it
 * means a surprising number here can never be explained by "the probe changed the game".
 *
 * <h2>Why hits are read off the server's counter</h2>
 * A guess and its answer are two separate calls, so the probe holds the guess as pending until
 * {@link #observe} brings the reply. The reply says what a miss looks like directly —
 * {@code wrongGuessCountOfCurrentWord} either went up or it did not — which is more reliable than
 * inferring a hit from the candidate set shrinking: a hit on a letter every candidate already
 * contains leaves the set exactly as it was.
 */
public final class MissAnatomy implements Solver {

    private final EliminationSolver delegate;
    private final Dictionary dictionary;
    private final MissAnatomySummary summary;
    private final int wordLength;

    private int guessesMade;
    private int missesSeen;
    private Pending pending;

    /**
     * @param delegate  the solver being measured; every call is forwarded untouched
     * @param dictionary the word list the delegate reasons over, used to price the chosen letter
     * @param summary   where finished guesses are accumulated; shared across words and sessions
     * @param wordLength letters in this word's answer
     */
    public MissAnatomy(EliminationSolver delegate, Dictionary dictionary,
                       MissAnatomySummary summary, int wordLength) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
        this.summary = Objects.requireNonNull(summary, "summary");
        if (wordLength <= 0) {
            throw new IllegalArgumentException("wordLength must be positive: " + wordLength);
        }
        this.wordLength = wordLength;
    }

    @Override
    public char nextGuess() {
        // Read before the delegate runs: candidateIndices() is the delegate's own array and it is
        // replaced, not mutated, on the next observe. Reading first also prices the letter against
        // the same candidate set the selector saw.
        int[] candidates = delegate.candidateIndices();
        int candidatesBefore = candidates.length;
        int missesLeftBefore = delegate.wrongGuessesLeft();

        char guess = delegate.nextGuess();
        guessesMade++;
        pending = new Pending(guess, guessesMade, candidatesBefore, missesLeftBefore, missesSeen,
                hitProbability(dictionary, candidates, guess));
        return guess;
    }

    @Override
    public void observe(char guess, WordState state) {
        Objects.requireNonNull(state, "state");
        delegate.observe(guess, state);

        int misses = state.wrongGuessCountOfCurrentWord();
        Pending recorded = pending;
        if (recorded != null && recorded.letter() == guess) {
            summary.record(new MissAnatomySummary.GuessSample(
                    wordLength, recorded.guessIndex(), recorded.candidatesBefore(),
                    recorded.missesLeftBefore(), guess, recorded.pHit(),
                    misses == recorded.missesSeen()));
            pending = null;
        }
        missesSeen = misses;
    }

    @Override
    public int candidateCount() {
        return delegate.candidateCount();
    }

    @Override
    public boolean shouldAbandon() {
        return delegate.shouldAbandon();
    }

    /**
     * The model's own estimate that {@code letter} hits, by the formula
     * {@link FrequencyLetterSelector} maximises: the weight of candidates containing the letter
     * over the weight of all candidates.
     *
     * <p>One letter is priced here rather than all twenty-six, because this runs once per guess
     * over a candidate set that can still hold thousands of words.
     *
     * @return zero when there are no candidates, which matches the blind-guess case
     */
    static double hitProbability(Dictionary dictionary, int[] candidates, char letter) {
        Objects.requireNonNull(dictionary, "dictionary");
        Objects.requireNonNull(candidates, "candidates");
        int bit = LetterFrequency.bitOf(letter);
        long total = 0;
        long hits = 0;
        for (int idx : candidates) {
            long weight = dictionary.weightAt(idx);
            total += weight;
            if ((dictionary.maskAt(idx) & bit) != 0) {
                hits += weight;
            }
        }
        return total == 0 ? 0.0 : (double) hits / total;
    }

    @Override
    public String toString() {
        return "miss-anatomy[" + delegate + ']';
    }

    /** A guess that has been sent but not yet answered. */
    private record Pending(char letter, int guessIndex, int candidatesBefore, int missesLeftBefore,
                           int missesSeen, double pHit) {
    }
}
