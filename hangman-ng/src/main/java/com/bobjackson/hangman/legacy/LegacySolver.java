package com.bobjackson.hangman.legacy;

import com.bobjackson.hangman.api.WordState;
import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.solver.Solver;
import com.bobjackson.hangman.solver.SolverFactory;

import java.util.Arrays;
import java.util.Objects;

/**
 * A faithful reimplementation of the legacy client's <em>decision</em> logic.
 *
 * <p>This is not a competitor to {@code EliminationSolver}; it is the control group. Running
 * the original algorithm against the same simulator, the same seeds and the same answer pool
 * turns "the old code had defects" from a code-review opinion into a score difference.
 *
 * <h2>What is reproduced exactly</h2>
 * <ol>
 *   <li>The first guess is always {@code E}, hard-coded.</li>
 *   <li>Candidates start as the whole bucket for the current word number, <b>not</b> filtered
 *       by the answer's length.</li>
 *   <li>Length filtering happens only inside the wrong-guess path, so a hit on the first
 *       guess leaves words of every length in play.</li>
 *   <li>On a miss, only the newest missed letter is removed; on a hit, only the newest
 *       revealed letter constrains the set. The history lists are never replayed — which is
 *       <b>not</b> itself a defect: candidates are filtered in place, so every earlier
 *       constraint has already been applied and dropping the history loses nothing. What it
 *       does mean is that a candidate set rebuilt from scratch could not be recovered, and
 *       the original rebuilt it on every word.</li>
 *   <li>Letter choice is the highest total occurrence count across the candidate set,
 *       skipping letters already answered correctly but <b>not</b> skipping letters already
 *       missed.</li>
 *   <li>When the candidate set is empty the occurrence counts are not recomputed, so the
 *       previous word state's counts are reused.</li>
 *   <li>The fallback order is {@code EAOIUTSRNHLDCMFPGWYBVKXJQZ}.</li>
 * </ol>
 *
 * <h2>What is deliberately not reproduced, and why</h2>
 * <ul>
 *   <li><b>Rebuilding four 416k-entry lists per word.</b> That is a cost, not a decision; it
 *       is what made a game take 86 minutes and it would make this comparison take days.
 *       {@link LegacyTiers} builds once per run.</li>
 *   <li><b>Reflection into {@code LinkedHashMap.tail}.</b> Reading the newest entry is
 *       reproduced with a field; how it was read changes no decision.</li>
 *   <li><b>{@code CopyOnWriteArrayList} iteration while removing.</b> The same removals are
 *       produced with array filtering. The surviving set is identical.</li>
 *   <li><b>Repeating an already-guessed letter.</b> The stale-count path can select a letter
 *       that was already tried; against the real server that burned an attempt and left the
 *       state unchanged, which the simulator would turn into an infinite loop. Skipping
 *       guessed letters is the one behavioural deviation, and it can only help the legacy
 *       score, never hurt it.</li>
 *   <li><b>Non-deterministic ties.</b> The original sorted entries by count with a stable
 *       sort and then took the first key, so ties fell back to {@code HashMap} iteration
 *       order. For 26 {@code Character} keys that order is alphabetical — {@code 'A'} hashes
 *       to the lowest bucket — so ties here break alphabetically too. This matters more than
 *       it sounds: with sixteen thousand candidates, equal counts are common.</li>
 * </ul>
 */
public final class LegacySolver implements Solver {

    /** Hard-coded first guess: {@code FIRST_GUESS_LETTER = "E"}. */
    private static final char FIRST_GUESS = 'E';

    /** {@code lettersByFrequency}, in order, before any removal. */
    private static final String LETTER_ORDER = "EAOIUTSRNHLDCMFPGWYBVKXJQZ";

    private final Dictionary dictionary;
    private final boolean[] guessed = new boolean[26];
    private final boolean[] answeredCorrectly = new boolean[26];
    private final int[] occurrences = new int[26];

    private int[] candidates;
    private boolean occurrencesPresent;
    private boolean firstGuessTaken;

    /**
     * @param tiers      the legacy buckets, shared across every word in the run
     * @param firstState the freshly served word, used for its position in the session
     */
    public LegacySolver(LegacyTiers tiers, WordState firstState) {
        Objects.requireNonNull(tiers, "tiers");
        Objects.requireNonNull(firstState, "firstState");
        this.dictionary = tiers.dictionary();
        // "每次猜新词都需要重新初始化 allDictionary" — but only the bucket lookup is per word.
        // No copy: every filter below produces a fresh array, so the shared bucket is read-only.
        this.candidates = tiers.indicesForWord(firstState.totalWordCount());
    }

