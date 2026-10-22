package com.bobjackson.hangman.engine;

import com.bobjackson.hangman.api.GameResult;
import com.bobjackson.hangman.api.HangmanClient;
import com.bobjackson.hangman.api.StartGame;
import com.bobjackson.hangman.api.WordState;
import com.bobjackson.hangman.solver.Solver;
import com.bobjackson.hangman.solver.SolverFactory;

import java.util.Objects;

/**
 * Drives one full session: start, then repeatedly take a word, guess at it, and move on,
 * finally submitting the score.
 *
 * <p>All game rules come from {@link StartGame} returned by the server rather than from
 * constants, and the loop condition is expressed in terms of what the server reports, so
 * a server that changes {@code numberOfWordsToGuess} or the miss allowance needs no code
 * change here.
 *
 * <p>A fresh {@link Solver} is created per word. The solver owns the per-word state; the
 * engine owns none, which is why it is trivial to run eighty words or eighty games.
 *
 * <p>The loop asks the solver once per turn whether the word is worth continuing. That is
 * the only place abandoning can happen: the engine never decides it, and a solver that never
 * wants to is unaffected.
 */
public final class GameEngine {

    private final HangmanClient client;
    private final SolverFactory solverFactory;
    private final GameListener listener;

    public GameEngine(HangmanClient client, SolverFactory solverFactory) {
        this(client, solverFactory, GameListener.NONE);
    }

    public GameEngine(HangmanClient client, SolverFactory solverFactory, GameListener listener) {
        this.client = Objects.requireNonNull(client, "client");
        this.solverFactory = Objects.requireNonNull(solverFactory, "solverFactory");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Plays a complete game and submits it.
     *
     * @return the server's final score report
     */
    public GameResult play(String playerId) {
        StartGame game = client.startGame(playerId);
        listener.onGameStart(game);

        int allowedForEachWord = game.numberOfGuessAllowedForEachWord();

        while (true) {
            WordState state = client.nextWord();
            listener.onWordStart(state);

            Solver solver = solverFactory.create(state, allowedForEachWord);
            int guessesUsed = 0;
            boolean abandoned = false;

            while (!state.solved() && state.wrongGuessCountOfCurrentWord() < allowedForEachWord) {
                if (solver.shouldAbandon()) {
                    abandoned = true;
                    break;
                }
                char guess = solver.nextGuess();
                state = client.guess(guess);
                solver.observe(guess, state);
                guessesUsed++;
                listener.onGuess(state, guess, solver.candidateCount());
            }

            listener.onWordFinished(state, solver.candidateCount(), guessesUsed, abandoned);

            if (state.totalWordCount() >= game.numberOfWordsToGuess()) {
                break;
            }
        }

        GameResult result = client.submitResult();
        listener.onGameFinished(result);
        return result;
    }
}
