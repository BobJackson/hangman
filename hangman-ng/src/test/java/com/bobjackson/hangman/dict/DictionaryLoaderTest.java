package com.bobjackson.hangman.dict;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DictionaryLoaderTest {

    @Test
    void stripsCarriageReturnsLeftByNaiveLineSplitting() {
        // This is the failure mode that silently broke a cross-check against this very
        // repository: words.txt is CRLF, and "THE\r" never equals "THE".
        DictionaryLoader.LoadResult result = DictionaryLoader.fromLines(
                List.of("ALPHA\r", "BETA\r", "GAMMA\r"), WordWeighting.uniform());

        assertThat(result.kept()).isEqualTo(3);
        assertThat(result.dictionary().wordAt(0)).isEqualTo("ALPHA");
        for (int len : result.dictionary().lengthsArray()) {
            for (int index : result.dictionary().indicesOfLength(len)) {
                assertThat(result.dictionary().wordAt(index)).doesNotContain("\r");
            }
        }
    }

    @Test
    void countsRejectedAndDuplicateLinesInsteadOfSwallowingThem() {
        DictionaryLoader.LoadResult result = DictionaryLoader.fromLines(
                List.of("ALPHA", "ALPHA", "B3TA", "", "   ", "gamma"), WordWeighting.uniform());

        assertThat(result.linesRead()).isEqualTo(4);
        assertThat(result.kept()).isEqualTo(2);
        assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.yield()).isEqualTo(0.5);
    }

    @Test
    void uppercasesInput() {
        DictionaryLoader.LoadResult result = DictionaryLoader.fromLines(
                List.of("alpha", "Beta"), WordWeighting.uniform());

        assertThat(result.dictionary().wordAt(0)).isEqualTo("ALPHA");
        assertThat(result.dictionary().wordAt(1)).isEqualTo("BETA");
    }

    @Test
    void groupsWordsByLength() {
        Dictionary dictionary = DictionaryLoader.fromLines(
                List.of("AA", "BBB", "CCC", "DDDDD"), WordWeighting.uniform()).dictionary();

        assertThat(dictionary.indicesOfLength(2)).hasSize(1);
        assertThat(dictionary.indicesOfLength(3)).hasSize(2);
        assertThat(dictionary.indicesOfLength(4)).isEmpty();
        assertThat(dictionary.indicesOfLength(5)).hasSize(1);
        assertThat(dictionary.lengthsArray()).containsExactly(2, 3, 5);
    }

    @Test
    void failsWhenNothingSurvivesSanitisation() {
        assertThatThrownBy(() -> DictionaryLoader.fromLines(List.of("123", "$$$", ""),
                WordWeighting.uniform()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no usable words");
    }

    @Test
    void readsAFileWithWindowsLineEndings(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("words.txt");
        byte[] content = "alpha\r\nBETA\r\nG4MMA\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);

        DictionaryLoader.LoadResult result = DictionaryLoader.fromFile(file, WordWeighting.uniform());

        assertThat(result.kept()).isEqualTo(2);
        assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.dictionary().size()).isEqualTo(2);
    }

    @Test
    void rejectsAMissingFile(@TempDir Path tempDir) {
        assertThatThrownBy(() -> DictionaryLoader.fromFile(tempDir.resolve("absent.txt"),
                WordWeighting.uniform()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void describesWordCountPerLength() {
        Dictionary dictionary = DictionaryLoader.fromLines(
                List.of("AA", "BBB", "CCC"), WordWeighting.uniform()).dictionary();

        assertThat(DictionaryLoader.describe(dictionary))
                .containsExactly("len 2: 1", "len 3: 2");
    }
}
