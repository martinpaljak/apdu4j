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

import apdu4j.prefs.Preferences;

/**
 * The plated result of a {@link Recipe} execution: the produced value together
 * with all {@link Preferences} accumulated during the recipe chain.
 *
 * <p>Returned by {@link Chef#serve}. This is the APDU-construction counterpart
 * of {@link apdu4j.bibosa.BIBOSA}, which pairs transport with accumulated
 * middleware preferences on the transport axis.
 *
 * <p>Example: extracting session metadata contributed by a recipe via
 * {@link Verdict.NextStep}:
 * <pre>{@code
 * Dish<byte[]> dish = chef.serve(initUpdateRecipe, prefs);
 * String sessionId = dish.preferences().valueOf(SESSION_ID).orElseThrow();
 * }</pre>
 *
 * @param value       the recipe's result
 * @param preferences accumulated preferences from the recipe chain
 * @param <T>         the result type
 * @see Chef#serve
 * @see Chef#cook
 */
public record Dish<T>(T value, Preferences preferences) {
}
