package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.api.WordState;
import com.bobjackson.hangman.dict.Dictionary;

import java.util.Arrays;
import java.util.Objects;

/**
 * Constraint-propagation solver: maintain every dictionary word still consistent with the
 * evidence, then ask a {@link LetterSelector} which letter to probe next.
 *
 * <h2>The consistency rule</h2>
 * A candidate word {@code w} survives iff, for every position {@code i}:
 * <ol>
 *   <li>if position {@code i} is revealed, then {@code w[i]} equals the revealed letter;</li>
 *   <li>if position {@code i} is still masked, then {@code w[i]} is a letter we have
 *       <b>never guessed</b>.</li>
 * </ol>
 *
 * <p>Rule 2 is the whole trick, and it is stronger than it first looks. Guessing a letter
 * reveals <em>every</em> occurrence of it, so if {@code w[i]} were any letter we had
 * already tried, position {@code i} would no longer be masked. One condition therefore
 * subsumes both "the word contains none of the letters we missed" and "the revealed letter
 * occurs exactly as many times as the mask shows" — the two facts the legacy code tracked
 * separately in a {@code wrongHistory} list and a {@code rightGuess} map, then applied only
 * to the most recent entry of each.
 *
 * <p>State is a mask plus a pattern. There is no history list to keep in sync, no static
 * field to reset between words, and nothing shared with any other solver instance.
 *
 * <h2>Complexity</h2>
 * One filter pass is O(candidates × word length) over primitive arrays, with a single
 * allocation when something is actually eliminated. The legacy equivalent removed elements
 * from a {@code CopyOnWriteArrayList} inside a loop, copying the whole backing array per
 * removal — O(n²) over a 416k-word list, which is the main reason a full game took 86 minutes.
 */
public final class EliminationSolver implements Solver {

    private final Dictionary dictionary;
    private final LetterSelector selector;
    private final int wrongGuessesAllowed;
    private final char[] pattern;

    private int guessedMask;
    private int[] candidates;
    private int wrongGuessesSeen;

    /**
     * @param dictionary          immutable word list; only its length bucket is used
     * @param selector            letter-picking strategy
     * @param wordLength          number of letters in the answer
     * @param wrongGuessesAllowed misses permitted before the word is abandoned
     */
    public EliminationSolver(Dictionary dictionary, LetterSelector selector,
                             int wordLength, int wrongGuessesAllowed) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
        this.selector = Objects.requireNonNull(selector, "selector");
        if (wordLength <= 0) {
            throw new IllegalArgumentException("wordLength must be positive: " + wordLength);
        }
        if (wrongGuessesAllowed <= 0) {
            throw new IllegalArgumentException("wrongGuessesAllowed must be positive: " + wrongGuessesAllowed);
        }
        this.wrongGuessesAllowed = wrongGuessesAllowed;
        this.pattern = new char[wordLength];
        Arrays.fill(this.pattern, WordState.MASK);
        this.candidates = dictionary.indicesOfLength(wordLength);
    }

    /** Starts a solver for the word described by {@code firstState}. */
    public static EliminationSolver forWord(Dictionary dictionary, LetterSelector selector,
                                            WordState firstState, int wrongGuessesAllowed) {
        return new EliminationSolver(dictionary, selector, firstState.length(), wrongGuessesAllowed);
    }

    @Override
    public char nextGuess() {
        int wrongGuessesLeft = Math.max(0, wrongGuessesAllowed - wrongGuessesSeen);
        char guess = selector.select(new GuessContext(dictionary, candidates, guessedMask, wrongGuessesLeft));

        if (guess < 'A' || guess > 'Z') {
            throw new IllegalStateException("selector returned a non-letter: '" + guess + '\'');
        }
        if ((guessedMask & LetterFrequency.bitOf(guess)) != 0) {
            // Repeating a guess wastes nothing score-wise on a well-behaved server, but it
            // makes no progress either, and the legacy code could reach this state through
            // a corrupted letter list. Treat it as a programming error.
            throw new IllegalStateException("selector returned already-guessed letter '" + guess + '\'');
        }
        return guess;
    }

    @Override
    public void observe(char guess, WordState state) {
        Objects.requireNonNull(state, "state");
        if (state.length() != pattern.length) {
            throw new IllegalArgumentException(
                    "response length " + state.length() + " does not match the word length " + pattern.length);
        }
        if (guess < 'A' || guess > 'Z') {
            throw new IllegalArgumentException("guess must be A-Z, got '" + guess + '\'');
        }

        guessedMask |= LetterFrequency.bitOf(guess);
        wrongGuessesSeen = state.wrongGuessCountOfCurrentWord();
        for (int i = 0; i < pattern.length; i++) {
            pattern[i] = state.word().charAt(i);
        }
        candidates = retainConsistent(candidates);
    }

    @Override
    public int candidateCount() {
        return candidates.length;
    }

    /** The mask as currently understood, e.g. {@code "*A**Y"}. For diagnostics. */
    public String currentPattern() {
        return new String(pattern);
    }

    /** Misses still available on this word. */
    public int wrongGuessesLeft() {
        return Math.max(0, wrongGuessesAllowed - wrongGuessesSeen);
    }

    private int[] retainConsistent(int[] current) {
        int[] kept = new int[current.length];
        int n = 0;
        for (int idx : current) {
            if (isConsistent(dictionary.wordAt(idx))) {
                kept[n++] = idx;
            }
        }
        // Return the original array untouched when nothing was eliminated, so a hit that
        // confirms every candidate costs no allocation at all.
        return n == current.length ? current : Arrays.copyOf(kept, n);
    }

    private boolean isConsistent(String word) {
        for (int i = 0; i < word.length(); i++) {
            char actual = word.charAt(i);
            if (pattern[i] == WordState.MASK) {
                // A masked position can never hold a letter we already asked about.
                if ((guessedMask & (1 << (actual - 'A'))) != 0) {
                    return false;
                }
            } else if (pattern[i] != actual) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "EliminationSolver[pattern=" + currentPattern()
                + ", candidates=" + candidates.length
                + ", guessed=" + Integer.bitCount(guessedMask) + ']';
    }
}
