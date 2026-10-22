package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.dict.Dictionary;

/**
 * Everything a {@link LetterSelector} needs to pick a letter.
 *
 * @param dictionary       the immutable word list; never modified
 * @param candidates       indices into {@code dictionary} still consistent with the evidence
 * @param guessedMask      bit {@code i} set when {@code 'A' + i} has been guessed
 * @param wrongGuessesLeft misses still permitted on this word before it is abandoned
 */
public record GuessContext(Dictionary dictionary, int[] candidates, int guessedMask, int wrongGuessesLeft) {

    public GuessContext {
        if (dictionary == null) {
            throw new IllegalArgumentException("dictionary must not be null");
        }
        if (candidates == null) {
            throw new IllegalArgumentException("candidates must not be null");
        }
        if (wrongGuessesLeft < 0) {
            throw new IllegalArgumentException("wrongGuessesLeft must not be negative: " + wrongGuessesLeft);
        }
    }

    /** True when {@code letter} has already been guessed. */
    public boolean isGuessed(char letter) {
        return (guessedMask & LetterFrequency.bitOf(letter)) != 0;
    }

    /** True when at least one dictionary word still matches the evidence. */
    public boolean hasCandidates() {
        return candidates.length > 0;
    }
}
