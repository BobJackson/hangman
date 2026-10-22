package com.bobjackson.hangman.transport;

import com.bobjackson.hangman.api.GameResult;
import com.bobjackson.hangman.api.HangmanApiException;
import com.bobjackson.hangman.api.HangmanClient;
import com.bobjackson.hangman.api.StartGame;
import com.bobjackson.hangman.api.WordState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryingHangmanClientTest {

    private final List<Long> delays = new ArrayList<>();

    @Test
    void retriesATransientFailureThenSucceeds() {
        FlakyClient delegate = new FlakyClient(2, 503);
        RetryingHangmanClient client = client(delegate, 5, 100L, 1_000L);

        StartGame game = client.startGame("player");

        assertThat(game.sessionId()).isEqualTo("session");
        assertThat(delegate.calls).isEqualTo(3);
        assertThat(delays).containsExactly(100L, 200L);
    }

    @Test
    void retriesEveryMethodOfTheProtocol() {
        List<HangmanClient> wrappers = new ArrayList<>();
        List<FlakyClient> delegates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            FlakyClient delegate = new FlakyClient(1, 500);
            delegates.add(delegate);
            wrappers.add(client(delegate, 3, 0L, 0L));
        }

        wrappers.get(0).startGame("player");
        wrappers.get(1).nextWord();
        wrappers.get(2).guess('A');
        wrappers.get(3).getResult();
        wrappers.get(4).submitResult();

        assertThat(delegates).extracting(delegate -> delegate.calls).containsOnly(2);
    }

    @Test
    void doesNotRetryAClientError() {
        FlakyClient delegate = new FlakyClient(5, 400);

        assertThatThrownBy(() -> client(delegate, 5, 0L, 0L).startGame("player"))
                .isInstanceOf(HangmanApiException.class)
                .hasMessageContaining("boom");

        assertThat(delegate.calls).isEqualTo(1);
        assertThat(delays).isEmpty();
    }

    @Test
    void givesUpAfterTheAttemptBudgetAndSurfacesTheLastFailure() {
        FlakyClient delegate = new FlakyClient(99, 500);

        assertThatThrownBy(() -> client(delegate, 3, 10L, 1_000L).startGame("player"))
                .isInstanceOf(HangmanApiException.class);

        assertThat(delegate.calls).isEqualTo(3);
        assertThat(delays).containsExactly(10L, 20L);
    }

    @Test
    void backsOffExponentiallyAndRespectsTheCeiling() {
        RetryingHangmanClient client = client(new FlakyClient(0, 500), 10, 100L, 500L);

        assertThat(client.delayForAttempt(1)).isEqualTo(100L);
        assertThat(client.delayForAttempt(2)).isEqualTo(200L);
        assertThat(client.delayForAttempt(3)).isEqualTo(400L);
        assertThat(client.delayForAttempt(4)).isEqualTo(500L);
        assertThat(client.delayForAttempt(99)).isEqualTo(500L);
    }

    @Test
    void treatsATransportFailureWithoutAStatusAsRetryable() {
        FlakyClient delegate = new FlakyClient(1, HangmanApiException.NO_STATUS);

        client(delegate, 2, 0L, 0L).startGame("player");

        assertThat(delegate.calls).isEqualTo(2);
    }

    @Test
    void propagatesAnInterruptInsteadOfSleepingThroughIt() {
        FlakyClient delegate = new FlakyClient(9, 500);
        Sleeper interrupted = millis -> {
            throw new InterruptedException("test");
        };
        RetryingHangmanClient client = new RetryingHangmanClient(delegate, 5, 10L, 100L,
                interrupted, System.getLogger(getClass().getName()));

        try {
            assertThatThrownBy(() -> client.startGame("player"))
                    .isInstanceOf(HangmanApiException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear the flag so later tests on this thread start clean
        }
    }

    @Test
    void closesTheDelegateAndValidatesItsOwnConfiguration() {
        FlakyClient delegate = new FlakyClient(0, 500);
        client(delegate, 2, 0L, 0L).close();
        assertThat(delegate.closed).isTrue();

        assertThatThrownBy(() -> new RetryingHangmanClient(delegate, 0, 1L, 1L,
                Sleeper.noop(), System.getLogger(getClass().getName())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryingHangmanClient(delegate, 2, -1L, 1L,
                Sleeper.noop(), System.getLogger(getClass().getName())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RetryingHangmanClient client(FlakyClient delegate, int maxAttempts,
                                         long baseDelay, long maxDelay) {
        return new RetryingHangmanClient(delegate, maxAttempts, baseDelay, maxDelay,
                delays::add, System.getLogger(getClass().getName()));
    }

    /** Fails the first {@code failuresBeforeSuccess} calls with the given status. */
    private static final class FlakyClient implements HangmanClient {

        private final int failuresBeforeSuccess;
        private final int status;
        int calls;
        boolean closed;

        FlakyClient(int failuresBeforeSuccess, int status) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.status = status;
        }

        @Override
        public StartGame startGame(String playerId) {
            failWhileBudgetLasts();
            return new StartGame("session", 80, 10);
        }

        @Override
        public WordState nextWord() {
            failWhileBudgetLasts();
            return new WordState("***", 1, 0);
        }

        @Override
        public WordState guess(char letter) {
            failWhileBudgetLasts();
            return new WordState("***", 1, 0);
        }

        @Override
        public GameResult getResult() {
            failWhileBudgetLasts();
            return new GameResult(0, 0, 0, 0);
        }

        @Override
        public GameResult submitResult() {
            failWhileBudgetLasts();
            return new GameResult(0, 0, 0, 0);
        }

        @Override
        public void close() {
            closed = true;
        }

        private void failWhileBudgetLasts() {
            calls++;
            if (calls <= failuresBeforeSuccess) {
                throw new HangmanApiException("boom", status);
            }
        }
    }
}
