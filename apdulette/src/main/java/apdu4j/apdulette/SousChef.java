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

import apdu4j.apdulette.PreparationStep.Ingredients;
import apdu4j.apdulette.PreparationStep.Premade;
import apdu4j.apdulette.Verdict.Error;
import apdu4j.apdulette.Verdict.NextStep;
import apdu4j.apdulette.Verdict.Ready;
import apdu4j.core.APDUBIBO;
import apdu4j.core.BIBO;
import apdu4j.core.CommandAPDU;
import apdu4j.core.ResponseAPDU;
import apdu4j.prefs.Preferences;

import java.util.ArrayList;
import java.util.List;

// Real-time executor: transmits APDUs over BIBO, trampoline loop
public final class SousChef implements Chef {
    static final int MAX_ITERATIONS = 10_000;
    private final APDUBIBO bibo;

    public SousChef(BIBO bibo) {
        this.bibo = new APDUBIBO(bibo);
    }

    public SousChef(APDUBIBO bibo) {
        this.bibo = bibo;
    }

    @Override
    public <T> Dish<T> serve(Recipe<T> recipe, Preferences prefs) {
        var current = recipe;
        var currentPrefs = prefs;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            switch (current.prepare(currentPrefs)) {
                case Premade<T>(var v) -> {
                    return new Dish<>(v, currentPrefs);
                }
                case Ingredients<T> ing -> {
                    var responses = transmit(ing.commands());
                    switch (ing.taster().apply(responses)) {
                        case Ready<T>(var v) -> {
                            return new Dish<>(v, currentPrefs);
                        }
                        case NextStep<T>(var r, var p) -> {
                            current = r;
                            currentPrefs = currentPrefs.merge(p);
                        }
                        case Error<T>(var reason, var sw) -> throw new SpoiledIngredient(reason, sw);
                    }
                }
            }
        }
        throw new KitchenDisaster("Recipe exceeded " + MAX_ITERATIONS + " iterations");
    }

    private List<ResponseAPDU> transmit(List<CommandAPDU> commands) {
        var responses = new ArrayList<ResponseAPDU>(commands.size());
        for (var cmd : commands) {
            responses.add(bibo.transmit(cmd));
        }
        return List.copyOf(responses);
    }
}
