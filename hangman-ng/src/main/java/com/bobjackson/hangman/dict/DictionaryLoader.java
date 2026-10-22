package com.bobjackson.hangman.dict;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tolerant parser that turns a raw word file into a {@link Dictionary}.
 *
 * <p>{@code words.txt} in this repository uses <b>CRLF</b> line endings. Standard line
 * readers cope, but the naïve alternative — {@code new String(readAllBytes()).split("\n")} —
 * leaves a trailing {@code '\r'} on every word, which makes every later string comparison
 * fail <em>silently</em>: the dictionary loads, the solver just never matches anything.
 * Every line is therefore {@link String#strip()}ped regardless of how it arrived.
 *
 * <p>Rejected and duplicate lines are counted rather than thrown away quietly, because
 * "how much of this file is actually usable" turned out to be the single most informative
 * number in this project: of 416,264 words, only ~19% appear in a 100k common-word list.
 */
public final class DictionaryLoader {

    private static final System.Logger LOG = System.getLogger(DictionaryLoader.class.getName());

    private DictionaryLoader() {
        // static utility
    }

    /**
     * Outcome of a load, including why lines were dropped.
     *
     * @param dictionary the usable words
     * @param linesRead  non-empty lines seen in the source
     * @param kept       distinct words accepted
     * @param rejected   lines that were not pure {@code A-Z} after trimming
     * @param duplicates lines repeating an already-accepted word
     */
    public record LoadResult(Dictionary dictionary, int linesRead, int kept, int rejected, int duplicates) {

        /** Fraction of read lines that survived, in [0,1]. */
        public double yield() {
            return linesRead == 0 ? 0.0 : (double) kept / linesRead;
        }
    }

    /** Reads and sanitises {@code file}, then builds a dictionary with the given weighting. */
    public static LoadResult fromFile(Path file, WordWeighting weighting) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("not a readable file: " + file);
        }
        List<String> raw = Files.readAllLines(file, StandardCharsets.UTF_8);
        return fromLines(raw, weighting);
    }

    /** Sanitises raw lines and builds a dictionary. */
    public static LoadResult fromLines(List<String> rawLines, WordWeighting weighting) {
        Set<String> accepted = new LinkedHashSet<>();
        int linesRead = 0;
        int rejected = 0;
        int duplicates = 0;

        for (String raw : rawLines) {
            // strip() removes the CRLF residue as well as any stray whitespace
            String word = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
            if (word.isEmpty()) {
                continue;
            }
            linesRead++;
            if (!isPlainLetters(word)) {
                rejected++;
                continue;
            }
            if (!accepted.add(word)) {
                duplicates++;
            }
        }

        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("no usable words found in " + linesRead + " lines");
        }

        Dictionary dictionary = Dictionary.of(accepted, weighting);
        LoadResult result = new LoadResult(dictionary, linesRead, accepted.size(), rejected, duplicates);

        LOG.log(System.Logger.Level.INFO,
                "dictionary loaded: {0} words from {1} lines (rejected {2}, duplicates {3})",
                result.kept(), result.linesRead(), result.rejected(), result.duplicates());
        return result;
    }

    private static boolean isPlainLetters(String word) {
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        return true;
    }

    /** Convenience wrapper when the load statistics are not needed. */
    public static Dictionary load(Path file, WordWeighting weighting) throws IOException {
        return fromFile(file, weighting).dictionary();
    }

    /** Word count per length, ascending, for diagnostics. */
    public static List<String> describe(Dictionary dictionary) {
        List<String> lines = new ArrayList<>();
        for (int len : dictionary.lengthsArray()) {
            lines.add("len " + len + ": " + dictionary.indicesOfLength(len).length);
        }
        return lines;
    }
}
