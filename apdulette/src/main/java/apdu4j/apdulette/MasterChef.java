// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.apdulette.PreparationStep.Ingredients;
import apdu4j.core.BIBO;
import apdu4j.core.ResponseAPDU;
import apdu4j.prefs.Preferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Real-time executor: transmits APDUs over BIBO through the SousChef trampoline
public final class MasterChef implements Chef {
    private final BIBO bibo;
    private final Preferences baseline;

    public MasterChef(BIBO bibo) {
        this(bibo, new Preferences());
    }

    // baseline: session context (ATR, protocol, reader name); serve()/cook() prefs layer on top.
    public MasterChef(BIBO bibo, Preferences baseline) {
        this.bibo = bibo;
        this.baseline = baseline;
    }

    @Override
    public <T> Dish<T> serve(Recipe<T> recipe, Preferences prefs) {
        // Empty baseline passes prefs through by reference, preserving identity.
        return SousChef.serve(recipe, baseline.isEmpty() ? prefs : baseline.merge(prefs), this::transmit);
    }

    // Transmit commands; an expectation mismatch stops transmission early.
    // The taster owns the verdict, judged from the responses received so far.
    private <T> Verdict<T> transmit(Ingredients<T> ing, Preferences prefs) {
        var responses = new ArrayList<ResponseAPDU>(ing.commands().size());
        for (int i = 0; i < ing.commands().size(); i++) {
            var response = bibo.transmit(ing.commands().get(i));
            responses.add(response);
            if (!ing.expected().isEmpty()) {
                var exp = ing.expected().get(i);
                // SW must match; data only when the expectation carries data
                if (response.getSW() != exp.getSW()
                        || (exp.getData().length > 0 && !Arrays.equals(response.getData(), exp.getData()))) {
                    break;
                }
            }
        }
        return ing.taster().taste(List.copyOf(responses), prefs);
    }
}
