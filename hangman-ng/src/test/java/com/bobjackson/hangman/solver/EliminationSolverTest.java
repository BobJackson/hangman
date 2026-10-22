package com.bobjackson.hangman.solver;

import com.bobjackson.hangman.api.WordState;
import com.bobjackson.hangman.dict.Dictionary;
import com.bobjackson.hangman.dict.WordWeighting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EliminationSolverTest {

    private static final int ALLOWED = 10;

    @Test
    void onlyWordsOfTheRightLengthStartAsCandidates() {
        // The legacy implementation filtered by length only inside its "wrong guess" branch,
        // so a first guess that hit left words of every length in play. Here the length
        // filter happens at construction and cannot be skipped.
        Dictionary dictionary = lexicon("CAT", "DOG", "BIRD", "FISH");

        EliminationSolver solver = new EliminationSolver(dictionary, coverage(), 3, ALLOWED);

        assertThat(solver.candidateCount()).isEqualTo(2);
    }

    @Test
    void stillOnlyWordsOfTheRightLengthAfterAFirstGuessHits() {
        Dictionary dictionary = lexicon("CAT", "CAR", "BIRD", "FISH");

        EliminationSolver solver = new EliminationSolver(dictionary, coverage(), 3, ALLOWED);
        solver.observe('C', state("C**", 0));

        assertThat(solver.candidateCount()).isEqualTo(2); // CAT, CAR — and never BIRD / FISH
    }

    @Test
    void aMissEliminatesEveryWordContainingTheMissedLetter() {
        Dictionary dictionary = lexicon("CAT", "CAR", "DOG");

        EliminationSolver solver = new EliminationSolver(dictionary, coverage(), 3, ALLOWED);
        solver.observe('D', state("***", 1));

        assertThat(solver.candidateCount()).isEqualTo(2);
        assertThat(solver.wrongGuessesLeft()).isEqualTo(ALLOWED - 1);
    }

    @Test
    void aRepeatedLetterIsPinnedByTheMaskAlone() {
        Dictionary dictionary = lexicon("LEVEL", "LOCAL", "LEMON");

        EliminationSolver solver = new EliminationSolver(dictionary, coverage(), 5, ALLOWED);
        // L occurs twice in the answer, so both positions are revealed at once.
        solver.observe('L', state("L***L", 0));
        assertThat(solver.candidateCount()).isEqualTo(2); // LEVEL, LOCAL — LEMON has one L

        // Revealing E reveals both of its occurrences, which rules out LOCAL: its second
        // letter is O, and its fourth is A.
        solver.observe('E', state("LE*EL", 0));
        assertThat(solver.candidateCount()).isEqualTo(1);
    }

    @Test
    void candidateCountNeverGrows() {
        Dictionary dictionary = lexicon("LEVEL", "LOCAL", "LEMON", "LEVER");
        EliminationSolver solver = new EliminationSolver(dictionary, coverage(), 5, ALLOWED);

        int previous = solver.candidateCount();
        String[] masks = {"L***L", "LE*EL", "LEVEL"};
        char[] guesses = {'L', 'E', 'V'};
        for (int i = 0; i < guesses.length; i++) {
            solver.observe(guesses[i], state(masks[i], 0));
            assertThat(solver.candidateCount()).isLessThanOrEqualTo(previous);
            previous = solver.candidateCount();
        }
    }

    @Test
    void fallsBackToLetterFrequencyWhenTheDictionaryRunsOut() {
        Dictionary dictionary = lexicon("CAT");

        EliminationSolver solver = new EliminationSolver(dictionary, coverage(), 3, ALLOWED);
        // Pretend the server missed on C, which no dictionary word can explain since CAT
        // contains it: the answer simply is not in our list.
        solver.observe('C', state("***", 1));

        assertThat(solver.candidateCount()).isZero();
        assertThat(solver.nextGuess()).isEqualTo('E'); // C is gone, E leads the frequency order
    }

    @Test
    void neverReturnsALetterThatWasAlreadyGuessed() {
        Dictionary dictionary = lexicon("CAT");
        LetterSelector stuck = context -> 'C';

        EliminationSolver solver = new EliminationSolver(dictionary, stuck, 3, ALLOWED);
        solver.observe('C', state("C**", 0));

        assertThatThrownBy(solver::nextGuess)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already-guessed");
    }

    @Test
    void rejectsASelectorThatReturnsSomethingOtherThanALetter() {
        Dictionary dictionary = lexicon("CAT");

        EliminationSolver solver = new EliminationSolver(dictionary, context -> '?', 3, ALLOWED);

        assertThatThrownBy(solver::nextGuess).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAResponseOfADifferentLength() {
        Dictionary dictionary = lexicon("CAT");

        EliminationSolver solver = new EliminationSolver(dictionary, coverage(), 3, ALLOWED);

        assertThatThrownBy(() -> solver.observe('C', state("C***", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void tracksTheMaskAsItIsRevealed() {
        Dictionary dictionary = lexicon("LEVEL");

        EliminationSolver solver = EliminationSolver.forWord(dictionary, coverage(), state("*****", 0), ALLOWED);
        solver.observe('L', state("L***L", 0));
        solver.observe('E', state("LE*EL", 0));

        assertThat(solver.currentPattern()).isEqualTo("LE*EL");
        assertThat(solver.candidateCount()).isEqualTo(1);
    }

    @Test
    void rejectsImpossibleConstructionArguments() {
        Dictionary dictionary = lexicon("CAT");

        assertThatThrownBy(() -> new EliminationSolver(dictionary, coverage(), 0, ALLOWED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EliminationSolver(dictionary, coverage(), 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static WordState state(String mask, int wrongGuesses) {
        return new WordState(mask, 1, wrongGuesses);
    }

    private static LetterSelector coverage() {
        return new FrequencyLetterSelector(FrequencyLetterSelector.Mode.COVERAGE);
    }

    private static Dictionary lexicon(String... words) {
        return Dictionary.of(List.of(words), WordWeighting.uniform());
    }
}
