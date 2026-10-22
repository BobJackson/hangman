package com.bobjackson.hangman.solver;

/**
 * Static English letter frequency, used only as a last resort.
 *
 * <p>When the dictionary has been filtered down to nothing — meaning the answer is a word
 * we do not have — there is no candidate statistics left to consult, and the solver falls
 * back to plain letter frequency. This is the same idea as the legacy
 * {@code EAOIUTSRNHLDCMFPGWYBVKXJQZ} constant, using the more conventional ordering.
 *
 * <p>Unlike the legacy version, which mutated a shared {@code CopyOnWriteArrayList} with
 * {@code removeAll} and rebuilt it by re-adding characters, this is immutable and the
 * "first not yet guessed" query is answered from a bitmask.
 */
public final class LetterFrequency {

    /** Letters ordered by descending frequency in English text. */
    public static final String ORDER = "ETAOINSRHLDCUMFPGWYBVKXJQZ";

    /** Bitmask with all 26 letter bits set. */
    public static final int ALL_LETTERS = (1 << 26) - 1;

    private static final int[] RANK = new int[26];

    static {
        if (ORDER.length() != 26) {
            throw new AssertionError("ORDER must list all 26 letters exactly once");
        }
        for (int i = 0; i < ORDER.length(); i++) {
            RANK[ORDER.charAt(i) - 'A'] = i;
        }
    }

    private LetterFrequency() {
        // static utility
    }

    /** 0 for the most frequent letter, 25 for the least. */
    public static int rank(char letter) {
        requireLetter(letter);
        return RANK[letter - 'A'];
    }

    /** The letter at the given frequency rank. */
    public static char at(int rank) {
        if (rank < 0 || rank >= ORDER.length()) {
            throw new IndexOutOfBoundsException("rank out of range: " + rank);
        }
        return ORDER.charAt(rank);
    }

    /** Bit index for a letter: {@code 'A'} -&gt; 0 … {@code 'Z'} -&gt; 25. */
    public static int bitOf(char letter) {
        requireLetter(letter);
        return 1 << (letter - 'A');
    }

    /**
     * The most frequent letter that is not yet in {@code guessedMask}.
     *
     * @throws IllegalStateException if all 26 letters have been guessed. In a real game this
     *         cannot happen — a word is abandoned after 10 misses, so at most 10 letters are
     *         ever spent on it — but failing loudly beats the legacy behaviour of throwing
     *         {@code IndexOutOfBoundsException} from {@code list.get(0)}.
     */
    public static char firstUnguessed(int guessedMask) {
        for (int i = 0; i < ORDER.length(); i++) {
            char c = ORDER.charAt(i);
            if ((guessedMask & bitOf(c)) == 0) {
                return c;
            }
        }
        throw new IllegalStateException("all 26 letters have already been guessed");
    }

    private static void requireLetter(char letter) {
        if (letter < 'A' || letter > 'Z') {
            throw new IllegalArgumentException("expected A-Z, got '" + letter + '\'');
        }
    }
}
