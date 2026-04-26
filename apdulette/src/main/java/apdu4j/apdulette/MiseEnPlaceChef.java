// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.apdulette.PreparationStep.Failed;
import apdu4j.apdulette.PreparationStep.Ingredients;
import apdu4j.apdulette.PreparationStep.Premade;
import apdu4j.apdulette.PreparationStep.Seasoned;
import apdu4j.apdulette.Verdict.NextStep;
import apdu4j.apdulette.Verdict.Ready;
import apdu4j.core.ResponseAPDU;
import apdu4j.prefs.Preferences;

import java.util.Collections;

// Pre-computation executor: feeds expected responses to taster, no I/O
public final class MiseEnPlaceChef implements Chef {

    @Override
    public <T> Dish<T> serve(Recipe<T> recipe, Preferences prefs) {
        var current = recipe;
        var currentPrefs = prefs;
        for (int i = 0; i < SousChef.MAX_ITERATIONS; i++) {
            switch (current.prepare(currentPrefs)) {
                case Premade<T>(var v) -> {
                    return new Dish<>(v, currentPrefs);
                }
                case Seasoned<T>(var r, var p) -> {
                    current = r;
                    currentPrefs = currentPrefs.merge(p);
                }
                case Failed<T>(var reason) -> throw new KitchenDisaster(reason);
                case Ingredients<T> ing -> {
                    // Use explicit expectations, or assume 9000 for each command
                    var responses = ing.expected().isEmpty() ? Collections.nCopies(ing.commands().size(), ResponseAPDU.OK) : ing.expected();
                    switch (ing.taster().apply(responses, currentPrefs)) {
                        case Ready<T>(var v, var p) -> {
                            return new Dish<>(v, currentPrefs.merge(p));
                        }
                        case NextStep<T>(var r, var p) -> {
                            current = r;
                            currentPrefs = currentPrefs.merge(p);
                        }
                        case Verdict.Error<T> err ->
                                throw new KitchenDisaster("%s (SW=%04X)".formatted(err.message(), err.sw()));
                    }
                }
            }
        }
        throw new KitchenDisaster("Recipe exceeded " + SousChef.MAX_ITERATIONS + " iterations");
    }
}
