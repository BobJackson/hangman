package com.bobjackson.hangman.solver;

import java.util.Objects;
import java.util.Optional;

/**
 * Wraps a {@link LetterSelector}, prices its choice exactly, and then plays it anyway.
 *
 * <p>The point is measurement, not improvement. {@link ExactScore} can only run on states
 * small enough to solve out, and those are exactly the states near the end of a word, where
 * the twenty points are actually won or lost. On every such state this selector asks what the
 * best letter was worth and what the delegate's letter was worth, records the difference, and
 * returns the delegate's letter unchanged. An audited run therefore scores bit for bit what
 * the plain run scores; the only thing that changes is {@link AuditSummary}.
 *
 * <p>That property is what makes the result readable. If the gap turns out to be small, the
 * greedy objective is vindicated on the only states that can be priced and the honest
 * conclusion is that lookahead is not where the remaining points are. If it turns out to be
 * large, the number is the budget a full policy would have to beat, and it was obtained
 * without risking a single point on a heuristic whose leaves are estimated.
 *
 * <p>The thresholds exist because the exact tree explodes with the candidate count and with
 * the misses in hand. They are counted, not hidden: a decision that is too large is reported
 * through {@link AuditSummary#recordOverBudget()}.
 */
public final class AuditedLetterSelector implements LetterSelector {

    /** Above this many candidates a state is too wide to price within the budget. */
    public static final int DEFAULT_MAX_CANDIDATES = 40;

    /** With more misses in hand than this, the miss chains dominate the state count. */
    public static final int DEFAULT_MAX_MISSES_LEFT = 3;

    /** Distinct states one decision may expand before the audit gives up on it. */
    public static final int DEFAULT_NODE_BUDGET = 5_000;

    private final LetterSelector delegate;
    private final AuditSummary summary;
    private final int maxCandidates;
    private final int maxMissesLeft;
    private final int nodeBudget;

    public AuditedLetterSelector(LetterSelector delegate, AuditSummary summary) {
        this(delegate, summary, DEFAULT_MAX_CANDIDATES, DEFAULT_MAX_MISSES_LEFT, DEFAULT_NODE_BUDGET);
    }

    /**
     * @param delegate       the strategy that is actually played
     * @param summary        where observations accumulate; shared across all words of a run
     * @param maxCandidates  largest candidate set that will be priced
     * @param maxMissesLeft  most misses in hand that will be priced
     * @param nodeBudget     distinct states one decision may expand
     */
    public AuditedLetterSelector(LetterSelector delegate, AuditSummary summary,
                                 int maxCandidates, int maxMissesLeft, int nodeBudget) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.summary = Objects.requireNonNull(summary, "summary");
        if (maxCandidates < 1) {
            throw new IllegalArgumentException("maxCandidates must be positive: " + maxCandidates);
        }
        if (maxMissesLeft < 1) {
            throw new IllegalArgumentException("maxMissesLeft must be positive: " + maxMissesLeft);
        }
        if (nodeBudget < 1) {
            throw new IllegalArgumentException("nodeBudget must be positive: " + nodeBudget);
        }
        this.maxCandidates = maxCandidates;
        this.maxMissesLeft = maxMissesLeft;
        this.nodeBudget = nodeBudget;
    }

    @Override
    public char select(GuessContext context) {
        char chosen = delegate.select(context);
        summary.recordSeen();
        audit(context, chosen);
        return chosen;
    }

    public AuditSummary summary() {
        return summary;
    }

    private void audit(GuessContext context, char chosen) {
        if (!context.hasCandidates()
                || context.candidates().length > maxCandidates
                || context.wrongGuessesLeft() > maxMissesLeft
                || context.wrongGuessesLeft() <= 0
                || context.isGuessed(chosen)) {
            return;
        }
        Optional<ExactScore> measured = ExactScore.measure(context.dictionary(), context.candidates(),
                context.guessedMask(), context.wrongGuessesLeft(), nodeBudget);
        if (measured.isEmpty()) {
            summary.recordOverBudget();
            return;
        }
        ExactScore score = measured.get();
        summary.record(score.bestValue(), score.valueOf(chosen));
    }

    @Override
    public String toString() {
        return "audited[" + delegate + ']';
    }
}
