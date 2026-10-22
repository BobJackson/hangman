package com.bobjackson.hangman.engine;

import com.bobjackson.hangman.api.GameResult;
import com.bobjackson.hangman.api.StartGame;
import com.bobjackson.hangman.api.WordState;

/**
 * Observation hooks for a game in progress.
 *
 * <p>Present because the legacy client had no visibility at all: an 86-minute run printed
 * nothing but a stack trace on failure, so there was no way to tell which kind of word was
 * costing points. The harness uses {@link #onWordFinished} to build a per-difficulty-tier
 * breakdown; the CLI uses it to print progress.
 *
 * <p>All methods are no-ops by default, so callers override only what they need.
 */
public interface GameListener {

    /** A listener that discards everything. */
    GameListener NONE = new GameListener() {
    };

    /** The session has been opened and the server's rule parameters are known. */
    default void onGameStart(StartGame game) {
    }

    /** A new word has been served, fully masked. */
    default void onWordStart(WordState state) {
    }

    /** A letter was guessed and the server answered. */
    default void onGuess(WordState state, char guess, int candidateCount) {
    }

    /**
     * The current word is over, either revealed or abandoned.
     *
     * @param state          the final state for this word
     * @param candidateCount candidates left when it ended; 0 means the answer was not in our dictionary
     * @param guessesUsed    letters spent on this word
     * @param abandoned      true when the solver gave up rather than running out of misses
     */
    default void onWordFinished(WordState state, int candidateCount, int guessesUsed, boolean abandoned) {
    }

    /** The session was submitted. */
    default void onGameFinished(GameResult result) {
    }
}
