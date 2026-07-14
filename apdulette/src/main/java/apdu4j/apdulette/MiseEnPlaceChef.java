// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.apdulette.PreparationStep.Ingredients;
import apdu4j.core.ResponseAPDU;
import apdu4j.prefs.Preferences;

import java.util.Collections;

// Pre-computation executor: feeds expected responses to taster, no I/O
public final class MiseEnPlaceChef implements Chef {

    @Override
    public <T> Dish<T> serve(Recipe<T> recipe, Preferences prefs) {
        return SousChef.serve(recipe, prefs, MiseEnPlaceChef::simulate);
    }

    // Use explicit expectations, or assume 9000 for each command
    private static <T> Verdict<T> simulate(Ingredients<T> ing, Preferences prefs) {
        var responses = ing.expected().isEmpty() ? Collections.nCopies(ing.commands().size(), ResponseAPDU.OK) : ing.expected();
        return ing.taster().taste(responses, prefs);
    }
}
