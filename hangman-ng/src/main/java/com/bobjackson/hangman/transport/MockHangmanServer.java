package com.bobjackson.hangman.transport;

import com.bobjackson.hangman.api.GameResult;
import com.bobjackson.hangman.api.HangmanApiException;
import com.bobjackson.hangman.api.HangmanClient;
import com.bobjackson.hangman.api.StartGame;
import com.bobjackson.hangman.api.WordState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The other end of {@link HttpHangmanClient}: the documented protocol, served over a socket.
 *
 * <p>It exists because the two halves of the HTTP path had never met. {@code HttpHangmanClient}
 * was only ever exercised against a stubbed exchange, and {@code SimulatedServer} only
 * in-process, so a disagreement between them about the wire format would have passed every
 * test. This class puts a real socket in between: the same {@code java.net.http} stack, the
 * same parser, the same JSON, and a {@code GameEngine} driving a whole session end to end.
 *
 * <h2>What it deliberately does not do</h2>
 * No game rules. Each action is forwarded to a {@link HangmanClient} built by the supplied
 * factory, one instance per session, so every rule — reveal-all-occurrences, tier selection,
 * the miss budget, the score formula — stays in {@code SimulatedServer}, where it is already
 * measured. A second implementation of those rules is exactly the drift this project spends
 * its effort avoiding.
 *
 * <h2>Session identity</h2>
 * Sessions are named here rather than by the delegate. A simulator built from a fixed seed
 * hands out the same session id to every caller, which is harmless in-process and wrong over
 * a socket, where two clients can be playing at once. The id issued here is therefore this
 * class's own. The fixed seed is kept on purpose: every session then draws the same answer
 * sequence, so any failure reproduces from the seed alone.
 *
 * <h2>Status codes</h2>
 * A request this layer or the delegate refuses as malformed becomes {@code 400}. A delegate
 * that fails with an HTTP status of its own — a {@link HangmanApiException} carrying {@code 500}
 * from injected faults — passes that status through, so a retrying client sees what it would
 * have seen from the real server.
 */
public final class MockHangmanServer implements AutoCloseable {

    /** The single endpoint named in the official instructions. */
    public static final String PATH = "/game/on";

    private static final System.Logger LOG = System.getLogger(MockHangmanServer.class.getName());

    private final HttpServer http;
    private final ExecutorService executor;
    private final Supplier<HangmanClient> clients;
    private final Map<String, HangmanClient> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionIds = new AtomicLong();
    private final String advertisedHost;

    /**
     * Binds a socket but does not start serving; call {@link #start()}.
     *
     * @param host    interface to bind, e.g. {@code 127.0.0.1}
     * @param port    port to bind, {@code 0} for any free one — {@link #endpoint()} then reports
     *                the real port, which is what lets tests run without picking numbers
     * @param clients builds the delegate for one session; called once per {@code startGame}
     */
    public MockHangmanServer(String host, int port, Supplier<HangmanClient> clients) throws IOException {
        Objects.requireNonNull(host, "host");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.advertisedHost = host.isBlank() || host.equals("0.0.0.0") ? "127.0.0.1" : host;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.http = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.http.createContext(PATH, this::handle);
        this.http.setExecutor(executor);
    }

    /** The URI a client should be pointed at; safe to read once the constructor returns. */
    public URI endpoint() {
        return URI.create("http://" + advertisedHost + ':' + http.getAddress().getPort() + PATH);
    }

    public void start() {
        http.start();
        LOG.log(System.Logger.Level.DEBUG, "mock server listening on {0}", endpoint());
    }

    /** Stops serving and closes every session still open. */
    @Override
    public void close() {
        http.stop(0);
        executor.shutdownNow();
        sessions.values().forEach(MockHangmanServer::closeQuietly);
        sessions.clear();
    }

