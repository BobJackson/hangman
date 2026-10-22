package com.bobjackson.hangman.transport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON reader and string escaper, sized for one protocol and nothing else.
 *
 * <p>The game server speaks a flat envelope — {@code {"sessionId": "...", "data": {...}}} — with
 * string and integer leaves. Pulling in a binding library for nine fields would put a runtime
 * dependency on the build, which until this milestone ran with none and can therefore be
 * rebuilt offline. This class parses the full grammar anyway, including arrays and nesting,
 * because a hand-rolled reader that only recognises the shapes it has seen is a reader that
 * breaks the first time the server wraps something.
 *
 * <p>What it does not do: reject duplicate keys, normalise numbers to a single type, or
 * preserve key order beyond insertion. Integers come back as {@code Long}, fractional numbers
 * as {@code Double}, and nothing in this protocol needs either.
 */
final class Json {

    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    /** Parses a JSON object, ignoring surrounding whitespace. */
    static Map<String, Object> parseObject(String text) {
        Json json = new Json(text);
        json.skipWhitespace();
        Map<String, Object> value = json.readObject();
        json.skipWhitespace();
        if (json.position != json.text.length()) {
            throw json.error("trailing content");
        }
        return value;
    }

    /** Renders {@code value} as a JSON string literal, escapes included. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> object = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return object;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, readValue());
            skipWhitespace();
            char next = next();
            if (next == '}') {
                return object;
            }
            if (next != ',') {
                throw error("expected ',' or '}' but found '" + next + '\'');
            }
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> array = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
            char next = next();
            if (next == ']') {
                return array;
            }
            if (next != ',') {
                throw error("expected ',' or ']' but found '" + next + '\'');
            }
        }
    }

    private Object readValue() {
        char c = peek();
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, position)) {
            throw error("expected " + literal);
        }
        position += literal.length();
        return value;
    }

    private Object readNumber() {
        int start = position;
        if (peek() == '-') {
            position++;
        }
        boolean fractional = false;
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c >= '0' && c <= '9') {
                position++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                fractional = fractional || c == '.' || c == 'e' || c == 'E';
                position++;
            } else {
                break;
            }
        }
        String token = text.substring(start, position);
        if (token.isEmpty() || token.equals("-")) {
            throw error("expected a number");
        }
        try {
            return fractional ? (Object) Double.parseDouble(token) : (Object) Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw error("malformed number \"" + token + '"');
        }
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (position >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(position++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = next();
            switch (escaped) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(readUnicodeEscape());
                default -> throw error("unknown escape \\" + escaped);
            }
        }
    }

    private char readUnicodeEscape() {
        if (position + 4 > text.length()) {
            throw error("truncated \\u escape");
        }
        String hex = text.substring(position, position + 4);
        position += 4;
        try {
            return (char) Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw error("malformed \\u escape \"" + hex + '"');
        }
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private char peek() {
        if (position >= text.length()) {
            throw error("unexpected end of input");
        }
        return text.charAt(position);
    }

    private char next() {
        char c = peek();
        position++;
        return c;
    }

    private void expect(char expected) {
        char actual = next();
        if (actual != expected) {
            throw error("expected '" + expected + "' but found '" + actual + '\'');
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON: " + message + " at offset " + position);
    }
}
