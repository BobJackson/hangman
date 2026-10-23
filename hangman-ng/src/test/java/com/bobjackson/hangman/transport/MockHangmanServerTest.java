package com.bobjackson.hangman.transport;

import com.bobjackson.hangman.api.GameResult;
import com.bobjackson.hangman.api.HangmanClient;
import com.bobjackson.hangman.api.StartGame;
import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.RankWordWeighting;
import com.bobjackson.hangman.engine.GameEngine;
import com.bobjackson.hangman.sim.SimulatedServer;
import com.bobjackson.hangman.sim.SimulationConfig;
import com.bobjackson.hangman.solver.EliminationSolver;
import com.bobjackson.hangman.solver.FrequencyLetterSelector;
import com.bobjackson.hangman.solver.SolverFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one test in the project where both halves of the protocol are real at once.
 *
 * <p>{@code HttpHangmanClientTest} checks the client against a hand-written responder;
 * {@code SimulatedServerTest} checks the simulator in process. Neither can catch the two of
 * them disagreeing about the wire format, because a stub agrees with whatever the client
 * expects. Here the client's requests are parsed by the server's own reader and the server's
 * replies are parsed by the client's own parser, so a renamed field or an unquoted number
 * fails a test.
 */
class MockHangmanServerTest {

    private static RankWordWeighting ranking;
    private static Dictionary dictionary;

    private MockHangmanServer server;
    private URI endpoint;

    @BeforeAll
    static void loadCorpus() {
        ranking = RankWordWeighting.fromClasspath();
        dictionary = Dictionary.of(ranking.topN(5_000), ranking);
    }

    @BeforeEach
    void startServer() throws IOException {
        // Port 0: the test never picks a number, so two builds running at once cannot collide.
        server = new MockHangmanServer("127.0.0.1", 0, MockHangmanServerTest::newSession);
        server.start();
        endpoint = server.endpoint();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void servesTheRuleParametersInTheStartGameReply() {
        try (HttpHangmanClient client = client()) {
            StartGame game = client.startGame("player@example.com");

            assertThat(game.numberOfWordsToGuess()).isEqualTo(4);
            assertThat(game.numberOfGuessAllowedForEachWord()).isEqualTo(10);
            assertThat(game.sessionId()).isNotBlank();
        }
    }

    @Test
    void playsAFullSessionOverTheWire() {
        GameResult result = playOneSession();

        assertThat(result.totalWordCount()).isEqualTo(4);
        assertThat(result.isScoreConsistent())
                .as("the served score must match 20*correct - wrong, as the client asserts")
                .isTrue();
    }

    @Test
    void everySessionReplaysTheSameAnswers() {
        // The fixed seed is deliberate: a failure reproduces from --seed alone, and the same
        // guarantee the offline harness offers survives the trip through a socket.
        assertThat(playOneSession()).isEqualTo(playOneSession());
    }

    @Test
    void rejectsAnUnknownAction() throws Exception {
        HttpResponse<String> response = post("{\"action\":\"dance\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("unknown action: dance");
    }

    @Test
    void rejectsAnUnknownSession() throws Exception {
        HttpResponse<String> response = post("{\"action\":\"nextWord\",\"sessionId\":\"s-nope\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("unknown session: s-nope");
    }

    @Test
    void rejectsAGuessBeforeAWordWasServed() throws Exception {
        try (HttpHangmanClient client = client()) {
            String sessionId = client.startGame("player").sessionId();

            HttpResponse<String> response =
                    post("{\"action\":\"guessWord\",\"sessionId\":\"" + sessionId + "\",\"guess\":\"E\"}");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("nextWord first");
        }
    }

    @Test
    void endsTheSessionOnceTheResultIsSubmitted() throws Exception {
        try (HttpHangmanClient client = client()) {
            String sessionId = client.startGame("player").sessionId();

            assertThat(client.submitResult().totalWordCount()).isZero();

            HttpResponse<String> afterSubmit =
                    post("{\"action\":\"nextWord\",\"sessionId\":\"" + sessionId + "\"}");
            assertThat(afterSubmit.statusCode()).isEqualTo(400);
            assertThat(afterSubmit.body()).contains("unknown session");
        }
    }

    @Test
    void refusesAnythingThatIsNotAPost() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }

    private GameResult playOneSession() {
        SolverFactory solver = (firstState, allowed) -> EliminationSolver.forWord(
                dictionary,
                new FrequencyLetterSelector(FrequencyLetterSelector.Mode.COVERAGE),
                firstState, allowed);
        try (HttpHangmanClient client = client()) {
            return new GameEngine(client, solver).play("player");
        }
    }

    private HttpHangmanClient client() {
        return new HttpHangmanClient(endpoint, Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    private HttpResponse<String> post(String body) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Four words, one per tier, so a whole game costs milliseconds. */
    private static HangmanClient newSession() {
        return new SimulatedServer(new SimulationConfig(5_000, SimulationConfig.Sampling.WEIGHTED,
                SimulationConfig.DifficultySemantics.BUCKETED, 1, 7L, 0.0, 4, 10), ranking);
    }
}
