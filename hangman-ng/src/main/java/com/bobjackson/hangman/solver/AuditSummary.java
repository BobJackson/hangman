package com.bobjackson.hangman.solver;

import java.util.Locale;

/**
 * What {@link AuditedLetterSelector} saw, accumulated across a whole run.
 *
 * <p>A counter bag rather than a report: the selector is created once per run and shared by
 * every word, so this is the one place where per-decision observations become a number.
 *
 * <p>{@code foregone} sums the <em>expected</em> points lost by not taking the best letter.
 * That is the quantity a lookahead policy would have to recover, and it is deliberately not
 * the score difference: the score difference also carries the noise of a single realisation,
 * which would swamp a few points per game. Because the audit never changes what is played,
 * the score line of an audited run is identical to the plain one by construction; only this
 * object differs.
 *
 * <p>Synchronised because a best-of-N run plays its attempts on several threads and every
 * attempt shares the one summary.
 */
public final class AuditSummary {

    /** Values closer than this are the same decision; the DP is deterministic in doubles. */
    private static final double TOLERANCE = 1e-9;

    private long seen;
    private long decisions;
    private long suboptimal;
    private double foregone;
    private long overBudget;

    /** Records one choice the selector made, priced or not; the denominator of coverage. */
    public synchronized void recordSeen() {
        seen++;
    }

    /** Records one priced decision and what the selector actually chose. */
    public synchronized void record(double bestValue, double chosenValue) {
        double gap = bestValue - chosenValue;
        if (gap < -TOLERANCE) {
            // The chosen letter cannot beat the measured optimum; if it does, one of the two
            // numbers is wrong and a silent clamp would hide it.
            throw new IllegalStateException(
                    "chosen letter beats the measured optimum by " + (-gap) + " points");
        }
        decisions++;
        if (gap > TOLERANCE) {
            suboptimal++;
            foregone += gap;
        }
    }

    /** Records a decision that was too large to price, so the run says how much it skipped. */
    public synchronized void recordOverBudget() {
        overBudget++;
    }

    public synchronized long decisions() {
        return decisions;
    }

    /** Every choice the selector made, priced or not. */
    public synchronized long seen() {
        return seen;
    }

    public synchronized long suboptimal() {
        return suboptimal;
    }

    /** Expected points per game that the played strategy left on the table. */
    public synchronized double foregonePerGame(int games) {
        if (games < 1) {
            throw new IllegalArgumentException("games must be positive: " + games);
        }
        return foregone / games;
    }

    public synchronized long overBudget() {
        return overBudget;
    }

    /** One ASCII line, in the same spirit as the benchmark tables. */
    public synchronized String describe(int games) {
        if (games < 1) {
            throw new IllegalArgumentException("games must be positive: " + games);
        }
        if (decisions == 0) {
            return String.format(Locale.ROOT,
                    "audit: 0 of %d choices priced exactly (%d over budget)%n", seen, overBudget);
        }
        return String.format(Locale.ROOT,
                "audit: %d of %d choices priced exactly (%.1f%%), %d not optimal (%.1f%%), "
                        + "%.2f points per game foregone, %d over budget%n",
                decisions, seen, 100.0 * decisions / Math.max(1, seen),
                suboptimal, 100.0 * suboptimal / decisions,
                foregone / games, overBudget);
    }
}
