package com.bobjackson.hangman.engine;

import com.bobjackson.hangman.api.GameResult;
import com.bobjackson.hangman.api.HangmanApiException;
import com.bobjackson.hangman.api.HangmanClient;
import com.bobjackson.hangman.api.StartGame;
import com.bobjackson.hangman.api.WordState;
import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.WordWeighting;
import com.bobjackson.hangman.solver.EliminationSolver;
import com.bobjackson.hangman.solver.FrequencyLetterSelector;
import com.bobjackson.hangman.solver.LetterSelector;
import com.bobjackson.hangman.solver.Solver;
import com.bobjackson.hangman.solver.SolverFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameEngineTest {

    @Test
    void playsExactlyTheNumberOfWordsTheServerAdvertises() {
        FakeServer server = new FakeServer(List.of("CAT", "DOG", "HEN"), 10);

        GameResult result = new GameEngine(server, factory()).play("player");

        // The legacy client hard-coded 80 here, so a server that changed the count silently
        // produced a partial or infinite game.
        assertThat(result.totalWordCount()).isEqualTo(3);
        assertThat(result.correctWordCount()).isEqualTo(3);
        assertThat(result.isScoreConsistent()).isTrue();
        assertThat(server.submitted).isTrue();
    }

    @Test
    void movesOnWhenAWordIsExhaustedInsteadOfGuessingForever() {
        // A one-word dictionary: the first answer cannot be explained by any candidate, so
        // the solver spends every allowed miss and the engine has to move on.
        Dictionary single = Dictionary.of(List.of("CAT"), WordWeighting.uniform());
        FakeServer server = new FakeServer(List.of("ZZZ", "CAT"), 3);

        GameResult result = new GameEngine(server, factory(single)).play("player");

        assertThat(result.totalWordCount()).isEqualTo(2);
        assertThat(result.correctWordCount()).isEqualTo(1);
        assertThat(result.totalWrongGuessCount()).isEqualTo(3);
        assertThat(result.score()).isEqualTo(GameResult.scoreOf(1, 3));
    }

    @Test
    void abandonsAWordWithoutSpendingAGuessWhenTheSolverAsksTo() {
        FakeServer server = new FakeServer(List.of("CAT", "DOG"), 10);
        SolverFactory givesUpImmediately = (first, allowed) -> new Solver() {
            @Override
            public char nextGuess() {
                throw new AssertionError("a word the solver gave up on must not be guessed at");
            }

            @Override
            public void observe(char guess, WordState state) {
                throw new AssertionError("nothing was guessed, so nothing can be observed");
            }

            @Override
            public int candidateCount() {
                return 0;
            }

            @Override
            public boolean shouldAbandon() {
                return true;
            }
        };

        GameResult result = new GameEngine(server, givesUpImmediately).play("player");

        assertThat(result.totalWordCount()).isEqualTo(2);
        assertThat(result.correctWordCount()).isZero();
        assertThat(result.totalWrongGuessCount()).isZero();
        assertThat(result.score()).isZero();
        assertThat(server.submitted).isTrue();
    }

    @Test
    void notifiesTheListenerOncePerWord() {
        FakeServer server = new FakeServer(List.of("CAT", "DOG"), 10);
        CountingListener listener = new CountingListener();

        new GameEngine(server, factory(), listener).play("player");

        assertThat(listener.gamesStarted).isEqualTo(1);
        assertThat(listener.wordsStarted).isEqualTo(2);
        assertThat(listener.wordsFinished).isEqualTo(2);
        assertThat(listener.gamesFinished).isEqualTo(1);
        assertThat(listener.guesses).isPositive();
    }

    @Test
    void createsAFreshSolverForEveryWord() {
        FakeServer server = new FakeServer(List.of("CAT", "DOG", "HEN"), 10);
        List<Integer> allowances = new ArrayList<>();
        SolverFactory factory = (first, allowed) -> {
            allowances.add(allowed);
            return EliminationSolver.forWord(lexicon(), new FrequencyLetterSelector(), first, allowed);
        };

        new GameEngine(server, factory).play("player");

        assertThat(allowances).containsExactly(10, 10, 10);
    }

    @Test
    void surfacesClientFailuresInsteadOfSwallowingThem() {
        HangmanClient broken = new FakeServer(List.of("CAT"), 10) {
            @Override
            public WordState nextWord() {
                throw new HangmanApiException("server unavailable", 503);
            }
        };
        CountingListener listener = new CountingListener();

        assertThatThrownBy(() -> new GameEngine(broken, factory(), listener).play("player"))
                .isInstanceOf(HangmanApiException.class)
                .hasMessageContaining("server unavailable");
        assertThat(listener.gamesFinished).isZero();
    }

    private static SolverFactory factory() {
        return factory(lexicon());
    }

    private static SolverFactory factory(Dictionary dictionary) {
        LetterSelector selector = new FrequencyLetterSelector();
        return (first, allowed) -> EliminationSolver.forWord(dictionary, selector, first, allowed);
    }

    private static Dictionary lexicon() {
        return Dictionary.of(List.of("CAT", "DOG", "HEN"), WordWeighting.uniform());
    }

    private static final class CountingListener implements GameListener {

        int gamesStarted;
        int wordsStarted;
        int wordsFinished;
        int gamesFinished;
        int guesses;

        @Override
        public void onGameStart(StartGame game) {
            gamesStarted++;
        }

        @Override
        public void onWordStart(WordState state) {
            wordsStarted++;
        }

        @Override
        public void onGuess(WordState state, char guess, int candidateCount) {
            guesses++;
        }

        @Override
        public void onWordFinished(WordState state, int candidateCount, int guessesUsed, boolean abandoned) {
            wordsFinished++;
        }

        @Override
        public void onGameFinished(GameResult result) {
            gamesFinished++;
        }
    }

    /**
     * A hand-written stand-in for the server, deliberately independent of
     * {@link com.bobjackson.hangman.sim.SimulatedServer} so that a bug in the simulator
     * cannot hide a bug in the engine.
     */
    private static class FakeServer implements HangmanClient {

        private final List<String> answers;
        private final int allowed;

        private int served;
        private String current;
        private char[] mask;
        private int wrong;
        private int correct;
        private int totalWrong;
        boolean submitted;

        FakeServer(List<String> answers, int allowed) {
            this.answers = List.copyOf(answers);
            this.allowed = allowed;
        }

        @Override
        public StartGame startGame(String playerId) {
            return new StartGame("fake-session", answers.size(), allowed);
        }

        @Override
        public WordState nextWord() {
            current = answers.get(served++);
            mask = new char[current.length()];
            Arrays.fill(mask, WordState.MASK);
            wrong = 0;
            return snapshot();
        }

        @Override
        public WordState guess(char letter) {
            if (current == null) {
                throw new IllegalStateException("no word in play");
            }
            if (current.indexOf(letter) >= 0) {
                for (int i = 0; i < current.length(); i++) {
                    if (current.charAt(i) == letter) {
                        mask[i] = letter;
                    }
                }
                if (new String(mask).indexOf(WordState.MASK) < 0) {
                    correct++;
                }
            } else {
                wrong++;
                totalWrong++;
            }
            return snapshot();
        }

        @Override
        public GameResult getResult() {
            return result();
        }

        @Override
        public GameResult submitResult() {
            submitted = true;
            return result();
        }

        private WordState snapshot() {
            return new WordState(new String(mask), served, wrong);
        }

        private GameResult result() {
            return new GameResult(served, correct, totalWrong, GameResult.scoreOf(correct, totalWrong));
        }
    }
}
