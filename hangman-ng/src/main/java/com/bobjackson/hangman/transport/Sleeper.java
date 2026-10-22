package com.bobjackson.hangman.transport;

/**
 * Abstraction over sleeping, so retry logic can be tested without real delays.
 *
 * <p>Injecting this is what keeps {@link RetryingHangmanClientTest} fast: a test can assert
 * the exact delay sequence a policy would have used without waiting for it.
 */
@FunctionalInterface
public interface Sleeper {

    /** Sleeps for {@code millis}. A non-positive value means "do not sleep". */
    void sleep(long millis) throws InterruptedException;

    /** Really sleeps. The default for production use. */
    static Sleeper real() {
        return millis -> {
            if (millis > 0) {
                Thread.sleep(millis);
            }
        };
    }

    /** Ignores sleeps entirely. */
    static Sleeper noop() {
        return millis -> {
        };
    }
}
