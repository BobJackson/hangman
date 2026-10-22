package com.bobjackson.hangman.transport;

import com.bobjackson.hangman.api.GameResult;
import com.bobjackson.hangman.api.HangmanApiException;
import com.bobjackson.hangman.api.StartGame;
import com.bobjackson.hangman.api.WordState;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpHangmanClientTest {

    private HttpServer server;
    private URI endpoint;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile Function<String, Stub> responder;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/game/on", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            Stub stub = responder.apply(body);
            byte[] payload = stub.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(stub.status(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/game/on");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsTheRuleParametersFromTheServerRatherThanAssumingThem() {
        responder = body -> Stub.ok("{\"sessionId\":\"s-1\",\"data\":{"
                + "\"numberOfWordsToGuess\":80,\"numberOfGuessAllowedForEachWord\":10}}");

        StartGame game = client().startGame("player@example.com");

        assertThat(game.sessionId()).isEqualTo("s-1");
        assertThat(game.numberOfWordsToGuess()).isEqualTo(80);
        assertThat(game.numberOfGuessAllowedForEachWord()).isEqualTo(10);
        assertThat(requests).singleElement().asString().contains("\"action\":\"startGame\"")
                .contains("\"playerId\":\"player@example.com\"");
    }

    @Test
    void readsRuleParametersFromTheTopLevelWhenTheServerDoesNotNestThem() {
        responder = body -> Stub.ok("{\"sessionId\":\"s-1\",\"numberOfWordsToGuess\":80,"
                + "\"numberOfGuessAllowedForEachWord\":10}");

        assertThat(client().startGame("player").numberOfGuessAllowedForEachWord()).isEqualTo(10);
    }

    @Test
    void carriesTheSessionIdForwardAndParsesWordState() {
        responder = body -> {
            if (body.contains("startGame")) {
                return Stub.ok("{\"sessionId\":\"s-7\",\"data\":{"
                        + "\"numberOfWordsToGuess\":80,\"numberOfGuessAllowedForEachWord\":10}}");
            }
            if (body.contains("guessWord")) {
                return Stub.ok("{\"sessionId\":\"s-7\",\"data\":{\"word\":\"C**\","
                        + "\"totalWordCount\":1,\"wrongGuessCountOfCurrentWord\":0}}");
            }
            return Stub.ok("{\"sessionId\":\"s-7\",\"data\":{\"word\":\"***\","
                    + "\"totalWordCount\":1,\"wrongGuessCountOfCurrentWord\":0}}");
        };
        HttpHangmanClient client = client();
        client.startGame("player");

        WordState afterNext = client.nextWord();
        WordState afterGuess = client.guess('C');

        assertThat(afterNext.word()).isEqualTo("***");
        assertThat(afterGuess.word()).isEqualTo("C**");
        assertThat(requests.get(1)).contains("\"action\":\"nextWord\"").contains("\"sessionId\":\"s-7\"");
        assertThat(requests.get(2)).contains("\"action\":\"guessWord\"").contains("\"guess\":\"C\"");
    }

    @Test
    void parsesBothResultCalls() {
        responder = body -> {
            if (body.contains("startGame")) {
                return Stub.ok("{\"sessionId\":\"s-7\",\"data\":{"
                        + "\"numberOfWordsToGuess\":80,\"numberOfGuessAllowedForEachWord\":10}}");
            }
            return Stub.ok("{\"sessionId\":\"s-7\",\"data\":{\"totalWordCount\":80,"
                    + "\"correctWordCount\":78,\"totalWrongGuessCount\":234,\"score\":1326}}");
        };
        HttpHangmanClient client = client();
        client.startGame("player");

        GameResult peeked = client.getResult();
        GameResult submitted = client.submitResult();

        assertThat(peeked.score()).isEqualTo(1326);
        assertThat(peeked.isScoreConsistent()).isTrue();
        assertThat(submitted.score()).isEqualTo(1326);
        assertThat(requests.get(1)).contains("\"action\":\"getResult\"");
        assertThat(requests.get(2)).contains("\"action\":\"submitResult\"");
    }

    @Test
    void reportsANonOkStatusWithItsCodeSoRetryPolicyCanDecide() {
        responder = body -> new Stub(503, "{\"message\":\"overloaded\"}");

        assertThatThrownBy(() -> client().startGame("player"))
                .isInstanceOf(HangmanApiException.class)
                .satisfies(e -> {
                    HangmanApiException api = (HangmanApiException) e;
                    assertThat(api.statusCode()).isEqualTo(503);
                    assertThat(api.isRetryable()).isTrue();
                })
                .hasMessageContaining("overloaded");
    }

    @Test
    void treatsAClientErrorAsFinal() {
        responder = body -> new Stub(400, "{\"message\":\"bad player id\"}");

        assertThatThrownBy(() -> client().startGame("player"))
                .isInstanceOf(HangmanApiException.class)
                .satisfies(e -> assertThat(((HangmanApiException) e).isRetryable()).isFalse());
    }

    @Test
    void surfacesARejectionMessageInsteadOfAMissingField() {
        responder = body -> Stub.ok("{\"message\":\"game already in progress\"}");

        assertThatThrownBy(() -> client().startGame("player"))
                .isInstanceOf(HangmanApiException.class)
                .hasMessageContaining("game already in progress");
    }

    @Test
    void rejectsAMissingOrNonNumericRuleParameter() {
        responder = body -> Stub.ok("{\"sessionId\":\"s-1\",\"data\":{\"numberOfWordsToGuess\":80}}");

        assertThatThrownBy(() -> client().startGame("player"))
                .isInstanceOf(HangmanApiException.class)
                .hasMessageContaining("numberOfGuessAllowedForEachWord");
    }

    @Test
    void rejectsAnEmptyBody() {
        responder = body -> Stub.ok("");

        assertThatThrownBy(() -> client().startGame("player"))
                .isInstanceOf(HangmanApiException.class)
                .hasMessageContaining("empty body");
    }

    @Test
    void rejectsUnreadableJson() {
        responder = body -> Stub.ok("{\"sessionId\":\"s-1\",\"data\":{");

        assertThatThrownBy(() -> client().startGame("player"))
                .isInstanceOf(HangmanApiException.class)
                .hasMessageContaining("unreadable response");
    }

    @Test
    void refusesToActBeforeTheSessionExists() {
        assertThatThrownBy(() -> client().nextWord())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startGame first");
        assertThat(requests).isEmpty();
    }

    @Test
    void refusesLettersOutsideAToZBeforeSendingAnything() {
        assertThatThrownBy(() -> client().guess('c'))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A'-'Z");
        assertThat(requests).isEmpty();
    }

    @Test
    void turnsATimeoutIntoARetryableApiFailure() {
        responder = body -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Stub.ok("{\"sessionId\":\"s-1\"}");
        };
        HttpHangmanClient client = new HttpHangmanClient(endpoint,
                Duration.ofMillis(200), Duration.ofMillis(150));

        assertThatThrownBy(() -> client.startGame("player"))
                .isInstanceOf(HangmanApiException.class)
                .satisfies(e -> assertThat(((HangmanApiException) e).isRetryable()).isTrue())
                .hasMessageContaining("timed out");
    }

    private HttpHangmanClient client() {
        return new HttpHangmanClient(endpoint, Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    private record Stub(int status, String body) {

        static Stub ok(String body) {
            return new Stub(200, body);
        }
    }
}