    private void handle(HttpExchange exchange) {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, error("only POST is accepted"));
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            dispatch(exchange, body);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "could not read the request body", e);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "unexpected failure while serving a request", e);
            try {
                respond(exchange, 500, error(e.getMessage() == null ? e.toString() : e.getMessage()));
            } catch (IOException ignored) {
                // the client is gone; nothing left to report to
            }
        } finally {
            exchange.close();
        }
    }

    private void dispatch(HttpExchange exchange, String body) throws IOException {
        Map<String, Object> request;
        try {
            request = Json.parseObject(body);
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error("unreadable request: " + e.getMessage()));
            return;
        }
        Object action = request.get("action");
        if (!(action instanceof String name)) {
            respond(exchange, 400, error("request field \"action\" is missing or not a string"));
            return;
        }
        try {
            String payload = switch (name) {
                case "startGame" -> startGame(request);
                case "nextWord" -> nextWord(request);
                case "guessWord" -> guessWord(request);
                case "getResult" -> getResult(request);
                case "submitResult" -> submitResult(request);
                default -> throw new IllegalArgumentException("unknown action: " + name);
            };
            respond(exchange, 200, payload);
        } catch (HangmanApiException e) {
            int status = e.statusCode() == HangmanApiException.NO_STATUS ? 500 : e.statusCode();
            respond(exchange, status, error(e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            respond(exchange, 400, error(e.getMessage()));
        }
    }

    private String startGame(Map<String, Object> request) {
        String sessionId = "s-" + sessionIds.incrementAndGet();
        HangmanClient client = Objects.requireNonNull(clients.get(), "the session factory returned null");
        sessions.put(sessionId, client);
        try {
            StartGame game = client.startGame(requireString(request, "playerId"));
            return "{\"sessionId\":" + Json.quote(sessionId) + ",\"data\":{"
                    + "\"numberOfWordsToGuess\":" + game.numberOfWordsToGuess() + ','
                    + "\"numberOfGuessAllowedForEachWord\":" + game.numberOfGuessAllowedForEachWord() + "}}";
        } catch (RuntimeException e) {
            // A session that could not start would otherwise stay in the map forever.
            sessions.remove(sessionId);
            closeQuietly(client);
            throw e;
        }
    }

    private String nextWord(Map<String, Object> request) {
        String sessionId = requireString(request, "sessionId");
        return wordState(sessionId, session(sessionId).nextWord());
    }

    private String guessWord(Map<String, Object> request) {
        String sessionId = requireString(request, "sessionId");
        String raw = requireString(request, "guess");
        if (raw.length() != 1 || raw.charAt(0) < 'A' || raw.charAt(0) > 'Z') {
            throw new IllegalArgumentException("request field \"guess\" must be a single 'A'-'Z', got \"" + raw + '"');
        }
        return wordState(sessionId, session(sessionId).guess(raw.charAt(0)));
    }

    private String getResult(Map<String, Object> request) {
        String sessionId = requireString(request, "sessionId");
        return gameResult(sessionId, session(sessionId).getResult());
    }

    private String submitResult(Map<String, Object> request) {
        String sessionId = requireString(request, "sessionId");
        HangmanClient client = session(sessionId);
        GameResult result = client.submitResult();
        sessions.remove(sessionId);
        closeQuietly(client);
        return gameResult(sessionId, result);
    }

    private HangmanClient session(String sessionId) {
        HangmanClient client = sessions.get(sessionId);
        if (client == null) {
            throw new IllegalArgumentException("unknown session: " + sessionId);
        }
        return client;
    }

    private static String wordState(String sessionId, WordState state) {
        return "{\"sessionId\":" + Json.quote(sessionId) + ",\"data\":{\"word\":"
                + Json.quote(state.word()) + ','
                + "\"totalWordCount\":" + state.totalWordCount() + ','
                + "\"wrongGuessCountOfCurrentWord\":" + state.wrongGuessCountOfCurrentWord() + "}}";
    }

    private static String gameResult(String sessionId, GameResult result) {
        return "{\"sessionId\":" + Json.quote(sessionId) + ",\"data\":{"
                + "\"totalWordCount\":" + result.totalWordCount() + ','
                + "\"correctWordCount\":" + result.correctWordCount() + ','
                + "\"totalWrongGuessCount\":" + result.totalWrongGuessCount() + ','
                + "\"score\":" + result.score() + "}}";
    }

    private static String requireString(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalArgumentException("request field \"" + key + "\" is missing or not a string");
    }

    private static String error(String message) {
        return "{\"message\":" + Json.quote(message == null ? "unspecified failure" : message) + '}';
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static void closeQuietly(HangmanClient client) {
        try {
            client.close();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "closing a session failed", e);
        }
    }

    @Override
    public String toString() {
        return "MockHangmanServer[" + endpoint() + ", " + sessions.size() + " sessions]";
    }
}
