package com.bobjackson.hangman.transport;

import com.bobjackson.hangman.api.GameResult;
import com.bobjackson.hangman.api.HangmanApiException;
import com.bobjackson.hangman.api.HangmanClient;
import com.bobjackson.hangman.api.StartGame;
import com.bobjackson.hangman.api.WordState;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Retries a client call on transient failures, with exponential backoff.
 *
 * <p>This exists because the official instructions call the problem out directly: their
 * server times out under load and clients are expected to cope. The legacy implementation
 * responded with
 * <pre>{@code
 * while (statusCode != 200) { response = httpclient.execute(httpPost); }
 * }</pre>
 * which retries at full speed with no ceiling and never closes the abandoned response,
 * leaking a connection per attempt. Against a server that is already overloaded that is
 * indistinguishable from an attack.
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>Only {@link HangmanApiException#isRetryable() retryable} failures are retried. A
 *       4xx means we sent something invalid; repeating it just wastes the word.</li>
 *   <li>Retries are bounded. Exhausting the budget surfaces the last failure rather than
 *       looping forever.</li>
 *   <li>Delay is {@code min(cap, base * 2^(attempt-1))}. There is deliberately no random
 *       jitter: jitter exists to de-synchronise a fleet of clients, and there is exactly
 *       one here.</li>
 *   <li>Every retried call must be safe to repeat. The game protocol satisfies this —
 *       a {@code guessWord} that failed at the transport level was never applied, and
 *       {@code SimulatedServer} injects faults before mutating any state.</li>
 * </ul>
 */
public final class RetryingHangmanClient implements HangmanClient {

    /** Default attempts, counting the first try. */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    /** Default first backoff, in milliseconds. */
    public static final long DEFAULT_BASE_DELAY_MILLIS = 200L;

    /** Default backoff ceiling, in milliseconds. */
    public static final long DEFAULT_MAX_DELAY_MILLIS = 5_000L;

    private final HangmanClient delegate;
    private final int maxAttempts;
    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final Sleeper sleeper;
    private final System.Logger log;

    public RetryingHangmanClient(HangmanClient delegate) {
        this(delegate, DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_DELAY_MILLIS, DEFAULT_MAX_DELAY_MILLIS,
                Sleeper.real(), System.getLogger(RetryingHangmanClient.class.getName()));
    }

    public RetryingHangmanClient(HangmanClient delegate, int maxAttempts,
                                 long baseDelayMillis, long maxDelayMillis,
                                 Sleeper sleeper, System.Logger log) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.log = Objects.requireNonNull(log, "log");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
        }
        if (baseDelayMillis < 0 || maxDelayMillis < 0) {
            throw new IllegalArgumentException("delays must not be negative");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelayMillis = baseDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
    }

    @Override
    public StartGame startGame(String playerId) {
        return call("startGame", () -> delegate.startGame(playerId));
    }

    @Override
    public WordState nextWord() {
        return call("nextWord", delegate::nextWord);
    }

    @Override
    public WordState guess(char letter) {
        return call("guessWord", () -> delegate.guess(letter));
    }

    @Override
    public GameResult getResult() {
        return call("getResult", delegate::getResult);
    }

    @Override
    public GameResult submitResult() {
        return call("submitResult", delegate::submitResult);
    }

    @Override
    public void close() {
        delegate.close();
    }

    /** Delay before the given attempt retries; package-private so tests can pin the curve. */
    long delayForAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt is 1-based: " + attempt);
        }
        int shift = Math.min(attempt - 1, 20); // guard against overflow on absurd attempt counts
        long exponential = baseDelayMillis << shift;
        return Math.min(exponential, maxDelayMillis);
    }

    private <T> T call(String action, Supplier<T> operation) {
        HangmanApiException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return operation.get();
            } catch (HangmanApiException e) {
                if (!e.isRetryable()) {
                    log.log(System.Logger.Level.ERROR,
                            "{0} failed permanently (status {1}): {2}",
                            action, e.statusCode(), e.getMessage());
                    throw e;
                }
                lastFailure = e;
                if (attempt == maxAttempts) {
                    break;
                }
                long delay = delayForAttempt(attempt);
                log.log(System.Logger.Level.WARNING,
                        "{0} failed (status {1}), retry {2}/{3} in {4} ms",
                        action, e.statusCode(), attempt, maxAttempts - 1, delay);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new HangmanApiException(action + " interrupted during backoff", e);
                }
            }
        }
        log.log(System.Logger.Level.ERROR, "{0} exhausted {1} attempts", action, maxAttempts);
        throw lastFailure;
    }
}
