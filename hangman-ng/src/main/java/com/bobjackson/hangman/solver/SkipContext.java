package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.dict.Dictionary;

import java.util.Objects;

/**
 * Everything a {@link SkipPolicy} is allowed to look at when deciding whether to give up.
 *
 * <p>Deliberately narrower than {@link GuessContext}: a skip decision needs to know what is
 * still hidden and how much rope is left, but not which letter the selector would pick. Keeping
 * the two contexts apart means a policy cannot quietly start choosing letters.
 *
 * @param dictionary       the immutable word list
 * @param candidates       indices into {@code dictionary} still consistent with the evidence
 * @param pattern          the mask as currently understood, e.g. {@code "*A**Y"}
 * @param guessedMask      bit {@code i} set when {@code 'A' + i} has been guessed
 * @param wrongGuessesLeft misses still permitted on this word before the server ends it
 */
public record SkipContext(Dictionary dictionary, int[] candidates, String pattern,
                          int guessedMask, int wrongGuessesLeft) {

    public SkipContext {
        Objects.requireNonNull(dictionary, "dictionary");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(pattern, "pattern");
        if (wrongGuessesLeft < 0) {
            throw new IllegalArgumentException("wrongGuessesLeft must not be negative: " + wrongGuessesLeft);
        }
    }

    /** True when {@code letter} has already been guessed. */
    public boolean isGuessed(char letter) {
        return (guessedMask & LetterFrequency.bitOf(letter)) != 0;
    }

    /**
     * Distinct hidden letters in {@code word}, i.e. how many more hits a perfect run needs
     * before it is fully revealed. Zero means the word is already solved.
     */
    public int hitsRequired(String word) {
        if (word.length() != pattern.length()) {
            throw new IllegalArgumentException(
                    "candidate length " + word.length() + " does not match pattern length " + pattern.length());
        }
        int letters = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '*') {
                letters |= LetterFrequency.bitOf(word.charAt(i));
            }
        }
        return Integer.bitCount(letters);
    }
}
