package com.bobjackson.hangman.transport;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    @Test
    void parsesEveryScalarTypeTheProtocolUses() {
        Map<String, Object> parsed = Json.parseObject(
                "{\"word\":\"**PP*\",\"totalWordCount\":3,\"score\":1326,\"ratio\":0.25,\"ok\":true,\"none\":null}");

        assertThat(parsed).containsEntry("word", "**PP*")
                .containsEntry("totalWordCount", 3L)
                .containsEntry("score", 1326L)
                .containsEntry("ratio", 0.25)
                .containsEntry("ok", true)
                .containsKey("none");
        assertThat(parsed.get("none")).isNull();
    }

    @Test
    void parsesTheNestedEnvelopeTheServerUses() {
        Map<String, Object> parsed = Json.parseObject(
                "{\"sessionId\":\"abc\",\"data\":{\"word\":\"C**\",\"wrongGuessCountOfCurrentWord\":1}}");

        assertThat(parsed).containsEntry("sessionId", "abc");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) parsed.get("data");
        assertThat(data).containsEntry("word", "C**").containsEntry("wrongGuessCountOfCurrentWord", 1L);
    }

    @Test
    void parsesArraysIncludingEmptyOnes() {
        Map<String, Object> parsed = Json.parseObject("{\"words\":[\"A\",\"B\"],\"none\":[],\"nested\":[[1]]}");

        assertThat(parsed.get("words")).isEqualTo(List.of("A", "B"));
        assertThat(parsed.get("none")).isEqualTo(List.of());
        assertThat(parsed.get("nested")).isEqualTo(List.of(List.of(1L)));
    }

    @Test
    void decodesEscapesInsideStrings() {
        Map<String, Object> parsed = Json.parseObject("{\"s\":\"a\\\"b\\\\c\\nd\\u0041\"}");

        assertThat(parsed).containsEntry("s", "a\"b\\c\ndA");
    }

    @Test
    void toleratesWhitespaceBetweenEverything() {
        Map<String, Object> parsed = Json.parseObject("  { \n \"a\" : 1 ,\t\"b\" : [ 2 ] \n }  ");

        assertThat(parsed).containsEntry("a", 1L).containsEntry("b", List.of(2L));
    }

    @Test
    void rejectsTrailingContentAfterTheObject() {
        assertThatThrownBy(() -> Json.parseObject("{\"a\":1} garbage"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing content");
    }

    @Test
    void rejectsUnterminatedStringsAndMissingColons() {
        assertThatThrownBy(() -> Json.parseObject("{\"a\":\"unterminated}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unterminated");
        assertThatThrownBy(() -> Json.parseObject("{\"a\" 1}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected ':'");
    }

    @Test
    void quotesAndEscapesWhenSerialising() {
        assertThat(Json.quote("plain")).isEqualTo("\"plain\"");
        assertThat(Json.quote("a\"b")).isEqualTo("\"a\\\"b\"");
        assertThat(Json.quote("a\\b")).isEqualTo("\"a\\\\b\"");
        assertThat(Json.quote("a\nb")).isEqualTo("\"a\\nb\"");
        assertThat(Json.quote("a\u0001b")).isEqualTo("\"a\\u0001b\"");
    }

    @Test
    void roundTripsAStringThroughQuoteAndParse() {
        String awkward = "quote\" backslash\\ newline\n tab\t unicode\u00e9";

        Map<String, Object> parsed = Json.parseObject("{\"v\":" + Json.quote(awkward) + '}');

        assertThat(parsed).containsEntry("v", awkward);
    }
}
