package com.bobjackson.hangman.api;

import java.util.Objects;

/**
 * Response to {@code nextWord} and {@code guessWord}: the partially revealed word plus
 * progress counters.
 *
 * @param word                       the mask, e.g. {@code "**PP*"}; {@code '*'} hides a letter
 * @param totalWordCount             how many words have been served so far (1-based)
 * @param wrongGuessCountOfCurrentWord incorrect guesses already spent on this word
 */
public record WordState(String word, int totalWordCount, int wrongGuessCountOfCurrentWord) {

    /** The character the server uses to hide an unrevealed letter. */
    public static final char MASK = '*';

    public WordState {
        Objects.requireNonNull(word, "word");
        if (word.isEmpty()) {
            throw new IllegalArgumentException("word must not be empty");
        }
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c != MASK && (c < 'A' || c > 'Z')) {
                throw new IllegalArgumentException(
                        "word must contain only A-Z or '" + MASK + "', found '" + c + "' in \"" + word + '"');
            }
        }
        if (totalWordCount < 0) {
            throw new IllegalArgumentException("totalWordCount must not be negative: " + totalWordCount);
        }
        if (wrongGuessCountOfCurrentWord < 0) {
            throw new IllegalArgumentException(
                    "wrongGuessCountOfCurrentWord must not be negative: " + wrongGuessCountOfCurrentWord);
        }
    }

    /** True once no masked position remains, i.e. the word has been fully revealed. */
    public boolean solved() {
        return word.indexOf(MASK) < 0;
    }

    /** Number of letters in the answer, revealed or not. */
    public int length() {
        return word.length();
    }

    /** How many positions are still hidden. */
    public int maskedCount() {
        int n = 0;
        for (int i = 0; i < word.length(); i++) {
            if (word.charAt(i) == MASK) {
                n++;
            }
        }
        return n;
    }
}
