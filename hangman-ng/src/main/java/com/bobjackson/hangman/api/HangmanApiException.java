package com.bobjackson.hangman.api;

/**
 * Raised when the game server cannot be talked to, or replies with an error.
 *
 * <p>Carries the HTTP status (or {@link #NO_STATUS} for transport-level failures) so the
 * caller can distinguish "our request was malformed, retrying is pointless" from "the
 * server is overloaded, back off and retry". The official instructions call the latter
 * out explicitly: their server times out under load and clients are expected to cope.
 */
public class HangmanApiException extends RuntimeException {

    /** Sentinel for failures that never produced an HTTP status, e.g. a socket timeout. */
    public static final int NO_STATUS = -1;

    private final int statusCode;

    public HangmanApiException(String message) {
        this(message, NO_STATUS, null);
    }

    public HangmanApiException(String message, Throwable cause) {
        this(message, NO_STATUS, cause);
    }

    public HangmanApiException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    public HangmanApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** The HTTP status, or {@link #NO_STATUS} if the request never completed. */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Whether retrying could plausibly succeed.
     *
     * <p>Transport-level failures and 5xx / 429 are transient. A 4xx means we sent
     * something the server rejects, and hammering it again only wastes the word.
     */
    public boolean isRetryable() {
        if (statusCode == NO_STATUS) {
            return true;
        }
        return statusCode == 429 || statusCode >= 500;
    }
}
