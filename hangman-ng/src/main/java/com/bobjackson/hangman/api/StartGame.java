package com.bobjackson.hangman.api;

import java.util.Objects;

/**
 * Response to {@code startGame}.
 *
 * <p>The rule parameters are read from the server rather than hard-coded: the legacy
 * implementation baked in {@code 80} and {@code 10}, which silently breaks if the server
 * ever changes them.
 *
 * @param sessionId                          identifies the session; required on every later call
 * @param numberOfWordsToGuess               how many words finish the game
 * @param numberOfGuessAllowedForEachWord    how many <em>incorrect</em> guesses each word allows
 */
public record StartGame(String sessionId,
                        int numberOfWordsToGuess,
                        int numberOfGuessAllowedForEachWord) {

    public StartGame {
        Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (numberOfWordsToGuess <= 0) {
            throw new IllegalArgumentException("numberOfWordsToGuess must be positive: " + numberOfWordsToGuess);
        }
        if (numberOfGuessAllowedForEachWord <= 0) {
            throw new IllegalArgumentException(
                    "numberOfGuessAllowedForEachWord must be positive: " + numberOfGuessAllowedForEachWord);
        }
    }
}
