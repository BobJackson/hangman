package com.bobjackson.hangman.sim;

import com.bobjackson.hangman.api.GameResult;
import com.bobjackson.hangman.api.HangmanApiException;
import com.bobjackson.hangman.api.HangmanClient;
import com.bobjackson.hangman.api.StartGame;
import com.bobjackson.hangman.api.WordState;
import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.WordWeighting;
import com.bobjackson.hangman.engine.GameEngine;
import com.bobjackson.hangman.solver.EliminationSolver;
import com.bobjackson.hangman.solver.FrequencyLetterSelector;
import com.bobjackson.hangman.solver.SolverFactory;
import com.bobjackson.hangman.transport.RetryingHangmanClient;
import com.bobjackson.hangman.transport.Sleeper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedServerTest {

    /** One word per length band, so every difficulty tier is non-empty. */
    private static final List<String> POOL =
            List.of("SHEEP", "ELEPHANT", "BUTTERFLY", "EXTRAORDINARY");

    private static final int WORDS_PER_GAME = 4;
    private static final int ALLOWED = 10;

    @Test
    void advertisesTheRulesItWillEnforce() {
        SimulatedServer server = server(0.0);

        StartGame game = server.startGame("player");

        assertThat(game.sessionId()).isNotBlank();
        assertThat(game.numberOfWordsToGuess()).isEqualTo(WORDS_PER_GAME);
        assertThat(game.numberOfGuessAllowedForEachWord()).isEqualTo(ALLOWED);
    }

    @Test
    void failsOnAnEmptyPlayerIdAndBeforeStart() {
        SimulatedServer server = server(0.0);

        assertThatThrownBy(() -> server.startGame("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(server::nextWord).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revealingAHitRevealsEveryOccurrence() {
        SimulatedServer server = server(0.0);
        server.startGame("player");
        server.nextWord();

        String answer = server.currentAnswer();
        char letter = answer.charAt(0);
        WordState state = server.guess(letter);

        for (int i = 0; i < answer.length(); i++) {
            if (answer.charAt(i) == letter) {
                assertThat(state.word().charAt(i)).isEqualTo(letter);
            } else {
                assertThat(state.word().charAt(i)).isEqualTo(WordState.MASK);
            }
        }
        assertThat(state.wrongGuessCountOfCurrentWord()).isZero();
    }

    @Test
    void aRepeatedWrongLetterCostsASecondMiss() {
        SimulatedServer server = server(0.0);
        server.startGame("player");
        server.nextWord();

        char miss = firstLetterAbsentFrom(server.currentAnswer());
        server.guess(miss);
        WordState state = server.guess(miss);

        assertThat(state.wrongGuessCountOfCurrentWord()).isEqualTo(2);
    }

    @Test
    void stopsAcceptingGuessesOnceTheAllowanceIsSpent() {
        SimulatedServer server = server(0.0);
        server.startGame("player");
        server.nextWord();

        int misses = 0;
        for (char letter = 'Z'; letter >= 'A' && misses < ALLOWED; letter--) {
            if (server.currentAnswer().indexOf(letter) < 0) {
                server.guess(letter);
                misses++;
            }
        }

        assertThat(misses).isEqualTo(ALLOWED);
        assertThatThrownBy(() -> server.guess('Q')).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scoresARevealedWordAccordingToTheRecoveredFormula() {
        SimulatedServer server = server(0.0);
        server.startGame("player");
        WordState state = server.nextWord();

        for (char letter : server.currentAnswer().toCharArray()) {
            if (!state.solved() && state.word().indexOf(letter) < 0) {
                state = server.guess(letter);
            }
        }

        assertThat(state.solved()).isTrue();
        GameResult result = server.getResult();
        assertThat(result.correctWordCount()).isEqualTo(1);
        assertThat(result.totalWrongGuessCount()).isZero();
        assertThat(result.score()).isEqualTo(GameResult.REWARD_PER_CORRECT_WORD);
        assertThat(result.isScoreConsistent()).isTrue();
    }

    @Test
    void refusesToServeMoreWordsThanTheGameHas() {
        SimulatedServer server = server(0.0);
        server.startGame("player");

        for (int i = 0; i < WORDS_PER_GAME; i++) {
            server.nextWord();
        }

        assertThatThrownBy(server::nextWord).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsCallsAfterSubmission() {
        SimulatedServer server = server(0.0);
        server.startGame("player");
        server.submitResult();

        assertThatThrownBy(server::nextWord).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> server.guess('A')).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void isReproducibleFromTheSeed() {
        SimulatedServer first = server(0.0);
        SimulatedServer second = server(0.0);
        first.startGame("player");
        second.startGame("player");

        for (int i = 0; i < WORDS_PER_GAME; i++) {
            first.nextWord();
            second.nextWord();
            assertThat(first.currentAnswer()).isEqualTo(second.currentAnswer());
        }
    }

    @Test
    void startsEachSessionFromAZeroedScore() {
        SimulatedServer server = server(0.0);
        server.startGame("player");
        server.nextWord();
        server.guess(firstLetterAbsentFrom(server.currentAnswer()));
        assertThat(server.getResult().totalWrongGuessCount()).isEqualTo(1);

        server.startGame("player");

        assertThat(server.getResult().totalWordCount()).isZero();
        assertThat(server.getResult().totalWrongGuessCount()).isZero();
    }

    @Test
    void describesItsAnswerPoolPerTier() {
        SimulatedServer server = server(0.0);

        assertThat(server.describePools()).hasSize(SimulationConfig.TIERS);
    }

    @Test
    void injectsFaultsOnlyWhenAskedTo() {
        SimulatedServer clean = server(0.0);
        for (int i = 0; i < 200; i++) {
            clean.startGame("player");
        }

        SimulatedServer faulted = server(0.9);
        int failures = 0;
        for (int i = 0; i < 200; i++) {
            try {
                faulted.startGame("player");
            } catch (HangmanApiException e) {
                failures++;
                assertThat(e.statusCode()).isEqualTo(500);
                assertThat(e.isRetryable()).isTrue();
            }
        }

        assertThat(failures).isBetween(1, 199);
    }

    @Test
    void retriesAbsorbFaultsWithoutChangingTheScore() {
        Dictionary dictionary = Dictionary.of(POOL, WordWeighting.uniform());
        SolverFactory factory = (first, allowed) -> EliminationSolver.forWord(
                dictionary, new FrequencyLetterSelector(), first, allowed);
        SimulationConfig noise = config(0.9);
        HangmanClient raw = new SimulatedServer(noise, POOL, WordWeighting.uniform());
        // A hundred attempts keeps the probability of a spurious test failure around 1e-10.
        HangmanClient client = new RetryingHangmanClient(raw, 100, 0L, 0L, Sleeper.noop(),
                System.getLogger(SimulatedServerTest.class.getName()));

        GameResult result = new GameEngine(client, factory).play("player");

        assertThat(result.totalWordCount()).isEqualTo(WORDS_PER_GAME);
        assertThat(result.isScoreConsistent()).isTrue();
        // Every fault was injected before any state change, so no guess was counted twice.
        assertThat(result.totalWrongGuessCount()).isZero();
    }

    private static SimulatedServer server(double failureRate) {
        return new SimulatedServer(config(failureRate), POOL, WordWeighting.uniform());
    }

    private static SimulationConfig config(double failureRate) {
        return new SimulationConfig(POOL.size(), SimulationConfig.Sampling.UNIFORM,
                SimulationConfig.DifficultySemantics.CUMULATIVE, 1, 42L, failureRate,
                WORDS_PER_GAME, ALLOWED);
    }

    private static char firstLetterAbsentFrom(String answer) {
        for (char letter = 'Z'; letter >= 'A'; letter--) {
            if (answer.indexOf(letter) < 0) {
                return letter;
            }
        }
        throw new IllegalStateException("answer uses every letter: " + answer);
    }
}
