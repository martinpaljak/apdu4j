// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.prefs.Preferences;

/**
 * The plated result of a {@link Recipe} execution: the produced value together
 * with all {@link Preferences} accumulated during the recipe chain.
 *
 * <p>Returned by {@link Chef#serve}. This is the APDU-construction counterpart
 * of {@link apdu4j.core.BIBOSA}, which pairs transport with accumulated
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
