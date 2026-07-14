// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.apdulette.PreparationStep.CardError;
import apdu4j.apdulette.PreparationStep.Ingredients;
import apdu4j.apdulette.PreparationStep.Premade;
import apdu4j.apdulette.PreparationStep.Seasoned;
import apdu4j.apdulette.Verdict.NextStep;
import apdu4j.apdulette.Verdict.Ready;
import apdu4j.prefs.Preferences;

// The shared prepare-evaluate trampoline behind every Chef; the evaluator
// decides how an Ingredients step turns into a verdict.
final class SousChef {
    static final int MAX_ITERATIONS = 10_000;

    // Produces the verdict for one Ingredients step: transmission or simulation.
    interface Evaluator<T> {
        Verdict<T> evaluate(Ingredients<T> ing, Preferences prefs);
    }

    private SousChef() {
    }

    static <T> Dish<T> serve(Recipe<T> recipe, Preferences prefs, Evaluator<T> evaluator) {
        var current = recipe;
        var currentPrefs = prefs;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            switch (current.prepare(currentPrefs)) {
                case Premade<T>(var v) -> {
                    return new Dish<>(v, currentPrefs);
                }
                case CardError<T>(var response, var message) ->
                        throw new KitchenDisaster("%s (SW=%04X)".formatted(message, response.getSW()), response, currentPrefs);
                case Seasoned<T>(var r, var p) -> {
                    current = r;
                    currentPrefs = currentPrefs.merge(p);
                }
                case Ingredients<T> ing -> {
                    switch (evaluator.evaluate(ing, currentPrefs)) {
                        case Ready<T>(var v, var p) -> {
                            return new Dish<>(v, currentPrefs.merge(p));
                        }
                        case NextStep<T>(var r, var p) -> {
                            current = r;
                            currentPrefs = currentPrefs.merge(p);
                        }
                        case Verdict.Error<T> err ->
                                throw new KitchenDisaster("%s (SW=%04X)".formatted(err.message(), err.sw()), err.response(), currentPrefs);
                    }
                }
            }
        }
        throw new KitchenDisaster("Recipe exceeded " + MAX_ITERATIONS + " iterations", null, currentPrefs);
    }
}
