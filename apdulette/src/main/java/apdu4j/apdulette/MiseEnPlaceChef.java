/*
 * Copyright (c) 2026-present Martin Paljak <martin@martinpaljak.net>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package apdu4j.apdulette;

import apdu4j.apdulette.PreparationStep.Failed;
import apdu4j.apdulette.PreparationStep.Ingredients;
import apdu4j.apdulette.PreparationStep.Premade;
import apdu4j.apdulette.Verdict.NextStep;
import apdu4j.apdulette.Verdict.Ready;
import apdu4j.prefs.Preferences;

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
                case Failed<T>(var reason) -> throw new KitchenDisaster(reason);
                case Ingredients<T> ing -> {
                    if (ing.expected().isEmpty()) {
                        throw new KitchenDisaster("Cannot pre-compute: no expected responses");
                    }
                    switch (ing.taster().apply(ing.expected())) {
                        case Ready<T>(var v, var p) -> {
                            return new Dish<>(v, currentPrefs.merge(p));
                        }
                        case NextStep<T>(var r, var p) -> {
                            current = r;
                            currentPrefs = currentPrefs.merge(p);
                        }
                        case Verdict.Error<T> err -> throw new KitchenDisaster(
                                "%s (SW=%04X)".formatted(err.message(), err.sw()));
                    }
                }
            }
        }
        throw new KitchenDisaster("Recipe exceeded " + SousChef.MAX_ITERATIONS + " iterations");
    }
}