    /** The factory the harness binds to a dictionary. */
    public static SolverFactory factory(LegacyTiers tiers) {
        Objects.requireNonNull(tiers, "tiers");
        return (firstState, wrongGuessesAllowed) -> new LegacySolver(tiers, firstState);
    }

    @Override
    public char nextGuess() {
        if (!firstGuessTaken) {
            firstGuessTaken = true;
            guessed[FIRST_GUESS - 'A'] = true;
            return FIRST_GUESS;
        }

        // "if (suitableDictionary.size() > 0) countCharacterByLetter(...)" — and when it is
        // empty the old counts survive, which is reproduced by not touching the array.
        if (candidates.length > 0) {
            countOccurrences();
        }

        char best = mostFrequentUnguessed();
        if (best != 0) {
            guessed[best - 'A'] = true;
            return best;
        }

        // Strategy two: walk the frequency order and take the first letter never guessed.
        for (int i = 0; i < LETTER_ORDER.length(); i++) {
            char letter = LETTER_ORDER.charAt(i);
            if (!guessed[letter - 'A']) {
                guessed[letter - 'A'] = true;
                return letter;
            }
        }
        throw new IllegalStateException("every letter has been guessed");
    }

    @Override
    public void observe(char guess, WordState state) {
        Objects.requireNonNull(state, "state");
        guessed[guess - 'A'] = true;

        String mask = state.word();
        if (mask.indexOf(guess) < 0) {
            // wrongHistory.add(guess) followed by removeLatestWrongGuess: the length filter
            // lives here, and only here.
            removeContaining(guess, mask.length());
            return;
        }

        // recordRightGuess + narrowDictionaryByRightGuess: the mask already reveals every
        // occurrence of the letter, so its positions in the mask are all of its positions.
        answeredCorrectly[guess - 'A'] = true;
        narrowByCorrectLetter(guess, mask);
    }

    @Override
    public int candidateCount() {
        return candidates.length;
    }

    /** How many candidates remain, for diagnostics in tests. */
    public int[] candidateIndices() {
        return candidates.clone();
    }

    private void removeContaining(char wrong, int length) {
        int[] kept = new int[candidates.length];
        int n = 0;
        for (int index : candidates) {
            String word = dictionary.wordAt(index);
            if (word.indexOf(wrong) < 0 && word.length() == length) {
                kept[n++] = index;
            }
        }
        candidates = Arrays.copyOf(kept, n);
    }

    /**
     * Applies the newest correct guess only, which is what the original did: it read the last
     * entry of {@code rightGuess} through reflection and ignored every earlier one.
     *
     * <p>Two conditions, both from the original loop: the letter must occur exactly as many
     * times as the mask shows, and every position where the mask reveals it must hold it.
     * A word shorter than a revealed position never survived ({@code charAt} threw and the
     * word was dropped) but a word of the <em>wrong length</em> could survive, because length
     * was never compared here.
     */
    private void narrowByCorrectLetter(char letter, String mask) {
        int expected = countOf(letter, mask);
        int[] kept = new int[candidates.length];
        int n = 0;
        for (int index : candidates) {
            String word = dictionary.wordAt(index);
            if (countOf(letter, word) == expected && revealsMatch(letter, mask, word)) {
                kept[n++] = index;
            }
        }
        candidates = Arrays.copyOf(kept, n);
    }

    private static boolean revealsMatch(char letter, String mask, String word) {
        for (int i = 0; i < mask.length(); i++) {
            if (mask.charAt(i) == letter) {
                if (i >= word.length() || word.charAt(i) != letter) {
                    return false;
                }
            }
        }
        return true;
    }

    private void countOccurrences() {
        Arrays.fill(occurrences, 0);
        for (int index : candidates) {
            String word = dictionary.wordAt(index);
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                if (!answeredCorrectly[c - 'A']) {
                    occurrences[c - 'A']++;
                }
            }
        }
        occurrencesPresent = true;
    }

    /**
     * {@code sortMapByValue(characters)} then {@code keySet().toArray()[0]}.
     *
     * <p>The original did not skip letters it had already guessed (see the class comment).
     * Ties resolve alphabetically, which is the order {@code HashMap} iterated its
     * {@code Character} keys in.
     */
    private char mostFrequentUnguessed() {
        if (!occurrencesPresent) {
            return 0;
        }
        char best = 0;
        int bestCount = 0;
        for (char letter = 'A'; letter <= 'Z'; letter++) {
            int count = occurrences[letter - 'A'];
            if (count > bestCount && !guessed[letter - 'A']) {
                best = letter;
                bestCount = count;
            }
        }
        return best;
    }

    private static int countOf(char letter, String word) {
        int n = 0;
        for (int i = 0; i < word.length(); i++) {
            if (word.charAt(i) == letter) {
                n++;
            }
        }
        return n;
    }
}
