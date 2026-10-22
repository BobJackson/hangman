package com.bobjackson.hangman.transport;

import com.bobjackson.hangman.api.GameResult;
import com.bobjackson.hangman.api.HangmanApiException;
import com.bobjackson.hangman.api.HangmanClient;
import com.bobjackson.hangman.api.StartGame;
import com.bobjackson.hangman.api.WordState;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Talks to the real game server over HTTP.
 *
 * <p>One endpoint, one POST per action, JSON in both directions:
 * <pre>
 *   {"action": "startGame", "playerId": "..."}              -&gt; {"sessionId": "...", "data": {...}}
 *   {"action": "nextWord",   "sessionId": "..."}            -&gt; {"sessionId": "...", "data": {...}}
 *   {"action": "guessWord",  "sessionId": "...", "guess": "E"}
 *   {"action": "getResult",  "sessionId": "..."}
 *   {"action": "submitResult", "sessionId": "..."}
 * </pre>
 *
 * <p>Session state is held here and never leaks to the caller, which is what lets
 * {@link com.bobjackson.hangman.engine.GameEngine} run unchanged against this client and the
 * simulator.
 *
 * <h2>What this class deliberately does not do</h2>
 * No retries. Retrying is a policy decision that belongs in {@link RetryingHangmanClient},
 * which wraps this one — mixing the two here would make it impossible to test the retry
 * policy without a socket, and impossible to count how many attempts a call actually took.
 *
 * <h2>The one protocol assumption</h2>
 * Responses arrive as {@code {"sessionId": ..., "data": {...}}}. The rule parameters
 * {@code numberOfWordsToGuess} and {@code numberOfGuessAllowedForEachWord} are read from
 * {@code data}, and from the top level if the server ever flattens them. Neither is defaulted:
 * guessing 80 and 10 here would reintroduce exactly the bug the legacy client had. The one
 * documented exception is the {@code word} field, which the error messages do not always carry;
 * its absence is reported as a protocol error rather than an exception from the constructor of
 * {@link WordState}.
 */
public final class HttpHangmanClient implements HangmanClient {

    /** The endpoint named in the official instructions. Almost certainly retired; see README. */
    public static final URI DEFAULT_ENDPOINT =
            URI.create("https://strikingly-hangman.herokuapp.com/game/on");

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final URI endpoint;
    private final HttpClient http;
    private final Duration requestTimeout;

    private String sessionId;

    /** Uses {@link #DEFAULT_ENDPOINT} with default timeouts. */
    public HttpHangmanClient() {
        this(DEFAULT_ENDPOINT, DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    public HttpHangmanClient(URI endpoint, Duration connectTimeout, Duration requestTimeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .build();
    }

    @Override
    public StartGame startGame(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId must not be blank");
        }
        Map<String, Object> response = post("{\"action\":\"startGame\",\"playerId\":" + Json.quote(playerId) + '}');

        this.sessionId = requireString(response, "sessionId");
        Map<String, Object> data = dataOf(response);
        return new StartGame(sessionId,
                requireInt(data, "numberOfWordsToGuess"),
                requireInt(data, "numberOfGuessAllowedForEachWord"));
    }

    @Override
    public WordState nextWord() {
        return wordState(post(session()));
    }

    @Override
    public WordState guess(char letter) {
        if (letter < 'A' || letter > 'Z') {
            throw new IllegalArgumentException("only 'A'-'Z' is accepted, got '" + letter + '\'');
        }
        String body = "{\"action\":\"guessWord\",\"sessionId\":" + Json.quote(requireSession())
                + ",\"guess\":" + Json.quote(String.valueOf(letter)) + '}';
        return wordState(post(body));
    }

    @Override
    public GameResult getResult() {
        return gameResult(post(session("getResult")));
    }

    @Override
    public GameResult submitResult() {
        return gameResult(post(session("submitResult")));
    }

    /** The session this client is holding, or {@code null} before {@code startGame}. */
    public String sessionId() {
        return sessionId;
    }

    private String session() {
        return session("nextWord");
    }

    private String session(String action) {
        return "{\"action\":" + Json.quote(action) + ",\"sessionId\":" + Json.quote(requireSession()) + '}';
    }

    private String requireSession() {
        if (sessionId == null) {
            throw new IllegalStateException("call startGame first");
        }
        return sessionId;
    }

    private Map<String, Object> post(String body) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new HangmanApiException("request timed out after " + requestTimeout, e);
        } catch (IOException e) {
            throw new HangmanApiException("transport failure: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HangmanApiException("interrupted while waiting for the server", e);
        }

        if (response.statusCode() != 200) {
            throw new HangmanApiException(
                    "server returned " + response.statusCode() + ": " + abbreviate(response.body()),
                    response.statusCode());
        }
        return parse(response.body());
    }

    private static Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            throw new HangmanApiException("server returned an empty body");
        }
        Map<String, Object> parsed;
        try {
            parsed = Json.parseObject(body);
        } catch (IllegalArgumentException e) {
            throw new HangmanApiException("unreadable response: " + abbreviate(body), e);
        }
        // The server signals rejections with a message and no data payload. Surfacing the
        // message beats surfacing "missing field word" three frames later.
        Object message = parsed.get("message");
        if (message != null && !parsed.containsKey("data")) {
            throw new HangmanApiException("server rejected the request: " + message);
        }
        return parsed;
    }

    private static WordState wordState(Map<String, Object> response) {
        Map<String, Object> data = dataOf(response);
        return new WordState(
                requireString(data, "word"),
                requireInt(data, "totalWordCount"),
                requireInt(data, "wrongGuessCountOfCurrentWord"));
    }

    private static GameResult gameResult(Map<String, Object> response) {
        Map<String, Object> data = dataOf(response);
        return new GameResult(
                requireInt(data, "totalWordCount"),
                requireInt(data, "correctWordCount"),
                requireInt(data, "totalWrongGuessCount"),
                requireInt(data, "score"));
    }

    /** The {@code data} envelope, or the response itself when the server flattened it. */
    private static Map<String, Object> dataOf(Map<String, Object> response) {
        Object data = response.get("data");
        if (data instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        if (data == null) {
            return response;
        }
        throw new HangmanApiException("expected \"data\" to be an object, got " + data.getClass().getSimpleName());
    }

    private static String requireString(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof String text) {
            return text;
        }
        throw new HangmanApiException("response field \"" + key + "\" is missing or not a string");
    }

    private static int requireInt(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                throw new HangmanApiException("response field \"" + key + "\" is not an integer: " + text, e);
            }
        }
        throw new HangmanApiException("response field \"" + key + "\" is missing or not a number");
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "<null>";
        }
        String single = body.replace('\n', ' ').replace('\r', ' ').strip();
        return single.length() <= 200 ? single : single.substring(0, 200) + "...";
    }

    @Override
    public String toString() {
        return "HttpHangmanClient[" + endpoint + (sessionId == null ? ", no session" : ", session " + sessionId) + ']';
    }
}
