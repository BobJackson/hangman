package com.bobjackson.hangman.api;

/**
 * Transport-agnostic view of the Hangman game server.
 *
 * <p>The five methods map 1:1 onto the five actions in the official specification
 * ({@code startGame}, {@code nextWord}, {@code guessWord}, {@code getResult},
 * {@code submitResult}). Session state lives in the implementation, not in the caller,
 * so {@link com.bobjackson.hangman.engine.GameEngine} never has to thread a session id
 * through its logic.
 *
 * <p>Two implementations exist: an offline {@code SimulatedServer} used for evaluation,
 * and (from milestone M4) an HTTP-backed client. Keeping them behind this interface is
 * what makes the solver testable without a network.
 */
public interface HangmanClient extends AutoCloseable {

    /** Starts a session and reads the rule parameters the server actually advertises. */
    StartGame startGame(String playerId);

    /** Asks for the next word. Also the way to skip the current word. */
    WordState nextWord();

    /**
     * Guesses a single letter.
     *
     * @param letter an uppercase {@code A-Z} character; the server accepts nothing else
     */
    WordState guess(char letter);

    /** Reads the score so far without ending the session. */
    GameResult getResult();

    /** Ends the session and records the score. */
    GameResult submitResult();

    /** Releases any underlying transport resources. No-op for in-process implementations. */
    @Override
    default void close() {
        // nothing to release by default
    }
}
