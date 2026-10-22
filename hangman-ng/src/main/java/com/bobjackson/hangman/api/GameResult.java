package com.bobjackson.hangman.api;

/**
 * Response to {@code getResult} and {@code submitResult}.
 *
 * <p>The scoring rule is not stated in the official instructions, but it is recoverable
 * from the two worked examples they publish:
 * <pre>
 *   78 correct, 234 wrong -&gt; 78*20 - 234 = 1326   (matches the reported score)
 *   77 correct, 233 wrong -&gt; 77*20 - 233 = 1307   (matches the reported score)
 * </pre>
 * Encoding it here lets {@link #isScoreConsistent()} act as a runtime assertion against
 * any server whose behaviour drifts from the documented examples.
 *
 * @param totalWordCount        words attempted
 * @param correctWordCount      words fully revealed
 * @param totalWrongGuessCount  incorrect guesses across the whole game
 * @param score                 the server's score
 */
public record GameResult(int totalWordCount,
                         int correctWordCount,
                         int totalWrongGuessCount,
                         int score) {

    /** Points awarded for revealing a word. */
    public static final int REWARD_PER_CORRECT_WORD = 20;

    /** Points deducted for each incorrect guess. */
    public static final int PENALTY_PER_WRONG_GUESS = 1;

    public GameResult {
        if (totalWordCount < 0 || correctWordCount < 0 || totalWrongGuessCount < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        if (correctWordCount > totalWordCount) {
            throw new IllegalArgumentException(
                    "correctWordCount " + correctWordCount + " exceeds totalWordCount " + totalWordCount);
        }
    }

    /** The score implied by the reverse-engineered formula. */
    public static int scoreOf(int correctWordCount, int totalWrongGuessCount) {
        return correctWordCount * REWARD_PER_CORRECT_WORD - totalWrongGuessCount * PENALTY_PER_WRONG_GUESS;
    }

    /** True when the reported score agrees with {@link #scoreOf(int, int)}. */
    public boolean isScoreConsistent() {
        return score == scoreOf(correctWordCount, totalWrongGuessCount);
    }

    /** Mean incorrect guesses per attempted word; 0 when nothing was attempted. */
    public double wrongGuessesPerWord() {
        return totalWordCount == 0 ? 0.0 : (double) totalWrongGuessCount / totalWordCount;
    }

    /** Fraction of attempted words that were revealed, in [0,1]. */
    public double accuracy() {
        return totalWordCount == 0 ? 0.0 : (double) correctWordCount / totalWordCount;
    }
}
